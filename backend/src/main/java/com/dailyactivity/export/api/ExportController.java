package com.dailyactivity.export.api;

import com.dailyactivity.auth.api.AuthSupport;
import com.dailyactivity.export.application.ExportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
@RestController
@RequestMapping("/api/v1/admin")
public class ExportController {
    private final ExportService exports;

    public ExportController(ExportService exports) {
        this.exports = exports;
    }

    @PostMapping("/exports/{type}")
    public ResponseEntity<byte[]> export(@PathVariable String type,
                                         @RequestBody ExportService.ExportRequest body,
                                         HttpServletRequest request) {
        ExportService.ExportFile file = exports.export(AuthSupport.principal(request, "seller"), type, body);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .contentLength(file.content().length)
                .body(file.content());
    }

    @PostMapping("/exports/{type}/count")
    public ExportService.ExportPreview preview(@PathVariable String type,
                                                @RequestBody ExportService.ExportRequest body,
                                                HttpServletRequest request) {
        return exports.preview(AuthSupport.principal(request, "seller"), type, body);
    }
}
