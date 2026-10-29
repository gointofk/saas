package com.dailyactivity.merchant.api;

import com.dailyactivity.auth.api.AuthSupport;
import com.dailyactivity.common.PageResponse;
import com.dailyactivity.merchant.application.MerchantService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/platform")
public class MerchantController {
    private final MerchantService service;
    public MerchantController(MerchantService service) { this.service=service; }
    @GetMapping("/merchants")
    public PageResponse<MerchantService.MerchantView> page(@RequestParam(required=false) Integer page,HttpServletRequest r) {
        return service.page(AuthSupport.principal(r,"platform"),page);
    }
    @PostMapping("/merchants")
    public MerchantService.MerchantView create(@RequestBody MerchantService.Create body,HttpServletRequest r) {
        return service.create(AuthSupport.principal(r,"platform"),body);
    }
    @PutMapping("/merchants/{id}")
    public MerchantService.MerchantView update(@PathVariable long id,@RequestBody MerchantService.Update body,HttpServletRequest r) {
        return service.update(AuthSupport.principal(r,"platform"),id,body);
    }
}
