package com.dailyactivity.cart.api;

import com.dailyactivity.auth.api.AuthSupport;
import com.dailyactivity.cart.application.CartService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/customer/cart")
public class CartController {
    private final CartService service;

    public CartController(CartService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> get(HttpServletRequest r) {
        return service.get(AuthSupport.principal(r, "customer"));
    }

    @PutMapping("/items/{productId}")
    public Map<String, Object> set(@PathVariable long productId, @RequestBody Map<String, Integer> body, HttpServletRequest r) {
        return service.set(AuthSupport.principal(r, "customer"), productId, body.getOrDefault("quantity", 0));
    }

    @DeleteMapping("/items/{id}")
    public void remove(@PathVariable long id, HttpServletRequest r) {
        service.remove(AuthSupport.principal(r, "customer"), id);
    }
}
