package com.dailyactivity.file.infrastructure;

import com.dailyactivity.file.domain.port.FileRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class JdbcFileRepository implements FileRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcFileRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(FileAsset a, long actor) {
        jdbc.update("INSERT INTO file_asset(merchant_id,file_uuid,stored_file_name,original_file_name,purpose,media_type,size_bytes,sha256,status,uploaded_at,created_by_user_id,updated_by_user_id) VALUES((SELECT merchant_id FROM seller WHERE id=:actor),:uuid,:storedName,:originalName,:purpose,:mediaType,:size,:sha256,'available',:uploadedAt,:actor,:actor)", new MapSqlParameterSource().addValue("uuid", a.uuid()).addValue("storedName", a.storedName()).addValue("originalName", a.originalName()).addValue("purpose", a.purpose()).addValue("mediaType", a.mediaType()).addValue("size", a.sizeBytes()).addValue("sha256", a.sha256()).addValue("actor", actor).addValue("uploadedAt", Timestamp.from(a.uploadedAt())));
    }

    @Override
    public Optional<FileAsset> find(String uuid) {
        return jdbc.query("SELECT file_uuid,stored_file_name,original_file_name,purpose,media_type,size_bytes,sha256,status,uploaded_at FROM file_asset WHERE file_uuid=:uuid AND deleted=0", new MapSqlParameterSource("uuid", uuid), rows -> rows.next() ? Optional.of(new FileAsset(rows.getString("file_uuid"), rows.getString("stored_file_name"), rows.getString("original_file_name"), rows.getString("purpose"), rows.getString("media_type"), rows.getLong("size_bytes"), rows.getString("sha256"), rows.getString("status"), rows.getTimestamp("uploaded_at").toInstant())) : Optional.empty());
    }

    @Override
    public boolean belongsToMerchant(String uuid, long merchantId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM file_asset WHERE file_uuid=:uuid AND merchant_id=:merchant AND status='available' AND deleted=0", new MapSqlParameterSource("uuid",uuid).addValue("merchant",merchantId),Long.class)>0;
    }

    @Override
    public boolean orderBelongsToMerchant(long orderId,long merchantId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales_order WHERE id=:id AND merchant_id=:merchant AND audit_status='approved' AND order_status NOT IN ('completed','cancelled')",new MapSqlParameterSource("id",orderId).addValue("merchant",merchantId),Long.class)>0;
    }

    @Override
    public boolean availableForPurpose(String uuid, String purpose) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM file_asset WHERE file_uuid=:uuid AND purpose=:purpose AND status='available' AND deleted=0", new MapSqlParameterSource().addValue("uuid", uuid).addValue("purpose", purpose), Long.class);
        return count != null && count > 0;
    }

    @Override
    public boolean customerCanAccessFile(String uuid, long customerId) {
        String sql = "SELECT COUNT(*) FROM file_asset f "
                + "WHERE f.file_uuid=:uuid AND f.status='available' AND f.deleted=0 AND f.merchant_id=(SELECT merchant_id FROM `user` WHERE id=:customerId) "
                + "AND ((f.purpose='coa' AND (EXISTS (SELECT 1 FROM coa_certificate c JOIN product_coa_link pcl ON pcl.coa_id=c.id AND pcl.deleted=0 "
                + "JOIN product p ON p.id=pcl.product_id AND p.merchant_id=c.merchant_id AND p.deleted=0 AND p.active=1 AND p.product_type='regular' "
                + "JOIN category cat ON cat.id=p.category_id AND cat.merchant_id=p.merchant_id AND cat.deleted=0 AND cat.active=1 "
                + "WHERE c.current_file_uuid=f.file_uuid AND c.merchant_id=f.merchant_id AND pcl.merchant_id=f.merchant_id AND c.deleted=0) "
                + "OR EXISTS (SELECT 1 FROM order_item_coa_snapshot s JOIN sales_order o ON o.id=s.order_id "
                + "WHERE s.file_uuid=f.file_uuid AND o.customer_id=:customerId AND o.merchant_id=f.merchant_id))) "
                + "OR (f.purpose IN ('order_fulfillment','order_shipping_label') AND EXISTS (SELECT 1 FROM order_event_file oef JOIN sales_order o ON o.id=oef.order_id "
                + "WHERE oef.file_uuid=f.file_uuid AND o.customer_id=:customerId AND o.merchant_id=f.merchant_id)) "
                + "OR (f.purpose='payment_channel_qr' AND EXISTS (SELECT 1 FROM payment_channel pc "
                + "WHERE pc.qr_file_uuid=f.file_uuid AND pc.active=1 AND pc.deleted=0 AND pc.merchant_id=(SELECT merchant_id FROM `user` WHERE id=:customerId))))";
        return jdbc.queryForObject(sql, new MapSqlParameterSource().addValue("uuid", uuid).addValue("customerId", customerId), Long.class) > 0;
    }
}
