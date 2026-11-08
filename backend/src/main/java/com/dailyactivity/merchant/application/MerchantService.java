package com.dailyactivity.merchant.application;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.auth.infrastructure.PasswordHasher;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.common.PageResponse;
import com.dailyactivity.common.RequestValidation;
import com.dailyactivity.merchant.domain.FreeShippingThresholds;
import com.dailyactivity.merchant.domain.ShippingFees;
import com.dailyactivity.identity.domain.port.MemberRepository;
import com.dailyactivity.merchant.domain.port.MerchantRepository;
import com.dailyactivity.merchant.domain.port.MerchantEmailSettingsRepository;
import com.dailyactivity.merchant.domain.port.MerchantEmailSender;
import com.dailyactivity.merchant.domain.port.MerchantSecretProtector;
import com.dailyactivity.commission.domain.port.CommissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
public class MerchantService {
    private final MerchantRepository merchants;
    private final MemberRepository members;
    private final AccountService accounts;
    private final MerchantEmailSettingsRepository emailSettings;
    private final MerchantSecretProtector secretCipher;
    private final MerchantEmailSender emailSender;
    private final CommissionRepository commissions;
    public MerchantService(MerchantRepository merchants, MemberRepository members, AccountService accounts,
                           MerchantEmailSettingsRepository emailSettings, MerchantSecretProtector secretCipher,
                           MerchantEmailSender emailSender, CommissionRepository commissions) {
        this.merchants=merchants; this.members=members; this.accounts=accounts;
        this.emailSettings=emailSettings; this.secretCipher=secretCipher; this.emailSender=emailSender;
        this.commissions=commissions;
    }
    @Transactional(readOnly=true)
    public PageResponse<MerchantView> page(Principal p, Integer page) {
        accounts.requirePlatformAdmin(p);
        int current=RequestValidation.page(page);
        return PageResponse.of(merchants.page(current).stream().map(this::view).toList(),current,merchants.count());
    }
    @Transactional
    public MerchantView create(Principal p, Create r) {
        accounts.requirePlatformAdmin(p);
        FreeShippingThresholds thresholds = r.freeShippingThresholds()==null
                ? FreeShippingThresholds.disabled() : r.freeShippingThresholds();
        validate(r.name(),r.shippingFees(),thresholds);
        String domain = StorefrontService.normalizeDomain(r.domain());
        StorefrontService.validateBranding(r.logoUrl(),r.theme());
        boolean registrationEnabled=Boolean.TRUE.equals(r.registrationEnabled());
        String registrationDefaultTier=normalizeRegistrationDefaultTier(r.registrationDefaultTier());
        boolean commissionEnabled=r.commissionEnabled()==null || r.commissionEnabled();
        boolean cryptoEnabled=r.cryptoEnabled()==null || r.cryptoEnabled();
        boolean onlinePaymentLinkEnabled=Boolean.TRUE.equals(r.onlinePaymentLinkEnabled());
        PreparedEmailSettings mail=prepareEmailSettings(r.mailSettings(),null,registrationEnabled);
        if(registrationEnabled) requireUsableMailSettings(mail,null);
        if (r.code()==null || !r.code().matches("[A-Za-z0-9_-]{2,32}"))
            throw ApiException.badRequest("INVALID_MERCHANT_CODE","Merchant code must contain 2–32 letters, digits, underscores or hyphens.");
        if (r.adminAccount()==null || r.adminAccount().isBlank() || r.adminAccount().trim().length()>64 || r.adminName()==null || r.adminName().isBlank() || r.adminName().trim().length()>100)
            throw ApiException.badRequest("INVALID_MEMBER","Initial administrator account and name are required.");
        if (r.password()==null || !r.password().equals(r.confirmation()))
            throw ApiException.badRequest("PASSWORD_CONFIRMATION_MISMATCH","Password confirmation does not match.");
        if (r.password().length()<12 || r.password().length()>64)
            throw ApiException.badRequest("INVALID_PASSWORD","Password must be 12–64 characters.");
        String hash=PasswordHasher.hash(r.password());
        long id=merchants.create(r.code().toUpperCase(Locale.ROOT),r.name().trim(),r.shippingFees(),thresholds,
                domain,r.logoUrl(),r.theme(),p.userId(),registrationEnabled,registrationDefaultTier,
                commissionEnabled,cryptoEnabled,onlinePaymentLinkEnabled);
        if(mail!=null) emailSettings.save(id,mail.settings(),mail.encryptedPassword());
        var permissions=List.of("order_audit","orders","commissions","bills","customers","products","certificates","categories","payment_channels","members");
        members.createMerchantAdministrator(id,new MemberRepository.MemberInput(r.adminAccount().trim().toLowerCase(Locale.ROOT),r.adminName().trim(),hash,permissions),p.actor());
        merchants.audit(id,p.actor(),"merchant_created","Domain: "+domain+", theme="+r.theme()+", logo="+r.logoUrl()+", shippingFees="+r.shippingFees()+", freeShippingThresholds="+thresholds+", customerRegistrationEnabled="+registrationEnabled+", registrationDefaultTier="+registrationDefaultTier+", commissionEnabled="+commissionEnabled+", cryptoEnabled="+cryptoEnabled+", onlinePaymentLinkEnabled="+onlinePaymentLinkEnabled+", emailSettingsUpdated="+(mail!=null));
        return view(merchants.find(id).orElseThrow());
    }
    @Transactional
    public MerchantView update(Principal p,long id,Update r) {
        accounts.requirePlatformAdmin(p);
        String domain = StorefrontService.normalizeDomain(r.domain());
        StorefrontService.validateBranding(r.logoUrl(),r.theme());
        var existing=merchants.lock(id);
        checkVersion(existing,r.expectedUpdatedAt());
        FreeShippingThresholds thresholds = r.freeShippingThresholds()==null
                ? existing.freeShippingThresholds() : r.freeShippingThresholds();
        validate(r.name(),r.shippingFees(),thresholds);
        boolean commissionEnabled=r.commissionEnabled()==null?existing.commissionEnabled():r.commissionEnabled();
        boolean cryptoEnabled=r.cryptoEnabled()==null?existing.cryptoEnabled():r.cryptoEnabled();
        boolean onlinePaymentLinkEnabled=r.onlinePaymentLinkEnabled()==null?existing.onlinePaymentLinkEnabled():r.onlinePaymentLinkEnabled();
        if (existing.commissionEnabled() && !commissionEnabled) {
            CommissionRepository.CommissionDisableReadiness readiness=commissions.commissionDisableReadiness(id);
            if (!readiness.canDisable()) {
                throw ApiException.conflict("COMMISSION_CLOSE_BLOCKED", "Settle " + readiness.outstandingAccrualCount()
                        + " outstanding commission record(s) totaling " + readiness.outstandingAmount().toPlainString()
                        + " and review " + readiness.pendingEligibleOrderCount() + " commission-eligible order(s) before disabling commissions.");
            }
        }
        boolean resetPassword = (r.password()!=null && !r.password().isEmpty())
                || (r.confirmation()!=null && !r.confirmation().isEmpty());
        MemberRepository.InitialAdministrator administrator = null;
        String passwordHash = null;
        if (resetPassword) {
            if (r.password()==null || !r.password().equals(r.confirmation()))
                throw ApiException.badRequest("PASSWORD_CONFIRMATION_MISMATCH","Password confirmation does not match.");
            if (r.password().isBlank() || r.password().length()<12 || r.password().length()>64)
                throw ApiException.badRequest("INVALID_PASSWORD","Password must be 12–64 characters.");
            administrator=members.initialAdministrator(id).orElseThrow(()->ApiException.notFound("Merchant administrator not found."));
            passwordHash=PasswordHasher.hash(r.password());
        }

        boolean registrationEnabled=r.registrationEnabled()==null?existing.registrationEnabled():r.registrationEnabled();
        String registrationDefaultTier=r.registrationDefaultTier()==null?existing.registrationDefaultTier():normalizeRegistrationDefaultTier(r.registrationDefaultTier());
        var stored=emailSettings.find(id).orElse(null);
        PreparedEmailSettings mail=prepareEmailSettings(r.mailSettings(),stored,registrationEnabled);
        if(registrationEnabled) requireUsableMailSettings(mail,stored);

        merchants.update(id,r.name().trim(),r.shippingFees(),thresholds,r.active(),p.userId());
        if(registrationEnabled!=existing.registrationEnabled()) merchants.updateRegistrationEnabled(id,registrationEnabled,p.userId());
        else if(r.registrationEnabled()!=null) merchants.updateRegistrationEnabled(id,registrationEnabled,p.userId());
        if(!registrationDefaultTier.equals(existing.registrationDefaultTier()) || r.registrationDefaultTier()!=null)
            merchants.updateRegistrationDefaultTier(id,registrationDefaultTier,p.userId());
        if(commissionEnabled!=existing.commissionEnabled() || r.commissionEnabled()!=null)
            merchants.updateCommissionEnabled(id,commissionEnabled,p.userId());
        if(cryptoEnabled!=existing.cryptoEnabled() || onlinePaymentLinkEnabled!=existing.onlinePaymentLinkEnabled()
                || r.cryptoEnabled()!=null || r.onlinePaymentLinkEnabled()!=null)
            merchants.updatePaymentMethods(id,cryptoEnabled,onlinePaymentLinkEnabled,p.userId());
        if(mail!=null) emailSettings.save(id,mail.settings(),mail.encryptedPassword());
        merchants.branding(id,domain,r.logoUrl(),r.theme());
        if (resetPassword) {
            members.resetPassword(id,administrator.id(),passwordHash,p.actor());
            merchants.audit(id,p.actor(),"merchant_admin_password_reset","Administrator seller ID: "+administrator.id());
        }
        merchants.audit(id,p.actor(),"merchant_updated","Before: "+existing+"; after: name="+r.name()+", active="+r.active()+", shippingFees="+r.shippingFees()+", freeShippingThresholds="+thresholds+", domain="+domain+", theme="+r.theme()+", logo="+r.logoUrl()+", customerRegistrationEnabled="+registrationEnabled+", registrationDefaultTier="+registrationDefaultTier+", commissionEnabled="+commissionEnabled+", cryptoEnabled="+cryptoEnabled+", onlinePaymentLinkEnabled="+onlinePaymentLinkEnabled+", emailSettingsUpdated="+(mail!=null));
        return view(merchants.find(id).orElseThrow());
    }
    private void checkVersion(MerchantRepository.Merchant m,Instant version) {
        if (version==null || !version.equals(m.updatedAt())) throw ApiException.conflict("MERCHANT_CHANGED","Merchant settings changed. Refresh and try again.");
    }
    private void validate(String name,ShippingFees fees,FreeShippingThresholds thresholds) {
        if (name==null || name.isBlank() || name.trim().length()>100)
            throw ApiException.badRequest("INVALID_MERCHANT","Check the merchant name.");
        if (fees==null) throw ApiException.badRequest("INVALID_SHIPPING_FEES","Configure shipping fees for all customer tiers.");
        fees.values().forEach(this::validateFee);
        if (thresholds==null) throw ApiException.badRequest("INVALID_FREE_SHIPPING_THRESHOLDS","Check free shipping thresholds for all customer tiers.");
        thresholds.values().stream().filter(Objects::nonNull).forEach(this::validateThreshold);
    }
    private void validateFee(BigDecimal fee) {
        if (fee==null || fee.signum()<0 || fee.scale()>2 || fee.compareTo(new BigDecimal("9999999999999999.99"))>0)
            throw ApiException.badRequest("INVALID_SHIPPING_FEE","Shipping fee must be a non-negative amount with at most two decimals.");
    }
    private void validateThreshold(BigDecimal threshold) {
        if (threshold.signum()<=0 || threshold.scale()>2 || threshold.compareTo(new BigDecimal("9999999999999999.99"))>0)
            throw ApiException.badRequest("INVALID_FREE_SHIPPING_THRESHOLD","Free shipping threshold must be greater than zero and have at most two decimals.");
    }
    private String normalizeRegistrationDefaultTier(String tier) {
        String normalized=tier==null?"V1":tier.trim().toUpperCase(Locale.ROOT);
        if(!Set.of("V1","V2","V3","V4","V5","V6").contains(normalized))
            throw ApiException.badRequest("INVALID_REGISTRATION_DEFAULT_TIER","Choose a valid default customer pricing tier.");
        return normalized;
    }
    private MerchantView view(MerchantRepository.Merchant m) {
        return new MerchantView(m.id(),m.code(),m.name(),m.active(),m.shippingFees(),m.freeShippingThresholds(),m.updatedAt(),m.domain(),m.logoUrl(),m.theme(),m.registrationEnabled(),m.registrationDefaultTier(),m.commissionEnabled(),m.cryptoEnabled(),m.onlinePaymentLinkEnabled(),emailSettings.find(m.id()).map(this::emailView).orElse(null),members.initialAdministrator(m.id()).orElse(null));
    }
    private MerchantEmailSettingsView emailView(MerchantEmailSettingsRepository.StoredSettings s) {
        return new MerchantEmailSettingsView(s.smtpHost(),s.smtpPort(),s.smtpUsername(),s.tlsMode(),s.senderEmail(),s.senderName(),s.configured());
    }
    private PreparedEmailSettings prepareEmailSettings(MailSettingsInput input,MerchantEmailSettingsRepository.StoredSettings existing,boolean enabled) {
        if(input==null) return null;
        boolean hasInput=notBlank(input.smtpHost())||input.smtpPort()!=null||notBlank(input.smtpUsername())||notBlank(input.smtpPassword())||notBlank(input.tlsMode())||notBlank(input.senderEmail())||notBlank(input.senderName());
        if(!hasInput) return null;
        String host=input.smtpHost()==null?null:input.smtpHost().trim();
        String username=input.smtpUsername()==null?null:input.smtpUsername().trim();
        String tls=input.tlsMode()==null?null:input.tlsMode().trim().toLowerCase(Locale.ROOT);
        String from=input.senderEmail()==null?null:input.senderEmail().trim().toLowerCase(Locale.ROOT);
        String senderName=input.senderName()==null?null:input.senderName().trim();
        if(host==null||host.length()>253||!host.matches("[A-Za-z0-9.-]+")||input.smtpPort()==null||input.smtpPort()<1||input.smtpPort()>65535
                ||username==null||username.isBlank()||username.length()>255||!Set.of("starttls","ssl").contains(tls)
                ||!validEmail(from)||senderName==null||senderName.isBlank()||senderName.length()>100
                ||(notBlank(input.smtpPassword())&&input.smtpPassword().length()>255))
            throw ApiException.badRequest("INVALID_MERCHANT_EMAIL_SETTINGS","Check the SMTP host, port, account, TLS mode, and sender details.");
        String encrypted=existing==null?null:existing.encryptedPassword();
        if(notBlank(input.smtpPassword())) encrypted=secretCipher.encrypt(input.smtpPassword());
        var settings=new MerchantEmailSettingsRepository.Settings(host,input.smtpPort(),username,tls,from,senderName);
        var candidate=new MerchantEmailSettingsRepository.StoredSettings(host,input.smtpPort(),username,encrypted,tls,from,senderName);
        if(enabled&&!candidate.configured()) throw ApiException.badRequest("MERCHANT_EMAIL_REQUIRED","Configure SMTP credentials before enabling customer registration.");
        if(enabled) secretCipher.decrypt(encrypted);
        return new PreparedEmailSettings(settings,encrypted);
    }
    private void requireUsableMailSettings(PreparedEmailSettings prepared,MerchantEmailSettingsRepository.StoredSettings existing) {
        var settings=prepared==null?existing:new MerchantEmailSettingsRepository.StoredSettings(prepared.settings().smtpHost(),prepared.settings().smtpPort(),prepared.settings().smtpUsername(),prepared.encryptedPassword(),prepared.settings().tlsMode(),prepared.settings().senderEmail(),prepared.settings().senderName());
        if(settings==null||!settings.configured()) throw ApiException.badRequest("MERCHANT_EMAIL_REQUIRED","Configure SMTP credentials before enabling customer registration.");
        secretCipher.decrypt(settings.encryptedPassword());
    }
    private boolean validEmail(String email) { return email!=null&&email.length()<=254&&email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"); }
    private boolean notBlank(String value) { return value!=null&&!value.isBlank(); }
    @Transactional(readOnly = true)
    public void testEmail(Principal p,long id,String recipient) {
        accounts.requirePlatformAdmin(p);
        if(!merchants.find(id).isPresent()) throw ApiException.notFound("Merchant not found.");
        if(!validEmail(recipient)) throw ApiException.badRequest("INVALID_EMAIL","Enter a valid test recipient email address.");
        var stored=emailSettings.find(id).filter(MerchantEmailSettingsRepository.StoredSettings::configured).orElseThrow(()->ApiException.badRequest("MERCHANT_EMAIL_REQUIRED","Configure SMTP credentials before sending a test email."));
        String password=secretCipher.decrypt(stored.encryptedPassword());
        try {
            emailSender.send(new MerchantEmailSender.SmtpConfiguration(stored.smtpHost(),stored.smtpPort(),stored.smtpUsername(),password,stored.tlsMode(),stored.senderEmail(),stored.senderName()),recipient.trim(),"DailyActivity email configuration test","This message confirms that the merchant email configuration can send mail.");
        } catch(RuntimeException e) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,"EMAIL_TEST_FAILED","The test email could not be sent. Check the SMTP settings.");
        }
    }
    private record PreparedEmailSettings(MerchantEmailSettingsRepository.Settings settings,String encryptedPassword) {}
    public record TestEmailRequest(String recipient) {}
    public record MerchantEmailSettingsView(String smtpHost,int smtpPort,String smtpUsername,String tlsMode,String senderEmail,String senderName,boolean passwordConfigured) {}
    public record MerchantView(long id,String code,String name,boolean active,ShippingFees shippingFees,FreeShippingThresholds freeShippingThresholds,Instant updatedAt,String domain,String logoUrl,String theme,boolean registrationEnabled,String registrationDefaultTier,boolean commissionEnabled,boolean cryptoEnabled,boolean onlinePaymentLinkEnabled,MerchantEmailSettingsView mailSettings,MemberRepository.InitialAdministrator administrator) {}
    public record MailSettingsInput(String smtpHost,Integer smtpPort,String smtpUsername,String smtpPassword,String tlsMode,String senderEmail,String senderName) {}
    public record Create(String code,String name,ShippingFees shippingFees,FreeShippingThresholds freeShippingThresholds,String adminAccount,String adminName,String password,String confirmation,String domain,String logoUrl,String theme,Boolean registrationEnabled,MailSettingsInput mailSettings,Boolean commissionEnabled,Boolean cryptoEnabled,Boolean onlinePaymentLinkEnabled,String registrationDefaultTier) {
        public Create(String code,String name,ShippingFees shippingFees,String adminAccount,String adminName,String password,String confirmation,String domain,String logoUrl,String theme,Boolean registrationEnabled,MailSettingsInput mailSettings,Boolean commissionEnabled,Boolean cryptoEnabled,Boolean onlinePaymentLinkEnabled,String registrationDefaultTier) {
            this(code,name,shippingFees,null,adminAccount,adminName,password,confirmation,domain,logoUrl,theme,registrationEnabled,mailSettings,commissionEnabled,cryptoEnabled,onlinePaymentLinkEnabled,registrationDefaultTier);
        }
        public Create(String code,String name,ShippingFees shippingFees,String adminAccount,String adminName,String password,String confirmation,String domain,String logoUrl,String theme,Boolean registrationEnabled,MailSettingsInput mailSettings,Boolean commissionEnabled,Boolean cryptoEnabled,Boolean onlinePaymentLinkEnabled) {
            this(code,name,shippingFees,null,adminAccount,adminName,password,confirmation,domain,logoUrl,theme,registrationEnabled,mailSettings,commissionEnabled,cryptoEnabled,onlinePaymentLinkEnabled,"V1");
        }
        public Create(String code,String name,ShippingFees shippingFees,String adminAccount,String adminName,String password,String confirmation,String domain,String logoUrl,String theme,Boolean registrationEnabled,MailSettingsInput mailSettings,Boolean commissionEnabled) {
            this(code,name,shippingFees,null,adminAccount,adminName,password,confirmation,domain,logoUrl,theme,registrationEnabled,mailSettings,commissionEnabled,null,null,"V1");
        }
        public Create(String code,String name,ShippingFees shippingFees,String adminAccount,String adminName,String password,String confirmation,String domain,String logoUrl,String theme,Boolean registrationEnabled,MailSettingsInput mailSettings) {
            this(code,name,shippingFees,null,adminAccount,adminName,password,confirmation,domain,logoUrl,theme,registrationEnabled,mailSettings,true,null,null,"V1");
        }
    }
    public record Update(String name,boolean active,ShippingFees shippingFees,FreeShippingThresholds freeShippingThresholds,Instant expectedUpdatedAt,String domain,String logoUrl,String theme,Boolean registrationEnabled,MailSettingsInput mailSettings,String password,String confirmation,Boolean commissionEnabled,Boolean cryptoEnabled,Boolean onlinePaymentLinkEnabled,String registrationDefaultTier) {
        public Update(String name,boolean active,ShippingFees shippingFees,Instant expectedUpdatedAt,String domain,String logoUrl,String theme,Boolean registrationEnabled,MailSettingsInput mailSettings,String password,String confirmation,Boolean commissionEnabled,Boolean cryptoEnabled,Boolean onlinePaymentLinkEnabled,String registrationDefaultTier) {
            this(name,active,shippingFees,null,expectedUpdatedAt,domain,logoUrl,theme,registrationEnabled,mailSettings,password,confirmation,commissionEnabled,cryptoEnabled,onlinePaymentLinkEnabled,registrationDefaultTier);
        }
        public Update(String name,boolean active,ShippingFees shippingFees,Instant expectedUpdatedAt,String domain,String logoUrl,String theme,Boolean registrationEnabled,MailSettingsInput mailSettings,String password,String confirmation,Boolean commissionEnabled,Boolean cryptoEnabled,Boolean onlinePaymentLinkEnabled) {
            this(name,active,shippingFees,null,expectedUpdatedAt,domain,logoUrl,theme,registrationEnabled,mailSettings,password,confirmation,commissionEnabled,cryptoEnabled,onlinePaymentLinkEnabled,null);
        }
        public Update(String name,boolean active,ShippingFees shippingFees,Instant expectedUpdatedAt,String domain,String logoUrl,String theme,Boolean registrationEnabled,MailSettingsInput mailSettings,String password,String confirmation,Boolean commissionEnabled) {
            this(name,active,shippingFees,null,expectedUpdatedAt,domain,logoUrl,theme,registrationEnabled,mailSettings,password,confirmation,commissionEnabled,null,null,null);
        }
        public Update(String name,boolean active,ShippingFees shippingFees,Instant expectedUpdatedAt,String domain,String logoUrl,String theme,Boolean registrationEnabled,MailSettingsInput mailSettings) {
            this(name,active,shippingFees,null,expectedUpdatedAt,domain,logoUrl,theme,registrationEnabled,mailSettings,null,null,null,null,null,null);
        }
        public Update(String name,boolean active,ShippingFees shippingFees,Instant expectedUpdatedAt,String domain,String logoUrl,String theme) {
            this(name,active,shippingFees,null,expectedUpdatedAt,domain,logoUrl,theme,null,null,null,null,null,null,null,null);
        }
        public Update(String name,boolean active,ShippingFees shippingFees,Instant expectedUpdatedAt,String domain,String logoUrl,String theme,Boolean registrationEnabled,MailSettingsInput mailSettings,String password,String confirmation) {
            this(name,active,shippingFees,null,expectedUpdatedAt,domain,logoUrl,theme,registrationEnabled,mailSettings,password,confirmation,null,null,null,null);
        }
    }
}
