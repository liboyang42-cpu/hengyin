-- P0-02 产品真源:cms_topic.product_type + cms_registration.purchase_kind
-- 来源:《城瘾代码侧_现状与改造施工图_TODO_2026-07-15》Phase 1 / §3.1
--
-- 【背景】
--   今天没有任何一个字段能回答「这是哪种产品」,靠五个间接信号共同猜,其中唯一的「权威」是
--   oms_ticket.mode —— 但它的语义是主题级、存储却在票级,于是:
--     · 混票是默认行为(前端 addTicket 硬编码 mode:1,自由定向入口加票即造出 mode=1 的票)
--     · resolveMode 取「第一张有 mode 的票」且票列表无 order by ⇒ 玩法模式由 InnoDB 扫描路径决定
--   本迁移把产品类型上收到主题(product_type),把购买类型显式化到订单(purchase_kind)。
--   oms_ticket.mode 随后降级为派生列(由 product_type 反推并覆盖),不再是真源。
--
--   取值域按施工图 §3.1(储存用 TINYINT,与既有 oms_ticket.mode / PlayMode 常量同值域,便于派生):
--     cms_topic.product_type      1 = ORIENTEERING   经典定向(①有人带票 与 ②自玩通行证 共存于此)
--                                 2 = FREE_EXPLORE   自由定向(③)
--                                 NULL = 存量未回填 / 混票待人工(读侧回落票 mode 推断,见下)
--     cms_registration.purchase_kind  1 = GUIDED_TICKET   ① 有人带票
--                                     2 = SELF_PLAY_PASS  ② 自玩通行证
--                                     3 = EXPLORE_PASS    ③ 自由探索票
--                                     NULL = 存量未回填(读侧回落推断)
--   ④ ROAM_FREE 是独立栈(ApiRoamController/RoamSession/RoamPoi),不进这两张表,故无对应值。
--
-- 【为什么可空而不是 NOT NULL DEFAULT】
--   施工图 §3.1 定的口径是「旧数据要么明确删除、要么回填 product_type,混票主题隔离出来人工处理」,
--   且明写「判不清归属的人工确认,禁止静默回填」。给个 DEFAULT 1 会把混票主题静默打成经典定向 ——
--   那正是这条铁律要防的。故:能判的回填,判不清的留 NULL 由 P3 自检列出来人工定。
--   读侧在过渡期双读(product_type 非空则用它,为空回落既有票 mode 推断并告警),
--   所以 NULL 不会让功能挂掉,只是没享受到真源的好处。
--
-- 【幂等】
--   两个 ADD COLUMN 不幂等(重复执行报 1060 Duplicate column)—— MySQL 8 无 ADD COLUMN IF NOT EXISTS
--   (那是 MariaDB 专属,在 MySQL 8 报 1064)。故执行前必须跑 P0 预检确认列不存在。
--   两条回填 UPDATE 幂等(WHERE 带 product_type IS NULL / purchase_kind IS NULL,重复执行 0 行)。
--
-- 【可逆】
--   ALTER TABLE cms_topic DROP COLUMN product_type
--   ALTER TABLE cms_registration DROP COLUMN purchase_kind
--   两列均为新增可空列,无外键、无生成列依赖,DROP 不影响任何存量业务数据。
--   ⚠️ 但若新 jar 已部署,DROP 会让 mapper 引用不存在的列而 SQL 报错 ⇒ 回滚顺序 = 先回 jar 再 DROP。
--
-- 【风险】
--   低-中。加列本身无副作用(可空、无默认)。风险集中在回填判据:
--     · product_type 回填判据 = 该主题**所有非空 mode 的票**取值唯一 ⇒ 取该值;
--       取值不唯一(真混票)/ 无票 / mode 全空 ⇒ 留 NULL。
--       ⚠️ mode 为空的票**不参与判断也不阻止回填** —— 空 mode 不携带信息,这与既有读法一致
--       (resolveMode 取「第一张有 mode 的票」,跳过 null)。所以 [mode=1, mode=NULL] 会被回填成 1,
--       这是**故意的**,不算「静默回填判不清的」。真判不清的只有「两种非空 mode 并存」。
--     · purchase_kind 回填判据 = 施工图 §1 的过渡谓词:
--         无 ticket_id 且 owner_type=1 ⇒ 2(自玩通行证。createSelfPlayPass 不 set ticketId,这是 R2/G1
--         那个洞的副产物,恰好成了此处最可靠的判据)
--         有 ticket_id ⇒ 跟该票的 mode 走:mode=1 → 1,mode=2 → 3,mode 为空 → 留 NULL
--   两条判据都只在能确定时才写,判不清一律留 NULL。
--
-- ⚠️⚠️ 生产库 MySQL 8。禁止直接执行。必须走 chengyin-db-migrate:先只读预检 → 用户确认 → 才执行。
-- ⚠️ 部署顺序:本迁移必须先于新 jar 落库,否则 CmsTopic/CmsRegistration 引用新列会 SQL 报错。


