package com.dailyactivity.merchant.api;
import com.dailyactivity.merchant.application.StorefrontService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/v1/customer/storefront")
public class StorefrontController {
    private final StorefrontService storefront;
    public StorefrontController(StorefrontService storefront) { this.storefront=storefront; }
    @GetMapping
    public ResponseEntity<Brand> get(HttpServletRequest request) {
        var m=storefront.resolve(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Brand(m.name(),m.logoUrl(),m.theme(),m.registrationEnabled(),m.commissionEnabled()));
    }
    public record Brand(String name,String logoUrl,String theme,boolean registrationEnabled,boolean commissionEnabled) {}
}
