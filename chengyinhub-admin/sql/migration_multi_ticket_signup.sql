-- 多票报名扩展迁移
-- 执行方式：在 MySQL 命令行连接到生产库后 source 本文件，或在数据库管理工具里运行
-- 注意：执行前请备份 cms_registration 表

-- 1. 参与日期（自由探索票必填，城市定向票留空或存集合时间）
ALTER TABLE cms_registration
    ADD COLUMN participate_date VARCHAR(20) NULL COMMENT '参与日期 yyyy-MM-dd（自由探索）'
    AFTER address_id;

-- 2. 父订单号（同一次报名拆成多条记录时共享同一支付单）
ALTER TABLE cms_registration
    ADD COLUMN parent_order_no VARCHAR(40) NULL COMMENT '父订单号 同一支付的多条记录共享'
    AFTER registration_no;

CREATE INDEX idx_cms_registration_parent_order ON cms_registration(parent_order_no);
CREATE INDEX idx_cms_registration_participate ON cms_registration(owner_id, participate_date);
