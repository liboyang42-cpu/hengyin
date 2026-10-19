package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.PricingResult;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.IPricingService;
import com.chengyinhub.common.utils.DateUtils;

/**
 * 定价引擎实现(报告§5.4 · B2)。
 */
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.service.ISettlementRuleService;
@Service
public class PricingServiceImpl implements IPricingService {

    @Autowired private com.chengyinhub.business.service.support.PureContentExploreService pureContentExploreService;

    /** [P0-50] 计酬成本上界:算「全通关必亏」硬闸用。 */
    @Autowired
    private com.chengyinhub.business.mapper.MerchantChapterOfferMapper offerMapper;

    /**
     * [P0-28 批1] 费率兜底值 —— **不是真源**,真源是 settlement_rule。
     *
     * <p>这三个数原本是硬编码常量(注释还写着「后续拍板改此常量或提配置」——配置没提),
     * 于是调一次抽成要走一遍发版,而库里 settlement_rule.platform_commission_rate 是同一概念的另一份、
     * 后台可改 ⇒ 运营在后台调成 0.15,这里还是 0.10,定出的地板价低 5 个点 → 结算 margin<0 → 拒结悬空。
     *
     * <p>现在只在**查不到规则行**时兜底(并打 error)。取值刻意等于原硬编码值,保证 fallback 时行为不变。
     */
    private static final BigDecimal FALLBACK_PLATFORM_RATE = new BigDecimal("0.10");
    private static final BigDecimal FALLBACK_GUARANTEE_BASE = new BigDecimal("0.05");
    private static final BigDecimal FALLBACK_GUARANTEE_STEP = new BigDecimal("0.02");
    private static final int FALLBACK_GUARANTEE_MAX_MERCHANT = 5;

    /** 主题费率规则的坐标:平台默认行(merchant_id=0) + 主题业务线。 */
    private static final Long PLATFORM_DEFAULT_MERCHANT = 0L;
    private static final String BIZ_TYPE_TOPIC = "topic";

    private static final Logger logger = LoggerFactory.getLogger(PricingServiceImpl.class);

    @Autowired
    private ISettlementRuleService settlementRuleService;

    @Autowired
    private CoopOrderMapper coopOrderMapper;

    @Autowired
    private CmsTopicMapper cmsTopicMapper;

    @Autowired
    private OmsTicketMapper omsTicketMapper;

    @Autowired
    private CoopPerkMapper coopPerkMapper;

    @Autowired
    private com.chengyinhub.business.mapper.PricingReferenceMapper pricingReferenceMapper;

    /**
     * 当前生效的平台抽成率(读库,非常量)。
     *
     * <p>⚠️ **结算路径别直接用它** —— 结算要用主题锁价时冻的快照({@link #platformRateOf}),
     * 否则调一次费率会追溯改写所有已售未结订单。这个方法只用于「此刻定价该按多少算」。
     */
    @Override
    public BigDecimal platformRate() {
        SettlementRule rule = topicRule();
        if (rule == null || rule.getPlatformCommissionRate() == null) {
            logger.error("settlement_rule 查不到 (merchant_id=0, biz_type=topic) 的平台抽成率,"
                    + "回落硬编码 {} —— 单真源名存实亡,请补上这一行", FALLBACK_PLATFORM_RATE);
            return FALLBACK_PLATFORM_RATE;
        }
        return rule.getPlatformCommissionRate();
    }

    @Override
    public BigDecimal initiatorGuaranteeRate(int verifiedMerchantCount) {
        SettlementRule rule = topicRule();
        BigDecimal base = rule != null && rule.getGuaranteeBaseRate() != null
                ? rule.getGuaranteeBaseRate() : FALLBACK_GUARANTEE_BASE;
        BigDecimal step = rule != null && rule.getGuaranteeStepRate() != null
                ? rule.getGuaranteeStepRate() : FALLBACK_GUARANTEE_STEP;
        int max = rule != null && rule.getGuaranteeMaxMerchant() != null
                ? rule.getGuaranteeMaxMerchant() : FALLBACK_GUARANTEE_MAX_MERCHANT;
        return guaranteeRate(base, step, max, verifiedMerchantCount);
    }

