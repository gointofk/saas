package com.dailyactivity.file.api;

import com.dailyactivity.auth.api.AuthSupport;
import com.dailyactivity.file.application.FileService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class FileController {
    private final FileService service;

    public FileController(FileService service) {
        this.service = service;
    }

    @PostMapping(value = "/admin/files", consumes = "multipart/form-data")
    public Map<String, Object> upload(@RequestPart("file") MultipartFile file, @RequestParam String purpose, @RequestParam(required = false) Long orderId, @RequestParam(required = false) String stage, @RequestParam(required = false) String parcelUuid, HttpServletRequest r) {
        return service.upload(AuthSupport.principal(r, "seller"), file, purpose, orderId, stage, parcelUuid);
    }

    @GetMapping("/admin/files/{uuid}/signed-url")
    public Map<String, String> signed(@PathVariable String uuid, HttpServletRequest r) {
        return Map.of("url", service.signedUrl(uuid, AuthSupport.principal(r, "seller")));
    }

    @GetMapping("/customer/files/{uuid}/signed-url")
    public Map<String, String> customerSigned(@PathVariable String uuid, HttpServletRequest r) {
        return Map.of("url", service.customerSignedUrl(uuid, AuthSupport.principal(r, "customer")));
    }

    @GetMapping("/public/coas/{uuid}")
    public ResponseEntity<Resource> publicCoa(@PathVariable String uuid) {
        return service.publicCoaContent(uuid);
    }

    @GetMapping("/files/{uuid}/content")
    public ResponseEntity<Resource> content(@PathVariable String uuid, @RequestParam long expires, @RequestParam String signature) {
        return service.content(uuid, expires, signature);
    }
}
