package com.dailyactivity.identity.infrastructure;

import com.dailyactivity.identity.domain.port.CustomerRegistrationRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class JdbcCustomerRegistrationRepository implements CustomerRegistrationRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCustomerRegistrationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean issue(long merchantId, String email, String codeHash, Instant now, Instant expiresAt, Instant resendAfter) {
        MapSqlParameterSource p = new MapSqlParameterSource("merchantId", merchantId).addValue("email", email)
                .addValue("hash", codeHash).addValue("now", Timestamp.from(now))
                .addValue("expires", Timestamp.from(expiresAt)).addValue("resendAfter", Timestamp.from(resendAfter));
        jdbc.update("INSERT INTO customer_registration_challenge(merchant_id,email,code_hmac,expires_at,resend_after,attempt_count,consumed_at) "
                        + "VALUES(:merchantId,:email,:hash,:expires,:resendAfter,0,NULL) "
                        + "ON DUPLICATE KEY UPDATE "
                        + "code_hmac=IF(resend_after<=:now,VALUES(code_hmac),code_hmac),"
                        + "expires_at=IF(resend_after<=:now,VALUES(expires_at),expires_at),"
                        + "attempt_count=IF(resend_after<=:now,0,attempt_count),"
                        + "consumed_at=IF(resend_after<=:now,NULL,consumed_at),"
                        + "updated_at=IF(resend_after<=:now,UTC_TIMESTAMP(6),updated_at),"
                        + "resend_after=IF(resend_after<=:now,VALUES(resend_after),resend_after)", p);
        String saved = jdbc.queryForObject("SELECT code_hmac FROM customer_registration_challenge WHERE merchant_id=:merchantId AND email=:email", p, String.class);
        return codeHash.equals(saved);
    }

    @Override
    public Optional<Challenge> lock(long merchantId, String email) {
        return jdbc.query("SELECT code_hmac,expires_at,resend_after,attempt_count,consumed_at FROM customer_registration_challenge WHERE merchant_id=:merchantId AND email=:email FOR UPDATE",
                new MapSqlParameterSource("merchantId", merchantId).addValue("email", email), rs -> {
                    if (!rs.next()) return Optional.empty();
                    Timestamp consumed = rs.getTimestamp("consumed_at");
                    return Optional.of(new Challenge(rs.getString("code_hmac"), rs.getTimestamp("expires_at").toInstant(),
                            rs.getTimestamp("resend_after").toInstant(), rs.getInt("attempt_count"),
                            consumed == null ? null : consumed.toInstant()));
                });
    }

    @Override
    public void recordFailedAttempt(long merchantId, String email, int maxAttempts) {
        jdbc.update("UPDATE customer_registration_challenge SET consumed_at=IF(attempt_count+1>=:maxAttempts,UTC_TIMESTAMP(6),consumed_at),"
                        + "attempt_count=attempt_count+1,updated_at=UTC_TIMESTAMP(6) "
                        + "WHERE merchant_id=:merchantId AND email=:email AND consumed_at IS NULL",
                new MapSqlParameterSource("merchantId", merchantId).addValue("email", email).addValue("maxAttempts", maxAttempts));
    }

    @Override
    public void consume(long merchantId, String email) {
        jdbc.update("UPDATE customer_registration_challenge SET consumed_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) WHERE merchant_id=:merchantId AND email=:email AND consumed_at IS NULL",
                new MapSqlParameterSource("merchantId", merchantId).addValue("email", email));
    }

    @Override
    public void revoke(long merchantId, String email, String codeHash) {
        jdbc.update("UPDATE customer_registration_challenge SET consumed_at=UTC_TIMESTAMP(6),resend_after=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) WHERE merchant_id=:merchantId AND email=:email AND code_hmac=:hash AND consumed_at IS NULL",
                new MapSqlParameterSource("merchantId", merchantId).addValue("email", email).addValue("hash", codeHash));
    }

    @Override
    public int deleteExpired(Instant before, int limit) {
        return jdbc.update("DELETE FROM customer_registration_challenge WHERE expires_at<:before LIMIT :limit",
                new MapSqlParameterSource("before", Timestamp.from(before)).addValue("limit", limit));
    }
}
