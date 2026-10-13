-- 漫游·附近的局:玩家自组的免费约玩群(设计:vault 04《漫游附近的局_玩家自组局_系统设计与TODO_20260905》)。
-- 成员真源 = im_conversation_member(退群 = 退局),不建成员表;零资金流,不碰 registration / order / settlement。
CREATE TABLE IF NOT EXISTS roam_hangout (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  member_id       BIGINT UNSIGNED NOT NULL COMMENT '局主 ums_member.id',
  title           VARCHAR(30)  NOT NULL,
  description     VARCHAR(120) DEFAULT NULL,
  cover_url       VARCHAR(500) DEFAULT NULL,
  latitude        DECIMAL(10,7) NOT NULL,
  longitude       DECIMAL(10,7) NOT NULL,
  address_name    VARCHAR(100) DEFAULT NULL,
  start_at        DATETIME DEFAULT NULL COMMENT '可选;过后 24h 自动关',
  status          TINYINT NOT NULL DEFAULT 0 COMMENT '0进行中 1已关 2机审/举报隐藏',
  conversation_id BIGINT UNSIGNED DEFAULT NULL COMMENT 'im_conversation.id,建局时即建群',
  create_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time     DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag        TINYINT NOT NULL DEFAULT 0,
  KEY idx_geo (status, latitude, longitude),
  KEY idx_member (member_id, status),
  CONSTRAINT ck_roam_hangout_coords CHECK (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='漫游·附近的局';

-- 过期关局任务:依赖 roamHangoutExpireTask bean。每 30 分钟扫一次;有时间的局 start_at+24h 关,
-- 常驻局 14 天无群消息关。只关局 + 关群聊,不读订单、不退款。concurrent='1' 禁并发,status='0' 注册即启用。
INSERT INTO sys_job
    (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status,
     create_by, create_time, remark)
SELECT '漫游附近的局过期关闭', 'DEFAULT', 'roamHangoutExpireTask.run()', '0 0/30 * * * ?',
       '3', '1', '0', 'admin', NOW(),
       '关闭已过期的玩家自组局并关闭其群聊;不读取订单、不触发退款'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_job WHERE invoke_target = 'roamHangoutExpireTask.run()'
);
