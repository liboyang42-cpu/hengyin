-- cms_topic 扩列(报告§7.4 · D-6):招募死线 + 生命周期状态机
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)
-- 影响:对存量表加可空列,lifecycle 默认 0(草稿),对现有数据/查询无副作用。
-- ⚠️ 部署顺序:本迁移必须先于新 jar 落库,否则 CmsTopic 实体/Mapper 引用新列会 SQL 报错。
-- ⚠️ 双死线依赖:recruit_deadline 是 TopicRecruitDeadlineTask 的输入;lifecycle 是招募/定价/售票门禁的状态位。
-- 说明:存量主题 lifecycle=0,不会被招募到期 Job 命中(Job 只扫 lifecycle=1 招募中)。发布流写入
--       lifecycle=1 + recruit_deadline 的改造属后续 B1(本迁移只铺列,不改发布逻辑)。

ALTER TABLE cms_topic
    ADD COLUMN recruit_deadline     DATETIME   NULL COMMENT '招募截止时间(到期触发 TopicRecruitDeadlineTask 判定)' AFTER merchant_sign_up_end_date,
    ADD COLUMN recruit_extend_count TINYINT(1) NOT NULL DEFAULT 0 COMMENT '招募期已延长次数(最多 2 次)'          AFTER recruit_deadline,
    ADD COLUMN lifecycle            TINYINT(1) NOT NULL DEFAULT 0 COMMENT '生命周期 0草稿 1招募 2定价 3售票 4进行 5结算 6关闭' AFTER recruit_extend_count;

-- 招募到期 Job 按 (lifecycle, recruit_deadline) 扫描
ALTER TABLE cms_topic
    ADD KEY idx_topic_recruit (lifecycle, recruit_deadline);
