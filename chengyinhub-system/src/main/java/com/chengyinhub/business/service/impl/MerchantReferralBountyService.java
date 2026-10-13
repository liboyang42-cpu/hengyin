package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.domain.MerchantReferralBounty;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.mapper.MerchantReferralBountyMapper;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IUmsMemberService;

/**
 * 拓展人激活赏金(冷启动)。
 *
 * <p><b>目的</b>:有人帮平台拓来商家,该商家真跑起来后给拓展人一次性 ¥30。
 * 参数 2026-07-17 拍板,见《后台发主题与商家报名_拆除复制层_施工图_20260717.md》§3.3.1。
 *
 * <p><b>为什么是「一次性 + 激活闸 + 封顶」而不是持续分成</b>(§3.2 调研):
 * 城瘾抽 GMV、平台净利仅约 2.4%。原实现是「商家分成 × 2%,每次结算、永久」=
 * GMV×1.4% ≈ <b>吃掉平台净利的 58%,且永久</b> —— 数学上不成立。
 * DoorDash(同为抽 GMV 的薄毛利平台)用的正是一次性 $1000 + 激活闸(60 天 15 单)+ 封顶 10 家;
 * 持续分成只出现在 SaaS(毛利 75-90%)与订阅费分成里。
 *
 * <p><b>为什么钱由平台出、不从商家分成里切</b>:Shopify 给 partner 的 20% 是从**它自己收的订阅费**
 * 里出,商家成本不变。从商家收入里切 = 让商家为自己被招募买单,且是隐性的。
 *
 * <p><b>★ 与旧实现的根本区别</b>:旧的 inviter 佣金挂在 {@code sharingAmount} 上,而那是一笔
 * <b>死账</b>({@code sharing_amount} 全仓零消费者)。基数是假的 ⇒ 算出来的真钱没有依据。
 * 新模型只认「核销」这个真实信号,与结算<b>完全解耦</b>。
 *
 * @author chengyinhub
 */
@Service
public class MerchantReferralBountyService
{
    private static final Logger log = LoggerFactory.getLogger(MerchantReferralBountyService.class);

    /** 赏金金额 ¥30(2026-07-17 由 ¥300 下调)。落库时落值不落配置:改价不追溯改写历史。 */
    public static final BigDecimal BOUNTY_AMOUNT = new BigDecimal("30.00");
    /** 激活窗口:商家注册起 30 天 */
    public static final int WINDOW_DAYS = 30;
    /** 激活闸:该商家自己扫码核销的去重人头数 */
    public static final int ACTIVATION_HEADS = 3;

    /**
     * ★ 反套利闸:窗口内合计**实付**必须 ≥ 赏金额,否则不激活。
     *
     * <p><b>为什么下限绑定 BOUNTY_AMOUNT 而不是写死一个数</b>:这样套利在数学上永远不成立 ——
     * 要套走 ¥30 得先真付 ¥30 进来(还搭上微信手续费,且那笔钱本身还要走结算分给商家和发布者)。
     * 哪天赏金调成 ¥50,门槛自动跟上,不会留下一个忘了改的常数。
     *
     * <p><b>为什么必须有</b>:{@code payment_status=2} 不等于真付过钱。生产实证(2026-07-17):
     * reg#47 与 reg#71 <b>实付 0.00 而 payment_status=2</b>;72 张票里最低票价 0.00、7 张 ≤¥1。
     * 没有这道闸,建个 0 元票主题 + 3 个小号自扫自核 = <b>零成本套走赏金</b>,封顶 20 家也只是
     * 把单账号上限抬到 ¥600,而账号可以再开。
     *
     * <p><b>为什么防线在这层而不在 clawback</b>:clawback 是<b>事后</b>补救 —— 它要等退款真的发生,
     * 而刷子根本不会去退款(他要的就是钱留下)。事前拦截才治本。
     *
     * <p>⚠️ 别误读成「clawback 没用」:PR #95(P0-14)把 {@code ensureRefundable} 改成
     * 「{@code manualReview} 的放行,运营就是那个人工」之后,<b>③ EXPLORE_PASS 的已核销单经后台审核
     * 可退</b> ⇒ clawback 是活路径,48h 冻结也真有意义。两道闸各管一头:
     * 本闸挡「凭 0 元票凭空造人头」,clawback 挡「真付了钱、事后退掉」。
     */
    public static final BigDecimal ACTIVATION_MIN_PAID = BOUNTY_AMOUNT;
    /** 单个拓展人封顶家数 */
    public static final int INVITER_CAP = 20;
    /** 入账后冻结小时数(与定稿 §2.3 的 48h 试点退款窗口对齐) */
    public static final int FREEZE_HOURS = 48;

