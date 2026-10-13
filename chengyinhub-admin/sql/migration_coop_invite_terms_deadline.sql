-- coop_invite 扩列(报告§7.1 · D-1):条款字段 + 双死线字段
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)
-- 影响:对存量表加可空列,全部 NULL 默认,对现有数据/查询无副作用。
-- ⚠️ 部署顺序:本迁移必须先于新 jar 落库,否则 CoopInvite 实体/Mapper 引用新列会 SQL 报错。
-- ⚠️ 双死线依赖:config_deadline(配置期)、expire_time(招募期镜像)是 CoopInviteExpireTask 的输入列。

ALTER TABLE coop_invite
    ADD COLUMN game_id         BIGINT(20)     NULL COMMENT '城市定向:目标游戏(游戏级邀约)'          AFTER topic_id,
    ADD COLUMN target_level    TINYINT(1)     NULL DEFAULT 0 COMMENT '槽位粒度 0主题级/1游戏级'      AFTER game_id,
    ADD COLUMN expire_time     DATETIME       NULL COMMENT '邀约自身过期时间(=主题招募截止,挂起超时判过期)' AFTER handle_time,
    ADD COLUMN config_deadline DATETIME       NULL COMMENT '自由探索:同意后配置游戏死线(超时释放槽位)'   AFTER expire_time,
    ADD COLUMN share_mode      TINYINT(1)     NULL COMMENT '报酬模式 0引流/1分成/2固定'              AFTER config_deadline,
    ADD COLUMN share_rate      DECIMAL(5,2)   NULL COMMENT '分成型比例(share_mode=1)'              AFTER share_mode,
    ADD COLUMN fixed_fee       DECIMAL(10,2)  NULL COMMENT '固定型每核销人头费(share_mode=2)'       AFTER share_rate;

-- status 语义扩展(无需改列,仅登记新取值):0待确认 1已接受 2已拒绝 3已取消 4已顶替(SUPERSEDED) 5已过期(EXPIRED)
ALTER TABLE coop_invite
    MODIFY COLUMN status TINYINT(1) NOT NULL DEFAULT 0
    COMMENT '状态 0待确认 1已接受 2已拒绝 3已取消 4已顶替 5已过期';

-- 加索引:双死线两个 Job 按 (status, expire_time) / (status, config_deadline) 扫描
ALTER TABLE coop_invite
    ADD KEY idx_coop_expire (status, expire_time),
    ADD KEY idx_coop_config (status, config_deadline);
