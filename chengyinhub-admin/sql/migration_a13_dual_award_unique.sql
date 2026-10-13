-- A-13 防双中标:库级唯一约束(最终兜底)
-- 代码侧已加:confirmCandidate/dispatchNodeMerchant @Transactional + audit_status compare-and-set(confirmWinnerIfPending)
--            + 逐节点唯一守卫(slotHasOtherWinner)+ createCoopOrder 按 reg_id 幂等。
-- 本迁移兜住代码侧唯一挡不住的场景:并发下「两个不同报名同时确认同一竞争位」(SELECT 守卫有 TOCTOU)。
--
-- 【2026-07-15 修正 · 竞争位口径】本文件初版(随 #54 合入 master,但未必已落库 —— 见 P0)
--   的 win_key 是 `topic + COALESCE(template, node)`。经 4 路链路调研(发布者/商家/玩家/结算),
--   该口径有两处与实际数据模型相反,已改正(详见下方 DDL 1 的注释):
--     ① 键用 template 优先 → 多地点共用一个模板时会挤成一个竞争位,另几个地点招不到商家
--     ② 缺 del_flag=0 → 软删的中标行仍占键位,挡住重新调配(同文件的 reg_key 想到了这点,win_key 漏了)
--   代码侧 slotHasOtherWinner / sweepLosers(CmsRegistrationMerchantController / ApiCoopController)
--   已同步改为 (topic, node) 同口径。
--   ⚠️ 一度还想按 mode 分流(mode=2 自由探索不互斥),已否决并回滚:那建立在「自由探索由商家自己建点」
--      的误解上,而全链路【没有任何入口创建 cms_topic_node】,商家只认领发布者建好的节点
--      ⇒ 一个节点只能一个商家,与 mode 无关。
--
-- ⚠️⚠️ 生产库 MySQL8。禁止直接执行。必须走 chengyin-db-migrate:先只读预检 → 用户确认 → 才执行。
--       加唯一键前若存量已有脏数据(同竞争位多中标 / 同 reg 多单),ALTER 会失败,须先清洗再加约束。
--
-- ======================= 第一步:只读预检(先跑这三条) =======================
-- P0【决定走哪条路】本文件初版是否已落库?迁移靠人手动跑、没有强制机制,两种可能都要覆盖:
--   SELECT COLUMN_NAME, GENERATION_EXPRESSION
--     FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cms_registration_merchant'
--      AND COLUMN_NAME = 'win_key';
--   · 0 行 → 初版没跑过,直接执行下方 DDL 1(常见情况)。
--   · 1 行且表达式含 COALESCE → 初版已落库,是【旧口径】,必须先拆掉再按新口径重建:
--       ALTER TABLE cms_registration_merchant DROP INDEX uk_reg_merchant_win, DROP COLUMN win_key;
--     然后再跑 DDL 1。(DROP 生成列不丢业务数据:win_key 是 VIRTUAL,不落盘、由表达式算出。)
--   · 1 行且表达式已是新口径 → 本迁移已生效,跳过 DDL 1。
--   ⚠️ 只要 P0 查到 win_key 存在(无论哪种口径),就说明初版整份跑过 ⇒ 【DDL 2 也已执行】,
--      coop_order.reg_key 已在 ⇒ 本文件【不可整份重跑】(DDL 2 会 1060 Duplicate column 'reg_key',
--      且 MySQL DDL 隐式提交、DDL 1 已生效无法一起回滚)。此时只跑 DDL 1(必要时先跑上面的 DROP),
--      DDL 2 跳过。可用下面这条确认 reg_key 在不在:
--        SELECT COLUMN_NAME FROM information_schema.COLUMNS
--         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'coop_order' AND COLUMN_NAME = 'reg_key';
--
-- P1【必须 0 行】同一 (topic, node) 已有多个中标(脏数据 → ALTER 会 1062 失败)。
--    ⚠️ 谓词必须与下方 win_key 的 CASE 逐字一致,否则预检漏报。
--       含 node_id <> 0:0 是「无节点」哨兵(自由探索),本就允许同主题多家,不是脏数据。
--       topic_id IS NOT NULL:win_key 用 CONCAT,topic_id 为 NULL 时整键 NULL 不入约束;
--       而 GROUP BY 会把多个 NULL 并成一组 ⇒ 不加此条会误报(方向是误报不是漏报,但会害人误清数据)。
--   SELECT topic_id, node_id, COUNT(*) AS c
--     FROM cms_registration_merchant
--    WHERE audit_status = 1 AND del_flag = 0
--      AND topic_id IS NOT NULL AND node_id IS NOT NULL AND node_id <> 0
--    GROUP BY topic_id, node_id
--   HAVING c > 1;
--
-- P2【必须 0 行】同一报名(reg_id)已有多张生效 coop_order(脏数据):
--   SELECT reg_id, COUNT(*) AS c
--     FROM coop_order
--    WHERE reg_id IS NOT NULL AND del_flag = 0
--    GROUP BY reg_id
--   HAVING c > 1;
--
-- 若 P1/P2 非 0:先人工核对哪些是真双中标/双单,作废重复的(改 audit_status / del_flag),再执行下方 DDL。
--
-- ======================= 第二步:DDL(预检 0 行后,用户确认再执行) =======================

-- 1) cms_registration_merchant:每 (topic, node) 至多一个中标。
--
--    竞争位 = (topic_id, node_id),【两种 mode 一律互斥】:节点由发布者建、商家只认领
--    (4 路链路调研核实:全仓无任何入口创建 cms_topic_node,商家报名只填 node_id 认领现成节点)
--    ⇒ 一个节点只能一个商家承接,这与玩法模式无关。
--
--    为什么键是 node 而不是 template:template 是【玩法】(cms_member_template:题目/验证/难度/时长),
--    node 是【地点】(cms_topic_node:地址/经纬度/打卡码/营业时间)。node 引用 template 且是【多对一】
--    —— 同一主题的多个地点可以共用一个玩法模板。按 template 做键会把「3 个地点共用一个模板」挤成
--    同一个竞争位,只能中一家,另两个地点招不到商家。
--
--    MySQL8 虚拟生成列 win_key —— 仅「已中标 + 未软删 + 有有效地点」时非空,其余恒 NULL
--    (NULL 不进唯一键、互不冲突)。四个谓词缺一不可:
--      audit_status = 1        —— 只约束中标行(待调配/落选不占位)
--      del_flag = 0            —— 否则软删的中标行仍占键位、挡住重新调配(与下方 reg_key 同口径)
--      node_id IS NOT NULL     —— 未传节点
--      node_id <> 0            —— ⚠️【关键】0 是前端哨兵值「无节点」,不是真实节点 ID:
--        mode=2 自由探索【不选节点】(merchantinfo.js:236 只在 mode=1 时强制选节点;
--        selectedNodeId 默认 0 → merchantapply1.js:88 `parseInt(options.nodeId) || 0` → 0
--        → ApiRegistrationMerchantController:297 不校验直接落库 node_id=0)。
--        若不排除 0,同一主题【所有】自由探索中标行的 win_key 都是 'topicId-0' 而互相冲突
--        ⇒ 库级强制「每主题至多一个自由探索商家」,第二家确认时 1062 → 事务回滚 500。
--        这与产品意图正相反:自由探索本就允许一个模板多个商家承接。
--    与代码侧 hasNodeSlot(nodeId != null && nodeId != 0) 同口径(两个 controller 各一份)。
--    (⚠️ 拼接必须用 CONCAT 而非 CONCAT_WS:后者会跳过 NULL,让所有空 node 的行生成相同键而互相冲突。
--      本 CASE 已先滤掉 NULL/0,此处保留 CONCAT 以防后续有人放宽谓词时踩回该坑。)
ALTER TABLE cms_registration_merchant
  ADD COLUMN win_key VARCHAR(64)
    GENERATED ALWAYS AS (
      CASE WHEN audit_status = 1 AND del_flag = 0 AND node_id IS NOT NULL AND node_id <> 0
           THEN CONCAT(topic_id, '-', node_id)
           ELSE NULL END
    ) VIRTUAL,
  ADD UNIQUE KEY uk_reg_merchant_win (win_key);

-- 2) coop_order:同一报名至多一张【生效】合作单。
--    用生成列 reg_key = 仅 del_flag=0 时取 reg_id(软删单 / 邀约单 reg_id=NULL 均为 NULL,不进唯一键),
--    与代码 selectCoopOrderByRegId 的 del_flag=0 口径一致——避免"软删旧单挡住同 reg 重新确认→UNIQUE 冲突回滚"。
ALTER TABLE coop_order
  ADD COLUMN reg_key BIGINT
    GENERATED ALWAYS AS (CASE WHEN del_flag = 0 THEN reg_id ELSE NULL END) VIRTUAL,
  ADD UNIQUE KEY uk_coop_order_reg (reg_key);
