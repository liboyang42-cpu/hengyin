package com.chengyinhub.business.service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.chengyinhub.business.domain.MerchantReferralBounty;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.mapper.MerchantReferralBountyMapper;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import com.chengyinhub.business.service.IUmsMemberService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拓展人激活赏金:四道闸 + 发钱/追回。
 *
 * <p>【本用例证不了什么】mapper 全被 mock ⇒ <b>SQL 口径对不对(核销怎么算、退款有没有被排除)
 * 一概证不了</b>。那半由 {@code countVerifiedHeadsByMerchantInWindow} 的 XML 与
 * {@code countStillValidAmong} 的逐字一致性保证,只有真库能验。本用例只管
 * 「拿到人头数之后,闸开不开、钱发不发、发几次」。
 *
 * <p>⚠️ 别用 {@code any()} 匹配发钱调用 —— 那样金额写错了测试照样绿(2026-07-16 栽过)。
 * 所有 addBalance 断言都锁死具体 memberId + 具体金额。
 *
 * <p>负控实测(2026-07-17,六条逐个注入→红→撤销→绿,串行跑):
 * <ol>
 *   <li>{@code heads < ACTIVATION_HEADS} → {@code heads < 2} ⇒ activate_paysNothing_whenHeadsBelowThreshold 红</li>
 *   <li>{@code if (now.after(windowExpiresAt))} → {@code if (false)} ⇒ activate_marksExpiredNotPaid_whenWindowElapsed 红</li>
 *   <li>{@code paid >= INVITER_CAP} → {@code paid > INVITER_CAP} ⇒ activate_paysNothing_whenInviterCapReached 红</li>
 *   <li>发放 CAS {@code == 0} → {@code == -1} ⇒ activate_paysNothing_whenCasLost 红</li>
 *   <li>超窗分支改回 {@code markPaidIfPending(id,null,…)} ⇒ activate_marksExpiredNotPaid_whenWindowElapsed 红(假账防线)</li>
 *   <li>追回 CAS {@code == 0} → {@code == -1} ⇒ clawback_deductsOnlyOnce_whenCasLost 红</li>
 * </ol>
 *
 * <p>★ ④⑥ 第一次跑负控时<b>没变红 = 假绿</b>:那两个用例当初没 stub inviter,
 * {@code selectUmsMemberById} 返回 null ⇒ payBounty/clawbackBalance 在 addBalance 之前就
 * early-return,<b>拦住钱的是缺失的 stub 而不是 CAS</b>。补 stub 后变异才如实变红。
 * 教训:「never().addBalance()」这类否定断言最容易假绿 —— 必须让别的前置条件全部成立。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MerchantReferralBountyTest {

    private static final long MERCHANT = 500L;
    private static final long INVITER = 100L;
    private static final long BOUNTY_ID = 7L;

    @Mock
    private MerchantReferralBountyMapper bountyMapper;

    @Mock
    private IUmsMemberService umsMemberService;

    @Mock
    private UmsMemberBalanceDetailMapper balanceDetailMapper;

    /** ★ 2026-07-25:追回扣款改走 mapper 直调(绕开 IUmsMemberService.addBalance 拒负数的校验)。 */
    @Mock
    private UmsMemberMapper umsMemberMapper;

    @InjectMocks
    private MerchantReferralBountyService service;

    @org.junit.jupiter.api.BeforeEach
    void wireFinancialLockFixture() {
        org.springframework.transaction.PlatformTransactionManager manager =
                org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.mockito.Mockito.when(manager.getTransaction(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(i -> new org.springframework.transaction.support.SimpleTransactionStatus());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "transactionManager", manager);
        org.mockito.Mockito.when(umsMemberMapper.selectBalanceForUpdate(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(i -> {
                    UmsMember member = umsMemberService.selectUmsMemberById(i.getArgument(0));
                    // Lock-row fixture is independent of the legacy existence guard being tested.
                    return member == null || member.getBalance() == null ? BigDecimal.ZERO : member.getBalance();
                });
        org.mockito.Mockito.when(bountyMapper.selectPaidIdsByInviterForUpdate(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(i -> java.util.Collections.nCopies(bountyMapper.countPaidByInviter(i.getArgument(0)), 0L));
        org.mockito.Mockito.when(bountyMapper.selectByMerchantMemberIdForUpdate(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(i -> bountyMapper.selectByMerchantMemberId(i.getArgument(0)));
    }

    private static Date daysFromNow(int days) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTime();
    }

    /** 待激活占位:注册于 10 天前,窗口还剩 20 天。 */
    private MerchantReferralBounty pendingBounty() {
        MerchantReferralBounty b = new MerchantReferralBounty();
        b.setId(BOUNTY_ID);
        b.setMerchantMemberId(MERCHANT);
        b.setInviterMemberId(INVITER);
        b.setMerchantRegisteredAt(daysFromNow(-10));
        b.setWindowExpiresAt(daysFromNow(20));
        b.setStatus(MerchantReferralBounty.STATUS_PENDING);
        return b;
    }

    private void givenInviterBalance(String balance) {
        UmsMember m = new UmsMember();
        m.setId(INVITER);
        m.setBalance(new BigDecimal(balance));
        when(umsMemberService.selectUmsMemberById(INVITER)).thenReturn(m);
    }

    /** 人头够 + 实付够(¥100 远高于 ¥30 下限)—— 只想测别的闸时用这个。 */
    private void givenHeads(int heads) {
        givenHeads(heads, "100.00");
    }

    /**
     * ★ 让「能发钱」的**其余**前置条件全部成立 —— 否定断言(never().addBalance)专用。
     *
     * <p><b>不用它就会假绿</b>,这个坑我在本文件里栽过两次:
     * <ol>
     *   <li>CAS 那两条 —— 没 stub inviter,payBounty 在 addBalance 前就 early-return,
     *       拦住钱的是缺失的 stub 而不是 CAS;</li>
     *   <li>反套利这两条 —— 同样没 stub inviter,拆掉反套利闸做负控时 27 条<b>全绿</b>,
     *       真正拦着的是「收款人不存在」检查。</li>
     * </ol>
     * 教训:验「某道闸拦住了钱」时,必须让**除它以外**的每道闸都放行,否则你测的是别人。
     */
    private void givenEverythingElseWouldPay() {
        when(bountyMapper.countPaidByInviter(INVITER)).thenReturn(0);
        when(bountyMapper.markPaidIfPending(eq(BOUNTY_ID), any(), any(), any(), any(), any())).thenReturn(1);
        givenInviterBalance("0.00");
    }

    private void givenHeads(int heads, String paidInWindow) {
        when(bountyMapper.countVerifiedHeadsByMerchantInWindow(eq(MERCHANT), any(), any())).thenReturn(heads);
        when(bountyMapper.sumVerifiedPaidByMerchantInWindow(eq(MERCHANT), any(), any()))
                .thenReturn(new BigDecimal(paidInWindow));
        when(bountyMapper.selectVerifiedRegistrationIdsByMerchantInWindow(eq(MERCHANT), any(), any(), anyInt()))
                .thenReturn(Arrays.asList(11L, 22L, 33L));
    }

    // ==================== 闸①:达到 3 次核销才发 ====================

    @Test
    void activate_paysExactlyThirtyYuan_whenThreeHeadsReached() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(pendingBounty());
        givenHeads(3);
        when(bountyMapper.countPaidByInviter(INVITER)).thenReturn(0);
        when(bountyMapper.markPaidIfPending(eq(BOUNTY_ID), any(), any(), any(), any(), any())).thenReturn(1);
        givenInviterBalance("5.00");

        service.onVerification(MERCHANT);

        // 锁死金额:用 any() 匹配的话,发 ¥3000 也照样绿
        verify(umsMemberService).addBalance(INVITER, new BigDecimal("30.00"));
    }

    /** ★ 负控靶心:2 次核销时一分钱都不能动(其余闸全部放行,确保拦住钱的就是人头闸)。 */
    @Test
    void activate_paysNothing_whenHeadsBelowThreshold() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(pendingBounty());
        givenHeads(2, "100.00");
        givenEverythingElseWouldPay();

        service.onVerification(MERCHANT);

        verify(umsMemberService, never()).addBalance(anyLong(), any());
        verify(bountyMapper, never()).markPaidIfPending(anyLong(), any(), any(), any(), any(), any());
    }

    // ==================== 闸①b:反套利 —— 光有人头不算,得有真钱进来 ====================

    /**
     * ★★ 零成本套利:3 个小号买 0 元票、商家自扫自核 ⇒ 推荐人白拿 ¥30。必须拦住。
     *
     * <p><b>这不是假想</b>(生产实证 2026-07-17):reg#47 与 reg#71 <b>实付 0.00 而 payment_status=2</b>;
     * 72 张票里最低票价 0.00、7 张 ≤¥1;5 笔有效已支付报名里 3 笔实付 ≤¥1。
     * 激活闸原先只数 {@code payment_status=2} 的人头,建个 0 元票主题就能白刷。
     *
     * <p>我在自审里写过「刷 ¥30 的成本远高于 ¥30,经济上自限」——<b>那句是错的</b>,
     * 我默认了「付费报名 ≈ 真实 GMV」,而闸上根本没有金额下限。这条用例就是那句错话的墓碑。
     */
    @Test
    void activate_paysNothing_whenHeadsAreFreeTickets() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(pendingBounty());
        givenHeads(3, "0.00");   // 3 个人头,合计实付 0 元
        givenEverythingElseWouldPay();

        service.onVerification(MERCHANT);

        verify(umsMemberService, never()).addBalance(anyLong(), any());
        verify(bountyMapper, never()).markPaidIfPending(anyLong(), any(), any(), any(), any(), any());
    }

    /** ¥0.03(3 张 1 分钱票)同样拦住 —— 下限是赏金额 ¥30,不是「大于 0」。 */
    @Test
    void activate_paysNothing_whenPaidBelowBountyAmount() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(pendingBounty());
        givenHeads(3, "29.99");   // 差一分钱
        givenEverythingElseWouldPay();

        service.onVerification(MERCHANT);

        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    /**
     * 边界:合计实付恰好等于赏金额 ⇒ 放行。
     *
     * <p>门槛绑定 BOUNTY_AMOUNT 的意义就在这:套利在数学上永远不成立(要拿 ¥30 得先真付 ¥30 进来,
     * 还搭上手续费,且那笔钱还要走结算分给商家和发布者),而对真商家几乎无感。
     */
    @Test
    void activate_pays_whenPaidExactlyMeetsBountyAmount() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(pendingBounty());
        givenHeads(3, "30.00");
        when(bountyMapper.countPaidByInviter(INVITER)).thenReturn(0);
        when(bountyMapper.markPaidIfPending(eq(BOUNTY_ID), any(), any(), any(), any(), any())).thenReturn(1);
        givenInviterBalance("0.00");

        service.onVerification(MERCHANT);

        verify(umsMemberService).addBalance(INVITER, new BigDecimal("30.00"));
    }

    /** 门槛必须跟着赏金额走,不能是写死的常数 —— 改价时忘改门槛就是重新打开套利口。 */
    @Test
    void activationFloor_isBoundToBountyAmount_notAHardcodedNumber() {
        assertEquals(0, MerchantReferralBountyService.BOUNTY_AMOUNT
                        .compareTo(MerchantReferralBountyService.ACTIVATION_MIN_PAID),
                "实付下限必须恒等于赏金额,否则改价会留下套利缺口");
    }

    // ==================== 闸②:30 天窗口 ====================

    /**
     * ★ 超窗必须置 3(已失效),<b>绝不能走 markPaidIfPending</b>。
     *
     * <p>这条断言存在的理由:写第一版时我图省事,超窗分支复用了
     * {@code markPaidIfPending(id, null, null, null, null, null)} —— 那会把 status 置成
     * 1(已发放)却不发钱,造出「记录说发了、余额没动」的假账,正是本轨要消灭的病。
     */
    @Test
    void activate_marksExpiredNotPaid_whenWindowElapsed() {
        MerchantReferralBounty b = pendingBounty();
        b.setMerchantRegisteredAt(daysFromNow(-40));
        b.setWindowExpiresAt(daysFromNow(-10));   // 窗口 10 天前就关了
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(b);

        service.onVerification(MERCHANT);

        verify(bountyMapper).markExpiredIfPending(BOUNTY_ID);
        verify(bountyMapper, never()).markPaidIfPending(anyLong(), any(), any(), any(), any(), any());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    // ==================== 闸③:单拓展人封顶 20 家 ====================

    @Test
    void activate_paysNothing_whenInviterCapReached() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(pendingBounty());
        givenHeads(3);
        givenEverythingElseWouldPay();
        when(bountyMapper.countPaidByInviter(INVITER)).thenReturn(20);   // 已满(覆盖上面的 0)

        service.onVerification(MERCHANT);

        verify(umsMemberService, never()).addBalance(anyLong(), any());
        verify(bountyMapper, never()).markPaidIfPending(anyLong(), any(), any(), any(), any(), any());
    }

    /** 第 20 家仍该发(闸是 >=20 不发,即第 21 家才拦)。边界方向别记反。 */
    @Test
    void activate_stillPays_atNineteenPaid() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(pendingBounty());
        givenHeads(3);
        when(bountyMapper.countPaidByInviter(INVITER)).thenReturn(19);
        when(bountyMapper.markPaidIfPending(eq(BOUNTY_ID), any(), any(), any(), any(), any())).thenReturn(1);
        givenInviterBalance("0.00");

        service.onVerification(MERCHANT);

        verify(umsMemberService).addBalance(INVITER, new BigDecimal("30.00"));
    }

    // ==================== 闸④:重复触发只发一次 ====================

    /**
     * ★ CAS 输了(affected=0)= 别人已经发过 ⇒ 本次绝不能再发钱。
     *
     * <p>这是并发下唯一的防线:「先读 status=0 再写」会让两个线程都读到 0、都发钱。
     *
     * <p>⚠️ {@code givenInviterBalance} 这行不能省 —— 省了就是假绿。第一版没写它,
     * 负控实测(把 CAS 的 {@code == 0} 改 {@code == -1})<b>测试照样绿</b>:
     * inviter 查不到 ⇒ payBounty 在 addBalance 之前就 early-return 了,
     * 拦住钱的是缺失的 stub 而非 CAS。补上后变异才如实变红。
     */
    @Test
    void activate_paysNothing_whenCasLost() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(pendingBounty());
        givenHeads(3);
        when(bountyMapper.countPaidByInviter(INVITER)).thenReturn(0);
        when(bountyMapper.markPaidIfPending(eq(BOUNTY_ID), any(), any(), any(), any(), any())).thenReturn(0);
        givenInviterBalance("5.00");   // ← 让「能发钱」的一切前置条件都成立,只剩 CAS 拦着

        service.onVerification(MERCHANT);

        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    /** 已发放的记录再被核销触发,直接短路,连人头都不该数。 */
    @Test
    void activate_shortCircuits_whenAlreadyPaid() {
        MerchantReferralBounty b = pendingBounty();
        b.setStatus(MerchantReferralBounty.STATUS_PAID);
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(b);

        service.onVerification(MERCHANT);

        verify(bountyMapper, never()).countVerifiedHeadsByMerchantInWindow(anyLong(), any(), any());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    // ==================== ★ 收款人不存在:不置位、不发钱(假账防线) ====================

    /**
     * ★★ 拓展人查不到时,<b>绝不能把记录置成「已发放」</b>。
     *
     * <p><b>这条是自审(/code-review high)抓出来的真 bug 的守门人</b>。初版把顺序写反了:
     * 先 CAS 置 status=1,再在 payBounty 里查 inviter,查不到就 log.error + return
     * ⇒ 库里记为「已发放」、余额一分没动 = <b>正是本轨立志要消灭的那个假账</b>
     * (我为它写了三处注释,却把它留在了自己的代码里)。
     *
     * <p><b>且这条路很好走</b>:{@code ums_member.invite_user_id} 是自由文本 VARCHAR、<b>没有外键</b>,
     * parseInviter 只校验「能解析成正数」,不校验「这个人存在」—— 随便填个 99999 就走到这儿。
     */
    @Test
    void activate_marksNothing_whenInviterDoesNotExist() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(pendingBounty());
        givenHeads(3);
        when(bountyMapper.countPaidByInviter(INVITER)).thenReturn(0);
        when(umsMemberService.selectUmsMemberById(INVITER)).thenReturn(null);   // 脏 invite_user_id 指向不存在的人

        service.onVerification(MERCHANT);

        // 关键:不能置位。置了就是「记录说发了、余额没动」
        verify(bountyMapper, never()).markPaidIfPending(anyLong(), any(), any(), any(), any(), any());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
        verify(balanceDetailMapper, never()).insertUmsMemberBalanceDetail(any());
    }

    /** 追回方向同理:置了 status=2「已追回」却没扣到钱 = 假账的另一个方向。 */
    @Test
    void clawback_marksNothing_whenInviterDoesNotExist() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(paidBounty());
        when(bountyMapper.countStillValidAmong(any(), eq(MERCHANT))).thenReturn(0);
        when(umsMemberService.selectUmsMemberById(INVITER)).thenReturn(null);

        service.onRefund(MERCHANT);

        verify(bountyMapper, never()).markClawedBackIfPaid(anyLong(), any(), any());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    // ==================== 推荐关系解析(invite_user_id 是 String 列) ====================

    @Test
    void noBounty_whenMerchantHasNoInviter() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(null);
        UmsMember merchant = new UmsMember();
        merchant.setId(MERCHANT);
        merchant.setCreateTime(daysFromNow(-1));
        merchant.setInviteUserId("");        // 空串 = 无推荐人
        when(umsMemberService.selectUmsMemberById(MERCHANT)).thenReturn(merchant);

        service.onVerification(MERCHANT);

        verify(bountyMapper, never()).insertBounty(any());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    /**
     * ★ invite_user_id 是 VARCHAR,脏数据会让裸 Long.valueOf 抛。
     *
     * <p>既有的 CmsTopicServiceImpl 就是裸转 —— 这里显式校验,不照抄它。
     */
    @Test
    void parseInviter_toleratesDirtyStringColumn() {
        assertNull(MerchantReferralBountyService.parseInviter(null));
        assertNull(MerchantReferralBountyService.parseInviter(""));
        assertNull(MerchantReferralBountyService.parseInviter("   "));
        assertNull(MerchantReferralBountyService.parseInviter("abc"));
        assertNull(MerchantReferralBountyService.parseInviter("0"));
        assertNull(MerchantReferralBountyService.parseInviter("-5"));
        assertEquals(Long.valueOf(100L), MerchantReferralBountyService.parseInviter(" 100 "));
    }

    @Test
    void noBounty_whenMerchantInvitedSelf() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(null);
        UmsMember merchant = new UmsMember();
        merchant.setId(MERCHANT);
        merchant.setCreateTime(daysFromNow(-1));
        merchant.setInviteUserId(String.valueOf(MERCHANT));   // 自己推自己
        when(umsMemberService.selectUmsMemberById(MERCHANT)).thenReturn(merchant);

        service.onVerification(MERCHANT);

        verify(bountyMapper, never()).insertBounty(any());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    // ==================== 退款追回 ====================

    private MerchantReferralBounty paidBounty() {
        MerchantReferralBounty b = pendingBounty();
        b.setStatus(MerchantReferralBounty.STATUS_PAID);
        b.setVerifiedHeadsSnapshot("11,22,33");
        b.setBountyAmount(new BigDecimal("30.00"));
        return b;
    }

    @Test
    void clawback_deductsThirty_whenSnapshotDropsBelowThreshold() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(paidBounty());
        when(bountyMapper.countStillValidAmong(any(), eq(MERCHANT))).thenReturn(2);   // 一条被退了
        when(bountyMapper.markClawedBackIfPaid(eq(BOUNTY_ID), any(), any())).thenReturn(1);
        when(umsMemberMapper.addBalance(anyLong(), any())).thenReturn(1);
        givenInviterBalance("50.00");

        service.onRefund(MERCHANT);

        verify(umsMemberMapper).addBalance(INVITER, new BigDecimal("-30.00"));
    }

    /**
     * ★ 快照的存在理由:后续核销补上缺口时,<b>重算会得 3、快照仍得 2</b>。
     *
     * <p>本用例锁死「传给 countStillValidAmong 的就是快照那 3 个 id」。若哪天有人把它
     * 改成重算(不传 id 列表),这条会红。
     */
    @Test
    void clawback_judgesBySnapshotIds_notByRecount() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(paidBounty());
        when(bountyMapper.countStillValidAmong(any(), eq(MERCHANT))).thenReturn(2);
        when(bountyMapper.markClawedBackIfPaid(eq(BOUNTY_ID), any(), any())).thenReturn(1);
        givenInviterBalance("50.00");

        service.onRefund(MERCHANT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(bountyMapper).countStillValidAmong(captor.capture(), eq(MERCHANT));
        assertEquals(Arrays.asList(11L, 22L, 33L), captor.getValue());
    }

    @Test
    void clawback_doesNothing_whenSnapshotStillIntact() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(paidBounty());
        when(bountyMapper.countStillValidAmong(any(), eq(MERCHANT))).thenReturn(3);   // 退的不是构成激活的那 3 条

        service.onRefund(MERCHANT);

        verify(bountyMapper, never()).markClawedBackIfPaid(anyLong(), any(), any());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
        verify(umsMemberMapper, never()).addBalance(anyLong(), any());
    }

    @Test
    void clawback_doesNothing_whenNeverPaid() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(pendingBounty());   // status=0

        service.onRefund(MERCHANT);

        verify(bountyMapper, never()).countStillValidAmong(any(), anyLong());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
        verify(umsMemberMapper, never()).addBalance(anyLong(), any());
    }

    /**
     * CAS 输了 = 已被追过 ⇒ 不能扣第二次(否则拓展人被扣 ¥60)。
     *
     * <p>⚠️ 同 {@link #activate_paysNothing_whenCasLost}:{@code givenInviterBalance} 不能省,
     * 省了这条也是假绿(拦住钱的会变成 clawbackBalance 里那个 inviter==null 的 early-return)。
     */
    @Test
    void clawback_deductsOnlyOnce_whenCasLost() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(paidBounty());
        when(bountyMapper.countStillValidAmong(any(), eq(MERCHANT))).thenReturn(2);
        when(bountyMapper.markClawedBackIfPaid(eq(BOUNTY_ID), any(), any())).thenReturn(0);
        givenInviterBalance("50.00");   // ← 只剩 CAS 拦着

        service.onRefund(MERCHANT);

        verify(umsMemberService, never()).addBalance(anyLong(), any());
        verify(umsMemberMapper, never()).addBalance(anyLong(), any());
    }

    /**
     * ★ 余额已提现 ⇒ 扣成负数,<b>不静默跳过</b>。
     *
     * <p>跳过就成了「记录说追回了、钱没回来」的假账。挂账抵后续合作款(定稿 D14)依赖
     * 收付通,尚未到位;当前如实扣、留流水,由对账脚本暴露负余额。
     */
    @Test
    void clawback_allowsNegativeBalance_whenAlreadyWithdrawn() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(paidBounty());
        when(bountyMapper.countStillValidAmong(any(), eq(MERCHANT))).thenReturn(0);
        when(bountyMapper.markClawedBackIfPaid(eq(BOUNTY_ID), any(), any())).thenReturn(1);
        when(umsMemberMapper.addBalance(anyLong(), any())).thenReturn(1);
        givenInviterBalance("0.00");   // 钱已提走

        service.onRefund(MERCHANT);

        verify(umsMemberMapper).addBalance(INVITER, new BigDecimal("-30.00"));

        ArgumentCaptor<UmsMemberBalanceDetail> captor = ArgumentCaptor.forClass(UmsMemberBalanceDetail.class);
        verify(balanceDetailMapper).insertUmsMemberBalanceDetail(captor.capture());
        assertEquals(new BigDecimal("-30.00"), captor.getValue().getAfterBalance());
    }

    /**
     * ★ 2026-07-25 负控:验证 clawbackBalance 真的调用了 mapper 而不是被校验挡住(资金核心
     * 曾在此处栽过——CAS 已置「已追回」,余额却从没扣,只是被 mock 掩盖)。
     *
     * <p>模拟 mapper 层 addBalance 返回 0(如并发下目标行被删/更新 0 行)时,不能留下
     * 「状态说追回了、流水却说钱动了」的假账 —— 断言 ledger 从未被写入,异常被
     * {@code onRefund} 的外层吞掉不影响退款主流程。
     */
    @Test
    void clawback_writesNoLedger_whenMapperAddBalanceReturnsZero() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(paidBounty());
        when(bountyMapper.countStillValidAmong(any(), eq(MERCHANT))).thenReturn(0);
        when(bountyMapper.markClawedBackIfPaid(eq(BOUNTY_ID), any(), any())).thenReturn(1);
        when(umsMemberMapper.addBalance(anyLong(), any())).thenReturn(0);   // mapper 层失败
        givenInviterBalance("50.00");

        service.onRefund(MERCHANT);   // 不抛出去,onRefund 吞异常

        verify(umsMemberMapper).addBalance(INVITER, new BigDecimal("-30.00"));
        verify(balanceDetailMapper, never()).insertUmsMemberBalanceDetail(any());
    }

    // ==================== 流水可追溯(对账的前提) ====================

    /**
     * ★ 发钱必须留 event_type=91 + event_id=赏金 id 的流水。
     *
     * <p>现有 inviter 佣金查不清的病根就是没有这层记录 —— 钱到了余额里,没人知道为什么。
     * 对账脚本靠这两个字段把「库里说发了」和「余额真动了」join 起来。
     */
    @Test
    void pay_writesTraceableLedgerEntry() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(pendingBounty());
        givenHeads(3);
        when(bountyMapper.countPaidByInviter(INVITER)).thenReturn(0);
        when(bountyMapper.markPaidIfPending(eq(BOUNTY_ID), any(), any(), any(), any(), any())).thenReturn(1);
        givenInviterBalance("5.00");

        service.onVerification(MERCHANT);

        ArgumentCaptor<UmsMemberBalanceDetail> captor = ArgumentCaptor.forClass(UmsMemberBalanceDetail.class);
        verify(balanceDetailMapper).insertUmsMemberBalanceDetail(captor.capture());
        UmsMemberBalanceDetail d = captor.getValue();
        assertEquals(Long.valueOf(INVITER), d.getMemberId());
        assertEquals(Long.valueOf(MerchantReferralBountyService.EVENT_TYPE_REFERRAL_BOUNTY), d.getEventType());
        assertEquals(Long.valueOf(BOUNTY_ID), d.getEventId());
        assertEquals(new BigDecimal("30.00"), d.getChangeBalance());
        assertEquals(new BigDecimal("35.00"), d.getAfterBalance());
    }

    /** ★ 快照必须落库 —— 它是 clawback 的唯一依据,不落就永远追不回。 */
    @Test
    void pay_persistsSnapshotAndFreezeWindow() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(pendingBounty());
        givenHeads(3);
        when(bountyMapper.countPaidByInviter(INVITER)).thenReturn(0);
        when(bountyMapper.markPaidIfPending(eq(BOUNTY_ID), any(), any(), any(), any(), any())).thenReturn(1);
        givenInviterBalance("0.00");

        long before = System.currentTimeMillis();
        service.onVerification(MERCHANT);

        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Date> frozenUntil = ArgumentCaptor.forClass(Date.class);
        verify(bountyMapper).markPaidIfPending(eq(BOUNTY_ID), any(), snapshot.capture(),
                eq(new BigDecimal("30.00")), any(), frozenUntil.capture());
        assertEquals("11,22,33", snapshot.getValue());

        // 冻结到 48h 后(容差 1 分钟,别拿 equals 比时间)
        long expected = before + 48L * 3600 * 1000;
        long actual = frozenUntil.getValue().getTime();
        assertTrue(Math.abs(actual - expected) < 60_000,
                "frozenUntil 应为 paid_at + 48h,实际差 " + (actual - expected) + "ms");
    }

    // ==================== 赏金不能连累核销 ====================

    /**
     * ★ 赏金炸了绝不能让核销失败。
     *
     * <p>核销是收钱路径,赏金是实验性冷启动功能。这条断言锁死「onVerification 永不抛」。
     */
    @Test
    void onVerification_swallowsExceptions_neverBreaksVerification() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT))
                .thenThrow(new RuntimeException("db down"));

        service.onVerification(MERCHANT);   // 不抛即通过
    }

    @Test
    void onRefund_swallowsExceptions_neverBreaksRefund() {
        when(bountyMapper.selectByMerchantMemberId(MERCHANT))
                .thenThrow(new RuntimeException("db down"));

        service.onRefund(MERCHANT);
    }

    @Test
    void nullMerchantId_isNoOp() {
        service.onVerification(null);
        service.onRefund(null);

        verify(bountyMapper, never()).selectByMerchantMemberId(any());
    }
}
