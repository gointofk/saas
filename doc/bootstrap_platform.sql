-- 在 init_mysql56.sql 初始化的全新库中，由部署人员显式执行。
-- 先在同一数据库会话设置 @platform_account、@platform_name、@platform_password_hash。
-- password_hash 必须由 Argon2id 工具生成；此文件不提供默认密码，不自动提升商家账号。
-- 仅当平台尚无账号时插入；首次引导后，重复执行不会更改任何账号。
INSERT INTO admin(account,name,password_hash,active)
SELECT LOWER(TRIM(@platform_account)),TRIM(@platform_name),@platform_password_hash,1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM admin)
  AND CHAR_LENGTH(TRIM(@platform_account)) BETWEEN 1 AND 64
  AND CHAR_LENGTH(TRIM(@platform_name)) BETWEEN 1 AND 100
  AND @platform_password_hash LIKE '$argon2id$%';
