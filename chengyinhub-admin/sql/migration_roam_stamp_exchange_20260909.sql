-- migration_roam_stamp_exchange_20260909.sql
-- 走 chengyin-db-migrate:只读预检 `--describe roam_stamp_exchange` 确认表不存在后执行。
-- 新建表,不改存量,可逆(DROP TABLE)。必须先于引用它的 jar 上线 ——
-- 没有这张表,POST /api/roam/stamp/exchange 直接 500,「投一张换一张」整段玩法没有后半截。
--
-- 【这张表回答什么】「我投出的这一张,换回来的是谁那一张」。
-- 一行 = 一次交换。它同时兼了三个职责,不要再拆表:
--   ① 幂等:uk_member_given 让「同一张投出的票」永远只兑换出同一张回礼。
--      客户端重试 / 双击 / 弱网重发,拿回的都是同一枚,不会一次投票换走两张。
--   ② 去重:uk_member_received 保证同一个人不会两次收到同一张票 ——
--      「换出上一个人那张」的前提是每次都换到没见过的。
--   ③ 收件箱:member_id 上的索引就是「我换回来的那些」,不必另建表。
--
-- 【为什么不在 roam_stamp 上加列】一张票会被很多人换到,是一对多,加列存不下。
-- 而且加列会把「谁换走过我的票」写进票本身,读票的地方全都要跟着过滤。
--
-- 【given_stamp_id 为什么必须存在】它是这次交换的「代价」。没有它就只剩「领一张」,
-- 而这套玩法的立论就是投一张才换一张 —— 写是代价,看是回报。
-- 应用层保证 given 是本人的票,DB 侧不加外键(本仓一贯不建 FK,靠应用层与对账)。
--
-- 【注释与字面量禁 ASCII 分号】沿用 migration_p6_roam_stamp.sql 的约定:
-- 半角分号会被按分号拆句的执行器劈开,一律用中文逗号。
--
-- 上线后校验:
-- SELECT COUNT(*) FROM information_schema.tables WHERE table_name='roam_stamp_exchange'

CREATE TABLE roam_stamp_exchange (
  id                 BIGINT   NOT NULL AUTO_INCREMENT,
  member_id          BIGINT   NOT NULL COMMENT '换票的人,也就是收件人',
  given_stamp_id     BIGINT   NOT NULL COMMENT '他这次投出去的票 roam_stamp.id,即本次交换的代价',
  received_stamp_id  BIGINT   NOT NULL COMMENT '换回来的别人那张 roam_stamp.id',
  create_time        DATETIME NOT NULL,
  PRIMARY KEY (id),
  -- 幂等:一张投出的票只兑换一次。重试拿回同一枚回礼。
  UNIQUE KEY uk_member_given (member_id, given_stamp_id),
  -- 去重:同一个人不会两次收到同一张票。
  UNIQUE KEY uk_member_received (member_id, received_stamp_id),
  -- 收件箱按时间倒序翻页
  KEY idx_member_time (member_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='漫游集邮 投一张换一张的交换记录';