-- ======================= 第一步:只读预检 =======================
--
-- P0【决定要不要跑】两列是否已存在(本文件不可整份重跑,ADD COLUMN 会 1060):
--   SELECT TABLE_NAME, COLUMN_NAME
--     FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND ((TABLE_NAME = 'cms_topic' AND COLUMN_NAME = 'product_type')
--        OR (TABLE_NAME = 'cms_registration' AND COLUMN_NAME = 'purchase_kind'));
--   · 0 行 → 全新,整份执行。
--   · 2 行 → 已落库,整份跳过(回填 UPDATE 可单独重跑,幂等)。
--   · 1 行 → 上次跑了一半(DDL 隐式提交,不可整体回滚)。只执行缺的那一条 ADD COLUMN + 两条 UPDATE。
--
-- P1【口径核验 · 期望 0 行,非 0 则本迁移的前提不成立】混票主题清单。
--   施工图 §3.1 明写「混票主题隔离出来人工处理」。这条查的就是要人工处理的那批:
--   SELECT k.owner_id AS topic_id, COUNT(DISTINCT k.mode) AS mode_kinds,
--          GROUP_CONCAT(DISTINCT k.mode) AS modes
--     FROM oms_ticket k
--     JOIN cms_topic t ON t.id = k.owner_id AND t.del_flag = 0
--    WHERE k.owner_type = 1 AND k.del_flag = 0 AND k.mode IS NOT NULL
--    GROUP BY k.owner_id
--   HAVING mode_kinds > 1;
--   ⚠️ 必须 JOIN 主题并滤 t.del_flag —— 只滤票的 del_flag 会把已软删主题列进来(回填 UPDATE 与
--      下面的 P3 都滤了 t.del_flag),于是 P3 与 P1 对不上,你会以为哪里错了。
--   · 0 行 → 无混票,回填能覆盖全部有票主题,最理想。
--   · 非 0 行 → 这些 topic_id 的 product_type 会被留成 NULL(回填 UPDATE 的 HAVING 已排除它们),
--              **必须人工逐个定型后手工 UPDATE**,别用「取第一张票的 mode」之类偷懒回填 ——
--              那正是本迁移要根除的病。
--
-- P2【存量规模 · 供判断风险】各表行数与可回填比例:
--   SELECT (SELECT COUNT(*) FROM cms_topic WHERE del_flag = 0)              AS topics,
--          (SELECT COUNT(*) FROM oms_ticket WHERE del_flag = 0)             AS tickets,
--          (SELECT COUNT(*) FROM cms_registration)                          AS registrations,
--          (SELECT COUNT(*) FROM cms_registration WHERE payment_status = 2) AS paid_registrations;
--   · paid_registrations = 0 → 施工图 §3.1 的「无真实付费数据」前提成立,这是加列最便宜的窗口。
--   · paid_registrations > 0 → 前提被推翻,施工图要求「本节策略整体翻为加列+回填+过渡期双读,
--                              工作量重估」。⚠️ 代码侧已按双读实现(product_type 为空回落票 mode 推断),
--                              故此情形不阻塞落库,但请回头知会一声、复核回填判据。
--
-- P3【回填后自检 · 落库后跑】留 NULL 的主题清单(= 需要人工定型的):
--   SELECT id, name, self_play, status
--     FROM cms_topic
--    WHERE del_flag = 0 AND product_type IS NULL;
--   期望 = P1 的混票清单 ∪ 无票主题(草稿)∪ mode 全空的主题。三类之外若还有行,别猜,回头问。


-- ======================= 第二步:DDL(预检确认后执行) =======================

-- DDL 1:主题产品类型(产品真源)
ALTER TABLE cms_topic
    ADD COLUMN product_type TINYINT NULL COMMENT '产品类型 1经典定向ORIENTEERING/2自由定向FREE_EXPLORE。NULL=存量未回填或混票待人工,读侧回落票mode推断' AFTER topic_type;

-- DDL 2:订单购买类型(由 ticketId 有无 + product_type 推导,报名落库时固化)
ALTER TABLE cms_registration
    ADD COLUMN purchase_kind TINYINT NULL COMMENT '购买类型 1有人带票GUIDED_TICKET/2自玩通行证SELF_PLAY_PASS/3自由探索票EXPLORE_PASS。NULL=存量未回填,读侧回落推断' AFTER topic_id;


-- ======================= 第三步:回填(幂等,可单独重跑) =======================

-- 回填 1:主题产品类型 —— 仅回填「所有**非空** mode 的票取值唯一」的主题。
--   真混票(两种非空 mode 并存,COUNT(DISTINCT mode) > 1)被 HAVING 排除 → 留 NULL → P3 列出来人工定型。
--   无票主题 / mode 全空的主题不在子查询里 → 同样留 NULL。
--   ⚠️ [mode=1, mode=NULL] 这种**会**被回填成 1:空 mode 不携带信息,与既有读法一致
--      (resolveMode 取「第一张有 mode 的票」、跳过 null)。这不算「静默回填判不清的」。
UPDATE cms_topic t
  JOIN (
        SELECT owner_id, MIN(mode) AS only_mode
          FROM oms_ticket
         WHERE owner_type = 1 AND del_flag = 0 AND mode IS NOT NULL
         GROUP BY owner_id
        HAVING COUNT(DISTINCT mode) = 1
       ) m ON m.owner_id = t.id
   SET t.product_type = m.only_mode
 WHERE t.del_flag = 0 AND t.product_type IS NULL;

-- 回填 2a:自玩通行证 ⇒ purchase_kind = 2
--   判据 = 施工图 §1 的过渡谓词「ownerType=1 且无 ticketId」。createSelfPlayPass 不 set ticketId,
--   而主链路购票必带 ticket_id ⇒ 该组合在 owner_type=1 下唯一指向自玩通行证。
UPDATE cms_registration
   SET purchase_kind = 2
 WHERE purchase_kind IS NULL
   AND owner_type = 1
   AND ticket_id IS NULL;

-- 回填 2b:有票订单 ⇒ 跟该票的 mode 走(1→GUIDED_TICKET,2→EXPLORE_PASS)
--   票 mode 为空的订单判不清,JOIN 条件已排除 → 留 NULL。
UPDATE cms_registration r
  JOIN oms_ticket k ON k.id = r.ticket_id
   SET r.purchase_kind = CASE WHEN k.mode = 2 THEN 3 ELSE 1 END
 WHERE r.purchase_kind IS NULL
   AND r.ticket_id IS NOT NULL
   AND k.mode IS NOT NULL;
