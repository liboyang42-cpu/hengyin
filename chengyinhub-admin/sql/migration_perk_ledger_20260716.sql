-- P0-12/13/14 权益与核销账本:三张执行层表 + 章节承接配置 + 权益零售标价
-- 来源:《城瘾代码侧_现状与改造施工图_TODO_2026-07-15》Phase 3 / §3.2(③ 开卖前置,首轮内测的心脏)
--
-- 【背景】
--   ③ 自由定向的商业模型是「玩家买一张通票 → 每个必选章节到一家商家领一份权益」,但今天:
--     · 权益只有「申报层」coop_perk(商家有什么),**没有执行层**(谁应得、谁已得、还剩多少)
--     · CoopPerkMapper 只有 4 个方法且无 update ⇒ 改权益只能全清重插,核销扣减无处落
--     · coop_perk 挂 invite 维度,**与章节/节点零关联**;玩家侧完全不可见
--     · 跨商家重复核销零拦截(V2):核销无 (registration, chapter) 维度的唯一键
--     · perks_snapshot 列建了但零写零读 ⇒ 「同意瞬间冻结,结算只认合作单」从未发生
--   本迁移建三张执行层表(职责与唯一键按 §3.2 定案),coop_perk 保留为申报层不动。
--
-- 【为什么不复用 coop_perk 直接扩列】
--   §3.2 的复用决策:纯新建会重复造已有的一半(perk 的品名/规则/有效期),纯复用又缺章节维
--   (coop_perk 挂 invite,一个 invite 覆盖整个主题,答不出「这份权益是哪一章的」)。
--   故 coop_perk = 申报层(商家有什么),merchant_chapter_offer = 执行层(这一章真正在供给什么)。
--
-- 【幂等】
--   三个 CREATE TABLE 用 IF NOT EXISTS,幂等。
--   **六个** ALTER ... ADD COLUMN 不幂等(重复执行报 1060)—— MySQL 8 无 ADD COLUMN IF NOT EXISTS
--   (MariaDB 专属,MySQL 8 报 1064)。执行前必须跑 P1 预检确认列不存在。
--   ⚠️ 本仓另有一份用存储过程包 IF NOT EXISTS 做真幂等的老迁移
--   (migration_compliance_uniqueness_guard_20260712.sql)—— **那个模式今天已不可用**:
--   ci/migration_gates.py 的分句门会拦 CREATE PROCEDURE(分句器不支持 DELIMITER,
--   BEGIN...END 里的分号会被切错)。那份文件早于该门禁,拿它跑今天的 CI 会红。故只能靠预检。
--
-- 【可逆】—— ⚠️ 仅适用于「jar 已部署后要整体回退」,**不适用于本迁移执行中途失败**(见【执行方式】)
--   ALTER TABLE cms_topic_chapter DROP COLUMN category, DROP COLUMN perk_min_value,
--       DROP COLUMN max_merchant, DROP COLUMN required
--   ALTER TABLE coop_perk DROP COLUMN retail_value
--   ALTER TABLE coop_perk_template DROP COLUMN retail_value
--   DROP TABLE redemption, registration_entitlement, merchant_chapter_offer
--   回退顺序 = 先回 jar 再 DROP(反了会让运行中的 jar 引用不存在的列)。
--
-- 【风险】
--   中。风险不在建表(全新表,无存量),在两处:
--   ① **cms_topic_chapter 的真实 schema 不在仓库** —— 该表早于 sql/ 目录,仓库里没有它的建表 DDL
--      (`grep -rl cms_topic_chapter chengyinhub-admin/sql/*.sql` 只命中本文件自己)。故四个新列
--      是否已存在、表用什么 ROW_FORMAT(决定 ADD COLUMN 是不是 INSTANT),**只能靠 P2 预检看真库**。
--   ② `max_merchant` 与孤儿 commit 2b0aa004 的同名列语义一致(该 commit 未合并,列应未落库)。
--      若那批不知何时被 cherry-pick 过,P1 会查到列已存在 —— 按 P1 指引跳过对应 ADD COLUMN。
--
-- ⚠️⚠️ 生产库 MySQL 8。禁止直接执行。必须走 chengyin-db-migrate:先只读预检 → 用户确认 → 才执行。
-- ⚠️ 部署顺序:本迁移必须先于新 jar 落库。


