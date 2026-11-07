-- DailyActivity V1.9: 全量 MySQL 5.6.51 初始化
-- 仅在预先创建的空业务库执行。数据库由 mysql 命令参数选择。
-- 不含 CREATE DATABASE/USE，不修改用户账户，不导入 Demo 或默认密码。
-- 35 张 InnoDB 实体表；无视图、外键、触发器、存储过程、会话表。
-- 不含原生 JSON、生成列、CHECK、CTE 或 5.7/8.x 专有 SQL。
-- COA、收款二维码和履约附件使用 file_asset.file_uuid；商户 Logo 使用公开品牌图片路径写入 merchant.logo_url。
-- 文件内容仍在本地磁盘，业务 JSON 按字段约定保存为 LONGTEXT 或有界 VARCHAR。
-- 每个索引按 COMPACT 的 767 字节限制设计，唯一键不使用前缀缩短。
-- 仅带 deleted 字段的主数据和可重复关系由应用执行 UPDATE deleted=id，并写 updated_at/updated_by_user_id。
-- 数量/金额/枚举/引用/0%不结算等规则由应用层事务校验。
-- DDL 隐式提交；执行错误必须停止，勿使用 --force，勿在业务库反复重跑。
-- 仅用于首次空库初始化；不包含业务示例数据或会话存储表。
-- 运行账户需要 SELECT/INSERT/UPDATE；DELETE 仅用于 cart_item、product_tier_price 和过期 customer_registration_challenge 清理。

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET SESSION time_zone = '+00:00';
SET SESSION sql_mode = 'STRICT_TRANS_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';

-- 商户配置由独立平台管理员管理；商户目录由各商户自行维护。
CREATE TABLE merchant (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(32) NOT NULL,
  name VARCHAR(100) NOT NULL,
  domain VARCHAR(253) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
  logo_url VARCHAR(512) NOT NULL DEFAULT '/ytpeps-logo.svg',
  theme VARCHAR(32) NOT NULL DEFAULT 'classic',
  active TINYINT NOT NULL DEFAULT 1,
  customer_registration_enabled TINYINT NOT NULL DEFAULT 0,
  customer_registration_default_tier CHAR(2) NOT NULL DEFAULT 'V1' COMMENT '新自助注册客户默认价格等级：V1～V6',
  crypto_payment_enabled TINYINT NOT NULL DEFAULT 1,
  online_payment_link_enabled TINYINT NOT NULL DEFAULT 0,
  commission_enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用佣金功能',
  shipping_fee_v1 DECIMAL(18,2) NOT NULL DEFAULT 0.00,
  shipping_fee_v2 DECIMAL(18,2) NOT NULL DEFAULT 0.00,
  shipping_fee_v3 DECIMAL(18,2) NOT NULL DEFAULT 0.00,
  shipping_fee_v4 DECIMAL(18,2) NOT NULL DEFAULT 0.00,
  shipping_fee_v5 DECIMAL(18,2) NOT NULL DEFAULT 0.00,
  shipping_fee_v6 DECIMAL(18,2) NOT NULL DEFAULT 0.00,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  created_by_user_id BIGINT NULL,
  created_by_type VARCHAR(16) NOT NULL DEFAULT 'platform',
  updated_by_user_id BIGINT NULL,
  updated_by_type VARCHAR(16) NOT NULL DEFAULT 'platform',
  PRIMARY KEY (id), UNIQUE KEY uk_merchant_code (code), UNIQUE KEY uk_merchant_domain (domain)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE merchant_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  merchant_id BIGINT NOT NULL,
  actor_user_id BIGINT NOT NULL,
  actor_type VARCHAR(16) NOT NULL,
  action VARCHAR(64) NOT NULL,
  detail LONGTEXT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id), KEY idx_merchant_event (merchant_id,created_at,id), KEY idx_merchant_event_platform_time (created_at,id)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 平台查看订单和买家详情、联系方式时记录访问用途；只追加，不提供删除入口。
CREATE TABLE platform_data_access_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  actor_user_id BIGINT NOT NULL,
  merchant_id BIGINT NOT NULL,
  resource_type VARCHAR(32) NOT NULL,
  resource_id BIGINT NOT NULL,
  access_type VARCHAR(32) NOT NULL,
  purpose VARCHAR(500) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY idx_platform_access_time (created_at,id),
  KEY idx_platform_access_actor (actor_user_id,created_at,id),
  KEY idx_platform_access_resource (merchant_id,resource_type,resource_id,created_at)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='平台跨商户业务数据访问用途审计';

