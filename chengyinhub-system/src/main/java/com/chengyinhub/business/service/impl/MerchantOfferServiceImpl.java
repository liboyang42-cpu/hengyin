package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.notify.NotifyEvent;
import com.chengyinhub.business.notify.NotifyMessage;
import com.chengyinhub.business.notify.WxSubscribeChannel;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.ISystemNotificationIntentService;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CoopPerk;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.MerchantCircleSupplyProfile;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.MerchantChapterApplicationMapper;
import com.chengyinhub.business.mapper.MerchantCircleSupplyProfileMapper;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IMerchantChapterApplicationService;
import com.chengyinhub.business.service.IMerchantOfferService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.business.service.ITopicSaleCanonicalMutationService;
import com.chengyinhub.business.support.ChapterRecruitmentRules;
import com.chengyinhub.business.support.CircleSupplyPolicy;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.TermsMode;
import com.chengyinhub.common.constant.RecruitmentWindowPolicy;
import com.chengyinhub.common.constant.CoopTermsAxes;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;

/**
 * 商家入驻章节实现(P0-12)。
 *
 * <p>本类的校验与 DDL 的 {@code ck_offer_terms} 是**同一套规则的两层**:
 * 应用层先拒是为了给商家一句人话,而不是让他吃 MySQL 的 3819 CHECK 违反。
 * ⚠️ 两层必须同口径 —— 应用层放宽了,DDL 会兜住(500);应用层收严了,是本层说了算。
 * 改规则时两边一起改,别只改一边。
 */
@Service
public class MerchantOfferServiceImpl implements IMerchantOfferService {

    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.service.support.TopicSupplyReviewService supplyReviewService;

    @Autowired
    private MerchantChapterOfferMapper offerMapper;

    @Autowired
    private com.chengyinhub.business.mapper.RegistrationEntitlementMapper registrationEntitlementMapper;

    @Autowired
    private com.chengyinhub.business.service.support.CommittedBusinessNotice businessNotice;

    @Autowired
    private com.chengyinhub.business.service.INotifyService notifyService;

    @Autowired
    private ISystemNotificationIntentService systemNotificationIntents;

    @Autowired
    private IImService imService;

    @Autowired
    private WxSubscribeChannel ownerNoticeWxChannel;

    @Autowired
    @Qualifier("scheduledExecutorService")
    private ScheduledExecutorService ownerNoticeExecutor;

    @Autowired
    private MerchantChapterApplicationMapper applicationMapper;

    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired
    private MerchantCircleSupplyProfileMapper circleSupplyMapper;

    @Autowired
    private CmsTopicChapterMapper chapterMapper;

    @Autowired
    private CmsTopicMapper cmsTopicMapper;

    @Autowired
    private CoopPerkMapper coopPerkMapper;

    @Autowired
    private IProductRoleService productRoleService;

    @Autowired
    private ICoopInviteService coopInviteService;

    @Autowired
    private ICmsRegistrationMerchantService registrationMerchantService;

    /** V1 开放招商章节的授权源：发布者审核通过的 chapter 申请。 */
    @Autowired
    private IMerchantChapterApplicationService merchantChapterApplicationService;

    @Autowired
    private ISysCategoryService sysCategoryService;

    @Autowired
    private ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;

