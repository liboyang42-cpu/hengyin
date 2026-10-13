-- 俱乐部经营配置：会员优先报名、统一优惠价、会员保留名额。
-- 生产执行前必须走 chengyin-db-migrate 的只读预检与用户确认。
ALTER TABLE club
    ADD COLUMN priority_signup_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '成员优先报名 0否1是' AFTER member_count,
    ADD COLUMN member_discount_price DECIMAL(10,2) NULL COMMENT '成员优惠价(元，NULL=不启用)' AFTER priority_signup_enabled,
    ADD COLUMN member_reserved_quota INT NOT NULL DEFAULT 0 COMMENT '成员保留报名名额' AFTER member_discount_price;