    /** 余额流水 event_type:拓展人激活赏金 */
    public static final long EVENT_TYPE_REFERRAL_BOUNTY = 91L;

    @Autowired
    private MerchantReferralBountyMapper bountyMapper;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private UmsMemberBalanceDetailMapper balanceDetailMapper;

    /**
     * ★ 2026-07-25 修复:追回扣款不能走 {@code IUmsMemberService.addBalance}——那层校验
     * {@code balance<=0} 即抛 {@link IllegalArgumentException}(只许加钱不许扣钱),负数必抛。
     * 此前 {@link #clawbackBalance} 调用它传负数,异常被 {@link #onRefund} 的
     * {@code catch(RuntimeException)} 吞掉、只打日志 —— CAS 已把状态置成「已追回」,
     * 余额却从未真的被扣,是本项目反复警惕的那种「记录说追回了、钱没回来」假账,
     * 只是被单测的 mock 掩盖了(mock 不会执行真实校验逻辑)。
     * 直接注入 mapper 绕开这层校验,不改动 {@code IUmsMemberService.addBalance} 本身
     * ——那是被广泛复用的公共 API,风险面不该为了一个扣款场景放大。
     */
    @Autowired
    private UmsMemberMapper umsMemberMapper;

    /**
     * 核销发生后调用:判断该商家是否刚好达成激活,达成则给拓展人发一次性赏金。
     *
     * <p><b>调用方必须包 try/catch</b> —— 本方法自己也全包了,但核销是核心路径,
     * 赏金是实验性的冷启动功能,<b>任何情况下都不能让它连累核销</b>。
     *
     * @param merchantMemberId 扫码核销的商家
     */
    public void onVerification(Long merchantMemberId)
    {
        try
        {
            tryActivate(merchantMemberId);
        }
        catch (RuntimeException e)
        {
            // 赏金不该连累核销。这里吞掉并告警,由对账脚本兜底发现落差。
            log.error("[ReferralBounty] activate failed merchantMemberId={}", merchantMemberId, e);
        }
    }

