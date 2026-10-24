package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.business.service.impl.RefundPayoutServiceImpl;
import com.chengyinhub.business.task.RefundReconcileTask;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 退款出金必须先原子领取派发权，处理中只查单不重派。 */
class RefundPayoutAtomicityContractTest {

    @Test
    void payoutWithoutCasOwnershipNeverCallsWechat() {
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        IWechatPayService wechat = mock(IWechatPayService.class);
        RefundApplication refund = refund(7L, RefundApplication.PAYOUT_DISPATCH_PENDING);
        when(mapper.selectRefundApplicationById(7L)).thenReturn(refund);
        when(mapper.claimPayoutDispatch(eq(7L), anyLong(), anyString())).thenReturn(0);
        when(mapper.selectRegistrationHistoryForUpdate(7L)).thenReturn(java.util.List.of(refund));
        RefundPayoutServiceImpl service = new RefundPayoutServiceImpl();
        ReflectionTestUtils.setField(service, "refundApplicationMapper", mapper);
        ReflectionTestUtils.setField(service, "payoutTransactions", com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.unitWriteTransactions());
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", mock(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(service, "refundService", mock(IRefundService.class));
        ReflectionTestUtils.setField(service, "wechatPayService", wechat);

        service.payoutAfterReview(7L);
        verify(mapper).claimPayoutDispatch(eq(7L), anyLong(), anyString());
        verify(wechat, never()).submitRefund(any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class),anyString(),anyString(),any(),any(),anyString());

        verify(wechat, never()).submitRefund(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void reconciliationRedispatchesPendingAndDispatchingButOnlyPollsProcessing() {
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        IWechatPayService wechat = mock(IWechatPayService.class);
        IRefundPayoutService payout = mock(IRefundPayoutService.class);
        RefundApplication pending = refund(1L, RefundApplication.PAYOUT_DISPATCH_PENDING);
        RefundApplication dispatching = refund(2L, RefundApplication.PAYOUT_DISPATCHING);
        RefundApplication processing = refund(3L, RefundApplication.PAYOUT_PROCESSING);
        processing.setRefundNo("RF3");
        // A包对账改为固定本轮上界 + 稳定游标分页(selectStalePayoutProcessingAfter)；同三行落在首页。
        when(mapper.selectStalePayoutScanUpperBound(30)).thenReturn(3L);
        when(mapper.selectStalePayoutProcessingAfter(30, 0L, 3L, 200))
                .thenReturn(Arrays.asList(pending, dispatching, processing));
        RefundReconcileTask task = new RefundReconcileTask();
        ReflectionTestUtils.setField(task, "coopInviteService", mock(ICoopInviteService.class));
        ReflectionTestUtils.setField(task, "refundApplicationMapper", mapper);
        ReflectionTestUtils.setField(task, "wechatPayService", wechat);
        ReflectionTestUtils.setField(task, "refundPayoutService", payout);
        ReflectionTestUtils.setField(task, "alarmService", mock(AlarmService.class));

        task.reconcile();

        verify(payout).payoutAfterReview(1L);
        verify(payout).payoutAfterReview(2L);
        verify(wechat).refreshPayoutFromWx("RF3");
        verify(wechat, never()).submitRefund(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void manualPayoutRequiresOwnerLeaseFenceAndDefinitiveWechatTerminal() throws Exception {
        String mapper = source("chengyinhub-system/src/main/resources/mapper/business/RefundApplicationMapper.xml");
        String controller = source("chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/RefundController.java");

        assertTrue(mapper.contains("<update id=\"completeManualPayout\">"));
        // 领取:owner+token+到期时间三列都要写,且只允许"空租约"或"自己的过期租约"。
        String claimSql = mapperUpdate(mapper, "claimManualPayout");
        assertTrue(claimSql.contains("payout_lease_owner = #{handlerId}"));
        assertTrue(claimSql.contains("payout_lease_token = #{leaseToken}"));
        // 2026-08-01:到期时刻改由 **数据库** 生成(只传时长)。原先写的是 JVM 算好的
        // #{leaseExpiresAt},而这一列会在 WHERE 里跟 now() 比 —— JVM 与 MySQL 时区一旦
        // 不一致,15 分钟租约窗口立即失效(接管别人在途的单 ⇒ 重复线下打款)。
        // 这里比原断言更严:不只要求"写了这一列",还要求它的值来自 DB 侧表达式。
        assertTrue(claimSql.contains("payout_lease_expires_at = NOW(3) + INTERVAL #{leaseMinutes} MINUTE"));
        assertTrue(claimSql.contains("payout_lease_token is null"));
        assertTrue(claimSql.contains("payout_lease_expires_at &lt; now()"));
        // 释放:owner+token 双 fence 保留,但**不许**再要求租约未过期 ——
        // 那正是"过期后既领不了也放不掉"的死锁,M1 修的就是它。
        String releaseSql = mapperUpdate(mapper, "releaseManualPayoutClaim");
        assertTrue(releaseSql.contains("payout_lease_owner = #{handlerId}"));
        assertTrue(releaseSql.contains("payout_lease_token = #{leaseToken}"));
        assertFalse(releaseSql.contains("payout_lease_expires_at &gt;= now()"),
                "释放不动钱,要求租约未过期会让过期单永久卡死");
        // 接管:只对他人的过期租约生效。
        String takeoverSql = mapperUpdate(mapper, "takeoverExpiredManualPayout");
        assertTrue(takeoverSql.contains("payout_lease_owner &lt;&gt; #{handlerId}"));
        assertTrue(takeoverSql.contains("payout_lease_expires_at &lt; now()"));

        assertTrue(controller.contains("isDefinitiveManualPayoutStatus"));
        int claim = controller.indexOf("public AjaxResult manualClaim");
        int claimEnd = controller.indexOf("public AjaxResult manualRelease", claim);
        String claimBody = controller.substring(claim, claimEnd);
        assertTrue(claimBody.indexOf("refreshPayoutFromWx") < claimBody.indexOf("claimManualPayout"));
        // 接管同样要先问微信终态再签发 token,否则等于绕过"钱可能已经退了"的那道闸。
        int takeover = controller.indexOf("public AjaxResult manualTakeover");
        String takeoverBody = controller.substring(takeover,
                controller.indexOf("private boolean isDefinitiveManualPayoutStatus", takeover));
        assertTrue(takeoverBody.indexOf("refreshPayoutFromWx")
                < takeoverBody.indexOf("takeoverExpiredManualPayout"));
        int handle = controller.indexOf("public AjaxResult manualHandle");
        String handleBody = controller.substring(handle, claim);
        assertFalse(handleBody.contains("refreshPayoutFromWx"));
        assertFalse(handleBody.contains("releaseManualPayoutClaim"));
    }

    private String mapperUpdate(String mapper, String id) {
        int start = mapper.indexOf("<update id=\"" + id + "\">");
        assertTrue(start >= 0, "mapper 缺少 " + id);
        return mapper.substring(start, mapper.indexOf("</update>", start));
    }

    private String source(String relative) throws Exception {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if (root.getFileName() != null && "chengyinhub-admin".equals(root.getFileName().toString())) {
            root = root.getParent();
        }
        return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
    }

    private RefundApplication refund(Long id, int payoutStatus) {
        RefundApplication refund = new RefundApplication();
        refund.setId(id);
        refund.setStatus(1);
        refund.setSourceType("registration");
        refund.setSourceId(id);
        refund.setRefundAmount(BigDecimal.ONE);
        refund.setPayoutStatus(payoutStatus);
        return refund;
    }
}
