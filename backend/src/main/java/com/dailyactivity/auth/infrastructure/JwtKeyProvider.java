package com.dailyactivity.auth.infrastructure;

import com.dailyactivity.config.AuthProperties;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;

@Component
public class JwtKeyProvider {
    private final SecretKey secretKey;

    public JwtKeyProvider(AuthProperties properties) {
        this.secretKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public SecretKey secretKey() {
        return secretKey;
    }
}
