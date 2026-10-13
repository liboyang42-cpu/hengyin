-- NF-34 昵称唯一性 —— 2026-08-28 用户裁决后的缩减版。
--
-- 原 migration_nickname_uniqueness_20260828.sql 做四件事,这里只保留第 ①:
--   ① 加派生列 nickname_key = LOWER(TRIM(nickname))   ← 保留。纯派生,不改任何人的数据。
--   ② UPDATE 给重名昵称加 16 位十六进制后缀            ← 【删除】
--   ③ ADD UNIQUE KEY uk_ums_member_nickname_key      ← 【删除】
--   ④ BEFORE INSERT/UPDATE 触发器补默认昵称            ← 【删除】
--
-- 为什么删 ②③:生产只读预检(2026-08-28)显示 ums_member 共 54 人、空昵称 0 人,
-- 而 43 人昵称都是**默认的**「城瘾用户」。②会把其中 42 个**真实用户**的显示名改写成
-- 「城瘾用户#a3f91c2e」这样的乱码且不可逆。重名的成因是默认值撞车,不是用户起了同名 ——
-- 对这种重复施加唯一约束,等于拿存量用户的身份去还一个还不存在的债。
-- 用户裁决:唯一性改由应用层查重 + 建议名承担(NicknameSuggestionServiceImpl),
-- DB 唯一索引留到用户量真起来、且大家都改过昵称之后再加。
--
-- 为什么删 ④:生产库账号 chengyinhubdb@localhost 无 SUPER/SET_USER_ID,
-- log_bin=1 且 log_bin_trust_function_creators=0 ⇒ CREATE TRIGGER 报 1419(2026-08-28 实测)。
-- 且删了 ②③ 之后,这两个触发器要兜的场景本来也不存在了。
--
-- 保留 ① 的理由:UmsMemberMapper.xml 的 countActiveNickname 查的就是 nickname_key。
-- 它是 STORED 派生列,54 行的重建代价可忽略;将来真要加唯一索引时,列已就位。

ALTER TABLE ums_member
    ADD COLUMN nickname_key VARCHAR(100)
      GENERATED ALWAYS AS (LOWER(TRIM(nickname))) STORED
      COMMENT '昵称比较键(LOWER+TRIM);当前不加唯一索引,唯一性由应用层承担';

-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'ums_member'
     AND column_name = 'nickname_key') = 1
  -- 负向断言:确认没有误加唯一索引
  AND (SELECT COUNT(*) FROM information_schema.statistics
       WHERE table_schema = DATABASE() AND table_name = 'ums_member'
         AND index_name = 'uk_ums_member_nickname_key') = 0
  -- 负向断言:确认一个人的昵称都没被改写。
  -- ⚠️ 2026-09-10 改判据。原来这里写死「= 43 个『城瘾用户』」—— 那是 2026-08-28 预检
  --    当天的人数。注册一多就恒假:实测今天是 45(总人数 54 → 56),于是落库对账连着
  --    几天把这份**早已落库**的迁移报成「未落库」,还被我当成 P0 上报过两次。
  --    「永远红」和「永远绿」一样没有信息。
  --    判据换成结构性的:派生列就是 LOWER(TRIM(nickname)) 本身,所以只要它是 STORED
  --    生成列、表达式没被改过,就等价于「没有任何人的昵称被这份迁移动过」——
  --    生成列根本写不进去。这个断言不随注册人数漂移。
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'ums_member'
         AND column_name = 'nickname_key'
         AND extra LIKE '%STORED GENERATED%'
         AND REPLACE(REPLACE(generation_expression, '`', ''), ' ', '')
             = 'lower(trim(nickname))') = 1
  AS assertion_ok;
