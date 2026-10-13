-- App(微信开放平台移动应用)openid 独立列
--
-- 背景与必要性:
--   ums_member 原先只有一列 wx_open_id,**小程序 openid 与 App openid 混存** ——
--   AppLoginService:129 与 ApiLoginController /login/wechat/app 都写这一列,
--   后者注释原文:「查不到再按 wxOpenId(=该 App openid)查」。
--   微信按 (appid, openid) 认人,而商家转账到零钱必须传 appid:
--   拿小程序 appid 配 App openid 会被判「不匹配」而拒付 ⇒ **App 用户提现一直失败**
--   (资金有兜底会退回余额,不丢钱,但功能不可用且错误指向 openid,极易误判)。
--
-- ⚠️ 存量数据**无法回溯区分**:已经写进 wx_open_id 的值看不出是哪个应用发的。
--   故本迁移只加列、不回填,由 App 登录时渐进写入(见 ApiLoginController)。
--   代价:存量 App 用户在**下次 App 登录之前**提现仍会失败。这是已知且有意
--   接受的过渡代价 —— 任何"猜测式回填"都可能把小程序 openid 误标成 App 的,
--   那会让原本正常的小程序用户也提现失败,比现状更糟。
--
-- 执行方式:连生产库 source 本文件(或用 chengyin-db-migrate skill 跑)。
-- 落库须先于部署 —— 否则新代码读该列会报 Unknown column。

ALTER TABLE ums_member
    ADD COLUMN app_open_id varchar(64) NULL COMMENT '微信开放平台移动应用openid(App端)' AFTER wx_open_id;

-- 按 openid 反查用户是登录热路径,必须有索引。
ALTER TABLE ums_member
    ADD INDEX idx_ums_member_app_open_id (app_open_id);
