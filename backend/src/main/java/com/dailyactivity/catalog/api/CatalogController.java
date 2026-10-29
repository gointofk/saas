package com.dailyactivity.catalog.api;

import com.dailyactivity.auth.api.AuthSupport;
import com.dailyactivity.auth.api.RequireMenu;
import com.dailyactivity.catalog.application.CatalogService;
import com.dailyactivity.common.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    private final CatalogService service;
    public CatalogController(CatalogService service) { this.service = service; }
    @GetMapping("/customer/categories") public List<Map<String,Object>> categories(HttpServletRequest request) { AuthSupport.principal(request, "customer"); return service.customerCategories(); }
    @GetMapping("/customer/products") public List<Map<String,Object>> products(HttpServletRequest request) { return service.customerProducts(AuthSupport.principal(request, "customer")); }
    @GetMapping("/admin/categories") public List<Map<String,Object>> adminCategories(HttpServletRequest request) { return service.adminCategories(AuthSupport.principal(request, "seller")); }
    @RequireMenu("categories")
    @PostMapping("/admin/categories") public Map<String,Object> createCategory(@Valid @RequestBody CatalogService.CategoryRequest body, HttpServletRequest request) { return service.createCategory(AuthSupport.principal(request, "seller"), body); }
    @RequireMenu("categories")
    @PutMapping("/admin/categories/{id}") public void updateCategory(@PathVariable long id, @Valid @RequestBody CatalogService.CategoryRequest body, HttpServletRequest request) { service.updateCategory(AuthSupport.principal(request, "seller"), id, body); }
    @RequireMenu("categories")
    @DeleteMapping("/admin/categories/{id}") public void deleteCategory(@PathVariable long id, HttpServletRequest request) { service.deleteCategory(AuthSupport.principal(request, "seller"), id); }
    @RequireMenu("products")
    @GetMapping("/admin/products") public PageResponse<Map<String,Object>> adminProducts(@RequestParam(required=false) String q, @RequestParam(required=false) Long categoryId, @RequestParam(required=false) String productType, @RequestParam(required=false) Boolean active, @RequestParam(required=false) String sortBy, @RequestParam(required=false) String sortDirection, @RequestParam(required=false) Integer page, HttpServletRequest request) { return service.adminProducts(AuthSupport.principal(request, "seller"), q, categoryId, productType, active, sortBy, sortDirection, page); }
    @RequireMenu("products")
    @PostMapping("/admin/products") public Map<String,Object> createProduct(@Valid @RequestBody CatalogService.ProductRequest body, HttpServletRequest request) { return service.createProduct(AuthSupport.principal(request, "seller"), body); }
    @RequireMenu("products")
    @PutMapping("/admin/products/{id}") public void updateProduct(@PathVariable long id, @Valid @RequestBody CatalogService.ProductRequest body, HttpServletRequest request) { service.updateProduct(AuthSupport.principal(request, "seller"), id, body); }
    @RequireMenu("products")
    @PatchMapping("/admin/products/{id}/status") public void status(@PathVariable long id, @RequestBody CatalogService.ProductStatusRequest body, HttpServletRequest request) { service.setProductActive(AuthSupport.principal(request, "seller"), id, Boolean.TRUE.equals(body.active()), body.expectedUpdatedAt()); }
    @RequireMenu("products")
    @DeleteMapping("/admin/products/{id}") public void deleteProduct(@PathVariable long id, @RequestBody(required = false) CatalogService.ProductVersionRequest body, HttpServletRequest request) { service.deleteProduct(AuthSupport.principal(request, "seller"), id, body == null ? null : body.expectedUpdatedAt()); }
    @RequireMenu("products")
    @PostMapping("/admin/products/{id}/stock-adjustments") public Map<String,Object> stock(@PathVariable long id, @Valid @RequestBody CatalogService.StockRequest body, HttpServletRequest request) { return service.adjustStock(AuthSupport.principal(request, "seller"), id, body); }
    @RequireMenu("products")
    @GetMapping("/admin/products/{id}/stock-movements") public PageResponse<Map<String,Object>> movements(@PathVariable long id, @RequestParam(required=false) Integer page, HttpServletRequest request) { return service.stockMovements(AuthSupport.principal(request, "seller"), id, page); }
}
