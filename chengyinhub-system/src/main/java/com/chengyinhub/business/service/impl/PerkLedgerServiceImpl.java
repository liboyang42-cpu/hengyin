package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.domain.RegistrationEntitlement;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.RedemptionMapper;
import com.chengyinhub.business.mapper.RegistrationEntitlementMapper;
import com.chengyinhub.business.service.IMerchantPayableService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.common.constant.TermsMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权益与核销账本(§3.2)。
 *
 * <p>本类的每个方法都在守同一件事:**平台卖出去多少份权益,就得能兑现多少份**。
 * 兑现不了的下场是 §3.5 的缺口事件 → 替代商家 → 失败即整单全额退,是真资损。
 */
@Service
public class PerkLedgerServiceImpl implements IPerkLedgerService {

    @Autowired private com.chengyinhub.business.service.support.PureContentExploreService pureContentExploreService;

    private static final Logger logger = LoggerFactory.getLogger(PerkLedgerServiceImpl.class);

    /** P0-16 安全垫:覆盖率低于此值黄色预警(补招商/催配额)。 */
    private static final double COVERAGE_WARN = 1.2d;

    @Autowired
    private CmsTopicChapterMapper chapterMapper;

    @Autowired
    private CmsTopicMapper topicMapper;

    @Autowired
    private MerchantChapterOfferMapper offerMapper;

    @Autowired
    private RegistrationEntitlementMapper entitlementMapper;

    @Autowired
    private RedemptionMapper redemptionMapper;

    /** 撤销核销时清 verification_status —— 与「首次核销就置位」成对,不清就为一笔不存在的核销永久锁死退款。 */
    @Autowired
    private CmsRegistrationMapper cmsRegistrationMapper;

    /** 撤销核销时冲正平台应付 —— 与「核销即计提」成对,不冲就继续欠着一笔已不存在的核销的钱。 */
    @Autowired
    private IMerchantPayableService merchantPayableService;

    /** 撤销核销的**不可变审计**(谁撤的/为什么/何时),与撤销同事务;不落审计就不许撤销。 */
    @Autowired
    private BusinessAuditEventMapper businessAuditEventMapper;

    /** ③ 最后一章回退时，对称撤销官方活动主题 evidence。 */
    @Autowired
    private IOfficialEventV2Service officialEventV2Service;

    @Autowired
    private RedemptionRewardClawbackService redemptionRewardClawbackService;

    // ------------------------------------------------------------ 容量

    /**
     * ⚠️ **只读展示口径,不能用它做购票校验** —— 它走普通读(快照),在事务里会读到过期的 read view。
     * 购票必须用 {@link #chapterCapacityForUpdate}。两者的差别就是超卖与不超卖。
     */
    @Override
    public int chapterCapacity(Long chapterId) {
        if (chapterId == null) {
            return 0;
        }
        // Σ 只算「生效」供给 —— 生效是两条轴(status=1 且 valid_end 未过期),
        // 判据封在 mapper 的 liveOffer 里,别在这里重写一遍口径。
        int supply = nz(offerMapper.sumRemainingQuotaByChapter(chapterId));
        // 已售未核销 = 预占。售出即占,不等核销 —— 票卖出去的瞬间平台就欠着这份权益了。
        int held = entitlementMapper.countUnredeemedByChapter(chapterId);
        return supply - held;
    }

    /**
     * 同一个公式,但两个聚合都走**当前读**(FOR SHARE)。购票校验只能用这个。
     *
     * <p>为什么不能用普通读的那版:RR 下 read view 在事务的**第一次普通读**时建立 ——
     * reserveEntitlements 一进来的 {@code requiredChapters} 就把它建好了。之后哪怕 FOR UPDATE
     * 锁住章节行,锁后的普通聚合读**依然看的是旧快照**,看不见对手刚提交的 entitlement。
     * 真库实测:供给 1 座 / 2 并发买家 → B 在锁上真阻塞 0.81s,醒来仍算出 capacity=1 → sold=2;
     * 压测 8 买家 / 3 座 → 8 人全买进、0 拒绝、0 报错。换成当前读后:单例正确拒绝,压测恰好 3 进 5 拒。
     */
    private int chapterCapacityForUpdate(Long chapterId) {
        if (chapterId == null) {
            return 0;
        }
        int supply = nz(offerMapper.sumRemainingQuotaByChapterForUpdate(chapterId));
        int held = entitlementMapper.countUnredeemedByChapterForUpdate(chapterId);
        return supply - held;
    }

    @Override
    public int topicSellableCapacity(Long topicId) {
        List<CmsTopicChapter> required = requiredChapters(topicId);
        if (required.isEmpty()) {
            // 没有必选章 = 不受权益容量约束(纯内容型主题);别返 0 把票全卡死。
            return Integer.MAX_VALUE;
        }
        int min = Integer.MAX_VALUE;
        for (CmsTopicChapter c : required) {
            // 非权益档不在容量链上(quota_total 由 enroll 落 0)⇒ 不参与 min。
            // 全是非权益档 ⇒ min 保持 MAX_VALUE = 不受约束,与「无必选章」同档 —— 别返 0。
            if (!usesQuota(c)) {
                continue;
            }
            min = Math.min(min, chapterCapacity(c.getId()));
        }
        return min;
    }

    @Override
    public double chapterCoverageRatio(Long chapterId) {
        int held = entitlementMapper.countUnredeemedByChapter(chapterId);
        if (held <= 0) {
            return Double.POSITIVE_INFINITY;   // 还没卖出去,谈不上缺口
        }
        return nz(offerMapper.sumRemainingQuotaByChapter(chapterId)) / (double) held;
    }

