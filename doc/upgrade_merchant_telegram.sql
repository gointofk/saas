-- Existing databases only: run once after confirming merchant.telegram_url is absent.
-- The application does not execute this script automatically.
ALTER TABLE merchant
  ADD COLUMN telegram_url VARCHAR(512) NULL COMMENT 'HTTPS Telegram 联系链接；NULL 表示未配置' AFTER whatsapp_number;
