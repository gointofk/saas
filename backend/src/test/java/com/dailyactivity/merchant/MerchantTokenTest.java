package com.dailyactivity.merchant;

import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.auth.infrastructure.JwtKeyProvider;
import com.dailyactivity.auth.infrastructure.JwtTokenService;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.config.AuthProperties;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MerchantTokenTest {
    private final AuthProperties properties = new AuthProperties("test", "customer", "admin", 1, 0,
            UUID.randomUUID().toString(), 300, 10);
    private final JwtKeyProvider keys = new JwtKeyProvider(properties);
    private final JwtTokenService tokens = new JwtTokenService(keys, properties);

    @Test
    void issuedTokenPreservesAccountAndMerchantIdentity() {
        Principal principal = new Principal(12, "seller", 3, 7);
        assertThat(tokens.parse(tokens.issue(principal).accessToken())).isEqualTo(principal);
    }

    @Test
    void correctlySignedLegacyTokenWithoutMerchantIsRejected() {
        String token = Jwts.builder().issuer("test").audience().add("admin").and().subject("user:12")
                .claim("userType", "seller").claim("authVersion", 3)
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(keys.secretKey(), Jwts.SIG.HS256).compact();
        assertThatThrownBy(() -> tokens.parse(token)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("UNAUTHORIZED");
    }
}