-- ======================= 【执行方式】(先读这段,再跑) =======================
--
-- · 九条语句**按顺序逐条执行,失败即停**,不要整批灌。
-- · **中途失败是无害的,不要回滚**:新增的列全部可空或带 DEFAULT,三张表是全新的,
--   而 jar 此刻尚未部署 ⇒ 没有任何代码读写这些列。正确处置 = 修好失败那条,继续往下跑。
--   ⚠️ 千万别去跑【可逆】里的 DROP —— 那是给「jar 已部署后整体回退」用的,
--   在这里跑等于把一个无害状态变成一次真破坏。
-- · MySQL DDL 隐式提交、不可回滚 —— 这正是「失败即停 + 继续而非回滚」的原因。


-- ======================= 第一步:只读预检 =======================
--
-- P0【三张新表是否已存在】:
--   SELECT TABLE_NAME FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND TABLE_NAME IN ('merchant_chapter_offer','registration_entitlement','redemption');
--   · 0 行 → 全新。
--   · 3 行 → 已建过,跳到 ALTER 部分。
--   · **1-2 行 → 上次执行中途中断**。CREATE 带 IF NOT EXISTS 幂等,直接重跑第二步即可补齐,无需清理。
--
-- P0b【CHECK 约束是否真生效 · 必查】:
--   SELECT VERSION();
--   ⚠️ **MySQL 8.0.16 之前 CHECK 是静默忽略的(解析通过、不执行)= 假绿**。本迁移有三个 CHECK,
--      它们是「唯一键静默失效」与「配额扣成负数」两个资损的兜底。低于 8.0.16 则这三个 CHECK 形同
--      注释,必须知会一声再决定(改触发器 / 纯靠应用层 CAS)。
--
-- P1【必看 · 决定跳过哪些 ALTER】六个新列是否已存在:
--   SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE
--     FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND ((TABLE_NAME = 'cms_topic_chapter' AND COLUMN_NAME IN ('category','perk_min_value','max_merchant','required'))
--        OR (TABLE_NAME = 'coop_perk' AND COLUMN_NAME = 'retail_value')
--        OR (TABLE_NAME = 'coop_perk_template' AND COLUMN_NAME = 'retail_value'));
--   · 0 行 → 六个 ALTER 全执行。
--   · 有命中 → **逐条把已存在的那个 ALTER 从执行清单里划掉**(撞已存在列 = 1060)。
--   ⚠️ 特别留意 max_merchant(见【风险】②)。
--
-- P2【章节表真实结构 · 两个用途,别只看一个】
--   SHOW CREATE TABLE cms_topic_chapter;
--   ① 看列名:确认 P1 的结论;本文件的 ADD COLUMN 一律不写 AFTER(建表 DDL 不在仓库,猜列名会 1054)。
--   ② **看 ROW_FORMAT**:决定六条 ALTER 是不是 INSTANT。本文件已给每条加 ALGORITHM=INSTANT ⇒
--      不是 INSTANT 就**硬报 1845 当场停**,而不是静默降级成全表重建(那会锁住一张不知道多大的表)。
--      真撞上 1845:去掉那条的 ALGORITHM 子句、择低峰窗口再跑,别硬扛。
--
-- P3【存量盘点】
--   SELECT (SELECT COUNT(*) FROM cms_topic_chapter)  AS chapters,
--          (SELECT COUNT(*) FROM coop_perk WHERE del_flag = 0) AS perks;
--   四个章节列都有 DEFAULT(required 默认 1 = 必选,max_merchant 默认 4),存量自动获得合理值,
--   **无需回填**。retail_value 可空,存量为 NULL ⇒ 入驻门槛校验对未申报零售价的 perk 一律不通过
--   (P0-12 的「比 retail_value」),这是**故意的**:没报零售价就不该进供给池。
--   ⚠️ 行数对估锁没用(INSTANT 与行数无关);它只用来判断回填必要性。


-- ======================= 第二步:执行层三表 =======================