    @Override
    public int chapterCoverageStatus(Long chapterId) {
        if (chapterId == null) {
            // ⚠️ chapterCoverageRatio 内部**没有** null 保护(不同于 chapterCapacity):
            // chapterId 传 null 时 `where chapter_id = null` 恒 false 返 0 → held=0 → 返 Infinity
            // ⇒ **静默放行**。在这里显式挡掉,别把「问错了问题」当成「供给充足」。
            return COVERAGE_GAP;
        }
        double ratio = chapterCoverageRatio(chapterId);
        if (Double.isInfinite(ratio)) {
            return COVERAGE_OK;   // 还没卖出去,谈不上缺口。⚠️ 别让 Infinity 流进算术/格式化
        }
        if (ratio < 1.0d) {
            return COVERAGE_GAP;      // 已售权益兑现不了 —— 缺口事件,硬停售
        }
        if (ratio < COVERAGE_WARN) {
            return COVERAGE_WARNING;  // 安全垫不足 —— 补招商/催配额
        }
        return COVERAGE_OK;
    }

    @Override
    public int topicCoverageStatus(Long topicId) {
        int worst = COVERAGE_OK;
        for (CmsTopicChapter c : requiredChapters(topicId)) {
            // 非权益档没有 quota 这回事:它的 ratio 恒 0(供给 0 ÷ 已售 n),那是量纲错配不是缺口。
            // 算进来会让任何开了计酬/引流档的 ③ 路线永远显示红色缺口 ⇒ 真缺口反而没人信。
            //
            // 但**零商家是真缺口**(D21 后半句):没人能履约,已售的票全砸在手里。判据是商家数不是配额
            // —— ratio 分不清「商家在但 quota_total=0」与「没有商家」,两者 ratio 都是 0。
            //
            // ⚠️ 同样先看 held:**还没卖出去就谈不上缺口**,与权益档口径对齐
            // (chapterCoverageStatus 在 held<=0 时返 OK)。缺口事件的语义是 §4.6「**已售出**的
            // 兑现不了 → 替代商家 → 失败即整单退」—— 零售出时没有任何单子要处置,报缺口是假警报,
            // 而本方法在主题详情读路径上(ApiTopicController),假警报会把 warn 刷成噪声,
            // 真缺口反而没人信。收钱那侧由 reserveEntitlements 的拒售闸独立守着,不靠这里。
            if (!usesQuota(c)) {
                if (entitlementMapper.countUnredeemedByChapter(c.getId()) > 0 && !hasLiveMerchant(c)) {
                    logger.warn("[D21 缺口] topicId={} chapterId={} 非权益档零生效商家,已售权益无人可核销,需走缺口处置",
                            topicId, c.getId());
                    worst = Math.max(worst, COVERAGE_GAP);
                }
                continue;
            }
            int s = chapterCoverageStatus(c.getId());
            if (s == COVERAGE_GAP) {
                // 缺口不可静默:已售权益兑现不了 = 真资损在路上(替代商家失败即整单全额退)。
                logger.warn("[P0-16 缺口] topicId={} chapterId={} 覆盖率跌破 1.0,已售权益兑现不了,需走缺口处置",
                        topicId, c.getId());
            }
            worst = Math.max(worst, s);   // 木桶效应:一章兑现不了,整张票就毁了
        }
        return worst;
    }

