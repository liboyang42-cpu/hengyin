-- ============================================================================
-- 竞猜:玩家下注 → 商家次日给出正确答案 → 猜中的按模板条件奖励发。
--
-- 与其他八个玩法的结构差异:**答案在玩家提交时还不存在**。所以判定不能在提交时
--   发生,得等商家结算。为了不把玩家卡在节点上,提交即算通关(与估数/比价同一条
--   口径:带运气的玩法不卡进度),奖励延迟发。
--
-- 为什么要 bet 表而不是只用 game_runtime_session.state:
--   结算要按「这个节点这一天猜了 A 的所有人」批量发奖。扫 session 表按 JSON 里的
--   字段筛,既没索引也不好写;bet 表一条索引就够,而且它同时是发奖的幂等键
--   (uk_round_member 保证一人一注,reward_status 保证一份奖只发一次)。
--
-- 一轮 = (node_id, play_day)。竞猜天然是每天一轮(「今天哪款卖最多」),
--   不需要单独的 round 表,省一次 join 和一张表的生命周期管理。
--
-- ⚠️ 本文件只准备,不执行。生产库写操作在用户点头后由 chengyin-db-migrate 执行。
-- ⚠️ 不声明 requires=transaction:本文件含 ALTER/CREATE,MySQL 的 DDL 是隐式提交、
--   不可回滚,runner 的窄事务模式也明确拒绝普通 DDL。按 autocommit 逐句执行。
--   文件里的 UPDATE 是幂等的(搬完就不再匹配 WHERE),重跑安全。
-- ============================================================================

-- ======================= 第一步:只读预检 =======================
-- 期望 0 行。非 0 说明已跑过。
SELECT table_name
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('play_predict_bet', 'play_predict_result');


-- ======================= 第二步:建表 =======================
CREATE TABLE play_predict_bet (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    node_id       BIGINT       NOT NULL COMMENT '竞猜所在节点',
    topic_id      BIGINT       NOT NULL COMMENT '冗余,便于按主题统计',
    activity_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '自玩会话为 0',
    play_day      DATE         NOT NULL COMMENT '按 Asia/Shanghai 的自然日,与结算轮次对齐',
    member_id     BIGINT       NOT NULL,
    option_key    VARCHAR(64)  NOT NULL COMMENT '玩家押的选项',
    reward_status TINYINT      NOT NULL DEFAULT 0 COMMENT '0未结算 1已中奖发奖 2未中奖 3轮次作废',
    create_time   DATETIME     NOT NULL,
    update_time   DATETIME     NULL,
    PRIMARY KEY (id),
    -- 一人一轮只能下一注。这条同时是「重复提交只记一次」的幂等闸。
    UNIQUE KEY uk_round_member (node_id, play_day, member_id),
    -- 结算时按这条批量捞「这一轮押了某个选项的人」
    KEY idx_round_option (node_id, play_day, option_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='竞猜下注';

CREATE TABLE play_predict_result (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    node_id        BIGINT       NOT NULL,
    play_day       DATE         NOT NULL,
    settled_option VARCHAR(64)  NULL COMMENT '商家给出的正确选项;作废时为空',
    settle_status  TINYINT      NOT NULL DEFAULT 0 COMMENT '0待结算 1已结算 2已作废',
    settled_by     BIGINT       NULL COMMENT '谁结的,备查',
    settled_at     DATETIME     NULL,
    create_time    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    -- 一轮只能结算一次。条件更新命中 0 行即为重复结算,不靠应用层记状态。
    UNIQUE KEY uk_round (node_id, play_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='竞猜结算';


-- ======================= 第三步:写后断言 =======================
-- 断言一:两张表都建上了(期望 2)。
SELECT JSON_EXTRACT(IF(COUNT(*) = 2, '[]', 'ASSERT_FAILED__predict_tables_created'), '$[0]') AS predict_tables_created
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('play_predict_bet', 'play_predict_result');

-- 断言二:两个幂等闸真的建上了(期望 2)。
-- 少了 uk_round_member,同一个人能对同一轮下无数注;少了 uk_round,一轮能结算多次、奖重复发。
SELECT JSON_EXTRACT(IF(COUNT(*) = 2, '[]', 'ASSERT_FAILED__predict_unique_keys_present'), '$[0]') AS predict_unique_keys_present
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND index_name IN ('uk_round_member', 'uk_round')
  AND seq_in_index = 1;
