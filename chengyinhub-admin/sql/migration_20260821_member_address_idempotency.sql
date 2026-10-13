-- 参与人新增幂等键：同一会员同一提交意图只生成一条有效记录。
-- 部署前须走 chengyin-db-migrate；本文件未在任何数据库执行。
ALTER TABLE ums_member_address
    ADD COLUMN request_id VARCHAR(64) NULL COMMENT '客户端新增地址幂等键' AFTER del_flag,
    ADD UNIQUE KEY uk_member_address_request (member_id, request_id);
