-- One-time additive DDL for an existing database before deploying platform operations.
-- This is not run by the application and is not part of the empty-database initializer.
-- Confirm the target schema and follow the deployment database-change procedure before execution.
-- Not rerunnable: after any error, inspect completed ALTER statements before resuming.
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET SESSION time_zone = '+00:00';

ALTER TABLE merchant_event
  ADD KEY idx_merchant_event_platform_time (created_at,id);

ALTER TABLE `user`
  ADD KEY idx_platform_user_created (created_at,id);

ALTER TABLE sales_order
  ADD KEY idx_platform_order_created (created_at,id),
  ADD KEY idx_platform_order_audit (audit_status,created_at,id),
  ADD KEY idx_platform_order_payment (payment_status,created_at,id),
  ADD KEY idx_platform_order_fulfillment (audit_status,order_status,created_at,id);

ALTER TABLE payment_receipt
  ADD KEY idx_platform_receipt_verified (verified_at,order_id);

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