-- 表 1:商家×章节的生效供给(执行层)。coop_perk 是「商家有什么」,本表是「这一章真正在供给什么」。
--
-- ⚠️ merchant_id 语义 = memberId,不是 mms_merchant.id(术语陷阱:库里「商家」是三个东西共用一词,
--    settlement_detail.merchant_id 也是 memberId —— 语义超载曾致一次资损)。
--
-- ⚠️ 刻意**不设 per-offer 预占列**(§3.2 定案):玩家购买时并未选商家,预占的真源是章节级的
--    「已售未核销 entitlement 数」,不是某个 offer 的。设了就会有两个真源。
--
-- ⚠️ **唯一键取 (chapter_id, merchant_id) 而不是带上 perk_id** —— 这是个会影响钱的选择:
--    容量公式按 Σ(quota_total − quota_used) 求和,若同一商家同一章节能开多行(每个 perk 一行),
--    Σ 就会把「商家挂了 3 种权益供玩家选、总共只接待 10 人」算成 30 人 ⇒ 超卖 ⇒ 缺口事件 ⇒ 整单退。
--    而 redemption 的唯一键是「每票每章一次」= 玩家一章只领一份,所以 Σ 必须按**接待人次**求和,
--    不能按权益种类求和。故:一个商家一章一行,quota_total = 该商家在这章的真实接待上限,
--    perk_id = 他供给的是哪份权益。这也让 max_merchant 有了 DB 背书(数行即商家数)。
--
-- ⚠️ **「生效」是两条轴,缺一即超卖**:status=1 且 valid_end 未过期。valid_end 到期**不会**自动翻
--    status,没有任何任务在做这件事。裸写 `WHERE status=1` 算容量会把过期供给算进去。
CREATE TABLE IF NOT EXISTS merchant_chapter_offer (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    topic_id     BIGINT       NOT NULL COMMENT '主题id(冗余,便于按主题聚合容量)',
    chapter_id   BIGINT       NOT NULL COMMENT '章节id',
    merchant_id  BIGINT       NOT NULL COMMENT '商家memberId(⚠️非mms_merchant.id)',
    perk_id      BIGINT       NOT NULL COMMENT '引用coop_perk.id(申报层),即本商家在这章供给的是哪份权益',
    quota_total  INT          NOT NULL DEFAULT 0 COMMENT '本商家在本章的接待上限(人次,不是权益种类数)',
    quota_used   INT          NOT NULL DEFAULT 0 COMMENT '已核销人次(核销时原子+1,撤销时原子-1)',
    valid_end    DATETIME     NULL COMMENT '供给有效期(空=不限)。⚠️到期不会自动翻status,算容量必须同时判它',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1生效 0失效(商家退出标失效,不删行)。⚠️生效判据=status=1 AND (valid_end IS NULL OR valid_end>NOW()),两条缺一即超卖',
    create_by    VARCHAR(64)  NULL,
    create_time  DATETIME     NULL,
    update_by    VARCHAR(64)  NULL,
    update_time  DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_offer_merchant_chapter (chapter_id, merchant_id),
    KEY idx_offer_capacity (chapter_id, status, valid_end, quota_total, quota_used),
    KEY idx_offer_topic (topic_id, status),
    KEY idx_offer_merchant (merchant_id, status),
    CONSTRAINT ck_offer_quota CHECK (quota_used >= 0 AND quota_used <= quota_total)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家×章节生效供给(执行层;申报层是coop_perk)';

-- 表 2:每票每必选章的应得/已得/失效。
-- 售出一张 5 必选章通票的瞬间,平台已欠玩家 5 份章节权益 ⇒ **未核销也占容量**(§3.2 定案)。
--
-- ⚠️ **uk 不含 status:失效(2)行永久占坑**。任何「重新发放」(退款失败回滚 / 缺口事件补发 /
--    运营手工补权益)一律 `UPDATE status=0`,**禁止 INSERT**(会 1062)。
--    ⚠️ 这与 redemption **恰好相反** —— 那边撤销后生成列变 NULL、键释放、可重插。两张表的重发语义
--    相反,是这一组表最容易写错的地方。
--
-- ⚠️ status 这一行是**核销与退款的唯一序列化点**。核销事务必须**先** CAS 抢占它(status 0→1),
--    再扣 offer 配额,最后插 redemption;顺序反了会出现「钱退了货照发」:
--      T1 核销读到 status=0 判定可核销 → T2 退款置 status=2 提交 → T3 核销 UPDATE status=1
--      (若 WHERE 不带 status=0)把 2 覆盖回 1 ⇒ 玩家钱已退、商家咖啡已端出、还会进结算。
--    故合法写法恒为:UPDATE ... SET status=1 WHERE registration_id=? AND chapter_id=? AND status=0,
--    0 行即拒。退款侧同理:UPDATE ... SET status=2 WHERE registration_id=? AND status=0,
--    0 行 = 已核销 ⇒ 走「已核销退款」分支冲减商家结算,**不能当无事发生**。
CREATE TABLE IF NOT EXISTS registration_entitlement (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    registration_id BIGINT      NOT NULL COMMENT '报名id(=票)',
    chapter_id      BIGINT      NOT NULL COMMENT '必选章节id',
    topic_id        BIGINT      NOT NULL COMMENT '主题id(冗余,便于按主题聚合)',
    status          TINYINT     NOT NULL DEFAULT 0 COMMENT '0应得(已售未核销,占容量) 1已得(已核销) 2失效(退款/取消,释放容量)',
    invalid_reason  VARCHAR(32) NULL COMMENT '失效原因 REFUND/ORGANIZER_CANCEL/FULFILLMENT_GAP/MANUAL(status=2时应填)',
    redeemed_at     DATETIME    NULL COMMENT '核销时间',
    create_time     DATETIME    NULL,
    update_time     DATETIME    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_entitlement (registration_id, chapter_id),
    KEY idx_entitlement_chapter_status (chapter_id, status),
    KEY idx_entitlement_topic (topic_id),
    CONSTRAINT ck_entitlement_status CHECK (status IN (0, 1, 2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每票每必选章的权益应得/已得/失效(容量预占真源)';

-- 表 3:核销流水 —— 不可变核销事件 + 反向撤销事件。
-- 为什么要「撤销」而不是删/改:店员误扫、运营撤销、权益补发后重新核销,都需要留痕且要让键释放。
--
-- 唯一键只对 ACTIVE 生效(生成列 + UNIQUE):
--   · ③ 每票每章一次 ⇒ 键 = registration-chapter(**天然防跨商家重复核销 = V2 的根治**)
--   · ① 每票整单一次 ⇒ chapter_id 为空,键退化成 registration-ORDER
--   · 撤销(status=REVERSED)后生成列为 NULL,MySQL 唯一索引不约束 NULL ⇒ 键自动释放,可重新核销
--
-- ⚠️ **哨兵归一 NULLIF(chapter_id, 0)**:本仓前端对「可选 ID」传的是哨兵 0 而不是 NULL
--    (merchantinfo.js 的 `parseInt(options.nodeId) || 0` 直接把 0 落库)。不归一的话,
--    整单核销走 chapter_id=0 的路径得 key='5-0'、走 NULL 的路径得 key='5-ORDER',两键不同 ⇒
--    同一份整单权益可被核销两次,唯一键完全不拦,且静默。migration_a13_dual_award_unique.sql:77-83
--    为同一个坑专门在 win_key 的 CASE 里加了 `node_id <> 0` —— 同款教训,别再推一遍。
--
-- ⚠️ **status 的取值域与 active_key 的 CASE 是一对必须同改的双胞胎**。CASE 只认 'ACTIVE',
--    status 一旦不等于它(typo 'ACTIV'、将来加第三态),CASE 落进无 ELSE 分支 → active_key=NULL →
--    唯一索引不约束 NULL → **这张表声称的 V2 根治被静默绕过,三家商家可对同一票同一章各自核销成功、
--    零报错**。故必须有 ck_redemption_status 兜底:加值就在迁移期撞墙,被迫同步改 CASE。
--
-- ⚠️ idempotency_key 保护**请求**幂等(扫码超时重试),active_key 保护**业务结果**唯一(每票每章一次)。
--    键值构造沿用本仓 SettlementEntryServiceImpl 的模式:redeem:{reg}:{chapter}:{merchant}。
--
-- ⚠️⚠️ **它的唯一键必须和 active_key 一样只对 ACTIVE 生效**,否则会把「撤销后可重新核销」整个废掉。
--    真库实测过这个坑:裸 `UNIQUE(idempotency_key)` 时,撤销后 active_key 变 NULL(键如设计释放),
--    但 idempotency_key 原封不动 ⇒ 同一商家改正后重新核销恒 1062 —— 与本表声称的设计正相反,
--    而店员误扫→撤销→改正重扫恰恰是这张表存在的头号理由。
--    故用生成列 active_idem_key 同款收敛:REVERSED 行的键塌成 NULL 自动让位。
--    这样两种幂等都还在:①同一请求重试 → 两次都是 ACTIVE、撞 uk_redemption_idem_active ✅
--                        ②撤销后重新核销 → 旧行已 REVERSED、键已释放 ✅
--                        ③重复撤销 → 撤销是 UPDATE 不是 INSERT,由 status='ACTIVE' 的 CAS 兜,不靠键
CREATE TABLE IF NOT EXISTS redemption (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    registration_id BIGINT       NOT NULL COMMENT '报名id(=票)',
    chapter_id      BIGINT       NULL COMMENT '章节id;NULL/0=① 整单核销(无章节维度)。③ 必须非空,由核销服务保证',
    topic_id        BIGINT       NOT NULL COMMENT '主题id',
    merchant_id     BIGINT       NOT NULL COMMENT '核销商家memberId',
    offer_id        BIGINT       NULL COMMENT '命中的merchant_chapter_offer.id;NULL=① 无供给概念',
    operator_id     BIGINT       NOT NULL COMMENT '实际操作人memberId(店员≠商家主体时可区分)',
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE生效 / REVERSED已撤销。⚠️与active_key的CASE是双胞胎,加值必改CASE',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '请求级幂等键 redeem:{reg}:{chapter}:{merchant} / reverse:{redemptionId}',
    reversed_at     DATETIME     NULL COMMENT '撤销时间',
    reverse_reason  VARCHAR(255) NULL COMMENT '撤销原因(误扫/运营撤销/权益补发)',
    create_time     DATETIME     NULL,
    active_key      VARCHAR(64)  GENERATED ALWAYS AS (
                        CASE WHEN status = 'ACTIVE'
                             THEN CONCAT(registration_id, '-', IFNULL(NULLIF(chapter_id, 0), 'ORDER'))
                        END) VIRTUAL COMMENT '生效核销的唯一键;撤销后为NULL自动释放',
    active_idem_key VARCHAR(128) GENERATED ALWAYS AS (
                        CASE WHEN status = 'ACTIVE' THEN idempotency_key END
                        ) VIRTUAL COMMENT '生效核销的请求幂等键;撤销后为NULL自动释放,让同一商家可改正重扫',
    PRIMARY KEY (id),
    UNIQUE KEY uk_redemption_active (active_key),
    UNIQUE KEY uk_redemption_idem_active (active_idem_key),
    KEY idx_redemption_reg (registration_id),
    KEY idx_redemption_merchant (merchant_id, create_time),
    KEY idx_redemption_chapter (chapter_id, status),
    CONSTRAINT ck_redemption_status CHECK (status IN ('ACTIVE', 'REVERSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='核销流水(不可变事件+反向撤销;唯一键只对ACTIVE生效)';


-- ======================= 第三步:章节承接配置(P0-12) =======================
-- ⚠️ 不写 AFTER:cms_topic_chapter 的建表 DDL 不在仓库,猜列名会 1054(见预检 P2)。
-- ⚠️ 加 ALGORITHM=INSTANT:不是 INSTANT 就硬报 1845 当场停,而不是静默降级成全表重建(见预检 P2)。
-- ⚠️ 每条独立一句,便于按 P1 的结果逐条跳过。
--
-- 🔒 **购票路径的防超卖定案 —— 两件事,缺一不可**(§3.2 容量公式)
--    章节可续售容量 = Σ(生效offer的 quota_total − quota_used) − 该章已售未核销 entitlement 数
--    —— 跨两表的聚合,库里没有单行可锁。
--
--    ① **容量聚合必须走当前读(FOR SHARE)** ← 这才是防超卖的真防线
--       ⚠️ 本文件初版把这条写成了「先 FOR UPDATE 锁章节行再算聚合」,**那是错的,真库实测打脸**:
--       RR 下 read view 在事务的第一次**普通读**时就建立了,行锁能等到对手提交、却读不到对手刚提交的
--       entitlement ⇒ 两边算出同样的容量 ⇒ 双双插入。实测:供给 1 座 / 2 并发买家 → B 在锁上真阻塞
--       0.81s,醒来仍算出 capacity=1 → sold=2;压测 8 买家 / 3 座 → **8 人全买进、0 拒绝、零告警**。
--       换成 FOR SHARE 当前读后:单例正确拒绝,压测恰好 3 进 5 拒、0 死锁。
--       ⚠️ 别指望 REQUIRES_NEW 或 isolation=READ_COMMITTED 顶替:前者搬不走 read view(它由方法内的
--       第一次普通读建立)、且会让 entitlement 独立提交在购票失败时泄漏;后者在 Spring 里对
--       REQUIRED 参与者是**静默忽略**的(validateExistingTransaction 默认 false),假绿。
--    ② **章节行锁**:`SELECT id FROM cms_topic_chapter WHERE id IN (...) ORDER BY id FOR UPDATE`
--       (**按 id 升序,防 ABBA**)。它管的是「同章购票串行 + 加锁顺序」,**不管**防超卖。
--
--    刻意**不物化 capacity 计数列** —— 那会造出 §3.2 明令避免的第二个真源(且要在 offer 增删改额、
--    entitlement 增删失效共 6 个点同步,漏一个就静默错账)。
--    加锁顺序全局定案:cms_topic_chapter → registration_entitlement → merchant_chapter_offer → redemption。
--    核销路径不碰章节行(它按 entitlement → offer → redemption 走),故与购票无 ABBA。

ALTER TABLE cms_topic_chapter
    ADD COLUMN category VARCHAR(32) NULL COMMENT '章节品类(如 咖啡/书店/酒吧),用于商家匹配', ALGORITHM=INSTANT;

ALTER TABLE cms_topic_chapter
    ADD COLUMN perk_min_value DECIMAL(10,2) NULL COMMENT '入驻门槛:商家权益零售标价须≥此值。⚠️比的是retail_value零售价,不是unit_cost成本价', ALGORITHM=INSTANT;

ALTER TABLE cms_topic_chapter
    ADD COLUMN max_merchant TINYINT NOT NULL DEFAULT 4 COMMENT '本章节最多可承接商家数(默认4)', ALGORITHM=INSTANT;

ALTER TABLE cms_topic_chapter
    ADD COLUMN required TINYINT NOT NULL DEFAULT 1 COMMENT '1必选章(计入通关判定与容量) 0可选章。默认1=保持既有「每章都算」语义', ALGORITHM=INSTANT;


-- ======================= 第四步:权益零售标价(P0-12) =======================
-- unit_cost 的库注释是「单人成本价·商家自报」= 成本不是标价,定价引擎按它算成本。
-- 入驻门槛要比的是**零售标价**(玩家眼里这份权益值多少),故必须是另一列。
-- 可空:存量 perk 没报过零售价 ⇒ 门槛校验一律不通过,这是故意的(没报零售价就不该进供给池)。
--
-- ⚠️ coop_perk_template 也要加(§3.2 原文只提了 coop_perk):template 是 perk 的模板源,
--    模板没有 retail_value ⇒ 从模板生成的 perk 恒 NULL ⇒ 按上面「NULL 一律不通过」的设计,
--    **所有走模板的商家全部进不了供给池**。这是补文档的洞,不是夹带。

ALTER TABLE coop_perk
    ADD COLUMN retail_value DECIMAL(10,2) NULL COMMENT '权益零售标价(玩家视角价值);入驻门槛比它,不比unit_cost', ALGORITHM=INSTANT;

ALTER TABLE coop_perk_template
    ADD COLUMN retail_value DECIMAL(10,2) NULL COMMENT '权益零售标价(玩家视角价值);入驻门槛比它,不比unit_cost', ALGORITHM=INSTANT;
