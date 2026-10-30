package com.dailyactivity.catalog.infrastructure;

import com.dailyactivity.catalog.domain.QuantityPricing;
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
    public Map<Long, List<Band>> prices(long merchantId, List<Long> ids, String tier) {
        Map<Long, List<Band>> result = new HashMap<>();
        if (ids.isEmpty()) return result;
        jdbc.query("SELECT qp.product_id,qp.min_quantity,qp.unit_price FROM product_quantity_price qp JOIN product p ON p.id=qp.product_id AND p.merchant_id=:merchantId WHERE qp.product_id IN (:ids) AND qp.tier=:tier AND qp.deleted=0 ORDER BY qp.product_id,qp.min_quantity",
                new MapSqlParameterSource("merchantId", merchantId).addValue("ids", ids).addValue("tier", tier), rs -> {
                    result.computeIfAbsent(rs.getLong("product_id"), ignored -> new ArrayList<>()).add(new Band(
                            rs.getInt("min_quantity"), null, rs.getBigDecimal("unit_price")));
                });
        result.replaceAll((productId, bands) -> QuantityPricing.fromThresholds(bands));
        return result;
    }

    @Override
    public List<Band> pricesForUpdate(long merchantId, long id, String tier) {
        return QuantityPricing.fromThresholds(jdbc.query("SELECT qp.min_quantity,qp.unit_price FROM product_quantity_price qp JOIN product p ON p.id=qp.product_id AND p.merchant_id=:merchantId WHERE qp.product_id=:id AND qp.tier=:tier AND qp.deleted=0 ORDER BY qp.min_quantity FOR UPDATE",
                new MapSqlParameterSource("merchantId", merchantId).addValue("id", id).addValue("tier", tier), (rs, row) -> new Band(
                        rs.getInt("min_quantity"), null, rs.getBigDecimal("unit_price"))));
    }

    @Override
    public List<TierPrices> allPrices(long merchantId, long productId) {
        Map<String, List<Band>> result = new TreeMap<>();
        jdbc.query("SELECT qp.tier,qp.min_quantity,qp.unit_price FROM product_quantity_price qp JOIN product p ON p.id=qp.product_id AND p.merchant_id=:merchantId WHERE qp.product_id=:id AND qp.deleted=0 ORDER BY qp.tier,qp.min_quantity",
                new MapSqlParameterSource("merchantId", merchantId).addValue("id", productId), rs -> {
                    result.computeIfAbsent(rs.getString("tier"), ignored -> new ArrayList<>()).add(new Band(
                            rs.getInt("min_quantity"), null, rs.getBigDecimal("unit_price")));
                });
        return result.entrySet().stream().map(e -> new TierPrices(e.getKey(), QuantityPricing.fromThresholds(e.getValue()))).toList();
    }

    @Override
    public void replace(long merchantId, long id, List<TierPrices> prices, long actor) {
        if (prices == null) return;
        var params = new MapSqlParameterSource("merchantId", merchantId).addValue("id", id).addValue("actor", actor);
        jdbc.update("UPDATE product_quantity_price SET deleted=id,updated_by_user_id=:actor,updated_at=UTC_TIMESTAMP(6) WHERE product_id=:id AND deleted=0 AND EXISTS (SELECT 1 FROM product p WHERE p.id=:id AND p.merchant_id=:merchantId)", params);
        for (var tier : prices) for (var band : tier.bands())
            jdbc.update("INSERT INTO product_quantity_price(product_id,tier,min_quantity,unit_price,created_by_user_id,updated_by_user_id) SELECT p.id,:tier,:min,:price,:actor,:actor FROM product p WHERE p.id=:id AND p.merchant_id=:merchantId",
                    new MapSqlParameterSource("merchantId", merchantId).addValue("id", id).addValue("tier", tier.tier()).addValue("min", band.minQuantity())
                            .addValue("price", band.unitPrice()).addValue("actor", actor));
    }
}
