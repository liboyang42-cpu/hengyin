-- 关掉工作台上那条永远清不掉的「待扫码」(2026-08-20)。
--
-- 病:商家 100096 的工作台在主题卡上挂着「待扫码」,点进去却是「已结束」。
-- 根因(2026-08-20 查实,两层):
--   ① 数据层:cms_registration_scan#15 建于 2026-02-05,status 一直是 0(待商家确认),
--      而它指向的 cms_registration#72 的 verification_status 早就是 1(已核销)。
--      ★ 全仓**没有任何生产代码**调用 updateCmsRegistrationScan —— scan.status 一旦写成 0
--        就再没有任何路径能把它变成 1。这是个只增不减的计数器。
--   ② 口径层:/api/merchant/todo-summary 完全不看项目的 end_date / lifecycle,
--      主题 21「副本人生」end_date=2026-03-28 早已结束,照样发待办。
--      这一层由同批的 ApiMerchantController 改动堵住(结束满 7 天不再进待办,
--      口径对齐玩家侧 ApiRegistrationController.filterEndedOverWeek)。
--
-- 本文件只做 ①:把这条既成事实的扫码记录标成已确认。
-- 它对应的报名单本就已核销,所以 status=1 是把库写成事实,不是抹掉待办。
--
-- ⚠️ 全库另有 4 条 status=0 的 scan(id 11/12/13/14,属于 100000/100092/100086),
--   同样指向已结束的主题 8 / 21。本文件**不动它们** —— 那不是本次报障的账号,
--   且代码层修完后它们也不会再产生待办。要清另开迁移,别顺手捎带。
--
-- 幂等:UPDATE 带 status=0 条件,重跑命中 0 行。

-- 前置断言:scan#15 必须仍是我 2026-08-20 读到的样子,且其报名单确实已核销。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM cms_registration_scan
         WHERE id = 15 AND member_id = 100096 AND registration_id = 72 AND status = 0) = 1
   AND (SELECT COUNT(*) FROM cms_registration
         WHERE id = 72 AND verification_status = 1 AND payment_status = 2) = 1;

UPDATE cms_registration_scan
   SET status = 1
 WHERE id = 15 AND member_id = 100096 AND status = 0;

-- 回读断言:100096 名下不得再有待确认扫码。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM cms_registration_scan
         WHERE member_id = 100096 AND status = 0) = 0;