    private void tryActivate(Long merchantMemberId)
    {
        if (merchantMemberId == null)
        {
            return;
        }

        MerchantReferralBounty bounty = bountyMapper.selectByMerchantMemberId(merchantMemberId);
        if (bounty == null)
        {
            bounty = tryCreatePending(merchantMemberId);
            if (bounty == null)
            {
                return;   // 没有推荐人 / 推荐关系不可用 ⇒ 本商家不参与赏金
            }
        }
        if (bounty.getStatus() == null || bounty.getStatus() != MerchantReferralBounty.STATUS_PENDING)
        {
            return;   // 已发/已追/已失效 —— uk_merchant 保证一辈子只发一次
        }

        Date now = new Date();
        if (now.after(bounty.getWindowExpiresAt()))
        {
            // 超窗未达标:置失效(专用 CAS,别复用 markPaidIfPending —— 那会置成「已发放」却不发钱 = 假账)
            bountyMapper.markExpiredIfPending(bounty.getId());
            log.info("[ReferralBounty] window expired merchantMemberId={}", merchantMemberId);
            return;
        }

        int heads = bountyMapper.countVerifiedHeadsByMerchantInWindow(
                merchantMemberId, bounty.getMerchantRegisteredAt(), bounty.getWindowExpiresAt());
        if (heads < ACTIVATION_HEADS)
        {
            return;   // 还没到闸
        }

        // ★ 反套利闸:光有人头不算数,得有真钱进来。见 ACTIVATION_MIN_PAID 的说明 ——
        //   实付 0 元的报名 payment_status 照样是 2,只数人头 = 零成本可刷。
        BigDecimal paidInWindow = bountyMapper.sumVerifiedPaidByMerchantInWindow(
                merchantMemberId, bounty.getMerchantRegisteredAt(), bounty.getWindowExpiresAt());
        if (paidInWindow == null || paidInWindow.compareTo(ACTIVATION_MIN_PAID) < 0)
        {
            log.info("[ReferralBounty] 人头够但实付不够,不激活 merchantMemberId={} heads={} 合计实付={} 需≥{}",
                    merchantMemberId, heads, paidInWindow, ACTIVATION_MIN_PAID);
            return;
        }

        // 封顶:只数该拓展人**已发放**的家数(已追回的名额应释放;待激活的不算,否则拉僵尸就能占满)
        int paid = bountyMapper.countPaidByInviter(bounty.getInviterMemberId());
        if (paid >= INVITER_CAP)
        {
            log.info("[ReferralBounty] inviter cap reached inviterMemberId={} paid={}",
                    bounty.getInviterMemberId(), paid);
            return;
        }

        // ★★ 收款人必须在 CAS **之前**查出来 —— 顺序错了就是假账。
        //
        // 本来写反过 :置位在前、payBounty 里再查 inviter,查不到就 log.error + return
        // ⇒ 库里 status=1「已发放」,余额一分没动。而这恰恰是本轨立志要消灭的那个病
        //   (我甚至为它写了三处注释,却把它留在了自己的代码里)。
        //
        // ⚠️ 且这条路**很好走**:invite_user_id 是自由文本 VARCHAR、**没有外键**,
        //    parseInviter 只校验「能解析成正数」,不校验「这个人存在」。填个 99999 就到这儿了。
        //
        // 查不到收款人 ⇒ 什么都不置,留在 status=0。下次核销再试;真是脏数据就等超窗置 3。
        UmsMember inviter = umsMemberService.selectUmsMemberById(bounty.getInviterMemberId());
        if (inviter == null)
        {
            log.error("[ReferralBounty] 拓展人不存在,不置位不发钱 inviterMemberId={} merchantMemberId={} "
                    + "(invite_user_id 无外键,可能是脏数据)", bounty.getInviterMemberId(), merchantMemberId);
            return;
        }

        List<Long> regIds = bountyMapper.selectVerifiedRegistrationIdsByMerchantInWindow(
                merchantMemberId, bounty.getMerchantRegisteredAt(), bounty.getWindowExpiresAt(), ACTIVATION_HEADS);
        String snapshot = join(regIds);

        Date frozenUntil = plusHours(now, FREEZE_HOURS);
        // ★ CAS:仅 status=0 可置 1。并发/重复触发只有一方 affected=1 ⇒ 只发一次钱。
        //   先读后写会让两个线程都读到 0、都发钱。
        if (bountyMapper.markPaidIfPending(bounty.getId(), now, snapshot, BOUNTY_AMOUNT, now, frozenUntil) == 0)
        {
            return;   // 别人已经发过了
        }

        payBounty(inviter, BOUNTY_AMOUNT, bounty.getId(), merchantMemberId);
        log.info("[ReferralBounty] paid inviterMemberId={} merchantMemberId={} amount={} snapshot={}",
                bounty.getInviterMemberId(), merchantMemberId, BOUNTY_AMOUNT, snapshot);
    }

    /**
     * ★ 该会员当前被冻结、<b>不可提现</b>的赏金总额。提现路径必须先刨掉它再判余额够不够。
     *
     * <p><b>⚠️ 本方法刻意不吞异常 —— 与 {@link #onVerification} / {@link #onRefund} 相反</b>。
     * 那两个是「赏金别连累核销/退款」⇒ 失败要 fail-open;这里是「冻结额算不出来时不许放钱出去」
     * ⇒ 必须 fail-close。查询挂了却返回 0,等于冻结静默失效、钱当场提走,而这正是冻结要防的。
     * 两个方向都对,别把它们统一成一种写法。
     *
     * @return 冻结额,≥0
     */
    public BigDecimal frozenAmount(Long memberId)
    {
        if (memberId == null)
        {
            return BigDecimal.ZERO;
        }
        BigDecimal frozen = bountyMapper.sumFrozenByInviter(memberId, new Date());
        return frozen == null ? BigDecimal.ZERO : frozen;
    }

