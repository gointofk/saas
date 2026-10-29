package com.dailyactivity.auth.application;

import com.dailyactivity.auth.domain.Account;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.auth.domain.port.AccountRepository;
import com.dailyactivity.auth.infrastructure.JwtTokenService;
import com.dailyactivity.auth.infrastructure.PasswordHasher;
import com.dailyactivity.common.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
public class AccountService {
    private final com.dailyactivity.merchant.domain.port.MerchantRepository merchants;
    private final AccountRepository users;
    private final JwtTokenService tokens;
    private final LoginRateLimiter loginRateLimiter;

    @Autowired
    public AccountService(AccountRepository users, JwtTokenService tokens, LoginRateLimiter loginRateLimiter, com.dailyactivity.merchant.domain.port.MerchantRepository merchants) {
        this.merchants = merchants;
        this.users = users;
        this.tokens = tokens;
        this.loginRateLimiter = loginRateLimiter;
    }

    @Transactional
    public Map<String, Object> customerLogin(long merchantId, String email, String password) {
        return customerLogin(merchantId, email, password, null);
    }

    @Transactional
    public Map<String, Object> customerLogin(long merchantId, String email, String password, String clientIp) {
        String identifier = normalize(email);
        loginRateLimiter.check("customer:" + merchantId, identifier, clientIp);
        Account account = users.findCustomerByEmail(merchantId, identifier).orElse(null);
        if (account == null || !account.active() || !accountMerchantActive(account) || !PasswordHasher.matches(password, account.passwordHash())) {
            loginRateLimiter.recordFailure("customer:" + merchantId, identifier, clientIp);
            throw ApiException.unauthorized("Email or password is incorrect.");
        }
        loginRateLimiter.recordSuccess("customer:" + merchantId, identifier);
        users.touchLastLogin(account.userType(), account.id(), Instant.now());
        return loginResponse(account, tokens.issue(new Principal(account.id(), account.userType(), account.authVersion(), account.merchantId())));
    }

    @Transactional
    public Map<String, Object> sellerLogin(String accountName, String password) {
        return sellerLogin(accountName, password, null);
    }

    @Transactional
    public Map<String, Object> sellerLogin(String accountName, String password, String clientIp) {
        String identifier = normalize(accountName);
        loginRateLimiter.check("admin", identifier, clientIp);
        Account account = users.findSellerByAccount(identifier).orElse(null);
        if (account == null || !account.active() || !accountMerchantActive(account) || !PasswordHasher.matches(password, account.passwordHash())) {
            loginRateLimiter.recordFailure("admin", identifier, clientIp);
            throw ApiException.unauthorized("Account or password is incorrect.");
        }
        loginRateLimiter.recordSuccess("admin", identifier);
        users.touchLastLogin(account.userType(), account.id(), Instant.now());
        return loginResponse(account, tokens.issue(new Principal(account.id(), account.userType(), account.authVersion(), account.merchantId())));
    }

    @Transactional(readOnly = true)
    public void assertValid(Principal principal) {
        Account account = users.findActiveById(principal.userType(), principal.userId()).orElseThrow(() -> ApiException.unauthorized("Your account is no longer active."));
        if (!account.userType().equals(principal.userType()) || account.authVersion() != principal.authVersion() || account.merchantId() != principal.merchantId() || !accountMerchantActive(account))
            throw ApiException.unauthorized("Your access token is no longer valid.");
    }

