package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsRegistrationScanMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.common.constant.PurchaseKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ★★ [资损·根因闸] ③ 探索票**绝不能**走 {@code verifyAndSettle}(整单收口)。
 *
 * <p><b>这个类是「同一个病犯了三次」之后立的最后一道防线</b>。verifyAndSettle 的语义是
 * 「整单票款按 settlement_rule 结算给**单一** payee」,而 ③ 一张票跨 N 家商家逐章核销
 * ⇒ 整单给谁都是错的 ⇒ <b>谁调它谁就制造资损</b>。三次翻车全是「我以为我普查完了调用方」:
 * <ol>
 *   <li>③ 分流放错分支(写进 type="topic",而 ③ 只走 type="activity");</li>
 *   <li>拿「③ 定死 owner_type=2 ⇒ 到不了 topic 分支」这个**假不变量**拆掉守卫 ——
 *       实际走哪个分支由**客户端传的 type 字符串**决定;</li>
 *   <li>漏掉 {@code ApiClubLeadController} 的领队全团结算 —— 它把该活动下**所有已付单**
 *       (探针无 purchase_kind 过滤)逐个丢进 verifyAndSettle,payee=俱乐部主理人
 *       ⇒ ③ 的整单票款结给主理人,真正端出权益的 N 家商家一分钱没有。</li>
 * </ol>
 *
 * <p>⇒ <b>闸放在调用方就永远漏,只有放在被调方才保得住「现在的三个入口 + 将来任何新入口」</b>。
 * 这条用例就是那道闸的守卫:任何人新写一条路把 ③ 丢进 verifyAndSettle,它立刻红。
 *
 * <p>⚠️ 负控已实测:删掉 verifyAndSettle 里的 EXPLORE_PASS 判断 ⇒ 本类三条全红。
 */
class ExploreNeverWholeOrderSettleTest {

    private static final Long REG = 900L;
    private static final Long VERIFIER = 500L;
    private static final Long PAYEE = 999L;

    private CmsRegistrationMapper regMapper;
    private ISettlementEntryService settlementSvc;
    private CmsRegistrationScanMapper scanMapper;
    private CmsRegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        regMapper = mock(CmsRegistrationMapper.class);
        settlementSvc = mock(ISettlementEntryService.class);
        scanMapper = mock(CmsRegistrationScanMapper.class);

        service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", regMapper);
        com.chengyinhub.business.mapper.CmsActivityMapper activities = mock(com.chengyinhub.business.mapper.CmsActivityMapper.class);
        com.chengyinhub.business.domain.CmsActivity activity = new com.chengyinhub.business.domain.CmsActivity();
        activity.setDelFlag(0);
        when(activities.selectCmsActivityByIdForUpdate(800L)).thenReturn(activity);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(service, "settlementEntryService", settlementSvc);
        ReflectionTestUtils.setField(service, "cmsRegistrationScanMapper", scanMapper);
        // [赏金] writeScanLedger 末尾会调 onVerification(核销是赏金唯一认的信号,收口点在那);
        //        本类不测赏金,哑 mock 即可。赏金自身判定见 MerchantReferralBountyTest。
        ReflectionTestUtils.setField(service, "merchantReferralBountyService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.MerchantReferralBountyService.class));
        // 核销成功后入队到店成长发放(本类不测成长,哑 mock 即可)
        ReflectionTestUtils.setField(service, "growthService", mock(IGrowthService.class));
        BusinessAuditEventMapper auditMapper = mock(BusinessAuditEventMapper.class);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditMapper);

        when(regMapper.verifyIfNotVerified(anyLong(), anyLong())).thenReturn(1);
        when(scanMapper.selectCmsRegistrationScanList(any()))
                .thenReturn(java.util.Collections.<com.chengyinhub.business.domain.CmsRegistrationScan>emptyList());
    }

    private CmsRegistration reg(Integer purchaseKind) {
        CmsRegistration r = new CmsRegistration();
        r.setId(REG);
        r.setOwnerType(2L);
        r.setOwnerId(800L);
        r.setPaymentStatus(2);
        r.setPurchaseKind(purchaseKind);
        return r;
    }

    /**
     * ★ 核心:③ 丢进 verifyAndSettle ⇒ **抛**,且一分钱都不入账、一行台账都不写、不置位。
     *
     * <p>抛而不是静默返 false:走到这里 = 调用方漏了 ③ 分流 = 编程错误,必须响亮。
     * 返 false 的语义是「已被核销」,拿它掩盖会让新入口静默地把 ③ 结算掉 —— 正是要杀的病。
     */
    @Test
    void explorePass_rejectedAtSharedSettleGate() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.verifyAndSettle(reg(PurchaseKind.EXPLORE_PASS), VERIFIER, PAYEE, null));

        assertTrue(e.getMessage().contains("按章节核销"), "实际:" + e.getMessage());
        // ★ 三件事一件都不能发生
        verify(settlementSvc, never()).recordPaidRegistration(any(), anyLong());
        verify(regMapper, never()).verifyIfNotVerified(anyLong(), anyLong());
        verify(scanMapper, never()).insertCmsRegistrationScan(any());
    }

    /** ① 逐字不变:照常整单收口。闸误伤 ① 的话这条红。 */
    @Test
    void guidedTicket_stillSettlesAsBefore() {
        assertTrue(service.verifyAndSettle(reg(PurchaseKind.GUIDED_TICKET), VERIFIER, PAYEE, null));

        verify(regMapper).verifyIfNotVerified(REG, VERIFIER);
        verify(settlementSvc).recordPaidRegistration(any(), org.mockito.ArgumentMatchers.eq(PAYEE));
        verify(scanMapper).insertCmsRegistrationScan(any());
    }

    /**
     * purchase_kind 为 NULL(存量未回填 / 后台手工建单)⇒ **放行**,逐字走老路。
     *
     * <p>fail-safe 方向对:NULL 的三个来源都产生不了「带 entitlement 的 ③ 活动单」——
     * 后台手工建单走 insertCmsRegistration,压根不经 reserveEntitlements。
     * 若这里对 NULL 也拒,会把存量 ① 单的核销全打死 = 打挂现网。
     */
    @Test
    void nullPurchaseKind_passesThrough() {
        assertTrue(service.verifyAndSettle(reg(null), VERIFIER, PAYEE, null));

        verify(settlementSvc).recordPaidRegistration(any(), anyLong());
    }
}
