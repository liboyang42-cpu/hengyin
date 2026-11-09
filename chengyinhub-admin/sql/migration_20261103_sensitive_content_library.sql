-- Round5D(2026-11-03 owner):自建敏感内容库,自动识别敏感词与已知违规图片。叠加在微信机审之前,不替代微信。
-- 只新建 4 张表 + 1 个开关(默认只报告)+ 后台菜单(不授权给任何角色)。
-- 不改、不删、不回填既有表。词表不随仓库提交:上线后由管理员在后台导入。可重复执行。
-- 阶段:先跑本文件(旧 jar 不读这些表/开关/菜单,照常运行),再部署含 sensitiveContentRescanTask 的新 jar。
-- 2026-11-04(ROUND10 r1 F1/F2,本文件尚未进 master、未发布,按业主批准原地修改):
--   Quartz 注册(sys_job)已移到 migration_20261104_content_safety_jobs_post_deploy.sql,新 jar 上线后才跑。
--   原因:SysJobServiceImpl.init 与调度主会话每 2 秒校验全部 sys_job 的 bean,旧 jar 没有这个 bean ——
--   先插行会让正在运行的旧 jar 停掉全部定时任务,回滚到旧 jar 时启动失败。

CREATE TABLE IF NOT EXISTS content_sensitive_word (
  id BIGINT NOT NULL AUTO_INCREMENT,
  word VARCHAR(100) NOT NULL,
  category VARCHAR(32) NOT NULL DEFAULT 'OTHER' COMMENT '分类:POLITICS/PORN/GAMBLING/DRUGS/VIOLENCE/FRAUD/ABUSE/AD/OTHER',
  action VARCHAR(16) NOT NULL DEFAULT 'BLOCK' COMMENT 'BLOCK=提交即拒;LOG=只记命中不拦(试运行)',
  enabled TINYINT NOT NULL DEFAULT 1,
  create_by VARCHAR(64) DEFAULT NULL,
  create_time DATETIME NOT NULL,
  update_by VARCHAR(64) DEFAULT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_content_sensitive_word (word),
  KEY idx_content_sensitive_word_enabled (enabled, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感词库(Round5D)';

CREATE TABLE IF NOT EXISTS content_sensitive_allow (
  id BIGINT NOT NULL AUTO_INCREMENT,
  phrase VARCHAR(100) NOT NULL COMMENT '含敏感词但无害的短语,如 成人票、血量',
  enabled TINYINT NOT NULL DEFAULT 1,
  create_by VARCHAR(64) DEFAULT NULL,
  create_time DATETIME NOT NULL,
  update_by VARCHAR(64) DEFAULT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_content_sensitive_allow (phrase)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感词放行短语(Round5D)';

CREATE TABLE IF NOT EXISTS content_sensitive_image (
  id BIGINT NOT NULL AUTO_INCREMENT,
  sha256 CHAR(64) NOT NULL,
  dhash BIGINT DEFAULT NULL COMMENT '64 位差值感知哈希;ImageIO 读不了的格式为空,只按 sha256 认',
  category VARCHAR(32) NOT NULL DEFAULT 'OTHER',
  enabled TINYINT NOT NULL DEFAULT 1,
  note VARCHAR(200) DEFAULT NULL,
  create_by VARCHAR(64) DEFAULT NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_content_sensitive_image_sha (sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='已知违规图片指纹(Round5D),不存原图';

CREATE TABLE IF NOT EXISTS content_sensitive_hit (
  id BIGINT NOT NULL AUTO_INCREMENT,
  source VARCHAR(16) NOT NULL COMMENT 'TEXT=提交时文本 IMAGE=图片 RESCAN=存量复查',
  content_type VARCHAR(40) NOT NULL,
  content_id BIGINT DEFAULT NULL,
  category VARCHAR(32) NOT NULL,
  action VARCHAR(16) NOT NULL COMMENT 'BLOCK/LOG/REPORT/REMOVED',
  scan_version VARCHAR(64) DEFAULT NULL COMMENT '存量复查用的词库版本,保证同一版本同一内容只记一次',
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_content_sensitive_hit_time (create_time, id),
  KEY idx_content_sensitive_hit_content (content_type, content_id, scan_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感内容命中记录:只存类型/ID/分类,不存命中词与个人数据';

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '敏感词存量复查自动下架', 'content.sensitive.rescan.remove', 'false', 'Y', 'admin', NOW(),
       'false=只报告命中(默认,owner 看过一轮结果前保持);true=命中自动下架,走内容安全自动下架同一路径'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'content.sensitive.rescan.remove');

-- 后台菜单:挂在审核中心下,只建菜单与按钮权限,不授权给任何角色(由 owner 分配)。
DROP TEMPORARY TABLE IF EXISTS tmp_cy_sensitive_menu_guard;
CREATE TEMPORARY TABLE tmp_cy_sensitive_menu_guard(id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tmp_cy_sensitive_menu_guard(id) VALUES(1);
INSERT INTO tmp_cy_sensitive_menu_guard(id) SELECT 1 WHERE (SELECT COUNT(*) FROM sys_menu WHERE menu_type='M' AND path='audit-center')<>1;
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '敏感内容库',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='audit-center') m),40,'sensitive','business/sensitive/index',1,0,'C','0','0','business:sensitive:list','validCode','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:sensitive:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '敏感内容库维护',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE perms='business:sensitive:list') m),1,'#','',1,0,'F','0','0','business:sensitive:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:sensitive:edit');
INSERT INTO tmp_cy_sensitive_menu_guard(id) SELECT 1 WHERE (SELECT COUNT(*) FROM sys_menu WHERE perms IN ('business:sensitive:list','business:sensitive:edit'))<>2;
DROP TEMPORARY TABLE tmp_cy_sensitive_menu_guard;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE() AND engine = 'InnoDB'
          AND table_name IN ('content_sensitive_word','content_sensitive_allow','content_sensitive_image','content_sensitive_hit')) = 4
  AND (SELECT COUNT(*) FROM sys_config WHERE config_key = 'content.sensitive.rescan.remove') = 1
  AND (SELECT COUNT(*) FROM sys_menu WHERE perms IN ('business:sensitive:list','business:sensitive:edit')) = 2 AS assertion_ok;
