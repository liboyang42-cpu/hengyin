package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.service.impl.CmsRegistrationMerchantServiceImpl;
import com.chengyinhub.business.mapper.CmsRegistrationMerchantMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.*;

/**
 * C5 · 商家取消承接必须查 auditStatus。
 *
 * <p><b>洞</b>:{@code ApiRegistrationMerchantController.cancelRegistration}(C 端)只校验
 * 「是不是本人」,<b>不看 auditStatus</b> ⇒ 已中标商家可以直接把自己的承接记录软删(delFlag=2),
 * 而中标时建的 {@code CmsActivity} + 复制出来的票<b>还挂在那里</b> ⇒ 活动与票成孤儿:
 * 玩家照样能看到、能买,但承接记录已经没了。
 *
 * <p><b>口径不是我自造的</b>:后台驳回路径 {@code CmsRegistrationMerchantController:396-399}
 * (HF-06/A-37)早就有同款拦截,理由一字不差 ——「已中标的报名不能直接驳回,否则其已发布活动+
 * 已复制票成孤儿。撤销中标须走专用流程」。C 端取消漏了这一道,本类把两边拉平。
 *
 * <p>⚠️ <b>「撤销中标流程」全仓无实现</b>(只存在于那句报错文案里,施工图 C5 已记)。所以本闸只能
 * <b>拒绝 + 引导</b>,不能代替它 —— 拒了之后商家怎么退出,得等 P0-29 那条真做出来。
 * 这是诚实的边界:堵住资损口子,不假装解决了退出问题。
 *
 * <p>⚠️ 负控:删掉 assertNotWon 调用 ⇒ {@code wonRegistration_cannotCancel} 红。
 */
class MerchantCancelAuditGateTest {

    private static final Long ME = 100L;
    private static final Long REG = 500L;

    private CmsRegistrationMerchantMapper mapper;
    private CmsRegistrationMerchantServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(CmsRegistrationMerchantMapper.class);
        service = new CmsRegistrationMerchantServiceImpl();
        ReflectionTestUtils.setField(service, "cmsRegistrationMerchantMapper", mapper);
        when(mapper.cancelUnwonByMember(any(), any(), any(), any())).thenReturn(1);
    }

    private CmsRegistrationMerchant reg(Long auditStatus) {
        CmsRegistrationMerchant r = new CmsRegistrationMerchant();
        r.setId(REG);
        r.setMemberId(ME);
        r.setAuditStatus(auditStatus);
        r.setDelFlag(0);
        return r;
    }

    private boolean cancel(Long auditStatus, Long operator) {
        when(mapper.selectCmsRegistrationMerchantById(REG)).thenReturn(reg(auditStatus));
        return service.cancelByMerchant(REG, operator, "tester");
    }

    /** ★已中标(audit_status=1)⇒ 拒。洞本身:放它过去会留下孤儿活动+孤儿票。 */
    @Test
    void wonRegistration_cannotCancel() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> cancel(1L, ME));
        assertTrue(e.getMessage().contains("已中标"), "实际:" + e.getMessage());
        assertTrue(e.getMessage().contains("撤销中标"),
                "必须引导去撤销中标流程,而不是只说不行 —— 否则商家不知道下一步该干嘛。实际:" + e.getMessage());
    }

    /** 待审核(audit_status=0)⇒ 放行。这是正常退出路径,拦了就是打挂功能。 */
    @Test
    void pendingRegistration_canCancel() {
        assertTrue(cancel(0L, ME));
    }

    /** 已驳回(audit_status=2)⇒ 放行(没中标,没有孤儿风险)。 */
    @Test
    void rejectedRegistration_canCancel() {
        assertTrue(cancel(2L, ME));
    }

    /** auditStatus 为 null(存量)⇒ 放行。没中标过,与 :397 的既有口径一致(它也只拦 ==1L)。 */
    @Test
    void nullAuditStatus_canCancel() {
        assertTrue(cancel(null, ME));
    }

    /** 他人的报名 ⇒ 拒。原有的归属校验不能被我改丢。 */
    @Test
    void othersRegistration_cannotCancel() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> cancel(0L, 999L));
        assertTrue(e.getMessage().contains("无权"), "实际:" + e.getMessage());
        verify(mapper, never()).cancelUnwonByMember(any(), any(), any(), any());
    }

    @Test
    void cancellationUsesCurrentOwnerAndAuditCasWithoutRewritingReadSnapshot() {
        CmsRegistrationMerchant snapshot = reg(0L);
        when(mapper.selectCmsRegistrationMerchantById(REG)).thenReturn(snapshot);
        when(mapper.cancelUnwonByMember(eq(REG), eq(ME), eq("tester"), any(Date.class))).thenReturn(1);

        assertTrue(service.cancelByMerchant(REG, ME, "tester"));

        verify(mapper).cancelUnwonByMember(eq(REG), eq(ME), eq("tester"), any(Date.class));
        verify(mapper, never()).updateCmsRegistrationMerchant(any());
        assertEquals(0, snapshot.getDelFlag());
        assertEquals(0L, snapshot.getAuditStatus());
    }

    @Test
    void staleCancellationLosingCurrentStateCasReportsFailureWithoutGenericWrite() {
        when(mapper.selectCmsRegistrationMerchantById(REG)).thenReturn(reg(0L));
        when(mapper.cancelUnwonByMember(eq(REG), eq(ME), any(), any())).thenReturn(0);

        assertFalse(service.cancelByMerchant(REG, ME, "tester"));

        verify(mapper, never()).updateCmsRegistrationMerchant(any());
    }
}
