-- ============================================================================
-- 条件奖励:让商家能配「达到某个结果才发这份奖」,而不只是「完成即发」。
--
-- 起因:估数分命中/接近/偏离三档、比价按连对几个分档、竞猜要猜中才发、
--   盲盒要抽到稀有的才发 —— 这些都需要奖励挂一个触发条件。而现在模板上的
--   奖励是三个平铺列(coupon_id / feedback_text / medal_*),没有条件位,
--   语义写死成「节点完成即发」(reward-selector 的 occasion 就是这么定的)。
--
-- 做法:新增一列存条件奖励数组,**平铺列继续表示无条件奖励,行为不变**。
--   发放时两者都走:无条件的照常发,条件的按本次 outcomeCode 匹配再发。
--   不动平铺列的原因是存量模板全靠它们,改语义等于把已发布的奖励规则重解释一遍。
--
-- outcomeCode 复用既有的结果合同(utils/publish/node-outcome-contract.js):
--   分支剧情终点码 / 偏好题结果码 / 新玩法档位码(ESTIMATE_HIT、PRICE_PERFECT…)。
--   码的形状 ^[A-Z][A-Z0-9_]{0,63}$ 与前端合同一致,服务端另有校验。
--
-- reward_rules_json 形状(数组,最多 8 条):
--   [{"outcomeCode":"ESTIMATE_HIT","couponId":123,"xp":50,
--     "medalName":"神算子","medalImg":"https://…","medalStyle":"glow",
--     "feedbackText":"你差点把老板吓着"}]
--   每条至少要有 couponId / xp / medalName+medalImg / feedbackText 之一。
--
-- ⚠️ 本文件只准备,不执行。生产库写操作在用户点头后由 chengyin-db-migrate 执行。
-- ⚠️ 不声明 requires=transaction:本文件含 ALTER/CREATE,MySQL 的 DDL 是隐式提交、
--   不可回滚,runner 的窄事务模式也明确拒绝普通 DDL。按 autocommit 逐句执行。
--   文件里的 UPDATE 是幂等的(搬完就不再匹配 WHERE),重跑安全。
-- ============================================================================

-- ======================= 第一步:只读预检 =======================
-- 期望 0 行。非 0 说明已跑过,不要重复执行。
SELECT table_name, column_name
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN ('cms_member_template', 'cms_template_library')
  AND column_name = 'reward_rules_json';


-- ======================= 第二步:加列 =======================
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS,靠上面的预检把关。
ALTER TABLE cms_member_template
    ADD COLUMN reward_rules_json VARCHAR(4000) NULL
    COMMENT '条件奖励:[{outcomeCode,couponId,xp,medalName,medalImg,medalStyle,feedbackText}];平铺的 coupon_id 等仍表示无条件奖励'
    AFTER medal_style;

ALTER TABLE cms_template_library
    ADD COLUMN reward_rules_json VARCHAR(4000) NULL
    COMMENT '条件奖励:同 cms_member_template'
    AFTER coupon_id;


-- ======================= 第三步:写后断言 =======================
-- 断言一:两列都已存在(期望 2)。
-- ⚠️ 同上:runner 不回显结果集,断言必须写成「不满足就报错」。
SELECT JSON_EXTRACT(IF(COUNT(*) = 2, '[]', 'ASSERT_FAILED__reward_rules_columns_added'), '$[0]') AS reward_rules_columns_added
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN ('cms_member_template', 'cms_template_library')
  AND column_name = 'reward_rules_json';

-- 断言二:存量一行都不该被这次迁移改动 —— 所有既有模板继续按「完成即发」跑。
SELECT JSON_EXTRACT(IF(COUNT(*) = 0, '[]', 'ASSERT_FAILED__existing_rows_untouched'), '$[0]') AS existing_rows_untouched
FROM cms_member_template
WHERE del_flag = 0 AND reward_rules_json IS NOT NULL;
