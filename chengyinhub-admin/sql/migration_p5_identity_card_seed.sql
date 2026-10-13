-- migration_p5_identity_card_seed.sql
-- 走 chengyin-db-migrate:只读预检 `--describe growth_badge` 确认 unlock_hint 不存在后执行。
-- ADD COLUMN nullable,MySQL8 INSTANT 不锁表,可逆。必须先于引用新列的 jar 上线。
--
-- 【为什么有这份】PR#24 把 badge_def 引擎与 /api/badge/wall-v2 建好了,但种子只有 9 行,
-- 十张 PRD 城市身份卡从未入库,只活在 badge-wall 前端的硬编码 IDENTITY_CARDS 里。
-- 本迁移把身份卡目录的真源搬进库,前端只留渲染器(徽记几何)。
--
-- 【硬依赖警告】本迁移是 badge-wall 页运行期硬依赖:若 jar/小程序先于种子行上线,
-- 勋章墙将静默只显 3 张身份卡(FIRST_STEP/TOPIC_CLEAR/ID_ROAM_FIRST),
-- 无报错无日志——与 PR#24 的 selectByCode CRITICAL 同一失败家族。
-- 顺序必须是:本迁移落库 → jar 部署 → 小程序微信上传。
--
-- 【注释与字面量禁 ASCII 分号】2026-07-14 实测:COMMENT 'L3/L4可用;L1/L2为空' 里的
-- ASCII 分号被按分号拆句的执行器劈开,ALTER 被切成两半。本文件一律用中文逗号。
--
-- 上线后校验(身份卡应 = 11):
-- SELECT COUNT(*) FROM growth_badge WHERE status = '0' AND asset_type = 'IDENTITY'

ALTER TABLE growth_badge
  ADD COLUMN unlock_hint VARCHAR(255) NULL COMMENT '锁定态可理解的下一步,PRD 8.3';

-- 复用既有两枚为 PRD 身份卡(PRD 12.4)。这不是新造名字:badge-wall 前端的 CODE_TO_CARD
-- 早已把 FIRST_STEP 显示为「开始在场」、TOPIC_CLEAR 显示为「完成一程」,此处是让库对齐 UI 既成事实。
-- 副作用:/api/growth/center 等读同一行 badge_name 的界面会跟着改名,是收敛非回归。
UPDATE growth_badge SET
  badge_name  = '开始在场',
  name_en     = 'ARRIVAL',
  statement   = '我不是路过,我开始在场。',
  unlock_hint = '完成你的第一个有效节点',
  asset_type  = 'IDENTITY',
  category    = 'EXPLORE'
WHERE badge_code = 'FIRST_STEP';

UPDATE growth_badge SET
  badge_name  = '完成一程',
  name_en     = 'ROUTE CLEARED',
  statement   = '一座城,要走完一程才开始被记住。',
  unlock_hint = '完整走完一条主题路线',
  asset_type  = 'IDENTITY',
  category    = 'EXPLORE'
WHERE badge_code = 'TOPIC_CLEAR';

-- 回填发奖快照。player_badge.badge_name 是发奖当时从 def 复制下来的
-- (PlayerProgressionServiceImpl.addIfNew:String name = d.getBadgeName()),不随 def 改名走。
-- 不回填的话,库里 def 叫「开始在场」而老的已发放行仍叫「初次启程」,两处各说各话。
-- 勋章墙不受影响(它按 def 渲染),但没理由留着这个分叉。
UPDATE player_badge SET badge_name = '开始在场' WHERE badge_code = 'FIRST_STEP';
UPDATE player_badge SET badge_name = '完成一程' WHERE badge_code = 'TOPIC_CLEAR';

-- 既有漫游身份卡补下一步。它不在 PRD 十张之列,但确在库里且 asset_type=IDENTITY,
-- 故为墙上第 11 枚。前端无对应徽记,由 glyphs.js 的 DEFAULT 兜底(未知 key 落坐标点)。
UPDATE growth_badge SET
  unlock_hint = '完成你的第一次城市漫游'
WHERE badge_code = 'ID_ROAM_FIRST';

-- 种子:PRD 缺失的 8 张身份卡。category 为真实五类,恢复墙面五色轨道
-- (此前 9 行 category 全 EXPLORE,五色塌成一色)。
-- 不带 trigger_event / unlock_rule_json:授予事件属「平台事件授予」后续计划,
-- 本轮为锁定展示(PRD 14:首发 4 张点亮,其余展示可理解的下一步)。
INSERT INTO growth_badge (badge_code, badge_name, name_en, statement, unlock_hint, icon_url, asset_type, category, rule_version, status, sort_no, create_time)
VALUES
 ('ID_CITY_PROPOSAL','城市提案','CITY PROPOSAL','我向这座城,提交了第一个玩法。','发布第一个可被体验的城市任务','','IDENTITY','CREATE',1,'0',10,now()),
 ('ID_FOUNDING_CIRCLE','开门成社','FOUNDING CIRCLE','从今天起,这座城里有了我们的名字。','创建你的第一个俱乐部','','IDENTITY','ORGANIZE',1,'0',11,now()),
 ('ID_JOINED_CIRCLE','同路相逢','JOINED THE CIRCLE','城市很大,幸好遇见同路的人。','加入第一个真实俱乐部','','IDENTITY','CONNECT',1,'0',12,now()),
 ('ID_GATHERING','一次召集','THE GATHERING','一次召集,让陌生人共享了同一段城市时间。','组织一次真实成行的活动','','IDENTITY','ORGANIZE',1,'0',13,now()),
 ('ID_FOLLOWED_PATH','有人循迹','FOLLOWED PATH','我留下的路线,第一次被另一个人走完。','你的作品被第一位陌生玩家完整走完','','IDENTITY','CREATE',1,'0',14,now()),
 ('ID_NEW_COMPANION','带来同行者','NEW COMPANION','因为我的邀请,这座城多了一位参与者。','邀请的新用户完成有效激活','','IDENTITY','CONNECT',1,'0',15,now()),
 ('ID_CITY_ALLIANCE','共创发生','CITY ALLIANCE','当两种能力相遇,城市里发生了新的体验。','完成一次跨角色共创合作(即将开放)','','IDENTITY','CO_CREATE',1,'0',16,now()),
 ('ID_CITY_LIT','点亮一隅','CITY LIT','我为这座城,点亮了一个真实坐标。','首次点亮一处城市空间','','IDENTITY','EXPLORE',1,'0',17,now())
ON DUPLICATE KEY UPDATE
 badge_name=VALUES(badge_name), name_en=VALUES(name_en), statement=VALUES(statement),
 unlock_hint=VALUES(unlock_hint), asset_type=VALUES(asset_type), category=VALUES(category);
