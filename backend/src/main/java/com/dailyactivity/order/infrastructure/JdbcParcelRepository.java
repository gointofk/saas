package com.dailyactivity.order.infrastructure;

import com.dailyactivity.order.domain.port.ParcelRepository;
import com.dailyactivity.common.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

@Repository
public class JdbcParcelRepository implements ParcelRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcParcelRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<ParcelRow> parcels(long orderId) {
        return jdbc.query("SELECT id,order_id,parcel_uuid,parcel_no,status,tracking_no,carrier,packed_at,shipped_at,received_at FROM order_parcel WHERE order_id=:orderId ORDER BY parcel_no,id",
                new MapSqlParameterSource("orderId", orderId), (r, n) -> new ParcelRow(r.getLong("id"), r.getLong("order_id"),
                        r.getString("parcel_uuid"), r.getInt("parcel_no"), r.getString("status"), r.getString("tracking_no"),
                        r.getString("carrier"), instant(r, "packed_at"), instant(r, "shipped_at"), instant(r, "received_at")));
    }

    @Override
    public List<ParcelItemRow> items(long orderId) {
        return jdbc.query("SELECT i.parcel_id,i.order_item_id,i.quantity FROM order_parcel_item i JOIN order_parcel p ON p.id=i.parcel_id WHERE p.order_id=:orderId ORDER BY p.parcel_no,i.id",
                new MapSqlParameterSource("orderId", orderId), (r, n) -> new ParcelItemRow(r.getLong("parcel_id"), r.getLong("order_item_id"), r.getInt("quantity")));
    }

    @Override
    public long insert(long orderId, String uuid, int number, long actor) {
        var key = new GeneratedKeyHolder();
        try {
            jdbc.update("INSERT INTO order_parcel(order_id,parcel_uuid,parcel_no,status,packed_at,created_by_user_id,updated_by_user_id) VALUES(:orderId,:uuid,:number,'packed',UTC_TIMESTAMP(6),:actor,:actor)",
                    new MapSqlParameterSource().addValue("orderId", orderId).addValue("uuid", uuid).addValue("number", number).addValue("actor", actor), key, new String[]{"id"});
        } catch (DuplicateKeyException duplicate) {
            throw ApiException.conflict("PARCEL_ALREADY_EXISTS", "包裹标识或编号已使用，请刷新订单。");
        }
        return key.getKey().longValue();
    }

    @Override
    public void insertItem(long parcelId, long orderItemId, int quantity) {
        jdbc.update("INSERT INTO order_parcel_item(parcel_id,order_item_id,quantity) VALUES(:parcel,:item,:quantity)",
                new MapSqlParameterSource().addValue("parcel", parcelId).addValue("item", orderItemId).addValue("quantity", quantity));
    }

    @Override
    public void ship(long parcelId, String trackingNo, String carrier, long actor) {
        jdbc.update("UPDATE order_parcel SET status='shipped',tracking_no=:tracking,carrier=:carrier,shipped_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id AND status='packed'",
                new MapSqlParameterSource().addValue("id", parcelId).addValue("tracking", trackingNo).addValue("carrier", carrier).addValue("actor", actor));
    }

    @Override
    public void receive(long parcelId, long actor) {
        jdbc.update("UPDATE order_parcel SET status='received',received_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id AND status='shipped'",
                new MapSqlParameterSource().addValue("id", parcelId).addValue("actor", actor));
    }

    @Override
    public void associateEvent(long eventId, long parcelId) {
        jdbc.update("UPDATE order_event SET parcel_id=:parcelId WHERE id=:eventId AND parcel_id IS NULL",
                new MapSqlParameterSource().addValue("parcelId", parcelId).addValue("eventId", eventId));
    }

    private static Instant instant(ResultSet r, String name) throws SQLException {
        var value = r.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
}
