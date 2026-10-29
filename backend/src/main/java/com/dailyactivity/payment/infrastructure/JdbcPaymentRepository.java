package com.dailyactivity.payment.infrastructure;

import com.dailyactivity.common.ApiException;
import com.dailyactivity.payment.domain.port.PaymentRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcPaymentRepository implements PaymentRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPaymentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private String select() {
        return "SELECT id,name,currency_code,network,receiving_address,instructions,additional_rate,qr_file_uuid,active,sort_no FROM payment_channel WHERE merchant_id=:merchantId AND deleted=0";
    }

    @Override
    public List<Channel> find(long merchantId, boolean activeOnly) {
        return jdbc.query(select() + (activeOnly ? " AND active=1" : "") + " ORDER BY sort_no,id", new MapSqlParameterSource().addValue("merchantId", merchantId), (rs, row) -> channel(rs));
    }

    @Override
    public List<Channel> page(long merchantId, boolean activeOnly, int page) {
        return jdbc.query(select() + (activeOnly ? " AND active=1" : "") + " ORDER BY sort_no,id LIMIT 10 OFFSET :offset", new MapSqlParameterSource("offset", (page - 1) * 10).addValue("merchantId", merchantId), (rs, row) -> channel(rs));
    }

    @Override
    public long count(long merchantId, boolean activeOnly) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM payment_channel WHERE merchant_id=:merchantId AND deleted=0" + (activeOnly ? " AND active=1" : ""), new MapSqlParameterSource().addValue("merchantId", merchantId), Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public Optional<Channel> channel(long merchantId, long id, boolean activeOnly) {
        return jdbc.query(select() + " AND id=:id" + (activeOnly ? " AND active=1" : ""), new MapSqlParameterSource("id", id).addValue("merchantId", merchantId), rows -> rows.next() ? Optional.of(channel(rows)) : Optional.empty());
    }

    @Override
    public long create(long merchantId, ChannelInput i, long actor) {
        try {
            KeyHolder h = new GeneratedKeyHolder();
            jdbc.update("INSERT INTO payment_channel(merchant_id,name,currency_code,network,receiving_address,instructions,additional_rate,qr_file_uuid,active,sort_no,created_by_user_id,updated_by_user_id) VALUES(:merchantId,:name,:currencyCode,:network,:address,:instructions,:additionalRate,:qr,:active,:sortNo,:actor,:actor)", params(i, actor).addValue("merchantId", merchantId), h, new String[]{"id"});
            return h.getKey().longValue();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("PAYMENT_CHANNEL_EXISTS", "Payment channel name is already in use.");
        }
    }

    @Override
    public void update(long merchantId, long id, ChannelInput i, long actor) {
        try {
            int n = jdbc.update("UPDATE payment_channel SET name=:name,currency_code=:currencyCode,network=:network,receiving_address=:address,instructions=:instructions,additional_rate=:additionalRate,qr_file_uuid=:qr,active=:active,sort_no=:sortNo,updated_by_user_id=:actor,updated_at=UTC_TIMESTAMP(6) WHERE merchant_id=:merchantId AND id=:id AND deleted=0", params(i, actor).addValue("merchantId", merchantId).addValue("id", id));
            if (n != 1) throw ApiException.notFound("Payment channel not found.");
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("PAYMENT_CHANNEL_EXISTS", "Payment channel name is already in use.");
        }
    }

    @Override
    public void softDelete(long merchantId, long id, long actor) {
        int n = jdbc.update("UPDATE payment_channel SET deleted=id,updated_by_user_id=:actor,updated_at=UTC_TIMESTAMP(6) WHERE merchant_id=:merchantId AND id=:id AND deleted=0", new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("id", id).addValue("actor", actor));
        if (n != 1) throw ApiException.notFound("Payment channel not found.");
    }

    private MapSqlParameterSource params(ChannelInput i, long actor) {
        return new MapSqlParameterSource().addValue("name", i.name()).addValue("currencyCode", i.currencyCode()).addValue("network", i.network()).addValue("address", i.receivingAddress()).addValue("instructions", i.instructions()).addValue("additionalRate", i.additionalRate()).addValue("qr", i.qrFileUuid()).addValue("active", i.active()).addValue("sortNo", i.sortNo()).addValue("actor", actor);
    }

    private Channel channel(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Channel(r.getLong("id"), r.getString("name"), r.getString("currency_code"), r.getString("network"), r.getString("receiving_address"), r.getString("instructions"), r.getBigDecimal("additional_rate"), r.getString("qr_file_uuid"), r.getBoolean("active"), r.getInt("sort_no"));
    }
}
