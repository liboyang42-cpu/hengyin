-- D22 团核销台账(《核销拓扑与商家分账基数_设计_2026-07-17.md》§6.3 · 决策 A 拍板 β)
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)
-- 影响:新建一表,对存量无影响。存量 16 个主题全走存量路(coop_order 全表 0 行),不受本表影响。
-- ⚠️ 部署顺序:本迁移必须【先于】新 jar 落库 —— CoopGroupVerifyMapper 引用 coop_group_verify,
--    表不在则 settleCoopTopic 的分流分支查询报错、整批主题拒结。
--
-- 为什么新建表而不复用 cms_registration_scan(决策 A 否决 α):
--   那张表已经背了两种语义 —— registration_type=0 是报名时按节点模板给【模板主】批量插的,
--   =1 才是核销扫码行(CmsRegistrationMapper.xml:367-374 专门写了注释把两者隔开)。
--   再塞第三种「团核销」就是第三次「用一张表解释多种事实」,正是本轮要修的那个病根。
--   且主理人没有玩家报名行,走 α 得先给他造一条,语义别扭。
--
-- 为什么只记「存在性」不记 heads/sales:
--   商家扫团码的时刻,俱乐部可能还没核销完全部玩家 —— 此刻冻结基数必漏。
--   结算时现查(member_id=leader_member_id AND registration_type=1),与 T1 的口径同源。

CREATE TABLE IF NOT EXISTS coop_group_verify (
    id                 BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    topic_id           BIGINT(20) NOT NULL COMMENT '主题(cms_topic.id)',
    coop_order_id      BIGINT(20) NOT NULL COMMENT '该商家的生效合作单 —— 结算按它取分账条款(定稿§6.5-C 结算只认合作单)',
    merchant_member_id BIGINT(20) NOT NULL COMMENT '接待该团的商家 memberId(= coop_order.to_id,to_type=merchant)',
    leader_member_id   BIGINT(20) NOT NULL COMMENT '出示团码的主理人/管理员 memberId —— 结算按它反查该团已核销的玩家',
    verify_time        DATETIME   NOT NULL COMMENT '商家扫团码的时刻',
    PRIMARY KEY (id),
    -- 幂等:同一商家对同一团只算接待一次。商家重复扫码不叠加基数(叠加=凭一次接待拿两份钱)。
    UNIQUE KEY uk_group_verify (topic_id, merchant_member_id, leader_member_id),
    KEY idx_group_verify_topic (topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='D22 团核销台账:哪个商家接待了哪个领队带的团';
