package com.dailyactivity.auth.infrastructure;

import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;

@Service
public class JwtTokenService {
    private final JwtKeyProvider keys;
    private final AuthProperties properties;

    public JwtTokenService(JwtKeyProvider keys, AuthProperties properties) {
        this.keys = keys;
        this.properties = properties;
    }

    public IssuedToken issue(Principal principal) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(properties.tokenDays() * 86_400L);
        String audience = principal.customer() ? properties.audienceCustomer() : properties.audienceAdmin();
        String token = Jwts.builder().issuer(properties.issuer()).audience().add(audience).and().subject("user:" + principal.userId())
                .claim("merchantId", principal.merchantId()).claim("userType", principal.userType()).claim("authVersion", principal.authVersion())
                .issuedAt(Date.from(issuedAt)).expiration(Date.from(expiresAt)).signWith(keys.secretKey(), Jwts.SIG.HS256).compact();
        return new IssuedToken(token, expiresAt);
    }

    public Principal parse(String token) {
        try {
            Jws<Claims> signed = Jwts.parser().verifyWith(keys.secretKey()).requireIssuer(properties.issuer()).clockSkewSeconds(properties.clockSkewSeconds()).build().parseSignedClaims(token);
            Claims claims = signed.getPayload();
            String type = claims.get("userType", String.class);
            if (!"customer".equals(type) && !"seller".equals(type))
                throw ApiException.unauthorized("Token user type is invalid.");
            String expectedAudience = "customer".equals(type) ? properties.audienceCustomer() : properties.audienceAdmin();
            if (claims.getAudience() == null || !claims.getAudience().contains(expectedAudience))
                throw ApiException.unauthorized("Token audience is invalid.");
            String subject = claims.getSubject();
            if (subject == null || !subject.startsWith("user:"))
                throw ApiException.unauthorized("Token subject is invalid.");
            Number version = claims.get("authVersion", Number.class);
            if (version == null) throw ApiException.unauthorized("Token auth version is invalid.");
            Number merchant = claims.get("merchantId", Number.class);
            if (merchant == null || merchant.longValue() < 1) throw ApiException.unauthorized("Token merchant is invalid.");
            return new Principal(Long.parseLong(subject.substring(5)), type, version.longValue(), merchant.longValue());
        } catch (io.jsonwebtoken.JwtException | IllegalArgumentException exception) {
            throw ApiException.unauthorized("Invalid access token.");
        }
    }

    public record IssuedToken(String accessToken, Instant expiresAt) {
    }
}