    /**
     * 退款发生后调用:若该退款打掉了构成激活的核销、使有效核销 &lt; 3,则追回赏金。
     *
     * <p><b>为什么靠快照而不是重算</b>:重算会被后续核销掩盖 —— 当初那 3 次里有一次退了,
     * 但第 4 次核销补上了缺口,重算仍得 3 ⇒ 该追的追不到。快照框死了「当初凭哪 3 条发的钱」。
     *
     * <p><b>调用方必须包 try/catch</b> —— 本方法自己也全包了。退款是核心路径,不能被赏金连累。
     *
     * @param merchantMemberId 被退款报名所涉的核销商家
     */
    public void onRefund(Long merchantMemberId)
    {
        try
        {
            tryClawback(merchantMemberId);
        }
        catch (RuntimeException e)
        {
            log.error("[ReferralBounty] clawback failed merchantMemberId={}", merchantMemberId, e);
        }
    }

    private void tryClawback(Long merchantMemberId)
    {
        if (merchantMemberId == null)
        {
            return;
        }
        MerchantReferralBounty b = bountyMapper.selectByMerchantMemberId(merchantMemberId);
        if (b == null || b.getStatus() == null || b.getStatus() != MerchantReferralBounty.STATUS_PAID)
        {
            return;   // 没发过就没什么可追
        }
        List<Long> snapshot = parseSnapshot(b.getVerifiedHeadsSnapshot());
        if (snapshot.isEmpty())
        {
            return;
        }
        int stillValid = bountyMapper.countStillValidAmong(snapshot, merchantMemberId);
        if (stillValid >= ACTIVATION_HEADS)
        {
            return;   // 激活依据仍然成立
        }

        // ★ 同 tryActivate:扣款对象必须在 CAS 之前查出来。置了 status=2「已追回」却没扣到钱,
        //   就是「记录说追回了、钱没回来」—— 假账的另一个方向。
        UmsMember inviter = umsMemberService.selectUmsMemberById(b.getInviterMemberId());
        if (inviter == null)
        {
            log.error("[ReferralBounty] 拓展人不存在,不置位不扣款 inviterMemberId={} merchantMemberId={}",
                    b.getInviterMemberId(), merchantMemberId);
            return;
        }

        String reason = "构成激活的核销有效数降至 " + stillValid + "(<" + ACTIVATION_HEADS + "),快照=" + b.getVerifiedHeadsSnapshot();
        if (bountyMapper.markClawedBackIfPaid(b.getId(), new Date(), reason) == 0)
        {
            return;   // 已被追过
        }
        clawbackBalance(inviter, b.getBountyAmount(), b.getId(), reason);
        log.warn("[ReferralBounty] clawed back inviterMemberId={} merchantMemberId={} amount={} reason={}",
                b.getInviterMemberId(), merchantMemberId, b.getBountyAmount(), reason);
    }

    /**
     * 扣回赏金。
     *
     * <p>⚠️ <b>余额可能已被提现</b>:48h 冻结期覆盖了定稿 §2.3 的试点退款窗口,绝大多数追回
     * 发生在钱离开平台之前。但若冻结期已过且已提现,余额会被扣成负数 —— 这正是定稿 D14
     * 「不足部分挂账抵后续合作款」要处理的情况,<b>依赖收付通(D11),尚未到位</b>。
     * 当前实现:如实扣(允许为负),流水留痕,由对账脚本暴露负余额。<b>别静默跳过</b> ——
     * 跳过就成了「记录说追回了、钱没回来」的假账。
     */
    private void clawbackBalance(UmsMember inviter, BigDecimal amount, Long bountyId, String reason)
    {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0)
        {
            return;
        }
        Long inviterId = inviter.getId();
        BigDecimal before = inviter.getBalance() != null ? inviter.getBalance() : BigDecimal.ZERO;
        // 直接调 mapper,不走 umsMemberService.addBalance——那层拒绝负数,见字段注释。
        int rows = umsMemberMapper.addBalance(inviterId, amount.negate());
        if (rows == 0)
        {
            throw new IllegalStateException("拓展人赏金追回扣款失败 inviterId=" + inviterId);
        }

