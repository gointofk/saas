package com.dailyactivity.catalog.infrastructure;

import com.dailyactivity.catalog.domain.port.CatalogRepository;
import com.dailyactivity.common.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcCatalogRepository implements CatalogRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCatalogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CategoryView> categories(long merchantId, boolean activeOnly) {
        String active = activeOnly ? " AND c.active=1" : "";
        return jdbc.query("SELECT c.id,c.name,c.sort_no,c.active,"
                        + "(SELECT COUNT(*) FROM product p WHERE p.merchant_id=c.merchant_id AND p.category_id=c.id AND p.deleted=0) product_count,"
                        + "u.name updated_by,c.updated_at "
                        + "FROM category c LEFT JOIN seller u ON u.id=c.updated_by_user_id AND u.merchant_id=c.merchant_id "
                        + "WHERE c.merchant_id=:merchantId AND c.deleted=0" + active + " ORDER BY c.sort_no,c.id",
                new MapSqlParameterSource("merchantId", merchantId), (rs, row) -> new CategoryView(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("sort_no"), rs.getBoolean("active"), rs.getLong("product_count"),
                        rs.getString("updated_by"),
                        rs.getTimestamp("updated_at").toInstant()));
    }

    @Override
    public List<CustomerProductView> customerProducts(long merchantId, String priceTier) {
        String sql = "SELECT p.id,p.category_id,c.name category,p.sku,p.name,p.description,p.stock_available,"
                + "COALESCE(tp.price,p.base_price) price "
                + "FROM product p JOIN category c ON c.id=p.category_id AND c.merchant_id=p.merchant_id AND c.deleted=0 "
                + "LEFT JOIN product_tier_price tp ON tp.product_id=p.id AND tp.tier=:tier "
                + "WHERE p.merchant_id=:merchantId AND p.deleted=0 AND p.active=1 AND c.active=1 AND p.product_type='regular' ORDER BY p.id";
        String coaSql = "SELECT pcl.product_id,coa.name,coa.current_file_uuid,coa.lot,"
                + "DATE_FORMAT(coa.test_date,'%Y-%m-%d') test_date "
                + "FROM product_coa_link pcl JOIN coa_certificate coa ON coa.id=pcl.coa_id AND coa.merchant_id=pcl.merchant_id AND coa.deleted=0 "
                + "JOIN product p ON p.id=pcl.product_id AND p.merchant_id=pcl.merchant_id AND p.deleted=0 AND p.active=1 AND p.product_type='regular' "
                + "JOIN category c ON c.id=p.category_id AND c.merchant_id=p.merchant_id AND c.deleted=0 AND c.active=1 "
                + "WHERE pcl.merchant_id=:merchantId AND pcl.deleted=0 ORDER BY pcl.product_id,pcl.id";
        Map<Long, List<CustomerCoaView>> coasByProduct = new HashMap<>();
        List<ProductCoaRow> coaRows = jdbc.query(coaSql, new MapSqlParameterSource("merchantId", merchantId), (rs, row) -> new ProductCoaRow(
                rs.getLong("product_id"), new CustomerCoaView(rs.getString("name"), rs.getString("current_file_uuid"),
                rs.getString("lot"), rs.getString("test_date"))));
        for (ProductCoaRow coaRow : coaRows)
            coasByProduct.computeIfAbsent(coaRow.productId(), ignored -> new ArrayList<>()).add(coaRow.coa());
        return jdbc.query(sql, new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("tier", priceTier == null ? "V1" : priceTier),
                (rs, row) -> new CustomerProductView(rs.getLong("id"), rs.getLong("category_id"),
                        rs.getString("category"), rs.getString("sku"), rs.getString("name"),
                        rs.getString("description"), rs.getBigDecimal("price"), rs.getInt("stock_available"),
                        coasByProduct.getOrDefault(rs.getLong("id"), List.of())));
    }

    private record ProductCoaRow(long productId, CustomerCoaView coa) {
    }

    @Override
    public Page<ProductView> adminProducts(long merchantId, String query, Long categoryId, String productType, Boolean active, String sortBy, String sortDirection, int page) {
        String filters = productFilters(query, categoryId, false)
                + (productType == null ? "" : " AND p.product_type=:productType")
                + (active == null ? "" : " AND p.active=:active");
        MapSqlParameterSource params = productParams(merchantId, query, categoryId)
                .addValue("productType", productType)
                .addValue("active", active);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM product p JOIN category c ON c.id=p.category_id AND c.merchant_id=p.merchant_id AND c.deleted=0 WHERE p.merchant_id=:merchantId AND p.deleted=0" + filters, params, Long.class);
        params.addValue("tier", "V1").addValue("offset", (page - 1) * 10);
        String sortColumn = "stock".equals(sortBy) ? "p.stock_available" : "price".equals(sortBy) ? "price" : "p.id";
        String direction = "asc".equals(sortDirection) ? "ASC" : "DESC";
        List<ProductView> items = jdbc.query(productSelect() + filters + " ORDER BY " + sortColumn + " " + direction + ",p.id DESC LIMIT 10 OFFSET :offset", params, (rs, row) -> product(rs));
        if (!items.isEmpty()) {
            var coas = jdbc.query("SELECT pcl.product_id,c.id,c.name,c.current_file_uuid,c.lot,DATE_FORMAT(c.test_date,'%Y-%m-%d') test_date FROM product_coa_link pcl JOIN coa_certificate c ON c.id=pcl.coa_id AND c.merchant_id=pcl.merchant_id AND c.deleted=0 WHERE pcl.merchant_id=:merchantId AND pcl.deleted=0 AND pcl.product_id IN (:ids) ORDER BY pcl.product_id,pcl.id",
                    new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("ids", items.stream().map(ProductView::id).toList()), (rs, row) -> Map.entry(rs.getLong("product_id"), new ProductCoaView(rs.getLong("id"), rs.getString("name"), rs.getString("current_file_uuid"), rs.getString("lot"), rs.getString("test_date"))));
            Map<Long, List<ProductCoaView>> byProduct = new HashMap<>();
            coas.forEach(item -> byProduct.computeIfAbsent(item.getKey(), ignored -> new ArrayList<>()).add(item.getValue()));
            items = items.stream().map(item -> new ProductView(item.id(), item.categoryId(), item.category(), item.sku(), item.name(), item.description(), item.productType(), item.active(), item.price(), item.basePrice(), item.costPrice(), item.stock(), item.priceRevision(), item.updatedAt(), byProduct.getOrDefault(item.id(), List.of()))).toList();
        }
        return new Page<>(items, total);
    }

    @Override
    public Optional<ProductView> product(long merchantId, long productId, String priceTier, boolean activeOnly, boolean lock) {
        String filters = " AND p.id=:id" + (activeOnly ? " AND p.active=1 AND c.active=1 AND p.product_type='regular'" : "");
        String sql = productSelect() + filters + (lock ? " FOR UPDATE" : "");
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("id", productId).addValue("tier", priceTier == null ? "V1" : priceTier);
        return jdbc.query(sql, params, rows -> rows.next() ? Optional.of(product(rows)) : Optional.empty());
    }

    @Override
    public List<PriceView> prices(long merchantId, long productId) {
        return jdbc.query("SELECT tp.tier,tp.price FROM product_tier_price tp JOIN product p ON p.id=tp.product_id AND p.merchant_id=:merchantId WHERE tp.product_id=:id ORDER BY tp.tier",
                new MapSqlParameterSource().addValue("id", productId).addValue("merchantId", merchantId), (rs, row) -> new PriceView(rs.getString("tier"), rs.getBigDecimal("price")));
    }

    @Override
    public Page<ProductView> orderEditProducts(long merchantId, String query, String priceTier, boolean giftsOnly, int page) {
        String filters = " AND p.active=1 AND c.active=1"
                + (giftsOnly ? " AND p.product_type='gift'" : "")
                + productFilters(query, null, false);
        var params = productParams(merchantId, query, null).addValue("tier", priceTier).addValue("offset", (page - 1) * 10);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM product p JOIN category c ON c.id=p.category_id AND c.merchant_id=p.merchant_id AND c.deleted=0 WHERE p.merchant_id=:merchantId AND p.deleted=0" + filters, params, Long.class);
        var items = jdbc.query(productSelect() + filters + " ORDER BY p.id DESC LIMIT 10 OFFSET :offset", params, (rs, row) -> product(rs));
        return new Page<>(items, total);
    }

    @Override
    public Optional<ProductView> orderEditProduct(long merchantId, long productId, String priceTier) {
        return jdbc.query(productSelect() + " AND p.id=:id AND p.active=1 AND c.active=1 FOR UPDATE",
                new MapSqlParameterSource("merchantId", merchantId).addValue("id", productId).addValue("tier", priceTier),
                rows -> rows.next() ? Optional.of(product(rows)) : Optional.empty());
    }

    @Override
    public long createCategory(long merchantId, String name, int sortNo, long actorId) {
        try {
            KeyHolder holder = new GeneratedKeyHolder();
            jdbc.update("INSERT INTO category(merchant_id,name,sort_no,active,created_by_user_id,updated_by_user_id) VALUES(:merchantId,:name,:sortNo,1,:actor,:actor)",
                    new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("name", name).addValue("sortNo", sortNo).addValue("actor", actorId), holder, new String[]{"id"});
            return holder.getKey().longValue();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("CATEGORY_NAME_EXISTS", "Category name is already in use.");
        }
    }

    @Override
    public void updateCategory(long merchantId, long id, String name, int sortNo, boolean active, long actorId, java.time.Instant expectedUpdatedAt) {
        try {
            int rows = jdbc.update("UPDATE category SET name=:name,sort_no=:sortNo,active=:active,updated_by_user_id=:actor,updated_by_type='seller',updated_at=UTC_TIMESTAMP(6) WHERE merchant_id=:merchantId AND id=:id AND deleted=0 AND (:version IS NULL OR updated_at=:version)",
                    new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("id", id).addValue("version", expectedUpdatedAt == null ? null : java.sql.Timestamp.from(expectedUpdatedAt)).addValue("name", name).addValue("sortNo", sortNo).addValue("active", active).addValue("actor", actorId));
            if (rows != 1) throw ApiException.conflict("CATEGORY_CHANGED", "Category changed. Refresh and try again.");
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("CATEGORY_NAME_EXISTS", "Category name is already in use.");
        }
    }

    @Override
    public void softDeleteCategory(long merchantId, long id, long actorId) {
        Long products = jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE merchant_id=:merchantId AND category_id=:id", new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("id", id), Long.class);
        if (products != null && products > 0)
            throw ApiException.conflict("CATEGORY_HAS_PRODUCTS", "A category with product history cannot be deleted.");
        int rows = jdbc.update("UPDATE category SET deleted=id,updated_by_user_id=:actor,updated_by_type='seller',updated_at=UTC_TIMESTAMP(6) WHERE merchant_id=:merchantId AND id=:id AND deleted=0",
                new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("id", id).addValue("actor", actorId));
        if (rows != 1) throw ApiException.notFound("Category not found.");
    }

    @Override
    public long createProduct(long merchantId, ProductCommand c, long actorId) {
        try {
            requireCategory(merchantId, c.categoryId());
            KeyHolder holder = new GeneratedKeyHolder();
            jdbc.update("INSERT INTO product(merchant_id,category_id,sku,name,description,product_type,active,base_price,cost_price,stock_available,created_by_user_id,updated_by_user_id) VALUES(:merchantId,:categoryId,:sku,:name,:description,:productType,:active,:basePrice,:costPrice,:stock,:actor,:actor)", productParams(merchantId, c, actorId), holder, new String[]{"id"});
            long id = holder.getKey().longValue();
            replacePrices(id, c.tierPrices(), actorId);
            return id;
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("PRODUCT_SKU_EXISTS", "Product SKU is already in use.");
        }
    }

    @Override
    public void updateProduct(long merchantId, long id, ProductCommand c, long actorId) {
        try {
            requireCategory(merchantId, c.categoryId());
            int rows = jdbc.update("UPDATE product SET category_id=:categoryId,sku=:sku,name=:name,description=:description,product_type=:productType,active=:active,base_price=:basePrice,cost_price=:costPrice,updated_by_user_id=:actor,updated_by_type='seller',updated_at=UTC_TIMESTAMP(6),price_revision=price_revision+1 WHERE merchant_id=:merchantId AND id=:id AND deleted=0",
                    productParams(merchantId, c, actorId).addValue("id", id));
            if (rows != 1) throw ApiException.notFound("Product not found.");
            replacePrices(id, c.tierPrices(), actorId);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("PRODUCT_SKU_EXISTS", "Product SKU is already in use.");
        }
    }

    @Override
    public void setProductActive(long merchantId, long id, boolean active, long actorId) {
        int rows = jdbc.update("UPDATE product SET active=:active,updated_by_user_id=:actor,updated_by_type='seller',updated_at=UTC_TIMESTAMP(6) WHERE merchant_id=:merchantId AND id=:id AND deleted=0",
                new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("id", id).addValue("active", active).addValue("actor", actorId));
        if (rows != 1) throw ApiException.notFound("Product not found.");
    }

    @Override
    public void softDeleteProduct(long merchantId, long id, long actorId) {
        Boolean active = jdbc.query("SELECT active FROM product WHERE merchant_id=:merchantId AND id=:id AND deleted=0 FOR UPDATE", new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("id", id), rows -> rows.next() ? rows.getBoolean(1) : null);
        if (active == null) throw ApiException.notFound("Product not found.");
        if (active)
            throw ApiException.conflict("PRODUCT_MUST_BE_OFFLINE", "Take the product offline before deleting it.");
        jdbc.update("UPDATE product SET deleted=id,updated_by_user_id=:actor,updated_by_type='seller',updated_at=UTC_TIMESTAMP(6) WHERE merchant_id=:merchantId AND id=:id AND deleted=0", new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("id", id).addValue("actor", actorId));
    }

    private String productSelect() {
        return "SELECT p.id,p.category_id,c.name category,p.sku,p.name,p.description,p.product_type,p.active,p.base_price,p.cost_price,p.stock_available,p.price_revision,p.updated_at,COALESCE(tp.price,p.base_price) price FROM product p JOIN category c ON c.id=p.category_id AND c.merchant_id=p.merchant_id AND c.deleted=0 LEFT JOIN product_tier_price tp ON tp.product_id=p.id AND tp.tier=:tier WHERE p.merchant_id=:merchantId AND p.deleted=0";
    }

    private String productFilters(String query, Long categoryId, boolean activeOnly) {
        return (activeOnly ? " AND p.active=1 AND c.active=1 AND p.product_type='regular'" : "") + (categoryId == null ? "" : " AND p.category_id=:categoryId") + (query == null || query.isBlank() ? "" : " AND (p.name LIKE :query OR p.sku LIKE :query)");
    }

    private MapSqlParameterSource productParams(long merchantId, String query, Long categoryId) {
        return new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("categoryId", categoryId).addValue("query", query == null || query.isBlank() ? null : "%" + query.trim() + "%");
    }

    private MapSqlParameterSource productParams(long merchantId, ProductCommand c, long actorId) {
        return new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("categoryId", c.categoryId()).addValue("sku", c.sku()).addValue("name", c.name()).addValue("description", c.description()).addValue("productType", c.productType()).addValue("active", c.active()).addValue("basePrice", c.basePrice()).addValue("costPrice", c.costPrice()).addValue("stock", c.stockAvailable()).addValue("actor", actorId);
    }

    private void requireCategory(long merchantId, long categoryId) {
        Integer found = jdbc.query("SELECT id FROM category WHERE merchant_id=:merchantId AND id=:id AND deleted=0 FOR UPDATE",
                new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("id", categoryId), rows -> rows.next() ? 1 : null);
        if (found == null) throw ApiException.notFound("Category not found.");
    }

    private void replacePrices(long productId, List<PriceView> prices, long actorId) {
        if (prices == null) return;
        jdbc.update("DELETE FROM product_tier_price WHERE product_id=:productId", new MapSqlParameterSource("productId", productId));
        for (PriceView price : prices)
            jdbc.update("INSERT INTO product_tier_price(product_id,tier,price,created_by_user_id,updated_by_user_id) VALUES(:productId,:tier,:price,:actor,:actor)", new MapSqlParameterSource().addValue("productId", productId).addValue("tier", price.tier()).addValue("price", price.price()).addValue("actor", actorId));
    }

    private ProductView product(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ProductView(rs.getLong("id"), rs.getLong("category_id"), rs.getString("category"), rs.getString("sku"), rs.getString("name"), rs.getString("description"), rs.getString("product_type"), rs.getBoolean("active"), rs.getBigDecimal("price"), rs.getBigDecimal("base_price"), rs.getBigDecimal("cost_price"), rs.getInt("stock_available"), rs.getLong("price_revision"), rs.getTimestamp("updated_at").toInstant(), List.of());
    }
}
