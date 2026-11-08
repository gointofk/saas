-- Existing databases only: run once after confirming merchant.whatsapp_number is absent.
-- The application does not execute this script automatically.
ALTER TABLE merchant
  ADD COLUMN whatsapp_number VARCHAR(32) NULL COMMENT '国际格式 WhatsApp 售后号码；NULL 表示未配置' AFTER logo_url;
