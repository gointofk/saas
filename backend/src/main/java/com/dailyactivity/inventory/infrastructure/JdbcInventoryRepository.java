package com.dailyactivity.inventory.infrastructure;

import com.dailyactivity.common.ApiException;
import com.dailyactivity.inventory.domain.port.InventoryRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcInventoryRepository implements InventoryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcInventoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean adjust(long productId, int targetStock, String reason, String operationId, long actorId) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", productId);
        Integer before = jdbc.query("SELECT stock_available FROM product WHERE id=:id AND deleted=0 FOR UPDATE", p, rows -> rows.next() ? rows.getInt(1) : null);
        if (before == null) throw ApiException.notFound("Product not found.");
        int delta = targetStock - before;
        if (delta == 0) return true;
        jdbc.update("UPDATE product SET stock_available=:target,updated_by_user_id=:actor,updated_at=UTC_TIMESTAMP(6) WHERE id=:id AND deleted=0", p.addValue("target", targetStock).addValue("actor", actorId));
        jdbc.update("INSERT INTO stock_movement(merchant_id,product_id,movement_type,quantity_delta,quantity_before,quantity_after,reason,operation_id,created_by_user_id) VALUES((SELECT merchant_id FROM `user` WHERE id=:actor),:id,'manual_adjust',:delta,:before,:after,:reason,:operationId,:actor)", p.addValue("delta", delta).addValue("before", before).addValue("after", targetStock).addValue("reason", reason).addValue("operationId", operationId).addValue("actor", actorId));
        return true;
    }

    @Override
    public boolean decrement(long productId, int quantity, long actorId, String operationId, long orderId) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", productId);
        Integer before = jdbc.query("SELECT stock_available FROM product WHERE id=:id AND deleted=0 FOR UPDATE", p, rows -> rows.next() ? rows.getInt(1) : null);
        if (before == null || before < quantity) return false;
        jdbc.update("UPDATE product SET stock_available=stock_available-:quantity,updated_by_user_id=:actor,updated_at=UTC_TIMESTAMP(6) WHERE id=:id AND deleted=0", p.addValue("quantity", quantity).addValue("actor", actorId));
        jdbc.update("INSERT INTO stock_movement(merchant_id,product_id,order_id,movement_type,quantity_delta,quantity_before,quantity_after,operation_id,created_by_user_id) VALUES((SELECT merchant_id FROM `user` WHERE id=:actor),:id,:orderId,'order_create',:delta,:before,:after,:operationId,:actor)", p.addValue("orderId", orderId).addValue("delta", -quantity).addValue("before", before).addValue("after", before - quantity).addValue("operationId", operationId).addValue("actor", actorId));
        return true;
    }

    @Override
    public void restore(long productId, int quantity, String reason, String operationId, long orderId, long actorId) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", productId);
        Integer before = jdbc.query("SELECT stock_available FROM product WHERE id=:id FOR UPDATE", p, rows -> rows.next() ? rows.getInt(1) : null);
        if (before == null) throw ApiException.notFound("Product not found.");
        jdbc.update("UPDATE product SET stock_available=stock_available+:quantity,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id", p.addValue("quantity", quantity).addValue("actor", actorId));
        jdbc.update("INSERT INTO stock_movement(merchant_id,product_id,order_id,movement_type,quantity_delta,quantity_before,quantity_after,reason,operation_id,created_by_user_id) VALUES((SELECT merchant_id FROM `user` WHERE id=:actor),:id,:orderId,'order_cancel',:quantity,:before,:after,:reason,:operationId,:actor)", p.addValue("orderId", orderId).addValue("before", before).addValue("after", before + quantity).addValue("reason", reason).addValue("operationId", operationId).addValue("actor", actorId));
    }

    @Override
    public List<StockMovement> movements(long merchantId, long productId, int page) {
        return jdbc.query("SELECT id,quantity_delta,quantity_before,quantity_after,movement_type,reason,operation_id,created_at FROM stock_movement WHERE merchant_id=:merchantId AND product_id=:productId ORDER BY created_at DESC,id DESC LIMIT 10 OFFSET :offset", new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("productId", productId).addValue("offset", (page - 1) * 10), (rs, row) -> new StockMovement(rs.getLong("id"), rs.getInt("quantity_delta"), rs.getInt("quantity_before"), rs.getInt("quantity_after"), rs.getString("movement_type"), rs.getString("reason"), rs.getString("operation_id"), rs.getTimestamp("created_at").toInstant()));
    }

    @Override
    public long movementCount(long merchantId, long productId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stock_movement WHERE merchant_id=:merchantId AND product_id=:productId", new MapSqlParameterSource("productId", productId).addValue("merchantId", merchantId), Long.class);
    }
}
