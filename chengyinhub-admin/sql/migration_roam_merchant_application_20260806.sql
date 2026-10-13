-- L6 商家自助据点：申请当前态直接落 roam_poi，不污染主题报名账本。
-- ⚠️ 合并前需先落库；生产执行前走 chengyin-db-migrate 只读预检。
--
-- 合并前只读预检（未落库时应返回 0 行）：
-- SELECT column_name FROM information_schema.columns
-- WHERE table_schema = DATABASE() AND table_name = 'roam_poi'
--   AND column_name IN ('address','audit_status','application_type','applicant_merchant_id','audit_reason');

ALTER TABLE roam_poi
  ADD COLUMN address VARCHAR(255) NULL COMMENT '商家投放表单地址' AFTER radius_m,
  ADD COLUMN audit_status TINYINT NULL COMMENT '商家申请审核态:0待审 1通过 2驳回 3审核中;NULL非申请' AFTER status,
  ADD COLUMN application_type TINYINT NULL COMMENT '商家申请类型:1据点投放 2节点认领' AFTER audit_status,
  ADD COLUMN applicant_merchant_id BIGINT NULL COMMENT '待审申请人:mms_merchant.id;不得当作生效owner' AFTER application_type,
  ADD COLUMN audit_reason VARCHAR(200) NULL COMMENT '驳回原因' AFTER applicant_merchant_id,
  ADD KEY idx_roam_apply_review (application_type, audit_status),
  ADD KEY idx_roam_apply_applicant (applicant_merchant_id, audit_status);
