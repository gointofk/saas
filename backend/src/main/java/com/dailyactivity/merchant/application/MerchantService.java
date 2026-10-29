package com.dailyactivity.merchant.application;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.auth.infrastructure.PasswordHasher;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.common.PageResponse;
import com.dailyactivity.common.RequestValidation;
import com.dailyactivity.identity.domain.port.MemberRepository;
import com.dailyactivity.merchant.domain.port.MerchantRepository;
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
    public MerchantService(MerchantRepository merchants, MemberRepository members, AccountService accounts) {
        this.merchants=merchants; this.members=members; this.accounts=accounts;
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
        validate(r.name(),r.shippingFee(),r.permissions());
        String domain = StorefrontService.normalizeDomain(r.domain());
        StorefrontService.validateBranding(r.logoUrl(),r.theme());
        if (r.code()==null || !r.code().matches("[A-Za-z0-9_-]{2,32}"))
            throw ApiException.badRequest("INVALID_MERCHANT_CODE","Merchant code must contain 2–32 letters, digits, underscores or hyphens.");
        if (r.adminAccount()==null || r.adminAccount().isBlank() || r.adminAccount().trim().length()>64 || r.adminName()==null || r.adminName().isBlank() || r.adminName().trim().length()>100)
            throw ApiException.badRequest("INVALID_MEMBER","Initial administrator account and name are required.");
        if (r.password()==null || !r.password().equals(r.confirmation()))
            throw ApiException.badRequest("PASSWORD_CONFIRMATION_MISMATCH","Password confirmation does not match.");
        if (r.password().length()<12 || r.password().length()>64)
            throw ApiException.badRequest("INVALID_PASSWORD","Password must be 12–64 characters.");
        String hash=PasswordHasher.hash(r.password());
        long id=merchants.create(r.code().toUpperCase(Locale.ROOT),r.name().trim(),r.shippingFee(),domain,r.logoUrl(),r.theme(),p.userId());
        merchants.replacePermissions(id,new HashSet<>(r.permissions()),p.userId());
        var permissions=new ArrayList<>(List.of("order_audit","orders","commissions","bills","customers","payment_channels","members"));
        permissions.addAll(r.permissions());
        members.create(id,new MemberRepository.MemberInput(r.adminAccount().trim().toLowerCase(Locale.ROOT),r.adminName().trim(),hash,permissions),p.actor());
        merchants.audit(id,p.actor(),"merchant_created","Domain: "+domain+", theme="+r.theme()+", logo="+r.logoUrl()+"; public permissions: "+r.permissions());
        return view(merchants.find(id).orElseThrow());
    }
    @Transactional
    public MerchantView update(Principal p,long id,Update r) {
        accounts.requirePlatformAdmin(p);
        validate(r.name(),r.shippingFee(),r.permissions());
        String domain = StorefrontService.normalizeDomain(r.domain());
        StorefrontService.validateBranding(r.logoUrl(),r.theme());
        var existing=merchants.lock(id);
        checkVersion(existing,r.expectedUpdatedAt());

        merchants.update(id,r.name().trim(),r.shippingFee(),r.active(),p.userId());
        var before=merchants.permissions(id);
        merchants.branding(id,domain,r.logoUrl(),r.theme());
        merchants.replacePermissions(id,new HashSet<>(r.permissions()),p.userId());
        merchants.audit(id,p.actor(),"merchant_updated","Before: "+existing+", permissions="+before+"; after: name="+r.name()+", active="+r.active()+", shippingFee="+r.shippingFee()+", domain="+domain+", theme="+r.theme()+", logo="+r.logoUrl()+", permissions="+r.permissions());
        return view(merchants.find(id).orElseThrow());
    }
    private void checkVersion(MerchantRepository.Merchant m,Instant version) {
        if (version==null || !version.equals(m.updatedAt())) throw ApiException.conflict("MERCHANT_CHANGED","Merchant settings changed. Refresh and try again.");
    }
    private void validate(String name,BigDecimal fee,List<String> permissions) {
        if (name==null || name.isBlank() || name.trim().length()>100 || permissions==null || new HashSet<>(permissions).size()!=permissions.size() || permissions.stream().anyMatch(menu -> menu==null || !MerchantRepository.PUBLIC_MENUS.contains(menu)))
            throw ApiException.badRequest("INVALID_MERCHANT","Check the merchant name and public permissions.");
        validateFee(fee);
    }
    private void validateFee(BigDecimal fee) {
        if (fee==null || fee.signum()<0 || fee.scale()>2 || fee.compareTo(new BigDecimal("9999999999999999.99"))>0)
            throw ApiException.badRequest("INVALID_SHIPPING_FEE","Shipping fee must be a non-negative amount with at most two decimals.");
    }
    private MerchantView view(MerchantRepository.Merchant m) {
        return new MerchantView(m.id(),m.code(),m.name(),m.active(),m.shippingFee(),m.updatedAt(),merchants.permissions(m.id()).stream().sorted().toList(),m.domain(),m.logoUrl(),m.theme());
    }
    public record MerchantView(long id,String code,String name,boolean active,BigDecimal shippingFee,Instant updatedAt,List<String> permissions,String domain,String logoUrl,String theme) {}
    public record Create(String code,String name,BigDecimal shippingFee,List<String> permissions,String adminAccount,String adminName,String password,String confirmation,String domain,String logoUrl,String theme) {}
    public record Update(String name,boolean active,BigDecimal shippingFee,List<String> permissions,Instant expectedUpdatedAt,String domain,String logoUrl,String theme) {}
}