    @Autowired(required = false)
    private ITopicSaleCanonicalMutationService canonicalMutationService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long enroll(MerchantChapterOffer offer, Long merchantId) {
        if (offer == null || offer.getChapterId() == null) {
            throw new IllegalStateException("缺少章节");
        }
        if (merchantId == null) {
            throw new IllegalStateException("请先登录");
        }

        // ★ 章节行锁:让同章入驻**串行**,否则 assertMaxMerchant 是读-判-写无锁序列 ——
        // 两个商家同时入驻会双双读到 n < cap 然后各插一行,把上限撑破。
        // uk_offer_merchant_chapter 只挡「同一商家同一章重复」,挡不住这个;DDL 侧也没有任何
        // CHECK/触发器兜底(max_merchant 纯靠应用层数)。
        // ⚠️ 复用 reserveEntitlements 用的同一把锁(lockChaptersForCapacity),锁序天然一致,不引入 ABBA。
        supplyReviewService.lockBeforeChapter(offer.getChapterId());
        if (chapterMapper.lockChaptersForCapacity(
                java.util.Collections.singletonList(offer.getChapterId())).isEmpty()) {
            throw new IllegalStateException("章节不存在");
        }

        CmsTopicChapter chapter = chapterMapper.selectCmsTopicChapterById(offer.getChapterId());
        if (chapter == null) {
            throw new IllegalStateException("章节不存在");
        }

        // ★ 只有 ③ 的章节能被入驻。①/② 主题的章节入驻了也永远不会被消费 ——
        // reserveEntitlements 只对 purchase_kind==EXPLORE_PASS 触发 ⇒ ①② 的票不建 entitlement
        // ⇒ 这行 offer 的 quota 永不消耗,纯占着 max_merchant 名额、还让 chapterCapacity 算出个没意义的数。
        // 生产实测 34 个存量章节全属 ① 主题 ⇒ 误操作空间不小,fail-closed 拒掉。
        // ⚠️ 行锁读(不是裸读):与 confirmPrice 的 selectCmsTopicByIdForUpdate 串行,堵住
        // 「confirmPrice 读完阵容 → enroll 插一行 → confirmPrice 提交」的 TOCTOU 悬空窗 ——
        // 那时定价假设的成本与真实应付已经对不上,而必亏闸不会再跑第二次。
        // 与 acceptInviteWithLock 对同一不变式的做法同源。
        // 锁序:章节锁 → 主题锁;confirmPrice 只锁主题不锁章节(PricingServiceImpl 没注入章节
        // mapper),无 topic→chapter 路径 ⇒ 不构成 ABBA。
        CmsTopic topic = chapter.getTopicId() == null ? null
                : cmsTopicMapper.selectCmsTopicByIdForUpdate(chapter.getTopicId());
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag()) || topic.getProductType() == null
                || topic.getProductType() != ProductType.FREE_EXPLORE) {
            throw new IllegalStateException("该章节所属路线不是自由定向,不支持商家入驻");
        }

        // 关联只能来自主办批准的申请，不接受商家自行指定原供给。
        Long replacementOfOfferId = merchantChapterApplicationService.approvedReplacementOfferId(
                chapter.getId(), merchantId);
        offer.setReplacementOfOfferId(replacementOfOfferId);
        MerchantChapterOffer original = null;
        if (replacementOfOfferId != null) {
            original = offerMapper.selectByIdForUpdate(replacementOfOfferId);
            if (original == null || !topic.getId().equals(original.getTopicId())
                    || !chapter.getId().equals(original.getChapterId())
                    || merchantId.equals(original.getMerchantId())
                    || !Integer.valueOf(0).equals(original.getStatus())
                    || original.getWithdrawReason() == null || original.getWithdrawReason().trim().isEmpty()
                    || offerMapper.selectReplacementForUpdate(replacementOfOfferId) != null) {
                throw new IllegalStateException("原退出供给或补位邀请已变化，请刷新后确认");
            }
        }

        if (original == null) {
            if (TopicLifecycleState.commercialTermsFrozen(topic.getLifecycle())) {
                throw new IllegalStateException("主题已锁价,不能再入驻");
            }
            RecruitmentWindowPolicy.assertOpen(topic.getLifecycle(), topic.getStatus(), topic.getUserStatus(),
                    topic.getRecruitDeadline(), DateUtils.getNowDate());
        } else {
            com.chengyinhub.business.support.OrdinarySupplyReplacementPolicy.assertOpen(topic);
        }
        assertEnrollmentAuthorized(chapter, merchantId);

        MerchantCircleSupplyProfile circleSupply = offer.getCircleSupplyProfile();
        boolean circleTopic = topic.getCircleThemeCode() != null
                && !topic.getCircleThemeCode().trim().isEmpty();
        if (circleTopic) {
            if (!Integer.valueOf(1).equals(chapter.getCircleEnabled())
                    || chapter.getCircleCandidateCode() == null) {
                throw new IllegalStateException("该圈层候选未被城市实例启用");
            }
            if (circleSupply == null) {
                throw new IllegalStateException("请确认商家现成供给");
            }
            Date validationTime = DateUtils.getNowDate();
            circleSupply.setCheckedAt(validationTime);
            CircleSupplyPolicy.assertValid(topic.getCircleThemeCode(), chapter.getCirclePolicy(),
                    circleSupply, validationTime);
            circleSupply.setId(null);
            circleSupply.setOfferId(null);
            List<MerchantChapterOffer> existingOffers = offerMapper.selectByTopic(chapter.getTopicId());
            if (existingOffers != null) {
                for (MerchantChapterOffer existing : existingOffers) {
                    if (merchantId.equals(existing.getMerchantId())) {
                        throw new IllegalStateException("同一商家在一个圈层实例只能绑定一个现成供给");
                    }
                }
            }
        } else if (circleSupply != null) {
            throw new IllegalStateException("普通主题不能提交圈层供给声明");
        }

        String mode = offer.getTermsMode();
        if (!TermsMode.isValid(mode)) {
            throw new IllegalStateException("条款档不合法");
        }
        // 档必须与章节一致:章节才是口径真源(D5承诺按章展示、感知价值只加总权益档章节、
        // 容量链只对权益档成立)。混档章节里这三句全都答不出来,故入驻时对齐,落库后是快照。
        if (!mode.equals(chapter.getTermsMode())) {
            throw new IllegalStateException("本章节是「" + modeLabel(chapter.getTermsMode())
                    + "」,不能按「" + modeLabel(mode) + "」入驻");
        }
        CoopTermsAxes.Value axes = CoopTermsAxes.fromTermsMode(mode);
        offer.setCompensationMode(axes.getCompensationMode());
        offer.setBenefitMode(axes.getBenefitMode());

        // V1 开放招商的 PERK 供给来自商家自己的常备模板。模板不是 coop_perk，必须先复制为
        // 独立快照再落 offer；这样模板被改/删也不会追溯改掉已生效供给。
        boolean recruitmentPerk = TermsMode.PERK.equals(mode)
                && Integer.valueOf(1).equals(chapter.getRecruitEnabled());
        if (recruitmentPerk) {
            if (offer.getPerkTemplateId() == null) {
                throw new IllegalStateException("请选择要供给的权益模板");
            }
            offer.setPerkId(coopInviteService.materializeStandalonePerkFromTemplate(
                    offer.getPerkTemplateId(), merchantId, offer.getQuotaTotal()));
        }

        CoopPerk perk = assertTermsShape(offer, mode, chapter, merchantId);
        // 模板生成的是独立权益快照，供给自身的有效期也必须随快照冻结；不信前端传的 validEnd，
        // 否则商家可把一张短期券伪装成长效供给，liveOffer 会继续把它算进容量和核销池。
        if (recruitmentPerk) {
            offer.setValidEnd(perk.getValidEnd());
        }
        assertMaxMerchant(chapter);

        // topicId 从章节反查,不信前端传的 —— 它是容量聚合的分组键(idx_offer_topic),
        // 传错会让这行供给算到别的主题头上。
        offer.setTopicId(chapter.getTopicId());
        offer.setMerchantId(merchantId);
        // 玩法边界随承接冻结(2026-08-06):章节条款主办方随时可改,不冻的话
        // 商家按 A 条款承接、主办方事后改成 B,他已上线的点位会突然「超标」。
        // 与上面 coop_perk 快照、valid_end 强制复制是同一范式。
        // ⚠️ 只冻不读客户端传的值 —— 客户端能改条款就等于自己给自己放宽边界。
        offer.setAllowedValidationMethods(original == null ? chapter.getAllowedValidationMethods()
                : original.getAllowedValidationMethods());
        offer.setMaxNodeXp(original == null ? chapter.getMaxNodeXp() : original.getMaxNodeXp());
        if (original != null) {
            if (!java.util.Objects.equals(original.getPayerClubId(), offer.getPayerClubId()))
                throw new IllegalStateException("补位供给不能改变付款俱乐部");
            com.chengyinhub.business.support.OrdinarySupplyReplacementPolicy.assertCompatible(original, offer);
        }
        offer.setQuotaUsed(0);
        offer.setStatus(original == null ? 1 : 0); // 补位供给经点位与条款重审后才生效。
        offer.setCreateTime(DateUtils.getNowDate());
        // 非 PERK 档不走容量链,quota_total 落 0(与 DDL 默认一致);
        // consumeQuota 已按档位分支跳过它们,这里的 0 不会把核销卡死。
        if (!TermsMode.usesQuota(mode)) {
            offer.setQuotaTotal(0);
        }

        try {
            offerMapper.insertMerchantChapterOffer(offer);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // uk_offer_merchant_chapter (chapter_id, merchant_id):一个商家一章最多一行。
            // 这条唯一键是 findLiveOffer「取到即唯一」的依据,不能靠插多行再挑。
            throw new IllegalStateException("您已入驻本章节,请勿重复提交");
        }
        if (circleTopic) {
            if (offer.getId() == null) {
                throw new IllegalStateException("供给快照未生成主键");
            }
            circleSupply.setOfferId(offer.getId());
            circleSupply.setVersionNo(1);
            circleSupply.setCurrent(1);
            if (circleSupplyMapper.insert(circleSupply) != 1) {
                throw new IllegalStateException("圈层供给声明保存失败");
            }
            invalidateCircleReview(topic.getId());
        }
        invalidateApprovedCanonical(chapter.getTopicId(), "offer-enroll");
        return offer.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reconfirmCircleSupply(Long offerId, MerchantCircleSupplyProfile profile, Long merchantId) {
        if (offerId == null || profile == null) throw new IllegalStateException("缺少圈层供给声明");
        if (merchantId == null) throw new IllegalStateException("请先登录");
        MerchantChapterOffer offer = offerMapper.selectById(offerId);
        if (offer == null || !merchantId.equals(offer.getMerchantId())) {
            throw new IllegalStateException("只能重新确认自己的供给");
        }
        if (!Integer.valueOf(1).equals(offer.getStatus())) {
            throw new IllegalStateException("供给已停用，不能重新确认");
        }
        CmsTopicChapter chapter = chapterMapper.selectCmsTopicChapterById(offer.getChapterId());
        CmsTopic topic = offer.getTopicId() == null ? null
                : cmsTopicMapper.selectCmsTopicByIdForUpdate(offer.getTopicId());
        if (chapter == null || topic == null || topic.getCircleThemeCode() == null
                || chapter.getCircleCandidateCode() == null
                || !Integer.valueOf(1).equals(chapter.getCircleEnabled())) {
            throw new IllegalStateException("该供给不属于当前启用的圈层候选");
        }
        Date validationTime = DateUtils.getNowDate();
        profile.setCheckedAt(validationTime);
        CircleSupplyPolicy.assertValid(topic.getCircleThemeCode(), chapter.getCirclePolicy(),
                profile, validationTime);
        MerchantCircleSupplyProfile current = circleSupplyMapper.selectByOfferId(offerId);
        if (current == null || current.getVersionNo() == null) {
            throw new IllegalStateException("圈层供给声明不存在");
        }
        if (!sameCircleSupplyFacts(current, profile)) {
            throw new IllegalStateException("价格、时段、入口或供给资料变化时请先暂停模板，由城市代理补足并重新复核");
        }
        profile.setId(null);
        profile.setOfferId(offerId);
        profile.setVersionNo(current.getVersionNo() + 1);
        profile.setCurrent(1);
        if (circleSupplyMapper.retireCurrentSnapshot(offerId) != 1
                || circleSupplyMapper.insert(profile) != 1) {
            throw new IllegalStateException("圈层供给声明版本更新失败");
        }
        MerchantCircleSupplyProfile readback = circleSupplyMapper.selectByOfferId(offerId);
        CircleSupplyPolicy.assertValid(topic.getCircleThemeCode(), chapter.getCirclePolicy(),
                readback, validationTime);
        if (readback == null || !profile.getVersionNo().equals(readback.getVersionNo())
                || !Integer.valueOf(1).equals(readback.getCurrent())
                || !same(profile.getSupplyName(), readback.getSupplyName())
                || !same(profile.getSupplyKind(), readback.getSupplyKind())
                || !sameDecimal(profile.getRegularPrice(), readback.getRegularPrice())
                || !same(profile.getActionType(), readback.getActionType())
                || !same(profile.getActionValue(), readback.getActionValue())
                || !same(profile.getAvailableHours(), readback.getAvailableHours())
                || !same(profile.getMinPeople(), readback.getMinPeople())
                || !same(profile.getMaxPeople(), readback.getMaxPeople())
                || !same(profile.getDurationMinutes(), readback.getDurationMinutes())
                || !same(profile.getSplitBookingAllowed(), readback.getSplitBookingAllowed())
                || !same(profile.getOptionalBenefit(), readback.getOptionalBenefit())
                || !same(profile.getBenefitRules(), readback.getBenefitRules())
                || !same(profile.getMerchantRules(), readback.getMerchantRules())
                || !sameSecond(profile.getCheckedAt(), readback.getCheckedAt())
                || !same(profile.getGuardianPresentThroughout(), readback.getGuardianPresentThroughout())
                || !same(profile.getMerchantChildcareRequired(), readback.getMerchantChildcareRequired())
                || !same(profile.getAlcoholInvolved(), readback.getAlcoholInvolved())
                || !same(profile.getLegalDrinkingAge(), readback.getLegalDrinkingAge())
                || !same(profile.getLegalDrinkingAgeRule(), readback.getLegalDrinkingAgeRule())
                || !same(profile.getAlcoholWarning(), readback.getAlcoholWarning())) {
            throw new IllegalStateException("圈层供给声明回读不一致");
        }
        invalidateCircleReview(topic.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reconfirmCurrentCircleSupply(Long offerId, Long merchantId) {
        MerchantCircleSupplyProfile current = circleSupplyMapper.selectByOfferId(offerId);
        if (current == null) throw new IllegalStateException("圈层供给声明不存在");
        MerchantCircleSupplyProfile next = new MerchantCircleSupplyProfile();
        org.springframework.beans.BeanUtils.copyProperties(current, next);
        next.setId(null);
        next.setCheckedAt(DateUtils.getNowDate());
        reconfirmCircleSupply(offerId, next, merchantId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deactivateOwnCircleSupply(Long offerId, Long merchantId) {
        if (offerId == null || merchantId == null || merchantId <= 0) {
            throw new IllegalStateException("请先登录");
        }
        MerchantChapterOffer offer = offerMapper.selectById(offerId);
        if (offer == null || !merchantId.equals(offer.getMerchantId())
                || circleSupplyMapper.selectByOfferId(offerId) == null) {
            throw new IllegalStateException("只能暂停自己的圈层供给");
        }
        if (Integer.valueOf(0).equals(offer.getStatus())) return;
        if (offerMapper.deactivate(offerId) != 1) {
            throw new IllegalStateException("供给状态刚发生变化，请刷新后重试");
        }
        invalidateApprovedCanonical(offer.getTopicId(), "circle-supply-owner-deactivate");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean withdrawOwnChapterOffer(Long offerId, Long merchantId, String reason) {
        if (offerId == null || offerId <= 0 || merchantId == null || merchantId <= 0) {
            throw new IllegalStateException("缺少供给记录或商家身份");
        }
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty() || why.length() > 500) {
            throw new IllegalStateException("请填写1至500字的退出原因");
        }
        MerchantChapterOffer location = offerMapper.selectById(offerId);
        if (location == null || !merchantId.equals(location.getMerchantId())) {
            throw new IllegalStateException("只能退出自己的章节供给");
        }
        // 与入驻、购票共用章节锁；锁序保持章节 -> 主题 -> 供给。
        Long chapterId = location.getChapterId();
        supplyReviewService.lockBeforeChapter(chapterId);
        List<Long> locked = chapterId == null ? null : chapterMapper.lockChaptersForCapacity(
                java.util.Collections.singletonList(chapterId));
        if (locked == null || locked.size() != 1) {
            throw new IllegalStateException("章节不存在，暂不能退出");
        }
        CmsTopicChapter chapter = chapterMapper.selectCmsTopicChapterById(chapterId);
        CmsTopic topic = chapter == null || chapter.getTopicId() == null ? null
                : cmsTopicMapper.selectCmsTopicByIdForUpdate(chapter.getTopicId());
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())
                || !Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())
                || (topic.getCircleThemeCode() != null && !topic.getCircleThemeCode().trim().isEmpty())) {
            throw new IllegalStateException("该供给不属于普通自由探索章节");
        }
        TopicLifecycleState lifecycle;
        try {
            lifecycle = TopicLifecycleState.fromCode(topic.getLifecycle());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("主题生命周期未知，暂不能退出");
        }
        if (lifecycle.isRunTerminal()) {
            throw new IllegalStateException("主题已结束，不能退出历史供给");
        }
        MerchantChapterOffer offer = offerMapper.selectByIdForUpdate(offerId);
        if (offer == null || !merchantId.equals(offer.getMerchantId())
                || !chapterId.equals(offer.getChapterId())
                || !topic.getId().equals(offer.getTopicId())
                || !topic.getId().equals(location.getTopicId())) {
            throw new IllegalStateException("供给归属已变化，请刷新后重试");
        }
        boolean required = !Integer.valueOf(0).equals(chapter.getRequired());
        if (Integer.valueOf(0).equals(offer.getStatus())) {
            if (offer.getWithdrawReason() != null && !offer.getWithdrawReason().trim().isEmpty()) {
                return required && !Integer.valueOf(1).equals(topic.getUserStatus());
            }
            throw new IllegalStateException("供给已被停用，请联系主办核对");
        }
        if (!Integer.valueOf(1).equals(offer.getStatus())) {
            throw new IllegalStateException("供给状态异常，暂不能退出");
        }
        // 锁价不阻止商家退出；只暂停新增报名，已售订单、履约应收和核销事实不变。
        boolean reviewInvalidated = supplyReviewService.invalidate(topic.getId(), merchantId);
        if ((required || reviewInvalidated) && Integer.valueOf(1).equals(topic.getUserStatus())
                && cmsTopicService.casUserStatus(topic.getId(), 1, 0) != 1) {
            throw new IllegalStateException("主题状态刚发生变化，请刷新后重试");
        }
        if (offerMapper.withdrawOwned(offerId, merchantId, why, required) != 1) {
            throw new IllegalStateException("供给状态刚发生变化，请刷新后重试");
        }
        applicationMapper.retireApprovedForSupplyExit(topic.getId(), chapterId, merchantId);
        invalidateApprovedCanonical(topic.getId(), "ordinary-chapter-supply-withdraw");
        registerOwnerWithdrawalNotice(offerId, topic.getMemberId(), "章节供给已退出",
                "主题「" + topic.getName() + "」有章节供给退出。" + (required ? "已暂停新报名，请核对补位方案。" : "请核对已有玩家的履约安排。"));
        businessNotice.executeAfterCommit("chapter-supply-withdraw-players", offerId, () -> {
            List<Long> affected = registrationEntitlementMapper.selectAffectedPaidMembers(topic.getId(), chapterId);
            boolean delivered = true;
            if (affected != null) for (Long playerId : new java.util.LinkedHashSet<>(affected)) {
                if (playerId != null && playerId > 0 && !playerId.equals(topic.getMemberId())) {
                    delivered = notifyService.notifyOfficialBroadcast(playerId, "已购章节供给有变化",
                            "主题「" + topic.getName() + "」有待核销章节的商家供给退出，请在出发前联系主办方确认履约安排。") && delivered;
                }
            }
            return delivered;
        });
        return required;
    }

    /** 原退出事务保存站内意图；微信仅保留首次提交后的一次尝试，不参加原意图恢复。 */
    private void registerOwnerWithdrawalNotice(Long offerId, Long recipient, String title, String body) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("退出通知原意图必须与供给退出同事务登记");
        }
        if (offerId == null || offerId <= 0) throw new IllegalStateException("退出通知原事件身份无效");
        String eventKey = "chapter-supply-withdraw-owner:" + offerId;
        Long intentId = systemNotificationIntents.findIntentId(eventKey);
        NotifyMessage wxMessage = null;
        if (intentId == null) {
            if (recipient == null || recipient <= 0 || title == null || body == null) {
                throw new IllegalStateException("退出通知原收件人或内容无效");
            }
            intentId = systemNotificationIntents.enqueue(recipient, ImMessage.MSG_TEXT,
                    title + "\n" + body, null, eventKey);
            if (intentId == null) throw new IllegalStateException("退出通知原意图未保存");
            wxMessage = NotifyMessage.of(NotifyEvent.OFFICIAL_BROADCAST, recipient)
                    .with("title", title).with("sub", body);
        }
        if (intentId <= 0) throw new IllegalStateException("退出通知原意图身份无效");
        final Long originalIntentId = intentId;
        final NotifyMessage originalWxMessage = wxMessage;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                businessNotice.dispatchInNewTransaction("chapter-supply-withdraw-owner", offerId,
                        () -> {
                            Long messageId = imService.deliverSystemNotification(originalIntentId);
                            return messageId != null && messageId > 0;
                        });
                if (originalWxMessage != null) scheduleOwnerWithdrawalWx(offerId, originalWxMessage);
            }
        });
    }

    private void scheduleOwnerWithdrawalWx(Long offerId, NotifyMessage originalWxMessage) {
        try {
            ownerNoticeExecutor.schedule(() -> {
                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                    org.slf4j.LoggerFactory.getLogger(MerchantOfferServiceImpl.class)
                            .error("退出主办方微信通知未执行：仍有数据库事务 offerId={}", offerId);
                    return;
                }
                try {
                    if (ownerNoticeWxChannel.send(originalWxMessage)) return;
                } catch (Throwable unknown) {
                    // 通道违约抛出也不能触发本地意图的微信重放。
                }
                org.slf4j.LoggerFactory.getLogger(MerchantOfferServiceImpl.class)
                        .warn("退出主办方微信通知未确认，保留原事件人工核实，不自动重发 offerId={}", offerId);
            }, 10, TimeUnit.MILLISECONDS);
        } catch (Throwable rejected) {
            org.slf4j.LoggerFactory.getLogger(MerchantOfferServiceImpl.class)
                    .warn("退出主办方微信通知未安排 offerId={}", offerId);
        }
    }

    private void invalidateCircleReview(Long topicId) {
        if (topicId == null) {
            throw new IllegalStateException("城市实例旧复核状态清除失败");
        }
        cmsTopicMapper.clearCircleReviewedAt(topicId, DateUtils.getNowDate());
        CmsTopic readback = cmsTopicMapper.selectCmsTopicById(topicId);
        if (readback == null || readback.getCircleReviewedAt() != null) {
            throw new IllegalStateException("城市实例旧复核状态清除失败");
        }
    }

    private static boolean sameCircleSupplyFacts(MerchantCircleSupplyProfile left,
                                                 MerchantCircleSupplyProfile right) {
        return left != null && right != null
                && same(left.getSupplyName(), right.getSupplyName())
                && same(left.getSupplyKind(), right.getSupplyKind())
                && sameDecimal(left.getRegularPrice(), right.getRegularPrice())
                && same(left.getActionType(), right.getActionType())
                && same(left.getActionValue(), right.getActionValue())
                && same(left.getAvailableHours(), right.getAvailableHours())
                && same(left.getMinPeople(), right.getMinPeople())
                && same(left.getMaxPeople(), right.getMaxPeople())
                && same(left.getDurationMinutes(), right.getDurationMinutes())
                && same(left.getSplitBookingAllowed(), right.getSplitBookingAllowed())
                && same(left.getOptionalBenefit(), right.getOptionalBenefit())
                && same(left.getBenefitRules(), right.getBenefitRules())
                && same(left.getMerchantRules(), right.getMerchantRules())
                && same(left.getWorkdayEveningCovered(), right.getWorkdayEveningCovered())
                && same(left.getSinglePersonAllowed(), right.getSinglePersonAllowed())
                && same(left.getDeclaredAgeRange(), right.getDeclaredAgeRange())
                && same(left.getGuardianMode(), right.getGuardianMode())
                && same(left.getGuardianPresentThroughout(), right.getGuardianPresentThroughout())
                && same(left.getMerchantChildcareRequired(), right.getMerchantChildcareRequired())
                && same(left.getAdultChildCapacity(), right.getAdultChildCapacity())
                && same(left.getPriceUnit(), right.getPriceUnit())
                && same(left.getRestrictions(), right.getRestrictions())
                && same(left.getToolsAnimalsMaterials(), right.getToolsAnimalsMaterials())
                && same(left.getIncidentProcess(), right.getIncidentProcess())
                && same(left.getCancellationRule(), right.getCancellationRule())
                && same(left.getPersonalPhotoAllowed(), right.getPersonalPhotoAllowed())
                && same(left.getPhotoAllowedArea(), right.getPhotoAllowedArea())
                && same(left.getTripodPolicy(), right.getTripodPolicy())
                && same(left.getLightingPolicy(), right.getLightingPolicy())
                && same(left.getPhotoDurationLimit(), right.getPhotoDurationLimit())
                && same(left.getNightHours(), right.getNightHours())
                && same(left.getLastEntry(), right.getLastEntry())
                && same(left.getAlcoholInvolved(), right.getAlcoholInvolved())
                && same(left.getLegalDrinkingAge(), right.getLegalDrinkingAge())
                && same(left.getLegalDrinkingAgeRule(), right.getLegalDrinkingAgeRule())
                && same(left.getAlcoholWarning(), right.getAlcoholWarning())
                && same(left.getEventEndAt(), right.getEventEndAt());
    }

    private static boolean same(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private static boolean sameSecond(Date left, Date right) {
        return left == null ? right == null
                : right != null && left.getTime() / 1000L == right.getTime() / 1000L;
    }

    @Override
    public List<MerchantChapterOffer> adminQueue(Long topicId, Integer status) {
        if (topicId == null ? !topicOperationsScopeAuthorizer.hasGlobalTopicScope()
                : !topicOperationsScopeAuthorizer.canViewTopic(topicId)) {
            throw new IllegalStateException("当前账号无主题供给查看范围");
        }
        return offerMapper.selectAdminQueue(topicId, status);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adjustTerms(MerchantChapterOffer patch, String operBy) {
        if (patch == null || patch.getId() == null) {
            throw new IllegalStateException("缺少供给记录id");
        }
        MerchantChapterOffer existing = offerMapper.selectById(patch.getId());
        if (existing == null) {
            throw new IllegalStateException("供给记录不存在");
        }
        ensureTopicScope(existing.getTopicId());
        if (existing.getStatus() == null || existing.getStatus() != 1) {
            throw new IllegalStateException("该供给已失效,不能调整条款;如需恢复请让商家重新入驻");
        }

        // ★ 与 enroll/reserveEntitlements 同一把章节行锁:调参会动 quota_total(容量的分子),
        // 不与购票预占串行的话 —— 预占在旧容量上算 capacity、这里同时把 quota_total 改小 ⇒ 超卖。
        supplyReviewService.lockBeforeChapter(existing.getChapterId());
        if (chapterMapper.lockChaptersForCapacity(
                java.util.Collections.singletonList(existing.getChapterId())).isEmpty()) {
            throw new IllegalStateException("章节不存在");
        }
        CmsTopicChapter chapter = chapterMapper.selectCmsTopicChapterById(existing.getChapterId());
        if (chapter == null) {
            throw new IllegalStateException("章节不存在");
        }
        // 即使异常历史数据已落库，也不能借后台调条款继续维持 V1 不支持的开放计酬供给。
        if (Integer.valueOf(1).equals(chapter.getRecruitEnabled())) {
            ChapterRecruitmentRules.assertV1RecruitmentTermsMode(chapter.getTermsMode(),
                    directPayer(chapter.getTopicId()) != null);
        }
        // 锁序同 enroll:章节锁 → 主题锁,无反向路径,不构成 ABBA。
        CmsTopic topic = chapter.getTopicId() == null ? null
                : cmsTopicMapper.selectCmsTopicByIdForUpdate(chapter.getTopicId());
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) {
            throw new IllegalStateException("主题不存在");
        }
        // 同 enroll 的锁价闸:定价假设冻结后再改条款(尤其抬人头费),结算真实成本会脱离定价假设
        // → margin 翻负 → 守恒断言拒结、款项悬空转人工。宁可让 BD 在锁价前谈完。
        if (TopicLifecycleState.commercialTermsFrozen(topic.getLifecycle())) {
            throw new IllegalStateException("主题已锁价,不能再调整条款");
        }

        String mode = patch.getTermsMode();
        if (!TermsMode.isValid(mode)) {
            throw new IllegalStateException("条款档不合法");
        }
        // 定档只能对齐**当前**章节口径(章节是口径真源:D5承诺/感知价值/容量链三处全按章节答)。
        // 章节改档后与口径脱节的存量快照,本方法正是把它对齐回来的入口;要整章换档,先改章节条款。
        // null 按 PERK(§2.2 判据,与 DDL DEFAULT 'PERK' 同口径)。
        String chapterMode = chapter.getTermsMode() == null ? TermsMode.PERK : chapter.getTermsMode();
        if (!mode.equals(chapterMode)) {
            throw new IllegalStateException("本章节口径是「" + modeLabel(chapterMode)
                    + "」,定档须对齐章节口径;要换档请先修改章节的供给条款");
        }

        // 复用入驻同一套三档形状闸(费率上限/权益门槛/二清边界)。
        // ⚠️ IDOR 校验对象是 offer 的商家,不是后台操作员 —— 权益归属仍必须是这家商家自己的。
        assertTermsShape(patch, mode, chapter, existing.getMerchantId());
        if (TermsMode.REVSHARE.equals(mode) && !java.util.Objects.equals(
                patch.getPayerClubId(), existing.getPayerClubId())) {
            throw new IllegalStateException("付款方已变化，请保留原供给并按新付款方重新承接");
        }

        int used = existing.getQuotaUsed() == null ? 0 : existing.getQuotaUsed();
        if (TermsMode.usesQuota(mode)) {
            if (patch.getQuotaTotal() < used) {
                throw new IllegalStateException("接待上限不能低于已核销的 " + used + " 人次");
            }
        } else {
            // ★ 有核销历史的行不能改成非权益档:非 PERK 落 quota_total=0,而 quota_used 是既成事实,
            // 0 - used 会给容量求和(sumRemainingQuotaByChapter 不筛档位)贡献负数 ⇒ 静默吃掉别家容量。
            if (used > 0) {
                throw new IllegalStateException("该供给已有 " + used + " 人次核销记录,不能改为非权益档;请停用后按新口径重新入驻");
            }
            // 非 PERK 不走容量链,quota_total 落 0(与 enroll/DDL 默认同口径;updateTerms 全列覆写,必须给值)
            patch.setQuotaTotal(0);
        }

        // 把过期供给的有效期改回未来 = 这行重新占用本章名额 ⇒ 补数 max_merchant。
        // 自身此刻不在生效集里(已过期),无需自减;等于上限时照拒,与 enroll 同口径。
        boolean wasLive = existing.getValidEnd() == null || existing.getValidEnd().after(DateUtils.getNowDate());
        boolean willBeLive = patch.getValidEnd() == null || patch.getValidEnd().after(DateUtils.getNowDate());
        if (!wasLive && willBeLive) {
            assertMaxMerchant(chapter);
        }

        patch.setUpdateBy(operBy);
        // ★ 条件覆写(与 consumeQuota 同一范式):WHERE 带 status=1 AND quota_used<=quota_total 守卫。
        // consumeQuota 不拿章节锁,上面的行锁与预检挡不住它 —— 「读-判-写」窗口里并发核销把 used
        // 顶过新上限、或并发停用翻 0,都会让本次覆写变脏写。0 行 = 状态已变,拒掉让运营刷新,绝不带脏落库。
        if (offerMapper.updateTerms(patch) != 1) {
            throw new IllegalStateException("该供给状态刚发生变化(可能被核销或停用),请刷新后重试");
        }
        invalidateApprovedCanonical(existing.getTopicId(), "offer-adjust-terms");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deactivate(Long id) {
        MerchantChapterOffer existing = offerMapper.selectById(id);
        if (existing == null) {
            throw new IllegalStateException("供给记录不存在");
        }
        ensureTopicScope(existing.getTopicId());
        if (existing.getStatus() != null && existing.getStatus() == 0) {
            return;   // 幂等:重复点停用不报错
        }
        offerMapper.deactivate(id);
        invalidateApprovedCanonical(existing.getTopicId(), "offer-deactivate");
    }

    private void invalidateApprovedCanonical(Long topicId, String mutation) {
        if (canonicalMutationService != null && topicId != null) {
            canonicalMutationService.invalidateApprovedCanonical(topicId, mutation);
        }
    }


    private void ensureTopicScope(Long topicId) {
        if (topicId == null || !topicOperationsScopeAuthorizer.canViewTopic(topicId)) {
            throw new IllegalStateException("主题不存在或当前账号无供给操作范围");
        }
    }

    /**
     * 入驻鉴权:调用方须是本主题的中标商家({@code cms_registration_merchant.audit_status=1})。
     *
     * <p><b>为什么凭证是「中标报名」而不是「已接受邀约」</b>(2026-07-17 拍板):
     * <ul>
     *   <li>{@code createTopicByPublisher} 的口径就是「商家不占内容槽,经**报名**进**供给槽**」——
     *       本方法填的正是供给槽。(§3.6「报名≠关系、条款随邀约走」讲的是 <b>① 的结算阵容</b>,
     *       不是 ③ 的供给槽,两者别混。)</li>
     *   <li>中标只有后台运营能置(`select()` 的 CAS,须 business:registrationmerchant 权限)
     *       ⇒ 会员侧伪造不了。</li>
     *   <li>{@code coop_invite} 的唯一 insert 入口在会员侧(ApiCoopController),而 ③-自营 由平台
     *       后台发、没有会员发布者去发邀约 ⇒ 拿邀约当凭证会把 REVSHARE(只在自营开)整档锁死。</li>
     * </ul>
     *
     * <p>⚠️ 本闸只回答「这个商家被选中承接这条路线了吗」。它**不**回答「章节级的承接」——
     * 中标是主题级的,一个中标商家可入驻该主题的多个章节(受 max_merchant 与唯一键约束)。
     *
     * <p>⚠️ 过滤条件靠 mapper XML 的四个 {@code <if>}(member_id/topic_id/audit_status/del_flag),
     * 探针少给一个,闸就漏一类(待调配/落选/别人的/别的主题的)⇒ 四个字段必须都给。
     */
    private void assertWonBid(Long topicId, Long merchantId) {
        CmsRegistrationMerchant probe = new CmsRegistrationMerchant();
        probe.setMemberId(merchantId);
        probe.setTopicId(topicId);
        probe.setAuditStatus(1L);   // 0待调配 1中标 2落选
        probe.setDelFlag(0);
        List<CmsRegistrationMerchant> won = registrationMerchantService.selectCmsRegistrationMerchantList(probe);
        if (won == null || won.isEmpty()) {
            throw new IllegalStateException("您未被选为本路线的合作商家,不能入驻");
        }
    }

    /**
     * 新旧授权源的显式分界：recruitEnabled=1 才读取章节申请通过态，其他章节继续走历史中标闸。
     * 这样 migration 默认 0 的存量章节不会因本次上线被放开。
     */
    private void assertEnrollmentAuthorized(CmsTopicChapter chapter, Long merchantId) {
        if (Integer.valueOf(1).equals(chapter.getRecruitEnabled())) {
            ChapterRecruitmentRules.assertV1RecruitmentTermsMode(chapter.getTermsMode(),
                    directPayer(chapter.getTopicId()) != null);
            SysCategory category = chapter.getCategoryId() == null ? null
                    : sysCategoryService.selectSysCategoryById(chapter.getCategoryId().longValue());
            ChapterRecruitmentRules.assertMerchantCategory(chapter.getCategoryId(), category);
            if (!merchantChapterApplicationService.hasApprovedApplication(chapter.getId(), merchantId)) {
                throw new IllegalStateException("您尚未通过本章节的商家承接审核，不能入驻");
            }
            merchantChapterApplicationService.assertCurrentMerchantEligibility(chapter, merchantId);
            return;
        }
        assertWonBid(chapter.getTopicId(), merchantId);
    }

    /**
     * 三档的字段形状。与 DDL {@code ck_offer_terms} 同口径:
     * PERK ⇒ perk_id 非空且 per_head_fee 必须空;REVSHARE ⇒ per_head_fee 非空≥0 且 perk_id 必须空;
     * TRAFFIC ⇒ 两者都空。
     */
    private Long directPayer(Long topicId) {
        return com.chengyinhub.business.support.ClubDirectMerchantPolicy.payerClubId(
                cmsTopicMapper.selectCmsTopicById(topicId), productRoleService.activeContentSlot(topicId));
    }

    private CoopPerk assertTermsShape(MerchantChapterOffer offer, String mode, CmsTopicChapter chapter, Long merchantId) {
        offer.setPayerClubId(null);
        ChapterRecruitmentRules.assertPerkMinValueForTerms(chapter.getTermsMode(), chapter.getPerkMinValue());
        if (TermsMode.PERK.equals(mode)) {
            if (offer.getPerHeadFee() != null) {
                throw new IllegalStateException("权益档不谈人头费");
            }
            if (offer.getPerkId() == null) {
                throw new IllegalStateException("请选择要供给的权益");
            }
            CoopPerk perk = assertPerkMeetsThreshold(offer, chapter, merchantId);
            if (perk.getRetailValue() == null || perk.getRetailValue().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("权益档必须填写正数 retailValue");
            }
            if (perk.getQuota() == null || perk.getQuota() < 1) {
                throw new IllegalStateException("权益档必须填写正数 quota");
            }
            // quota_total 是「本商家在本章的接待上限(人次)」。留 0 = 这行供给一份都核销不了,
            // 却仍占着 max_merchant 的坑,是纯负资产 ⇒ 入驻口就拒,别让它进库。
            if (offer.getQuotaTotal() == null || offer.getQuotaTotal() < 1) {
                throw new IllegalStateException("请填写可接待人次(至少 1)");
            }
            return perk;
        } else if (TermsMode.REVSHARE.equals(mode)) {
            if (offer.getPerkId() != null) {
                throw new IllegalStateException("计酬档不绑权益");
            }
            if (offer.getPerHeadFee() == null || offer.getPerHeadFee().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalStateException("请填写每人次的计酬金额");
            }
            // ⚠️ 二清边界(P0-50):计酬只对 ③-自营 开放。自营票款是平台自营收入 ⇒ 人头费是平台的
            // 采购/推广费支出(应付台账 → 对公打款 → 商家开票),零收付通依赖;
            // 而 ③-第三方 的计酬 = 第三方之间分钱 = 二清,必须等收付通(P0-43)。
            // isPlatformOwned 本身 fail-closed(查不到 CONTENT 槽即返 false),宁可当场拒,
            // 也不要静默放过一个未来会变成二清的口子。
            Long clubId = directPayer(chapter.getTopicId());
            if (clubId == null && !productRoleService.isPlatformOwned(chapter.getTopicId())) {
                throw new IllegalStateException("计酬档须有俱乐部直付主体或既有平台采购归属");
            }
            offer.setPayerClubId(clubId);
            assertFeeWithinChapterCap(offer, chapter);
        } else {
            // TRAFFIC:零承诺零结算,接待打卡即可
            if (offer.getPerkId() != null) {
                throw new IllegalStateException("引流档不绑权益");
            }
            if (offer.getPerHeadFee() != null) {
                throw new IllegalStateException("引流档不谈人头费");
            }
        }
        return null;
    }

    /**
     * 计酬档人头费上限:{@code offer.per_head_fee} 不得超过 {@code chapter.max_per_head_fee}。
     *
     * <p><b>为什么必须有</b>:计酬档是**平台按核销真金白银付**,而 per_head_fee 由商家在 enroll
     * **自报**;P0-50 必亏闸挂在 {@code confirmPrice}(定价时),对定价后新增/改价的 offer
     * **不复查** ⇒ 没有本闸,中标商家可自挂天价人头费,平台照付且零告警。
     *
     * <p>⚠️ <b>章节没配上限 ⇒ 拒(fail-closed),不是「不设上限」</b>。与同类的
     * {@link #assertPerkMeetsThreshold}「min==null 即本章不设门槛」**刻意相反**:
     * 那是**质量门槛**(商家承诺自己的东西,不设=不挑),这是**平台成本上界**(留空=敞口无限)。
     * 口径同本档既有的 isPlatformOwned fail-closed:宁可当场拒,也不静默放过一个掏钱的口子。
     *
     * <p>⚠️ 闸是「不得**超过**」:等于上限放行(BD 按上限谈成是正常成交,不是异常)。
     */
    private void assertFeeWithinChapterCap(MerchantChapterOffer offer, CmsTopicChapter chapter) {
        BigDecimal cap = chapter.getMaxPerHeadFee();
        if (cap == null) {
            throw new IllegalStateException("本章节未配置计酬人头费上限,暂不能按计酬档入驻");
        }
        if (offer.getPerHeadFee().compareTo(cap) > 0) {
            throw new IllegalStateException("每人次计酬不得超过本章节上限 " + cap.toPlainString()
                    + " 元,当前为 " + offer.getPerHeadFee().toPlainString() + " 元");
        }
    }

    /**
     * 入驻门槛:权益零售标价须 ≥ 章节门槛。
     *
     * <p>⚠️ 比的是 {@code retail_value}(玩家眼里这份权益值多少),不是 {@code unit_cost}
     * (商家自报的成本价)—— 拿成本价当门槛会让高毛利商家轻松过线。
     */
    private CoopPerk assertPerkMeetsThreshold(MerchantChapterOffer offer, CmsTopicChapter chapter, Long merchantId) {
        CoopPerk perk = coopPerkMapper.selectById(offer.getPerkId());
        if (perk == null) {
            throw new IllegalStateException("权益不存在或已删除");
        }
        // ★ IDOR:只能拿**自己申报**的权益去入驻。不校验的话,商家 A 能挑商家 B 那份高零售价权益
        // 去过章节门槛,自己端便宜货 —— 门槛形同虚设,且核销时端出来的东西与承诺不符。
        // 独立快照直接读 merchantId；邀约权益仍经受邀方反查，统一收敛在 ICoopInviteService。
        if (!merchantId.equals(coopInviteService.resolvePerkOwner(offer.getPerkId()))) {
            throw new IllegalStateException("只能用自己申报的权益入驻");
        }
        BigDecimal min = chapter.getPerkMinValue();
        if (min == null) {
            return perk;   // 本章不设门槛
        }
        BigDecimal retail = perk.getRetailValue();
        if (retail == null) {
            // 存量数据的真实状态:attachPerks 曾漏抄 setRetailValue ⇒ 落库的 retail_value 恒 NULL。
            // 那个 bug 已修,但**存量行仍是 NULL**,不会自愈 ⇒ 这里必须拒而不是放过,
            // 否则「没报零售价就不该进供给池」的设计意图会被静默绕开。
            throw new IllegalStateException("该权益未填写零售价,请先在权益模板里补上");
        }
        if (retail.compareTo(min) < 0) {
            throw new IllegalStateException("本章节要求权益零售价不低于 " + min.toPlainString()
                    + " 元,当前为 " + retail.toPlainString() + " 元");
        }
        return perk;
    }

    /**
     * 每章承接商家数上限。DDL 是 {@code TINYINT NOT NULL DEFAULT 4},**库侧无任何约束**
     * ⇒ 不在这里数就等于无限入驻,一章挤满商家会稀释每家的曝光。
     *
     * <p>⚠️ 这里数的是**生效** offer(status=1 且 valid_end 未过期)—— 与容量口径同源。
     * 裸数 status=1 会把过期供给算进名额,让本该能进的商家进不来。
     *
     * <p>⚠️ 本方法是读-判,**写在调用方** ⇒ 必须在**章节行锁之内**调用,否则并发入驻双双过闸。
     * 锁在 {@link #enroll} 开头用 lockChaptersForCapacity 加,别把这两者拆开。
     */
    private void assertMaxMerchant(CmsTopicChapter chapter) {
        Integer cap = chapter.getMaxMerchant();
        ChapterRecruitmentRules.assertMaxMerchant(cap);
        if (cap == null || cap == 0) {
            return;
        }
        List<MerchantChapterOffer> live = offerMapper.selectByChapter(chapter.getId());
        int n = live == null ? 0 : live.size();
        if (n >= cap) {
            throw new IllegalStateException("本章节承接商家已满(" + cap + " 家)");
        }
    }

    private String modeLabel(String mode) {
        if (TermsMode.PERK.equals(mode)) return "权益档";
        if (TermsMode.REVSHARE.equals(mode)) return "计酬档";
        if (TermsMode.TRAFFIC.equals(mode)) return "引流档";
        return String.valueOf(mode);
    }
}
