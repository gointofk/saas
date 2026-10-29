package com.dailyactivity.auth.api;

import com.dailyactivity.auth.application.AccountService;
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
    private final TrustedClientIpResolver clientIps;

    public AuthController(AccountService accounts, TrustedClientIpResolver clientIps) {
        this.accounts = accounts;
        this.clientIps = clientIps;
    }

    @PostMapping("/customer/auth/login")
    public Map<String, Object> customerLogin(@Valid @RequestBody CustomerLoginRequest request, HttpServletRequest httpRequest) {
        return accounts.customerLogin(request.email(), request.password(), clientIps.resolve(httpRequest));
    }

    @PostMapping("/admin/auth/login")
    public Map<String, Object> sellerLogin(@Valid @RequestBody SellerLoginRequest request, HttpServletRequest httpRequest) {
        return accounts.sellerLogin(request.account(), request.password(), clientIps.resolve(httpRequest));
    }

    @PostMapping("/{area:customer|admin}/auth/logout")
    public ResponseEntity<Void> logout(@PathVariable String area, HttpServletRequest request) {
        accounts.logout(AuthSupport.principal(request, "customer".equals(area) ? "customer" : "seller"));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{area:customer|admin}/me")
    public Map<String, Object> me(@PathVariable String area, HttpServletRequest request) {
        return accounts.me(AuthSupport.principal(request, "customer".equals(area) ? "customer" : "seller"));
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

    public record SellerLoginRequest(@NotBlank String account, @NotBlank String password) {
    }

    public record ProfileRequest(@NotBlank String name) {
    }

    public record PasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword,
                                  @NotBlank String confirmation) {
    }
}
