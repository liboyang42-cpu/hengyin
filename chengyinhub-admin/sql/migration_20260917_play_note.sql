-- 预制人生 note 留言:跨玩家的一句话,按节点聚合(施工契约 20260917 §2.4)。
-- 为什么新建表:勘察过 dailySign 接力签 —— 后端 CLAIM_DAILY_SIGN 只从 poems 池里选一条
-- 落本局 state(lines / signer),「上一个人留下的话」并没有服务端存储可复用;
-- roam_stamp 是带图的格网邮票(geohash7 + 每人每格一枚),语义与按节点的一句话不同。
-- 故新建。幂等(CREATE TABLE IF NOT EXISTS),只建新表,不动存量表/列。
--
-- 落库是人工步骤(总控执行),纯 SQL 改动不会触发部署。
CREATE TABLE IF NOT EXISTS `play_note` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `topic_id`    BIGINT       NOT NULL COMMENT '主题 id',
  `node_id`     BIGINT       NOT NULL COMMENT '节点 id(留言按这一站聚合)',
  `member_id`   BIGINT       NOT NULL COMMENT '留言的玩家',
  `content`     VARCHAR(80)  NOT NULL COMMENT '留言内容(长度上限由模板 note.maxLength 再收)',
  `del_flag`    TINYINT      NOT NULL DEFAULT 0 COMMENT '0=正常 1=已删(平台侧,本轮无入口)',
  `create_time` DATETIME     NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_node` (`member_id`, `node_id`),
  KEY `idx_node_time` (`node_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预制人生 note 留言(跨玩家,按节点聚合)';
