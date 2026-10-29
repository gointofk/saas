package com.dailyactivity.merchant.infrastructure;

import com.dailyactivity.merchant.domain.port.MerchantEmailSettingsRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JdbcMerchantEmailSettingsRepository implements MerchantEmailSettingsRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcMerchantEmailSettingsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoredSettings> find(long merchantId) {
        return jdbc.query("SELECT smtp_host,smtp_port,smtp_username,smtp_password_ciphertext,tls_mode,sender_email,sender_name FROM merchant_email_settings WHERE merchant_id=:merchantId",
                new MapSqlParameterSource("merchantId", merchantId), rs -> rs.next()
                        ? Optional.of(new StoredSettings(rs.getString("smtp_host"), rs.getInt("smtp_port"),
                        rs.getString("smtp_username"), rs.getString("smtp_password_ciphertext"),
                        rs.getString("tls_mode"), rs.getString("sender_email"), rs.getString("sender_name")))
                        : Optional.empty());
    }

    @Override
    public void save(long merchantId, Settings settings, String encryptedPassword) {
        jdbc.update("INSERT INTO merchant_email_settings(merchant_id,smtp_host,smtp_port,smtp_username,smtp_password_ciphertext,tls_mode,sender_email,sender_name) "
                        + "VALUES(:merchantId,:host,:port,:username,:password,:tls,:senderEmail,:senderName) "
                        + "ON DUPLICATE KEY UPDATE smtp_host=VALUES(smtp_host),smtp_port=VALUES(smtp_port),smtp_username=VALUES(smtp_username),smtp_password_ciphertext=VALUES(smtp_password_ciphertext),tls_mode=VALUES(tls_mode),sender_email=VALUES(sender_email),sender_name=VALUES(sender_name),updated_at=UTC_TIMESTAMP(6)",
                new MapSqlParameterSource("merchantId", merchantId).addValue("host", settings.smtpHost())
                        .addValue("port", settings.smtpPort()).addValue("username", settings.smtpUsername())
                        .addValue("password", encryptedPassword).addValue("tls", settings.tlsMode())
                        .addValue("senderEmail", settings.senderEmail()).addValue("senderName", settings.senderName()));
    }
}