        UmsMemberBalanceDetail d = new UmsMemberBalanceDetail();
        d.setMemberId(inviterId);
        d.setCurrentBalance(before);
        d.setAfterBalance(before.subtract(amount));
        d.setChangeBalance(amount.negate());
        d.setChangeType(2L);
        d.setEventType(EVENT_TYPE_REFERRAL_BOUNTY);
        d.setEventId(bountyId);
        d.setChangeReason("拓展人激活赏金追回:" + reason);
        d.setCreateTime(new Date());
        balanceDetailMapper.insertUmsMemberBalanceDetail(d);
    }

    /**
     * 建待激活占位。推荐关系来自 {@code ums_member.invite_user_id}。
     *
     * <p>⚠️ 该列是 <b>String</b> 不是数字列。既有代码({@code CmsTopicServiceImpl})是裸
     * {@code Long.valueOf(...trim())},脏数据(空串/非数字)会抛 —— 这里显式校验,不照抄。
     */
    private MerchantReferralBounty tryCreatePending(Long merchantMemberId)
    {
        UmsMember merchant = umsMemberService.selectUmsMemberById(merchantMemberId);
        if (merchant == null || merchant.getCreateTime() == null)
        {
            return null;
        }
        Long inviterId = parseInviter(merchant.getInviteUserId());
        if (inviterId == null || inviterId.equals(merchantMemberId))
        {
            return null;   // 无推荐人,或自己推自己
        }

        MerchantReferralBounty b = new MerchantReferralBounty();
        b.setMerchantMemberId(merchantMemberId);
        b.setInviterMemberId(inviterId);
        b.setMerchantRegisteredAt(merchant.getCreateTime());
        b.setWindowExpiresAt(plusDays(merchant.getCreateTime(), WINDOW_DAYS));
        b.setStatus(MerchantReferralBounty.STATUS_PENDING);
        try
        {
            bountyMapper.insertBounty(b);
            return b;
        }
        catch (RuntimeException e)
        {
            // uk_merchant 撞键 = 并发下别人已占位,不是错误。回读即可。
            return bountyMapper.selectByMerchantMemberId(merchantMemberId);
        }
    }

    /** invite_user_id 是 String 列,可能是空串/空白/非数字/0 —— 全部视为「无推荐人」。 */
    public static Long parseInviter(String raw)
    {
        if (raw == null)
        {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty())
        {
            return null;
        }
        try
        {
            long v = Long.parseLong(s);
            return v > 0 ? Long.valueOf(v) : null;
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    /** 收款人由调用方在 CAS 前查好传进来 —— 见 tryActivate 里那段「顺序错了就是假账」。 */
    private void payBounty(UmsMember inviter, BigDecimal amount, Long bountyId, Long merchantMemberId)
    {
        Long inviterId = inviter.getId();
        BigDecimal before = inviter.getBalance() != null ? inviter.getBalance() : BigDecimal.ZERO;
        umsMemberService.addBalance(inviterId, amount);

        // 流水:event_id 挂赏金记录 id ⇒ 对账时两边能 join 上。
        // 当前 inviter 佣金之所以查不清,病根就是没有这层可追溯的记录。
        UmsMemberBalanceDetail d = new UmsMemberBalanceDetail();
        d.setMemberId(inviterId);
        d.setCurrentBalance(before);
        d.setAfterBalance(before.add(amount));
        d.setChangeBalance(amount);
        d.setChangeType(1L);
        d.setEventType(EVENT_TYPE_REFERRAL_BOUNTY);
        d.setEventId(bountyId);
        d.setChangeReason("拓展人激活赏金(商家 " + merchantMemberId + " 达成 " + ACTIVATION_HEADS + " 次核销)");
        d.setCreateTime(new Date());
        balanceDetailMapper.insertUmsMemberBalanceDetail(d);
    }

    private static String join(List<Long> ids)
    {
        if (ids == null || ids.isEmpty())
        {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++)
        {
            if (i > 0)
            {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    static List<Long> parseSnapshot(String snapshot)
    {
        List<Long> out = new ArrayList<>();
        if (snapshot == null || snapshot.trim().isEmpty())
        {
            return out;
        }
        for (String p : snapshot.split(","))
        {
            String s = p.trim();
            if (s.isEmpty())
            {
                continue;
            }
            try
            {
                out.add(Long.valueOf(s));
            }
            catch (NumberFormatException ignore)
            {
                // 快照脏了就当那条不存在;宁可少追也别抛
            }
        }
        return out;
    }

    private static Date plusDays(Date d, int days)
    {
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTime();
    }

    private static Date plusHours(Date d, int hours)
    {
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        c.add(Calendar.HOUR_OF_DAY, hours);
        return c.getTime();
    }
}
