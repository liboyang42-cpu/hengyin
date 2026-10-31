package com.chengyinhub.web.service.registration;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.vo.ExploreChapterRedemptionResult;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.ExploreTicketUsePolicy;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/** Existing registration scan admission, routing and original-result readback.
 * No outer transaction: registration/perk/seat commands keep their original transactional boundaries;
 * the original points and growth delivery remain after those calls. No ticket issuance or money rules.
 * Historical controller references below describe the original location; this bean now runs those checks. */
@Service
public class RegistrationRedemptionApplication {
    private static final Logger logger = LoggerFactory.getLogger(RegistrationRedemptionApplication.class);

    /** Frozen authentication read, including failure, so each original catch stays at its original layer.
     * Carries no HTTP/controller reference and does not re-read a different actor during routing. */
    public record Actor(Long memberId, boolean unavailable) {
        public Long currentMemberId() {
            if (unavailable) throw new IllegalStateException("登录上下文不可用");
            return memberId;
        }
    }

    @Autowired
    private ICmsRegistrationService registrationService;

    @Autowired
    private IMerchantAccessService merchantAccessService;

    @Autowired
    private com.chengyinhub.business.mapper.CoopOrderMapper coopOrderMapper;

    @Autowired
    private IClubMemberService clubMemberService;

    @Autowired
    private ICmsTopicService topicService;

    @Autowired
    private ICmsActivityService activityService;

    @Autowired
    private ICmsRegistrationMerchantService registrationMerchantService;

    @Autowired
    private com.chengyinhub.business.service.IPerkLedgerService perkLedgerService;

    @Autowired
    private IExploreCollectibleService exploreCollectibleService;

    @Autowired
    private ICmsTopicChapterService topicChapterService;

    @Autowired
    private ICmsActivityService cmsActivityService;

    @Autowired
    private com.chengyinhub.business.service.IPointsAwardService pointsAwardService;

    @Autowired
    private com.chengyinhub.business.service.IGrowthService growthService;

    /** 核销码密钥:与 ApiVerifyController 签发同源(默认 JWT secret,可用 verify.dyncode-secret 覆盖) */
    @Value("${verify.dyncode-secret:${token.secret}}")
    private String dynSecret;

    @Autowired
    private com.chengyinhub.web.service.verify.RegistrationDynCodeLedger dynCodeLedger;

    /** 新用途核销:验签与有效期之外,再拒已被同报名单同类型更新码取代的旧码(V-02)。只读回执仍走 verifyForReceipt。 */
    private VerifyDynCodeUtil.Result rejectSuperseded(VerifyDynCodeUtil.Result r, String code) {
        if (r.valid && dynCodeLedger != null && dynCodeLedger.superseded(code)) {
            return VerifyDynCodeUtil.Result.rejected(com.chengyinhub.web.service.verify.RegistrationDynCodeLedger.SUPERSEDED);
        }
        return r;
    }

    /**
     * 把扫到的 code 解析成 CmsRegistration(BE-11):先试动态签名码(verify 通过→按 regId 查),
     * v1 动态码失败保留校验原因；只有旧静态码进入 verificationCode 查询。
     * 选择章节/据点期间过期不会被误报为无效码，不改核销/结算主逻辑。
     */
    private CmsRegistration resolveRegistrationByScanCode(String code) {
        VerifyDynCodeUtil.Result r = rejectSuperseded(VerifyDynCodeUtil.verify(code, System.currentTimeMillis(), dynSecret), code);
        if (r.valid && r.regId != null) {
            return registrationService.selectCmsRegistrationById(r.regId);
        }
        if (code != null && code.startsWith("v1.")) {
            throw new ServiceException(r.reason);
        }
        return registrationService.selectByverificationCode(code);
    }

    /**
     * F-R2F-01:俱乐部场次的票不能在开场前几天被整单核销 —— 核销即「已到场/已交付」，
     * 提前核销后场次取消就会落入「整单全核销不退」。只约束俱乐部场次的整单核销，③ 按章核销与普通活动不变。
     *
     * @return null = 放行；非 null = 直接返回给扫码方的提示
     */
    public static AjaxResult clubSessionVerificationTooEarly(CmsActivity activity, Date now) {
        String refusal = com.chengyinhub.business.service.RegistrationSeatService.activityVerificationTooEarly(activity, now);
        return refusal == null ? null : AjaxResult.error(refusal);
    }

    /**
     * ★★ [资损·根因] {@code type} 与 {@code registration.owner_type} 必须一致。
     *
     * <p><b>为什么这是根因而不是防御性编程</b>:{@code scanQrCode(Actor actorContext, String type, String code)} 的
     * {@code type} 是**扫码方传的请求参数**,而 {@code resolveRegistrationByScanCode} 只从 code 解析单子、
     * **从不回头核对两者**。而两个分支对同一个 {@code owner_id} 的解释**根本不同**:
     * <ul>
     *   <li>{@code type="activity"} 分支:{@code selectCmsActivityById(reg.getOwnerId())} ⇒ ownerId 当 <b>activityId</b></li>
     *   <li>{@code type="topic"} 分支:{@code setTopicId(reg.getOwnerId())} ⇒ ownerId 当 <b>topicId</b></li>
     * </ul>
     * ⇒ 一旦 type 与 ownerType 不匹配,ownerId 就被**按错误的语义**拿去查另一张表。
     * {@code cms_topic} 与 {@code cms_activity} 是两条独立自增序列、id 区间大量重叠 ⇒ 撞上是常态不是巧合。
     *
     * <p><b>具体怎么变成资损</b>:一张 owner_type=2 的单(①活动票 或 ③探索票)被以 {@code type="topic"} 扫,
     * 进 topic 分支 → {@code setTopicId(activityId)} → 若扫码商家恰好在 {@code topic#activityId} 上中标
     * → {@code list.size()>0} → {@code verifyAndSettle(reg, memberId, memberId)}
     * ⇒ **整单票款结算给这个跟本单毫无关系的商家**。反方向(owner_type=1 的单以 type="activity" 扫)同型。
     *
     * <p>⚠️ 这个洞在 master 上就有,不是本次引入 —— 但本次一度用「③ 定死是 owner_type=2 ⇒ 到不了 topic 分支」
     * 当理由拆掉过 topic 分支的 ③ 守卫。<b>那个不变量是假的</b>:决定走哪个分支的是**客户端字符串**,
     * 不是单子的 ownerType。在两个分支各补一道 ③ 守卫只是给假不变量打补丁;
     * 真正该做的是把「type 必须与 ownerType 一致」这条**在入口钉死一次**,①③ 的同型洞一起消掉。
     *
     * @return null = 一致(放行);非 null = 该直接返回给前端的错误
     */
    private AjaxResult assertScanTypeMatchesOwner(String type, CmsRegistration registration) {
        Long ownerType = registration.getOwnerType();
        if (ownerType == null) {
            return AjaxResult.error("订单数据异常,无法核销");   // fail-closed:判不出归属就别让它进任一分支
        }
        boolean isActivityOrder = ownerType.longValue() == 2L;
        if (isActivityOrder != "activity".equals(type)) {
            return AjaxResult.error("核销类型与订单不匹配");
        }
        return null;
    }