    // ------------------------------------------------------------ 购票预占

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reserveEntitlements(Long registrationId, Long topicId) {
        if (pureContentExploreService != null && pureContentExploreService.isPureContent(topicId)) return;
        List<CmsTopicChapter> required = requiredChapters(topicId);
        if (required.isEmpty()) {
            return;
        }

        // 章节行锁:让同章购票**串行**,并给全局加锁顺序定个锚(升序传参 = 升序加锁,防两个购票事务 ABBA)。
        //
        // ⚠️ **行锁本身挡不住超卖** —— 这一点初版注释写反了,真库实测打脸:B 确实在锁上阻塞了 0.81s,
        // 但醒来后的普通聚合读仍走旧 read view(RR 下 read view 在事务第一次普通读时建立,
        // 也就是上面的 requiredChapters),读不到 A 刚提交的 entitlement ⇒ 算出同样的容量 ⇒ 照样双双插入。
        // 真防线是下面用的 chapterCapacityForUpdate(当前读),不是这把锁。锁只管串行与 ABBA。
        List<Long> ids = new ArrayList<>();
        for (CmsTopicChapter c : required) {
            ids.add(c.getId());
        }
        Collections.sort(ids);
        List<Long> locked = chapterMapper.lockChaptersForCapacity(ids);
        if (locked == null || locked.size() != ids.size()) {
            throw new RuntimeException("章节数据异常,请稍后重试");
        }

        // 先全量校验再全量插入:不允许只发一半权益 —— 5 必选章的通票发出去 3 份,
        // 玩家玩到第 4 章才发现没得领,那时钱已收、体验已毁,只能整单退。
        //
        // ⚠️ 遍历 required(带档位)而不是 ids(裸 Long):容量闸**只对权益档成立**。
        // 非权益档的 offer 由 enroll 落 quota_total=0(与 DDL 默认一致)⇒ 容量恒 `0 − 已售 ≤ 0`
        // ⇒ 无条件跑闸会让计酬/引流档的 ③ 路线**一张票都卖不出去**,商家再入驻都没用。
        // 与 redeemChapter 的 consumeQuota 档位分支是同一条纪律(TermsMode.usesQuota 是单一真源)。
        for (CmsTopicChapter c : required) {
            if (!usesQuota(c)) {
                // D21 后半句:非权益档不吃容量闸,但吃**商家数**闸 —— 零商家 ⇒ 该章永远核销不了
                // ⇒ isExploreCompleted 恒 false ⇒ 该票永不通关 ⇒ 只能整单全额退。卖出去是
                // **确定性资损**,不是风险,所以拒在收钱之前。
                //
                // ⚠️ 这不是「重复的闸」:ApiTopicController 那句「硬停售只由 reserveEntitlements 承担」
                // 的论证前提是 `coverage<1.0 ⟺ capacity<0`,而该等价对非权益档不成立(见 hasLiveMerchant)。
                // 这里正是它指定的那道唯一闸要覆盖的新情况。
                if (!hasLiveMerchant(c)) {
                    throw new RuntimeException("该路线暂无商家提供服务,暂时无法购买");
                }
                continue;
            }
            // 必须是 ForUpdate 版(当前读)。用 chapterCapacity(普通读)就是超卖。
            if (chapterCapacityForUpdate(c.getId()) <= 0) {
                throw new RuntimeException("该路线权益名额不足,暂时无法购买");
            }
        }

        // ⚠️ 这里**不分档位**,与上面的容量闸刻意不对称:跳的是**闸**,不是**账**。
        // 非权益档章照样要建 entitlement —— resolveRedeemableChapters / redeemChapter /
        // isExploreCompleted 全靠它当地基,少建一条这三处当场瞎(扫不出码、核销拒、永不通关)。
        Date now = new Date();
        for (CmsTopicChapter c : required) {
            RegistrationEntitlement e = new RegistrationEntitlement();
            e.setRegistrationId(registrationId);
            e.setChapterId(c.getId());
            e.setTopicId(topicId);
            e.setStatus(0);                       // 0=应得,占容量
            e.setCreateTime(now);
            entitlementMapper.insertRegistrationEntitlement(e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int releaseOnRefund(Long registrationId, String reason) {
        return entitlementMapper.invalidateByRegistration(registrationId, reason);
    }

    // ------------------------------------------------------------ 核销

    @Override
    public List<Long> resolveRedeemableChapters(Long registrationId, Long topicId, Long merchantId) {
        if (registrationId == null || topicId == null || merchantId == null) {
            return Collections.emptyList();
        }
        // 该商家在本主题的生效供给章节。selectByTopic 已含 liveOffer 两条轴(status=1 且 valid_end 未过期)
        // —— 裸按 status=1 会把过期供给算进来,让商家核销一个他其实已经退出的章节。
        // ⚠️ mapper 没有 selectByTopicAndMerchant,但 selectByTopic 已够(一个主题的 offer 量级很小),
        //    不必为此加新 SQL。
        Set<Long> mine = new LinkedHashSet<Long>();
        List<MerchantChapterOffer> offers = offerMapper.selectByTopic(topicId);
        if (offers != null) {
            for (MerchantChapterOffer o : offers) {
                if (merchantId.equals(o.getMerchantId()) && o.getChapterId() != null) {
                    mine.add(o.getChapterId());
                }
            }
        }
        if (mine.isEmpty()) {
            return Collections.emptyList();
        }
        // 该票尚欠的章节(status=0 应得态)。已核销(1)/已失效(2)都不该再被扫。
        List<Long> hit = new ArrayList<Long>();
        List<RegistrationEntitlement> ents = entitlementMapper.selectByRegistration(registrationId);
        if (ents != null) {
            for (RegistrationEntitlement e : ents) {
                if (Integer.valueOf(0).equals(e.getStatus()) && mine.contains(e.getChapterId())) {
                    hit.add(e.getChapterId());
                }
            }
        }
        Collections.sort(hit);
        return hit;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long redeemChapter(Long registrationId, Long chapterId, Long merchantId, Long operatorId) {
        // ⓪ 请求级幂等:同一(票,章,商家)之前已成功核销过 ⇒ 直接返回原 redemptionId。
        //
        // ⚠️ 必须在 ① 之前。放到 ① 之后就等于没放:重试时 entitlement 已是 status=1,
        //    markRedeemed 的 CAS 返 0 → 抛「该章节权益不可核销」→ 商家看到的是 500,
        //    而这正是本闸要消灭的现象(扫码超时/网络重试/商家手抖重复点)。
        //
        // 为什么这不是「把并发也一起解决了」—— 它只保**顺序重试**:
        //   两个并发的同 key 请求都会查空 → 都进 ① → 一个赢、另一个 CAS 返 0 抛异常。
        //   那条路仍由 uk_redemption_idem_active 在库层兜底(1062),不会双花。
        //   本闸解决的是「第一次其实成功了,但响应没回到商家手里」这一类,不是竞态。
        String idemKey = redeemKey(registrationId, chapterId, merchantId);
        Redemption done = redemptionMapper.selectActiveByIdempotencyKey(idemKey);
        if (done != null) {
            // ★★ [资损] 重放之前必须复核这张票**现在**还有效。
            //
            // 退款不会撤销已成立的核销:商家当时确实端出去了,那笔 redemption 保持 ACTIVE、
            // 对应 entitlement 保持 status=1(invalidateByRegistration 只动 status=0 的行)。
            // ⇒ 「已退款 + 该章已核销」的票,上面那行照样查得到 ACTIVE 流水。
            //
            // 改动前这条路是 ① 的 CAS 挡着的(要求 status=0,已核销的返 0 → 抛「已核销/已退款」)。
            // ⓪ 抢在 CAS 前面 = 把那道保护绕过去了 ⇒ 已退款玩家拿同一个码回原商家,
            // 商家端显示「核销成功」(实为重放旧流水)⇒ 二次端出权益。
            //
            // ⚠️ 为什么不能只靠 resolveExploreScanContext:1160 那道调用方闸:
            //    那是**闸放错层**。今天它确实拦着,但任何新入口(后台补核销、商家端批量、运营工具)
            //    漏了 payment_status 校验,这个洞就当场复活且零告警。本轮已经因为同型问题栽过四次。
            // ⚠️ 查询只发生在**幂等命中**这条罕见路径上,正常首次核销一次都不多查。
            //
            // ★ 抛**裸 RuntimeException** 而不是 IllegalStateException,这不是随手选的:
            //   ApiRegistrationController:1185/1188 按类型分档 —— IllegalStateException = 业务拒绝
            //   **静默**透出;裸 RuntimeException = logger.error 记一条。
            //   而走到这里意味着**调用方漏了 payment_status 校验**(正常路径根本到不了),
            //   正是上面那句「零告警」说的那种线上事故 ⇒ 必须记 error,否则新入口把闸绕开时
            //   闸虽然拦住了、却没有任何人知道有个入口是坏的。
            //   与同方法里的兄弟拒绝(「该章节权益不可核销」「名额已用完」)同档,
            //   也与 :1194 那句「宁可多记几条业务拒绝,也不要漏掉一次真故障」一致。
            CmsRegistration reg = cmsRegistrationMapper.selectCmsRegistrationById(registrationId);
            if (reg == null || !Integer.valueOf(2).equals(reg.getPaymentStatus())) {
                throw new RuntimeException(
                        "订单已退款或状态异常,不能重放核销,registrationId=" + registrationId);
            }
            return done.getId();
        }

        // ① **先** CAS 抢占 entitlement —— 这一行是核销与退款的唯一序列化点。
        //    顺序反了(先插流水再置 entitlement)会出现「钱退了货照发」:
        //    核销读到 status=0 判定可核销 → 退款并发置 status=2 提交 → 核销把 2 覆盖回 1
        //    ⇒ 玩家钱已退、商家咖啡已端出、还会进结算。
        //    带 status=0 条件的 CAS 让那一步变成 0 行,当场拒。
        if (entitlementMapper.markRedeemed(registrationId, chapterId) == 0) {
            throw new RuntimeException("该章节权益不可核销(未购买/已核销/已退款)");
        }

        // ② 找这家商家在这一章的生效供给,原子扣配额。
        MerchantChapterOffer offer = findLiveOffer(chapterId, merchantId);
        if (offer == null) {
            throw new RuntimeException("该商家在本章节没有生效的权益供给");
        }
        // ⚠️ 只有权益档走 quota:计酬/引流档不设接待上限(D21),它们的 quota_total 取 DDL 默认 0
        //    ⇒ 若无条件扣,consumeQuota 的 `quota_used < quota_total` 恒 false 返 0
        //    ⇒ 当场抛「名额已用完」整体回滚 ⇒ 非 PERK 档根本核销不了。这条与「入不入账」正交。
        if (usesQuota(offer)) {
            if (offerMapper.consumeQuota(offer.getId()) == 0) {
                // 条件 update 返 0 = 配额用尽或供给刚失效。抛出去让 ① 一起回滚,
                // 否则 entitlement 被吃掉但玩家没拿到东西。
                throw new RuntimeException("该商家的权益名额已用完");
            }
        }

        // ③ 插流水。唯一键 (registration, chapter) 兜住跨商家重复核销(V2 的根治):
        //    并发的第二家会撞 1062 → 本事务回滚 → ① 的 CAS 也一并撤销。
        Redemption r = new Redemption();
        r.setRegistrationId(registrationId);
        r.setChapterId(chapterId);
        r.setTopicId(offer.getTopicId());
        r.setMerchantId(merchantId);
        r.setOfferId(offer.getId());
        r.setOperatorId(operatorId);
        r.setStatus("ACTIVE");
        r.setIdempotencyKey(idemKey);   // 与 ⓪ 查的是同一个键,别在这里重算(改一处漏一处 = 幂等静默失效)
        r.setCreateTime(new Date());
        redemptionMapper.insertRedemption(r);
        return r.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean reverseRedemption(Long redemptionId, String reason, Long operatorUserId) {
        return reverseRedemption(redemptionId, reason, operatorUserId, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean reverseRedemption(Long redemptionId, String reason, Long operatorUserId,
                                     boolean clawbackRewards) {
        Redemption r = redemptionMapper.selectById(redemptionId);
        if (r == null) {
            throw new RuntimeException("核销记录不存在");
        }
        // 条件置位:0 行 = 已被撤销过。**必须在这里返回**,不能继续往下回补配额 ——
        // 重复撤销各回补一次会把 quota_used 扣成负数,凭空多出配额(库里 ck_offer_quota 会拦,
        // 但那是兜底不是设计;让它走到 CHECK 就已经是 500 了)。
        // 冲回「通关奖励」的判据是「这次撤销让主题从通关变成不通关」,必须在 reverse 之前读通关态。
        boolean wasCompleted = clawbackRewards && r.getTopicId() != null
                && isExploreCompleted(r.getRegistrationId(), r.getTopicId());
        if (redemptionMapper.reverse(redemptionId, reason) == 0) {
            return false;
        }
        // ⚠️ 这里**刻意不加档位分支**,与 redeemChapter 的 consumeQuota 不对称 —— 理由有二:
        //  1. 非 PERK 档核销时压根没扣(见 redeemChapter 的分支)⇒ quota_used 恒 0
        //     ⇒ releaseQuota 的 `quota_used > 0` 守卫让它本来就是 no-op,扣不穿。
        //  2. 更关键:按**当前**档位判会制造新洞。若某 offer 当初是 PERK(quota_used=3)、
        //     事后 terms_mode 被改成 REVSHARE,撤销当初那笔核销时,无条件回补是**对的**
        //     (撤销当初做过的事);按新档位跳过则 quota_used 永久卡住 ⇒ 容量永久少算 ⇒ 少卖。
        // ⇒ 回补的判据是「当初扣没扣」,而 quota_used > 0 正是这个事实的直接证据,
        //    比事后重读 terms_mode 更准。别改成读档位。
        if (r.getOfferId() != null) {
            offerMapper.releaseQuota(r.getOfferId());
        }
        if (r.getChapterId() != null && r.getChapterId() != 0L) {
            // 权益退回「应得」态,玩家可重新核销(uk 不含 status,失效行永久占坑 ⇒ 只能 UPDATE 不能 INSERT)
            entitlementMapper.restoreToPending(r.getRegistrationId(), r.getChapterId());
        }

        // ★ 与「首次核销就置 verification_status」成对:撤销到**一笔 ACTIVE 核销都不剩**时,清回未核销态。
        //
        // 不清的后果:这张票会为一笔**已经不存在**的核销永久停在「已核销」——
        // 退款被 RefundPolicy.verifiedVerdict 锁死(「已核销订单需人工核实」)、
        // 注销时被 isTicketFullyConsumed 当成已消费而隐形。店员误扫一下,玩家的票就废了。
        //
        // ⚠️ 「还剩不剩 ACTIVE」的判据**下推进 SQL 的 NOT EXISTS**,不在这里先查再判 ——
        // select 是普通读(RR 快照):两个运营并发各撤一笔时,双方快照里都看得见对方那笔仍 ACTIVE
        // ⇒ 双双跳过清位 ⇒ 零笔 ACTIVE 却永远停在「已核销」= 本闸要修的 bug 在并发下原样复活。
        // 同 chapterCapacityForUpdate 注释里那个真库实测过的 RR 陷阱。条件 update 走当前读才准。
        // ⚠️ 必须在 reverse 之后调:reverse 已把本条置成 REVERSED,NOT EXISTS 才不会把它算进去。
        boolean registrationNoLongerVerified = cmsRegistrationMapper.clearVerification(r.getRegistrationId()) == 1;

        // ★ 与「核销即计提」成对:撤销核销 ⇒ 冲正那笔平台应付。
        //
        // ⚠️ 必须在上面那道 `reverse(...) == 0 → return false` **之后** —— 那是重复撤销的唯一闸,
        //    抢在它前面冲正会让同一笔核销被冲两次,把应付冲成负数(平台反倒“欠”出一笔钱)。
        // ⚠️ 同事务:撤销提交了、冲正没落 = 平台继续欠着一笔已经不存在的核销的钱。
        // ⚠️ 冲正是**追加 REVERSAL 新行**,不改原行;计酬档以外的核销压根没计提过,
        //    reverseOnRedemption 自己会识别并空转,这里不必加档位分支(与上面 releaseQuota
        //    不加档位分支同一条理由:判据是「当初记没记」,不是「现在是什么档」)。
        merchantPayableService.reverseOnRedemption(redemptionId);

        // 主题完成的 V2 evidence 以 registration+topic 为来源事实。任一必选章撤销后若已不再通关，
        // 必须让 evidence、资格和活动 360 从当前 VALID 集立即重算，不能保留旧 progress 假绿。
        boolean completedNow = r.getTopicId() != null && isExploreCompleted(r.getRegistrationId(), r.getTopicId());
        if (r.getTopicId() != null && !completedNow) {
            officialEventV2Service.revokeThemeCompletion(r.getRegistrationId(), r.getTopicId(), "探索必选章核销已撤销", null);
        }

        // ★ 留痕(谁撤的/为什么/何时),与撤销同事务:
        //   撤销事实(reversed_at/reverse_reason)由上面 reverse() 写进 redemption,这里补**操作者**。
        //   ⚠️ 不能只靠后台 @Log 的 sys_oper_log:那份日志刻意不存请求参数(防 PII/token),
        //      所以它答不出「这次操作撤的是哪一条 redemption」——与撤销行对不上号就不算留痕。
        //   ⚠️ insert 返 0(或抛)必须让整个撤销回滚:审计缺失比撤销失败严重得多 ——
        //      一条撤了却查不出谁的核销,日后复盘时和没有撤销入口时代一个下场。
        writeReverseAudit(r, reason, operatorUserId);

        if (clawbackRewards) {
            CmsRegistration registration = cmsRegistrationMapper.selectCmsRegistrationById(r.getRegistrationId());
            if (registration == null || registration.getMemberId() == null) {
                throw new IllegalStateException("冲回奖励找不到报名单 registrationId=" + r.getRegistrationId());
            }
            redemptionRewardClawbackService.clawback(r, registration, registrationNoLongerVerified,
                    wasCompleted && !completedNow, reason, operatorUserId);
        }

        logger.info("核销已撤销 redemptionId={}, reason={}, operatorUserId={}", redemptionId, reason, operatorUserId);
        return true;
    }

    /** 撤销核销的追加式审计;request_id 用 redemptionId 定身份,重复撤销在 CAS 那步就进不来。 */
    private void writeReverseAudit(Redemption r, String reason, Long operatorUserId) {
        BusinessAuditEvent audit = new BusinessAuditEvent();
        // 域与「核销」那条审计同源(REGISTRATION/registrationId):同一张票的正向与反向事件落在
        // 同一个分区里,复盘时一次查询就能看到完整来龙去脉,不必跨域拼。
        audit.setDomainType("REGISTRATION");
        audit.setDomainId(r.getRegistrationId());
        audit.setOwnerMemberId(r.getMerchantId());
        audit.setActorType(BusinessAuditEvent.ACTOR_TYPE_SYS_USER);
        audit.setActorMemberId(operatorUserId);
        audit.setActorRoleCode("SYS_USER");
        audit.setAction("REDEMPTION_REVERSE");
        audit.setTargetType("REDEMPTION");
        audit.setTargetId(r.getId());
        audit.setRelationId(r.getChapterId());
        audit.setBeforeJson("{\"status\":\"ACTIVE\"}");
        audit.setAfterJson("{\"status\":\"REVERSED\"}");
        audit.setReason(reason);
        audit.setRequestId("redemption-reverse:" + r.getId());
        audit.setCreateTime(new Date());
        if (businessAuditEventMapper.insertBusinessAuditEvent(audit) != 1) {
            throw new RuntimeException("核销撤销审计写入失败,撤销已回滚");
        }
    }

    @Override
    public List<Redemption> listRedemptions(Long registrationId) {
        if (registrationId == null) {
            return Collections.emptyList();
        }
        List<Redemption> rows = redemptionMapper.selectByRegistration(registrationId);
        return rows == null ? Collections.<Redemption>emptyList() : rows;
    }

    // ------------------------------------------------------------ 通关

    @Override
    public boolean isExploreCompleted(Long registrationId, Long topicId) {
        if (pureContentExploreService != null && pureContentExploreService.isPureContent(topicId)) {
            return pureContentExploreService.isCompleted(registrationId, topicId);
        }
        CompletionRule rule = completionRule(topicId);
        if (rule != null) {
            if (!rule.valid) {
                return false;
            }
            return rule.matches(CompletionCounts.from(
                    redemptionMapper.selectActiveCompletionCounts(registrationId, topicId)));
        }
        List<CmsTopicChapter> required = requiredChapters(topicId);
        if (required.isEmpty()) {
            return false;   // 没有必选章就没有「通关」可言,别误发勋章
        }
        return redemptionMapper.countActiveRequiredChaptersRedeemed(registrationId, topicId) >= required.size();
    }

    @Override
    public ExploreCompletion exploreCompletion(Long topicId) {
        if (pureContentExploreService != null && pureContentExploreService.isPureContent(topicId)) {
            Set<Long> participants = new LinkedHashSet<>();
            Set<Long> completed = new LinkedHashSet<>();
            for (Map<String, Object> row : pureContentExploreService.completionFacts(topicId)) {
                Long memberId = toLong(row.get("memberId"));
                if (memberId == null) continue;
                participants.add(memberId);
                if (toInt(row.get("completed")) == 1) completed.add(memberId);
            }
            return new ExploreCompletion(participants, completed);
        }
        CompletionRule rule = completionRule(topicId);
        if (rule != null) {
            if (!rule.valid) {
                return new ExploreCompletion(Collections.<Long>emptySet(), Collections.<Long>emptySet());
            }
            Set<Long> participants = new LinkedHashSet<Long>();
            Set<Long> completed = new LinkedHashSet<Long>();
            List<Map<String, Object>> rows = redemptionMapper.selectCompletionCountsByTopic(topicId);
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    Long memberId = toLong(row.get("memberId"));
                    if (memberId == null) {
                        continue;
                    }
                    participants.add(memberId);
                    if (rule.matches(CompletionCounts.from(row))) {
                        completed.add(memberId);
                    }
                }
            }
            return new ExploreCompletion(participants, completed);
        }
        List<CmsTopicChapter> required = requiredChapters(topicId);
        if (required.isEmpty()) {
            // 与 isExploreCompleted 同口径:没有必选章就没有「通关」可言 ⇒ **全员 false**,不是全员 true。
            // ⚠️ 这条在批量版里最容易写错(顺手返 allMembers 就把没配章的主题全判成通关了)。
            return new ExploreCompletion(Collections.<Long>emptySet(), Collections.<Long>emptySet());
        }
        // 一次批量查询同时喂两个集合 —— 分子分母必须同口径,分开查就给了「只更新一个」的机会。
        Set<Long> participants = new LinkedHashSet<Long>();
        Set<Long> completed = new LinkedHashSet<Long>();
        for (Map<String, Object> row : redeemedCounts(required)) {
            Long m = toLong(row.get("memberId"));
            if (m == null) {
                continue;
            }
            participants.add(m);            // 有过核销就算参与
            if (toInt(row.get("cnt")) >= required.size()) {
                completed.add(m);           // 必选章全核销才算通关
            }
        }
        return new ExploreCompletion(participants, completed);
    }

    /** 批量查:每主题恒 1 次(requiredChapters 那次另算),与玩家数无关。 */
    private List<Map<String, Object>> redeemedCounts(List<CmsTopicChapter> required) {
        List<Long> chapterIds = new ArrayList<Long>();
        for (CmsTopicChapter c : required) {
            chapterIds.add(c.getId());
        }
        if (chapterIds.isEmpty()) {
            // 防御:required 非空但 id 全为 null 时,空 list 进 foreach 会生成 `in ()` 语法错。
            return Collections.emptyList();
        }
        List<Map<String, Object>> rows = redemptionMapper.selectRedeemedCountsByChapters(chapterIds);
        return rows == null ? Collections.<Map<String, Object>>emptyList() : rows;
    }

    private int toInt(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    private Long toLong(Object v) {
        return v instanceof Number ? Long.valueOf(((Number) v).longValue()) : null;
    }

    private static final ObjectMapper COMPLETE_RULE_READER = new ObjectMapper();

    /** NULL/空串 = 存量规则；非空但损坏 = fail-closed，绝不静默退回旧规则提前发奖。 */
    private CompletionRule completionRule(Long topicId) {
        CmsTopic topic = topicMapper.selectCmsTopicById(topicId);
        String raw = topic == null ? null : topic.getCompleteRuleJson();
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            JsonNode json = COMPLETE_RULE_READER.readTree(raw);
            int roles = json.path("distinctMerchantRoles").asInt(0);
            int groups = json.path("needGroups").asInt(0);
            if (roles <= 0 || groups <= 0) {
                throw new IllegalArgumentException("完成规则阈值必须为正整数");
            }
            return new CompletionRule(true, roles, groups);
        } catch (Exception e) {
            logger.error("主题完成规则损坏，拒绝通关 topicId={}", topicId, e);
            return new CompletionRule(false, 0, 0);
        }
    }

    private static final class CompletionRule {
        private final boolean valid;
        private final int distinctMerchantRoles;
        private final int needGroups;

        private CompletionRule(boolean valid, int distinctMerchantRoles, int needGroups) {
            this.valid = valid;
            this.distinctMerchantRoles = distinctMerchantRoles;
            this.needGroups = needGroups;
        }

        private boolean matches(CompletionCounts counts) {
            return counts != null
                    && counts.merchantRoles >= distinctMerchantRoles
                    && counts.needGroups >= needGroups;
        }
    }

    private static final class CompletionCounts {
        private final int merchantRoles;
        private final int needGroups;

        private CompletionCounts(int merchantRoles, int needGroups) {
            this.merchantRoles = merchantRoles;
            this.needGroups = needGroups;
        }

        private static CompletionCounts from(Map<String, Object> row) {
            if (row == null) {
                return null;
            }
            return new CompletionCounts(number(row.get("merchantRoles")), number(row.get("needGroups")));
        }

        private static int number(Object value) {
            return value instanceof Number ? ((Number) value).intValue() : 0;
        }
    }

    @Override
    public List<RegistrationEntitlement> listEntitlements(Long registrationId) {
        return entitlementMapper.selectByRegistration(registrationId);
    }

    @Override
    public boolean isTicketFullyConsumed(Integer verificationStatus, Long registrationId) {
        if (!Integer.valueOf(1).equals(verificationStatus)) {
            return false;   // 没被核销过,当然没消费完
        }
        // ③ 例外:置了位只说明「被核销过至少一次」。还欠着章节就不算消费完。
        // 非 ③ 的票压根没有 entitlement ⇒ hasPendingEntitlements 返 false ⇒ 与老口径逐字一致。
        // ⚠️ 这一步要查库 ⇒ 调用方若在**循环里**调(如 hasActiveRegistration 遍历某人全部报名单),
        //    会退化成 N+1。宿主已按 verification_status==1 短路,但一个老玩家可能有几百张已核销的 ①。
        //    真要收敛,调用方应先用 purchase_kind 过滤 —— 见 AccountDeregistrationServiceImpl 的用法。
        return !hasPendingEntitlements(registrationId);
    }

    @Override
    public boolean hasPendingEntitlements(Long registrationId) {
        if (registrationId == null) {
            return false;
        }
        List<RegistrationEntitlement> list = entitlementMapper.selectByRegistration(registrationId);
        if (list == null) {
            return false;
        }
        for (RegistrationEntitlement e : list) {
            if (Integer.valueOf(0).equals(e.getStatus())) {
                return true;   // 还欠着章节 ⇒ 该继续出码
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 内部

    private List<CmsTopicChapter> requiredChapters(Long topicId) {
        if (topicId == null) {
            return Collections.emptyList();
        }
        List<CmsTopicChapter> list = chapterMapper.selectRequiredByTopic(topicId);
        return list == null ? Collections.<CmsTopicChapter>emptyList() : list;
    }

    /**
     * 该 offer 是否走 quota 容量链。
     *
     * <p><b>判据取 offer.termsMode(快照)而非 chapter.termsMode(口径真源)</b> —— 这两者刻意不同:
     * {@code quota_total/quota_used} 是 offer 自己的列,而「章节改档不追溯改写存量 offer」是已定的
     * 快照纪律。若改用章节口径,章节一旦从 PERK 翻成 REVSHARE,**存量 PERK offer 会突然停止扣配额**,
     * 而 entitlement 照发 ⇒ quota_used 不涨 ⇒ 供给账失真 ⇒ 超发。判「这家商家按什么条款供货」
     * 只能问它自己谈定的那份条款。
     *
     * <p>⚠️ <b>null 按 PERK 处理</b>(与 DDL {@code NOT NULL DEFAULT 'PERK'} 同口径)。方向是刻意的:
     * 直接写 {@code TermsMode.usesQuota(null)} 会返 false ⇒ **PERK offer 静默跳过扣配额 = 超发且零告警**;
     * 而把 REVSHARE 误当 PERK 只会响亮地抛「名额已用完」,不造成资损。fail-closed 朝响亮那侧倒。
     */
    private boolean usesQuota(MerchantChapterOffer offer) {
        String mode = offer.getTermsMode();
        return mode == null || TermsMode.usesQuota(mode);
    }

    /**
     * 该**章节**是否走 quota 容量链(购票预占 / 可售容量 / 缺口判定三处共用)。
     *
     * <p>与上面 offer 版的分工:章节是**口径真源**(D5 承诺按章展示、感知价值只加总权益档章节、
     * 容量链只对权益档成立),故「这条路线卖不卖得动」问章节;offer 版是**快照**,
     * 管「这家商家按什么条款供货、扣不扣它的配额」。两者刻意不同,别合并。
     *
     * <p>⚠️ <b>null 按 PERK 处理</b>,与 DDL {@code cms_topic_chapter.terms_mode DEFAULT 'PERK'} 同口径。
     * <b>绝不能直接写 {@code TermsMode.usesQuota(c.getTermsMode())}</b> —— 它的实现是
     * {@code PERK.equals(m)},{@code usesQuota(null)} 返 false ⇒ 权益档章节**静默跳过容量闸
     * = 超发且零告警**。fail-closed 朝少卖那侧倒:把非权益档误当权益档只会响亮地拒售,不造成资损。
     * 与 offer 版同型,那边的 javadoc 已经栽过一次。
     */
    private boolean usesQuota(CmsTopicChapter chapter) {
        String mode = chapter.getTermsMode();
        return mode == null || TermsMode.usesQuota(mode);
    }

    /**
     * 该章当前有没有**能按本章档位履约的生效商家**(D21 后半句:非权益档的缺口判据 =
     * 「该章有效商家数 ≥1」)。
     *
     * <p><b>为什么必须按 count 判、不能按 quota 判</b>:{@code Σ(quota_total − quota_used)}
     * 分不清「计酬商家已入驻(enroll 落 quota_total=0)」与「根本没有商家」—— 两者都是 0。
     * 权益档不吃这个亏(有商家就有正配额),非权益档则永远撞在这个盲区上。定稿写「商家数」
     * 而不是「配额」正是为此。
     *
     * <p><b>为什么还要比档位</b>:{@link TermsMode} 已定「offer.terms_mode 是入驻校验的对象,
     * 必须与章节一致」,但快照纪律允许章节改档后两者漂移(章节 PERK→REVSHARE,存量 offer 仍是
     * PERK 快照)。这种 offer 谈的是按配额供货,**没有承诺计酬档的接待义务** ⇒ 数进去就会卖出
     * 无人按该档履约的票。这里在读侧把那条不变量重新断言一次,fail-closed 朝少卖那侧倒
     * ——与本类既有纪律同向(把商家误判成「没有」只会响亮地拒售,不造成资损)。
     *
     * <p>零新 SQL:{@code selectByChapter} 已含 liveOffer 两条轴(status=1 且 valid_end 未过期),
     * 与 {@link #findLiveOffer} 同源。代价是每个非权益章 +1 次查询,一个主题的 offer 量级很小
     * (同 {@link #resolveRedeemableChapters} 的口径)。
     *
     * <p>⚠️ <b>这里是普通读,与紧邻的容量闸(必须 ForUpdate 当前读)刻意不同</b>,不是手滑:
     * <ul>
     *   <li>失守方向只有一个 —— 商家在购票**瞬间**退出、买家的旧 read view 还看得见他 ⇒ 卖出一张
     *       随即无人可核销的票。但这正是定稿 §4.6「商家退出/无法补充的兜底」本来就要处理的场景
     *       (缺口事件 → 替代商家 → 失败即整单全额退),不是本闸新开的洞;本闸防的是**恒久零商家**。</li>
     *   <li>反方向(商家刚入驻、旧快照没看见)是少卖,fail-closed 无害。</li>
     *   <li>容量闸不能用普通读是因为它数的是**并发买家互相看不见对方**(真库实测 8 买家 3 座全买进),
     *       商家入驻/退出没有那种高并发争抢。</li>
     * </ul>
     * mapper 没有 {@code selectByChapterForUpdate};真要收紧得加新 SQL + mapper 测试,
     * 与本次「零新 SQL」的取舍冲突。若将来 ③ 上量后发现这个窗口真被踩到,再补不迟。
     */
    private boolean hasLiveMerchant(CmsTopicChapter chapter) {
        List<MerchantChapterOffer> offers = offerMapper.selectByChapter(chapter.getId());
        if (offers == null) {
            return false;
        }
        for (MerchantChapterOffer o : offers) {
            if (effectiveMode(o.getTermsMode()).equals(effectiveMode(chapter.getTermsMode()))) {
                return true;
            }
        }
        return false;
    }

    /** null 按 PERK(与两处 DDL {@code DEFAULT 'PERK'} 同口径),与 usesQuota 的 null 处理同向。 */
    private String effectiveMode(String mode) {
        return mode == null ? TermsMode.PERK : mode;
    }

    /** uk_offer_merchant_chapter 保证一个商家一章最多一行,故取到即唯一。 */
    private MerchantChapterOffer findLiveOffer(Long chapterId, Long merchantId) {
        List<MerchantChapterOffer> offers = offerMapper.selectByChapter(chapterId);
        if (offers == null) {
            return null;
        }
        for (MerchantChapterOffer o : offers) {
            if (o.getMerchantId() != null && o.getMerchantId().equals(merchantId)) {
                return o;
            }
        }
        return null;
    }

    /** 与 SettlementEntryServiceImpl 的幂等键同构;核销与撤销用不同前缀,各自幂等。 */
    private String redeemKey(Long registrationId, Long chapterId, Long merchantId) {
        return "redeem:" + registrationId + ":" + chapterId + ":" + merchantId;
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