    @Transactional
    public void logout(Principal principal) {
        Account locked = users.findByIdForUpdate(principal.userType(), principal.userId()).orElseThrow(() -> ApiException.unauthorized("Account not found."));
        if (locked.authVersion() != principal.authVersion()) return;
        if (!users.incrementAuthVersion(locked.userType(), locked.id(), locked.authVersion()))
            throw ApiException.conflict("AUTH_VERSION_CHANGED", "The account changed while signing out.");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> me(Principal principal) {
        assertValid(principal);
        Account account = users.findActiveById(principal.userType(), principal.userId()).orElseThrow(() -> ApiException.notFound("Account not found."));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userType", account.userType());
        if (!"platform".equals(account.userType())) result.put("merchant", merchants.find(account.merchantId()).orElseThrow());
        if (account.customer()) result.put("customer", customerView(account));
        else if (account.seller()) {
            result.put("merchantPermissions", merchants.permissions(account.merchantId()));
            result.put("member", sellerView(account));
            result.put("permissions", effectivePermissions(account));
        }
        if ("platform".equals(account.userType())) result.put("member",sellerView(account));
        return result;
    }

    @Transactional(readOnly = true)
    public Account customer(Principal principal) {
        return requireType(principal, "customer");
    }

    @Transactional
    public Account customerForUpdate(Principal principal) {
        if (!merchants.lock(principal.merchantId()).active()) throw ApiException.unauthorized("Merchant is inactive.");
        if (!"customer".equals(principal.userType()))
            throw ApiException.forbidden("A customer account is required.");
        Account account = users.findByIdForUpdate(principal.userType(), principal.userId())
                .filter(Account::customer)
                .filter(Account::active)
                .orElseThrow(() -> ApiException.unauthorized("Account not found or inactive."));
        if (account.authVersion() != principal.authVersion() || account.merchantId() != principal.merchantId() || !accountMerchantActive(account))
            throw ApiException.unauthorized("Your access token is no longer valid.");
        return account;
    }

    @Transactional(readOnly = true)
    public Account seller(Principal principal) {
        return requireType(principal, "seller");
    }

    public String customerName(long id) {
        return users.findCustomerById(id).map(Account::name).orElseThrow(() -> ApiException.badRequest("INVALID_REFERRER", "Referrer is not a customer."));
    }

    @Transactional
    public Map<String, Object> updateCustomerProfile(Principal principal, String name) {
        Account account = customer(principal);
        if (name == null || name.isBlank() || name.length() > 100)
            throw ApiException.badRequest("INVALID_NAME", "name is required.");
        if (!users.updateCustomerName(account.id(), name.trim(), account.id()))
            throw ApiException.notFound("Account not found.");
        return customerView(users.findActiveById("customer", account.id()).orElseThrow(() -> ApiException.notFound("Account not found.")));
    }

    @Transactional
    public void changeCustomerPassword(Principal principal, String oldPassword, String newPassword, String confirmation) {
        Account account = customer(principal);
        if (!PasswordHasher.matches(oldPassword, account.passwordHash()))
            throw ApiException.badRequest("OLD_PASSWORD_INVALID", "Current password is incorrect.");
        if (newPassword == null || !newPassword.equals(confirmation))
            throw ApiException.badRequest("PASSWORD_CONFIRMATION_MISMATCH", "New password confirmation does not match.");
        if (!users.updateCustomerPassword(account.id(), PasswordHasher.hash(newPassword), account.id()))
            throw ApiException.notFound("Account not found.");
    }

    @Transactional(readOnly = true)
    public void requireMenu(Principal principal, String menu) {
        Account account = seller(principal);
        Set<String> permissions = effectivePermissions(account);
        if (!permissions.contains(menu))
            throw ApiException.forbidden("This member does not have the " + menu + " menu permission.");
    }

    @Transactional(readOnly = true)
    public void requireAnyMenu(Principal principal, String... menus) {
        Account account = seller(principal);
        Set<String> permissions = effectivePermissions(account);
        if (menus == null || menus.length == 0 || java.util.Arrays.stream(menus).noneMatch(permissions::contains))
            throw ApiException.forbidden("This member does not have permission to access this resource.");
    }

    private Account requireType(Principal principal, String type) {
        if (!type.equals(principal.userType())) throw ApiException.forbidden("A " + type + " account is required.");
        assertValid(principal);
        return users.findActiveById(principal.userType(), principal.userId()).filter(a -> a.userType().equals(type)).orElseThrow(() -> ApiException.notFound("Account not found."));
    }

    private Map<String, Object> loginResponse(Account account, JwtTokenService.IssuedToken token) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accessToken", token.accessToken());
        if (!"platform".equals(account.userType())) result.put("merchant", merchants.find(account.merchantId()).orElseThrow());
        result.put("expiresAt", token.expiresAt());
        if (account.customer()) result.put("customer", customerView(account));
        else result.put("member", sellerView(account));
        return result;
    }

    private boolean accountMerchantActive(Account account) {
        return "platform".equals(account.userType()) ? account.merchantId() == 0 : merchantActive(account.merchantId());
    }

    @Transactional
    public Map<String, Object> platformLogin(String accountName, String password, String clientIp) {
        String identifier = normalize(accountName);
        loginRateLimiter.check("platform", identifier, clientIp);
        Account account = users.findPlatformByAccount(identifier).orElse(null);
        if (account == null || !account.active() || !PasswordHasher.matches(password, account.passwordHash())) {
            loginRateLimiter.recordFailure("platform", identifier, clientIp);
            throw ApiException.unauthorized("Account or password is incorrect.");
        }
        loginRateLimiter.recordSuccess("platform", identifier);
        users.touchLastLogin(account.userType(), account.id(), Instant.now());
        return loginResponse(account, tokens.issue(new Principal(account.id(), "platform", account.authVersion(), 0)));
    }

    private boolean merchantActive(long id) {
        return merchants.find(id).map(com.dailyactivity.merchant.domain.port.MerchantRepository.Merchant::active).orElse(false);
    }

    public Set<String> effectivePermissions(Account account) {
        var permissions = new java.util.HashSet<>(users.findPermissions(account.id()));
        var granted = merchants.permissions(account.merchantId());
        permissions.removeIf(menu -> com.dailyactivity.merchant.domain.port.MerchantRepository.PUBLIC_MENUS.contains(menu) && !granted.contains(menu));
        return permissions;
    }

    public void requirePlatformAdmin(Principal p) {
        if (!"platform".equals(p.userType())) throw ApiException.forbidden("Platform administrator is required.");
        assertValid(p);
    }

    public void validateMemberPermissions(Principal p, java.util.List<String> permissions) {
        var allowed = merchants.permissions(p.merchantId());
        var menus = Set.of("order_audit", "orders", "commissions", "bills", "customers", "products", "certificates", "categories", "payment_channels", "members");
        if (permissions == null || permissions.stream().anyMatch(menu -> menu == null || !menus.contains(menu)
                || com.dailyactivity.merchant.domain.port.MerchantRepository.PUBLIC_MENUS.contains(menu) && !allowed.contains(menu)))
            throw ApiException.forbidden("A permission exceeds this merchant's authorization.");
    }

    public java.math.BigDecimal shippingFee(Principal p) {
        assertValid(p);
        return merchants.find(p.merchantId()).orElseThrow().shippingFee();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public Map<String, Object> customerView(Account a) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", a.id());
        value.put("userCode", a.code());
        value.put("name", a.name());
        value.put("email", a.email());
        value.put("active", a.active());
        value.put("lastLogin", a.lastLoginAt());
        return value;
    }

    public Map<String, Object> sellerView(Account a) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", a.id());
        value.put("account", a.account());
        value.put("name", a.name());
        value.put("active", a.active());
        value.put("lastLogin", a.lastLoginAt());
        return value;
    }
}
