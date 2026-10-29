package com.dailyactivity.identity.api;

import com.dailyactivity.auth.api.AuthSupport;
import com.dailyactivity.identity.application.AddressService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/customer/addresses")
public class AddressController {
    private final AddressService service;

    public AddressController(AddressService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest r) {
        return service.list(AuthSupport.principal(r, "customer"));
    }

    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody AddressService.AddressRequest b, HttpServletRequest r) {
        return service.create(AuthSupport.principal(r, "customer"), b);
    }

    @PutMapping("/{id}")
    public void update(@PathVariable long id, @Valid @RequestBody AddressService.AddressRequest b, HttpServletRequest r) {
        service.update(AuthSupport.principal(r, "customer"), id, b);
    }

    @DeleteMapping("/{id}")
    public void remove(@PathVariable long id, HttpServletRequest r) {
        service.remove(AuthSupport.principal(r, "customer"), id);
    }

    @PutMapping("/{id}/default")
    public void defaultAddress(@PathVariable long id, HttpServletRequest r) {
        service.makeDefault(AuthSupport.principal(r, "customer"), id);
    }
}
