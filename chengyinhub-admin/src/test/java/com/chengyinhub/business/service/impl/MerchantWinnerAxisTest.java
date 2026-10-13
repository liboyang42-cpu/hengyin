package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.dto.MerchantSharingRateDTO;
import com.chengyinhub.business.mapper.CmsRegistrationMerchantMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G6:「中标」的真源是 audit_status,不是 status。
 *
 * 双轴语义(migration_merchant_pool_dispatch.sql:4-5):
 *   status       = 后台审核态 / 进行态
 *   audit_status = 调配状态:0待调配 1中标 2落选  ← 择优结果只在这条轴上
 *
 * 现在处处拿 status==1 当「中标」能 work 纯属巧合:select() 中标时恰好同置了 status=1。
 * 二者一旦漂移(updateSharingRates 可裸改 status 而不碰 audit_status),
 * 一个 audit_status=2(落选)但 status 被改回 1 的商家就能核销玩家的票。
 *
 * 注:原文这里还有「就能拿分账」—— 2026-07-17 起不再成立,分账那条路径(distributeMerchantCommissions)
 * 已整个拆除(假账,详见下方 G6-a 段)。剩下的风险面只有核销资格这一条,由 G6-b 守着。
 */
class MerchantWinnerAxisTest {

    private CmsRegistrationMerchant merchant(Long id, Long memberId, Long status, Long auditStatus, String rate) {
        CmsRegistrationMerchant m = new CmsRegistrationMerchant();
        m.setId(id);
        m.setMemberId(memberId);
        m.setStatus(status);
        m.setAuditStatus(auditStatus);
        m.setSharingRate(rate == null ? null : new BigDecimal(rate));
        m.setDelFlag(0);
        return m;
    }

    // ---------------- G6-a 已随代码删除(2026-07-17) ----------------
    //
    // 原有两条用例(落选/待调配商家不该拿分账)测的是 CmsTopicServiceImpl.distributeMerchantCommissions,
    // 而那个方法已被拆除 —— 它写的 sharing_amount 全仓零消费者(假账),顺带从假金额里抽 2% 给
    // 商家邀请人打真钱。拓展人激励改由 MerchantReferralBounty 承接(只认核销,与结算解耦)。
    // 详见 CmsTopicServiceImpl.settleTopic 的拆除说明与 MerchantReferralBountyTest。
    //
    // ⚠️ 删这两条**没有**削弱 G6 的防线:「audit_status 才是中标真源」在核销资格那一侧仍由下面的
    // G6-b 守着(落选商家不许被裸改 status=1)。分账那一侧已经不存在了,无从误判。

    // ---------------- G6-b:updateSharingRates 不许裸改 status 造成两轴漂移 ----------------

    private CmsRegistrationMerchantServiceImpl sharingService(CmsRegistrationMerchant existing) {
        CmsRegistrationMerchantMapper mapper = mock(CmsRegistrationMerchantMapper.class);
        when(mapper.selectCmsRegistrationMerchantById(existing.getId())).thenReturn(existing);
        when(mapper.updateCmsRegistrationMerchant(any())).thenReturn(1);
        CmsRegistrationMerchantServiceImpl s = new CmsRegistrationMerchantServiceImpl();
        ReflectionTestUtils.setField(s, "cmsRegistrationMerchantMapper", mapper);
        return s;
    }

    private MerchantSharingRateDTO rateDto(Long id, int status) {
        MerchantSharingRateDTO dto = new MerchantSharingRateDTO();
        dto.setId(id);
        dto.setStatus(status);
        return dto;
    }

    @Test
    void cannotFlipStatusToOneOnALosingMerchant() {
        // 落选商家被裸改 status=1 —— 这正是让「核销资格/分账」错判的那条漂移路径
        CmsRegistrationMerchant loser = merchant(1L, 900002L, 2L, 2L, null);
        CmsRegistrationMerchantServiceImpl svc = sharingService(loser);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.updateSharingRates(rateDto(1L, 1)));
        assertTrue(ex.getMessage().contains("落选"), "应点明是择优结果与审核态冲突,实际:" + ex.getMessage());
    }

    @Test
    void statusUpdateOnWinnerStillAllowed() {
        // 负控的另一半:中标商家改 status 是正常操作,别一起拦了
        CmsRegistrationMerchant winner = merchant(1L, 900002L, 0L, 1L, null);
        CmsRegistrationMerchantServiceImpl svc = sharingService(winner);
        withLoggedInOperator();
        try {
            svc.updateSharingRates(rateDto(1L, 1));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        assertEquals(Long.valueOf(1L), winner.getStatus(), "中标商家的 status 照常可改");
    }

    /** updateSharingRates 会写 update_by,需要一个登录态运营账号。 */
    private void withLoggedInOperator() {
        com.chengyinhub.common.core.domain.entity.SysUser user = new com.chengyinhub.common.core.domain.entity.SysUser();
        user.setUserId(1L);
        user.setUserName("operator");
        com.chengyinhub.common.core.domain.model.LoginUser loginUser =
                new com.chengyinhub.common.core.domain.model.LoginUser(1L, 1L, user, java.util.Collections.emptySet());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(loginUser, null));
    }
}
