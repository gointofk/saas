package com.dailyactivity.catalog.infrastructure;

import com.dailyactivity.catalog.domain.QuantityPricing.Band;
import com.dailyactivity.catalog.domain.QuantityPricing.TierPrices;
import com.dailyactivity.catalog.domain.port.QuantityPriceRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class JdbcQuantityPriceRepository implements QuantityPriceRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcQuantityPriceRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Map<Long, List<Band>> prices(List<Long> ids, String tier) {
        Map<Long, List<Band>> result = new HashMap<>();
        if (ids.isEmpty()) return result;
        jdbc.query("SELECT product_id,min_quantity,max_quantity,unit_price FROM product_quantity_price WHERE product_id IN (:ids) AND tier=:tier AND deleted=0 ORDER BY product_id,min_quantity",
                new MapSqlParameterSource("ids", ids).addValue("tier", tier), rs -> {
                    result.computeIfAbsent(rs.getLong("product_id"), ignored -> new ArrayList<>()).add(new Band(
                            rs.getInt("min_quantity"), (Integer) rs.getObject("max_quantity"), rs.getBigDecimal("unit_price")));
                });
        return result;
    }

    @Override
    public List<Band> pricesForUpdate(long id, String tier) {
        return jdbc.query("SELECT min_quantity,max_quantity,unit_price FROM product_quantity_price WHERE product_id=:id AND tier=:tier AND deleted=0 ORDER BY min_quantity FOR UPDATE",
                new MapSqlParameterSource("id", id).addValue("tier", tier), (rs, row) -> new Band(
                        rs.getInt("min_quantity"), (Integer) rs.getObject("max_quantity"), rs.getBigDecimal("unit_price")));
    }

    @Override
    public List<TierPrices> allPrices(long productId) {
        Map<String, List<Band>> result = new TreeMap<>();
        jdbc.query("SELECT tier,min_quantity,max_quantity,unit_price FROM product_quantity_price WHERE product_id=:id AND deleted=0 ORDER BY tier,min_quantity",
                new MapSqlParameterSource("id", productId), rs -> {
                    result.computeIfAbsent(rs.getString("tier"), ignored -> new ArrayList<>()).add(new Band(
                            rs.getInt("min_quantity"), (Integer) rs.getObject("max_quantity"), rs.getBigDecimal("unit_price")));
                });
        return result.entrySet().stream().map(e -> new TierPrices(e.getKey(), e.getValue())).toList();
    }

    @Override
    public void replace(long id, List<TierPrices> prices, long actor) {
        if (prices == null) return;
        var params = new MapSqlParameterSource("id", id).addValue("actor", actor);
        jdbc.update("UPDATE product_quantity_price SET deleted=id,updated_by_user_id=:actor,updated_at=UTC_TIMESTAMP(6) WHERE product_id=:id AND deleted=0", params);
        for (var tier : prices) for (var band : tier.bands())
            jdbc.update("INSERT INTO product_quantity_price(product_id,tier,min_quantity,max_quantity,unit_price,created_by_user_id,updated_by_user_id) VALUES(:id,:tier,:min,:max,:price,:actor,:actor)",
                    new MapSqlParameterSource("id", id).addValue("tier", tier.tier()).addValue("min", band.minQuantity())
                            .addValue("max", band.maxQuantity()).addValue("price", band.unitPrice()).addValue("actor", actor));
    }
}
