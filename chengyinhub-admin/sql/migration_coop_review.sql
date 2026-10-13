-- 合作双向互评(报告§六 · F4-2):活动结束后发布者↔商家互评
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)
-- 影响:新建表,对存量无影响。⚠️ 先于新 jar 落库。

CREATE TABLE IF NOT EXISTS coop_review (
    id          BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
    topic_id    BIGINT(20)   NOT NULL COMMENT '主题',
    from_id     BIGINT(20)   NOT NULL COMMENT '评价人memberId',
    to_id       BIGINT(20)   NOT NULL COMMENT '被评人memberId',
    rating      TINYINT(1)   NOT NULL COMMENT '评分 1~5',
    comment     VARCHAR(255) NULL COMMENT '评语',
    create_time DATETIME     NULL,
    del_flag    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '0存在 2删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_review (topic_id, from_id, to_id),
    KEY idx_review_to (to_id, del_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合作双向互评';
