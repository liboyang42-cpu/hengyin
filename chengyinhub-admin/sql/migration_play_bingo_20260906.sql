-- ============================================================================
-- 宾果任务格:主题级九宫格,格子由平台按玩家行为生成,商家零输入。
--
-- 与另外八个玩法的差异:**它不是节点玩法**。没有 validationMethod、不进
--   advanced_config_json、不参与 completeGate。它是挂在主题上的一块面板,
--   靠监听既有的完成事件来填格。
--
-- ⚠️ 填格必须是旁路,不能塞进 advanceProgress 的事务:宾果一个 bug 会让整条
--   游玩链路挂掉。填格失败只记 error 走事后对账,节点该完成还是完成。
--
-- filled_mask / reward_mask 用位图而不是子表:
--   九个格子固定,位运算做幂等天然免并发问题(同一个事件重放,or 上去还是同一个值),
--   而子表要额外一张表 + 一个唯一键才能达到同样效果。
--
-- ⚠️ 本文件只准备,不执行。生产库写操作在用户点头后由 chengyin-db-migrate 执行。
-- ⚠️ 不声明 requires=transaction:本文件含 ALTER/CREATE,MySQL 的 DDL 是隐式提交、
--   不可回滚,runner 的窄事务模式也明确拒绝普通 DDL。按 autocommit 逐句执行。
--   文件里的 UPDATE 是幂等的(搬完就不再匹配 WHERE),重跑安全。
-- ============================================================================

-- ======================= 第一步:只读预检 =======================
-- 期望 0 行。
SELECT table_name
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'play_bingo_card';


-- ======================= 第二步:建表 =======================
CREATE TABLE play_bingo_card (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    member_id    BIGINT      NOT NULL,
    topic_id     BIGINT      NOT NULL,
    cells_json   VARCHAR(2000) NOT NULL COMMENT '九格:[{pos,code,target,label}];开卡时按玩家历史抽,之后不变',
    filled_mask  INT         NOT NULL DEFAULT 0 COMMENT '位图,第 n 位=第 n 格已填。位运算幂等,事件重放无副作用',
    reward_mask  INT         NOT NULL DEFAULT 0 COMMENT '位图,已发过的奖档:bit0 首格 bit1 三连 bit2 满格',
    create_time  DATETIME    NOT NULL,
    update_time  DATETIME    NULL,
    PRIMARY KEY (id),
    -- 一人一主题一张卡
    UNIQUE KEY uk_member_topic (member_id, topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='宾果任务格';


-- ======================= 第三步:写后断言 =======================
SELECT JSON_EXTRACT(IF(COUNT(*) = 1, '[]', 'ASSERT_FAILED__bingo_table_created'), '$[0]') AS bingo_table_created
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'play_bingo_card';

-- 少了 uk_member_topic,同一个人在同一主题下会开出多张卡,进度各算各的。
SELECT JSON_EXTRACT(IF(COUNT(*) = 1, '[]', 'ASSERT_FAILED__bingo_unique_key_present'), '$[0]') AS bingo_unique_key_present
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'play_bingo_card'
  AND index_name = 'uk_member_topic'
  AND seq_in_index = 1;