    /**
     * [P0-14] 这张票是不是 ③ 探索票。
     *
     * <p><b>双读回落,不许写「新单必非空」这类判定</b>:purchase_kind 是下单即固化的真源
     * (主题的 product_type 事后可能被改,已售订单必须按买的时候算),但它有三个 NULL 来源 ——
     * 存量未回填 / 后台手工建单走 insertCmsRegistration 无上下文 / 独立活动无 topicId。
     * ⇒ 非空即认;为空则回落主题真源 product_type;两级都判不出 ⇒ false ⇒ 走 ①② 原路。
     * fail-safe 方向:判不清就当 ①,不会把存量单误切到 ③ 的按章核销上而永远核销不了。
     */
    private boolean isExplorePass(CmsRegistration registration, CmsActivity activity) {
        if (registration == null) {
            return false;
        }
        Integer pk = registration.getPurchaseKind();
        if (pk != null) {
            return pk == PurchaseKind.EXPLORE_PASS;
        }
        // ⚠️ 主题真源经 **activity.topicId** 拿,不是 registration.getOwnerId() ——
        // ③ 的 ownerId 是 activityId,拿它去查主题表会查到别的主题或 null。
        if (activity == null || activity.getTopicId() == null) {
            return false;
        }
        CmsTopic t = topicService.selectCmsTopicById(activity.getTopicId());
        return t != null && t.getProductType() != null
                && t.getProductType() == ProductType.FREE_EXPLORE;
    }

    /**
     * [P0-14] ③ 按章核销。
     *
     * <p><b>chapterId 后端反推</b>(零前端改动、零新 SQL):{该商家在本主题的生效供给章节}
     * ∩ {该票尚欠的章节}。交集三分,≥2 时**返回候选让商家选,绝不默默取第一个** ——
     * uk_offer_merchant_chapter 只约束「一商家一章一行」,不阻止一商家承接多章。
     *
     * <p><b>这里的 merchantOwnerMemberId = mms_merchant.member_id</b>,由统一商家权限上下文解析；
     * <b>operatorMemberId = actorContext.currentMemberId()</b>,只记录真实操作人。绝不能把 principal、
     * mms_merchant.id 与 owner memberId 混写。ApiVerifyController 的 roam_poi.merchant_id
     * 才是 mms_merchant.id；两张表同名列不同语义,搞反了
     * findLiveOffer 恒返 null → 「该商家在本章节没有生效的权益供给」,静默核销不了。
     *
     * <p><b>鉴权在这一层</b>:redeemChapter 方法内不做鉴权(findLiveOffer 只按 merchantId 过滤 offer,
     * 不校验调用者身份)⇒ IDOR 防线必须在 Controller。这里的 memberId 来自登录态,
     * 且反推只认「该商家自己的生效 offer」,越权扫别人的章节会落到交集为空。
     *
     * <p><b>核销留痕走 service 的事务收口方法</b> {@code redeemExploreChapterAndRecord}:
     * 候选集复核 + redeem + 台账 + 置位,四步同一事务。⚠️ 本方法是**无事务的 controller**,
     * 绝不能在这里顺序调多个 {@code @Transactional} 服务 —— 那是多个独立事务,
     * redeem 提交后台账再抛就会出现「配额扣了、entitlement 用尽了、台账没有」且再也重扫不了。
     *
     * <p><b>③ 的入账口径 = 平台应付台账</b>(D21:计酬档票款全额归平台,给商家的人头费是平台的
     * 采购成本 ⇒ 应付台账 + 对公打款 + 商家开票,不是分账、不碰二清)。逐笔计提落在
     * {@code redeemExploreChapterAndRecord} 的同一事务内,连费率一起快照。
     *
     * <p>⚠️ <b>刻意不调 {@code recordPaidRegistration}</b>:它是「整单票款给单一 payee」的口径,
     * 而 ③ 一张票跨 N 家核销 ⇒ 照搬 = 最后扫的那家白拿全额 = 资损。**这条别为了「接上入账」去动它。**
     */
    private AjaxResult redeemExploreChapter(CmsRegistration registration, Long topicId,
                                             Long merchantOwnerMemberId, Long operatorMemberId,
                                             String actorRoleCode) {
        if (topicId == null) {
            // 独立活动(无主题真源)不可能有 ③ 票 —— 走到这儿说明数据不一致,别静默当成「没供给」。
            return AjaxResult.error("该活动未关联路线,无法按章核销");
        }
        Map<Long, Long> scannable = perkLedgerService.resolveScannableChapters(
                registration.getId(), topicId, merchantOwnerMemberId);
        List<Long> candidates = new ArrayList<>(scannable.keySet());
        if (candidates.isEmpty()) {
            return AjaxResult.error("您在本路线没有可核销权益或有效核销回执");
        }
        if (candidates.size() > 1) {
            Map<String, Object> d = new HashMap<>();
            d.put("needChapterChoice", true);
            // chapterIds 保留:老客户端(以及不需要名字的调用方)仍按它工作。新增的 chapters 是同一份
            // 候选集带上名字,前端优先用它 —— 两个 key 必须同源同序,别让它们能各说各话。
            d.put("chapterIds", candidates);
            d.put("chapters", describeChapters(topicId, scannable));
            // ⚠️ [P0-2] **必须是 error,且永远不许改回 success** —— 选章未完成 = 核销没发生。
            //
            // 前后端契约:前端判据是 **data.needChapterChoice**,不是 code。定成这样是因为小程序
            // 发版不原子、老客户端会长期滞留 —— 一旦这里返 success,任何只判 code==200 的客户端
            // 都会弹「验票成功」放人走,而 entitlement 从没消费 ⇒ 同一张票可反复核销。
            // 判据落在载荷上,新老两代客户端才能共存。⇒ needChapterChoice/chapterIds 两个 key
            // 也是契约的一部分,改名 = 前端静默失效。
            return AjaxResult.error("请选择要核销的章节", d);
        }
        return doRedeemExploreChapter(registration, candidates.get(0), topicId,
                merchantOwnerMemberId, operatorMemberId, actorRoleCode);
    }

    /** 核销回包的章节名;核销已成立,查名失败只降级为「章节 id」,与 describeChapters 的兜底一致。 */
    private String chapterDisplayName(Long chapterId) {
        try {
            CmsTopicChapter chapter = topicChapterService.selectCmsTopicChapterById(chapterId);
            if (chapter != null && StringUtils.isNotEmpty(chapter.getName())) {
                return chapter.getName();
            }
        } catch (RuntimeException e) {
            logger.warn("核销回包章节名读取失败 chapterId={}", chapterId, e);
        }
        return "章节 " + chapterId;
    }

