package com.dailyactivity.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handle(ApiException exception, HttpServletRequest request) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(exception.status());
        if (exception.retryAfterSeconds() != null) builder.header("Retry-After", String.valueOf(exception.retryAfterSeconds()));
        return builder.body(ErrorResponse.of(exception, request.getRequestURI(), MDC.get(HttpTrafficLoggingFilter.REQUEST_ID_MDC_KEY)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException exception,
                                                          HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "VALIDATION_ERROR");
        body.put("message", "Please check the submitted fields.");
        body.put("fields", fields);
        body.put("path", request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> typeMismatch(MethodArgumentTypeMismatchException exception,
                                                            HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "VALIDATION_ERROR");
        body.put("message", "Please check the submitted fields.");
        body.put("fields", Map.of(exception.getName(), "Invalid value."));
        body.put("path", request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> missingHeader(MissingRequestHeaderException exception,
                                                             HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "VALIDATION_ERROR");
        body.put("message", "Required request header is missing.");
        body.put("fields", Map.of(exception.getHeaderName(), "This header is required."));
        body.put("path", request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception exception, HttpServletRequest request) {
        String requestId = MDC.get(HttpTrafficLoggingFilter.REQUEST_ID_MDC_KEY);
        log.error("Unhandled MVC exception requestId={} method={} path={}", requestId, request.getMethod(), request.getRequestURI(), exception);
        return ResponseEntity.internalServerError().body(new ErrorResponse(
                "INTERNAL_ERROR", "An unexpected error occurred.", request.getRequestURI(), java.time.Instant.now(), requestId));
    }
}
