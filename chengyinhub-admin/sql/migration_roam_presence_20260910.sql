-- migration_roam_presence_20260910.sql
-- 走 chengyin-db-migrate;生产 MySQL 8。
-- ⚠️ 全文件是 ALTER TABLE ... ADD COLUMN(普通写法,不带 IF NOT EXISTS ——
--    那是 MariaDB 扩展,MySQL 8 直接 ERROR 1064,整个迁移失败)。
--    幂等靠落库前的只读预检,不靠语法。
--
-- 【硬依赖警告 —— 必须先于 jar 上线】
--   没有这三列:POST /api/roam/presence 与 /api/roam/nearby-runners 双双
--   Unknown column 报 500,而且是**每一次定位心跳都 500**,日志会被刷满。
--
-- 做什么:给漫游会话记一份「最近一次上报的位置」,让地图能画出附近同时在漫游的人
--        (原型 f-other-a / f-other-b:点头像出 TA 的半屏)。
--
-- 隐私口径(2026-09-10 用户拍板「做,按原型来」):
--   露给附近陌生人的字段就是原型那几样 —— 昵称、头像、地盘 %、探店数、在走时长、
--   TA 这一趟点亮的店的照片。**不含**手机号、真实姓名、精确坐标。
--   坐标服务端截到小数点后 3 位(约 100 m 一格)再落库:够地图上看出「有人在附近」,
--   不够跟着一个人走。截位在 ApiRoamController.presence 里做,客户端传什么都要过这一刀。
--   关掉定位 = 不再上报 = last_report_at 变旧 = 立刻从别人地图上消失(见 PRESENCE_TTL_MIN)。

ALTER TABLE `roam_session`
  ADD COLUMN `last_lat`       decimal(10,7) DEFAULT NULL COMMENT '最近一次上报纬度(已截到 3 位小数,约 100m 网格)',
  ADD COLUMN `last_lng`       decimal(10,7) DEFAULT NULL COMMENT '最近一次上报经度(同上)',
  ADD COLUMN `last_report_at` datetime      DEFAULT NULL COMMENT '最近一次上报时间;超过 TTL 就不算在线',
  ADD COLUMN `explore_pct`    tinyint       DEFAULT 0    COMMENT '客户端算的本次探索度 0-99;仅展示,服务端已 clamp';

-- 附近查询走这条:先按 status+时间收敛到「此刻在线的会话」,再在应用层按半径筛。
-- 不建空间索引 —— 在线会话是个位数到百量级,一次全扫比维护 SPATIAL 便宜。
ALTER TABLE `roam_session`
  ADD KEY `idx_presence` (`status`, `last_report_at`);

-- 上线后校验:
--   SELECT COUNT(*) FROM information_schema.columns
--    WHERE table_name='roam_session' AND column_name IN ('last_lat','last_lng','last_report_at','explore_pct');  -- 应 4
