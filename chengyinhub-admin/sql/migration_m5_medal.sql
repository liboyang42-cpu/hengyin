-- ============================================================
-- M5 勋章：跟随模板（俱乐部在模板配置里上传勋章图，完成绑定该模板的节点即获得）
-- 勋章墙从 player_node_progress 推导，不建发放表。
-- 执行前：确认本脚本未执行过（列重复会报错）。MySQL8 ADD COLUMN 走 INSTANT。
-- ============================================================

ALTER TABLE cms_member_template
    ADD COLUMN medal_img  VARCHAR(255) NULL COMMENT '勋章图片URL(俱乐部上传,空=该节点不发勋章)' AFTER coupon_id,
    ADD COLUMN medal_name VARCHAR(64)  NULL COMMENT '勋章名称' AFTER medal_img;
