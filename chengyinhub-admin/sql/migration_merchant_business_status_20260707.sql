-- 营业状态(营业中/已打烊)。纯展示,不做门禁。ADD nullable/default 列可逆(DROP COLUMN)。
ALTER TABLE mms_merchant ADD COLUMN business_status TINYINT NOT NULL DEFAULT 1 COMMENT '营业状态 1营业中 0已打烊' AFTER account_status;
