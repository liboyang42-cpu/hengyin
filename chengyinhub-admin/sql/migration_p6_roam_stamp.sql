-- migration_p6_roam_stamp.sql
-- 走 chengyin-db-migrate:只读预检 `--describe roam_stamp` 确认表不存在后执行。
-- 新建表,不改存量,可逆(DROP TABLE)。必须先于引用它的 jar 上线。
--
-- 【uk 一个索引喂两档】MySQL 唯一索引允许多行 NULL:
--   普通票 geohash7=NULL  → 不受约束,无限多
--   盖销票 geohash7 非空  → 一格一生一枚(P1 用)
-- 已在生产库 8.0.45 实测坐实(TEMPORARY TABLE + 负控:同格第二次报 1062)。
--
-- 【uk_member_idem 同款】idem_key 为 NULL 的票不受约束(老客户端不送键照常入册),
-- 送了键的同一 member 重复提交撞 1062 → 应用层回查该键的既有票返同一 id。
-- ⚠️ 两个 uk 都会抛 DuplicateKeyException,应用层必须回查区分是哪一道撞的,
-- 不能只 catch 异常就当幂等命中(相邻同型异常互相接住 = 假绿)。
--
-- 【本 DDL 的三道闸已在生产库 8.0.45 实测坐实】2026-07-17,TEMPORARY TABLE + 5 条负控:
--   ① selectByMember 的 check_state != 2:置 2 后该票从册子消失(负控:去掉该谓词它就回来)
--   ② uk_member_idem:同键第二次报 1062 且报文指名 roam_stamp.uk_member_idem
--      (负控:NULL 键多行照常入册;另一 member 用同一键不受影响)
--   ③ markCheckPassed 的 and check_state = 0:对已置 2 的票 affected=0 不复活
--      (负控:对 0 的票 affected=1,证明不是恒 0 行)
-- 这三条 mock 测不到(仓库无 DB 测试基建),Java 侧不要写「断言 XML 文本」的替身,那是假绿。
--
-- 【注释与字面量禁 ASCII 分号】2026-07-14 实证:COMMENT 里的半角分号会被按分号
-- 拆句的执行器劈开。本文件一律用中文逗号。
--
-- 上线后校验:
-- SELECT COUNT(*) FROM roam_stamp

CREATE TABLE roam_stamp (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  member_id   BIGINT        NOT NULL COMMENT '归属用户',
  pic_url     VARCHAR(500)  NOT NULL COMMENT 'OSS 原图 URL',
  caption     VARCHAR(140)  NULL COMMENT '用户自填说明',
  kind        TINYINT       NOT NULL DEFAULT 0 COMMENT '0=普通(自由拍),1=盖销(P1)',
  geohash7    CHAR(7)       NULL COMMENT '盖销专用,与 roam_fog_tile 同精度,普通票为 NULL',
  lat         DECIMAL(10,6) NULL,
  lng         DECIMAL(10,6) NULL,
  city_code   VARCHAR(16)   NULL,
  check_state TINYINT       NOT NULL DEFAULT 0 COMMENT '0=未送检(含机审关闭与送检失败),1=机审通过,2=违规下架',
  check_trace VARCHAR(64)   NULL COMMENT 'checkMediaAsync 的 trace_id',
  idem_key    VARCHAR(64)   NULL COMMENT '客户端幂等键,防双击与重试重复入册,不送则为 NULL',
  del_flag    TINYINT       NOT NULL DEFAULT 0,
  create_time DATETIME      NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_member_geohash (member_id, geohash7),
  UNIQUE KEY uk_member_idem (member_id, idem_key),
  KEY idx_member_time (member_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='漫游集邮 UGC 邮票';
