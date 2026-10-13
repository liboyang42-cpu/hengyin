package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.RedemptionMapper;
import com.chengyinhub.business.mapper.RegistrationEntitlementMapper;
import com.chengyinhub.business.service.impl.PerkLedgerServiceImpl;
import com.chengyinhub.common.constant.TermsMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ★ 请求级幂等:同一(票,章,商家)重试 ⇒ 返回原 redemptionId,不重复扣任何东西。
 *
 * <p><b>为什么需要它</b>:核销是「扫码 → 商家手机等响应」。响应丢了(超时/断网/商家手抖再点一次)
 * 时,重试请求会撞上已经是 status=1 的 entitlement ⇒ markRedeemed 的 CAS 返 0 ⇒ 抛
 * 「该章节权益不可核销(未购买/已核销/已退款)」⇒ <b>商家看到的是一个 500 和一句"已核销"的错话</b>,
 * 而其实他上一次就成功了。玩家在店里,商家不知道该不该端出咖啡。
 *
 * <p><b>闸的位置是这条的全部</b>:必须在 CAS(①)**之前**。放到 CAS 之后等于没放 ——
 * CAS 会先把请求打回去,幂等查根本执行不到。{@link #retry_gateMustSitBeforeCas} 守这条。
 *
 * <p><b>它不解决并发</b>,也不试图解决:两个同 key 请求同时进来,都会查空、都进 CAS,
 * 一个赢一个抛。那条路由库层 uk_redemption_idem_active 兜底(1062),不会双花。
 * 本类守的是**顺序重试**,别拿它当并发证明 —— 并发单测跑不出来。
 *
 * <p>⚠️ 负控已实测(逐条注入→红→撤销→绿):
 * <ul>
 *   <li>删掉 redeemChapter 的 ⓪ 幂等查 ⇒ 本类红 3</li>
 *   <li>把 ⓪ 挪到 markRedeemed 之后 ⇒ 本类红 3(CAS 先抛,走不到幂等)</li>
 *   <li>去掉 ⓪ 里的 payment_status 复核 ⇒ refundedTicket_replayRejected 红</li>
 *   <li>payment_status 白名单(!=2 拒)改成黑名单(==4 拒)⇒ nonPaidPaymentStatus_replayRejected 红
 *       ——⚠️ 只有 refundedTicket_replayRejected 时这条变异是**绿**的,补了用 5 的用例才抓得到</li>
 *   <li>把裸 RuntimeException 改回 IllegalStateException ⇒
 *       replayRejection_isBareRuntimeException_soItGetsLogged 红</li>
 * </ul>
 *
 * <p>⚠️ <b>本类守不住「撤销后可重扫」,别以为守得住</b>。那条语义活在库的生成列
 * {@code active_idem_key}(= status='ACTIVE' 时才等于 idempotency_key,否则 NULL)里,
 * 而这里的 RedemptionMapper 是 mock —— <b>mock 表现不出生成列</b>。
 * 实测坐实:把 mapper 改成查裸 {@code idempotency_key}(即砸掉「撤销后自动失忆」)
 * ⇒ <b>本类 6/6 全绿</b>,一条都不红。
 * <p>⇒ 那条由 {@code ExploreRecallAndIdemSqlTest.idem_queriesGeneratedColumnForIndex}
 * 守(同一变异下它真的红)。<b>此前这里有一个叫 afterReversal_notIdempotent_canRedeemAgain
 * 的用例声称守这条,实为空测试</b>:它的 stub 与首次核销那条逐字相同,「撤销」一个字都没被执行,
 * 而 javadoc 还写着「该变异会让它红」—— 那条负控我从没跑过。已删除,教训留在这儿。
 *
 * <p>⚠️ <b>另一条负控也抓不到,如实记下来</b>:`r.setIdempotencyKey(idemKey)` 改回重算
 * `redeemKey(registrationId, chapterId, merchantId)` 时,两者当前**恰好等值**,所以没有任何用例会红。
 * 它防的是**将来**有人改了 redeemKey 的入参或格式却只改一处 —— 那时写进库的键与查的键会静默分叉,
 * 幂等永远查不中、每次重试都抛 500,且没有任何测试会告诉你。这是「同一事实算两遍」的结构性风险,
 * 只能靠复用同一个局部变量消除,消除不了就得靠人看。
 */
class ExploreRedeemIdempotentTest {

    private static final Long REG = 900L;
    private static final Long TOPIC = 10L;
    private static final Long CH1 = 101L;
    private static final Long MERCHANT = 500L;
    private static final Long OPERATOR = 501L;
    private static final Long OFFER = 7L;
    private static final Long EXISTING_REDEMPTION = 66L;

    /** 与 PerkLedgerServiceImpl.redeemKey 同格式。写死而不是调私有方法:格式漂了这里要红。 */
    private static final String IDEM_KEY = "redeem:" + REG + ":" + CH1 + ":" + MERCHANT;

    private MerchantChapterOfferMapper offerMapper;
    private RegistrationEntitlementMapper entitlementMapper;
    private RedemptionMapper redemptionMapper;
    private CmsRegistrationMapper regMapper;
    private PerkLedgerServiceImpl service;

    @BeforeEach
    void setUp() {
        offerMapper = mock(MerchantChapterOfferMapper.class);
        entitlementMapper = mock(RegistrationEntitlementMapper.class);
        redemptionMapper = mock(RedemptionMapper.class);
        regMapper = mock(CmsRegistrationMapper.class);

        service = new PerkLedgerServiceImpl();
        ReflectionTestUtils.setField(service, "chapterMapper", mock(CmsTopicChapterMapper.class));
        ReflectionTestUtils.setField(service, "offerMapper", offerMapper);
        ReflectionTestUtils.setField(service, "entitlementMapper", entitlementMapper);
        ReflectionTestUtils.setField(service, "redemptionMapper", redemptionMapper);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", regMapper);

        // 首次核销的正常世界:entitlement 抢得到、有生效供给、配额扣得动、票还是已支付态
        when(entitlementMapper.markRedeemed(REG, CH1)).thenReturn(1);
        when(offerMapper.selectByChapter(CH1)).thenReturn(Collections.singletonList(offer()));
        when(offerMapper.consumeQuota(OFFER)).thenReturn(1);
        when(regMapper.selectCmsRegistrationById(REG)).thenReturn(reg(2));   // 2 = 已支付
    }

    private CmsRegistration reg(Integer paymentStatus) {
        CmsRegistration r = new CmsRegistration();
        r.setId(REG);
        r.setPaymentStatus(paymentStatus);
        return r;
    }

    private MerchantChapterOffer offer() {
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setId(OFFER);
        o.setTopicId(TOPIC);
        o.setChapterId(CH1);
        o.setMerchantId(MERCHANT);
        o.setTermsMode(TermsMode.PERK);
        o.setQuotaTotal(10);
        o.setQuotaUsed(0);
        o.setStatus(1);
        return o;
    }

    private Redemption existing() {
        Redemption r = new Redemption();
        r.setId(EXISTING_REDEMPTION);
        r.setRegistrationId(REG);
        r.setChapterId(CH1);
        r.setMerchantId(MERCHANT);
        r.setStatus("ACTIVE");
        r.setIdempotencyKey(IDEM_KEY);
        return r;
    }

    /**
     * ★ 核心:重试 ⇒ 返回**原** redemptionId,且账本三件事一件都不做。
     *
     * <p>「不做」比「返对 id」更重要:重复扣一次 quota_used = 商家凭空少一个名额;
     * 重复插一行 redemption = 结算时按核销笔数付两次人头费(REVSHARE 档接上后就是真金)。
     */
    @Test
    void retry_returnsOriginalId_touchesNothing() {
        when(redemptionMapper.selectActiveByIdempotencyKey(IDEM_KEY)).thenReturn(existing());

        Long id = service.redeemChapter(REG, CH1, MERCHANT, OPERATOR);

        assertEquals(EXISTING_REDEMPTION, id, "重试必须返回原 redemptionId");
        verify(entitlementMapper, never()).markRedeemed(anyLong(), anyLong());
        verify(offerMapper, never()).consumeQuota(anyLong());
        verify(redemptionMapper, never()).insertRedemption(any());
    }

    /**
     * ★★ 闸的位置:幂等查必须在 CAS **之前**。
     *
     * <p>这条与上一条的区别:这里把 entitlement 摆成**真实的重试现场** —— 上一次已核销成功,
     * 所以 markRedeemed 现在返 0。闸若在 CAS 之后,CAS 先抛,幂等永远执行不到 ⇒ 这条红。
     * 上一条用的是 markRedeemed=1 的宽松世界,单看它无法区分闸放在 CAS 前还是后。
     */
    @Test
    void retry_gateMustSitBeforeCas() {
        when(redemptionMapper.selectActiveByIdempotencyKey(IDEM_KEY)).thenReturn(existing());
        when(entitlementMapper.markRedeemed(REG, CH1)).thenReturn(0);   // 已核销过 ⇒ CAS 必然打回

        Long id = service.redeemChapter(REG, CH1, MERCHANT, OPERATOR);

        assertEquals(EXISTING_REDEMPTION, id, "闸在 CAS 之后 ⇒ 这里会抛「该章节权益不可核销」");
    }

    /** 首次请求(查空)⇒ 逐字走老路:CAS → 扣配额 → 插流水。闸误伤首次核销的话这条红。 */
    @Test
    void firstTime_proceedsNormally() {
        when(redemptionMapper.selectActiveByIdempotencyKey(IDEM_KEY)).thenReturn(null);

        service.redeemChapter(REG, CH1, MERCHANT, OPERATOR);

        verify(entitlementMapper).markRedeemed(REG, CH1);
        verify(offerMapper).consumeQuota(OFFER);
        verify(redemptionMapper).insertRedemption(any());
    }

    /**
     * ★★ [资损] 已退款的票不许重放核销 —— 幂等不能变成「拿旧流水换新咖啡」的后门。
     *
     * <p>退款**不会**撤销已成立的核销(商家当时确实端出去了,那笔 redemption 保持 ACTIVE,
     * 对应 entitlement 保持 status=1 —— 见 RegistrationEntitlementMapper.xml:80 只动 status=0 的行)。
     * 于是「已退款 + 该章已核销」的票,⓪ 照样能查到 ACTIVE 流水。
     *
     * <p><b>改动前这条路是 CAS 挡的</b>:markRedeemed 要求 status=0,已核销的返 0 ⇒ 抛
     * 「该章节权益不可核销(未购买/已核销/已退款)」。⓪ 抢在 CAS 前面就把这道保护绕过去了 ——
     * 已退款玩家拿同一个码回到原商家,商家端显示「核销成功」(实为重放),二次端出权益。
     * ⇒ 必须在**被调方**自己复核,不能只靠 resolveExploreScanContext:1160 那道调用方闸:
     * 那是「闸放错层」,本轮已经因为它栽过四次。
     */
    @Test
    void refundedTicket_replayRejected() {
        when(redemptionMapper.selectActiveByIdempotencyKey(IDEM_KEY)).thenReturn(existing());
        when(regMapper.selectCmsRegistrationById(REG)).thenReturn(reg(4));   // 4 = 已退款

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.redeemChapter(REG, CH1, MERCHANT, OPERATOR));

        assertTrue(e.getMessage().contains("已退款"), "实际:" + e.getMessage());
        verify(redemptionMapper, never()).insertRedemption(any());
    }

    /**
     * ★ 必须是**裸 RuntimeException**,不能是 IllegalStateException。
     *
     * <p>ApiRegistrationController:1185/1188 按异常类型分档:IllegalStateException = 业务拒绝
     * **静默**透出(不记日志);裸 RuntimeException = logger.error 记一条。
     * 走到这道闸意味着**调用方漏了 payment_status 校验**(正常路径到不了)——
     * 那是线上事故,必须留下 error 日志,否则闸虽然拦住了、却没人知道有个入口是坏的。
     *
     * <p>⚠️ 断言用 getClass() 精确比对而不是 assertThrows(RuntimeException.class):
     * 后者对 IllegalStateException 也会通过(子类),等于守不住分档 —— 这条断言的全部意义
     * 就是把「别退回 IllegalStateException」钉死。
     */
    @Test
    void replayRejection_isBareRuntimeException_soItGetsLogged() {
        when(redemptionMapper.selectActiveByIdempotencyKey(IDEM_KEY)).thenReturn(existing());
        when(regMapper.selectCmsRegistrationById(REG)).thenReturn(reg(4));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.redeemChapter(REG, CH1, MERCHANT, OPERATOR));

        assertEquals(RuntimeException.class, e.getClass(),
                "IllegalStateException 会落进 controller 的静默分支 ⇒ 新入口绕开闸时零告警");
    }

    /**
     * ★★ 判据是**白名单**(只放行 2),不是黑名单(只挡 4)。
     *
     * <p>这条用例存在的唯一理由:负控实测发现,只有 {@link #refundedTicket_replayRejected} 时,
     * 把 `!= 2 拒` 改成 `== 4 拒`(白名单退化成黑名单)⇒ <b>全绿,一条都不红</b>。
     * 也就是说注释里写的「将来多一个终态,黑名单就漏、白名单自动挡」这句话**当时没有任何测试守着**。
     * 支付状态将来多一个终态(部分退款/资金冻结/争议中)就会静默漏回去 —— 而漏的方向是
     * 「已退款玩家重放核销」。用一个**不是 4 也不是 2** 的值把这条钉死。
     */
    @Test
    void nonPaidPaymentStatus_replayRejected() {
        when(redemptionMapper.selectActiveByIdempotencyKey(IDEM_KEY)).thenReturn(existing());
        when(regMapper.selectCmsRegistrationById(REG)).thenReturn(reg(5));   // 将来的某个终态

        assertThrows(RuntimeException.class,
                () -> service.redeemChapter(REG, CH1, MERCHANT, OPERATOR),
                "白名单必须只放行 2;写成黑名单(只挡 4)时这条会绿");
    }

    /** 报名查不到(脏数据/并发删)⇒ 拒,不重放。fail-closed。 */
    @Test
    void missingRegistration_replayRejected() {
        when(redemptionMapper.selectActiveByIdempotencyKey(IDEM_KEY)).thenReturn(existing());
        when(regMapper.selectCmsRegistrationById(REG)).thenReturn(null);

        assertThrows(RuntimeException.class,
                () -> service.redeemChapter(REG, CH1, MERCHANT, OPERATOR));
    }

    /**
     * ★ 换一家商家扫同一章 ⇒ **不**幂等,必须真的走进去被拒。
     *
     * <p>幂等键含 merchantId,所以 B 家的 key 与 A 家不同 ⇒ 查空 ⇒ 进 CAS ⇒ entitlement 已是 1 ⇒ 返 0 ⇒ 抛。
     * 若幂等键漏了 merchantId,B 家会拿到 A 家的 redemptionId 和一个"成功"响应
     * ⇒ <b>B 家白端出一杯咖啡还以为核销成功了</b>。
     */
    @Test
    void differentMerchant_notIdempotent_stillRejected() {
        Long otherMerchant = 777L;
        when(redemptionMapper.selectActiveByIdempotencyKey("redeem:" + REG + ":" + CH1 + ":" + otherMerchant))
                .thenReturn(null);
        when(entitlementMapper.markRedeemed(REG, CH1)).thenReturn(0);   // 已被 A 家核销

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.redeemChapter(REG, CH1, otherMerchant, OPERATOR));

        assertEquals("该章节权益不可核销(未购买/已核销/已退款)", e.getMessage());
        verify(redemptionMapper, never()).insertRedemption(any());
    }

    /** 写进库的 idempotency_key == 查的那个键。两者分叉 = 幂等静默失效(永远查不中)。 */
    @Test
    void insertedKey_matchesQueriedKey() {
        when(redemptionMapper.selectActiveByIdempotencyKey(IDEM_KEY)).thenReturn(null);
        org.mockito.ArgumentCaptor<Redemption> cap = org.mockito.ArgumentCaptor.forClass(Redemption.class);

        service.redeemChapter(REG, CH1, MERCHANT, OPERATOR);

        verify(redemptionMapper).insertRedemption(cap.capture());
        verify(redemptionMapper).selectActiveByIdempotencyKey(IDEM_KEY);
        assertEquals(IDEM_KEY, cap.getValue().getIdempotencyKey(),
                "写进去的键与查的键必须一致,否则重试永远命不中");
    }
}
