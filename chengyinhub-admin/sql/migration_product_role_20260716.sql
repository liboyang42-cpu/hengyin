-- P0-35 角色槽表 product_role 落库
-- 来源:《城瘾代码侧_现状与改造施工图_TODO_2026-07-15》§3.1 / P0-35(D6,2026-07-16 第二轮拍板)
--
-- 【为什么要这张表】
--   原设计是四个散字段(creator_member_id / publisher_member_id / publisher_context_type /
--   publisher_context_id)+ Phase 7 一堆按身份分格的门禁,在写代码前就被推翻了 —— 身份轴 × 形态轴的
--   4×4 矩阵长出三个自相矛盾(③ 票款归属 / CONTENT_ONLY 白拿 / 保证金收反)。D6 换成「角色声明挂在
--   这一笔交易上,不挂在主体的全局身份上」(出处:Stripe Connect destination charge / 音乐版税
--   split sheet / Amazon FBA-vs-FBM)。
--
--   ⚠️ **今天代码里根本没有一列能说「这个主题是自营的」**。最接近的是 CmsTopicServiceImpl:208 那句
--   裸魔数 `newTopic.setMemberId(100000L)` —— 全仓只出现一次、无名无注释,大概是平台账号但没人这么写。
--   这与 P0-02 治好的病一模一样:「是不是自玩票」曾只能靠 (owner_type + ticket_id + expires_at)
--   的组合约定反推。**没有 entity_type=PLATFORM 这个值,③-自营(首轮标的,D12)就没法表达。**
--
-- 【本迁移的范围 —— 只落地基,不落门禁】
--   施工图 §4「可延后」栏把 P0-34~P0-39 的**发布主体门禁**判为可延后(平台自己发,没有门禁问题);
--   但「必清」栏明写「P0-02/03(产品真源,**entity_type 须含 PLATFORM**)」,而 P0-35 自己标着 🔴
--   「本 Phase 的地基,先做这个」。⇒ **本迁移只建表 + 落 PLATFORM 这个值**,
--   P0-34/37/38/39 的门禁逻辑不在本次。
--   直接下游:**P0-50 的范围闸**(REVSHARE 仅 product_role.CONTENT.entity_type=PLATFORM 的主题可选)
--   —— 没有这张表,那道闸无处落脚,而它拦的是 ③-第三方 的第三方间分钱 = 二清。
--
-- 【关键设计:MySQL 8 建不出施工图写的那个唯一键】
--   §3.1 的 DDL 骨架写的是:
--       UNIQUE(topic_id, slot) WHERE slot IN ('CONTENT','ORGANIZER')
--   **带 WHERE 的部分唯一索引是 PostgreSQL / SQL Server 的特性,MySQL 8 没有**。
--   而且它还漏了 status —— 同一段文档写着「status 支持『旧承接标失效』不删行」,可这个键不看 status
--   的话,**承接方一旦标失效就永远换不了人**(旧行仍占着 (topic_id, ORGANIZER))。
--   解法照搬 redemption.active_key 那次踩过的坑:**生成列只对「生效且是单例槽」的行取值,
--   唯一键建在生成列上**。失效行 → 生成列为 NULL → MySQL 的 UNIQUE 允许多个 NULL → 键自动释放。
--
-- 【为什么 SUPPLY / DISTRIBUTION 不进这个历史唯一键】
--   SUPPLY 仍可多行（一章多商家）；DISTRIBUTION 当前首三期为单例，但由后续独立 generated guard
--   + UNIQUE 下沉，避免改动 CONTENT/ORGANIZER 已上线的 singleton_key 约束。
--
-- 【幂等】
--   CREATE TABLE IF NOT EXISTS **幂等**,重复执行无害。
--   ⚠️ 但**不回填任何历史数据**(见下),所以本迁移不存在「跑一半脏了」的状态。
--
-- 【可逆】
--   DROP TABLE IF EXISTS product_role;
--   ⚠️ 仅适用于「jar 已回退后整体拆除」。表是新建的、无外键、无人依赖 ⇒ 回退干净。
--
-- 【风险】
--   **低 —— 零行为变化**。新表新代码,读侧全部 fail-closed:
--   查不到 CONTENT 槽 ⇒ 当作「不是自营」⇒ P0-50 的 REVSHARE 拒绝。存量主题一行都没有 ⇒ 全部拒绝
--   REVSHARE,这是**故意的**:施工图原文「历史回填:判不清人工确认,**禁静默回填**」。
--   静默按 member_id 回填 = 把一个猜出来的归属写成事实,而票款归属就挂在 CONTENT 槽上(D8)。
--
--   ⚠️ **首轮开工前必看**:③-自营 的主题要能用 REVSHARE 档,其 CONTENT 槽必须是 PLATFORM。
--   本次接线是「后台建题 ⇒ PLATFORM,小程序创作者建题 ⇒ MEMBER/CLUB/MERCHANT」。
--   若首轮的 ③ 主题是从**小程序发布器**建的,它拿到的是 MEMBER ⇒ REVSHARE 会被拒。
--   **这是 fail-closed 的预期行为,不是 bug** —— 届时用后台把 CONTENT 槽改成 PLATFORM 即可,
--   而不是让闸放行。宁可当场拒绝,也不要静默放过一个未来会变成二清的口子。
--
-- 【实测说明 —— 别把它当成在生产版本上验过】
--   建表 + 6 条行为(同主题两个生效 CONTENT 撞 1062 / 标失效后能换人(生成列键释放)/
--   SUPPLY 可多行 / PLATFORM 带 entity_id 被 ck_role_entity_id 拒 / 非 PLATFORM 不带 entity_id 被拒 /
--   非法 slot 被 ck_role_slot 拒)已在**本地 MySQL 9.7.1** 上逐条跑通。
--   ⚠️ **生产是 MySQL 8.x,不是 9.7**。生成列 + 生成列上建 UNIQUE 自 5.7 起、CHECK 自 8.0.16 起
--   语义稳定,故判断可迁移 —— 但这是**推断,不是在生产版本上的观测**。P2 的版本闸必须真跑。
--
-- ⚠️⚠️ 生产库 MySQL 8。禁止直接执行。必须走 chengyin-db-migrate:先只读预检 → 用户确认 → 才执行。
-- ⚠️ 部署顺序:本迁移必须先于新 jar 落库。


