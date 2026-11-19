package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.service.ITopicLifecycleService;
import com.chengyinhub.common.constant.ProductType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * F10(FE-G13/FE-G14)自由探索「位置全部有人承接 ⇒ 自动进入待定价」。
 *
 * <p>照搬城市通票「签齐才定价」+ 拼团「凑齐自动进入下一步」:每个必选章(商家位置)都有生效供给
 * (商家承接或作者自己承接)时,招募中(1)CAS 进入待定价(2);开售前有位置重新变空,待定价回到招募中。
 *
 * <p>判据与锁价闸同源:{@link MerchantChapterOfferMapper#countRequiredChaptersWithoutLiveOffer}
 * (PricingServiceImpl.supplyBlockReason)。迁移走 {@link ITopicLifecycleService#transition} 的 CAS,
 * 并发补齐最后一个位置时只有一个请求迁移成功,其余拿到 false,幂等。
 */
@Component
public class FreeExploreSlotProgress {

    private static final Logger log = LoggerFactory.getLogger(FreeExploreSlotProgress.class);

    private final CmsTopicMapper topicMapper;
    private final MerchantChapterOfferMapper offerMapper;
    private final ITopicLifecycleService lifecycleService;

    @Autowired(required = false)
    private PureContentExploreService pureContentExploreService;

    public FreeExploreSlotProgress(CmsTopicMapper topicMapper, MerchantChapterOfferMapper offerMapper,
                                   ITopicLifecycleService lifecycleService) {
        this.topicMapper = topicMapper;
        this.offerMapper = offerMapper;
        this.lifecycleService = lifecycleService;
    }

    /** 位置统计:总数、已有生效承接数、空位数。 */
    public static final class Slots {
        private final int total;
        private final int open;

        public Slots(int total, int open) {
            this.total = Math.max(total, 0);
            this.open = Math.max(Math.min(open, this.total), 0);
        }

        public int getTotal() { return total; }
        public int getOpen() { return open; }
        public int getFilled() { return total - open; }
        public boolean isAllFilled() { return open == 0; }
    }

    public Slots slots(Long topicId) {
        if (topicId == null) return new Slots(0, 0);
        if (pureContentExploreService != null && pureContentExploreService.isPureContent(topicId)) {
            return new Slots(0, 0);
        }
        return new Slots(offerMapper.countRequiredChapters(topicId),
                offerMapper.countRequiredChaptersWithoutLiveOffer(topicId));
    }

    /**
     * 招募中且全部必选位置都有生效承接 ⇒ 迁移到待定价。
     *
     * @return true = 本次完成迁移;false = 不满足条件或已被并发请求迁移
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean tryAdvanceToPricing(Long topicId) {
        CmsTopic topic = freeExploreTopic(topicId);
        if (topic == null || !Integer.valueOf(TopicLifecycleState.RECRUITING.getCode()).equals(topic.getLifecycle())) {
            return false;
        }
        if (!slots(topicId).isAllFilled()) return false;
        boolean moved = lifecycleService.transition(topicId, TopicLifecycleState.RECRUITING, TopicLifecycleState.PRICING);
        if (moved) log.info("[F10] 自由探索位置全部有人承接,自动进入待定价 topicId={}", topicId);
        return moved;
    }

    /**
     * 待定价且出现空位 ⇒ 回到招募中。已经锁价(费率快照已冻结,旧运营开售路径)的主题不回退,
     * 沿用原有「锁价后暂停新报名」的处理。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean reopenRecruitingIfSlotOpen(Long topicId) {
        CmsTopic topic = freeExploreTopic(topicId);
        if (topic == null || !Integer.valueOf(TopicLifecycleState.PRICING.getCode()).equals(topic.getLifecycle())) {
            return false;
        }
        if (topic.getFeePlatformRate() != null) return false;
        if (slots(topicId).isAllFilled()) return false;
        boolean moved = lifecycleService.transition(topicId, TopicLifecycleState.PRICING, TopicLifecycleState.RECRUITING);
        if (moved) log.info("[F10] 自由探索开售前出现空位,回到招募中 topicId={}", topicId);
        return moved;
    }

    private CmsTopic freeExploreTopic(Long topicId) {
        if (topicId == null) return null;
        CmsTopic topic = topicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())
                || !Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())) {
            return null;
        }
        return topic;
    }
}