    /**
     * [P0-28 批1] 按主题**锁价时冻结的快照**算平台抽成率;未锁价/存量(快照为空)则回落当前 live 值。
     *
     * <p>这是结算路径唯一该用的入口。冻结的意义:coop_order 有 termsVersion 冻商家条款,
     * 平台率却曾是结算时现取 ⇒ 发版调价 = 已售票的主题结算时被多抽 → margin<0 → 拒结悬空。
     */
    @Override
    public BigDecimal platformRateOf(CmsTopic topic) {
        if (topic != null && topic.getFeePlatformRate() != null) {
            return topic.getFeePlatformRate();
        }
        return platformRate();
    }

    /**
     * [P0-28 批1] 按主题快照算发起人保底率;快照为空则回落 live 值。
     *
     * <p>⚠️ 冻的是**公式参数**不是算好的率 —— 保底率 = base + step × min(已核销商家数, max),
     * 而已核销商家数只有结算时才知道,锁价时算不出。同一批参数 + 同一个 n 恒得同一个率。
     */
    @Override
    public BigDecimal initiatorGuaranteeRateOf(CmsTopic topic, int verifiedMerchantCount) {
        if (topic != null && topic.getFeeGuaranteeBaseRate() != null
                && topic.getFeeGuaranteeStepRate() != null && topic.getFeeGuaranteeMaxMerchant() != null) {
            return guaranteeRate(topic.getFeeGuaranteeBaseRate(), topic.getFeeGuaranteeStepRate(),
                    topic.getFeeGuaranteeMaxMerchant(), verifiedMerchantCount);
        }
        return initiatorGuaranteeRate(verifiedMerchantCount);
    }

    /** 保底率公式的唯一实现,快照路径与 live 路径共用 —— 别在两处各写一遍。 */
    private BigDecimal guaranteeRate(BigDecimal base, BigDecimal step, int max, int verifiedMerchantCount) {
        int n = Math.max(0, Math.min(verifiedMerchantCount, Math.max(0, max)));
        return base.add(step.multiply(new BigDecimal(n)));
    }