-- ======================= 【执行方式】 =======================
-- · 一条 CREATE TABLE,幂等,失败即停。
-- · **不回填、不改任何现有表**。


-- ======================= 第一步:只读预检 =======================
--
-- P1【必看】表是否已存在:
--   SELECT TABLE_NAME FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product_role';
--   · 0 行 → 正常执行。 · 1 行 → 已建过,IF NOT EXISTS 会跳过(无害),但要确认列与本文件一致:
--     SHOW CREATE TABLE product_role;
--
-- P2【版本闸】生成列 + 生成列上建 UNIQUE 需要 MySQL 5.7+,本方案还依赖 8.0 的 CHECK:
--   SELECT VERSION();   -- 期望 8.0.16+(CHECK 在 8.0.16 前只解析不执行)
--
-- P3【落库后自检】新建的主题是否真的落了 CONTENT 槽(**这条最重要**):
--   SELECT topic_id, slot, entity_type, entity_id, status, created_at
--     FROM product_role WHERE slot = 'CONTENT' ORDER BY id DESC LIMIT 10;
--   期望:**部署之后新建的主题,每个都有且仅有一行 CONTENT 槽**。若一行都没有 ⇒ 接线断了,
--   槽表恒空 ⇒ P0-50 的 REVSHARE 对所有主题恒拒 ⇒ 计酬档整个用不了,且没有任何测试会红
--   (源码断言守的是「代码长这样」,守不住「运行时真写进去了」)。
--   ⚠️ 存量主题(部署前建的)没有任何行是**正常且预期**的,别去回填。
--
-- P4【自营确认】首轮的 ③ 主题拿到的是不是 PLATFORM:
--   SELECT t.id, t.name, r.entity_type
--     FROM cms_topic t LEFT JOIN product_role r ON r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1
--    WHERE t.product_type = 2;      -- 2 = FREE_EXPLORE = ③
--   期望:首轮自营主题 entity_type = 'PLATFORM'。若是 MEMBER ⇒ 是从小程序发布器建的,
--   用后台把 CONTENT 槽改过来,**别去改闸**。


