-- One-time index upgrade for existing databases created before the public COA indexes.
-- Do not execute this file for a fresh database initialized from init_mysql56.sql.
-- Before running, confirm that these index names are not already present.
-- This file is intentionally manual and is not executed by the application.

ALTER TABLE `coa_certificate`
  ADD KEY `idx_coa_public_uuid` (`current_file_uuid`,`merchant_id`,`deleted`);

ALTER TABLE `order_item_coa_snapshot`
  ADD KEY `idx_snapshot_public_uuid` (`file_uuid`,`order_id`);
