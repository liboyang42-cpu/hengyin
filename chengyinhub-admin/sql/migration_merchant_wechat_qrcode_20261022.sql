-- 门店微信拆列(owner 2026-10-22 批准)：mms_merchant.wechat 改存商家手填的微信号(文本 ID)，
-- 原来塞在 wechat 里的二维码图片 URL 搬到新列 mms_merchant.wechat_qrcode。
-- 前置：mms_merchant 在 sql/base_schema_20260818.sql。
-- 落库顺序：先手工跑本文件并通过末尾断言，再部署新 jar(新 jar 的 Mapper 会读写 wechat_qrcode)。
-- 幂等：ALTER 前查 information_schema，列已存在就跳过；同名但形状不符(非 varchar(512))fail-closed。
--   搬家 UPDATE 只命中「wechat 像 URL 且 wechat_qrcode 为空」的行：搬完 wechat 被清空，重跑不再匹配。
--   已有 wechat_qrcode 的行不覆盖(那是商家在新页面上传的)，只清掉 wechat 里残留的 URL 文本。
DROP TEMPORARY TABLE IF EXISTS merchant_wechat_qrcode_20261022_guard;
CREATE TEMPORARY TABLE merchant_wechat_qrcode_20261022_guard(id TINYINT PRIMARY KEY);
INSERT INTO merchant_wechat_qrcode_20261022_guard VALUES(1);

-- 目标表必须存在且为 InnoDB；否则下一条 INSERT 撞主键，整个文件停在这里。
INSERT INTO merchant_wechat_qrcode_20261022_guard
SELECT 1 WHERE (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema=DATABASE() AND table_name='mms_merchant' AND engine='InnoDB') <> 1;

-- 已存在的同名列必须正是本迁移要的形状；不符就停，不当成已完成。
INSERT INTO merchant_wechat_qrcode_20261022_guard
SELECT 1 WHERE EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='mms_merchant' AND column_name='wechat_qrcode'
    AND NOT (data_type='varchar' AND character_maximum_length=512 AND is_nullable='YES'));

SET @merchant_wechat_qrcode_ddl := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='mms_merchant' AND column_name='wechat_qrcode'),
  'SELECT 1',
  'ALTER TABLE mms_merchant ADD COLUMN wechat_qrcode VARCHAR(512) NULL DEFAULT NULL COMMENT ''门店微信二维码图片URL'' AFTER wechat, ALGORITHM=INSTANT'
);
PREPARE merchant_wechat_qrcode_stmt FROM @merchant_wechat_qrcode_ddl;
EXECUTE merchant_wechat_qrcode_stmt;
DEALLOCATE PREPARE merchant_wechat_qrcode_stmt;

DROP TEMPORARY TABLE merchant_wechat_qrcode_20261022_guard;

-- 存量搬家：wechat 里是 http(s) 图片地址的行 → 复制到 wechat_qrcode，再清空 wechat。
UPDATE mms_merchant
   SET wechat_qrcode = wechat
 WHERE (wechat LIKE 'http://%' OR wechat LIKE 'https://%')
   AND (wechat_qrcode IS NULL OR wechat_qrcode = '')
   AND CHAR_LENGTH(wechat) <= 512;

UPDATE mms_merchant
   SET wechat = ''
 WHERE (wechat LIKE 'http://%' OR wechat LIKE 'https://%')
   AND wechat_qrcode IS NOT NULL AND wechat_qrcode <> '';

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='mms_merchant' AND column_name='wechat_qrcode'
           AND data_type='varchar' AND character_maximum_length=512) = 1
   AND (SELECT COUNT(*) FROM mms_merchant WHERE wechat LIKE 'http://%' OR wechat LIKE 'https://%') = 0
       AS assertion_ok;
