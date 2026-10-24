package com.chengyinhub.business.service.support;

import com.chengyinhub.business.service.support.MerchantDeskCodePolicy.Resolution;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Doc2 F4 核销台归类:路由表必须与小程序 utils/verification-scan.js(pages/merchant/index 扫码契约)逐项一致,
 * 节点任务码永不进入核销端点,手输旧码必须由商家显式选 type。
 */
class MerchantDeskCodePolicyTest {

    private static final String SIG = "abcdefghijklmnopqrstuvwxyz012345ABCDEFGHIJK";

    @Test
    void dynamicTicket_routesToScanDynamicCode_likeMiniProgram() {
        Resolution r = MerchantDeskCodePolicy.classify("v1.88.topic.1790000000000.42." + SIG);
        assertTrue(r.accepted());
        assertEquals(MerchantDeskCodePolicy.KIND_DYNAMIC_TICKET, r.kind());
        assertEquals("/api/registration/scan_dynamic_code", r.submitEndpoint());
        assertEquals("code", r.fields().get(0).name());
        assertEquals("hidden", r.fields().get(0).widget());
    }

    @Test
    void groupCode_routesToGroupRedeem() {
        Resolution r = MerchantDeskCodePolicy.classify("v1.7.group_v2_5.1790000000000.1." + SIG);
        assertEquals(MerchantDeskCodePolicy.KIND_GROUP, r.kind());
        assertEquals("/api/verify/groupcode/redeem", r.submitEndpoint());
    }

    @Test
    void cityNodeVoucher_isWrongDeskWithRedirect_notSubmitted() {
        Resolution r = MerchantDeskCodePolicy.classify("v1.3.citynode_99.1790000000000.1." + SIG);
        assertFalse(r.accepted());
        assertEquals(MerchantDeskCodePolicy.WRONG_DESK, r.errorCode());
        assertEquals("/pages/merchant/citynode/index", r.redirectPath());
        assertNull(r.submitEndpoint());
    }

    @Test
    void couponPresentCode_routesToCouponVerificationWithMerchantScope() {
        Resolution r = MerchantDeskCodePolicy.classify("cq1.123.1790000000." + SIG);
        assertEquals(MerchantDeskCodePolicy.KIND_COUPON, r.kind());
        assertEquals("/api/coupon/verification", r.submitEndpoint());
        assertEquals("MERCHANT", r.fields().get(1).value());
    }

    @Test
    void legacyJsonTicket_keepsClientTypeVerbatim() {
        Resolution r = MerchantDeskCodePolicy.classify("{\"type\":\"activity\",\"code\":\"VER1790000000000ABC123\"}");
        assertEquals(MerchantDeskCodePolicy.KIND_LEGACY_TICKET, r.kind());
        assertEquals("/api/registration/scan_qr_code", r.submitEndpoint());
        assertEquals("activity", r.fields().get(1).value());
    }

    @Test
    void manualLegacyCode_requiresMerchantToPickType_serverNeverFillsIt() {
        Resolution r = MerchantDeskCodePolicy.classify("VER1790000000000ABC123");
        assertTrue(r.accepted());
        MerchantDeskCodePolicy.Field type = r.fields().get(1);
        assertEquals("type", type.name());
        assertEquals("select", type.widget());
        assertTrue(type.required());
        assertNull(type.value());
        assertEquals(2, type.options().size());
    }

    @Test
    void nodePosterCode_isRejectedAsNodeTaskCode() {
        Resolution r = MerchantDeskCodePolicy.classify("poi1.55.abcdefghijklmnop");
        assertFalse(r.accepted());
        assertEquals(MerchantDeskCodePolicy.KIND_NODE_TASK, r.kind());
        assertEquals(MerchantDeskCodePolicy.NODE_TASK_CODE, r.errorCode());
        assertNull(r.submitEndpoint());
        assertTrue(r.fields().isEmpty());
    }

    @Test
    void unknownDynamicTypeAndGarbage_areRejected() {
        assertEquals(MerchantDeskCodePolicy.UNSUPPORTED,
                MerchantDeskCodePolicy.classify("v1.1.weird.1.1." + SIG).errorCode());
        assertEquals(MerchantDeskCodePolicy.INVALID, MerchantDeskCodePolicy.classify("hello").errorCode());
        assertEquals(MerchantDeskCodePolicy.INVALID, MerchantDeskCodePolicy.classify("  ").errorCode());
        assertEquals(MerchantDeskCodePolicy.INVALID, MerchantDeskCodePolicy.classify("{\"type\":\"x\"}").errorCode());
    }

    @Test
    void formIsAlwaysOnline() {
        Map<String, Object> view = MerchantDeskCodePolicy.classify("VER1790000000000ABC123").toView();
        @SuppressWarnings("unchecked")
        Map<String, Object> form = (Map<String, Object>) view.get("form");
        assertEquals(Boolean.FALSE, form.get("offline"));
        assertEquals("POST", form.get("method"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ocr_extractsVouchers_excludesNodePosterCodes_andRepairsDigitConfusion() {
        String text = "店内张贴码 poi1.55.abcdefghijklmnop\n玩家票 VER17900000O0000ABC123 以及 cq1.123.1790000000." + SIG;
        Map<String, Object> result = MerchantDeskCodePolicy.ocrCandidates(text);
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) result.get("candidates");
        assertEquals(1, result.get("excludedNodeTaskCodes"));
        assertEquals(2, candidates.size());
        assertEquals("COUPON", candidates.get(0).get("kind"));
        assertEquals("VER1790000000000ABC123", candidates.get(1).get("code"));
        for (Map<String, Object> c : candidates) assertFalse(String.valueOf(c.get("code")).startsWith("poi1."));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ocr_capsCandidatesAndText() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 9; i++) text.append(" VER179000000000").append(i).append("ABC12").append(i);
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) MerchantDeskCodePolicy
                .ocrCandidates(text.toString()).get("candidates");
        assertEquals(MerchantDeskCodePolicy.MAX_OCR_CANDIDATES, candidates.size());
        assertEquals("VER1****C120", candidates.get(0).get("masked"));
        assertTrue(((List<?>) MerchantDeskCodePolicy.ocrCandidates(null).get("candidates")).isEmpty());
    }

    @Test
    void modeDefaultsToScan() {
        assertEquals("SCAN", MerchantDeskCodePolicy.normalizeMode(null));
        assertEquals("OCR", MerchantDeskCodePolicy.normalizeMode("ocr"));
        assertEquals("SCAN", MerchantDeskCodePolicy.normalizeMode("offline"));
    }
}
