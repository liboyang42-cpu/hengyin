-- ================= migration_orient_geo_ar_team_20261022.sql =================
SELECT 'migration_orient_geo_ar_team_20261022.sql' AS running;
-- Doc2 F1d 城市定向:坐标系(WGS84 入库)/ AR 替代任务 / 队伍进度矩阵 / 安全规则。
-- 只新增表与默认关闭的 sys_config 开关,不改任何既有表、不回填;可重复执行(IF NOT EXISTS + NOT EXISTS)。
-- 新 jar 在开关关闭(含键缺失)时不读写这些表;表缺失时打开开关会报错,所以必须先落库再开开关。
-- ⚠️ 生产执行前必须走 chengyin-db-migrate 的只读预检 + owner 确认,本 PR 不在任何库上执行。
-- 回滚:DROP TABLE orient_node_geo, orient_task_alternative, orient_task_attempt, orient_task_completion, orient_safety_rule;
--       DELETE FROM sys_config WHERE config_key LIKE 'orient.%';
--
-- 落库前只读预检:
--   SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name LIKE 'orient\_%';
--   SELECT config_key, config_value FROM sys_config WHERE config_key LIKE 'orient.%';

-- 1) 节点坐标的 WGS84 归一结果。cms_topic_node.longitude/latitude 仍是 varchar 原值、不改;
--    这里按「原值 + 原坐标系」的指纹缓存一次转换结果,脏值只标记(geo_status=1 + dirty_reason),绝不写成 0。
CREATE TABLE IF NOT EXISTS orient_node_geo (
    node_id BIGINT NOT NULL COMMENT 'cms_topic_node.id',
    topic_id BIGINT NOT NULL,
    source_coord_sys VARCHAR(8) NOT NULL COMMENT '原值坐标系 WGS84/GCJ02/BD09',
    source_lng VARCHAR(255) NULL COMMENT 'cms_topic_node.longitude 原值快照',
    source_lat VARCHAR(255) NULL COMMENT 'cms_topic_node.latitude 原值快照',
    source_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SHA-256(coord_sys|lng|lat),原值变了才重算',
    wgs84_lng DECIMAL(10,7) NULL COMMENT '脏值为 NULL',
    wgs84_lat DECIMAL(10,7) NULL COMMENT '脏值为 NULL',
    geo_status TINYINT NOT NULL DEFAULT 0 COMMENT '0干净 1脏值(隔离,不参与距离判定)',
    dirty_reason VARCHAR(32) NULL COMMENT 'MISSING/NOT_NUMBER/NON_FINITE/OUT_OF_RANGE/ZERO/SWAPPED_SUSPECT',
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (node_id),
    KEY idx_orient_node_geo_topic (topic_id, geo_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='城市定向节点 WGS84 坐标(脏值隔离)';

-- 2) 同一逻辑任务(task_id = cms_topic_node.id)的替代完成方式。没有行 = 老主题,行为不变。
--    刻意没有分数列:分数挂在 task_id 上(F1b 计分),替代路径与主路径同分。
CREATE TABLE IF NOT EXISTS orient_task_alternative (
    id BIGINT NOT NULL AUTO_INCREMENT,
    topic_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL COMMENT 'cms_topic_node.id(逻辑任务)',
    alternative_task_type VARCHAR(8) NOT NULL COMMENT 'SCAN/PHOTO/QUIZ',
    prompt_text VARCHAR(500) NULL COMMENT 'QUIZ 题面 / PHOTO 拍摄要求',
    answer_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'SCAN 码 / QUIZ 答案归一后的 SHA-256;PHOTO 为 NULL',
    enabled TINYINT NOT NULL DEFAULT 1,
    create_by BIGINT NOT NULL COMMENT '配置人(主题发布者)',
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_orient_task_alt (task_id, alternative_task_type),
    KEY idx_orient_task_alt_topic (topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='城市定向 AR 替代任务配置';

-- 3) 每次替代提交一行。(member_id, idempotency_key) 唯一 + request_fingerprint:同键同参重放回原结果,同键异参拒绝。
CREATE TABLE IF NOT EXISTS orient_task_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL DEFAULT 0 COMMENT '0=自玩',
    topic_id BIGINT NOT NULL,
    play_instance_id VARCHAR(64) NOT NULL DEFAULT 'default',
    task_id BIGINT NOT NULL,
    alternative_task_type VARCHAR(8) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    pic_url VARCHAR(500) NULL COMMENT 'PHOTO 提交的照片',
    report_coord_sys VARCHAR(8) NULL COMMENT '客户端上报坐标系(原样记录)',
    wgs84_lng DECIMAL(10,7) NULL COMMENT '上报位置,边界转换一次后的 WGS84',
    wgs84_lat DECIMAL(10,7) NULL,
    distance_m INT NULL COMMENT '与节点 WGS84 的距离;节点坐标脏/缺失时为 NULL',
    node_geo_dirty TINYINT NOT NULL DEFAULT 0 COMMENT '1=节点坐标为脏值,距离未计算',
    status TINYINT NOT NULL COMMENT '0待审核(PHOTO) 1已完成 2已驳回/校验失败 3重复(任务已完成) 4被其他路径抢先',
    review_by BIGINT NULL,
    review_time DATETIME NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_orient_attempt_idem (member_id, idempotency_key),
    KEY idx_orient_attempt_task (task_id, status),
    KEY idx_orient_attempt_scope (member_id, activity_id, topic_id, play_instance_id, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='城市定向替代任务提交(含拍照待审)';

-- 4) 逻辑任务完成事实:一个会话作用域内每个 task_id 只有一行,无论走哪条路径。
CREATE TABLE IF NOT EXISTS orient_task_completion (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL DEFAULT 0 COMMENT '0=自玩',
    topic_id BIGINT NOT NULL,
    play_instance_id VARCHAR(64) NOT NULL DEFAULT 'default',
    task_id BIGINT NOT NULL,
    completed_via VARCHAR(8) NOT NULL COMMENT 'PRIMARY/SCAN/PHOTO/QUIZ',
    source_attempt_id BIGINT NULL COMMENT 'orient_task_attempt.id;主路径为 NULL',
    completed_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_orient_completion (member_id, activity_id, topic_id, play_instance_id, task_id),
    KEY idx_orient_completion_board (activity_id, topic_id, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='城市定向逻辑任务完成(每任务一次)';

-- 5) 主题级安全规则。保险不强制(insurance_required 恒 0);夜间/天气/年龄监护是真闸;SOS 不受任何信誉限制。
CREATE TABLE IF NOT EXISTS orient_safety_rule (
    topic_id BIGINT NOT NULL,
    insurance_required TINYINT NOT NULL DEFAULT 0 COMMENT '恒为 0:保险不作为参与前置条件',
    night_start_minute INT NOT NULL DEFAULT 1200 COMMENT '夜间开始(当日分钟数,默认 20:00)',
    night_end_minute INT NOT NULL DEFAULT 360 COMMENT '夜间结束(次日分钟数,默认 06:00)',
    weather_block TINYINT NOT NULL DEFAULT 0 COMMENT '1=天气预警停玩',
    weather_reason VARCHAR(200) NULL,
    weather_block_until DATETIME NULL COMMENT 'NULL=直到手动解除',
    update_by BIGINT NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='城市定向安全规则';

-- 6) 开关:一律默认关(键缺失同样视为关);只在键缺失时插入,不覆盖运维已配置值。
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市定向-WGS84 坐标归一', 'orient.geo.wgs84.enabled', 'false', 'N', 'admin', NOW(),
       'true=开放节点坐标 WGS84 归一与脏值报告(Doc2 F1d)'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'orient.geo.wgs84.enabled');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市定向-AR 替代任务', 'orient.alt_task.enabled', 'false', 'N', 'admin', NOW(),
       'true=开放 SCAN/PHOTO/QUIZ 替代完成(仅对已配置替代方式的任务生效)'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'orient.alt_task.enabled');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市定向-队伍大厅与进度矩阵', 'orient.team.board.enabled', 'false', 'N', 'admin', NOW(),
       'true=开放队伍大厅/详情进度矩阵/队伍排名'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'orient.team.board.enabled');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市定向-队伍完成阈值比例', 'orient.team.completion_ratio', '1', 'N', 'admin', NOW(),
       '(0,1]:任务需在岗成员中至少 ceil(比例×在岗人数) 人完成才算队伍完成;1=全员'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'orient.team.completion_ratio');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市定向-安全闸', 'orient.safety.enabled', 'false', 'N', 'admin', NOW(),
       'true=启用夜间/天气/年龄监护闸(保险不强制,SOS 不受信誉限制)'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'orient.safety.enabled');

-- 回读断言:五张表都在,五个键各恰好一行(不校验值 —— 运维事先配置过的值保持原样)。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.tables
         WHERE table_schema = DATABASE()
           AND table_name IN ('orient_node_geo', 'orient_task_alternative', 'orient_task_attempt',
                              'orient_task_completion', 'orient_safety_rule')) = 5
   AND (SELECT SUM(config_key = 'orient.geo.wgs84.enabled') = 1
           AND SUM(config_key = 'orient.alt_task.enabled') = 1
           AND SUM(config_key = 'orient.team.board.enabled') = 1
           AND SUM(config_key = 'orient.team.completion_ratio') = 1
           AND SUM(config_key = 'orient.safety.enabled') = 1
          FROM sys_config WHERE config_key LIKE 'orient.%') AS assertion_ok;