    /**
     * [P0-50] 计酬成本上界必须 < 票价,否则全通关必亏本金。
     *
     * <p>两种票价各判各的(与地板价闸同构):{@code guidedPrice} 与 {@code selfPrice} 是
     * 两个独立的产品,不能拿一个的成本去卡另一个。
     */
    private void assertRevshareAffordable(Long topicId, BigDecimal guidedPrice, BigDecimal selfPrice) {
        BigDecimal cost = offerMapper.sumMaxPerHeadFeeByRequiredChapters(topicId);
        // null = 这个主题没有任何 REVSHARE 供给(全是 PERK/TRAFFIC,或还没招商)⇒ 平台不掏钱 ⇒ 无需拦。
        if (cost == null || cost.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (guidedPrice != null && guidedPrice.compareTo(cost) <= 0) {
            throw new IllegalStateException(revshareRefusal("有人带票价", guidedPrice, cost));
        }
        if (selfPrice != null && selfPrice.compareTo(cost) <= 0) {
            throw new IllegalStateException(revshareRefusal("无人带票价", selfPrice, cost));
        }
    }

    /** 拒绝话术要能自证:说清楚是哪个数、跟谁比、为什么。运营看到才知道该去改哪。 */
    private String revshareRefusal(String which, BigDecimal price, BigDecimal cost) {
        return which + " " + price + " 不高于计酬成本上界 " + cost
                + ":玩家全部通关时,每个必选章节最贵的计酬商家都要付一次人头费,合计 " + cost
                + " ≥ 票价 ⇒ 每张票必亏。请提高票价、降低人头费、或把部分章节改成权益档/引流档。";
    }

    private SettlementRule topicRule() {
        try {
            return settlementRuleService.getActiveRule(PLATFORM_DEFAULT_MERCHANT, BIZ_TYPE_TOPIC);
        } catch (Exception e) {
            logger.error("读 settlement_rule 失败,回落硬编码费率", e);
            return null;
        }
    }

    /**
     * 平台侧费率快照的**唯一写法** —— confirmPrice(人工锁价)与 freezeFeeSnapshot(零现金自动开售)共用。
     *
     * <p>四个字段必须一起写:只写 platformRate 会让 {@code initiatorGuaranteeRateOf} 回落到 live 值,
     * 那正是快照要防的「改一个数追溯改写已售未结订单」。
     */
    private void applyFeeSnapshot(CmsTopic upd) {
        SettlementRule feeRule = topicRule();
        upd.setFeePlatformRate(feeRule != null && feeRule.getPlatformCommissionRate() != null
                ? feeRule.getPlatformCommissionRate() : FALLBACK_PLATFORM_RATE);
        upd.setFeeGuaranteeBaseRate(feeRule != null && feeRule.getGuaranteeBaseRate() != null
                ? feeRule.getGuaranteeBaseRate() : FALLBACK_GUARANTEE_BASE);
        upd.setFeeGuaranteeStepRate(feeRule != null && feeRule.getGuaranteeStepRate() != null
                ? feeRule.getGuaranteeStepRate() : FALLBACK_GUARANTEE_STEP);
        upd.setFeeGuaranteeMaxMerchant(feeRule != null && feeRule.getGuaranteeMaxMerchant() != null
                ? feeRule.getGuaranteeMaxMerchant() : FALLBACK_GUARANTEE_MAX_MERCHANT);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int freezeFeeSnapshot(Long topicId) {
        if (topicId == null) throw new IllegalArgumentException("topicId 不能为空");
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(topicId);
        if (topic == null) throw new IllegalStateException("主题不存在");
        // 幂等且绝不覆盖:已冻的主题可能已有在售订单,重写会追溯改写它们的结算口径。
        if (topic.getFeePlatformRate() != null) {
            return 0;
        }
        CmsTopic upd = new CmsTopic();
        upd.setId(topicId);
        applyFeeSnapshot(upd);
        upd.setUpdateTime(DateUtils.getNowDate());
        return cmsTopicMapper.updateCmsTopic(upd);
    }

    @Override
    public PricingResult compute(BigDecimal sumShareRate, BigDecimal guaranteeRate, BigDecimal sumFixedFee,
                                 BigDecimal leadCostPerHead, BigDecimal perkCost, BigDecimal platformRate) {
        BigDecimal share = nz(sumShareRate);
        BigDecimal guarantee = nz(guaranteeRate);
        BigDecimal fixed = nz(sumFixedFee);
        BigDecimal lead = nz(leadCostPerHead);
        BigDecimal perk = nz(perkCost);
        BigDecimal rate = nz(platformRate);

        BigDecimal costC = lead.add(fixed).add(perk);
        // [资损修复] 地板分母须含发起人保底率(§3.4:P_min=C/(1−平台率−保底率−Σ分成率))。
        // 漏减保底率会放行一个结算时 margin<0、守恒断言拒结、款项悬空转人工的价格。
        BigDecimal denom = BigDecimal.ONE.subtract(rate).subtract(guarantee).subtract(share);
        if (denom.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("平台佣金率+保底率+分成比例之和≥100%,无法定价,请下调分成");
        }
        BigDecimal pMin = costC.divide(denom, 2, RoundingMode.CEILING);

        PricingResult r = new PricingResult();
        r.setPlatformRate(rate);
        r.setSumShareRate(share);
        r.setSumFixedFee(fixed);
        r.setLeadCostPerHead(lead);
        r.setPerkCost(perk);
        r.setCostC(costC);
        r.setPriceMin(pMin);
        return r;
    }

    @Override
    public PricingResult previewTopic(Long topicId, BigDecimal leadCost, Integer teamSize, String subType) {
        List<CoopOrder> orders = coopOrderMapper.selectEffectiveOrdersByTopicId(topicId);
        BigDecimal sumShareRate = BigDecimal.ZERO; // 小数累加
        BigDecimal sumFixedFee = BigDecimal.ZERO;
        int maxMerchantCount = 0; // [资损修复] 最坏核销情形:所有非club被邀商家都核销(与结算 verifiedMerchantCount 口径一致)
        List<PricingResult.Lineup> lineup = new ArrayList<>();
        for (CoopOrder o : orders) {
            PricingResult.Lineup l = new PricingResult.Lineup();
            l.setToId(o.getToId());
            l.setToType(o.getToType());
            l.setShareMode(o.getShareMode());
            l.setShareRate(o.getShareRate());
            l.setFixedFee(o.getFixedFee());
            lineup.add(l);
            if (!"club".equals(o.getToType())) maxMerchantCount++;
            if (o.getShareMode() != null && o.getShareMode() == 1 && o.getShareRate() != null) {
                // share_rate 存百分数(如 30.00),折算小数
                sumShareRate = sumShareRate.add(o.getShareRate().divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
            } else if (o.getShareMode() != null && o.getShareMode() == 2 && o.getFixedFee() != null) {
                sumFixedFee = sumFixedFee.add(o.getFixedFee());
            }
        }
        // [资损修复] 发起人保底率进地板:取最坏核销数(封顶5)的 guaranteeRate,与结算侧一致;
        // 配合"锁价后冻结阵容"(acceptInviteWithLock lifecycle 闸),定价时 maxN/Σ分成=结算时值,地板保证 margin≥0。
        BigDecimal guaranteeRate = initiatorGuaranteeRate(maxMerchantCount);
        // 带队成本仅 guided 票摊薄;self 票 leadCostPerHead=0
        BigDecimal leadPerHead = BigDecimal.ZERO;
        if ("guided".equals(subType)) {
            // CU-C-70:有带队成本却不成团(人数空/0/负)时不能静默归零 —— 原来这个条件短路成
            // 「未填/无人带」,带队成本被摊成 0、地板价直接变 0.00,而定价页只拿 min>=floor
            // 自校验,0 恒为真,零价也能点确认。显式报错,把这一次重算原样退回给用户。
            BigDecimal lead = nz(leadCost);
            // 负的带队成本会把地板价往下拉,confirmPrice 又按这份地板校验 ⇒ 资损路径必须拒
            if (lead.signum() < 0) {
                throw new IllegalStateException("带队成本不能为负数");
            }
            if (lead.signum() > 0 && (teamSize == null || teamSize <= 0)) {
                throw new IllegalStateException("有人带定价需填写成团人数且大于 0");
            }
            if (teamSize != null && teamSize > 0) {
                leadPerHead = lead.divide(new BigDecimal(teamSize), 2, RoundingMode.CEILING);
            }
        }
        // T10:权益成本从服务端已申报权益汇总(单人成本合计),不信任前端传值
        BigDecimal perkCost = nz(coopPerkMapper.sumUnitCostByTopicId(topicId));
        // 定价预览用 **live** 费率:此刻还没锁价、没有快照,「按现在的费率该定多少」正是这里要答的。
        // 锁价(confirmPrice)会把这一刻的 live 值冻进主题,之后结算只认那份快照。
        PricingResult r = compute(sumShareRate, guaranteeRate, sumFixedFee, leadPerHead, perkCost, platformRate());
        r.setSubType(subType);
        r.setLineup(lineup);
        // CU-M-32:与 confirmPrice 同一判据(lifecycle==2 + 自由探索必选章生效供给),前端据此事先置灰
        // 「确认终价并开售」,并给出与 confirmPrice 拒绝时完全相同的原话。
        CmsTopic lifecycleRow = cmsTopicMapper == null ? null : cmsTopicMapper.selectCmsTopicLifecycleById(topicId);
        String blockReason = lifecycleRow == null ? LIFECYCLE_BLOCK_REASON : lifecycleBlockReason(lifecycleRow);
        if (blockReason == null) blockReason = supplyBlockReason(cmsTopicMapper.selectProductTypeById(topicId), topicId);
        r.setCanConfirm(blockReason == null);
        r.setConfirmBlockReason(blockReason);
        java.util.Map<String, Object> reference = pricingReferenceMapper == null
                ? null : pricingReferenceMapper.selectCityReference(topicId, subType);
        if (reference != null && reference.get("referencePrice") != null) {
            r.setCityReferencePrice(new BigDecimal(String.valueOf(reference.get("referencePrice"))));
            Object sampleSize = reference.get("sampleSize");
            r.setCityReferenceSampleSize(sampleSize instanceof Number ? ((Number) sampleSize).intValue() : 0);
            Object cityLabel = reference.get("cityLabel");
            r.setCityReferenceLabel(cityLabel == null ? null : String.valueOf(cityLabel));
        } else {
            r.setCityReferenceSampleSize(0);
        }
        return r;
    }

    private static final String LIFECYCLE_BLOCK_REASON = "主题未在定价阶段,不能确认终价";

    /** 锁价闸①:只有定价阶段(lifecycle==2)能确认终价。previewTopic 的 canConfirm 与 confirmPrice 共用。 */
    private static String lifecycleBlockReason(CmsTopic topic) {
        return topic.getLifecycle() == null || topic.getLifecycle() != 2 ? LIFECYCLE_BLOCK_REASON : null;
    }

    /** 锁价闸②(SC-FREE-PRICE):自由探索主题每个必选章至少 1 家生效供给(纯内容探索除外)。preview/confirm 共用。 */
    private String supplyBlockReason(Integer productType, Long topicId) {
        if (ProductType.FREE_EXPLORE == (productType == null ? -1 : productType)
                && !(pureContentExploreService != null && pureContentExploreService.isPureContent(topicId))
                && offerMapper.countRequiredChaptersWithoutLiveOffer(topicId) > 0) {
            return "每个必选章至少需要 1 家生效商家供给,暂不能锁价开售";
        }
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int confirmPrice(Long topicId, BigDecimal guidedPrice, BigDecimal selfPrice,
                            Integer guidedInventory, BigDecimal leadCost, Integer teamSize) {
        // [资损·TOCTOU] 行锁读主题,与 acceptInviteWithLock 的行锁串行:确保"读单算地板→lifecycle=3"
        // 期间无并发 acceptInvite 挤入新分成单,消除锁价快照与结算阵容不一致的悬空窗。
        CmsTopic topic = cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (topic == null) throw new IllegalStateException("主题不存在");
        String lifecycleBlock = lifecycleBlockReason(topic);
        if (lifecycleBlock != null) throw new IllegalStateException(lifecycleBlock);
        if (guidedPrice == null && selfPrice == null) throw new IllegalStateException("请至少填写一种票价");
        String supplyBlock = supplyBlockReason(topic.getProductType(), topicId);
        if (supplyBlock != null) throw new IllegalStateException(supplyBlock);

        // 校验各票价 ≥ 各自底价(guided 摊带队成本,self 不摊)
        if (guidedPrice != null) {
            BigDecimal guidedFloor = previewTopic(topicId, leadCost, teamSize, "guided").getPriceMin();
            if (guidedPrice.compareTo(guidedFloor) < 0)
                throw new IllegalStateException("有人带票价不能低于底价 " + guidedFloor);
        }
        if (selfPrice != null) {
            BigDecimal selfFloor = previewTopic(topicId, null, null, "self").getPriceMin();
            if (selfPrice.compareTo(selfFloor) < 0)
                throw new IllegalStateException("无人带票价不能低于底价 " + selfFloor);
        }

        // [P0-50/D21] 计酬「全通关必亏」硬闸。
        //
        // 只拦无争议的那条线:**Σ(每必选章 max per_head_fee) ≥ 票价 ⇒ 全通关必亏本金**,
        // 与核销率无关、任何情况下都不该开卖。
        //
        // ⚠️ 刻意**不用** §8 那个 25%/40% 的 KPI 做事前闸 —— 那两个数是「实际支出占票款比」
        // (事后统计,分母是真实核销次数),而这里能算的是「最坏情况占票价比」(假设全通关)。
        // 两者差着一个核销率:按文档 §4.5 的工作假设(20% 核销),同一套配置事后是 20% 健康、
        // 最坏情况却可能超 100%。拿 40% 当事前闸会把一堆健康配置拦掉。
        //
        // 风险的真实形状:**产品越成功,亏得越多** —— 冷启动核销率低时完全看不见,
        // 等玩家真开始认真通关才爆,而那正是最不想踩刹车的时候。所以闸必须在开卖前。
        assertRevshareAffordable(topicId, guidedPrice, selfPrice);

        // self 票:复用现有自玩票(不收编),把终价写回 cms_topic
        CmsTopic upd = new CmsTopic();
        upd.setId(topicId);
        // [P0-28 批1] 锁价 = 冻结费率。之后再怎么调库里的费率,这个主题的结算都只认这一刻的快照。
        // 不冻的话:改一个数会追溯改写所有已售未结订单的结算 → 平台多抽 → 发起人 margin<0 →
        // 守恒断言拒结 → 款项悬空转人工。coop_order 冻了商家条款(termsVersion),这里冻平台侧。
        applyFeeSnapshot(upd);
        if (selfPrice != null) {
            upd.setSelfPlay(1);
            upd.setSelfPlayPrice(selfPrice);
        }
        upd.setUpdateTime(DateUtils.getNowDate());
        cmsTopicMapper.updateCmsTopic(upd);
        // guided 票:新建/更新一张 sub_type=guided 的 oms_ticket(owner=主题, mode=1)
        if (guidedPrice != null) {
            OmsTicket q = new OmsTicket();
            q.setOwnerType(1L);
            q.setOwnerId(topicId);
            q.setSubType("guided");
            List<OmsTicket> existing = omsTicketMapper.selectOmsTicketList(q);
            int inv = guidedInventory != null && guidedInventory > 0 ? guidedInventory : 100;
            if (existing != null && !existing.isEmpty()) {
                OmsTicket t = new OmsTicket();
                t.setId(existing.get(0).getId());
                t.setPrice(guidedPrice);
                t.setTotalInventory((long) inv);
                t.setRemainingInventory((long) inv);
                t.setUpdateTime(DateUtils.getNowDate());
                if (omsTicketMapper.updateOmsTicket(t) != 1) {
                    // updateOmsTicket 的库存谓词拒绝时，不能继续把主题报成“已开售”。
                    // 本方法在事务内，抛出后主题状态、费率快照、应缴标记会一起回滚。
                    throw new IllegalStateException("有人带票库存已变化，无法确认终价");
                }
            } else {
                OmsTicket t = new OmsTicket();
                t.setMemberId(topic.getMemberId());
                t.setOwnerType(1L);
                t.setOwnerId(topicId);
                t.setName("有人带");
                t.setSubType("guided");
                // [P0-02] 跟主题真源走,别硬编码 —— saveTickets 只覆盖创建路径,而这里是**开售时**
                // 插的第二条票产生路径。硬编码 1 会在一个 product_type=2 的主题上当场造出混票,
                // 正是 saveTickets 要防的那个状态,而它的「全量覆盖」在时间上够不着这里。
                // 存量未回填(null)时保持旧行为(经典定向),与 resolveMode 的兜底同口径。
                t.setMode(topic.getProductType() == null
                        ? ProductType.ORIENTEERING : ProductType.toTicketMode(topic.getProductType()));
                t.setPrice(guidedPrice);
                t.setTotalInventory((long) inv);
                t.setRemainingInventory((long) inv);
                t.setDelFlag(0);
                t.setCreateBy("pricing");
                t.setCreateTime(DateUtils.getNowDate());
                omsTicketMapper.insertOmsTicket(t);
            }
        }
        // canonical15 含人工责任/授权事实，锁价流程无权替运营补值或自动提审。
        // 本事务最多推进到 PRICING；运营保存 canonical 草稿后显式提交开售审。
        return 1;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
