package com.dailyactivity.support;

import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.auth.infrastructure.PasswordHasher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * Test-only data builder for the PRD acceptance scenarios.
 *
 * <p>The production application intentionally does not seed demo data.  Tests
 * therefore create the smallest complete business graph they need in the test
 * transaction instead of relying on rows left by a previous run.</p>
 */
public final class PrdTestFixture {
    public static final String CUSTOMER_PASSWORD = "CustomerPass123!";
    public static final String SELLER_PASSWORD = "SellerPass123!";

    private PrdTestFixture() {
    }

    private static final String MERCHANT_CODE = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 28);

    public static long merchant(JdbcTemplate jdbc, String code) {
        var ids = jdbc.queryForList("SELECT id FROM merchant WHERE code=?", Long.class, code);
        if (!ids.isEmpty()) return ids.get(0);
        jdbc.update("INSERT INTO merchant(code,name,shipping_fee,domain) VALUES(?, 'Test Merchant',20.00,?)", code,code.toLowerCase()+".test");
        long id = jdbc.queryForObject("SELECT id FROM merchant WHERE code=?",Long.class,code);
        return id;
    }

    public static Principal platform(JdbcTemplate jdbc) {
        String account="platform-"+UUID.randomUUID();
        jdbc.update("INSERT INTO admin(account,name,password_hash) VALUES(?,'Platform',?)",account,PasswordHasher.hash(SELLER_PASSWORD));
        return new Principal(jdbc.queryForObject("SELECT id FROM admin WHERE account=?",Long.class,account),"platform",0,0);
    }

    public static Scenario seed(JdbcTemplate jdbc) {
        return seed(jdbc, merchant(jdbc, MERCHANT_CODE));
    }

    public static Scenario seed(JdbcTemplate jdbc, long merchantId) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String sellerAccount = "test-admin-" + suffix;
        String referrerEmail = "referrer-" + suffix + "@test.local";
        String customerEmail = "customer-" + suffix + "@test.local";
        String categoryName = "Test Category " + suffix;
        String sku = "TEST-SKU-" + suffix;
        String giftSku = "TEST-GIFT-" + suffix;
        String channelName = "TC-" + suffix.substring(0, 20);
        String referrerCode = unusedCustomerCode(jdbc, "R");
        String customerCode = unusedCustomerCode(jdbc, "C");
        String passwordHash = PasswordHasher.hash(CUSTOMER_PASSWORD);

        jdbc.update("INSERT INTO seller(merchant_id,account,name,password_hash,active,auth_version,created_by_user_id,updated_by_user_id) "
                        + "VALUES(?,?,?,?,1,0,NULL,NULL)",
                merchantId, sellerAccount, "Test Admin", PasswordHasher.hash(SELLER_PASSWORD));
        long sellerId = jdbc.queryForObject("SELECT id FROM seller WHERE account=?", Long.class, sellerAccount);
        insertPermissions(jdbc, sellerId, sellerId,
                List.of("order_audit", "orders", "commissions", "bills", "customers", "products", "certificates", "categories", "payment_channels", "members"));

        jdbc.update("INSERT INTO `user`(merchant_id,email,name,password_hash,code,customer_type,price_tier,active,auth_version,created_by_user_id,updated_by_user_id) "
                        + "VALUES(?,?,?,?,?,?,'V1',1,0,?,?)",
                merchantId, referrerEmail, "Test Referrer", passwordHash, referrerCode, "partner", sellerId, sellerId);
        long referrerId = jdbc.queryForObject("SELECT id FROM `user` WHERE email=?", Long.class, referrerEmail);

        jdbc.update("INSERT INTO `user`(merchant_id,email,name,password_hash,code,customer_type,price_tier,active,referrer_id,commission_rate,auth_version,created_by_user_id,updated_by_user_id) "
                        + "VALUES(?,?,?,?,?,?,'V1',1,?,?,0,?,?)",
                merchantId, customerEmail, "Test Customer", passwordHash, customerCode, "regular", referrerId, new BigDecimal("5.00"), sellerId, sellerId);
        long customerId = jdbc.queryForObject("SELECT id FROM `user` WHERE email=?", Long.class, customerEmail);

        jdbc.update("INSERT INTO customer_address(customer_id,recipient_name,street,apt,city,region,postal_code,phone,is_default,created_by_user_id,updated_by_user_id) "
                        + "VALUES(?,?,?,?,?,?,?,?,1,?,?)",
                customerId, "Test Recipient", "1 Test Street", "Unit 1", "Shanghai", "Shanghai", "200000", "13800000000", customerId, customerId);
        long addressId = jdbc.queryForObject("SELECT id FROM customer_address WHERE customer_id=?", Long.class, customerId);

        jdbc.update("INSERT INTO category(merchant_id,name,sort_no,active,created_by_user_id,updated_by_user_id) VALUES(?,?,1,1,?,?)",
                merchantId, categoryName, sellerId, sellerId);
        long categoryId = jdbc.queryForObject("SELECT id FROM category WHERE name=?", Long.class, categoryName);

        jdbc.update("INSERT INTO product(merchant_id,category_id,sku,name,description,product_type,active,base_price,cost_price,stock_available,created_by_user_id,updated_by_user_id) "
                        + "VALUES(?,?,?,?,?,'regular',1,100.00,40.00,10,?,?)",
                merchantId, categoryId, sku, "Test Regular Product", "PRD catalog search", sellerId, sellerId);
        long productId = jdbc.queryForObject("SELECT id FROM product WHERE sku=?", Long.class, sku);
        jdbc.update("INSERT INTO product_tier_price(product_id,tier,price,created_by_user_id,updated_by_user_id) VALUES(?,'V1',90.00,?,?)",
                productId, sellerId, sellerId);

        jdbc.update("INSERT INTO product(merchant_id,category_id,sku,name,description,product_type,active,base_price,cost_price,stock_available,created_by_user_id,updated_by_user_id) "
                        + "VALUES(?,?,?,?,'Test gift','gift',1,0.00,0.00,10,?,?)",
                merchantId, categoryId, giftSku, "Test Gift Product", sellerId, sellerId);
        long giftProductId = jdbc.queryForObject("SELECT id FROM product WHERE sku=?", Long.class, giftSku);

        String qrUuid = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO file_asset(merchant_id,file_uuid,stored_file_name,original_file_name,purpose,media_type,size_bytes,sha256,status,created_by_user_id,uploaded_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,'available',?,?)",
                merchantId, qrUuid, qrUuid + ".png", "test.png", "payment_channel_qr", "image/png", 1L,
                "0".repeat(64), sellerId, Timestamp.from(java.time.Instant.now()));
        jdbc.update("INSERT INTO payment_channel(merchant_id,name,currency_code,network,receiving_address,instructions,qr_file_uuid,active,sort_no,created_by_user_id,updated_by_user_id) "
                        + "VALUES(?,?,?,?,?,?,?,1,1,?,?)",
                merchantId, channelName, "USD", "TESTNET", "test-receiving-address", "Test payment instructions", qrUuid, sellerId, sellerId);
        long channelId = jdbc.queryForObject("SELECT id FROM payment_channel WHERE name=?", Long.class, channelName);

        return new Scenario(
                sellerId,
                customerId,
                referrerId,
                categoryId,
                productId,
                giftProductId,
                addressId,
                channelId,
                customerEmail,
                sellerAccount,
                customerCode,
                sku,
                new Principal(customerId, "customer", 0, merchantId),
                new Principal(sellerId, "seller", 0, merchantId));
    }

    public static Principal seedRestrictedSeller(JdbcTemplate jdbc, long creatorId) {
        long merchantId = jdbc.queryForObject("SELECT merchant_id FROM seller WHERE id=?",Long.class,creatorId);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String account = "test-orders-only-" + suffix;
        jdbc.update("INSERT INTO seller(merchant_id,account,name,password_hash,active,auth_version,created_by_user_id,updated_by_user_id) "
                        + "VALUES(?,?,?,?,1,0,?,?)",
                merchantId, account, "Orders Only", PasswordHasher.hash(SELLER_PASSWORD), creatorId, creatorId);
        long sellerId = jdbc.queryForObject("SELECT id FROM seller WHERE account=?", Long.class, account);
        insertPermissions(jdbc, sellerId, creatorId, List.of("orders"));
        return new Principal(sellerId, "seller", 0, merchantId);
    }

    private static String unusedCustomerCode(JdbcTemplate jdbc, String prefix) {
        for (int i=0; i<1296; i++) {
            String suffix=Integer.toString(i,36).toUpperCase(java.util.Locale.ROOT);
            String code=prefix+"0".repeat(7-suffix.length())+suffix;
            if (jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE code=?",Long.class,code)==0) return code;
        }
        throw new IllegalStateException("No free fixture customer code.");
    }

    private static void insertPermissions(JdbcTemplate jdbc, long userId, long actorId, List<String> permissions) {
        for (String permission : permissions) {
            jdbc.update("INSERT INTO seller_permission(seller_id,menu_code,created_by_user_id,updated_by_user_id) VALUES(?,?,?,?)",
                    userId, permission, actorId, actorId);
        }
    }

    public record Scenario(long sellerId, long customerId, long referrerId, long categoryId, long productId,
                           long giftProductId, long addressId, long channelId, String customerEmail,
                           String sellerAccount, String customerCode, String productSku, Principal customer,
                           Principal seller) {
    }
}
