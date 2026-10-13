-- migration_ums_member_identity_20260919.sql
-- 走 chengyin-db-migrate:只读预检 `--describe ums_member_identity` 确认表不存在后执行。
-- 必须先于引用它的 jar 上线 —— 没有这张表,POST /api/publisher/identity 直接 500,
-- 而发布闸读到「没登记」会把所有新主题创建全部拦死。
--
-- 【这张表回答什么】「这个账号有没有把他的姓名和身份证号交给平台」。
-- 一行 = 一个发布者的实名登记。它同时兼了两个职责,不要再拆表:
--   ① 发布闸的事实来源:isRegistered(member_id) 只查这一张表,不去 ums_member 上添列。
--   ② 人工改绑的核对依据:id_card_hash 是同一张身份证是否绑过别的账号的唯一可查手段
--      (密文是 AES-GCM 随机 nonce,同样的明文密文不同,拿密文比对永远比不出)。
--
-- 【为什么不加列到 ums_member】 ums_member 被大量 `select *` 与后台 @Excel 导出路径读到,
-- 加两个 VARCHAR(768) 密文列等于把 PII 拖进每一条会员查询和每一次导出,
-- AdminPiiProtector 的过滤面从「一个接口」扩到「所有读会员的地方」。单独一张表,
-- 默认没人 join 它,读它只有 Service 一条路。
--
-- 【为什么 uk_member 而不是 (member_id, id_card_hash)】一个账号只留一条当前登记。
-- 改绑走人工(RUN-52 批复③),不给自助覆盖,所以「同一人两条」这种行不该存在。
--
-- 【三列密文为什么给 768】沿用 cms_registration.real_name 的既有口径:
-- AES-GCM 密文 = "v1:" + base64(nonce12 + 密文 + tag28),18 位身份证约 27 字节,
-- 加密后约 75 字符,768 留足冗余且与库内既有 PII 列同宽,不另立一套尺寸。
--
-- 【没有 FK】本仓一贯不建外键,靠应用层与对账(见 migration_roam_stamp_exchange 同样的取舍)。
--
-- 【注释与字面量禁 ASCII 分号】半角分号会被按分号拆句的执行器劈开,一律用中文逗号。
--
-- 上线后校验:
-- SELECT COUNT(*) FROM information_schema.tables
--  WHERE table_schema=DATABASE() AND table_name='ums_member_identity'

CREATE TABLE ums_member_identity (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  member_id      BIGINT       NOT NULL COMMENT '发布者本人 ums_member.id,一个账号一条',
  real_name      VARCHAR(768) NOT NULL COMMENT '真实姓名,AES-GCM 密文,只用于身份核验,不对外展示',
  id_card        VARCHAR(768) NOT NULL COMMENT '身份证号,AES-GCM 密文,不落日志不进接口响应',
  id_card_hash   CHAR(64)     NOT NULL COMMENT '身份证号 HMAC-SHA256 盲索引,供人工改绑时查「这张证是否绑过别的账号」',
  source         VARCHAR(24)  NOT NULL COMMENT '采集入口:club_apply merchant_apply topic_publish,出问题时知道是哪条链路收的',
  consent_time   DATETIME     NOT NULL COMMENT '单独同意发生时间,PIPL 第29条要求可举证,明细留痕在 consent_record',
  create_time    DATETIME     NOT NULL,
  update_time    DATETIME     NULL COMMENT '人工改绑才会动这一列',
  PRIMARY KEY (id),
  -- 一个账号只有一条当前登记
  UNIQUE KEY uk_member (member_id),
  -- 人工改绑核对:同一张身份证绑了几个账号
  KEY idx_id_card_hash (id_card_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布者实名登记 姓名与身份证号的密文存储';
