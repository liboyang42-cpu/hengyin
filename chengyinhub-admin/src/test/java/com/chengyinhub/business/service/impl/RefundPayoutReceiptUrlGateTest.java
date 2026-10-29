package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.ManualPayoutDTO;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.business.RefundController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R6 退款人工回执 URL 安全边界:
 * 一名财务在 /business/refund/manual/handle 写入的 payout_receipt_url,会被另一名财务在
 * 人工打款队列里直接点开(el-link href + target=_blank)。旧代码把入参原样透传给 mapper,
 * 任何 scheme/host 都能落库 ⇒ 钓鱼站、javascript:/data: 协议注入、内网探测都成立。
 *
 * 合同(后端权威、fail-closed):
 * - 空白沿用既有语义(回执非必填,不改业务);
 * - 非空必须解析为安全 URI:绝对地址只接受 HTTPS 且 host 命中显式白名单;
 * - 相对地址只接受单斜杠站内路径(本机上传回执落在 /profile/**),拒绝 //、反斜杠、路径穿越;
 * - 拒绝时在 mapper 写入之前抛出 ⇒ 一行都不许落库。
 */
class RefundPayoutReceiptUrlGateTest {

    private static final Long HANDLER = 100L;
    /** 站内上传回执的真实形态:CommonController.upload 返回的 fileName = RESOURCE_PREFIX + 相对路径。 */
    private static final String SITE_RECEIPT = "/profile/upload/2026/07/25/refund-receipt.png";

    private RefundApplicationMapper refundApplicationMapper;
    private RefundServiceImpl svc;

    @BeforeEach
    void setUp() {
        refundApplicationMapper = mock(RefundApplicationMapper.class);
        svc = new RefundServiceImpl();
        ReflectionTestUtils.setField(svc, "coopSettlementClaimService",
                mock(com.chengyinhub.business.service.ICoopSettlementClaimService.class));
        ReflectionTestUtils.setField(svc, "refundApplicationMapper", refundApplicationMapper);
        ReflectionTestUtils.setField(svc, "notifyService", mock(INotifyService.class));
        ReflectionTestUtils.setField(svc, "alarmService", mock(AlarmService.class));
        ReflectionTestUtils.setField(svc, "payoutReceiptAllowedHosts", "www.chengyinhub.com");
    }

    private int handle(String receiptUrl) {
        return svc.handleManualPayout(7L, "offline_transfer", receiptUrl, "已线下退", HANDLER, "lease");
    }

    private void assertRejectedWithoutAnyWrite(String receiptUrl) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> handle(receiptUrl),
                "危险回执链接必须被拒: " + receiptUrl);
        assertNotEquals(null, e.getMessage(), "拒绝必须给出可读原因,否则财务只看到 500");
        verify(refundApplicationMapper, never()).completeManualPayout(
                any(), any(), any(), any(), any(), any(), any());
    }

    // ===== 允许:既有业务语义不能被安全闸误伤 =====

    @Test
    void blankReceiptKeepsExistingSemantics() {
        when(refundApplicationMapper.completeManualPayout(eq(7L), eq("offline_transfer"), any(),
                eq(HANDLER), any(), any(), eq("lease"))).thenReturn(1);

        assertEquals(1, handle(null), "回执非必填:null 不该被新闸拦掉");
        assertEquals(1, handle(""), "回执非必填:空串不该被新闸拦掉");
        verify(refundApplicationMapper).completeManualPayout(eq(7L), any(), eq(null),
                any(), any(), any(), any());
        verify(refundApplicationMapper).completeManualPayout(eq(7L), any(), eq(""),
                any(), any(), any(), any());
    }

    @Test
    void siteRelativeReceiptIsAccepted() {
        when(refundApplicationMapper.completeManualPayout(eq(7L), any(), eq(SITE_RECEIPT),
                any(), any(), any(), any())).thenReturn(1);
        assertEquals(1, handle(SITE_RECEIPT));
    }

    @Test
    void allowlistedHttpsReceiptIsAccepted() {
        String url = "https://www.chengyinhub.com/profile/upload/refund-receipt.png";
        when(refundApplicationMapper.completeManualPayout(eq(7L), any(), eq(url),
                any(), any(), any(), any())).thenReturn(1);
        assertEquals(1, handle(url));
    }

    @Test
    void configuredHostExtendsAllowlist() {
        ReflectionTestUtils.setField(svc, "payoutReceiptAllowedHosts",
                " receipts.example.com , www.chengyinhub.com ");
        String url = "https://receipts.example.com/r/1.png";
        when(refundApplicationMapper.completeManualPayout(eq(7L), any(), eq(url),
                any(), any(), any(), any())).thenReturn(1);
        assertEquals(1, handle(url), "白名单必须能由 chengyin.refund.payout-receipt.allowed-hosts 扩展");
    }

    @Test
    void receiptIsTrimmedBeforePersistSoIdempotentReadBackStillMatches() {
        when(refundApplicationMapper.completeManualPayout(eq(7L), any(), eq(SITE_RECEIPT),
                any(), any(), any(), any())).thenReturn(1);
        assertEquals(1, handle("  " + SITE_RECEIPT + "  "));

        // 重放:mapper CAS 已用掉 token 返回 0,读回来的落库值必须与本次归一化结果一致才算幂等成功
        RefundApplication persisted = new RefundApplication();
        persisted.setId(7L);
        persisted.setPayoutStatus(RefundApplication.PAYOUT_MANUAL_HANDLED);
        persisted.setPayoutHandlerId(HANDLER);
        persisted.setPayoutLeaseToken("lease");
        persisted.setPayoutMethod("offline_transfer");
        persisted.setPayoutReceiptUrl(SITE_RECEIPT);
        when(refundApplicationMapper.completeManualPayout(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        when(refundApplicationMapper.selectRefundApplicationById(7L)).thenReturn(persisted);

        assertEquals(1, handle("  " + SITE_RECEIPT + "  "));
    }

    // ===== 拒绝:一行都不许落库 =====

    @Test
    void protocolInjectionSchemesAreRejected() {
        assertRejectedWithoutAnyWrite("javascript:alert(document.cookie)");
        assertRejectedWithoutAnyWrite("JaVaScRiPt:alert(1)");
        assertRejectedWithoutAnyWrite("data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==");
        assertRejectedWithoutAnyWrite("file:///etc/passwd");
        assertRejectedWithoutAnyWrite("ftp://evil.example.com/receipt.png");
        assertRejectedWithoutAnyWrite("vbscript:msgbox(1)");
    }

    @Test
    void plainHttpIsRejectedEvenOnAllowlistedHost() {
        assertRejectedWithoutAnyWrite("http://www.chengyinhub.com/profile/upload/r.png");
    }

    @Test
    void unlistedHostIsRejectedIncludingSuffixLookalikes() {
        assertRejectedWithoutAnyWrite("https://evil.example.com/receipt.png");
        assertRejectedWithoutAnyWrite("https://www.chengyinhub.com.evil.example.com/receipt.png");
        assertRejectedWithoutAnyWrite("https://evil.example.com/www.chengyinhub.com/receipt.png");
    }

    @Test
    void schemeRelativeAndUserinfoAndBackslashAreRejected() {
        assertRejectedWithoutAnyWrite("//evil.example.com/receipt.png");
        assertRejectedWithoutAnyWrite("https://www.chengyinhub.com@evil.example.com/r.png");
        assertRejectedWithoutAnyWrite("https://user:pass@www.chengyinhub.com/r.png");
        assertRejectedWithoutAnyWrite("https://www.chengyinhub.com\\@evil.example.com/r.png");
        assertRejectedWithoutAnyWrite("\\\\evil.example.com\\share\\r.png");
        assertRejectedWithoutAnyWrite("/\\evil.example.com/r.png");
    }

    @Test
    void explicitPortIsRejectedEvenWhenItIsTheStandardHttpsPort() {
        // 旧合同的回归断言:显式端口一律拒,含语义上等价标准端口的 :443 —— 只留一种规范写法,
        // 避免同一目标出现"带端口"与"不带端口"两种落库形态导致幂等读回比对失败。
        assertRejectedWithoutAnyWrite("https://www.chengyinhub.com:443/r.png");
        assertRejectedWithoutAnyWrite("https://www.chengyinhub.com:8443/r.png");
    }

    @Test
    void controlCharactersAndTraversalAndGarbageAreRejected() {
        assertRejectedWithoutAnyWrite("java\tscript:alert(1)");
        // 首尾空白按既有惯例 trim 掉(trim 后仍是合法链接),但串内控制字符一律拒 ——
        // 换行/NUL 在浏览器与日志里都会被吃掉或截断,足以把真实目标藏起来
        assertRejectedWithoutAnyWrite("https://www.chengyinhub.com/r\n.png");
        assertRejectedWithoutAnyWrite("https://www.chengyinhub.com/r" + (char) 0x00 + ".png");
        assertRejectedWithoutAnyWrite("https://www.chengyinhub.com/r ceipt.png");
        assertRejectedWithoutAnyWrite("/profile/../../../etc/passwd");
        assertRejectedWithoutAnyWrite("/profile/%2e%2e/%2e%2e/etc/passwd");
        assertRejectedWithoutAnyWrite("receipt.png");
        assertRejectedWithoutAnyWrite("www.chengyinhub.com/r.png");
        assertRejectedWithoutAnyWrite("https://");
    }

    @Test
    void oversizedReceiptIsRejectedInsteadOfSilentlyTruncatedByColumn() {
        StringBuilder sb = new StringBuilder("https://www.chengyinhub.com/");
        while (sb.length() <= 512) {
            sb.append('a');
        }
        assertRejectedWithoutAnyWrite(sb.toString());
    }

    // ===== 入口层:真实 HTTP 入口必须给财务可读错误,而不是 500,且状态机不动 =====

    @Test
    void controllerRejectsDangerousReceiptWithReadableErrorAndNoWrite() {
        RefundController controller = spy(new RefundController());
        ReflectionTestUtils.setField(controller, "refundService", svc);
        doReturn(HANDLER).when(controller).getUserId();

        ManualPayoutDTO dto = new ManualPayoutDTO();
        dto.setRefundId(7L);
        dto.setMethod("offline_transfer");
        dto.setReceiptUrl("javascript:alert(1)");
        dto.setLeaseToken("lease");

        AjaxResult result = controller.manualHandle(dto);

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG), "非法回执必须在入口就被拒");
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).length() > 0);
        verify(refundApplicationMapper, never()).completeManualPayout(
                any(), any(), any(), any(), any(), any(), any());
    }

    /** 两名财务链路不回归:合法回执登记后,复核人(≠处理人)仍能闭环,双人复核闸原样保留。 */
    @Test
    void twoFinanceStaffPathStillClosesAfterValidReceipt() {
        when(refundApplicationMapper.completeManualPayout(eq(7L), any(), eq(SITE_RECEIPT),
                any(), any(), any(), any())).thenReturn(1);
        assertEquals(1, handle(SITE_RECEIPT));

        RefundApplication handled = new RefundApplication();
        handled.setId(7L);
        handled.setMemberId(9L);
        handled.setPayoutHandlerId(HANDLER);
        handled.setPayoutStatus(RefundApplication.PAYOUT_MANUAL_HANDLED);
        when(refundApplicationMapper.selectRefundApplicationById(7L)).thenReturn(handled);
        when(refundApplicationMapper.markPayoutVerified(eq(7L), eq(200L), any())).thenReturn(1);

        assertThrows(IllegalStateException.class, () -> svc.verifyManualPayout(7L, HANDLER),
                "双人复核:复核人=处理人仍必须被拒");
        assertEquals(1, svc.verifyManualPayout(7L, 200L));
    }
}
