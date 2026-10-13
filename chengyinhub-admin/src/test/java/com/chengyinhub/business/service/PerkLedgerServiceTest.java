package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.domain.RegistrationEntitlement;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.RedemptionMapper;
import com.chengyinhub.business.mapper.RegistrationEntitlementMapper;
import com.chengyinhub.business.service.impl.PerkLedgerServiceImpl;
import com.chengyinhub.common.constant.TermsMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-13/14/15/16/17 权益与核销账本的业务规则。
 *
 * <p>本类守的是**顺序与拒绝**(mock 能证明的部分):三步核销的先后、拒绝时不留副作用、
 * 容量公式的算法。
 *
 * <p>⚠️ mock 证明不了的两类,由另外两处守,别在这里自我安慰:
 * <ul>
 *   <li>SQL 是否真按 valid_end 过滤、唯一键/CHECK 是否真拦得住 → {@code PerkLedgerMapperSqlTest}
 *       解析真 XML;DDL 的约束另在真 MySQL 上做过负控(见迁移文件头与 PR 描述)。</li>
 *   <li>并发交错(幻读超卖、撤销双花)→ 靠 DDL 的唯一键/CHECK + 章节行锁,单测跑不出来。</li>
 * </ul>
 *
 * <p>⚠️ **行锁不防超卖,当前读才防** —— 初版把这搞反了,真库并发实测打脸:普通读时 1 座 2 买家
 * 卖出 2、3 座 8 买家 8 人全买进(0 拒绝、0 报错、零告警),尽管 B 确实在章节行锁上阻塞了 0.81s。
 * 原因:RR 的 read view 在事务第一次普通读时建立(reserveEntitlements 一进来的 requiredChapters),
 * 锁后的普通聚合读看不见对手刚提交的写。改 FOR SHARE 当前读后:1 座卖 1,3 座 8 人恰好 3 进 5 拒。
 * 本类的 {@link #reserve_usesCurrentReadAggregates} 是这条的 CI 门禁(真库并发跑不进 CI)。
 *
 * ⚠️ 负控已实测:
 *  - reserve 改回用 chapterCapacity(普通读)⇒ reserve_usesCurrentReadAggregates 红
 *  - 把 redeemChapter 的 markRedeemed CAS 挪到 insertRedemption 之后 ⇒ redeem_orderIsCasFirst 红
 *  - 删掉 consumeQuota 返 0 时的 throw ⇒ redeem_quotaExhausted_rollsBack 红
 *  - reverseRedemption 在 reverse 返 0 时不 return ⇒ reverse_idempotent_noDoubleRelease 红
 *  - chapterCapacity 不减 held ⇒ capacity_heldCountsAgainstSupply 红
 *  - 把 redeemChapter 的 consumeQuota 档位分支去掉(改回无条件扣)⇒ revshare_quotaZero_stillRedeemable 红
 *  - usesQuota 把 null 判成非 PERK(即直接写 TermsMode.usesQuota(mode))⇒ nullTermsMode_treatedAsPerk_consumesQuota 红
 *  - 【D21 后半句·商家闸】拆掉 reserveEntitlements 的 hasLiveMerchant 闸 ⇒ 5 红
 *  - 【D21 后半句·商家闸】hasLiveMerchant 恒 true ⇒ 7 红(跨 PerkCoverageStatusTest 一起)
 *  - 【D21 后半句·商家闸】商家闸提到档位分支**外**(权益档也吃商家闸=闸放错层)⇒ 10 红,含
 *    reserve_perkChapterWithNoMerchant_rejectedByCapacityGateNotMerchantGate「权益档零商家应由容量闸拦,
 *    实际拿到:该路线暂无商家提供服务」—— 这条只有断言**哪道闸**才抓得到,只 assertThrows 会全绿放过
 *  - 【D21 后半句·档位过滤】hasLiveMerchant 不比档位(退回「数任何生效 offer」)⇒ 3 红
 *    (含跨档快照 PERK offer 冒充计酬档商家)
 *  - 【D21 后半句·零售出】topicCoverageStatus 非权益分支不看 held ⇒ 1 红
 *    (topicStatus_nonPerkChapterNoMerchantNothingSold_isOkNotGap),reserve 侧不受影响 = 两侧没串味
 */
class PerkLedgerServiceTest {

    private static final Long REG = 900L;
    private static final Long TOPIC = 10L;
    private static final Long CH1 = 101L;
    private static final Long CH2 = 102L;
    private static final Long MERCHANT = 500L;
    private static final Long OPERATOR = 501L;
    private static final Long OFFER = 7L;
    /** 平台后台账号 id(撤销入口的操作者,SysUser.userId)。 */
    private static final Long SYS_OPERATOR = 88L;

    private CmsTopicChapterMapper chapterMapper;
    private CmsTopicMapper topicMapper;
    private MerchantChapterOfferMapper offerMapper;
    private RegistrationEntitlementMapper entitlementMapper;
    private RedemptionMapper redemptionMapper;
    private com.chengyinhub.business.service.IMerchantPayableService merchantPayableService;
    private com.chengyinhub.business.mapper.CmsRegistrationMapper cmsRegistrationMapper;
    private BusinessAuditEventMapper businessAuditEventMapper;
    private PerkLedgerServiceImpl service;

    @BeforeEach
    void setUp() {
        chapterMapper = mock(CmsTopicChapterMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        offerMapper = mock(MerchantChapterOfferMapper.class);
        entitlementMapper = mock(RegistrationEntitlementMapper.class);
        redemptionMapper = mock(RedemptionMapper.class);

        service = new PerkLedgerServiceImpl();
        ReflectionTestUtils.setField(service, "chapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "topicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "offerMapper", offerMapper);
        ReflectionTestUtils.setField(service, "entitlementMapper", entitlementMapper);
        ReflectionTestUtils.setField(service, "redemptionMapper", redemptionMapper);
        // [P0-14] 撤销核销时清 verification_status(与「首次核销就置位」成对);本类不测该链路,注入空 mock。
        cmsRegistrationMapper = mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", cmsRegistrationMapper);
        // [③应付] 撤销核销时冲正平台应付(与「核销即计提」成对)。台账口径本身由
        // MerchantPayableServiceTest 测,这里只保证 reverseRedemption 真的把它调起来了(见 reverse_* 用例)。
        merchantPayableService = mock(com.chengyinhub.business.service.IMerchantPayableService.class);
        ReflectionTestUtils.setField(service, "merchantPayableService", merchantPayableService);
        // 撤销核销的不可变审计(谁撤的/为什么);默认写成功,写失败那条由 reverse_auditWriteFailure_rollsBack 专测。
        businessAuditEventMapper = mock(BusinessAuditEventMapper.class);
        when(businessAuditEventMapper.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", businessAuditEventMapper);
    }

    /**
     * ⚠️ 不设 termsMode ⇒ null ⇒ 被当 PERK(与 DDL {@code DEFAULT 'PERK'} 同口径)。
     * 这个默认是刻意的:本类的老用例全是权益档语义,继续走容量闸。要测别的档必须**显式**传,
     * 见 {@link #chapter(Long, String)}。
     */
    private CmsTopicChapter chapter(Long id) {
        return chapter(id, null);
    }

    private CmsTopicChapter chapter(Long id, String termsMode) {
        CmsTopicChapter c = new CmsTopicChapter();
        c.setId(id);
        c.setTopicId(TOPIC);
        c.setRequired(1);
        c.setTermsMode(termsMode);
        return c;
    }

    /** 必选章带档位版:id 顺序照传入的来(乱序也行),锁返回值按升序,与真 mapper 一致。 */
    private void givenRequiredChapters(CmsTopicChapter... chapters) {
        List<Long> idList = new ArrayList<>();
        for (CmsTopicChapter c : chapters) {
            idList.add(c.getId());
        }
        Collections.sort(idList);
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(Arrays.asList(chapters));
        when(chapterMapper.lockChaptersForCapacity(any())).thenReturn(idList);
    }

    private void givenRequiredChapters(Long... ids) {
        List<CmsTopicChapter> list = new ArrayList<>();
        List<Long> idList = new ArrayList<>();
        for (Long id : ids) {
            list.add(chapter(id));
            idList.add(id);
        }
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(list);
        when(chapterMapper.lockChaptersForCapacity(any())).thenReturn(idList);
    }

    /** 两版都 stub:只读路径用普通读版,购票路径用当前读版(FOR SHARE)。 */
    private void givenChapterSupply(Long chapterId, int remaining, int held) {
        when(offerMapper.sumRemainingQuotaByChapter(chapterId)).thenReturn(remaining);
        when(entitlementMapper.countUnredeemedByChapter(chapterId)).thenReturn(held);
        when(offerMapper.sumRemainingQuotaByChapterForUpdate(chapterId)).thenReturn(remaining);
        when(entitlementMapper.countUnredeemedByChapterForUpdate(chapterId)).thenReturn(held);
    }

    private MerchantChapterOffer liveOffer() {
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setId(OFFER);
        o.setTopicId(TOPIC);
        o.setChapterId(CH1);
        o.setMerchantId(MERCHANT);
        o.setQuotaTotal(10);
        o.setQuotaUsed(0);
        return o;
    }

    // ------------------------------------------------------- 容量公式(§3.2)

    /** 容量 = Σ(生效offer剩余) − 已售未核销。**售出即占容量**,不等核销。 */
    @Test
    void capacity_heldCountsAgainstSupply() {
        givenChapterSupply(CH1, 8, 4);
        assertEquals(4, service.chapterCapacity(CH1),
                "已售未核销的 4 份必须从供给里扣掉 —— 票卖出去的瞬间平台就欠着这份权益了");
    }

    /** 超卖时容量为负,不能被 clamp 成 0 —— 负值是缺口事件的输入信号。 */
    @Test
    void capacity_canGoNegativeWhenOversold() {
        givenChapterSupply(CH1, 2, 5);
        assertEquals(-3, service.chapterCapacity(CH1), "超卖必须暴露成负数,不能藏成 0");
    }

    /** 主题可售 = min(各必选章容量);任一章卡住整个主题就卖不动。 */
    @Test
    void topicCapacity_isMinAcrossRequiredChapters() {
        givenRequiredChapters(CH1, CH2);
        givenChapterSupply(CH1, 10, 0);
        givenChapterSupply(CH2, 3, 1);

        assertEquals(2, service.topicSellableCapacity(TOPIC), "短板章节决定整个主题还能卖几张");
    }

    /** 无必选章 ⇒ 不受权益容量约束,别把票全卡死。 */
    @Test
    void topicCapacity_noRequiredChapters_unbounded() {
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(Collections.<CmsTopicChapter>emptyList());
        assertEquals(Integer.MAX_VALUE, service.topicSellableCapacity(TOPIC));
    }

    /** P0-16 安全垫:覆盖率 = 剩余供给 ÷ 已售未核销。 */
    @Test
    void coverageRatio_computed() {
        givenChapterSupply(CH1, 12, 10);
        assertEquals(1.2d, service.chapterCoverageRatio(CH1), 0.0001);
    }

    /** 还没卖出去 ⇒ 无穷大,不是 0/0 崩掉,也不该报缺口。 */
    @Test
    void coverageRatio_nothingSold_isInfinite() {
        givenChapterSupply(CH1, 5, 0);
        assertTrue(Double.isInfinite(service.chapterCoverageRatio(CH1)));
    }

    // ------------------------------------------------------- 购票预占(P0-15)

    /** 容量够 ⇒ 每个必选章各建一条应得 entitlement。 */
    @Test
    void reserve_createsOneEntitlementPerRequiredChapter() {
        givenRequiredChapters(CH1, CH2);
        givenChapterSupply(CH1, 5, 0);
        givenChapterSupply(CH2, 5, 0);

        service.reserveEntitlements(REG, TOPIC);

        ArgumentCaptor<RegistrationEntitlement> captor = ArgumentCaptor.forClass(RegistrationEntitlement.class);
        verify(entitlementMapper, org.mockito.Mockito.times(2)).insertRegistrationEntitlement(captor.capture());
        for (RegistrationEntitlement e : captor.getAllValues()) {
            assertEquals(Integer.valueOf(0), e.getStatus(), "新建的权益必须是应得态(0),它才占容量");
            assertEquals(REG, e.getRegistrationId());
        }
    }

    /**
     * 任一必选章容量不足 ⇒ **整单拒绝,一条 entitlement 都不能建**。
     * 只发一半权益 = 玩家玩到第 4 章才发现没得领,那时钱已收、体验已毁,只能整单退。
     */
    @Test
    void reserve_anyChapterFull_rejectsWholeOrder() {
        givenRequiredChapters(CH1, CH2);
        givenChapterSupply(CH1, 5, 0);
        givenChapterSupply(CH2, 1, 1);          // 容量 0

        assertThrows(RuntimeException.class, () -> service.reserveEntitlements(REG, TOPIC));
        verify(entitlementMapper, never()).insertRegistrationEntitlement(any());
    }

    /**
     * 必须**先**锁章节行**再**算容量 —— 容量是跨两表聚合,不锁必幻读超卖。
     *
     * ⚠️ 用 InOrder 而不是光 verify 调用过:初版就是只 verify 了「调用过」,评审 mutation 实测把
     * lockChaptersForCapacity 挪到算容量之后,这条照样绿 —— 名字喊着「Before」却什么顺序都没断言。
     */
    @Test
    void reserve_locksChaptersBeforeComputingCapacity() {
        givenRequiredChapters(CH1, CH2);
        givenChapterSupply(CH1, 5, 0);
        givenChapterSupply(CH2, 5, 0);

        service.reserveEntitlements(REG, TOPIC);

        // 加锁必须早于任何一次容量聚合查询,也早于插 entitlement
        InOrder o = inOrder(chapterMapper, offerMapper, entitlementMapper);
        o.verify(chapterMapper).lockChaptersForCapacity(any());
        o.verify(offerMapper, atLeastOnce()).sumRemainingQuotaByChapterForUpdate(anyLong());
        o.verify(entitlementMapper, atLeastOnce()).insertRegistrationEntitlement(any());
    }

    /** 加锁必须按 id 升序 —— 两个购票事务锁同一组章节时顺序相反会 ABBA 死锁。 */
    @Test
    void reserve_locksInAscendingIdOrder() {
        // 故意乱序返回必选章
        List<CmsTopicChapter> list = Arrays.asList(chapter(CH2), chapter(CH1));
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(list);
        when(chapterMapper.lockChaptersForCapacity(any())).thenReturn(Arrays.asList(CH1, CH2));
        givenChapterSupply(CH1, 5, 0);
        givenChapterSupply(CH2, 5, 0);

        service.reserveEntitlements(REG, TOPIC);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(chapterMapper).lockChaptersForCapacity(captor.capture());
        assertEquals(Arrays.asList(CH1, CH2), captor.getValue(), "必须升序加锁,防 ABBA 死锁");
    }

    /**
     * **购票必须用当前读版本的聚合** —— 用普通读版就是超卖。
     *
     * <p>真库实测:普通读时 1 座 2 买家 → 卖出 2;8 买家 3 座 → 8 人全买进、零告警。
     * 行锁挡不住,因为 RR 的 read view 在本方法一进来的 requiredChapters 时就建好了。
     */
    @Test
    void reserve_usesCurrentReadAggregates() {
        givenRequiredChapters(CH1);
        givenChapterSupply(CH1, 5, 0);

        service.reserveEntitlements(REG, TOPIC);

        verify(offerMapper).sumRemainingQuotaByChapterForUpdate(CH1);
        verify(entitlementMapper).countUnredeemedByChapterForUpdate(CH1);
        verify(offerMapper, never()).sumRemainingQuotaByChapter(anyLong());
        verify(entitlementMapper, never()).countUnredeemedByChapter(anyLong());
    }

    /** 反过来:只读展示路径**不该**上锁(别把 S 锁带到浏览页)。 */
    @Test
    void readOnlyCapacity_usesPlainRead() {
        givenChapterSupply(CH1, 5, 1);

        service.chapterCapacity(CH1);

        verify(offerMapper).sumRemainingQuotaByChapter(CH1);
        verify(offerMapper, never()).sumRemainingQuotaByChapterForUpdate(anyLong());
    }

    /** 章节锁不全(有章节不存在)⇒ 拒,不能带着残缺数据往下走。 */
    @Test
    void reserve_chapterMissing_rejects() {
        givenRequiredChapters(CH1, CH2);
        when(chapterMapper.lockChaptersForCapacity(any())).thenReturn(Collections.singletonList(CH1));

        assertThrows(RuntimeException.class, () -> service.reserveEntitlements(REG, TOPIC));
        verify(entitlementMapper, never()).insertRegistrationEntitlement(any());
    }

    // ------------------------------------------------- 购票容量闸 × 条款档(F1)
    //
    // 与核销侧的 consumeQuota 分支是**同一个病的两处**:D21 三档里只有 PERK 走 quota,
    // enroll 对非 PERK 显式 setQuotaTotal(0) ⇒ 计酬/引流章的容量恒 `0 − 已售 ≤ 0`
    // ⇒ reserveEntitlements 的无条件容量闸让**该 ③ 路线一张票都卖不出去**,商家再入驻都没用。
    // 这不是误配,是 P0-50 必亏闸(算出 5 章 ×¥15 < ¥99 票价并放行)与容量闸在同一个 PR 里打架。
    //
    // ⚠️ 老用例的 chapter() 从不设 termsMode ⇒ null ⇒ PERK ⇒ 全部维持原语义,不是本节在放水。

    /** 供给耗尽的权益章:supply == held ⇒ 容量恰好 0。**闸该拦的就是这个值**。 */
    private void givenSupplyExhausted(Long chapterId) {
        givenChapterSupply(chapterId, 5, 5);
    }

    /** 非权益档 offer 的真实供给:enroll 落 quota_total=0 ⇒ Σ剩余 = 0,永远算不出正容量。 */
    private void givenNonPerkSupply(Long chapterId) {
        givenChapterSupply(chapterId, 0, 0);
    }

    /**
     * 该章有一个生效的非权益档商家。
     *
     * <p>⚠️ **必须显式 stub,别靠 mock 默认值** —— `givenNonPerkSupply` 的 0/0 同时符合
     * 「计酬商家已入驻(quota_total=0)」与「一个商家都没有」两种世界,`Σ(quota_total − quota_used)`
     * 分不清它们(都是 0)。D21 后半句用「商家数」而不是「配额」做判据正是因为这个,
     * 这也是缺口判定的 bug 藏这么久的原因。不 stub 就是拿默认空表冒充「有商家」。
     */
    private void givenLiveMerchantOn(Long chapterId, String termsMode) {
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setId(OFFER);
        o.setTopicId(TOPIC);
        o.setChapterId(chapterId);
        o.setMerchantId(MERCHANT);
        o.setTermsMode(termsMode);
        o.setQuotaTotal(0);          // 非权益档 enroll 就是落 0,不是漏填
        o.setQuotaUsed(0);
        when(offerMapper.selectByChapter(chapterId)).thenReturn(Collections.singletonList(o));
    }

    /**
     * ★★ 负控(D21 后半句):必选章 = 计酬档且**一个生效商家都没有** ⇒ 必须**拒售**。
     *
     * <p>零商家 ⇒ 该章永远核销不了 ⇒ isExploreCompleted 恒 false ⇒ 该票永不通关 ⇒ 只能整单全额退。
     * 卖出去是**确定性资损**,不是风险。F1 之前这条靠「Σ剩余=0 ⇒ 容量闸误拒」意外挡住了,
     * F1 把那道理由错误的保护移走 ⇒ 必须用正确的理由(商家数)接回来。
     *
     * <p>⚠️ 断言的是**哪道闸**拦的:同方法里「章节数据异常,请稍后重试」与「权益名额不足」
     * 都是裸 RuntimeException,只 assertThrows 会被它们接住 = 假绿。
     */
    @Test
    void reserve_revshareChapterWithNoMerchant_rejects() {
        givenRequiredChapters(chapter(CH1, TermsMode.REVSHARE));
        givenNonPerkSupply(CH1);
        // 刻意不 stub selectByChapter ⇒ 空表 ⇒ 零商家

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.reserveEntitlements(REG, TOPIC));
        assertTrue(e.getMessage().contains("商家"),
                "必须是「无商家」这道闸拦的,实际拿到:" + e.getMessage());
        verify(entitlementMapper, never()).insertRegistrationEntitlement(any());
    }

    /** 引流档同理:零商家 = 没人接待 = 打不了卡 = 永不通关。 */
    @Test
    void reserve_trafficChapterWithNoMerchant_rejects() {
        givenRequiredChapters(chapter(CH1, TermsMode.TRAFFIC));
        givenNonPerkSupply(CH1);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.reserveEntitlements(REG, TOPIC));
        assertTrue(e.getMessage().contains("商家"),
                "必须是「无商家」这道闸拦的,实际拿到:" + e.getMessage());
    }

    /**
     * ★★ 负控:混档里**权益章**零商家 ⇒ 仍由**容量闸**拦(Σ剩余=0 ⇒ capacity≤0),
     * 不该被新的「无商家」闸抢答。权益档的判据是配额不是商家数(D21 前半句),两条闸别串味。
     */
    @Test
    void reserve_perkChapterWithNoMerchant_rejectedByCapacityGateNotMerchantGate() {
        givenRequiredChapters(chapter(CH1, TermsMode.PERK));
        givenChapterSupply(CH1, 0, 0);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.reserveEntitlements(REG, TOPIC));
        assertTrue(e.getMessage().contains("权益名额不足"),
                "权益档零商家应由容量闸拦,实际拿到:" + e.getMessage());
    }

    /**
     * ★★ 跨档快照:章节已翻成 REVSHARE,但存量 offer 还是 PERK 快照 ⇒ **不算有效商家** ⇒ 拒售。
     *
     * <p>该商家谈的是权益档条款(按配额供货),并没有承诺计酬档的接待义务 —— 数进去就会卖出
     * 无人按该档履约的票。{@link TermsMode} 的 javadoc 已定「offer.terms_mode 必须与章节一致」,
     * 这里只是在读侧把这条不变量重新断言一次(快照纪律允许章节改档后两者漂移)。
     */
    @Test
    void reserve_nonPerkChapterWithOnlyMismatchedModeOffer_rejects() {
        givenRequiredChapters(chapter(CH1, TermsMode.REVSHARE));
        givenNonPerkSupply(CH1);
        givenLiveMerchantOn(CH1, TermsMode.PERK);   // 章节 REVSHARE,offer 却是 PERK 快照

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.reserveEntitlements(REG, TOPIC));
        assertTrue(e.getMessage().contains("商家"),
                "跨档快照 offer 不能冒充有效商家,实际拿到:" + e.getMessage());
    }

    /** null 档 offer 按 PERK ⇒ 对 REVSHARE 章同样是跨档 ⇒ 不算数。fail-closed 朝少卖倒。 */
    @Test
    void reserve_nonPerkChapterWithNullModeOffer_rejects() {
        givenRequiredChapters(chapter(CH1, TermsMode.REVSHARE));
        givenNonPerkSupply(CH1);
        givenLiveMerchantOn(CH1, null);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.reserveEntitlements(REG, TOPIC));
        assertTrue(e.getMessage().contains("商家"), "实际拿到:" + e.getMessage());
    }

    /** 一章有商家、另一章没有 ⇒ 整单拒:5 必选章发出 3 份权益,玩到第 4 章才发现没得领 = 钱已收体验已毁。 */
    @Test
    void reserve_oneNonPerkChapterMissingMerchant_rejectsWholeTicket() {
        givenRequiredChapters(chapter(CH1, TermsMode.REVSHARE), chapter(CH2, TermsMode.REVSHARE));
        givenNonPerkSupply(CH1);
        givenNonPerkSupply(CH2);
        givenLiveMerchantOn(CH1, TermsMode.REVSHARE);   // CH2 没有

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.reserveEntitlements(REG, TOPIC));
        assertTrue(e.getMessage().contains("商家"),
                "必须是「无商家」这道闸拦的,实际拿到:" + e.getMessage());
        verify(entitlementMapper, never()).insertRegistrationEntitlement(any());
    }

    /**
     * ★ 正控:必选章 = 计酬档、零 PERK offer ⇒ 必须**卖得出票**,且 entitlement 照建。
     *
     * <p>跳的是**闸**不是**账** —— entitlement 是 resolveRedeemableChapters / redeemChapter /
     * isExploreCompleted 的共同地基,少建一条这三处全瞎。
     */
    @Test
    void reserve_revshareChapter_sellsAndStillBuildsEntitlement() {
        givenRequiredChapters(chapter(CH1, TermsMode.REVSHARE));
        givenNonPerkSupply(CH1);
        givenLiveMerchantOn(CH1, TermsMode.REVSHARE);   // 「有商家」必须显式存在,见 givenLiveMerchantOn

        service.reserveEntitlements(REG, TOPIC);

        ArgumentCaptor<RegistrationEntitlement> captor = ArgumentCaptor.forClass(RegistrationEntitlement.class);
        verify(entitlementMapper).insertRegistrationEntitlement(captor.capture());
        assertEquals(Integer.valueOf(0), captor.getValue().getStatus(),
                "跳的是容量闸,不是账 —— 非权益档章照样要建应得态 entitlement");
        assertEquals(CH1, captor.getValue().getChapterId());
    }

    /** 引流档同理:零承诺零容量,接待打卡即可。 */
    @Test
    void reserve_trafficChapter_sells() {
        givenRequiredChapters(chapter(CH1, TermsMode.TRAFFIC));
        givenNonPerkSupply(CH1);
        givenLiveMerchantOn(CH1, TermsMode.TRAFFIC);

        service.reserveEntitlements(REG, TOPIC);

        verify(entitlementMapper).insertRegistrationEntitlement(any());
    }

    /** 非权益章根本不该去问容量 —— 问了就是白查一次库,也说明分支放错了层。 */
    @Test
    void reserve_nonPerkChapter_doesNotQueryCapacity() {
        givenRequiredChapters(chapter(CH1, TermsMode.REVSHARE));
        givenNonPerkSupply(CH1);
        givenLiveMerchantOn(CH1, TermsMode.REVSHARE);

        service.reserveEntitlements(REG, TOPIC);

        verify(offerMapper, never()).sumRemainingQuotaByChapterForUpdate(anyLong());
        verify(entitlementMapper, never()).countUnredeemedByChapterForUpdate(anyLong());
    }

    /**
     * ★★ 负控:权益章供给耗尽 ⇒ **必须仍抛**。不抛 = 闸被修穿了 = 超发。
     *
     * <p>⚠️ 断言的是**哪道闸**拦的,不只是「抛了」:同方法里「章节数据异常,请稍后重试」
     * 也是裸 RuntimeException,拆穿容量闸后它照样能把这条接住 ⇒ 只 assertThrows 就是假绿。
     */
    @Test
    void reserve_perkChapterExhausted_stillRejects() {
        givenRequiredChapters(chapter(CH1, TermsMode.PERK));
        givenSupplyExhausted(CH1);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.reserveEntitlements(REG, TOPIC));
        assertTrue(ex.getMessage().contains("权益名额不足"),
                "必须是容量闸拒的,不能被相邻的「章节数据异常」闸接住;实际:" + ex.getMessage());
        verify(entitlementMapper, never()).insertRegistrationEntitlement(any());
    }

    /**
     * ★★ null 档的方向是刻意的:按 PERK 处理,**照样拦**。
     *
     * <p>若把新谓词写成 {@code TermsMode.usesQuota(c.getTermsMode())},usesQuota(null) 返 false
     * ⇒ 权益档章节静默跳过容量闸 = **超发且零告警**。fail-closed 朝少卖倒,不朝超发倒。
     * 这条与 {@link #nullTermsMode_treatedAsPerk_consumesQuota} 是同一条纪律在购票侧的镜像。
     */
    @Test
    void reserve_nullTermsModeChapter_treatedAsPerk_stillRejects() {
        givenRequiredChapters(chapter(CH1, null));
        givenSupplyExhausted(CH1);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.reserveEntitlements(REG, TOPIC));
        assertTrue(ex.getMessage().contains("权益名额不足"),
                "null 必须按 PERK 走容量闸,否则权益章静默超发;实际:" + ex.getMessage());
    }

    /** 混档:计酬章不参与卡位,权益章有容量 ⇒ 整单成交,**两章都建账**。 */
    @Test
    void reserve_mixedModes_onlyPerkChapterGatesCapacity() {
        givenRequiredChapters(chapter(CH1, TermsMode.PERK), chapter(CH2, TermsMode.REVSHARE));
        givenChapterSupply(CH1, 5, 0);
        givenNonPerkSupply(CH2);
        givenLiveMerchantOn(CH2, TermsMode.REVSHARE);

        service.reserveEntitlements(REG, TOPIC);

        verify(entitlementMapper, org.mockito.Mockito.times(2)).insertRegistrationEntitlement(any());
        verify(offerMapper, never()).sumRemainingQuotaByChapterForUpdate(CH2);
    }

    /**
     * 混档负控:同一张通票里权益章耗尽 ⇒ 整单仍拒,非权益章的存在不削弱那道闸。
     *
     * <p>⚠️ CH2 必须显式给商家:否则容量闸被改穿时,「无商家」闸会把这条接住 ⇒ 照样绿 = 假绿。
     * 断言文案指名「权益名额不足」也是为这个(两道闸同为裸 RuntimeException)。
     */
    @Test
    void reserve_mixedModes_perkExhausted_stillRejectsWholeOrder() {
        givenRequiredChapters(chapter(CH1, TermsMode.PERK), chapter(CH2, TermsMode.REVSHARE));
        givenSupplyExhausted(CH1);
        givenNonPerkSupply(CH2);
        givenLiveMerchantOn(CH2, TermsMode.REVSHARE);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.reserveEntitlements(REG, TOPIC));
        assertTrue(ex.getMessage().contains("权益名额不足"), "实际:" + ex.getMessage());
        verify(entitlementMapper, never()).insertRegistrationEntitlement(any());
    }

    /**
     * 加锁仍必须升序 —— 容量闸的循环变量从 ids(Long)换成 required(CmsTopicChapter) 才拿得到档位,
     * 但**升序加锁与本次改动正交**,别顺手把 sort 一起搬走(ABBA 死锁)。
     */
    @Test
    void reserve_mixedModes_stillLocksInAscendingIdOrder() {
        givenRequiredChapters(chapter(CH2, TermsMode.REVSHARE), chapter(CH1, TermsMode.PERK));
        givenChapterSupply(CH1, 5, 0);
        givenNonPerkSupply(CH2);
        givenLiveMerchantOn(CH2, TermsMode.REVSHARE);

        service.reserveEntitlements(REG, TOPIC);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(chapterMapper).lockChaptersForCapacity(captor.capture());
        assertEquals(Arrays.asList(CH1, CH2), captor.getValue(),
                "必须升序加锁防 ABBA —— 与档位收窄正交,别一起改掉");
    }

    // ------------------------------------------------------- 核销(P0-14)

    private void givenRedeemable() {
        when(entitlementMapper.markRedeemed(REG, CH1)).thenReturn(1);
        when(offerMapper.selectByChapter(CH1)).thenReturn(Collections.singletonList(liveOffer()));
        when(offerMapper.consumeQuota(OFFER)).thenReturn(1);
    }

    /** 正控:三步都成功 ⇒ 流水带正确的 offer/幂等键。 */
    @Test
    void redeem_writesRedemptionWithOfferAndIdempotencyKey() {
        givenRedeemable();

        service.redeemChapter(REG, CH1, MERCHANT, OPERATOR);

        ArgumentCaptor<Redemption> captor = ArgumentCaptor.forClass(Redemption.class);
        verify(redemptionMapper).insertRedemption(captor.capture());
        Redemption r = captor.getValue();
        assertEquals(OFFER, r.getOfferId());
        assertEquals("ACTIVE", r.getStatus());
        assertEquals(OPERATOR, r.getOperatorId());
        assertEquals("redeem:" + REG + ":" + CH1 + ":" + MERCHANT, r.getIdempotencyKey(),
                "幂等键构造变了会让重试无法与「另一家抢核销」区分");
    }

    /**
     * **顺序定案**:必须先 CAS 抢占 entitlement,再扣配额,最后插流水。
     * 反了会出现「钱退了货照发」——核销读到应得态、退款并发置失效、核销再把失效覆盖回已核销。
     */
    @Test
    void redeem_orderIsCasFirst() {
        givenRedeemable();

        service.redeemChapter(REG, CH1, MERCHANT, OPERATOR);

        org.mockito.InOrder o = org.mockito.Mockito.inOrder(entitlementMapper, offerMapper, redemptionMapper);
        o.verify(entitlementMapper).markRedeemed(REG, CH1);
        o.verify(offerMapper).consumeQuota(OFFER);
        o.verify(redemptionMapper).insertRedemption(any());
    }

    /** entitlement 不是应得态(未买/已核销/已退款)⇒ 拒,且不得扣配额、不得插流水。 */
    @Test
    void redeem_notPending_rejectsWithoutSideEffects() {
        when(entitlementMapper.markRedeemed(REG, CH1)).thenReturn(0);

        assertThrows(RuntimeException.class, () -> service.redeemChapter(REG, CH1, MERCHANT, OPERATOR));
        verify(offerMapper, never()).consumeQuota(anyLong());
        verify(redemptionMapper, never()).insertRedemption(any());
    }

    /** 商家在本章无生效供给 ⇒ 拒,不得插流水。 */
    @Test
    void redeem_noLiveOffer_rejects() {
        when(entitlementMapper.markRedeemed(REG, CH1)).thenReturn(1);
        when(offerMapper.selectByChapter(CH1)).thenReturn(Collections.<MerchantChapterOffer>emptyList());

        assertThrows(RuntimeException.class, () -> service.redeemChapter(REG, CH1, MERCHANT, OPERATOR));
        verify(redemptionMapper, never()).insertRedemption(any());
    }

    /**
     * 配额用尽(条件 update 返 0)⇒ 必须抛,让 entitlement 的 CAS 一起回滚。
     * 不抛的话 entitlement 被吃掉但玩家没拿到东西 —— 权益凭空消失。
     */
    @Test
    void redeem_quotaExhausted_rollsBack() {
        when(entitlementMapper.markRedeemed(REG, CH1)).thenReturn(1);
        when(offerMapper.selectByChapter(CH1)).thenReturn(Collections.singletonList(liveOffer()));
        when(offerMapper.consumeQuota(OFFER)).thenReturn(0);      // 用尽

        assertThrows(RuntimeException.class, () -> service.redeemChapter(REG, CH1, MERCHANT, OPERATOR));
        verify(redemptionMapper, never()).insertRedemption(any());
    }

    /** 别家商家的 offer 不算数(uk 保证一商家一章一行,取错人就是把配额记到别人头上)。 */
    @Test
    void redeem_otherMerchantOffer_doesNotCount() {
        when(entitlementMapper.markRedeemed(REG, CH1)).thenReturn(1);
        MerchantChapterOffer other = liveOffer();
        other.setMerchantId(999L);
        when(offerMapper.selectByChapter(CH1)).thenReturn(Collections.singletonList(other));

        assertThrows(RuntimeException.class, () -> service.redeemChapter(REG, CH1, MERCHANT, OPERATOR));
    }

    // ------------------------------------------------------- 撤销

    // ------------------------------------------------------- 第二轮拍板第 4 条:撤销时勾选冲回积分/经验

    private com.chengyinhub.business.service.impl.RedemptionRewardClawbackService wireClawback(PerkLedgerServiceImpl target) {
        com.chengyinhub.business.service.impl.RedemptionRewardClawbackService clawback =
                mock(com.chengyinhub.business.service.impl.RedemptionRewardClawbackService.class);
        ReflectionTestUtils.setField(target, "redemptionRewardClawbackService", clawback);
        ReflectionTestUtils.setField(target, "officialEventV2Service",
                mock(com.chengyinhub.business.service.IOfficialEventV2Service.class));
        return clawback;
    }

    private com.chengyinhub.business.domain.CmsRegistration registrationRow() {
        com.chengyinhub.business.domain.CmsRegistration reg = new com.chengyinhub.business.domain.CmsRegistration();
        reg.setId(REG);
        reg.setMemberId(4242L);
        return reg;
    }

    /** 撤完票上已无生效核销 + 这一撤让主题从通关变不通关 ⇒ 两类奖励都交给冲回。 */
    @Test
    void reverse_withClawback_passesVerificationClearedAndCompletionLost() {
        PerkLedgerServiceImpl spyService = org.mockito.Mockito.spy(service);
        com.chengyinhub.business.service.impl.RedemptionRewardClawbackService clawback = wireClawback(spyService);
        Redemption r = activeRedemption();
        r.setTopicId(TOPIC);
        when(redemptionMapper.selectById(3L)).thenReturn(r);
        when(redemptionMapper.reverse(eq(3L), anyString())).thenReturn(1);
        when(cmsRegistrationMapper.clearVerification(REG)).thenReturn(1);
        when(cmsRegistrationMapper.selectCmsRegistrationById(REG)).thenReturn(registrationRow());
        org.mockito.Mockito.doReturn(true, false).when(spyService).isExploreCompleted(REG, TOPIC);

        assertTrue(spyService.reverseRedemption(3L, "店员扫错票", SYS_OPERATOR, true));

        verify(clawback).clawback(eq(r), any(), eq(true), eq(true), eq("店员扫错票"), eq(SYS_OPERATOR));
    }

    /** 同票其他章仍生效、主题仍通关 ⇒ 冲回照样留审计,但两类奖励都不冲。 */
    @Test
    void reverse_withClawback_otherChaptersStillActiveClawsNothingButStillAudits() {
        PerkLedgerServiceImpl spyService = org.mockito.Mockito.spy(service);
        com.chengyinhub.business.service.impl.RedemptionRewardClawbackService clawback = wireClawback(spyService);
        Redemption r = activeRedemption();
        r.setTopicId(TOPIC);
        when(redemptionMapper.selectById(3L)).thenReturn(r);
        when(redemptionMapper.reverse(eq(3L), anyString())).thenReturn(1);
        when(cmsRegistrationMapper.clearVerification(REG)).thenReturn(0);
        when(cmsRegistrationMapper.selectCmsRegistrationById(REG)).thenReturn(registrationRow());
        org.mockito.Mockito.doReturn(true).when(spyService).isExploreCompleted(REG, TOPIC);

        assertTrue(spyService.reverseRedemption(3L, "店员扫错票", SYS_OPERATOR, true));

        verify(clawback).clawback(eq(r), any(), eq(false), eq(false), anyString(), eq(SYS_OPERATOR));
    }

    /** 不勾 = 老行为,冲回服务一次都不碰;重复撤销(CAS 0 行)勾了也不碰 —— 不重复冲回。 */
    @Test
    void reverse_uncheckedOrDuplicate_neverClawsBack() {
        com.chengyinhub.business.service.impl.RedemptionRewardClawbackService clawback = wireClawback(service);
        when(redemptionMapper.selectById(3L)).thenReturn(activeRedemption());
        when(redemptionMapper.reverse(eq(3L), anyString())).thenReturn(1);
        assertTrue(service.reverseRedemption(3L, "店员误扫", SYS_OPERATOR));

        when(redemptionMapper.reverse(eq(3L), anyString())).thenReturn(0);
        assertFalse(service.reverseRedemption(3L, "重复点击", SYS_OPERATOR, true));

        verify(clawback, never()).clawback(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
    }

    private Redemption activeRedemption() {
        Redemption r = new Redemption();
        r.setId(3L);
        r.setRegistrationId(REG);
        r.setChapterId(CH1);
        r.setOfferId(OFFER);
        r.setStatus("ACTIVE");
        return r;
    }

    // ------------------------------------------------------- 条款档 × quota 容量链(缺口2)
    //
    // D21 三档里**只有 PERK 走 quota**(TermsMode.usesQuota):计酬/引流档不设接待上限,
    // 它们的 quota_total 取 DDL 默认 0。若 consumeQuota 无条件执行,
    // `quota_used < quota_total` 恒 false ⇒ 返 0 ⇒ 当场抛「名额已用完」整体回滚
    // ⇒ **非 PERK 档根本核销不了**。这条与「REVSHARE 要不要入账」正交:
    // 就算完全不做应付台账,只要开非 PERK 档就必炸。

    private MerchantChapterOffer offerWithMode(String mode, int quotaTotal) {
        MerchantChapterOffer o = liveOffer();
        o.setTermsMode(mode);
        o.setQuotaTotal(quotaTotal);
        return o;
    }

    /**
     * ★ 缺口2 的核心守卫:REVSHARE offer(quota_total=0)必须能核销。
     * 去掉 redeemChapter 的档位分支 ⇒ consumeQuota 被无条件调用、mock 默认返 0 ⇒ 抛异常 ⇒ 本例红。
     */
    @Test
    void revshare_quotaZero_stillRedeemable() {
        when(entitlementMapper.markRedeemed(REG, CH1)).thenReturn(1);
        when(offerMapper.selectByChapter(CH1))
                .thenReturn(Collections.singletonList(offerWithMode(TermsMode.REVSHARE, 0)));

        service.redeemChapter(REG, CH1, MERCHANT, OPERATOR);

        verify(offerMapper, never()).consumeQuota(anyLong());
        verify(redemptionMapper).insertRedemption(any());
    }

    /** 引流档同理:零结算零容量,核销只是「接待打卡」。 */
    @Test
    void traffic_quotaZero_stillRedeemable() {
        when(entitlementMapper.markRedeemed(REG, CH1)).thenReturn(1);
        when(offerMapper.selectByChapter(CH1))
                .thenReturn(Collections.singletonList(offerWithMode(TermsMode.TRAFFIC, 0)));

        service.redeemChapter(REG, CH1, MERCHANT, OPERATOR);

        verify(offerMapper, never()).consumeQuota(anyLong());
        verify(redemptionMapper).insertRedemption(any());
    }

    /** PERK 档不受影响:仍然扣配额,仍然在配额用尽时拒。 */
    @Test
    void perk_stillConsumesQuota() {
        when(entitlementMapper.markRedeemed(REG, CH1)).thenReturn(1);
        when(offerMapper.selectByChapter(CH1))
                .thenReturn(Collections.singletonList(offerWithMode(TermsMode.PERK, 10)));
        when(offerMapper.consumeQuota(OFFER)).thenReturn(1);

        service.redeemChapter(REG, CH1, MERCHANT, OPERATOR);

        verify(offerMapper).consumeQuota(OFFER);
    }

    /**
     * ★ null 的方向是刻意的:按 PERK 处理(与 DDL {@code NOT NULL DEFAULT 'PERK'} 同口径)。
     * 若直接写 {@code TermsMode.usesQuota(null)} 会返 false ⇒ PERK offer 静默跳过扣配额 = 超发且零告警。
     * 反向误判(REVSHARE 当 PERK)只会响亮抛「名额已用完」,不造成资损 ⇒ fail-closed 朝响亮那侧倒。
     */
    @Test
    void nullTermsMode_treatedAsPerk_consumesQuota() {
        when(entitlementMapper.markRedeemed(REG, CH1)).thenReturn(1);
        when(offerMapper.selectByChapter(CH1))
                .thenReturn(Collections.singletonList(offerWithMode(null, 10)));
        when(offerMapper.consumeQuota(OFFER)).thenReturn(1);

        service.redeemChapter(REG, CH1, MERCHANT, OPERATOR);

        verify(offerMapper).consumeQuota(OFFER);
    }

    /**
     * 撤销**不看档位**,与 consumeQuota 刻意不对称。
     *
     * <p>非 PERK 档核销时没扣 ⇒ quota_used 恒 0 ⇒ releaseQuota 的 {@code quota_used > 0} 守卫
     * 让它天然 no-op,扣不穿(所以不需要分支)。而按**当前**档位判反而制造新洞:
     * 某 offer 当初 PERK(quota_used=3)、事后 terms_mode 被改成 REVSHARE,撤销当初那笔时
     * 无条件回补才是对的;按新档位跳过则 quota_used 永久卡住 ⇒ 容量永久少算 ⇒ 少卖。
     * ⇒ 回补的判据是「当初扣没扣」,quota_used > 0 就是这个事实的直接证据。**别把这里改成读档位。**
     */
    @Test
    void reverse_revshareOffer_stillCallsReleaseQuota_guardedBySql() {
        Redemption r = activeRedemption();
        when(redemptionMapper.selectById(3L)).thenReturn(r);
        when(redemptionMapper.reverse(eq(3L), anyString())).thenReturn(1);

        assertTrue(service.reverseRedemption(3L, "店员误扫", SYS_OPERATOR));

        // 无条件调用;真正的保护在 SQL 的 quota_used > 0(见 PerkLedgerMapperSqlTest)
        verify(offerMapper).releaseQuota(OFFER);
    }

    /** 撤销:回补配额 + 权益退回应得态(可重新核销)。 */
    @Test
    void reverse_releasesQuotaAndRestoresEntitlement() {
        when(redemptionMapper.selectById(3L)).thenReturn(activeRedemption());
        when(redemptionMapper.reverse(eq(3L), anyString())).thenReturn(1);

        assertTrue(service.reverseRedemption(3L, "店员误扫", SYS_OPERATOR));

        verify(offerMapper).releaseQuota(OFFER);
        verify(entitlementMapper).restoreToPending(REG, CH1);
    }

    /**
     * 重复撤销 ⇒ 幂等,**不得二次回补配额**。
     * 各回补一次会把 quota_used 扣成负数,凭空多出配额(库里 ck_offer_quota 兜底会 500,
     * 但那是兜底不是设计)。
     */
    @Test
    void reverse_idempotent_noDoubleRelease() {
        when(redemptionMapper.selectById(3L)).thenReturn(activeRedemption());
        when(redemptionMapper.reverse(eq(3L), anyString())).thenReturn(0);   // 已被撤销过

        assertFalse(service.reverseRedemption(3L, "重复点击", SYS_OPERATOR));

        verify(offerMapper, never()).releaseQuota(anyLong());
        verify(entitlementMapper, never()).restoreToPending(anyLong(), anyLong());
        // [③应付] 重复撤销也不得二次冲正 —— 冲两次 = 应付被冲成负数 = 平台反倒“欠”出一笔钱。
        // 这里靠的是上面那道 `reverse(...) == 0 → return false` 闸,与配额回补同一道。
        verify(merchantPayableService, never()).reverseOnRedemption(anyLong());
    }

    /**
     * ★ [③应付] 撤销核销 ⇒ **冲正平台应付**,与「核销即计提」成对。
     *
     * <p>不冲的后果:平台继续欠着一笔**已经不存在**的核销的钱,且零告警 ——
     * 与本方法里 clearVerification 那条(为一笔不存在的核销永久锁死退款)同型。
     *
     * <p>台账口径本身(用被冲那笔的快照、追加 REVERSAL 新行、不改原行)由
     * {@link MerchantPayableServiceTest} 测;本条只管**这道线接上没有**。
     * ⚠️ 这条是负控实测补出来的:原先只注入了 mock 没有断言,
     * 把 reverseOnRedemption 调用删掉时 46 条测试全绿 —— 等于这道线没有任何东西守着。
     */
    @Test
    void reverse_reversesPlatformPayable() {
        when(redemptionMapper.selectById(3L)).thenReturn(activeRedemption());
        when(redemptionMapper.reverse(eq(3L), anyString())).thenReturn(1);

        assertTrue(service.reverseRedemption(3L, "店员误扫", SYS_OPERATOR));

        verify(merchantPayableService).reverseOnRedemption(3L);
    }

    /**
     * ★ RED→GREEN:一次撤销接口调用,三类事实必须同时回滚 —— 这正是本任务接线的验收本体。
     * 单独看每条已有用例(配额/权益/应付)只证明每根线在,这里证明**一次调用全部生效**。
     * 负控:把 reverseRedemption 里任意一条回滚摘掉 ⇒ 本条或对应专测红(已实测,见报告)。
     */
    @Test
    void reverse_redeemReverseEntry_rollsBackEntitlementPayableAndVerification() {
        when(redemptionMapper.selectById(3L)).thenReturn(activeRedemption());
        when(redemptionMapper.reverse(eq(3L), anyString())).thenReturn(1);

        assertTrue(service.reverseRedemption(3L, "店员扫错票", SYS_OPERATOR));

        InOrder inOrder = inOrder(redemptionMapper, offerMapper, entitlementMapper,
                cmsRegistrationMapper, merchantPayableService, businessAuditEventMapper);
        inOrder.verify(redemptionMapper).reverse(3L, "店员扫错票");      // 核销记录 REVERSED
        inOrder.verify(offerMapper).releaseQuota(OFFER);                  // 商家配额回补
        inOrder.verify(entitlementMapper).restoreToPending(REG, CH1);      // 权益退回可核销
        inOrder.verify(cmsRegistrationMapper).clearVerification(REG);     // 票的已核销标记回收
        inOrder.verify(merchantPayableService).reverseOnRedemption(3L);   // 平台应付冲正
        inOrder.verify(businessAuditEventMapper).insertBusinessAuditEvent(any(BusinessAuditEvent.class));
    }

    /**
     * ★ 留痕:谁撤的(平台账号)、为什么(reason)、撤的是哪条(targetId)。
     *
     * <p>只有 redeemed_at/reverse_reason 的 redemption 行答不出「谁撤的」;
     * 后台 @Log 的 sys_oper_log 又刻意不存请求参数,答不出「撤的是哪一条」。
     * 所以必须落 business_audit_event,且这条审计与撤销同事务(写失败见下条)。
     */
    @Test
    void reverse_writesWhoWhyWhichAudit() {
        Redemption r = activeRedemption();
        r.setMerchantId(MERCHANT);
        when(redemptionMapper.selectById(3L)).thenReturn(r);
        when(redemptionMapper.reverse(eq(3L), anyString())).thenReturn(1);

        service.reverseRedemption(3L, "店员误扫", SYS_OPERATOR);

        ArgumentCaptor<BusinessAuditEvent> captor = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(businessAuditEventMapper).insertBusinessAuditEvent(captor.capture());
        BusinessAuditEvent audit = captor.getValue();
        // 域与「核销」那条审计同分区(REGISTRATION/registrationId),正向反向一次查得到
        assertEquals("REGISTRATION", audit.getDomainType());
        assertEquals(REG, audit.getDomainId());
        assertEquals(BusinessAuditEvent.ACTOR_TYPE_SYS_USER, audit.getActorType());
        assertEquals(SYS_OPERATOR, audit.getActorMemberId());
        assertEquals("REDEMPTION_REVERSE", audit.getAction());
        assertEquals("REDEMPTION", audit.getTargetType());
        assertEquals(3L, audit.getTargetId().longValue());
        assertEquals(CH1, audit.getRelationId());
        assertEquals("店员误扫", audit.getReason());
        assertEquals("redemption-reverse:3", audit.getRequestId());
    }

    /**
     * 审计写不进去 ⇒ **整体回滚**(抛异常),不允许「撤了但查不出谁撤的」。
     * 负控:把 writeReverseAudit 里的 `!= 1 → throw` 改成 best-effort(只 logger.warn)⇒ 本条红。
     */
    @Test
    void reverse_auditWriteFailure_rollsBack() {
        when(redemptionMapper.selectById(3L)).thenReturn(activeRedemption());
        when(redemptionMapper.reverse(eq(3L), anyString())).thenReturn(1);
        when(businessAuditEventMapper.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(0);

        assertThrows(RuntimeException.class,
                () -> service.reverseRedemption(3L, "店员误扫", SYS_OPERATOR));
    }

    /**
     * ★ 连续撤销两次:第一次成功,第二次被 redemption 的 status='ACTIVE' CAS 挡住(返 false),
     * **不二次回补配额、不二次冲正、不二次落审计** —— 这是撤销入口幂等的本体。
     *
     * <p>负控:把 `reverse(...) == 0 → return false` 那行改成继续往下走 ⇒ 本条红
     * (重复调用会各回补一次,quota_used 扣成负数;应付被冲两次变负;审计撞唯一键)。
     */
    @Test
    void reverse_secondCallBlockedByCas_noDoubleRollback() {
        when(redemptionMapper.selectById(3L)).thenReturn(activeRedemption());
        when(redemptionMapper.reverse(eq(3L), anyString())).thenReturn(1, 0);

        assertTrue(service.reverseRedemption(3L, "店员误扫", SYS_OPERATOR));
        assertFalse(service.reverseRedemption(3L, "重复点击", SYS_OPERATOR));

        verify(offerMapper).releaseQuota(OFFER);
        verify(entitlementMapper).restoreToPending(REG, CH1);
        verify(merchantPayableService).reverseOnRedemption(3L);
        verify(businessAuditEventMapper).insertBusinessAuditEvent(any(BusinessAuditEvent.class));
    }

    /** ① 整单核销(chapter_id 为空)撤销时不碰 entitlement —— ① 没有章节权益这回事。 */
    @Test
    void reverse_orderLevelRedemption_doesNotTouchEntitlement() {
        Redemption r = activeRedemption();
        r.setChapterId(null);
        r.setOfferId(null);
        when(redemptionMapper.selectById(3L)).thenReturn(r);
        when(redemptionMapper.reverse(eq(3L), anyString())).thenReturn(1);

        assertTrue(service.reverseRedemption(3L, "运营撤销", SYS_OPERATOR));

        verify(entitlementMapper, never()).restoreToPending(anyLong(), anyLong());
        verify(offerMapper, never()).releaseQuota(anyLong());
    }

    /** 哨兵 chapter_id=0 与 null 同义,同样不碰 entitlement。 */
    @Test
    void reverse_sentinelChapterZero_treatedAsOrderLevel() {
        Redemption r = activeRedemption();
        r.setChapterId(0L);
        when(redemptionMapper.selectById(3L)).thenReturn(r);
        when(redemptionMapper.reverse(eq(3L), anyString())).thenReturn(1);

        service.reverseRedemption(3L, "运营撤销", SYS_OPERATOR);

        verify(entitlementMapper, never()).restoreToPending(anyLong(), anyLong());
    }

    // ------------------------------------------------------- 通关(P0-17)

    /** ③ 通关 = 每个必选章都有生效核销(不是 done>=total 的打卡口径)。 */
    @Test
    void completed_whenAllRequiredChaptersRedeemed() {
        givenRequiredChapters(CH1, CH2);
        when(redemptionMapper.countActiveRequiredChaptersRedeemed(REG, TOPIC)).thenReturn(2);

        assertTrue(service.isExploreCompleted(REG, TOPIC));
    }

    /** 差一章就不算通关。 */
    @Test
    void notCompleted_whenOneRequiredChapterMissing() {
        givenRequiredChapters(CH1, CH2);
        when(redemptionMapper.countActiveRequiredChaptersRedeemed(REG, TOPIC)).thenReturn(1);

        assertFalse(service.isExploreCompleted(REG, TOPIC));
    }

    /** 显式存在主题但规则列为 NULL 时，仍走存量必选章判据。 */
    @Test
    void nullCompletionRuleKeepsLegacyRequiredChapterSemantics() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setCompleteRuleJson(null);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        givenRequiredChapters(CH1, CH2);
        when(redemptionMapper.countActiveRequiredChaptersRedeemed(REG, TOPIC)).thenReturn(2);

        assertTrue(service.isExploreCompleted(REG, TOPIC));
        verify(redemptionMapper).countActiveRequiredChaptersRedeemed(REG, TOPIC);
    }

    /** 没有必选章 ⇒ 不算通关,别误发勋章/XP。 */
    @Test
    void notCompleted_whenNoRequiredChapters() {
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(Collections.<CmsTopicChapter>emptyList());

        assertFalse(service.isExploreCompleted(REG, TOPIC));
    }

    /** 新主题规则必须同时满足角色数与需求组数；只核销 2 章是文档指定负控。 */
    @Test
    void configuredCompletion_requiresThreeRolesAcrossTwoNeedGroups() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setCompleteRuleJson("{\"distinctMerchantRoles\":3,\"needGroups\":2}");
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);

        when(redemptionMapper.selectActiveCompletionCounts(REG, TOPIC))
                .thenReturn(completionCounts(2, 2), completionCounts(3, 1), completionCounts(3, 2));

        assertFalse(service.isExploreCompleted(REG, TOPIC), "角色不足不得通关");
        assertFalse(service.isExploreCompleted(REG, TOPIC), "需求组不足不得通关");
        assertTrue(service.isExploreCompleted(REG, TOPIC));
        verify(redemptionMapper, never()).countActiveRequiredChaptersRedeemed(REG, TOPIC);
    }

    /** 配置损坏不能静默退回旧判据提前发奖。 */
    @Test
    void malformedCompletionRuleFailsClosed() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setCompleteRuleJson("{");
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        givenRequiredChapters(CH1);
        when(redemptionMapper.countActiveRequiredChaptersRedeemed(REG, TOPIC)).thenReturn(1);

        assertFalse(service.isExploreCompleted(REG, TOPIC));
        verify(redemptionMapper, never()).countActiveRequiredChaptersRedeemed(REG, TOPIC);
    }

    private Map<String, Object> completionCounts(int roles, int needGroups) {
        Map<String, Object> row = new HashMap<>();
        row.put("merchantRoles", roles);
        row.put("needGroups", needGroups);
        return row;
    }

    // ------------------------------------------------------- 退款释放

    /** 退款失效未核销权益,带上失效原因(商家对账要答得出「我这章为什么少了预占」)。 */
    @Test
    void releaseOnRefund_passesReason() {
        when(entitlementMapper.invalidateByRegistration(REG, "REFUND")).thenReturn(3);

        assertEquals(3, service.releaseOnRefund(REG, "REFUND"));
        verify(entitlementMapper).invalidateByRegistration(REG, "REFUND");
    }
}
