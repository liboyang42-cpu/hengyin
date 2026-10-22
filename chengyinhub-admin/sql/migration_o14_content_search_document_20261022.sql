-- O-14(C8-3 选 B):统一内容搜索投影 content_search_document，覆盖 TOPIC / ACTIVITY / POST。
-- 为什么不扩 community_search_document(选项 A)：它的 CHECK 不允许 ACTIVITY，改 CHECK 要对线上表做 DDL，
-- 且那张表由广场 outbox 内联维护；新表纯新增，广场写路径零改动。
-- 只投影【对任何人都公开】的内容：会员专属主题、俱乐部/关注者可见帖文、待审/下架/删除内容一律 HIDDEN。
-- 读侧(/api/search/unified)仍按源表实时可见性再过一遍闸，投影过期也不会泄露。
-- 写入方：contentSearchProjectionTask.refresh()(sys_job 默认暂停)。
-- 幂等：CREATE TABLE IF NOT EXISTS；已存在同名表时由断言核对关键列，不替换、不删除。
CREATE TABLE IF NOT EXISTS content_search_document (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  document_type VARCHAR(16) NOT NULL COMMENT 'TOPIC/ACTIVITY/POST',
  document_id BIGINT NOT NULL COMMENT '源表主键',
  title VARCHAR(200) NULL COMMENT '标题快照',
  search_text TEXT NOT NULL COMMENT '检索文本(标题+摘要)',
  img_url VARCHAR(500) NULL COMMENT '封面快照',
  city_code VARCHAR(32) NULL COMMENT '城市编码',
  visibility_state VARCHAR(16) NOT NULL COMMENT 'VISIBLE/HIDDEN',
  source_time DATETIME NULL COMMENT '源内容创建/发布时间(排序用)',
  indexed_at DATETIME NOT NULL COMMENT '最近投影时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_content_search_document (document_type, document_id),
  KEY idx_content_search_visible (visibility_state, document_type, city_code, source_time),
  CONSTRAINT chk_content_search_type CHECK (document_type IN ('TOPIC','ACTIVITY','POST')),
  CONSTRAINT chk_content_search_visibility CHECK (visibility_state IN ('VISIBLE','HIDDEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='O-14 统一内容搜索投影';

-- runner: assert=scalar-true
SELECT COUNT(*)=6 AS assertion_ok
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='content_search_document'
  AND column_name IN ('document_type','document_id','search_text','city_code','visibility_state','indexed_at');
