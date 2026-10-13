-- 商家商业化权益订单幂等键。仅生成迁移；必须走 chengyin-db-migrate 预检并在合并前人工落库。
ALTER TABLE oms_order
    ADD COLUMN commerce_request_id VARCHAR(64) NULL COMMENT '商家权益下单客户端幂等键' AFTER payment_transaction_id,
    ADD UNIQUE KEY uk_oms_order_commerce_request
        (member_id, mms_merchant_id, biz_type, commerce_request_id);