    /**
     * [P0-2] 候选章节 id → {@code {id, name}}:**商家认的是章节名,不是数据库 id**。
     * 只给他「请选择:101 / 102」等于没给选项 —— 而选错章**不可逆**(entitlement 当场消费、
     * 玩家在另一章白跑)。
     *
     * <p>一次按 topicId 查完再在内存里挑,**不按 id 逐个查**:候选今天只有几条,但
     * 「一商家承接多章是常态」⇒ N+1 的形状会跟着长。⚠️ 只在交集 ≥2(真要选章)时才走这里,
     * 单章直接核销那条路一次额外查询都没有。
     *
     * <p>查不到名字仍返 {@code 章节 <id>} 兜底,**绝不返 null/空串** —— 面板上一个点不动的
     * 空条目比一个丑名字坏得多:商家会以为卡死,而他其实还欠着一次核销。
     *
     * @return 与入参 {@code chapterIds} **同序等长**的列表(前端可能按下标回取 id)
     */
    private List<Map<String, Object>> describeChapters(Long topicId, Map<Long, Long> scannable) {
        Map<Long, String> names = new HashMap<>();
        CmsTopicChapter probe = new CmsTopicChapter();
        probe.setTopicId(topicId);
        List<CmsTopicChapter> all = topicChapterService.selectCmsTopicChapterList(probe);
        if (all != null) {
            for (CmsTopicChapter c : all) {
                if (c.getId() != null && StringUtils.isNotEmpty(c.getName())) {
                    names.put(c.getId(), c.getName());
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long id : scannable.keySet()) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", id);
            String name = names.get(id);
            m.put("name", name != null ? name : ("章节 " + id));
            if (scannable.get(id) != null) {
                m.put("replayed", true);
                m.put("redemptionId", scannable.get(id));
                m.put("statusText", "已核销，不重复提供权益");
            }
            out.add(m);
        }
        return out;
    }

    /**
     * [P0-14] ③ 按指定章节核销 —— 交集 ≥2 时,商家从 {@code scan_qr_code} 返回的 {@code chapterIds}
     * 里选一个打回来。
     *
     * <p><b>为什么必须有这个端点</b>:{@code scan_qr_code} 只收 (type, code),交集 ≥2 时它返
     * {@code needChapterChoice + chapterIds} 却**没有任何端点能接收那个选择** ⇒ 核销是个死胡同。
     * 一个商家在同一路线承接多章是**常态**({@code uk_offer_merchant_chapter} 只约束一商家一章一行,
     * 不阻止一商家多章),不是边界情况。
     *
     * <p>⚠️ <b>上面那句「死胡同」不是预防性措辞,它真的发生过一轮</b>(2026-07-17 实测:本端点与
     * {@code needChapterChoice} / {@code chapterIds} 在小程序全仓 0 命中,而 {@code scan_qr_code}
     * 4 文件命中 ⇒ 负控有效):端点建好了、前端没接,而 {@code scan_qr_code} 那边返的还是
     * {@code success} ⇒ 两个扫码页都弹「验票成功」⇒ 玩家可拿同一张票反复核销。
     * ⇒ <b>建了端点不等于接通</b>;这类「后端独角戏」只有查前端有没有调才看得见,审后端 diff 抓不到。
     *
     * <p>⚠️ chapterId 是**商家传的** ⇒ 服务端必须复核它在候选集里(在
     * {@code redeemExploreChapterAndRecord} 的事务内做),否则商家能指定核销任意一章。
     */
    public AjaxResult scanQrCodeChapter(Actor actorContext, String code, Long chapterId) {
        Long principalMemberId;
        try { principalMemberId = actorContext.currentMemberId(); } catch (Exception e) { return AjaxResult.error("请先登录"); }
        if (principalMemberId == null || principalMemberId <= 0) return AjaxResult.error("请先登录");
        if (code == null || chapterId == null) return AjaxResult.error("参数缺失");

        MerchantAccessContext access;
        try {
            access = merchantAccessService.require(principalMemberId, MerchantPermission.VERIFY);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? com.chengyinhub.common.constant.HttpStatus.ERROR : e.getCode(), e.getMessage());
        }

        ScanContext ctx = resolveExploreScanContext(code);
        if (ctx.error != null) return ctx.error;
        return doRedeemExploreChapter(ctx.registration, chapterId, ctx.activity.getTopicId(),
                access.getOwnerMemberId(), principalMemberId, access.getRoleCode());
    }

    /** 城市定向同一路线多据点时返回可辨认、可稳定回传的中标记录候选。 */
    private AjaxResult requireStationChoice(List<CmsRegistrationMerchant> stations) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (CmsRegistrationMerchant station : stations) {
            Map<String, Object> item = new HashMap<>();
            item.put("registrationMerchantId", station.getId());
            String name = station.getAddressName();
            if (StringUtils.isEmpty(name)) name = station.getNodeName();
            if (StringUtils.isEmpty(name)) name = station.getAddress();
            if (StringUtils.isEmpty(name)) {
                name = "据点 " + station.getId();
            }
            item.put("name", name);
            item.put("nodeId", station.getNodeId());
            item.put("address", station.getAddress());
            item.put("validationMethod", station.getValidationMethod());
            items.add(item);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("needStationChoice", true);
        data.put("stations", items);
        // 未完成选择就没有发生核销。必须返回非成功,防旧客户端把候选回执当验票成功。
        return AjaxResult.error("请选择要核销的据点", data);
    }

    /**
     * 城市定向多据点选择后的核销入口。客户端只负责传选择,归属与主题由 service 在事务内复核。
     */
    public AjaxResult scanQrCodeStation(Actor actorContext, String code, Long registrationMerchantId) {
        Long principalMemberId;
        try { principalMemberId = actorContext.currentMemberId(); } catch (Exception e) { return AjaxResult.error("请先登录"); }
        if (principalMemberId == null || principalMemberId <= 0) return AjaxResult.error("请先登录");
        if (StringUtils.isEmpty(code) || registrationMerchantId == null) return AjaxResult.error("参数缺失");

        MerchantAccessContext access;
        try {
            access = merchantAccessService.require(principalMemberId, MerchantPermission.VERIFY);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? com.chengyinhub.common.constant.HttpStatus.ERROR : e.getCode(), e.getMessage());
        }

        CmsRegistration registration = resolveRegistrationByScanCode(code);
        if (registration == null) return AjaxResult.error("核销码无效");
        AjaxResult mismatch = assertScanTypeMatchesOwner("topic", registration);
        if (mismatch != null) return mismatch;
        Integer paymentStatus = registration.getPaymentStatus();
        if (paymentStatus == null || paymentStatus != 2) {
            return AjaxResult.error("订单未完成支付或已退款,无法核销");
        }
        if (registration.getOwnerId() == null) return AjaxResult.error("订单数据异常,无法核销");
        if (topicHasOrganizer(registration.getOwnerId())) {
            return AjaxResult.error("本主题由俱乐部带团，请扫领队出示的团码");
        }

        boolean verified;
        try {
            if (access.getOwnerMemberId().equals(principalMemberId)) {
                verified = registrationService.verifyAndSettleForStation(
                        registration, access.getOwnerMemberId(), registrationMerchantId);
            } else {
                verified = registrationService.verifyAndSettleForStation(
                        registration, access.getOwnerMemberId(), registrationMerchantId,
                        principalMemberId, access.getRoleCode());
            }
        } catch (IllegalStateException e) {
            return AjaxResult.error(e.getMessage());
        } catch (RuntimeException e) {
            logger.error("城市定向按据点核销失败 registrationId={} registrationMerchantId={} merchantId={}",
                    registration.getId(), registrationMerchantId, access.getOwnerMemberId(), e);
            return AjaxResult.error("核销失败,请稍后重试");
        }
        if (!verified) {
            applyStoreVerifyGrowth(registration);
            return duplicateVerificationResult();
        }