-- ======================= 第二步:DDL =======================

CREATE TABLE IF NOT EXISTS product_role (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    topic_id      BIGINT       NOT NULL COMMENT '主题id(cms_topic.id)',
    slot          VARCHAR(16)  NOT NULL COMMENT '角色槽:CONTENT内容方/ORGANIZER组织方/SUPPLY供给方/DISTRIBUTION分销方。CONTENT、ORGANIZER各仅1个生效；SUPPLY可多行；DISTRIBUTION当前首三期为单例，由后续独立guard兜底',
    entity_type   VARCHAR(16)  NOT NULL COMMENT '主体类型:PLATFORM平台/MEMBER玩家/CLUB俱乐部/MERCHANT商家。⚠️PLATFORM是③-自营(首轮标的D12)的唯一表达方式,原枚举没有它',
    entity_id     BIGINT       NULL COMMENT '主体id。entity_type=PLATFORM时为NULL(平台不是某个member);其余为对应的memberId/clubId',
    revenue_share DECIMAL(10,4) NULL COMMENT '该槽的分成比例。③票款归CONTENT槽持有者(D8);本次不落分账逻辑,列先留着由P0-46/P0-50填',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1生效 0失效。⚠️旧承接标失效不删行(要留痕),所以唯一键必须只对生效行生效',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    -- CONTENT/ORGANIZER 单例槽的历史唯一键。DISTRIBUTION 由后续独立 guard + UNIQUE 兜底。
    -- MySQL 8 没有「UNIQUE ... WHERE」(那是PG/SQLServer的部分索引),
    -- 用生成列表达条件:只有「生效 + 单例槽」的行才有值,其余为NULL;UNIQUE允许多个NULL ⇒
    -- 标失效后键自动释放,承接方换得了人。与redemption.active_key同一手法。
    singleton_key VARCHAR(32) GENERATED ALWAYS AS (
        CASE WHEN status = 1 AND slot IN ('CONTENT', 'ORGANIZER')
             THEN CONCAT(topic_id, '-', slot) END) VIRTUAL COMMENT '生效单例槽的唯一键(生成列)',

    PRIMARY KEY (id),
    UNIQUE KEY uk_product_role_singleton (singleton_key),
    KEY idx_role_topic_slot (topic_id, slot, status),
    KEY idx_role_entity (entity_type, entity_id, status),
    CONSTRAINT ck_role_slot CHECK (slot IN ('CONTENT', 'ORGANIZER', 'SUPPLY', 'DISTRIBUTION')),
    CONSTRAINT ck_role_entity_type CHECK (entity_type IN ('PLATFORM', 'MEMBER', 'CLUB', 'MERCHANT')),
    -- PLATFORM 不对应任何 member,entity_id 必须空;其余三类必须有主体。
    -- 不写这条的话,「PLATFORM + entity_id=123」这种自相矛盾的行能落库,而票款归属挂在这个槽上。
    CONSTRAINT ck_role_entity_id CHECK (
        (entity_type = 'PLATFORM' AND entity_id IS NULL)
        OR (entity_type <> 'PLATFORM' AND entity_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品角色槽(D6):角色声明挂在这个主题上,不挂在主体的全局身份上';
