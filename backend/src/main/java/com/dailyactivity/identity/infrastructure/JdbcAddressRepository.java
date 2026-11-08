package com.dailyactivity.identity.infrastructure;

import com.dailyactivity.common.ApiException;
import com.dailyactivity.identity.domain.port.AddressRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcAddressRepository implements AddressRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAddressRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Address> findByCustomer(long customerId) {
        return jdbc.query(select() + " AND customer_id=:customerId ORDER BY is_default DESC,created_at,id", new MapSqlParameterSource("customerId", customerId), (rs, row) -> address(rs));
    }

    @Override
    public Optional<Address> findByIdForUpdate(long customerId, long id) {
        return jdbc.query(select() + " AND customer_id=:customerId AND id=:id FOR UPDATE", new MapSqlParameterSource().addValue("customerId", customerId).addValue("id", id), rows -> rows.next() ? Optional.of(address(rows)) : Optional.empty());
    }

    @Override
    public long create(long customerId, AddressInput input, long actorId) {
        if (input.isDefault()) clearDefault(customerId, actorId);
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO customer_address(customer_id,recipient_name,street,apt,city,region,postal_code,phone,is_default,created_by_user_id,updated_by_user_id) VALUES(:customerId,:name,:street,:apt,:city,:region,:postalCode,:phone,:isDefault,:actor,:actor)", params(customerId, input, actorId), holder, new String[]{"id"});
        return holder.getKey().longValue();
    }

    @Override
    public void update(long customerId, long id, AddressInput input, long actorId) {
        findByIdForUpdate(customerId, id).orElseThrow(() -> ApiException.notFound("Address not found."));
        if (input.isDefault()) clearDefault(customerId, actorId);
        int rows = jdbc.update("UPDATE customer_address SET recipient_name=:name,street=:street,apt=:apt,city=:city,region=:region,postal_code=:postalCode,phone=:phone,is_default=:isDefault,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id AND customer_id=:customerId AND deleted=0", params(customerId, input, actorId).addValue("id", id));
        if (rows != 1) throw ApiException.notFound("Address not found.");
    }

    @Override
    public void remove(long customerId, long id, long actorId) {
        Address address = findByIdForUpdate(customerId, id).orElseThrow(() -> ApiException.notFound("Address not found."));
        int rows = jdbc.update("UPDATE customer_address SET deleted=id,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id AND customer_id=:customerId AND deleted=0", new MapSqlParameterSource().addValue("id", id).addValue("customerId", customerId).addValue("actor", actorId));
        if (rows != 1) return;
        if (address.isDefault()) {
            jdbc.update("UPDATE customer_address SET is_default=1,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=(SELECT chosen.id FROM (SELECT id FROM customer_address WHERE customer_id=:customerId AND deleted=0 ORDER BY created_at,id LIMIT 1) chosen)", new MapSqlParameterSource().addValue("customerId", customerId).addValue("actor", actorId));
        }
    }

    @Override
    public void makeDefault(long customerId, long id, long actorId) {
        findByIdForUpdate(customerId, id).orElseThrow(() -> ApiException.notFound("Address not found."));
        clearDefault(customerId, actorId);
        int rows = jdbc.update("UPDATE customer_address SET is_default=1,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id AND customer_id=:customerId AND deleted=0", new MapSqlParameterSource().addValue("id", id).addValue("customerId", customerId).addValue("actor", actorId));
        if (rows != 1) throw ApiException.notFound("Address not found.");
    }

    private void clearDefault(long customerId, long actorId) {
        jdbc.update("UPDATE customer_address SET is_default=0,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE customer_id=:customerId AND deleted=0", new MapSqlParameterSource().addValue("customerId", customerId).addValue("actor", actorId));
    }

    private String select() {
        return "SELECT id,recipient_name,street,apt,city,region,postal_code,phone,is_default FROM customer_address WHERE deleted=0";
    }

    private MapSqlParameterSource params(long customerId, AddressInput i, long actorId) {
        return new MapSqlParameterSource().addValue("customerId", customerId).addValue("name", i.name()).addValue("street", i.street()).addValue("apt", i.apt()).addValue("city", i.city()).addValue("region", i.region()).addValue("postalCode", i.postalCode()).addValue("phone", i.phone()).addValue("isDefault", i.isDefault()).addValue("actor", actorId);
    }

    private Address address(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Address(rs.getLong("id"), rs.getString("recipient_name"), rs.getString("street"), rs.getString("apt"), rs.getString("city"), rs.getString("region"), rs.getString("postal_code"), rs.getString("phone"), rs.getBoolean("is_default"));
    }
}
