package com.dailyactivity.file.api;

import com.dailyactivity.auth.api.AuthSupport;
import com.dailyactivity.file.application.MerchantLogoService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class MerchantLogoController {
    private final MerchantLogoService logos;

    public MerchantLogoController(MerchantLogoService logos) {
        this.logos = logos;
    }

    @PostMapping(value = "/platform/merchant-logos", consumes = "multipart/form-data")
    public Map<String, String> upload(@RequestPart("file") MultipartFile file, HttpServletRequest request) {
        return logos.upload(AuthSupport.principal(request, "platform"), file);
    }

    @GetMapping("/files/merchant-logos/{uuid}/content")
    public ResponseEntity<Resource> content(@PathVariable String uuid) {
        return logos.content(uuid);
    }
}
