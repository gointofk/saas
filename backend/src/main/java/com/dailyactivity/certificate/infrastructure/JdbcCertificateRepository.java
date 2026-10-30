package com.dailyactivity.certificate.infrastructure;

import com.dailyactivity.certificate.domain.port.CertificateRepository;
import com.dailyactivity.common.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcCertificateRepository implements CertificateRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCertificateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private String select(boolean customer) {
        if (customer) {
            return "SELECT c.updated_at,c.id,c.name,c.lot,c.purity,c.fill,c.test_date,c.current_file_uuid,pcl.product_id,p.name product_name,p.sku,f.original_file_name file_name "
                    + "FROM coa_certificate c JOIN product_coa_link pcl ON pcl.coa_id=c.id AND pcl.merchant_id=c.merchant_id AND pcl.deleted=0 "
                    + "JOIN product p ON p.id=pcl.product_id AND p.merchant_id=c.merchant_id AND p.deleted=0 AND p.active=1 AND p.product_type='regular' "
                    + "JOIN category cat ON cat.id=p.category_id AND cat.merchant_id=p.merchant_id AND cat.deleted=0 AND cat.active=1 "
                    + "LEFT JOIN file_asset f ON f.file_uuid=c.current_file_uuid AND f.merchant_id=c.merchant_id "
                    + "WHERE c.merchant_id=:merchantId AND c.deleted=0";
        }
        return "SELECT c.updated_at,c.id,c.name,c.lot,c.purity,c.fill,c.test_date,c.current_file_uuid,pcl.product_id,p.name product_name,p.sku,f.original_file_name file_name "
                + "FROM coa_certificate c LEFT JOIN product_coa_link pcl ON pcl.coa_id=c.id AND pcl.merchant_id=c.merchant_id AND pcl.deleted=0 "
                + "LEFT JOIN product p ON p.id=pcl.product_id AND p.merchant_id=c.merchant_id AND p.deleted=0 "
                + "LEFT JOIN file_asset f ON f.file_uuid=c.current_file_uuid AND f.merchant_id=c.merchant_id "
                + "WHERE c.merchant_id=:merchantId AND c.deleted=0";
    }

    @Override
    public List<Certificate> allCustomer(long merchantId) {
        return jdbc.query(select(true) + " ORDER BY c.created_at DESC,c.id DESC",
                new MapSqlParameterSource("merchantId", merchantId), (rs, row) -> certificate(rs));
    }

    @Override
    public List<Certificate> adminPage(long merchantId, String q, int page) {
        String filter = q == null || q.isBlank() ? "" : " AND (c.name LIKE :query OR p.sku LIKE :query OR p.name LIKE :query)";
        return jdbc.query(select(false) + filter + " ORDER BY c.created_at DESC,c.id DESC LIMIT 10 OFFSET :offset",
                new MapSqlParameterSource().addValue("merchantId", merchantId)
                        .addValue("query", q == null ? null : "%" + q.trim() + "%")
                        .addValue("offset", (page - 1) * 10), (rs, row) -> certificate(rs));
    }

    @Override
    public long adminCount(long merchantId, String q) {
        String sql = "SELECT COUNT(*) FROM coa_certificate c LEFT JOIN product_coa_link pcl ON pcl.coa_id=c.id AND pcl.merchant_id=c.merchant_id AND pcl.deleted=0 "
                + "LEFT JOIN product p ON p.id=pcl.product_id AND p.merchant_id=c.merchant_id AND p.deleted=0 WHERE c.merchant_id=:merchantId AND c.deleted=0";
        return jdbc.queryForObject(sql + (q == null || q.isBlank() ? "" : " AND (c.name LIKE :query OR p.sku LIKE :query OR p.name LIKE :query)"),
                new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("query", q == null ? null : "%" + q.trim() + "%"), Long.class);
    }

    @Override
    public Optional<Certificate> find(long merchantId, long id) {
        return jdbc.query(select(false) + " AND c.id=:id", new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("id", id),
                rows -> rows.next() ? Optional.of(certificate(rows)) : Optional.empty());
    }

    @Override
    public long create(long merchantId, Input i, long actor) {
        validateFile(merchantId, i.fileUuid());
        KeyHolder h = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO coa_certificate(merchant_id,name,lot,purity,fill,test_date,current_file_uuid,uploaded_at,created_by_user_id,updated_by_user_id) "
                        + "VALUES(:merchantId,:name,:lot,:purity,:fill,:testDate,:fileUuid,UTC_TIMESTAMP(6),:actor,:actor)",
                params(merchantId, i, actor), h, new String[]{"id"});
        return h.getKey().longValue();
    }

    @Override
    public void update(long merchantId, long id, Input i, long actor, java.time.Instant expectedUpdatedAt) {
        validateFile(merchantId, i.fileUuid());
        int n = jdbc.update("UPDATE coa_certificate SET name=:name,lot=:lot,purity=:purity,fill=:fill,test_date=:testDate,current_file_uuid=:fileUuid,uploaded_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor "
                        + "WHERE merchant_id=:merchantId AND id=:id AND deleted=0 AND (:version IS NULL OR updated_at=:version)",
                params(merchantId, i, actor).addValue("id", id).addValue("version", expectedUpdatedAt == null ? null : java.sql.Timestamp.from(expectedUpdatedAt)));
        if (n != 1) throw ApiException.conflict("CERTIFICATE_CHANGED", "Certificate changed. Refresh and try again.");
    }

    @Override
    public void softDelete(long merchantId, long id, long actor) {
        lockMerchant(merchantId);
        int n = jdbc.update("UPDATE coa_certificate SET deleted=id,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE merchant_id=:merchantId AND id=:id AND deleted=0",
                new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("id", id).addValue("actor", actor));
        if (n != 1) throw ApiException.notFound("Certificate not found.");
        jdbc.update("UPDATE product_coa_link SET deleted=id,ended_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE merchant_id=:merchantId AND coa_id=:id AND deleted=0",
                new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("id", id).addValue("actor", actor));
    }

    @Override
    public void bindProduct(long merchantId, long coaId, Long productId, long actor) {
        lockMerchant(merchantId);
        find(merchantId, coaId).orElseThrow(() -> ApiException.notFound("Certificate not found."));
        if (productId != null) requireProduct(merchantId, productId);
        jdbc.update("UPDATE product_coa_link SET deleted=id,ended_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE merchant_id=:merchantId AND coa_id=:coaId AND deleted=0",
                new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("coaId", coaId).addValue("actor", actor));
        if (productId == null) return;
        insertLink(merchantId, productId, coaId, actor);
    }

    @Override
    public List<Certificate> productCertificates(long merchantId, long productId) {
        return jdbc.query(select(false) + " AND pcl.product_id=:productId ORDER BY c.id",
                new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("productId", productId), (rs, row) -> certificate(rs));
    }

    @Override
    public void replaceProductCertificates(long merchantId, long productId, List<Long> coaIds, long actor) {
        lockMerchant(merchantId);
        requireProduct(merchantId, productId);
        if (!coaIds.isEmpty()) {
            Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM coa_certificate WHERE merchant_id=:merchantId AND deleted=0 AND id IN (:ids)",
                    new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("ids", coaIds), Integer.class);
            if (found == null || found != coaIds.size()) throw ApiException.badRequest("INVALID_COA_BINDING", "One or more COAs are unavailable.");
        }
        var params = new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("productId", productId).addValue("actor", actor);
        jdbc.update("UPDATE product_coa_link SET deleted=id,ended_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE merchant_id=:merchantId AND product_id=:productId AND deleted=0", params);
        for (Long coaId : coaIds) {
            jdbc.update("UPDATE product_coa_link SET deleted=id,ended_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE merchant_id=:merchantId AND coa_id=:coaId AND deleted=0",
                    new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("coaId", coaId).addValue("actor", actor));
            insertLink(merchantId, productId, coaId, actor);
        }
    }

    @Override
    public List<ProductBinding> productOptions(long merchantId, Long coaId, String query) {
        String search = query == null || query.isBlank() ? null : "%" + query.trim() + "%";
        return jdbc.query("SELECT p.id,p.sku,p.name FROM product p WHERE p.merchant_id=:merchantId AND p.deleted=0 AND p.active=1 AND p.product_type='regular' "
                        + "AND (:query IS NULL OR p.sku LIKE :query OR p.name LIKE :query) "
                        + "AND (:coaId IS NULL OR EXISTS (SELECT 1 FROM coa_certificate c WHERE c.id=:coaId AND c.merchant_id=:merchantId AND c.deleted=0)) ORDER BY p.id LIMIT 100",
                new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("coaId", coaId).addValue("query", search),
                (rs, row) -> new ProductBinding(rs.getLong("id"), rs.getString("sku"), rs.getString("name")));
    }

    @Override
    public Optional<ProductDetail> productDetail(long merchantId, long coaId) {
        return jdbc.query("SELECT p.id,p.sku,p.name,c.name category,p.description,p.product_type,p.active,p.stock_available,p.base_price "
                        + "FROM product_coa_link pcl JOIN coa_certificate cert ON cert.id=pcl.coa_id AND cert.merchant_id=pcl.merchant_id AND cert.deleted=0 "
                        + "JOIN product p ON p.id=pcl.product_id AND p.merchant_id=pcl.merchant_id AND p.deleted=0 "
                        + "LEFT JOIN category c ON c.id=p.category_id AND c.merchant_id=p.merchant_id AND c.deleted=0 "
                        + "WHERE pcl.merchant_id=:merchantId AND pcl.coa_id=:coaId AND pcl.deleted=0",
                new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("coaId", coaId), rows -> rows.next()
                        ? Optional.of(new ProductDetail(rows.getLong("id"), rows.getString("sku"), rows.getString("name"),
                        rows.getString("category"), rows.getString("description"), rows.getString("product_type"),
                        rows.getBoolean("active"), rows.getInt("stock_available"), rows.getBigDecimal("base_price")))
                        : Optional.empty());
    }

    @Override
    public boolean fileAvailable(long merchantId, String fileUuid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM file_asset WHERE file_uuid=:uuid AND merchant_id=:merchantId AND purpose='coa' AND status='available' AND deleted=0",
                new MapSqlParameterSource().addValue("uuid", fileUuid).addValue("merchantId", merchantId), Long.class) > 0;
    }

    private void validateFile(long merchantId, String uuid) {
        if (uuid == null || !fileAvailable(merchantId, uuid))
            throw ApiException.badRequest("FILE_NOT_AVAILABLE", "The file is not available for certificate use.");
    }

    private MapSqlParameterSource params(long merchantId, Input i, long actor) {
        return new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("name", i.name()).addValue("lot", i.lot())
                .addValue("purity", i.purity()).addValue("fill", i.fill())
                .addValue("testDate", i.testDate() == null ? null : Date.valueOf(i.testDate()))
                .addValue("fileUuid", i.fileUuid()).addValue("actor", actor);
    }

    private void lockMerchant(long merchantId) {
        jdbc.queryForObject("SELECT id FROM merchant WHERE id=:merchantId FOR UPDATE", new MapSqlParameterSource("merchantId", merchantId), Long.class);
    }

    private void requireProduct(long merchantId, long productId) {
        Integer found = jdbc.query("SELECT id FROM product WHERE merchant_id=:merchantId AND id=:id AND deleted=0 FOR UPDATE",
                new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("id", productId), rows -> rows.next() ? 1 : null);
        if (found == null) throw ApiException.notFound("Product not found.");
    }

    private void insertLink(long merchantId, long productId, long coaId, long actor) {
        jdbc.update("INSERT INTO product_coa_link(merchant_id,product_id,coa_id,linked_by_user_id,linked_at,created_by_user_id,updated_by_user_id) VALUES(:merchantId,:productId,:coaId,:actor,UTC_TIMESTAMP(6),:actor,:actor)",
                new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("productId", productId).addValue("coaId", coaId).addValue("actor", actor));
    }

    private Certificate certificate(java.sql.ResultSet r) throws java.sql.SQLException {
        Date date = r.getDate("test_date");
        return new Certificate(r.getLong("id"), r.getString("name"), r.getString("lot"), r.getString("purity"), r.getString("fill"),
                date == null ? null : date.toLocalDate(), r.getString("current_file_uuid"), (Long) r.getObject("product_id"),
                r.getString("product_name"), r.getString("sku"), r.getString("file_name"), r.getTimestamp("updated_at").toInstant());
    }
}