        pointsAwardService.award(registration.getMemberId(), 11L, registration.getId(), "到店核销");
        applyStoreVerifyGrowth(registration);
        return AjaxResult.success("核销成功");
    }

    /** {@link #resolveScanContext} 的结果:要么 error 非空(直接返前端),要么 registration+activity 都可用。 */
    private static final class ScanContext {
        final AjaxResult error;
        final CmsRegistration registration;
        final CmsActivity activity;

        private ScanContext(AjaxResult error, CmsRegistration registration, CmsActivity activity) {
            this.error = error;
            this.registration = registration;
            this.activity = activity;
        }

        static ScanContext fail(AjaxResult e) { return new ScanContext(e, null, null); }
        static ScanContext ok(CmsRegistration r, CmsActivity a) { return new ScanContext(null, r, a); }
    }

    /**
     * activity 码核销的**共用前置闸**:解析单子 + 三道校验 + 载入活动。**不判是不是 ③**。
     *
     * <p><b>为什么抽出来</b>:{@code scan_qr_code}(activity 分支)与 {@code scan_qr_code_chapter}
     * 是同一条核销路的两个入口,前置闸必须**逐字一致**。抄成两份 ⇒ 将来加第四道闸时只改一处
     * = 另一个入口裸奔 —— <b>本轮已经因为「两个分支口径不一致」栽过一次</b>(type 与 ownerType
     * 没人核对,导致 ③ 的码用 type="topic" 扫就能把整单结算走)。同型的坑不留第二次。
     *
     * <p>⚠️ <b>这段 javadoc 曾经是空头支票</b>:上一版把「解析+两道通用闸」与「断言必须是 ③」
     * 焊在同一个方法里,而 {@code scan_qr_code} 需要的是通用闸之后**分流**(③ 走按章核销 /
     * ①② 走原路)、不是 ③ 的断言 ⇒ 它只能自己内联第二份 ⇒ 收口从未兑现,helper 实际只有
     * {@code scan_qr_code_chapter} 一个调用方。**声称收口 ≠ 收口**,而不生效的恰恰是
     * {@code scan_qr_code} —— 商家实际扫码走的主入口。现在拆成两层,承诺才真的成立。
     *
     * <p>三道闸的顺序是有讲究的:type/ownerType **先于**支付态 —— 类型都对不上的乱扫,
     * 连「这单未支付」这种信息都不该泄漏给他。
     *
     * @param type 拿来与单子的 ownerType 核对的类型。**调用方若从客户端收到 type,必须原样透传**
     *             —— 这道闸的全部意义就是核对「客户端声称的 type」与「单子真实的 ownerType」一致,
     *             自己替它填一个「应该是」的值 = 把闸判成恒真。
     *             ⇒ {@code scan_qr_code} 透传客户端的 type;而 {@code scan_qr_code_chapter}
     *             **没有 type 入参**(③ 定死 owner_type=2),它传字面量 "activity" 不是「写死」,
     *             是在断言「这码必须是活动单」—— 拿主题单的码来调它会在这里被拒。
     */
    private ScanContext resolveScanContext(String type, String code) {
        CmsRegistration reg = resolveRegistrationByScanCode(code);
        if (reg == null) {
            return ScanContext.fail(AjaxResult.error("核销码无效"));
        }
        // ① type 必须与 ownerType 一致 —— 详见 assertScanTypeMatchesOwner
        AjaxResult mismatch = assertScanTypeMatchesOwner(type, reg);
        if (mismatch != null) {
            return ScanContext.fail(mismatch);
        }
        // ② 资损闸:仅已支付单可核销
        Integer ps = reg.getPaymentStatus();
        if (ps == null || ps != 2) {
            return ScanContext.fail(AjaxResult.error("订单未完成支付或已退款,无法核销"));
        }
        CmsActivity act = cmsActivityService.selectCmsActivityById(reg.getOwnerId());
        if (act == null) {
            return ScanContext.fail(AjaxResult.error("活动不存在"));
        }
        String rejection = ExploreTicketUsePolicy.rejection(reg.getPurchaseKind(), act.getEndDate(), new Date());
        if (rejection != null) return ScanContext.fail(AjaxResult.error(rejection));
        return ScanContext.ok(reg, act);
    }

    /**
     * [P0-14] ③ 专用前置闸 = 通用闸 + 「必须真是 ③」的断言。{@code scan_qr_code_chapter} 用。
     *
     * <p>{@code scan_qr_code} **不能**用这个:它要的是通用闸之后分流,①② 得走整单核销那条路,
     * 不该被这里的断言拒掉。⇒ 两个入口共享 {@link #resolveScanContext},各自加自己那一道。
     */
    private ScanContext resolveExploreScanContext(String code) {
        ScanContext ctx = resolveScanContext("activity", code);
        if (ctx.error != null) {
            return ctx;
        }
        // ③ 必须真是 ③ —— ①② 走整单核销那条路,别让它们进按章核销
        if (!isExplorePass(ctx.registration, ctx.activity)) {
            return ScanContext.fail(AjaxResult.error("该订单不是探索票,请走普通核销"));
        }
        return ctx;
    }

    /** ③ 核销的公共收尾:走 service 的事务收口 + 事务外发积分/勋章。 */
    private AjaxResult doRedeemExploreChapter(CmsRegistration registration, Long chapterId,
                                              Long topicId, Long merchantOwnerMemberId,
                                              Long operatorMemberId, String actorRoleCode) {
        if (topicId == null) {
            return AjaxResult.error("该活动未关联路线,无法按章核销");
        }
        ExploreChapterRedemptionResult redemption;
        try {
            if (merchantOwnerMemberId.equals(operatorMemberId)) {
                redemption = registrationService.redeemExploreChapterAndRecord(
                        registration, chapterId, topicId, merchantOwnerMemberId,
                        operatorMemberId, null);
            } else {
                redemption = registrationService.redeemExploreChapterAndRecord(
                        registration, chapterId, topicId, merchantOwnerMemberId,
                        operatorMemberId, actorRoleCode, null);
            }
        } catch (IllegalStateException e) {
            // 业务拒绝(章节不可由您核销)是人话,直接透出;事务已回滚。
            return AjaxResult.error(e.getMessage());
        } catch (RuntimeException e) {
            // ⚠️ [C3 失败不可静默] 意外异常(DB 断连 / 映射错 / NPE)绝不能伪装成业务拒绝:
            // 那会让商家看到「Cannot invoke ...」这种内部实现泄漏,而服务器**零 error 日志**
            // ⇒ 核销大面积失败时监控看不到任何东西。与本文件 advanceProgress 的口径一致
            //(发奖失败也 logger.error「不可静默,记 error 供补发」)。
            // ⚠️ redeemChapter 抛的是**裸 RuntimeException**(「该章节权益不可核销」「名额已用完」),
            //    落在这个分支 —— 它们会被记 error。宁可多记几条业务拒绝,也不要漏掉一次真故障。
            logger.error("③ 按章核销失败 registrationId={} chapterId={} merchantId={}",
                    registration.getId(), chapterId, merchantOwnerMemberId, e);
            return AjaxResult.error(e.getMessage());
        }
        Map<String, Object> d = new HashMap<>();
        d.put("redemptionId", redemption.getRedemptionId());
        d.put("chapterId", chapterId);
        // 首次与重放共用同一个 chapterName 字段:商家认章节名,回包丢失重试后也要看得到核的是哪一章。
        d.put("chapterName", chapterDisplayName(chapterId));
        if (redemption.isReplayed()) {
            d.put("replayed", true);
            return AjaxResult.success("该章节已核销，请勿重复提供权益", d);
        }
        // 积分/勋章在事务外:幂等、非资金 —— 发分失败不该让已经成立的核销回滚(同 ①② 分支口径)。
        try {
            pointsAwardService.award(registration.getMemberId(), 11L, registration.getId(), "到店核销");
        } catch (Exception e) {
            logger.error("到店核销积分发放失败 registrationId={} member={},需人工补发",
                    registration.getId(), registration.getMemberId(), e);
        }
        applyStoreVerifyGrowth(registration);
        try {
            exploreCollectibleService.record(registration.getMemberId(), topicId, chapterId,
                    merchantOwnerMemberId);
        } catch (Exception e) {
            logger.error("探店章节收藏凭证写入失败 registrationId={} chapterId={}", registration.getId(), chapterId, e);
        }
        // 商家核销 = 玩家在店:补记到店,否则「到店」「核销」两份事实对不上,玩家再扫门店码会被「该章节已核销」拒(实走 B4)
        registrationService.recordImpliedShopArrival(registration, chapterId, merchantOwnerMemberId);
        // 通关副作用已在 redeemExploreChapterAndRecord 的同一事务收口；这里不再吞异常或重复发奖。

        if (redemption.getCompletionEarnedXp() > 0) {
            d.put("completionEarnedXp", redemption.getCompletionEarnedXp());
        }
        return AjaxResult.success("核销成功", d);
    }

    /**
     * 扫描二维码
     */
    public AjaxResult scanDynamicCode(Actor actorContext, String code) {
        if (StringUtils.isEmpty(code)) {
            return AjaxResult.error("核销码无效");
        }
        VerifyDynCodeUtil.Result result = rejectSuperseded(VerifyDynCodeUtil.verify(code, System.currentTimeMillis(), dynSecret), code);
        if (!result.valid) {
            AjaxResult original = replayOriginalSeat(actorContext, "activity", code, null, false);
            if (original != null) return original;
            return AjaxResult.error(result.reason == null ? "核销码无效" : result.reason);
        }
        // The existing activity path retains its real permission/time-window gates before seat verification.
        if (result.type != null && result.type.startsWith("activity_seat_")
                && com.chengyinhub.business.service.RegistrationSeatService.seatNumber(result.type) != null) {
            return scanQrCode(actorContext, "activity", code);
        }
        // type 来自 HMAC 已验签的码，不能再收小程序回传的 type；团码/据点码各走自己的专用端点。
        if (!"activity".equals(result.type) && !"topic".equals(result.type)) {
            return AjaxResult.error("非玩家动态核销码");
        }
        return scanQrCode(actorContext, result.type, code);
    }

    @Autowired
    private com.chengyinhub.business.service.RegistrationSeatService registrationSeatService;

    /**
     * Failed new-use gates do not erase a committed seat. Signature-only identity checking is
     * confined to this terminal read; current verifier authorization and locking scope stay in
     * the seat service. A buyer's self-read permission is deliberately not used here.
     */
    private AjaxResult replayOriginalSeat(Actor actorContext, String type, String code, Long activityId, boolean requireActivity) {
        if (code == null || !code.startsWith("v1.")) return null;
        try {
            VerifyDynCodeUtil.Result identity = VerifyDynCodeUtil.verifyForReceipt(code, dynSecret);
            Integer seatNo = identity.valid
                    ? com.chengyinhub.business.service.RegistrationSeatService.seatNumber(identity.type) : null;
            if (seatNo == null) return null;
            boolean activityTicket = identity.type.startsWith("activity_seat_");
            if (type != null && !type.equals(activityTicket ? "activity" : "topic")) return null;
            if ((requireActivity || !activityTicket) && (activityId == null || activityId <= 0)) return null;
            Long actor;
            try { actor = actorContext.currentMemberId(); } catch (Exception unauthenticated) { return AjaxResult.error("请先登录"); }
            if (actor == null || actor <= 0) return AjaxResult.error("请先登录");
            var receipt = registrationSeatService.verifierSeatReceipt(identity.regId, seatNo, activityId, actor, activityTicket);
            return receipt == null ? null : AjaxResult.success("该席位已核销，请勿重复提供权益", receipt);
        } catch (ServiceException refusal) {
            return new AjaxResult(refusal.getCode() == null ? 500 : refusal.getCode(), refusal.getMessage());
        } catch (RuntimeException unknown) {
            logger.error("原席位核销事实读取失败，保留待确认状态", unknown);
            return new AjaxResult(520, "原席位核销结果待确认，请按原席位查询，勿改选其他席位重发");
        }
    }

    public AjaxResult scanGroupMemberTicket(Actor actorContext, String code, Long activityId) {
        if (StringUtils.isEmpty(code)) {
            return AjaxResult.error("核销码无效");
        }
        // 动态码的 type 只能信任服务端验签结果；活动票属于 D9 全团结算，不能落入 D22 主题商家分账。
        if (code.startsWith("v1.")) {
            VerifyDynCodeUtil.Result result = rejectSuperseded(VerifyDynCodeUtil.verify(code, System.currentTimeMillis(), dynSecret), code);
            if (!result.valid) {
                AjaxResult original = replayOriginalSeat(actorContext, null, code, activityId, true);
                if (original != null) return original;
                return AjaxResult.error(result.reason == null ? "核销码无效" : result.reason);
            }
            Integer seatNo = com.chengyinhub.business.service.RegistrationSeatService.seatNumber(result.type);
            if (seatNo != null && result.type.startsWith("activity_seat_")) {
                Long actor;
                try { actor = actorContext.currentMemberId(); } catch (Exception unauthenticated) { return AjaxResult.error("请先登录"); }
                if (actor == null || actor <= 0) return AjaxResult.error("请先登录");
                CmsRegistration original = registrationService.selectCmsRegistrationById(result.regId);
                if (original == null || !Long.valueOf(2L).equals(original.getOwnerType())
                        || activityId == null || !activityId.equals(original.getOwnerId()))
                    return AjaxResult.error("活动席位不属于当前带队场次");
                return scanQrCode(actorContext, "activity", code, activityId);
            }
            if (seatNo != null) {
                Long actor;
                try { actor = actorContext.currentMemberId(); } catch (Exception unauthenticated) { return AjaxResult.error("请先登录"); }
                var receipt = registrationSeatService.redeemGroupSeat(result.regId, seatNo, activityId, actor);
                applyOriginalSeatRewards(registrationService.selectCmsRegistrationById(receipt.registrationId()), receipt);
                return AjaxResult.success(receipt.replayed() ? "该席位已核销，请勿重复提供权益" : "核销成功", receipt);
            }
            if (!"topic".equals(result.type)) {
                return AjaxResult.error("本场次只可核销主题票");
            }
        }
        // 静态码没有可验签 type，仍由 scanQrCode 的 owner_type 断言拒绝活动票。
        return scanQrCode(actorContext, "topic", code, activityId);
    }

    public AjaxResult scanQrCode(Actor actorContext, String type, String code, Long activityId) {
        try {
            // 获取当前登录用户ID
            Long memberId = 0L;
            try {
                memberId = actorContext.currentMemberId();
            } catch (Exception e) {

            }
            if (memberId <= 0) {
                return AjaxResult.error("请先登录");
            }

            if ("coupon".equals(type)) {
                // 优惠券已有 /api/coupon/verification 唯一入口，那里统一校验动态码/静态码、
                // canRedeemCoupon 能力与事务核销；此旧入口不得再复制一条可漂移的资金写链。
                return AjaxResult.error("请使用专用优惠券核销入口");
            } else if (type.equals("activity")) {
                // ★ [F2] 前置闸(解析 + type/ownerType 一致 + 仅已支付 + 载入活动)全在 resolveScanContext 里,
                //   与 scan_qr_code_chapter **共用同一份**。这里曾经内联过第二份逐字重复的闸 ——
                //   那正是 resolveScanContext 的 javadoc 声称要消灭、却因为「①②③ 焊死在一个方法里」
                //   而从没消灭掉的形态。加第四道闸时改一处即可,两个入口一起生效(负控可证)。
                ScanContext ctx = resolveScanContext(type, code);
                if (ctx.error != null) {
                    AjaxResult original = replayOriginalSeat(actorContext, type, code, activityId, false);
                    if (original != null) return original;
                    return ctx.error;
                }
                CmsRegistration cmsRegistration = ctx.registration;
                CmsActivity cmsActivity = ctx.activity;
                // [P0-14] ③ 走按章核销 —— 必须在「活动主办方」鉴权**之前**分流。
                //
                // ★ 为什么是这个分支而不是 topic 分支:③ 定死是 owner_type=2 / owner_id=activityId
                //   的**活动单**(createRegistration 落 ownerType=2;ownerType==1 在 :208 被无条件
                //   early-return 进 createSelfPlayPass)⇒ ③ 的核销码只会走到这里。
                //   topic 分支处理的是 owner_type=1 的主题单(它把 ownerId 当 topicId 用),③ 到不了那儿。
                //
                // ★ 为什么在主办方鉴权之前:③ 的扫码方是经 merchant_chapter_offer **入驻**的商家,
                //   不是活动主办方 ⇒ 放在 `cmsActivity.getMemberId().equals(memberId)` 之后,
                //   商家会被当成「不是主办方」直接掉出去,核销永远走不通。
                //   ③ 自己的鉴权在 resolveRedeemableChapters 里:只认该商家自己的生效 offer。
                //
                // ★ topicId 必须取 activity.getTopicId(),**绝不能用 registration.getOwnerId()** ——
                //   ③ 的 ownerId 是 activityId,拿它当 topicId 去 selectByTopic 必然查空,
                //   商家会永远收到「您在本路线没有生效的权益供给」。
                if (isExplorePass(cmsRegistration, cmsActivity)) {
                    MerchantAccessContext access = merchantAccessService.require(
                            memberId, MerchantPermission.VERIFY);
                    return redeemExploreChapter(cmsRegistration, cmsActivity.getTopicId(),
                            access.getOwnerMemberId(), memberId, access.getRoleCode());
                }
                Long activityOwnerMemberId = cmsActivity.getMemberId();
                if (activityOwnerMemberId == null) {
                    return AjaxResult.error("活动主办方数据异常");
                }
                String actorRoleCode = "ACTIVITY_OWNER";
                if (!activityOwnerMemberId.equals(memberId)) {
                    MerchantAccessContext access = merchantAccessService.require(
                            memberId, MerchantPermission.VERIFY);
                    if (!activityOwnerMemberId.equals(access.getOwnerMemberId())) {
                        return AjaxResult.error("该店无权核销本场玩家票；俱乐部带队请扫描领队团码");
                    }
                    actorRoleCode = access.getRoleCode();
                }
                // PR10 复审:时间窗在权限之后判 —— 无权扫码方先拿到权限拒绝,不能借时间提示探测场次开场时间。
                AjaxResult tooEarly = clubSessionVerificationTooEarly(cmsActivity, new Date());
                if (tooEarly != null) {
                    AjaxResult original = replayOriginalSeat(actorContext, type, code, activityId, false);
                    if (original != null) return original;
                    return tooEarly;
                }
                if (cmsRegistration.getOrderNum() != null && cmsRegistration.getOrderNum() > 1) {
                    VerifyDynCodeUtil.Result explicitSeat = rejectSuperseded(VerifyDynCodeUtil.verify(code, System.currentTimeMillis(), dynSecret), code);
                    Integer seatNo = explicitSeat.valid && explicitSeat.type != null && explicitSeat.type.startsWith("activity_seat_")
                            ? com.chengyinhub.business.service.RegistrationSeatService.seatNumber(explicitSeat.type) : null;
                    if (seatNo == null) return AjaxResult.error("多人订单请逐席位出示核销码，整单码不能代替逐人核销");
                    try {
                        var receipt = registrationSeatService.redeemActivitySeat(cmsRegistration.getId(), seatNo, memberId);
                        applyOriginalSeatRewards(cmsRegistration, receipt);
                        return AjaxResult.success(receipt.replayed() ? "该席位已核销，请勿重复提供权益" : "核销成功", receipt);
                    } catch (ServiceException refusal) {
                        throw refusal;
                    } catch (RuntimeException unknown) {
                        logger.error("活动席位核销结果待确认 registrationId={} seatNo={} actor={}",
                                cmsRegistration.getId(), seatNo, memberId, unknown);
                        return new AjaxResult(520, "原席位核销结果待确认，请按原席位查询，勿改选其他席位重发");
                    }
                }
                // [V2] 重复核销闸 + 入账 + 台账,同一事务(verifyAndSettle)。
                    // 条件置位而非读判:读判挡不住并发双扫。
                    // 收进事务而非裸放这里:本方法无事务,置位一旦先提交、下游再抛,
                    // 商家重扫会被「已核销」挡回,这笔核销就再也补不回来了。
                    boolean verified = activityOwnerMemberId.equals(memberId)
                            ? registrationService.verifyAndSettle(cmsRegistration,
                            activityOwnerMemberId, activityOwnerMemberId, null)
                            : registrationService.verifyAndSettle(cmsRegistration,
                            activityOwnerMemberId, activityOwnerMemberId, null,
                            memberId, actorRoleCode);
                    if (!verified) {
                        applyStoreVerifyGrowth(cmsRegistration);
                        return duplicateVerificationResult();
                    }

                    // 到店核销完成，给玩家发积分（eventType=11，按 cms_points_setting 配额，幂等）
                    pointsAwardService.award(cmsRegistration.getMemberId(), 11L, cmsRegistration.getId(), "到店核销");
                    // 勋章:触发自动授章事件(运营需配 growth_mission.trigger_event='store_verify' 才会真发章)
                    applyStoreVerifyGrowth(cmsRegistration);

//                            //增加分润
////                            总体分润框架
////                            某主题活动总收入 T，由三方分配：
////                            •	商户（执行方）：70%
////                            •	主题设计者（创意方）：20%
////                            •	平台（技术 & 运营）：10%
//
//                            UmsMember umsMember = umsMemberService.selectUmsMemberById(cmsActivity.getMemberId());
//                            BigDecimal balance = cmsRegistration.getTotalAmount().multiply(new BigDecimal("0.7"));
//                            umsMemberService.addBalance(umsMember.getId(), balance);
//
//                            //余额记录
//                            UmsMemberBalanceDetail umsMemberBalanceDetail = new UmsMemberBalanceDetail();
//                            umsMemberBalanceDetail.setMemberId(cmsActivity.getMemberId());
//                            umsMemberBalanceDetail.setCurrentBalance(umsMember.getBalance());
//                            umsMemberBalanceDetail.setAfterBalance(umsMember.getBalance().add(balance));
//                            umsMemberBalanceDetail.setChangeBalance(balance);
//                            umsMemberBalanceDetail.setChangeReason("活动分润");
//                            umsMemberBalanceDetail.setEventType(Long.valueOf(1));
//                            umsMemberBalanceDetail.setEventId(cmsActivity.getId());
//                            umsMemberBalanceDetail.setChangeType(1L);
//                            umsMemberBalanceDetail.setDelFlag(Long.valueOf(0));
//                            umsMemberBalanceDetail.setCreateTime(new Date());
//                            umsMemberBalanceDetailService.insertUmsMemberBalanceDetail(umsMemberBalanceDetail);

                return AjaxResult.success("核销成功");
            } else if (type.equals("topic")) {
                // An explicit seat cannot fall through to legacy whole-order merchant verification.
                // Route to the existing scoped group entry, which still enforces TTL for any new use.
                VerifyDynCodeUtil.Result seatIdentity = VerifyDynCodeUtil.verifyForReceipt(code, dynSecret);
                if (seatIdentity.valid && seatIdentity.type.startsWith("topic_seat_")
                        && com.chengyinhub.business.service.RegistrationSeatService.seatNumber(seatIdentity.type) != null)
                    return scanGroupMemberTicket(actorContext, code, activityId);
                CmsRegistration cmsRegistration = resolveRegistrationByScanCode(code);
                if (cmsRegistration != null) {
                    // ★ [资损·根因] 与 activity 分支同一道闸:type 是扫码方传的,必须核对它与 ownerType 一致。
                    //
                    // ⚠️ 这道闸**就是** ③ 到不了这里的原因 —— 别把因果搞反。
                    // 曾经这里写过「③ 定死是 owner_type=2 的活动单 ⇒ 到不了这里」当作不加守卫的理由,
                    // **那个不变量是假的**:决定走哪个分支的是**客户端传的 type 字符串**,不是单子的 ownerType。
                    // 没有这道闸时,一张 ③(owner_type=2)的码用 type="topic" 扫就会走进来,
                    // 把 activityId 当 topicId 去匹配中标记录(两表 id 区间重叠,撞上是常态)
                    // ⇒ verifyAndSettle 把整单票款结算给这个跟本单毫无关系的商家。①(owner_type=2 的活动票)同型。
                    AjaxResult mismatch = assertScanTypeMatchesOwner(type, cmsRegistration);
                    if (mismatch != null) {
                        return mismatch;
                    }
                    // [资损闸] 同 activity 分支:仅已支付单可核销,拒已退款/未支付。
                    Integer ps = cmsRegistration.getPaymentStatus();
                    if (ps == null || ps != 2) {
                        return AjaxResult.error("订单未完成支付或已退款,无法核销");
                    }
                    // 闸过了 ⇒ 这里保证是 owner_type=1 的主题单 ⇒ 下面把 ownerId 当 topicId 用是安全的。
                    Long topicId = cmsRegistration.getOwnerId();
                    boolean hasOrganizer = topicHasOrganizer(topicId);
                    boolean isOrganizerMember = hasOrganizer && isTopicOrganizer(topicId, memberId);
                    boolean isOrganizer = hasOrganizer && clubMemberService.isTopicOwner(topicId, memberId);
                    if (isOrganizerMember && !isOrganizer) {
                        return AjaxResult.error("仅俱乐部主理人可核销玩家票");
                    }

                    Long merchantOwnerMemberId = null;
                    String merchantActorRoleCode = null;
                    if (!hasOrganizer) {
                        MerchantAccessContext access = merchantAccessService.require(
                                memberId, MerchantPermission.VERIFY);
                        merchantOwnerMemberId = access.getOwnerMemberId();
                        merchantActorRoleCode = access.getRoleCode();
                    }
                    // [G6] 核销资格 = 中标,真源是 audit_status(0待调配 1中标 2落选);
                    // 从前用 status=1 能 work 纯属巧合(select() 中标时恰好同置 status=1),两轴一漂移
                    // 就会让落选商家核销玩家的票。
                    List<CmsRegistrationMerchant> list = Collections.emptyList();
                    if (!hasOrganizer) {
                        CmsRegistrationMerchant cmsRegistrationMerchant = new CmsRegistrationMerchant();
                        cmsRegistrationMerchant.setMemberId(merchantOwnerMemberId);
                        cmsRegistrationMerchant.setDelFlag(0);
                        cmsRegistrationMerchant.setAuditStatus(Long.valueOf(1));
                        cmsRegistrationMerchant.setTopicId(topicId);
                        list = registrationMerchantService.selectCmsRegistrationMerchantList(
                                cmsRegistrationMerchant);
                    }

                    // [D22] 谁能核销**玩家票**取决于组织槽:
                    //  · 组织槽为空 = 玩家散着来 ⇒ 商家扫玩家(①中标报名行 / ②生效 merchant 合作单);
                    //  · 组织槽有人 = 有领队,团整体走完全程 ⇒ **只有俱乐部主理人**扫玩家,
                    //    商家改扫团码(/api/verify/groupcode/redeem)。
                    //
                    // ⚠️ 组织槽有人时必须把商家挡在这里,不能只在结算侧改口径:verifyIfNotVerified 是
                    // 一次性 CAS(where verification_status<>1),**谁先扫谁把核销位永久占死**。商家抢先扫
                    // ⇒ 俱乐部再扫返 0 ⇒ 该票永远不会有 member_id=领队 的 type=1 台账行 ⇒ 结算时它不计入
                    // 任何团的基数,却仍在 totalSales 里 ⇒ merchantTotal 偏小、残差 margin 偏大 ⇒
                    // 守恒断言照样通过 ⇒ 钱静默流进发起人毛利、零告警。
                    boolean isWinner = !hasOrganizer && !list.isEmpty();
                    boolean isCoopMerchant = !hasOrganizer && !isWinner
                            && hasEffectiveMerchantCoopOrder(topicId, merchantOwnerMemberId);

                    if (isWinner && list.size() > 1) {
                        return requireStationChoice(list);
                    }

                    if (isWinner || isCoopMerchant || isOrganizer) {
                        // [V2] 重复核销闸:中标闸只按 topic 过滤,而 (topic, node) 竞争位允许同一 topic 有多个
                        // 中标商家(mode=2 的哨兵 node_id=0 更是不构成竞争位),他们都能走到这里。
                        // 无此闸时第二家扫码会覆盖 verifier_id 并插自己的台账行,归因与台账错乱。
                        //
                        // 条件置位 + 入账 + 台账收进同一事务(verifyAndSettle),三条理由缺一不可:
                        // ①条件置位而非读判 —— 读判挡不住并发双扫;
                        // ②必须同事务 —— 本方法无事务,置位先提交、入账再抛的话,商家重扫会被
                        //   「已核销」挡回,而**核销是主题结算唯一被写入的时刻**(支付阶段对 ownerType=1
                        //   不入账),这笔钱商家就永远收不到、也没有任何路径能补;
                        // ③台账探针带 type=1 —— 不带会命中报名时按节点模板插的 type=0 行而漏插核销行。
                        //
                        // [D22] 收款方按槽分,别统一传 memberId:
                        //  · 供给槽(商家)—— 主题的执行商家在核销时才由商家池确定,payee = 他本人;
                        //  · 组织槽(俱乐部)—— 它的钱是 LEAD_FEE、由 settleCoopTopic 从合作单出,
                        //    核销时再给它记一笔 settlement_detail 入账就是双花 ⇒ payee=null
                        //    (recordPaidRegistration 对 null payee 是 no-op,台账仍照写)。
                        boolean verified;
                        if (isOrganizer) {
                            if (activityId == null || activityId <= 0) {
                                return AjaxResult.error("请选择带队场次后再核销");
                            }
                            CmsActivity groupActivity = activityService.selectCmsActivityById(activityId);
                            if (!isActiveOrganizerActivity(groupActivity, topicId)
                                    || !clubMemberService.isTopicOwner(topicId,
                                    groupActivity.getClubId(), memberId)) {
                                return AjaxResult.error("该带队场次不可核销");
                            }
                            if (cmsRegistration.getOrderNum() != null && cmsRegistration.getOrderNum() > 1) {
                                return AjaxResult.error("多人订单请逐席位出示核销码，整单码不能代替逐人核销");
                            }
                            // 规则 1：同主题不同 activity 是不同团。核销事务里必须把报名单绑定当前场次，
                            // 结算只认这条事实，绝不再从 topic + 核销人猜归属。
                            verified = registrationService.verifyAndSettleForGroupActivity(cmsRegistration,
                                    memberId, activityId);
                        } else {
                            if (isWinner) {
                                // 走到这里必然只有一个中标行(多个的在上面就返回候选让商家选了)。
                                // 这一行只是服务端选出的候选快照；事务收口会按原 topic→registration
                                // 锁序回读据点，并用实际核销审计 ID 记录新合同的独立履约归属。
                                verified = merchantOwnerMemberId.equals(memberId)
                                        ? registrationService.verifyAndSettleForOwnStation(
                                        cmsRegistration, merchantOwnerMemberId,
                                        merchantOwnerMemberId, list.get(0))
                                        : registrationService.verifyAndSettleForOwnStation(
                                        cmsRegistration, merchantOwnerMemberId,
                                        merchantOwnerMemberId, list.get(0), memberId,
                                        merchantActorRoleCode);
                            } else {
                                verified = merchantOwnerMemberId.equals(memberId)
                                        ? registrationService.verifyAndSettle(
                                        cmsRegistration, merchantOwnerMemberId,
                                        merchantOwnerMemberId, null)
                                        : registrationService.verifyAndSettle(
                                        cmsRegistration, merchantOwnerMemberId,
                                        merchantOwnerMemberId, null, memberId,
                                        merchantActorRoleCode);
                            }
                        }
                        if (!verified) {
                            applyStoreVerifyGrowth(cmsRegistration);
                            return duplicateVerificationResult();
                        }
                        // 积分/勋章在事务外:幂等、非资金 —— 发分失败不该让已经成立的核销回滚。
                        pointsAwardService.award(cmsRegistration.getMemberId(), 11L, cmsRegistration.getId(), "到店核销");
                        applyStoreVerifyGrowth(cmsRegistration);
                        return AjaxResult.success("核销成功");
                    }
                    if (hasOrganizer) {
                        return AjaxResult.error("本主题由俱乐部带团，请扫领队出示的团码");
                    }
                    return AjaxResult.error("查询失败");
                } else {
                    return AjaxResult.error("查询失败");
                }
            }
            return AjaxResult.error("查询失败");
        } catch (ServiceException e) {
            AjaxResult original = replayOriginalSeat(actorContext, type, code, activityId, false);
            if (original != null) return original;
            return new AjaxResult(e.getCode() == null ? com.chengyinhub.common.constant.HttpStatus.ERROR : e.getCode(), e.getMessage());
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    /** 保持 Java 调用方/既有测试的两参数契约；HTTP 入口由三参数方法接收可选 activityId。 */
    public AjaxResult scanQrCode(Actor actorContext, String type, String code) {
        return scanQrCode(actorContext, type, code, null);
    }

    private AjaxResult duplicateVerificationResult() {
        Map<String, Object> data = new HashMap<>();
        data.put("alreadyVerified", true);
        return AjaxResult.error("该订单已核销", data);
    }

    /** Retains the original post-commit registration reward behavior; never multiplies by seats. */
    private void applyOriginalSeatRewards(CmsRegistration registration,
            com.chengyinhub.business.service.RegistrationSeatService.Receipt receipt) {
        if (registration == null || !receipt.registrationId().equals(registration.getId()))
            throw new IllegalStateException("原报名核销奖励上下文缺失");
        if (receipt.registrationFirstTime())
            pointsAwardService.award(registration.getMemberId(), 11L, registration.getId(), "到店核销");
        applyStoreVerifyGrowth(registration);
    }

    private void applyStoreVerifyGrowth(CmsRegistration registration) {
        try {
            growthService.deliverStoreVerification(registration.getId());
        } catch (Exception e) {
            logger.error("到店核验成长奖励发放失败 registrationId={} member={},持久待办等待恢复",
                    registration.getId(), registration.getMemberId(), e);
        }
    }

    /**
     * [D22/方案2] type0 邀约商家的核销资格真源 = 生效合作单。
     *
     * <p>定稿 §6.5-C:type0(发起方↔商家)是「① 分成模型的主通路」,`MERCHANT_VERIFY` 的来源就是它,
     * 且「结算只认合作单」。但 {@code CoopInviteServiceImpl.acceptInviteWithLock} 只建 coop_order、
     * 从不建 cms_registration_merchant 行(reg_id 恒空,schema 注释自认「邀约侧为空」)⇒ 只认报名行的
     * 中标闸把主通路商家挡在门外 ⇒ 他扫不了码 ⇒ 分账基数恒 0(#58 把全主题口径收成 per-merchant 后暴露)。
     *
     * <p>不走「接受邀约时补建报名行」那条:邀约携带的是商务条款,报名行要的是履约细节
     * (address/坐标/档期/template/核销题目),两者近乎零交集且缺口不可补录——那等于把邀约流程
     * 改造成报名流程,也是在给 select() 那笔外包旧账补合同。
     */
    private boolean hasEffectiveMerchantCoopOrder(Long topicId, Long memberId) {
        return com.chengyinhub.web.service.registration.RegistrationQueryApplication.hasEffectiveMerchantCoopOrder(
                coopOrderMapper, topicId, memberId);
    }

    /** [D22] 组织槽核销资格,真源在 {@link IClubMemberService#isTopicOrganizer} —— 两个 controller 共用它。 */
    private boolean isTopicOrganizer(Long topicId, Long memberId) {
        return clubMemberService.isTopicOrganizer(topicId, memberId);
    }

    /** [D22] 该主题的组织槽有没有人 —— 决定玩家票由谁核销。 */
    private boolean topicHasOrganizer(Long topicId) {
        return clubMemberService.topicHasOrganizer(topicId);
    }

    /** 带队玩家核销只能落到当前主题下仍有效、且明确归属某承接俱乐部的场次。 */
    private boolean isActiveOrganizerActivity(CmsActivity activity, Long topicId) {
        return activity != null && topicId != null && topicId.equals(activity.getTopicId())
                && activity.getClubId() != null && activity.getClubId() > 0
                && (activity.getDelFlag() == null || activity.getDelFlag() == 0)
                && activity.getCancelTime() == null;
    }

}
