-- 店铺装修：与注册品牌图片 derivatives 分离。发布前按迁移流程执行。
ALTER TABLE mms_merchant ADD COLUMN derivative_benefits VARCHAR(100) NULL COMMENT '装修页衍生权益文字' AFTER derivatives;
