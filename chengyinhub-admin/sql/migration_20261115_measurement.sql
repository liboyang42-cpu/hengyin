-- P6 measurement(GRO-03 / PERF-06 / IOS-05):登录前首会话埋点、安装号关联、MetricKit 健康数据。
-- 幂等:三张新表全部 CREATE TABLE IF NOT EXISTS;不改任何既有表。
-- 回滚:应用侧先置 chengyin.telemetry.enabled=false(三条入口只回 accepted=0、不落库),
--       确认无需留存后再执行:
--         DROP TABLE IF EXISTS app_metric_payload;
--         DROP TABLE IF EXISTS app_install_link;
--         DROP TABLE IF EXISTS app_install_event;
-- 隐私:install_id 是客户端随机生成、玩家可在设置里重置的 UUID;事件只含白名单枚举/短码,
--       不含坐标、手机号、昵称。app_install_link 是唯一能把安装号与账号对上的表,
--       注销链路如需一并清理,按 member_id 删除即可(见 RESULT.md 的 owner 问题)。

CREATE TABLE IF NOT EXISTS app_install_event (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  install_id   CHAR(36)     NOT NULL COMMENT '客户端随机安装号(UUID,可重置)',
  event_id     CHAR(36)     NOT NULL COMMENT '事件幂等号(UUID)',
  event_name   VARCHAR(40)  NOT NULL COMMENT '白名单事件名,见 InstallEventSchema',
  props_json   VARCHAR(512) NULL     COMMENT '白名单属性(枚举/短码)JSON',
  platform     VARCHAR(16)  NOT NULL,
  app_version  VARCHAR(40)  NOT NULL,
  occurred_at  DATETIME     NOT NULL,
  create_time  DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_install_event (install_id, event_id),
  KEY idx_app_install_event_name_time (event_name, occurred_at),
  KEY idx_app_install_event_time (occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P6 匿名安装事件(登录前漏斗)';

CREATE TABLE IF NOT EXISTS app_install_link (
  id          BIGINT   NOT NULL AUTO_INCREMENT,
  install_id  CHAR(36) NOT NULL,
  member_id   BIGINT   NOT NULL,
  linked_at   DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_install_link (install_id, member_id),
  KEY idx_app_install_link_member (member_id),
  KEY idx_app_install_link_time (linked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P6 安装号与账号关联(登录后一次)';

CREATE TABLE IF NOT EXISTS app_metric_payload (
  id            BIGINT      NOT NULL AUTO_INCREMENT,
  install_id    CHAR(36)    NOT NULL,
  payload_kind  VARCHAR(16) NOT NULL COMMENT 'metric | diagnostic',
  app_version   VARCHAR(64) NOT NULL,
  os_version    VARCHAR(64) NOT NULL,
  payload_json  MEDIUMTEXT  NOT NULL COMMENT 'MetricKit JSON,单条 <= 256KB',
  payload_bytes INT         NOT NULL,
  create_time   DATETIME    NOT NULL,
  PRIMARY KEY (id),
  KEY idx_app_metric_payload_install_time (install_id, create_time),
  KEY idx_app_metric_payload_kind_time (payload_kind, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P6 MetricKit 指标与诊断(崩溃、卡顿、启动时间)';
