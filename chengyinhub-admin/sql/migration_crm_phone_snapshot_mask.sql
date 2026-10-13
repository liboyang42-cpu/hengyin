-- CRM 客户画像:清掉存量明文手机号快照(2026-07-16 · 续 PR #72)
-- 背景：merchant_customer_profile.member_phone 是从 ums_member.phone 明文快照复制来的。
--       PR #72 已在读路径脱敏(显示层安全),但存量行在库里仍是明文,且聚合每跑一次会重灌明文。
--       配套改动已把源头 insertAggregatedByMerchantId 改成落库即脱敏;本脚本清历史存量。
--
-- ★★ 执行时机:本脚本要在【新 jar 部署上线之后】跑,不是合并前先跑 ——
--    这是纯数据清理(UPDATE),代码不依赖它,故不受「带迁移合前先落库」那条铁律约束。
--    反过来:若在旧 jar 还在跑时先跑本脚本,期间任何人触发 CRM 聚合(business:crm:aggregate),
--    旧 jar 的 insert 仍会用明文 u.phone 重灌那个商家的行 → 清了又脏。
--    新 jar(落库即脱敏)上线后再跑,才没有新明文来源,清完即稳。本脚本幂等,晚跑无副作用。
--
-- 幂等：WHERE 只挑「还没脱敏」的行(不含 '*'),已脱敏行跳过 → 可重复执行。
-- 脱敏口径与源头/读路径一致：11 位 -> 前3后4中间****;非 11 位 -> 首字符+****(边界,罕见)。
-- 只读预检行数：SELECT COUNT(*) FROM merchant_customer_profile
--   WHERE member_phone IS NOT NULL AND member_phone <> '' AND member_phone NOT LIKE '%*%';

UPDATE merchant_customer_profile
SET member_phone = CASE
        WHEN CHAR_LENGTH(member_phone) = 11 THEN CONCAT(LEFT(member_phone, 3), '****', RIGHT(member_phone, 4))
        ELSE CONCAT(LEFT(member_phone, 1), '****')
    END
WHERE member_phone IS NOT NULL
  AND member_phone <> ''
  AND member_phone NOT LIKE '%*%';

-- 回读断言（回执 ≠ 观测）：清完后应为 0；非 0 说明还有明文残留。
SELECT COUNT(*) AS remaining_plaintext
FROM merchant_customer_profile
WHERE member_phone IS NOT NULL
  AND member_phone <> ''
  AND member_phone NOT LIKE '%*%';
