package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 答案验证与优惠券核销必须由 Service 通过 CAS 取得唯一处理权。 */
class RegistrationEquityAtomicityContractTest {

    private static String source(String relative) throws Exception {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if (root.getFileName() != null && "chengyinhub-admin".equals(root.getFileName().toString())) {
            root = root.getParent();
        }
        return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
    }

    /**
     * 第二轮拍板第 6 条:商家侧答题验票发券链路整条拆除(端点只回拒绝),
     * 模板券唯一发放路径 = /api/play/checkin → advanceProgress → grantTemplateCoupon(玩家)。
     * 拆一半(端点停了、服务里还留着发给商家 owner 的 grantCouponById)会被人顺手接回去。
     */
    @Test
    void merchantAnswerVerificationCouponPathIsRemoved() throws Exception {
        String controller = source("chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiRegistrationController.java");
        String service = source("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsRegistrationServiceImpl.java");
        String xml = source("chengyinhub-system/src/main/resources/mapper/business/CmsRegistrationScanMapper.xml");

        int endpoint = controller.indexOf("public AjaxResult VerifyAnswer");
        String endpointBody = controller.substring(endpoint, controller.indexOf("@Operation", endpoint + 1));
        assertFalse(endpointBody.contains("registrationService."), "停用端点不得再调报名服务");
        assertFalse(endpointBody.contains("grantCoupon"), "停用端点不得发券");
        assertFalse(service.contains("verifyRegistrationAnswer"), "发给商家 owner 的答题发券实现必须删掉");
        assertFalse(xml.contains("claimUnverifiedScan"));
        assertFalse(xml.contains("selectAnswerReward"));
    }

    @Test
    void couponRedemptionClaimsHistoryInsideTransaction() throws Exception {
        String controller = source("chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiCouponController.java");
        String service = source("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/SmsCouponHistoryServiceImpl.java");
        String mapper = source("chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/SmsCouponHistoryMapper.java");
        String xml = source("chengyinhub-system/src/main/resources/mapper/business/SmsCouponHistoryMapper.xml");

        int endpoint = controller.indexOf("public AjaxResult couponVerification");
        String endpointBody = controller.substring(endpoint);
        int method = service.indexOf("redeemCoupon(");
        int claim = service.indexOf("claimUnusedCoupon", method);
        int settlement = service.indexOf("recordCouponVerification", method);
        int audit = service.indexOf("insertBusinessAuditEvent", method);

        assertTrue(endpointBody.contains("smsCouponHistoryService.redeemCoupon"));
        assertFalse(endpointBody.contains("updateSmsCouponHistory"), "Controller 不得编排核销资金写入");
        assertTrue(service.substring(Math.max(0, method - 180), method)
                .contains("@Transactional(rollbackFor = Exception.class)"));
        assertTrue(mapper.contains("int claimUnusedCoupon("));
        assertTrue(xml.contains("<update id=\"claimUnusedCoupon\">"));
        assertTrue(xml.contains("use_status = 0"));
        assertTrue(claim > method && settlement > claim, "结算必须发生在取得核销唯一处理权之后");
        assertTrue(audit > settlement, "实际操作者审计必须与核销结算处于同一事务并在成功后写入");
        assertTrue(service.substring(settlement, audit).contains("incrementUseCount"));
        assertTrue(service.substring(audit, Math.min(service.length(), audit + 180))
                .contains("!= 1"), "审计零行必须抛错回滚核销、结算与计数");
    }

    @Test
    void couponZeroRowWritesMustReadBackOrRollback() throws Exception {
        String service = source("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/SmsCouponHistoryServiceImpl.java");

        assertTrue(service.contains("expireUnusedCoupon(historyId, now) == 0"));
        assertTrue(service.contains("selectSmsCouponHistoryByIdForUpdate(historyId)"));
        assertTrue(service.contains("tryIncrementIssuableReceiveCount(coupon.getId()) == 0"));
        assertTrue(service.contains("insertSmsCouponHistory(h) != 1"));
        int grantById = service.indexOf("public boolean grantCouponById");
        assertTrue(service.substring(Math.max(0, grantById - 180), grantById)
                .contains("@Transactional(rollbackFor = Exception.class)"));
    }

    @Test
    void legacyRegistrationCouponScanUsesTransactionalRedemptionBoundary() throws Exception {
        String controller = source("chengyinhub-admin/src/main/java/com/chengyinhub/web/service/registration/RegistrationRedemptionApplication.java");
        int start = controller.indexOf("if (\"coupon\".equals(type))");
        int end = controller.indexOf("} else if (type.equals(\"activity\"))", start);
        String couponBranch = controller.substring(start, end);

        assertTrue(couponBranch.contains("请使用专用优惠券核销入口"));
        assertFalse(couponBranch.contains("redeemCoupon"));
        assertFalse(couponBranch.contains("updateSmsCouponHistory"));
        assertFalse(couponBranch.contains("recordCouponVerification"));
    }
}
