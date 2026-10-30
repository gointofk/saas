package com.dailyactivity.auth.api;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.identity.application.CustomerRegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AccountService accounts;
    private final com.dailyactivity.merchant.application.StorefrontService storefront;
    private final TrustedClientIpResolver clientIps;
    private final CustomerRegistrationService registrations;

    public AuthController(AccountService accounts, TrustedClientIpResolver clientIps, com.dailyactivity.merchant.application.StorefrontService storefront, CustomerRegistrationService registrations) {
        this.storefront = storefront;
        this.accounts = accounts;
        this.clientIps = clientIps;
        this.registrations = registrations;
    }

    @PostMapping("/customer/auth/login")
    public Map<String, Object> customerLogin(@Valid @RequestBody CustomerLoginRequest request, HttpServletRequest httpRequest) {
        return accounts.customerLogin(storefront.resolve(httpRequest).id(), request.email(), request.password(), clientIps.resolve(httpRequest));
    }

    @PostMapping("/customer/auth/registration-code")
    public Map<String, String> registrationCode(@Valid @RequestBody RegistrationCodeRequest request, HttpServletRequest httpRequest) {
        long merchantId = storefront.resolve(httpRequest).id();
        registrations.requestCode(merchantId, request.email(), clientIps.resolve(httpRequest));
        return Map.of("message", "If registration is available for this email, a verification code will be sent.");
    }

    @PostMapping("/customer/auth/register")
    public ResponseEntity<Map<String, Boolean>> register(@Valid @RequestBody CustomerRegistrationRequest request, HttpServletRequest httpRequest) {
        long merchantId = storefront.resolve(httpRequest).id();
        registrations.register(merchantId, request.email(), request.name(), request.password(), request.confirmation(), request.verificationCode(), request.inviteCode(), clientIps.resolve(httpRequest));
        return ResponseEntity.status(201).body(Map.of("registered", true));
    }

    @PostMapping("/admin/auth/login")
    public Map<String, Object> sellerLogin(@Valid @RequestBody SellerLoginRequest request, HttpServletRequest httpRequest) {
        return accounts.sellerLogin(request.account(), request.password(), clientIps.resolve(httpRequest));
    }

    @PostMapping("/platform/auth/login")
    public Map<String,Object> platformLogin(@Valid @RequestBody SellerLoginRequest body,HttpServletRequest request) {
        return accounts.platformLogin(body.account(),body.password(),clientIps.resolve(request));
    }

    @PostMapping("/{area:customer|admin|platform}/auth/logout")
    public ResponseEntity<Void> logout(@PathVariable String area, HttpServletRequest request) {
        accounts.logout(AuthSupport.principal(request, "platform".equals(area) ? "platform" : "customer".equals(area) ? "customer" : "seller"));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{area:customer|admin|platform}/me")
    public Map<String, Object> me(@PathVariable String area, HttpServletRequest request) {
        return accounts.me(AuthSupport.principal(request, "platform".equals(area) ? "platform" : "customer".equals(area) ? "customer" : "seller"));
    }

    @PatchMapping("/customer/me/profile")
    public Map<String, Object> profile(@Valid @RequestBody ProfileRequest body, HttpServletRequest request) {
        return accounts.updateCustomerProfile(AuthSupport.principal(request, "customer"), body.name());
    }

    @PutMapping("/customer/me/password")
    public void password(@Valid @RequestBody PasswordRequest body, HttpServletRequest request) {
        accounts.changeCustomerPassword(AuthSupport.principal(request, "customer"), body.oldPassword(), body.newPassword(), body.confirmation());
    }

    public record CustomerLoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record RegistrationCodeRequest(@NotBlank String email) {
    }

    public record CustomerRegistrationRequest(@NotBlank String email, @NotBlank String name,
                                              @NotBlank String password, @NotBlank String confirmation,
                                              @NotBlank String verificationCode, String inviteCode) {
    }

    public record SellerLoginRequest(@NotBlank String account, @NotBlank String password) {
    }

    public record ProfileRequest(@NotBlank String name) {
    }

    public record PasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword,
                                  @NotBlank String confirmation) {
    }
}
