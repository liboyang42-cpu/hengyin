-- 自由探索:承接商家在章节下拥有自己的节点(2026-08-06 用户拍板 B 方案)
--
-- 背景:自由探索的章节可招 N 家商家(max_merchant),但商家承接的是「供给 offer」,
-- offer 上没有 nodeId —— 商家在玩家眼里只是个兑券点,没有自己的玩法。
-- 于是同一章节下的 8 家店跑主办方同一套玩法,玩家去 1 家和去 8 家体验完全相同,
-- 「一章节招 8 家」这个商业前提不成立。
--
-- B 方案:**商家 = 章节下的一个节点**。玩家侧一行不用改(节点列表/打卡/答题/发分全部复用),
-- 概念也跟漫游据点一致(商家 = 地图上一个点)。
--
-- ★ 边界在**招商时公示、承接时冻结**(2026-08-06 用户定):
--   玩法边界跟其它招商条款走同一条路 —— 章节上公示,商家看得到才来申请;
--   承接通过后冻进 offer,之后按**冻结的那份**校验。
--   不是「商家做完了才被告知超标」,主办方审核也不必每次重新谈条件。
--   (与既有的 coop_perk 快照、offer.valid_end 强制复制同一范式。)
--
-- ★ 责任口径:审核 = **准入,不是背书**。出事责任在内容作者(商家)。
--   所以节点上同时留「谁写的」(merchant_member_id)和「谁放行的 + 何时」(audit_member_id/time)。
--
-- 可逆:全部 nullable,回滚 = DROP COLUMN / DROP INDEX。MySQL 8 ADD COLUMN 是 INSTANT,不锁表。

-- ===== ① 章节:公示玩法边界 =====
ALTER TABLE `cms_topic_chapter`
    ADD COLUMN `allowed_validation_methods` VARCHAR(32) DEFAULT NULL
        COMMENT '本章节允许商家使用的验证方式,逗号分隔如"1,3,4";NULL=不限(存量语义)' AFTER `max_per_head_fee`,
    ADD COLUMN `max_node_xp` INT DEFAULT NULL
        COMMENT '单节点探索值上限,商家节点不得超过;NULL=不限' AFTER `allowed_validation_methods`;

-- ===== ② 供给:承接时把边界冻结进来 =====
-- 为什么要冻:章节条款是主办方随时可改的。不冻的话,商家按 A 条款承接、
-- 主办方事后改成 B,商家已上线的节点会突然「超标」。冻结后改章节只影响后来者。
ALTER TABLE `merchant_chapter_offer`
    ADD COLUMN `allowed_validation_methods` VARCHAR(32) DEFAULT NULL
        COMMENT '承接时冻结的允许验证方式快照;NULL=不限' AFTER `valid_end`,
    ADD COLUMN `max_node_xp` INT DEFAULT NULL
        COMMENT '承接时冻结的单节点探索值上限快照;NULL=不限' AFTER `allowed_validation_methods`;

-- ===== ③ 节点:归属 + 审核 =====
--
-- ⚠️ 可见性口径(下游必须一致,这里是单一真源):
--     merchant_member_id IS NULL → 主办方节点,照常可见(存量,不能因本次迁移消失)
--     merchant_member_id 非空    → 商家节点,**仅 node_audit_status = 1 时可见**(fail-closed)
--   反过来写成「node_audit_status <> 1 才隐藏」会把 NULL 的存量主办方节点一起隐藏,整条路线空掉。
ALTER TABLE `cms_topic_node`
    ADD COLUMN `merchant_member_id` BIGINT DEFAULT NULL
        COMMENT '该节点归属的承接商家 member_id;NULL=主办方自建节点' AFTER `template_id`,
    ADD COLUMN `node_audit_status` TINYINT DEFAULT NULL
        COMMENT '商家节点审核态 0待审 1通过 2驳回;NULL=主办方节点不走审核' AFTER `merchant_member_id`,
    ADD COLUMN `node_audit_reason` VARCHAR(200) DEFAULT NULL
        COMMENT '驳回原因(审核态=2 时给商家看)' AFTER `node_audit_status`,
    ADD COLUMN `node_audit_member_id` BIGINT DEFAULT NULL
        COMMENT '放行/驳回该节点的主办方 member_id。审核是准入不是背书,责任仍在内容作者' AFTER `node_audit_reason`,
    ADD COLUMN `node_audit_time` DATETIME DEFAULT NULL
        COMMENT '审核时间' AFTER `node_audit_member_id`;

-- 查「这个章节下某商家的节点」和玩家侧按章节取节点都会走这个组合。
CREATE INDEX `idx_topic_node_chapter_merchant`
    ON `cms_topic_node` (`chapter_id`, `merchant_member_id`);

-- ===== ④ 申请来源:区分「商家自己申请」和「主办方邀请」 =====
--
-- 2026-08-06 用户补充:商家进来有两条路 —— 报名(申请)和邀请。
-- 邀请**不另建一套链路**:它本质就是「预先批准的申请」——
-- 主办方直接建一条 status=1(已通过)、source=1 的申请,商家在「我的申请」里看到后
-- 直接填供给 + 点位。申请/供给/点位三段全部复用,不动 coop_invite(它没有 chapter_id,
-- 为章节另开一套邀约是重复建设)。
--
-- source 还决定**点位要不要再审**:
--   source=0 商家申请 → 点位随申请一起审(主办方看着实际玩法决定要不要这家)
--   source=1 主办方邀请 → 点位免审(人是他主动邀的,再让他审自己邀的人没有意义)
ALTER TABLE `merchant_chapter_application`
    ADD COLUMN `source` TINYINT DEFAULT 0
        COMMENT '来源 0商家申请 1主办方邀请;邀请=预先批准的申请,其点位免审' AFTER `status`;
