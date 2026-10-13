-- 俱乐部客户档案(标签 + 备注)。Figma K2 / K2-F「标签与备注」。
-- 本文件仅为迁移草案,未执行。执行前必须走 chengyin-db-migrate 只读预检并由总控确认。
--
-- 只存人工写入的两样东西:标签与备注。
-- 到店次数 / 待核销 / 已退款 / 累计实付 全部由 cms_registration 现算(ClubCrmMapper.xml),
-- 不落快照表 —— 快照会和真值漂移,而这些数字每一处都要能对上账。
--
-- ponytail: 标签整列覆盖式保存,不留逐条增删的事件流水。
--   商家侧 merchant_customer_tag_assignment 那套 ASSIGN/REMOVE 追加日志是为了可追溯「谁什么时候打的标签」;
--   俱乐部这一版没有这个诉求(一个编辑表单一次保存)。真需要审计时再补一张 club_customer_tag_event,
--   本表的 updated_by/update_time 已经留了「最后是谁改的」这一条最低限度线索。

-- 重复/半迁移预检:必须返回 0 行;有结果即停止,不得继续 CREATE。
SELECT table_name
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name = 'club_customer_profile';

CREATE TABLE club_customer_profile (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    club_id      BIGINT       NOT NULL COMMENT '俱乐部 club.id',
    member_id    BIGINT       NOT NULL COMMENT '客户 ums_member.id(买过本俱乐部票的人)',
    tags_json    VARCHAR(500) NOT NULL DEFAULT '[]' COMMENT '标签 JSON 数组,最多 8 个、单个 12 字',
    remark       VARCHAR(500) NOT NULL DEFAULT ''   COMMENT '备注,最多 200 字',
    updated_by   BIGINT       NOT NULL COMMENT '最后写入者 ums_member.id',
    request_id   VARCHAR(64)  NOT NULL COMMENT '最后一次写入的幂等键',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_club_customer (club_id, member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='俱乐部客户档案(人工标签与备注)';

-- 回读断言:必须恰好返回 1 行,且 uk_club_customer 存在。
SELECT table_name, table_comment
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name = 'club_customer_profile';

-- 回读断言:必须恰好返回 2 行(club_id, member_id 各一行)。
SELECT index_name, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'club_customer_profile'
  AND index_name = 'uk_club_customer'
ORDER BY seq_in_index;

-- 回滚(确认无数据后再执行):
-- DROP TABLE club_customer_profile;
