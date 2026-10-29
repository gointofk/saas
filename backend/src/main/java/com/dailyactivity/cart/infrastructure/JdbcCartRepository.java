package com.dailyactivity.cart.infrastructure;

import com.dailyactivity.cart.domain.port.CartRepository;
import com.dailyactivity.common.ApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcCartRepository implements CartRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCartRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private String select() {
        return "SELECT ci.id,ci.product_id,p.sku,p.name,c.name category,p.product_type,p.active,COALESCE(c.active,0) category_active,p.stock_available,ci.quantity,COALESCE(tp.price,p.base_price) unit_price,COALESCE(tp.price,p.base_price)*ci.quantity line_total FROM cart_item ci JOIN product p ON p.id=ci.product_id LEFT JOIN category c ON c.id=p.category_id LEFT JOIN product_tier_price tp ON tp.product_id=p.id AND tp.tier=:tier WHERE ci.customer_id=:customerId";
    }

    @Override
    public List<CartLine> find(long customerId, String tier) {
        return jdbc.query(select() + " ORDER BY ci.id", new MapSqlParameterSource().addValue("customerId", customerId).addValue("tier", tier), (rs, row) -> line(rs));
    }

    @Override
    public Optional<CartLine> findLineForUpdate(long customerId, long productId, String tier) {
        return jdbc.query(select() + " AND ci.product_id=:productId FOR UPDATE", new MapSqlParameterSource().addValue("customerId", customerId).addValue("productId", productId).addValue("tier", tier), rows -> rows.next() ? Optional.of(line(rows)) : Optional.empty());
    }

    @Override
    public void setQuantity(long customerId, long productId, int quantity, long actorId) {
        int rows = jdbc.update("UPDATE cart_item SET quantity=:quantity,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE customer_id=:customerId AND product_id=:productId", new MapSqlParameterSource().addValue("customerId", customerId).addValue("productId", productId).addValue("quantity", quantity).addValue("actor", actorId));
        if (rows == 0)
            jdbc.update("INSERT INTO cart_item(customer_id,product_id,quantity,created_by_user_id,updated_by_user_id) VALUES(:customerId,:productId,:quantity,:actor,:actor)", new MapSqlParameterSource().addValue("customerId", customerId).addValue("productId", productId).addValue("quantity", quantity).addValue("actor", actorId));
    }

    @Override
    public void remove(long customerId, long productOrLineId) {
        int rows = jdbc.update("DELETE FROM cart_item WHERE customer_id=:customerId AND (product_id=:id OR id=:id)", new MapSqlParameterSource().addValue("customerId", customerId).addValue("id", productOrLineId));
        if (rows == 0) throw ApiException.notFound("Cart item not found.");
    }

    private CartLine line(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CartLine(rs.getLong("id"), rs.getLong("product_id"), rs.getString("sku"), rs.getString("name"), rs.getString("category"), rs.getString("product_type"), rs.getBoolean("active"), rs.getBoolean("category_active"), rs.getInt("stock_available"), rs.getInt("quantity"), rs.getBigDecimal("unit_price"), rs.getBigDecimal("line_total"));
    }
}