CREATE TABLE merchant_email_settings (
  merchant_id BIGINT NOT NULL,
  smtp_host VARCHAR(253) NOT NULL,
  smtp_port INT NOT NULL,
  smtp_username VARCHAR(255) NOT NULL,
  smtp_password_ciphertext VARCHAR(1024) NULL,
  tls_mode VARCHAR(16) NOT NULL,
  sender_email VARCHAR(254) NOT NULL,
  sender_name VARCHAR(100) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (merchant_id)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- 固定菜单定义；members 是 seller 用户可被授予的菜单权限
CREATE TABLE `permission_menu` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `menu_code` VARCHAR(32) NOT NULL COMMENT '菜单编码',
  `name` VARCHAR(64) NOT NULL COMMENT '名称',
  `sort_no` INT NOT NULL COMMENT '排序序号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_menu_code` (`menu_code`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='固定菜单定义';

-- 买家账号；邮箱在商户内唯一，所有账号表独立生成ID。
CREATE TABLE `user` (
  merchant_id BIGINT NOT NULL,
  id BIGINT NOT NULL AUTO_INCREMENT,
  email VARCHAR(128) NOT NULL,
  name VARCHAR(100) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  code CHAR(8) NOT NULL,
  customer_type VARCHAR(16) NOT NULL,
  price_tier CHAR(2) NOT NULL,
  active TINYINT NOT NULL DEFAULT 1,
  referrer_id BIGINT NULL,
  commission_rate DECIMAL(5,2) NULL,
  auth_version BIGINT NOT NULL DEFAULT 0,
  email_verified_at DATETIME(6) NULL,
  last_login_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  created_by_user_id BIGINT NULL,
  created_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  updated_by_user_id BIGINT NULL,
  updated_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(id), UNIQUE KEY uk_user_email(merchant_id,email),
  UNIQUE KEY uk_user_customer_code(code), KEY idx_user_merchant(merchant_id,id),
  KEY idx_user_merchant_created(merchant_id,deleted,created_at,id),
  KEY idx_user_active(deleted,active,id), KEY idx_user_referrer(referrer_id,deleted,id),
  KEY idx_user_customer_type(deleted,customer_type,id), KEY idx_user_last_login(deleted,last_login_at,id),
  KEY idx_platform_user_created(created_at,id)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE customer_registration_challenge (
  merchant_id BIGINT NOT NULL,
  email VARCHAR(128) NOT NULL,
  code_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  resend_after DATETIME(6) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  consumed_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (merchant_id,email),
  KEY idx_customer_registration_expiry (expires_at)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE seller (
  id BIGINT NOT NULL AUTO_INCREMENT,
  merchant_id BIGINT NOT NULL,
  account VARCHAR(64) NOT NULL,
  name VARCHAR(100) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  active TINYINT NOT NULL DEFAULT 1,
  merchant_admin TINYINT NOT NULL DEFAULT 0,
  auth_version BIGINT NOT NULL DEFAULT 0,
  last_login_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  created_by_user_id BIGINT NULL,
  created_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  updated_by_user_id BIGINT NULL,
  updated_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(id), UNIQUE KEY uk_seller_account(account), KEY idx_seller_merchant(merchant_id,deleted,active,id),
  KEY idx_seller_merchant_admin(merchant_id,merchant_admin,deleted,id)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE admin (
  id BIGINT NOT NULL AUTO_INCREMENT,
  account VARCHAR(64) NOT NULL,
  name VARCHAR(100) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  active TINYINT NOT NULL DEFAULT 1,
  auth_version BIGINT NOT NULL DEFAULT 0,
  last_login_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  created_by_user_id BIGINT NULL,
  created_by_type VARCHAR(16) NOT NULL DEFAULT 'platform',
  updated_by_user_id BIGINT NULL,
  updated_by_type VARCHAR(16) NOT NULL DEFAULT 'platform',
  deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(id), UNIQUE KEY uk_admin_account(account)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 商家成员菜单授权；seller_id 只指向 seller。
CREATE TABLE seller_permission (
  id BIGINT NOT NULL AUTO_INCREMENT,
  seller_id BIGINT NOT NULL,
  menu_code VARCHAR(32) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  created_by_user_id BIGINT NULL,
  created_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  updated_by_user_id BIGINT NULL,
  updated_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(id), UNIQUE KEY uk_seller_menu(seller_id,menu_code,deleted), KEY idx_seller_permissions(seller_id,deleted)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 客户地址；每个客户最多一个有效默认地址由应用事务维护
CREATE TABLE `customer_address` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `customer_id` BIGINT NOT NULL COMMENT '客户用户ID',
  `recipient_name` VARCHAR(100) NOT NULL COMMENT '收件人姓名',
  `street` VARCHAR(200) NOT NULL COMMENT '街道地址',
  `apt` VARCHAR(100) NULL COMMENT '详细地址补充',
  `city` VARCHAR(100) NOT NULL COMMENT '城市',
  `region` VARCHAR(100) NULL COMMENT '省州地区',
  `postal_code` VARCHAR(20) NOT NULL COMMENT '邮政编码',
  `phone` VARCHAR(32) NOT NULL COMMENT '联系电话',
  `is_default` TINYINT NOT NULL DEFAULT 0 COMMENT '是否默认地址：1默认，0非默认',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
  `created_by_user_id` BIGINT NULL COMMENT '创建用户ID',
  created_by_type VARCHAR(16) NOT NULL DEFAULT 'customer',
  `updated_by_user_id` BIGINT NULL COMMENT '最后更新用户ID',
  updated_by_type VARCHAR(16) NOT NULL DEFAULT 'customer',
  `deleted` BIGINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0有效，非0为已删除行自身ID',
  PRIMARY KEY (`id`),
  KEY `idx_address_customer` (`customer_id`,`deleted`,`id`),
  KEY `idx_address_customer_default` (`customer_id`,`deleted`,`is_default`,`id`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='客户地址；默认地址由is_default标记';

-- 商品类目
CREATE TABLE `category` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `merchant_id` BIGINT NOT NULL COMMENT '所属商户ID',
  `name` VARCHAR(100) NOT NULL COMMENT '名称',
  `sort_no` INT NOT NULL COMMENT '排序序号',
  `active` TINYINT NOT NULL DEFAULT 1 COMMENT '启用状态：1启用，0停用',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
  `created_by_user_id` BIGINT NULL COMMENT '创建用户ID',
  created_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  `updated_by_user_id` BIGINT NULL COMMENT '最后更新用户ID',
  updated_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  `deleted` BIGINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0有效，非0为已删除行自身ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_category_merchant_name` (`merchant_id`,`name`),
  KEY `idx_category_catalog` (`merchant_id`,`deleted`,`active`,`sort_no`,`id`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品类目';

-- 商品与当前可售库存，历史库存身份保留
CREATE TABLE `product` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `merchant_id` BIGINT NOT NULL COMMENT '所属商户ID',
  `category_id` BIGINT NOT NULL COMMENT '商品类目ID',
  `sku` VARCHAR(64) NOT NULL COMMENT '商品SKU',
  `name` VARCHAR(200) NOT NULL COMMENT '名称',
  `description` VARCHAR(2000) NULL COMMENT '商品描述',
  `product_type` VARCHAR(16) NOT NULL COMMENT '商品类型：regular正品，gift赠品',
  `active` TINYINT NOT NULL DEFAULT 0 COMMENT '启用状态：1上架，0下架',
  `base_price` DECIMAL(18,2) NOT NULL COMMENT '商品基础售价',
  `cost_price` DECIMAL(18,2) NULL COMMENT '商品成本价',
  `stock_available` INT NOT NULL DEFAULT 0 COMMENT '当前可售库存',
  `price_revision` BIGINT NOT NULL DEFAULT 0 COMMENT '商品价格修订号',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
  `created_by_user_id` BIGINT NULL COMMENT '创建用户ID',
  created_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  `updated_by_user_id` BIGINT NULL COMMENT '最后更新用户ID',
  updated_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  `deleted` BIGINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0有效，非0为已删除行自身ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_merchant_sku` (`merchant_id`,`sku`),
  KEY `idx_product_catalog` (`merchant_id`,`category_id`,`deleted`,`active`,`product_type`,`id`),
  KEY `idx_product_stock` (`merchant_id`,`deleted`,`stock_available`,`id`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品与当前可售库存，历史库存身份保留';

-- V1至V6等级价，正品NULL表示回默认价；替换时物理删除旧行
CREATE TABLE `product_tier_price` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `product_id` BIGINT NOT NULL COMMENT '商品ID',
  `tier` CHAR(2) NOT NULL COMMENT '价格等级：V1～V6',
  `price` DECIMAL(18,2) NULL COMMENT '价格',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
  `created_by_user_id` BIGINT NULL COMMENT '创建用户ID',
  created_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  `updated_by_user_id` BIGINT NULL COMMENT '最后更新用户ID',
  updated_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_tier` (`product_id`,`tier`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='V1至V6等级价，正品NULL表示回默认价，替换时物理删除旧行';

-- 各客户等级独立配置的商品数量阶梯；无配置时沿用固定等级价
CREATE TABLE `product_quantity_price` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `product_id` BIGINT NOT NULL,
  `tier` CHAR(2) NOT NULL,
  `min_quantity` INT NOT NULL,
  `unit_price` DECIMAL(18,2) NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `created_by_user_id` BIGINT NULL,
  created_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  `updated_by_user_id` BIGINT NULL,
  updated_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  `deleted` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_quantity_price` (`product_id`,`tier`,`min_quantity`,`deleted`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品各客户等级数量阶梯价';

-- 客户购物车，一客户一商品一行，移除或下单消费时物理删除
CREATE TABLE `cart_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `customer_id` BIGINT NOT NULL COMMENT '客户用户ID',
  `product_id` BIGINT NOT NULL COMMENT '商品ID',
  `quantity` INT NOT NULL COMMENT '数量',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
  `created_by_user_id` BIGINT NULL COMMENT '创建用户ID',
  created_by_type VARCHAR(16) NOT NULL DEFAULT 'customer',
  `updated_by_user_id` BIGINT NULL COMMENT '最后更新用户ID',
  updated_by_type VARCHAR(16) NOT NULL DEFAULT 'customer',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cart_customer_product` (`customer_id`,`product_id`),
  KEY `idx_cart_customer` (`customer_id`,`id`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='客户购物车，一客户一商品一行，物理删除';

-- 订单成交快照、独立履约与付款状态
CREATE TABLE `sales_order` (
  `merchant_id` BIGINT NOT NULL COMMENT '所属商户ID',
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `order_no` VARCHAR(64) NOT NULL COMMENT '订单编号',
  `quote_token_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '已消费报价令牌SHA-256，阻止同一报价重复创建订单',
  `customer_id` BIGINT NOT NULL COMMENT '客户用户ID',
  `customer_code_snapshot` CHAR(8) NOT NULL COMMENT '客户编号快照',
  `customer_name_snapshot` VARCHAR(100) NOT NULL COMMENT '客户名称快照',
  `customer_email_snapshot` VARCHAR(128) NOT NULL COMMENT '客户邮箱快照',
  `order_status` VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT '履约状态：pending待处理，packed已打包，shipped已发货，received已收货，completed已完成，cancelled已取消',
  `payment_status` VARCHAR(32) NOT NULL DEFAULT 'unpaid' COMMENT '支付状态：unpaid待支付，pending_verification待验证，partially_paid部分支付，paid已付清',
  `audit_status` VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT '审核状态：pending待审核，approved已通过，rejected未通过，withdrawn审核前撤回',
  `audited_by_user_id` BIGINT NULL COMMENT '审核人seller.id',
  `audited_at` DATETIME(6) NULL COMMENT '审核时间（UTC）',
  `audit_remark` VARCHAR(1000) NULL COMMENT '审核备注',
  `currency` CHAR(3) NOT NULL DEFAULT 'USD' COMMENT '订单币种：仅USD',
  `subtotal` DECIMAL(18,2) NOT NULL COMMENT '商品小计',
  `shipping_fee` DECIMAL(18,2) NOT NULL COMMENT '下单时按客户等级确定的运费快照',
  `discount_amount` DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '审核编辑时给予的商品优惠额',
  `total_amount` DECIMAL(18,2) NOT NULL COMMENT '最终应付金额：商品小计加运费减优惠额',
  `address_snapshot` TEXT NOT NULL COMMENT '收货地址快照JSON',
  `recipient_name` VARCHAR(100) NOT NULL COMMENT '收件人姓名',
  `city` VARCHAR(100) NOT NULL COMMENT '城市',
  `buyer_note` VARCHAR(1000) NULL COMMENT '客户备注',
  `internal_note` VARCHAR(1000) NULL COMMENT '内部备注',
  `referrer_id_snapshot` BIGINT NULL COMMENT '下单时推荐人客户ID',
  `referrer_name_snapshot` VARCHAR(100) NULL COMMENT '推荐人名称快照',
  `commission_rate_snapshot` DECIMAL(5,2) NULL COMMENT '下单时佣金比例快照',
  `commission_enabled_snapshot` TINYINT NOT NULL DEFAULT 1 COMMENT '下单时商户佣金开关快照',
  `completed_at` DATETIME(6) NULL COMMENT '订单完成时间',
  `cancelled_at` DATETIME(6) NULL COMMENT '取消时间',
  `coa_captured_at` DATETIME(6) NULL COMMENT 'COA快照完成时间',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
  `created_by_user_id` BIGINT NULL COMMENT '创建用户ID',
  created_by_type VARCHAR(16) NOT NULL DEFAULT 'customer',
  `updated_by_user_id` BIGINT NULL COMMENT '最后更新用户ID',
  updated_by_type VARCHAR(16) NOT NULL DEFAULT 'customer',
  KEY `idx_sales_order_merchant` (`merchant_id`,`id`),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  UNIQUE KEY `uk_order_quote_token_hash` (`quote_token_hash`),
  KEY `idx_order_customer_date` (`customer_id`,`created_at`,`id`),
  KEY `idx_order_date` (`merchant_id`,`created_at`,`id`),
  KEY `idx_order_customer_payment` (`customer_id`,`payment_status`,`created_at`,`id`),
  KEY `idx_order_payment_date` (`merchant_id`,`payment_status`,`created_at`,`id`),
  KEY `idx_order_status_date` (`merchant_id`,`order_status`,`created_at`,`id`),
  KEY `idx_order_audit_date` (`merchant_id`,`audit_status`,`created_at`,`id`),
  KEY `idx_platform_order_created` (`created_at`,`id`),
  KEY `idx_platform_order_audit` (`audit_status`,`created_at`,`id`),
  KEY `idx_platform_order_payment` (`payment_status`,`created_at`,`id`),
  KEY `idx_platform_order_fulfillment` (`audit_status`,`order_status`,`created_at`,`id`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单成交快照、独立履约与付款状态';

-- 订单商品成交快照，移除行软删除
CREATE TABLE `order_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `product_id` BIGINT NOT NULL COMMENT '商品ID',
  `sku_snapshot` VARCHAR(64) NOT NULL COMMENT '商品SKU快照',
  `name_snapshot` VARCHAR(200) NOT NULL COMMENT '名称快照',
  `type_snapshot` VARCHAR(16) NOT NULL COMMENT '商品类型快照：regular正品，gift赠品',
  `quantity` INT NOT NULL COMMENT '数量',
  `unit_price` DECIMAL(18,2) NOT NULL COMMENT '商品成交单价',
  `line_amount` DECIMAL(18,2) NOT NULL COMMENT '订单行金额',
  `unit_cost_snapshot` DECIMAL(18,2) NULL COMMENT '商品单位成本快照',
  `price_source` VARCHAR(16) NOT NULL COMMENT '成交价格来源：base_price默认价，tier_price等级价，quantity_price数量阶梯价，manual人工改价',
  `price_tier_snapshot` CHAR(2) NULL COMMENT '成交价格等级快照：V1～V6；base_price或manual时为空',
  `sort_no` INT NOT NULL COMMENT '排序序号',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
  `created_by_user_id` BIGINT NULL COMMENT '创建用户ID',
  created_by_type VARCHAR(16) NOT NULL DEFAULT 'customer',
  `updated_by_user_id` BIGINT NULL COMMENT '最后更新用户ID',
  updated_by_type VARCHAR(16) NOT NULL DEFAULT 'customer',
  `deleted` BIGINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0有效，非0为已删除行自身ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_product_active` (`order_id`,`product_id`,`deleted`),
  KEY `idx_item_order_sort` (`order_id`,`deleted`,`sort_no`,`id`),
  KEY `idx_item_product` (`product_id`,`order_id`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单商品成交快照，移除行软删除';

-- 下单或添加商品时保存该订单行适用的完整数量阶梯；旧订单没有快照
CREATE TABLE `order_item_quantity_price` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `order_item_id` BIGINT NOT NULL,
  `min_quantity` INT NOT NULL,
  `unit_price` DECIMAL(18,2) NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_item_quantity_price` (`order_item_id`,`min_quantity`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单商品数量阶梯价快照';

-- 包裹履约；整单金额、支付、佣金及完成快照仍保存在订单
CREATE TABLE `order_parcel` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT NOT NULL,
  `parcel_uuid` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '打包业务唯一标识，同时绑定上传凭证',
  `parcel_no` INT NOT NULL COMMENT '订单内单调递增包裹编号，重新分包不复用',
  `status` VARCHAR(16) NOT NULL COMMENT 'packed、shipped、received、repacked（重新分包后归档）',
  `tracking_no` VARCHAR(128) NULL,
  `carrier` VARCHAR(64) NULL,
  `packed_at` DATETIME(6) NULL COMMENT '历史缺失时为空',
  `shipped_at` DATETIME(6) NULL,
  `received_at` DATETIME(6) NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `created_by_user_id` BIGINT NULL,
  created_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  `updated_by_user_id` BIGINT NULL,
  updated_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_parcel_uuid` (`parcel_uuid`),
  UNIQUE KEY `uk_order_parcel_no` (`order_id`,`parcel_no`),
  KEY `idx_parcel_order_status` (`order_id`,`status`),
  KEY `idx_parcel_tracking` (`tracking_no`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单包裹，仅追加或归档';

CREATE TABLE `order_parcel_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `parcel_id` BIGINT NOT NULL,
  `order_item_id` BIGINT NOT NULL COMMENT '关联订单商品行快照',
  `quantity` INT NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_parcel_item` (`parcel_id`,`order_item_id`),
  KEY `idx_parcel_item_order_item` (`order_item_id`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='包裹商品分配，不重复变动库存';

-- 订单事件与前后值，仅追加
CREATE TABLE `order_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `parcel_id` BIGINT NULL COMMENT '包裹履约事件关联，其余事件为空',
  `operation_id` VARCHAR(64) NOT NULL COMMENT '服务端单次操作审计ID，用于关联库存流水和订单事件',
  `action` VARCHAR(32) NOT NULL COMMENT '订单动作：submitted、customer_updated、seller_updated、cancelled、payment_submitted、payment_confirmed、payment_link_requested、packed、shipped、received、completed',
  `from_status` VARCHAR(32) NULL COMMENT '变更前状态：订单状态或支付状态，取值见order_status/payment_status',
  `to_status` VARCHAR(32) NULL COMMENT '变更后状态：订单状态或支付状态，取值见order_status/payment_status',
  `before_data` LONGTEXT NULL COMMENT '操作前数据JSON',
  `after_data` LONGTEXT NULL COMMENT '操作后数据JSON',
  `note` VARCHAR(256) NULL COMMENT '操作备注',
  `occurred_at` DATETIME(6) NOT NULL COMMENT '事件发生时间',
  `actor_user_id` BIGINT NOT NULL COMMENT '操作用户ID',
  actor_type VARCHAR(16) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_operation_action` (`order_id`,`operation_id`,`action`),
  KEY `idx_event_order_time` (`order_id`,`occurred_at`,`id`),
  KEY `idx_event_order_action` (`order_id`,`action`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单事件与前后值，仅追加';

-- 文件清单，文件内容仍在本地磁盘；file_uuid 是外部引用的稳定身份
CREATE TABLE `file_asset` (
  `merchant_id` BIGINT NOT NULL COMMENT '所属商户ID；包括COA文件在内的全部文件均为商户私有',
  `file_uuid` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '文件UUID；由应用生成并永久不复用',
  `stored_file_name` VARCHAR(64) NOT NULL COMMENT '服务端存储文件名',
  `original_file_name` VARCHAR(255) NOT NULL COMMENT '原始文件名',
  `purpose` VARCHAR(32) NOT NULL COMMENT '文件用途：coa、payment_channel_qr、order_fulfillment、order_shipping_label',
  `media_type` VARCHAR(64) NOT NULL COMMENT '文件MIME类型：application/pdf、image/jpeg、image/png、image/gif、image/bmp',
  `size_bytes` BIGINT NOT NULL COMMENT '文件字节数',
  `sha256` CHAR(64) NOT NULL COMMENT '文件SHA-256摘要',
  `status` VARCHAR(16) NOT NULL DEFAULT 'available' COMMENT '文件状态：available可引用，unavailable不可引用',
  `uploaded_at` DATETIME(6) NOT NULL COMMENT '文件上传时间',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
  `created_by_user_id` BIGINT NOT NULL COMMENT '创建用户ID',
  created_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  `updated_by_user_id` BIGINT NULL COMMENT '最后更新用户ID',
  updated_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  `deleted` BIGINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0有效，非0为已删除行自身ID',
  PRIMARY KEY (`file_uuid`),
  UNIQUE KEY `uk_file_stored_name` (`stored_file_name`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地文件清单和完整性元数据';

-- 履约节点关联，只保存 file_uuid，仅追加
CREATE TABLE `order_event_file` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `event_id` BIGINT NOT NULL COMMENT '订单事件ID',
  `file_uuid` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '文件UUID',
  `stage` VARCHAR(16) NOT NULL COMMENT '履约阶段：packed打包，shipped发货',
  `sort_no` INT NOT NULL COMMENT '排序序号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_file` (`event_id`,`file_uuid`),
  KEY `idx_event_file_order` (`order_id`,`event_id`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='履约节点文件关联，使用文件UUID';

-- 库存不可变流水，每命令每商品汇总一次
CREATE TABLE `stock_movement` (
  `merchant_id` BIGINT NOT NULL COMMENT '所属商户ID',
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `product_id` BIGINT NOT NULL COMMENT '商品ID',
  `order_id` BIGINT NULL COMMENT '订单ID',
  `movement_type` VARCHAR(32) NOT NULL COMMENT '库存变动类型：order_create下单扣减，order_edit订单编辑差额，order_cancel取消回补，manual_adjust人工调整',
  `quantity_delta` INT NOT NULL COMMENT '库存变动数量',
  `quantity_before` INT NOT NULL COMMENT '变动前可售库存',
  `quantity_after` INT NOT NULL COMMENT '变动后可售库存',
  `reason` VARCHAR(1000) NULL COMMENT '操作原因',
  `operation_id` VARCHAR(64) NOT NULL COMMENT '服务端单次操作审计ID',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
  `created_by_user_id` BIGINT NULL COMMENT '创建用户ID',
  created_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  KEY `idx_stock_movement_merchant` (`merchant_id`,`id`),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock_operation_product` (`operation_id`,`product_id`),
  KEY `idx_stock_product_time` (`product_id`,`created_at`,`id`),
  KEY `idx_stock_order` (`order_id`,`id`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存不可变流水，每命令每商品汇总一次';

-- 收款渠道，列表按 sort_no/id 排序，第一条有效渠道作为推荐渠道
CREATE TABLE `payment_channel` (
  `merchant_id` BIGINT NOT NULL COMMENT '所属商户ID',
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `name` VARCHAR(32) NOT NULL COMMENT '名称',
  `currency_code` VARCHAR(32) NOT NULL COMMENT '收款币种代码',
  `network` VARCHAR(32) NOT NULL COMMENT '收款网络',
  `receiving_address` VARCHAR(120) NOT NULL COMMENT '收款地址',
  `instructions` VARCHAR(240) NULL COMMENT '收款说明',
  `additional_rate` DECIMAL(5,2) NULL COMMENT '渠道附加费率百分比，例如5.00表示5%',
  `qr_file_uuid` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '收款二维码文件UUID',
  `active` TINYINT NOT NULL DEFAULT 1 COMMENT '启用状态：1启用，0停用',
  `sort_no` INT NOT NULL COMMENT '排序序号',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
  `created_by_user_id` BIGINT NULL COMMENT '创建用户ID',
  created_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  `updated_by_user_id` BIGINT NULL COMMENT '最后更新用户ID',
  updated_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  `deleted` BIGINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0有效，非0为已删除行自身ID',
  KEY `idx_payment_channel_merchant` (`merchant_id`,`id`),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_name` (`merchant_id`,`name`),
  KEY `idx_channel_available` (`merchant_id`,`deleted`,`active`,`sort_no`,`id`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收款渠道，第一条有效渠道作为推荐渠道';

-- 客户付款逐笔申报，仅追加；锁订单保证同时最多一笔待核验申报
CREATE TABLE `payment_submission` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `customer_id` BIGINT NOT NULL COMMENT '客户用户ID',
  `channel_id` BIGINT NOT NULL COMMENT '收款渠道ID；0 为站外线上支付链接虚拟渠道',
  `channel_snapshot` TEXT NOT NULL COMMENT '收款渠道快照JSON',
  `original_transaction_no` VARCHAR(256) NOT NULL COMMENT '客户原始交易号',
  `declared_order_total` DECIMAL(18,2) NOT NULL COMMENT '申报时订单总额快照',
  `declared_amount` DECIMAL(18,2) NOT NULL COMMENT '本次申报抵扣订单款金额',
  `status` VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending待核验，confirmed已确认',
  `submitted_at` DATETIME(6) NOT NULL COMMENT '付款申报时间',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
  `created_by_user_id` BIGINT NULL COMMENT '创建用户ID',
  created_by_type VARCHAR(16) NOT NULL DEFAULT 'customer',
  `updated_by_user_id` BIGINT NULL COMMENT '最后更新用户ID',
  updated_by_type VARCHAR(16) NOT NULL DEFAULT 'customer',
  PRIMARY KEY (`id`),
  KEY `idx_submission_order_status` (`order_id`,`status`,`id`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='逐笔付款原始申报，保留申报快照，交易号不唯一';

-- 不可变逐笔收款事实，允许跨订单相同交易号
CREATE TABLE `payment_receipt` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `submission_id` BIGINT NULL COMMENT '付款申报ID',
  `channel_id` BIGINT NOT NULL COMMENT '收款渠道ID',
  `channel_snapshot` TEXT NOT NULL COMMENT '收款渠道快照JSON',
  `verified_transaction_no` VARCHAR(256) NOT NULL COMMENT '核验交易号',
  `received_amount` DECIMAL(18,2) NOT NULL COMMENT '实际收款金额',
  `currency` CHAR(3) NOT NULL DEFAULT 'USD' COMMENT '订单币种：仅USD',
  `verified_by_user_id` BIGINT NOT NULL COMMENT '收款核验人seller.id',
  `verified_at` DATETIME(6) NOT NULL COMMENT '收款核验时间',
  `internal_note` VARCHAR(1000) NULL COMMENT '内部备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_receipt_submission` (`submission_id`),
  KEY `idx_receipt_order` (`order_id`,`verified_at`,`id`),
  KEY `idx_platform_receipt_verified` (`verified_at`,`order_id`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='不可变逐笔收款事实，允许跨订单相同交易号';

-- 当前COA元数据及当前文件 UUID，无证书版本实体
CREATE TABLE `coa_certificate` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `merchant_id` BIGINT NOT NULL COMMENT '所属商户ID',
  `name` VARCHAR(200) NOT NULL COMMENT '名称',
  `lot` VARCHAR(100) NULL COMMENT 'COA批次号',
  `purity` VARCHAR(32) NULL COMMENT 'COA纯度信息',
  `fill` VARCHAR(64) NULL COMMENT 'COA填充信息',
  `test_date` DATE NULL COMMENT 'COA检测日期',
  `current_file_uuid` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '当前COA文件UUID',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
  `created_by_user_id` BIGINT NULL COMMENT '创建用户ID',
  created_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  `updated_by_user_id` BIGINT NULL COMMENT '最后更新用户ID',
  updated_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  `deleted` BIGINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0有效，非0为已删除行自身ID',
  PRIMARY KEY (`id`),
  KEY `idx_coa_merchant_created` (`merchant_id`,`deleted`,`created_at`,`id`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商户私有COA元数据及当前文件UUID，无证书版本实体';

-- COA当前和历史绑定，一证最多一有效绑定
CREATE TABLE `product_coa_link` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `merchant_id` BIGINT NOT NULL COMMENT '所属商户ID；必须与商品和COA一致',
  `product_id` BIGINT NOT NULL COMMENT '商品ID',
  `coa_id` BIGINT NOT NULL COMMENT 'COA证书ID',
  `ended_at` DATETIME(6) NULL COMMENT '绑定结束时间',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
  `created_by_user_id` BIGINT NOT NULL COMMENT '创建用户ID',
  created_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  `updated_by_user_id` BIGINT NULL COMMENT '最后更新用户ID',
  updated_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  `deleted` BIGINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0有效，非0为已删除行自身ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_coa_merchant_active` (`merchant_id`,`coa_id`,`deleted`),
  KEY `idx_product_coa_active` (`merchant_id`,`product_id`,`deleted`,`coa_id`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商户内COA当前和历史绑定，一证最多一有效绑定';

-- 订单完成时证书及绑定快照，只存 file_uuid
CREATE TABLE `order_item_coa_snapshot` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `order_item_id` BIGINT NOT NULL COMMENT '订单商品行ID',
  `product_id` BIGINT NOT NULL COMMENT '商品ID',
  `coa_id` BIGINT NOT NULL COMMENT 'COA证书ID',
  `source_link_id` BIGINT NOT NULL COMMENT '来源COA绑定ID',
  `name_snapshot` VARCHAR(200) NOT NULL COMMENT '名称快照',
  `lot_snapshot` VARCHAR(100) NULL COMMENT 'COA批次号快照',
  `purity_snapshot` VARCHAR(32) NULL COMMENT 'COA纯度信息快照',
  `fill_snapshot` VARCHAR(64) NULL COMMENT 'COA填充信息快照',
  `test_date_snapshot` DATE NULL COMMENT 'COA检测日期快照',
  `file_uuid` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '文件UUID',
  `linked_by_user_id_snapshot` BIGINT NOT NULL COMMENT 'COA绑定人seller.id快照',
  `linked_at_snapshot` DATETIME(6) NOT NULL COMMENT 'COA绑定时间快照',
  `captured_at` DATETIME(6) NOT NULL COMMENT 'COA快照捕获时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_item_coa_snapshot` (`order_item_id`,`coa_id`),
  KEY `idx_snapshot_order` (`order_id`,`order_item_id`),
  KEY `idx_snapshot_source_link` (`source_link_id`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单完成时证书及绑定快照，只存文件UUID';

-- 仅下单锁定费率大于0的订单生成佣金计提
CREATE TABLE `commission_accrual` (
  `merchant_id` BIGINT NOT NULL COMMENT '所属商户ID',
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `referrer_id` BIGINT NOT NULL COMMENT '当前推荐人客户ID',
  `referred_customer_id` BIGINT NOT NULL COMMENT '被推荐客户ID',
  `referrer_name_snapshot` VARCHAR(100) NOT NULL COMMENT '推荐人名称快照',
  `referred_name_snapshot` VARCHAR(100) NOT NULL COMMENT '被推荐客户名称快照',
  `rate` DECIMAL(5,2) NOT NULL COMMENT '订单锁定佣金比例',
  `base_amount` DECIMAL(18,2) NOT NULL COMMENT '佣金基数快照：优惠后商品小计，不含运费',
  `commission_amount` DECIMAL(18,2) NOT NULL COMMENT '佣金金额',
  `status` VARCHAR(16) NOT NULL DEFAULT 'estimated' COMMENT '佣金状态：estimated待验证，payable待支付，settled已支付，void已作废',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
  `created_by_user_id` BIGINT NULL COMMENT '创建用户ID',
  created_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  `updated_by_user_id` BIGINT NULL COMMENT '最后更新用户ID',
  updated_by_type VARCHAR(16) NOT NULL DEFAULT 'seller',
  KEY `idx_commission_accrual_merchant` (`merchant_id`,`id`),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_accrual_order` (`order_id`),
  KEY `idx_accrual_referrer_status` (`referrer_id`,`status`,`id`),
  KEY `idx_accrual_status_order` (`status`,`order_id`),
  KEY `idx_accrual_referred` (`referred_customer_id`,`order_id`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='仅下单锁定费率大于0的订单生成佣金计提';

-- 线下佣金结算批次，单笔也归批次
CREATE TABLE `commission_settlement_batch` (
  `merchant_id` BIGINT NOT NULL COMMENT '所属商户ID',
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `batch_no` VARCHAR(64) NOT NULL COMMENT '结算批次编号',
  `order_count` INT NOT NULL COMMENT '结算订单数量',
  `total_base` DECIMAL(18,2) NOT NULL COMMENT '结算佣金基数合计',
  `total_commission` DECIMAL(18,2) NOT NULL COMMENT '结算佣金合计',
  `settled_by_user_id` BIGINT NOT NULL COMMENT '结算操作人seller.id',
  `settled_at` DATETIME(6) NOT NULL COMMENT '线下结算时间',
  `remark` VARCHAR(1000) NULL COMMENT '结算备注',
  KEY `idx_commission_settlement_batch_merchant` (`merchant_id`,`id`),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settlement_batch_no` (`batch_no`),
  KEY `idx_settlement_batch_time` (`merchant_id`,`settled_at`,`id`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='线下佣金结算批次，单笔也归批次';

-- 不可变逐单结算，0%禁止写入
CREATE TABLE `commission_settlement` (
  `merchant_id` BIGINT NOT NULL COMMENT '所属商户ID',
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `batch_id` BIGINT NOT NULL COMMENT '结算批次ID',
  `accrual_id` BIGINT NOT NULL COMMENT '佣金计提ID',
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `order_no_snapshot` VARCHAR(64) NOT NULL COMMENT '订单编号快照',
  `referrer_id` BIGINT NOT NULL COMMENT '当前推荐人客户ID',
  `referred_customer_id` BIGINT NOT NULL COMMENT '被推荐客户ID',
  `referrer_name_snapshot` VARCHAR(100) NOT NULL COMMENT '推荐人名称快照',
  `referred_name_snapshot` VARCHAR(100) NOT NULL COMMENT '被推荐客户名称快照',
  `base_amount` DECIMAL(18,2) NOT NULL COMMENT '佣金基数快照：优惠后商品小计，不含运费',
  `rate` DECIMAL(5,2) NOT NULL COMMENT '订单锁定佣金比例',
  `commission_amount` DECIMAL(18,2) NOT NULL COMMENT '佣金金额',
  KEY `idx_commission_settlement_merchant` (`merchant_id`,`id`),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settlement_order` (`order_id`),
  UNIQUE KEY `uk_settlement_accrual` (`accrual_id`),
  KEY `idx_settlement_batch` (`batch_id`,`id`),
  KEY `idx_settlement_referrer` (`merchant_id`,`referrer_id`,`batch_id`,`id`),
  KEY `idx_settlement_referred` (`merchant_id`,`referred_customer_id`,`batch_id`,`id`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='不可变逐单结算，0%禁止写入';

-- 应收/应付账单事实；一订单最多一条应收账单和一条佣金应付账单
CREATE TABLE `billing_entry` (
  `merchant_id` BIGINT NOT NULL COMMENT '所属商户ID',
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `bill_no` VARCHAR(64) NOT NULL COMMENT '账单编号：BILL-订单号或COMM-订单号',
  `bill_type` VARCHAR(32) NOT NULL COMMENT '账单类型：order_receivable应收，commission_payable应付',
  `order_id` BIGINT NOT NULL COMMENT '关联订单ID',
  `order_no_snapshot` VARCHAR(64) NOT NULL COMMENT '订单编号快照',
  `customer_id` BIGINT NOT NULL COMMENT '账单客户ID：应收为下单客户，应付为推荐人',
  `customer_name_snapshot` VARCHAR(100) NOT NULL COMMENT '账单客户名称快照',
  `customer_email_snapshot` VARCHAR(128) NOT NULL COMMENT '账单客户邮箱快照',
  `currency` CHAR(3) NOT NULL DEFAULT 'USD' COMMENT '账单币种：仅USD',
  `amount` DECIMAL(18,2) NOT NULL COMMENT '账单金额',
  `settled_amount` DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '已收或已付金额',
  `status` VARCHAR(32) NOT NULL COMMENT '按账单类型取值：应收为unpaid/pending_verification/partially_paid/paid/void，应付为estimated/payable/settled/void',
  `order_created_at` DATETIME(6) NOT NULL COMMENT '账单日期，统一取订单创建时间',
  `settled_at` DATETIME(6) NULL COMMENT '实际收款核验或佣金结算时间',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '账单创建时间（UTC）',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '账单更新时间（UTC）',
  KEY `idx_billing_entry_merchant` (`merchant_id`,`id`),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_billing_no` (`bill_no`),
  UNIQUE KEY `uk_billing_type_order` (`bill_type`,`order_id`),
  KEY `idx_billing_merchant_date` (`merchant_id`,`order_created_at`,`id`),
  KEY `idx_billing_merchant_type_status_date` (`merchant_id`,`bill_type`,`status`,`order_created_at`,`id`),
  KEY `idx_billing_customer_date` (`customer_id`,`order_created_at`,`id`),
  KEY `idx_billing_type_status_date` (`bill_type`,`status`,`order_created_at`,`id`)
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='应收应付账单事实，状态与收款/结算事实同事务维护';

-- 固定菜单只在首次空库初始化时写入，不重置已存在数据。各等级运费由 merchant.shipping_fee_v1～shipping_fee_v6 保存，在平台商户管理页面维护。
START TRANSACTION;

INSERT INTO permission_menu
  (id, menu_code, name, sort_no)
VALUES
  (1, 'order_audit', '订单审核', 10),
  (2, 'orders', '订单管理', 20),
  (3, 'commissions', '佣金管理', 30),
  (4, 'bills', '账单管理', 40),
  (5, 'customers', '客户管理', 50),
  (6, 'products', '商品管理', 60),
  (7, 'certificates', '分析证书', 70),
  (8, 'categories', '商品类目', 80),
  (9, 'payment_channels', '收款渠道', 90),
  (10, 'members', '成员与权限', 100);

COMMIT;

-- 可选的首个平台管理员。执行本文件前可在同一 MySQL 会话设置以下变量；不设置时不会创建默认账号。
-- @platform_password_hash 必须是 Argon2id 摘要，不接受明文密码。
INSERT INTO admin(account,name,password_hash,active)
SELECT LOWER(TRIM(@platform_account)),TRIM(@platform_name),@platform_password_hash,1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM admin)
  AND CHAR_LENGTH(TRIM(@platform_account)) BETWEEN 1 AND 64
  AND CHAR_LENGTH(TRIM(@platform_name)) BETWEEN 1 AND 100
  AND @platform_password_hash LIKE '$argon2id$%';
