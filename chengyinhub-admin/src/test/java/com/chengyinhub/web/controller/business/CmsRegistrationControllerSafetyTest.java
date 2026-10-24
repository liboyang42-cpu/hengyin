package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.domain.dto.RedemptionReverseRequest;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A3-1(P0):报名通用写端点封口。
 *
 * <p>报名承载支付、报名生命周期及核销事实。原先通用 PUT 可裸改这些事实、
 * 通用 DELETE 可删掉整条支付记录。本测试钉住:remove 一律 FORBIDDEN;edit 把交易事实字段
 * 剥成 null(对所有人含超管生效),合法业务字段照常提交。
 */
class CmsRegistrationControllerSafetyTest {

    private ICmsRegistrationService registrationService;
    private IPerkLedgerService perkLedgerService;
    private CmsRegistrationController controller;

    @BeforeEach
    void setUp() {
        registrationService = mock(ICmsRegistrationService.class);
        perkLedgerService = mock(IPerkLedgerService.class);
        controller = new CmsRegistrationController();
        ReflectionTestUtils.setField(controller, "cmsRegistrationService", registrationService);
        ReflectionTestUtils.setField(controller, "perkLedgerService", perkLedgerService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 装一个带指定权限集的登录态,供 edit 里的 canViewPii() 读取。 */
    private void loginWith(String... permissions) {
        loginAs(1L, permissions);
    }

    /** 指定平台账号 id 的登录态 —— 撤销入口要把操作者写进审计,必须有真 id。 */
    private void loginAs(Long userId, String... permissions) {
        SysUser user = new SysUser();
        user.setUserId(userId);
        // ⚠️ 必须走 4 参构造:LoginUser.getUserId() 读的是自己的 userId 字段,
        // 2 参构造不填它(SecurityUtils.getUserId() 会拿到 null,撤销审计当场缺操作者)。
        LoginUser loginUser = new LoginUser(userId, null, user, new HashSet<>(Arrays.asList(permissions)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }

    @Test
    void genericAddRejectsForgedFinancialFactsWithoutCallingWriter() {
        loginAs(1L, "business:registration:add");
        CmsRegistration request = new CmsRegistration();
        request.setMemberId(300L);
        request.setTicketId(200L);
        request.setPointUsed(1000L);
        request.setPointsReturned(0);
        request.setOrderNum(100L);

        AjaxResult result = controller.add(request);

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        assertEquals("REGISTRATION_GENERIC_CREATE_DISABLED", result.get("errorCode"));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("正式下单"));
        verifyNoInteractions(registrationService, perkLedgerService);
    }

    @Test
    void removeRejectsDeletingPaymentRecord() {
        AjaxResult result = controller.remove(new Long[] { 1L });

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("报名含支付事实,不支持删除"),
                "应被报名禁删封口拦下,实际文案:" + result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(registrationService);
    }

    @Test
    void editStripsPaymentAndVerificationFactsFromGenericPut() {
        // 授「看全量 PII」→ canViewPii()=true,PII 剥离被跳过,从而隔离出「支付/核销事实剥离」这一条本体。
        loginWith("business:registration:pii");

        CmsRegistration submitted = new CmsRegistration();
        submitted.setId(5L);
        submitted.setTicketName("普通业务字段");           // 合法业务字段:必须照常提交
        submitted.setTotalAmount(new BigDecimal("999"));
        submitted.setPayableAmount(new BigDecimal("999"));
        submitted.setPaymentStatus(1);
        submitted.setRegistrationStatus(3);
        submitted.setPaymentType(2);
        submitted.setPaymentTime(new Date());
        submitted.setTransactionId("HACK-TXN");
        submitted.setPointPaymentAmount(new BigDecimal("50"));
        submitted.setWechatPaymentAmount(new BigDecimal("949"));
        submitted.setVerificationCode("VC-HACK");
        submitted.setVerificationStatus(1);
        submitted.setVerificationTime(new Date());
        submitted.setVerifierId(7L);

        controller.edit(submitted);

        // 支付/报名/核销事实字段全部被剥成 null → mapper 动态 <if> 跳过 → 库中真值不动;
        // 业务字段 ticketName 与主键 id 必须保留,否则封口顺手废掉了编辑功能。
        verify(registrationService).updateCmsRegistration(argThat(r ->
                r.getTotalAmount() == null
                        && r.getPayableAmount() == null
                        && r.getPaymentStatus() == null
                        && r.getRegistrationStatus() == null
                        && r.getPaymentType() == null
                        && r.getPaymentTime() == null
                        && r.getTransactionId() == null
                        && r.getPointPaymentAmount() == null
                        && r.getWechatPaymentAmount() == null
                        && r.getVerificationCode() == null
                        && r.getVerificationStatus() == null
                        && r.getVerificationTime() == null
                        && r.getVerifierId() == null
                        && "普通业务字段".equals(r.getTicketName())
                        && Long.valueOf(5L).equals(r.getId())));
    }

    @Test
    void genericEditCannotCancelExpireOrResurrectRegistrationLifecycle() {
        loginWith("business:registration:pii");
        when(registrationService.updateCmsRegistration(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        for (int target : new int[] {1, 2, 3, 4}) {
            CmsRegistration request = new CmsRegistration();
            request.setId(5L);
            request.setRegistrationStatus(target);
            request.setTicketName("保留正常编辑");
            assertEquals(200, controller.edit(request).get(AjaxResult.CODE_TAG));
        }
        org.mockito.ArgumentCaptor<CmsRegistration> writes = org.mockito.ArgumentCaptor.forClass(CmsRegistration.class);
        verify(registrationService, org.mockito.Mockito.times(4)).updateCmsRegistration(writes.capture());
        for (CmsRegistration write : writes.getAllValues()) {
            assertNull(write.getRegistrationStatus(), "生命周期只能经已有支付/取消/过期命令迁移");
            assertEquals(Long.valueOf(5L), write.getId());
            assertEquals("保留正常编辑", write.getTicketName());
        }
        verifyNoInteractions(perkLedgerService);
    }

    @Test
    void genericEditCannotRewriteOriginalPointsInventoryOrBeneficiary() {
        loginWith("business:registration:pii");
        CmsRegistration request = new CmsRegistration();
        request.setId(5L);
        request.setPointUsed(1000L);
        request.setPointsReturned(0);
        request.setOrderNum(100L);
        request.setTicketId(200L);
        request.setMemberId(300L);
        request.setTicketName("ordinary editable label");
        controller.edit(request);
        org.mockito.ArgumentCaptor<CmsRegistration> writes =
                org.mockito.ArgumentCaptor.forClass(CmsRegistration.class);
        verify(registrationService).updateCmsRegistration(writes.capture());
        CmsRegistration write = writes.getValue();
        assertNull(write.getPointUsed(), "generic edits must not invent points to refund");
        assertNull(write.getPointsReturned(), "generic edits must not release a consumed refund identity");
        assertNull(write.getOrderNum(), "inventory release must use the original reservation quantity");
        assertNull(write.getTicketId(), "inventory release must use the original ticket identity");
        assertNull(write.getMemberId(), "generic edits must not change the economic beneficiary");
        assertEquals("ordinary editable label", write.getTicketName());
    }

    @Test
    void genericEditDoesNotCopyOriginalIdentityContextOrAuditFacts() throws Exception {
        loginWith("business:registration:pii");
        CmsRegistration request = new CmsRegistration();
        // Poison every scalar property, including inherited create/update metadata.
        for (java.beans.PropertyDescriptor property : java.beans.Introspector
                .getBeanInfo(CmsRegistration.class).getPropertyDescriptors()) {
            if (property.getWriteMethod() == null) continue;
            Class<?> type = property.getPropertyType();
            Object value = null;
            if (type == String.class) value = "untrusted";
            else if (type == Long.class) value = 99L;
            else if (type == Integer.class) value = 99;
            else if (type == BigDecimal.class) value = new BigDecimal("99.99");
            else if (type == Date.class) value = new Date();
            if (value != null) property.getWriteMethod().invoke(request, value);
        }
        controller.edit(request);
        org.mockito.ArgumentCaptor<CmsRegistration> writes =
                org.mockito.ArgumentCaptor.forClass(CmsRegistration.class);
        verify(registrationService).updateCmsRegistration(writes.capture());
        CmsRegistration write = writes.getValue();
        for (String protectedProperty : new String[]{"registrationNo", "parentOrderNo", "requestId",
                "requestFingerprint", "ownerType", "ownerId", "topicId", "memberId", "ticketId",
                "orderNum", "participateDate", "purchaseKind", "ticketPrice", "originalAmount",
                "discountAmount", "pointUsed", "pointsReturned", "pointDeductAmount", "pointDeductRate",
                "maxPointDeduct", "feePlatformRate", "sourceClubId", "sourceFrozenAt", "sourceChannelCode",
                "refundPolicyCode", "refundPolicyVersion", "refundDeadline", "expiresAt", "payExpireTime",
                "firstSuccessfulScanAt", "qrcodeUrl", "createBy", "createTime", "updateBy", "updateTime"}) {
            assertNull(org.springframework.beans.BeanUtils.getPropertyDescriptor(
                    CmsRegistration.class, protectedProperty).getReadMethod().invoke(write), protectedProperty);
        }
        assertEquals(99L, write.getId());
        assertEquals("untrusted", write.getActivityTitle());
        assertEquals("untrusted", write.getTicketName());
        assertEquals("untrusted", write.getRealName());
        assertEquals("untrusted", write.getPhone());
        assertEquals("untrusted", write.getEmail());
        assertEquals("untrusted", write.getAddress());
        assertEquals(99L, write.getAddressId());
    }

    @Test
    void genericEditRequiresAnOriginalIdBeforeCallingWriter() {
        assertEquals(500, controller.edit(null).get(AjaxResult.CODE_TAG));
        assertEquals(500, controller.edit(new CmsRegistration()).get(AjaxResult.CODE_TAG));
        verifyNoInteractions(registrationService);
    }

    // ------------------------------------------- 错核撤销入口(平台后台唯一入口)

    /**
     * ★ RED:撤销入口必须把 **平台账号 id** 一起送进账本服务 —— 少了它,审计就答不出「谁撤的」。
     * 负控:把 controller 里 perkLedgerService.reverseRedemption(...) 那一行摘掉/改成别的调用
     * ⇒ 本条当场红(Mockito verify 落空)。
     */
    @Test
    void reverseRedeemWiresLedgerWithLoggedInOperatorAndReason() {
        loginAs(77L);
        when(perkLedgerService.reverseRedemption(3L, "店员误扫", 77L, false)).thenReturn(true);

        RedemptionReverseRequest request = new RedemptionReverseRequest();
        request.setRedemptionId(3L);
        request.setReason("店员误扫");
        AjaxResult result = controller.reverseRedemption(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG), "正常撤销应报成功,实际:" + result.get(AjaxResult.MSG_TAG));
        verify(perkLedgerService).reverseRedemption(3L, "店员误扫", 77L, false);
    }

    /** 第二轮拍板第 4 条:弹窗勾了「同时冲回积分和经验」才把 true 送进账本服务;没带字段 = 不冲回。 */
    @Test
    void reverseRedeemPassesClawbackChoiceThrough() {
        loginAs(77L);
        when(perkLedgerService.reverseRedemption(3L, "店员误扫", 77L, true)).thenReturn(true);

        RedemptionReverseRequest request = new RedemptionReverseRequest();
        request.setRedemptionId(3L);
        request.setReason("店员误扫");
        request.setClawbackRewards(true);
        AjaxResult result = controller.reverseRedemption(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(perkLedgerService).reverseRedemption(3L, "店员误扫", 77L, true);
    }

    /** 原因必填(留痕本体);空白原因不得碰账本服务。 */
    @Test
    void reverseRedeemBlankReasonRejectedBeforeTouchingLedger() {
        loginAs(77L);
        RedemptionReverseRequest request = new RedemptionReverseRequest();
        request.setRedemptionId(3L);
        request.setReason("   ");

        AjaxResult result = controller.reverseRedemption(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("撤销原因不能为空", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(perkLedgerService);
    }

    /** 原因列是 VARCHAR(255),超长必须在这里拦下,别等 SQL 抛 truncate 500。 */
    @Test
    void reverseRedeemOverlongReasonRejected() {
        loginAs(77L);
        RedemptionReverseRequest request = new RedemptionReverseRequest();
        request.setRedemptionId(3L);
        request.setReason(String.join("", Collections.nCopies(256, "误")));

        AjaxResult result = controller.reverseRedemption(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(perkLedgerService, never()).reverseRedemption(anyLong(), anyString(), anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    /** 流水 id 缺失/非法 → 拒,且不查库。 */
    @Test
    void reverseRedeemBadIdRejected() {
        loginAs(77L);
        RedemptionReverseRequest request = new RedemptionReverseRequest();
        request.setReason("店员误扫");

        assertEquals(500, controller.reverseRedemption(request).get(AjaxResult.CODE_TAG));
        request.setRedemptionId(0L);
        assertEquals(500, controller.reverseRedemption(request).get(AjaxResult.CODE_TAG));
        verifyNoInteractions(perkLedgerService);
    }

    /**
     * CAS 落空(已被撤销过)必须变成可读错误 —— 幂等,但**不报成功**:
     * 运维需要知道这次点击没有产生新动作。
     */
    @Test
    void reverseRedeemAlreadyReversedReportsConflict() {
        loginAs(77L);
        when(perkLedgerService.reverseRedemption(eq(3L), anyString(), eq(77L), eq(false))).thenReturn(false);

        RedemptionReverseRequest request = new RedemptionReverseRequest();
        request.setRedemptionId(3L);
        request.setReason("重复点击");
        AjaxResult result = controller.reverseRedemption(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("已被撤销"),
                "应提示已撤销,实际:" + result.get(AjaxResult.MSG_TAG));
    }

    /** 撤销入口的读回列表:核销流水(含已撤销)原样透出。 */
    @Test
    void redemptionsListDelegatesToLedger() {
        Redemption revoked = new Redemption();
        revoked.setId(3L);
        revoked.setStatus("REVERSED");
        revoked.setReverseReason("店员误扫");
        when(perkLedgerService.listRedemptions(900L)).thenReturn(Collections.singletonList(revoked));

        AjaxResult result = controller.redemptions(900L);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        List<?> data = (List<?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(1, data.size());
        assertEquals("REVERSED", ((Redemption) data.get(0)).getStatus());
    }
}
