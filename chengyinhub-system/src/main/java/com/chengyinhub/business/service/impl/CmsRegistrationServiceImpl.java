package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;

import com.chengyinhub.common.annotation.DeadlockRetry;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ICmsPointsSettingService;
import com.chengyinhub.business.service.IMerchantPayableService;
import com.chengyinhub.business.service.ISettlementEntryService;
import com.chengyinhub.business.service.ISettlementRuleService;
import com.chengyinhub.business.service.IWxPaymentCallbackInboxService;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import com.chengyinhub.business.service.support.SaleAvailabilityPolicy;
import com.chengyinhub.business.service.support.TopicSaleCanonicalCodec;
import com.chengyinhub.business.service.support.ExploreAccountTicketGate;
import com.chengyinhub.business.service.support.ExploreTicketUsePolicy;
import com.chengyinhub.business.util.RefundPolicy;
import com.chengyinhub.common.constant.EntitlementInvalidReason;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
import org.apache.ibatis.annotations.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IRegistrationCreationTransaction;
import com.chengyinhub.business.service.RegistrationOrderExpiredException;
import com.chengyinhub.business.service.IClubWaitlistOrderBridge;
import com.chengyinhub.business.service.RegistrationRequestConflictException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.chengyinhub.business.domain.dto.RegistrationQuoteResult;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.domain.dto.RegistrationTicketItem;
import com.chengyinhub.business.domain.vo.ExploreChapterRedemptionResult;
import com.chengyinhub.business.domain.vo.TopicCompletionAwardVO;
import com.chengyinhub.common.utils.sign.Md5Utils;

import static com.chengyinhub.common.constant.CmsConstants.QUOTE_SIGN_SALT;

/**
 * 活动/主题报名Service业务层处理
 *
 * @author webxh
 * @date 2025-09-04
 */
@Service
public class CmsRegistrationServiceImpl implements ICmsRegistrationService, IRegistrationCreationTransaction {

    /** D1:积分汇率与单笔上限的唯一真源(sys_config,缺省 = 原 CmsConstants 常量);手工 new 的实例走缺省值。 */
    @Autowired
    private com.chengyinhub.business.service.support.PointsValuePolicy pointsValuePolicy =
            com.chengyinhub.business.service.support.PointsValuePolicy.codeDefaults();

    @Autowired
    private com.chengyinhub.business.service.INotifyService notifyService;

    @Autowired
    private com.chengyinhub.business.service.IImService imService;

    @Autowired
    private com.chengyinhub.business.service.support.CommittedBusinessNotice committedBusinessNotice;

    @Autowired
    private AlarmService alarmService;

    @Autowired
    private org.springframework.beans.factory.ObjectProvider<com.chengyinhub.business.service.IWechatPayService> paymentAttemptGateway;

    @org.springframework.beans.factory.annotation.Value("${chengyin.platform.member-id:100000}")
    private Long platformMemberId;
    private static final Logger logger = LoggerFactory.getLogger(CmsRegistrationServiceImpl.class);
    @Autowired
    private CmsRegistrationMapper cmsRegistrationMapper;

    @Autowired
    private CmsRegistrationMerchantMapper cmsRegistrationMerchantMapper;

    /** F03 主题归属真源:商业主题走合同二售卖闸,纯免费 UGC 按上架事实报名。 */
    @Autowired
    private CommercialTopicPolicy commercialTopicPolicy;

    @Autowired
    private ISettlementRuleService settlementRuleService;

    /** [赏金] 核销触发激活判定、微信退款回调触发追回,见 verifyAndSettle / handleWechatPayCallback。 */
    @Autowired
    private MerchantReferralBountyService merchantReferralBountyService;

    /** 异常只进人工队列；任何风控探针异常都不得回滚已经成立的核销与应付台账。 */
    @Autowired
    private VerificationRiskService verificationRiskService;

    @Autowired
    private com.chengyinhub.business.service.IGrowthService growthService;

    @Autowired
    private CmsActivityMapper cmsActivityMapper;

    @Autowired
    private UmsMemberMapper umsMemberMapper;

    @Autowired
    private CmsTopicMapper cmsTopicMapper;

    @Autowired
    private TopicSaleSnapshotMapper topicSaleSnapshotMapper;

    /** [#22 二清闸] 票款归属真源(D8:票款归 CONTENT 槽持有者)。 */
    @Autowired
    private com.chengyinhub.business.service.IProductRoleService productRoleService;

    /** [P0-15] ③ 购票预占权益容量。跨 bean 注入 ⇒ 走代理 ⇒ 它的 @Transactional(REQUIRED) 并入本事务。 */
    @Autowired
    private com.chengyinhub.business.service.IPerkLedgerService perkLedgerService;

    /** DOC2 F3 Phase 1 套票账本(开关默认关)。 */
    @Autowired
    private com.chengyinhub.business.service.IFreeExploreBundleService freeExploreBundleService;

    @Autowired
    @Lazy
    private com.chengyinhub.business.service.ITopicCompletionAwardService topicCompletionAwardService;

    @Autowired
    private com.chengyinhub.business.mapper.RedemptionMapper redemptionMapper;

    @Autowired
    private OmsTicketMapper omsTicketMapper;

    /** 候补 offer 已在晋级事务占过库存；报名事务只 claim/bind，绝不能再扣一次。 */
    @Autowired(required = false)
    private IClubWaitlistOrderBridge clubWaitlistOrderBridge;

    @Autowired
    private FirstReviewPointsClawbackService firstReviewPointsClawback;

    @Autowired
    private ClubMapper clubMapper;

    @Autowired
    private ClubMemberMapper clubMemberMapper;

    @Autowired
    private QrcodeServiceImpl qrcodeService;

    @Autowired
    private UmsMemberPointsDetailMapper umsMemberPointsDetailMapper;

    @Autowired
    private CmsRegistrationScanMapper cmsRegistrationScanMapper;

    @Autowired
    private com.chengyinhub.business.mapper.StoreVerifyGrowthDeliveryMapper storeVerifyGrowthDeliveryMapper;

    @Autowired
    private RedemptionRewardClawbackService redemptionRewardClawbackService;

    /** 核销与资金/权益写入同事务的不可变 actor 审计；写失败必须让核销整体回滚。 */
    @Autowired
    private BusinessAuditEventMapper businessAuditEventMapper;

    /** 带队场次中的玩家核销事实；与通用 cms_registration_scan 分开，避免混合语义。 */
    @Autowired
    private CoopGroupMemberVerifyMapper coopGroupMemberVerifyMapper;


    @Autowired
    private ICmsPointsSettingService cmsPointsSettingService;

    @Autowired
    private ISettlementEntryService settlementEntryService;

    @Autowired
    private IMerchantPayableService merchantPayableService;

    /** P1:支付回调先按 notify_hash 落 inbox 去重;状态推进仍由下方既有 CAS 负责。 */
    @Autowired
    private IWxPaymentCallbackInboxService wxPaymentCallbackInboxService;

    @Override
    public BigDecimal selectTotalSalesByActivityId(Long activityId) {
        return cmsRegistrationMapper.selectTotalSalesByActivityId(activityId);
    }

    @Override
    public BigDecimal selectTotalSalesByTopicId(Long topicId) {
        return cmsRegistrationMapper.selectTotalSalesByTopicId(topicId);
    }

    @Override
    public java.util.List<java.util.Map<String, Object>> countByParticipateDate(Long ownerType, Long ownerId) {
        return cmsRegistrationMapper.selectCountGroupByDate(ownerType, ownerId);
    }

    /**
     * 查询活动/主题报名
     *
     * @param id 活动/主题报名主键
     * @return 活动/主题报名
     */
    @Override
    public CmsRegistration selectCmsRegistrationById(Long id) {
        return cmsRegistrationMapper.selectCmsRegistrationById(id);
    }

    /**
     * 查询活动/主题报名列表
     *
     * @param cmsRegistration 活动/主题报名
     * @return 活动/主题报名
     */
    @Override
    public List<CmsRegistration> selectCmsRegistrationList(CmsRegistration cmsRegistration) {
        return cmsRegistrationMapper.selectCmsRegistrationList(cmsRegistration);
    }

    @Override
    public Map<Long, com.chengyinhub.business.domain.vo.MerchantTodoRegistrationVO> selectTodoRegistrationOwners(
            java.util.Collection<Long> registrationIds) {
        Map<Long, com.chengyinhub.business.domain.vo.MerchantTodoRegistrationVO> owners = new java.util.HashMap<>();
        if (registrationIds == null || registrationIds.isEmpty()) return owners;
        for (com.chengyinhub.business.domain.vo.MerchantTodoRegistrationVO row : cmsRegistrationMapper.selectTodoRegistrationOwners(registrationIds)) {
            owners.put(row.getId(), row);
        }
        return owners;
    }

    @Override
    public Map<Long, long[]> countPaidVerificationByOwners(Long ownerType, java.util.Collection<Long> ownerIds) {
        Map<Long, long[]> out = new java.util.HashMap<>();
        if (ownerType == null || ownerIds == null || ownerIds.isEmpty()) return out;
        List<Map<String, Object>> rows = cmsRegistrationMapper.countPaidVerificationByOwners(ownerType, ownerIds);
        if (rows == null) return out;
        for (Map<String, Object> row : rows) {
            Object owner = row.get("owner_id");
            Object status = row.get("verification_status");
            if (status instanceof Boolean) status = ((Boolean) status) ? 1 : 0;  // 驱动把 tinyint(1) 映射成 Boolean 时兜底
            Object cnt = row.get("cnt");
            if (!(owner instanceof Number) || !(status instanceof Number) || !(cnt instanceof Number)) continue;
            int idx = ((Number) status).intValue();
            if (idx != 0 && idx != 1) continue;
            out.computeIfAbsent(((Number) owner).longValue(), k -> new long[2])[idx] += ((Number) cnt).longValue();
        }
        return out;
    }

    /**
     * 新增活动/主题报名
     *
     * @param cmsRegistration 活动/主题报名
     * @return 结果
     */
    @Override
    public int insertCmsRegistration(CmsRegistration cmsRegistration) {
        cmsRegistration.setCreateTime(DateUtils.getNowDate());
        return cmsRegistrationMapper.insertCmsRegistration(cmsRegistration);
    }

    /**
     * 修改活动/主题报名
     *
     * @param cmsRegistration 活动/主题报名
     * @return 结果
     */
    @Override
    public int updateCmsRegistration(CmsRegistration cmsRegistration) {
        cmsRegistration.setUpdateTime(DateUtils.getNowDate());
        return cmsRegistrationMapper.updateCmsRegistration(cmsRegistration);
    }

    /**
     * 批量删除活动/主题报名
     *
     * @param ids 需要删除的活动/主题报名主键
     * @return 结果
     */
    @Override
    public int deleteCmsRegistrationByIds(Long[] ids) {
        assertDeletable(ids);
        return cmsRegistrationMapper.deleteCmsRegistrationByIds(ids);
    }

    /**
     * 删除活动/主题报名信息
     *
     * @param id 活动/主题报名主键
     * @return 结果
     */
    @Override
    public int deleteCmsRegistrationById(Long id) {
        assertDeletable(new Long[]{id});
        return cmsRegistrationMapper.deleteCmsRegistrationById(id);
    }

    /**
     * F7 物理删闸:碰过钱的报名单一律不许删。
     *
     * <p>cms_registration 上**连 del_flag 都没有**,这两个删除方法是真 `delete from`。
     * 运营在后台勾选一条已付款报名点删除,链路上没有任何东西拦一下,后果是:
     * <ul>
     *   <li>钱不退,而且连退款单都开不出来 —— reviewRefund 查不到 registration 直接抛</li>
     *   <li>wechatPaymentAmount / transaction_id 蒸发,对账无据</li>
     *   <li>settlement_detail 与 coop_settlement 里指向该 reg 的行成为悬空引用,冲销永远失配</li>
     *   <li>整单退时按 parent_order_no 捞同单会少票,退款金额算错</li>
     * </ul>
     *
     * <p>拒绝 1/2/4 三种状态:
     * <ul>
     *   <li>2 已支付 —— 钱在平台手里,必须先走退款</li>
     *   <li>4 已退款 —— 钱来回走过一遭,这行就是对账凭证,删了等于毁证</li>
     *   <li>1 支付中 —— 钱在途中。这是**最不能删**的时刻:删完支付回调回来就找不到单了</li>
     * </ul>
     * 只放行 0(待支付)与 3(支付失败)—— 那是真脏单,删了无副作用。
     *
     * <p>⚠️ 这只是止血。彻底的做法是软删化(加 del_flag + 全量查询改 where),但那要动这张表的
     * 每一处读,不在本轮范围。
     */
    private void assertDeletable(Long[] ids) {
        if (ids == null) {
            return;
        }
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            CmsRegistration r = cmsRegistrationMapper.selectCmsRegistrationById(id);
            if (r == null) {
                continue;   // 已经不在了,让下游的删除返 0 行即可
            }
            Integer ps = r.getPaymentStatus();
            if (ps != null && (ps == 1 || ps == 2 || ps == 4)) {
                throw new IllegalStateException(
                        "报名单 " + id + " 已付款或退款(payment_status=" + ps + "),不可删除。"
                                + "请走退款流程处理,删除会让钱无处可退且对账失据");
            }
        }
    }

    /**
     * 创建活动报名（单票，向前兼容）
     */
    @Override
    @Transactional
    public CmsRegistration createRegistration(
            Long memberId,
            Integer ownerType,
            Long ownerId,
            Long ticketId,
            Integer isUsePoint,
            String realName,
            String phone,
            String address,
            String email
    ) {
        return createRegistration(memberId, ownerType, ownerId, ticketId, isUsePoint,
                realName, phone, address, email, 1, null, null, null);
    }

    /**
     * 创建活动报名（多票扩展）
     */
    @Override
    @Transactional
    public CmsRegistration createRegistration(
            Long memberId,
            Integer ownerType,
            Long ownerId,
            Long ticketId,
            Integer isUsePoint,
            String realName,
            String phone,
            String address,
            String email,
            Integer count,
            String participateDate,
            String parentOrderNo,
            BigDecimal overridePayable
    ) {
        return createRegistrationInternal(memberId, ownerType, ownerId, ticketId, isUsePoint,
                realName, phone, address, email, count, participateDate, parentOrderNo,
                overridePayable, null, null, null, null);
    }

    private CmsRegistration createRegistrationInternal(
            Long memberId,
            Integer ownerType,
            Long ownerId,
            Long ticketId,
            Integer isUsePoint,
            String realName,
            String phone,
            String address,
            String email,
            Integer count,
            String participateDate,
            String parentOrderNo,
            BigDecimal overridePayable,
            String requestId,
            Long sourceClubId,
            String sourceChannelCode,
            ClubWaitlistClaim waitlistClaim
    ) {

        int qty = (count == null || count <= 0) ? 1 : count;

        // 1. 验证参数 ,ownerType-拥有类型1、主题  2、活动
        validateRegistrationParams(ownerType, ownerId, ticketId, isUsePoint, realName, phone);

        // 2. 验证活动/主题是否存在且有效
        String activityTitle = "";//活动/主题名称
        // [P0-02] 产品真源:purchase_kind 必须由 topic.product_type 推导,**不能拿票的 mode 当真源** ——
        // mode 是 product_type 的派生列,而写票口不止 saveTickets 一个(PricingService 开售时插的
        // 「有人带」票曾硬编码 mode=1、活动自办票直接吃前端传值),拿它反推会把产品类型判错。
        Integer productType = null;
        // [#22 二清闸] 票款归属要在价格算完后才判得了(判据含 payableAmount),而 topic 是在下面两个
        // 分支里各查各的 ⇒ 提一个引用出来带到闸那里。null = 独立活动(无主题真源)= 平台自办,故意放行。
        CmsTopic sellerTopic = null;
        CmsActivity registrationActivity = null;
        if (ownerType == 2) {
            CmsActivity activity = validateAndGetActivity(ownerId, memberId);
            registrationActivity = activity;
            activityTitle = activity.getName();
            // 活动单溯源到主题;独立活动(无 topicId)无主题真源 → 留 null,由 derive 兜底
            if (activity.getTopicId() != null) {
                CmsTopic actTopic = cmsTopicMapper.selectCmsTopicById(activity.getTopicId());
                if (actTopic != null) {
                    productType = actTopic.getProductType();
                    sellerTopic = actTopic;
                    // [P0-50] **活动挂着 ③ 主题时,同样要过 ③ 的开卖闸。**
                    //
                    // ⚠️ 这条是本闸真正的承重点:validateAndGetActivity 只查 status/publishStatus/
                    // delFlag/endDate,**不看 lifecycle、不看 product_type**。而活动可以挂 topicId
                    // 指向一个 ③ 主题(CmsActivityController / ApiCoopController /
                    // CmsRegistrationMerchantController 三个入口可达),玩家买了活动票、
                    // ApiPlayProgressController 顺着 activity.topicId 真的把他送进那个 ③ 主题去玩。
                    // ⇒ 闸只写在 else 分支里 = ③ 的票从活动这条路照卖,四道闸一道走不到。
                    //
                    // 更要命的是覆盖面:ApiRegistrationController:199-222 把 ownerType==1
                    // **无条件** early-return 进 createSelfPlayPass ⇒ 下面那个 else 分支
                    // **从 API 根本不可达**。活动这条才是今天在用的售票路径。
                    assertExploreSellable(actTopic);
                }
            }
        } else {
            CmsTopic topic = validateAndGetTopic(ownerId);
            activityTitle = topic.getName();
            productType = topic.getProductType();
            sellerTopic = topic;
        }
        assertShopDayPointsUnsupported(sellerTopic, isUsePoint);
        assertExploreAccountTicketAvailable(sellerTopic, memberId);
        List<Club> lockedOwningClubs = lockActiveOwningClubs(registrationActivity, sellerTopic);
        revalidateWaitlistParticipant(waitlistClaim, memberId, lockedOwningClubs);
        // 3. 验证票种是否存在、属于当前活动且库存充足
        if (waitlistClaim != null && (qty != 1 || !Integer.valueOf(2).equals(ownerType)
                || !memberId.equals(waitlistClaim.getMemberId()) || !ownerId.equals(waitlistClaim.getActivityId())
                || !ticketId.equals(waitlistClaim.getTicketId()))) {
            throw new RuntimeException("候补库存凭证与报名范围不一致");
        }
        OmsTicket ticket = validateAndGetTicket(ticketId, ownerType, ownerId, waitlistClaim != null);
        if (sellerTopic != null) {
            assertRegistrationSellable(registrationActivity, sellerTopic, ticket, new Date());
        }

        // [P0-13 ③ 限购闸·行级] ③ 是限量供给,一票=一人玩一趟一个主题,qty>1 无语义。
        //
        // ⚠️ 必须在 computePrice(下面那行)**之前** —— 落在其后 = 钱已按 N 张算完,
        //    会建出「按 3 张收钱、只发 1 份权益」的单:reserveEntitlements 的循环维度只有 chapter 没有 qty,
        //    且 uk_entitlement(registration_id, chapter_id) 从表结构上禁止同一单同一章建多行
        //    ⇒ 卖 3 张只占 1 份容量 = 超卖 2 份权益,且「补建 2 行」这条路直接撞 1062 走不通。
        //
        // 判据走 derive 而非裸 product_type:与 :345 落库同口径,规则改了自动同步。
        // 虽然此处 ticketId 保证非 null(validateRegistrationParams 已拒 null/≤0)使二者等价,
        // 但抄一份规则就是未来分叉的种子。
        // productType == null(独立活动无主题真源)⇒ derive 兜底 GUIDED_TICKET ⇒ 放行,fail-safe 方向正确。
        if (PurchaseKind.derive(ticketId, productType) == PurchaseKind.EXPLORE_PASS && qty > 1) {
            throw new RuntimeException("探索票每单限购 1 张");
        }

        // 4. 获取用户信息并验证
        UmsMember member = validateAndGetMember(memberId);
        Long userPoint = member.getPoint() != null ? member.getPoint() : 0; // 用户积分
        ClubOperationBenefit clubBenefit = resolveClubOperationBenefit(memberId, registrationActivity);
        if (waitlistClaim == null) assertClubReservationAvailable(clubBenefit, ticket, qty);

        // 5-7. 金额/积分抵扣/支付方式：统一走 computePrice(与 quoteRegistration 共用，保证「报价=实付」分毫不差)
        BigDecimal ticketPrice = ticket.getPrice(); // 票价（单张）
        PriceCalc calc = computePrice(ticketPrice, clubBenefit.effectivePrice(ticketPrice), qty, userPoint, isUsePoint);
        BigDecimal originalAmount = calc.originalAmount;   // 本条 registration 的金额 = 单价 * 张数
        BigDecimal discountAmount = calc.memberDiscountAmount;
        BigDecimal totalAmount = calc.totalAmount;
        BigDecimal pointDeductAmount = calc.pointDeductAmount;
        int pointUsed = calc.pointUsed;
        BigDecimal payableAmount = calc.payableAmount;
        int paymentType = calc.paymentType;
        BigDecimal pointPaymentAmount = calc.pointPaymentAmount;
        BigDecimal wechatPaymentAmount = calc.wechatPaymentAmount;

        // [#22 二清闸] 价格算完才判得了「这笔到底收不收钱」——积分全额抵扣后 payable=0 的单不进资金链,
        // 不构成二清,不能拦。必须在建单前拦:建了单再拦 = 留一堆永远付不掉的孤儿单。
        assertCollectible(sellerTopic, payableAmount, registrationActivity);
        if (payableAmount.compareTo(BigDecimal.ZERO) > 0 && sellerTopic != null) {
            assertFeeRateValid(sellerTopic.getFeePlatformRate());
        }

        // 8. 创建报名记录
        CmsRegistration registration = new CmsRegistration();

        // 生成唯一报名编号
        String registrationNo = generateRegistrationNo(ownerType);
        registration.setRegistrationNo(registrationNo);

        registration.setOwnerType(Convert.toLong(ownerType));
        registration.setOwnerId(ownerId);
        registration.setActivityTitle(activityTitle);
        registration.setTicketId(ticketId);
        registration.setTicketName(ticket.getName());
        registration.setTicketPrice(ticketPrice);
        // [P0-02] 购买类型下单即固化 —— 主题的 product_type 事后可能被改,已售订单必须按买的时候算
        // (退款档位与结算口径都挂在它上面)。
        // ⚠️ 喂的是**主题真源 product_type**,不是票的 mode。初版喂 mode 是错的:那句
        // 「活动票的 mode 由主题票复制而来」只对派单路径成立,自办活动的票 mode 是前端直传的;
        // 而 PricingService 开售时插的「有人带」票曾硬编码 mode=1 —— 拿派生列反推真源,
        // 会造出「玩法=自由定向、账=有人带票」这种同一笔订单两个口径的单。
        // productType 为 null(独立活动 / 存量未回填)时 derive 按经典定向兜底,与 resolveMode 同口径。
        registration.setPurchaseKind(PurchaseKind.derive(ticketId, productType));
        // [R9-P1] 票源俱乐部归因只接受服务端事实 —— 自报值只进审计码与日志,恒不进这一列。
        auditDiscardedTicketSourceClaim(sourceClubId, memberId, ownerId);
        registration.setSourceClubId(null);
        registration.setSourceChannelCode(normalizeTicketSourceChannel(sourceChannelCode));
        // [P0-06] 退款政策同刻冻结。与 purchase_kind 同处不是巧合:**产品真源定了,政策就定了**,
        // 分开写迟早分叉。锚点源(票的 end_time)卖票后还能被后台改,现取 = 追溯改写已售单的退款窗口。
        // 只落 ③(施工图:「P0-06 只需 ③ 的档位」);①② 返回 null ⇒ 不打快照 ⇒ 读侧回落现取。
        RefundPolicy.Snapshot rp = RefundPolicy.snapshotOnPurchase(
                registration.getPurchaseKind(), ticket.getEndTime());
        if (rp != null) {
            registration.setRefundPolicyCode(rp.code);
            registration.setRefundPolicyVersion(rp.version);
            registration.setRefundDeadline(rp.deadline);
        }
        registration.setOrderNum(Long.valueOf(qty));
        registration.setParticipateDate(participateDate);
        registration.setParentOrderNo(parentOrderNo);
        registration.setRequestId(requestId);
        registration.setMemberId(memberId);
        registration.setRealName(realName);
        registration.setPhone(phone);
        registration.setAddress(address);
        registration.setEmail(email);

        // 设置金额相关
        registration.setOriginalAmount(originalAmount);
        registration.setDiscountAmount(discountAmount);
        registration.setTotalAmount(totalAmount);
        registration.setPointUsed(Convert.toLong(pointUsed));
        registration.setPointDeductAmount(pointDeductAmount);
        registration.setPointDeductRate(calc.pointDeductRate);
        // 多票主单：覆盖 payableAmount 为汇总金额，其他从单为 0
        BigDecimal finalPayable = overridePayable != null ? overridePayable : payableAmount;
        registration.setPayableAmount(finalPayable);
        registration.setPointPaymentAmount(pointPaymentAmount);

        // 微信应收=真实现金额(行274/279按支付方式已算对);此前硬编码 0.01 测试值已移除
        registration.setWechatPaymentAmount(wechatPaymentAmount);
        registration.setFeePlatformRate(resolveFeeSnapshot(sellerTopic, ownerType, finalPayable));

        // 设置支付相关
        registration.setPaymentType(paymentType);
        registration.setPaymentStatus(0); // 0待支付
        registration.setRegistrationStatus(1); // 1待支付

        // 设置支付过期时间（30分钟后过期）
        Date expireTime = DateUtils.addMinutes(new Date(), 30);
        if (waitlistClaim != null && waitlistClaim.getExpiresAt() != null
                && waitlistClaim.getExpiresAt().before(expireTime)) {
            expireTime = waitlistClaim.getExpiresAt();
        }
        registration.setPayExpireTime(expireTime);

        // 设置创建信息
        registration.setCreateBy(memberId.toString());
        registration.setCreateTime(new Date());

        // 9. 保存报名记录
        cmsRegistrationMapper.insertCmsRegistration(registration);

        if (waitlistClaim != null) {
            if (clubWaitlistOrderBridge == null) throw new IllegalStateException("候补报名桥未配置");
            clubWaitlistOrderBridge.bindRegistration(waitlistClaim, registration.getId());
        }

        // 10. 扣减库存（按 qty 张）
        // [P0-资损] 原子扣减(remaining >= qty 才扣),并发下两单同读旧值也不会双卖;0 行 = 库存不足
        if (waitlistClaim == null) {
            long remainInv = ticket.getRemainingInventory() == null ? 0L : ticket.getRemainingInventory();
            if (remainInv < qty) {
                throw new RuntimeException("库存不足,仅剩 " + remainInv + " 张");
            }
            int inventoryUpdated = clubBenefit.shouldKeepReservation()
                    ? omsTicketMapper.deductInventoryKeepingReserved(ticket.getId(), (long) qty,
                            clubBenefit.memberReservedQuota)
                    : omsTicketMapper.deductInventory(ticket.getId(), (long) qty);
            if (inventoryUpdated == 0) {
                if (clubBenefit.shouldKeepReservation()) {
                    throw new RuntimeException("剩余名额已为俱乐部会员保留");
                }
                throw new RuntimeException("库存不足,请重试");
            }
        }

        // [P0-15] ③ 购票预占权益容量 —— 读成「扣库存 → 占权益 → 扣积分」一条扣减链。
        //
        // 落点为什么在这:① 必须在 insertCmsRegistration 之后(reserveEntitlements 第一个参数是
        // registrationId);② 库存闸更便宜,先跑,容量不足的场景多数已被库存挡掉;
        // ③ 三者都抛 RuntimeException,裸 @Transactional 的默认规则即回滚,无需改注解。
        //
        // ⚠️ topicId 必须用 sellerTopic.getId(),**绝不能用 registration.getTopicId()** ——
        // 全类 setTopicId 只有 createSelfPlayPass 一处,活动单的 registration.topicId 恒 null,
        // 用它会把 ③ 整条静默漏掉(不报错、不告警,只是永远不占容量)。
        //
        // ⚠️ 必须按 purchase_kind 显式收窄,**不能无条件调**:reserveEntitlements 对 ①② 事实上
        // no-op 靠的是「requiredChapters(topicId) 恰好为空」这个**数据巧合**,不是语义闸 ——
        // selectRequiredByTopic 只 where topic_id=? and required=1,**不看 product_type**。
        // 生产实测:34 个存量章节全部 required=1,一旦有人给 ① 主题配章节,① 的票会立刻
        // 静默开始占 ③ 的容量。
        //
        // 多票路径无需单独接线::491 循环调 createRegistration 自动继承,语义 = 每行各得一整套
        // entitlement,正确;任一行容量不足则全单回滚,符合「不允许只发一半权益」。
        // ⚠️ 不要照抄二清闸的「整单层再判一次」——那个理由(payable 是整单概念)不适用于 entitlement:
        // 容量按行/按票算,不按钱算,整单再调一次 = 重复预占。
        if (PurchaseKind.EXPLORE_PASS == registration.getPurchaseKind() && sellerTopic != null) {
            perkLedgerService.reserveEntitlements(registration.getId(), sellerTopic.getId());
            // DOC2 F3 Phase 1:③ 套票冻结快照并按持有人发放单位。sale 开关默认关 ⇒ 空转零写入;
            // 同事务,任一步抛出整单回滚(与上面的权益预占同一条纪律)。
            if (freeExploreBundleService != null) {
                freeExploreBundleService.freezeOnOrderCreated(registration, sellerTopic, registrationActivity);
            }
        }

        //11-12. 扣用户积分 + 记流水
        deductPointsAndLog(memberId, userPoint, pointUsed, ownerType, registration.getId());

        return registration;
    }

    /**
     * 扣积分 + 记消费流水。抽出来是因为多票必须在【整单层】扣一次:
     * 抵扣封顶是订单级概念(见 createMultiTicketRegistrations),行内各扣会按错误的行级封顶算。
     * 单票路径仍在行内调用,行为不变。
     */
    private void deductPointsAndLog(Long memberId, long userPoint, int pointUsed,
                                    Integer ownerType, Long registrationId) {
        if (pointUsed <= 0) {
            return;
        }
        // [P1-资损] 扣减 0 行 = 并发下积分已不足,必须抛出回滚订单,否则未扣分也能下单成功
        if (umsMemberMapper.deductPoints(memberId, pointUsed) == 0) {
            throw new RuntimeException("积分不足,请重试");
        }
        String reason = "";
        Long eventType = 0L;
        if (ownerType != null && ownerType == 1) {
            reason = "主题报名";
            eventType = 3L;
        }
        if (ownerType != null && ownerType == 2) {
            reason = "活动报名";
            eventType = 4L;
        }
        UmsMemberPointsDetail umsMemberPointsDetail = new UmsMemberPointsDetail();
        umsMemberPointsDetail.setMemberId(memberId);
        umsMemberPointsDetail.setCurrentPoints(userPoint);
        umsMemberPointsDetail.setAfterPoints(userPoint - Convert.toLong(pointUsed));
        umsMemberPointsDetail.setChangePoints(Convert.toLong(pointUsed));
        umsMemberPointsDetail.setChangeReason(reason);
        umsMemberPointsDetail.setEventType(eventType);
        umsMemberPointsDetail.setEventId(registrationId);
        umsMemberPointsDetail.setChangeType(2L);
        umsMemberPointsDetail.setDelFlag(0);
        umsMemberPointsDetail.setCreateTime(new Date());
        umsMemberPointsDetailMapper.insertUmsMemberPointsDetail(umsMemberPointsDetail);
    }

    /**
     * BE-09 多票原子下单:把「循环建多条 registration + 首单汇总应付」整体包进一个事务。
     * createRegistration 各自的 @Transactional(REQUIRED) 会加入本外层事务,任一票抛异常则全部回滚,
     * 杜绝原控制器循环里「第2票失败但第1票已提交成可付款孤儿单」的资损。
     */
    @Override
    @Transactional
    public List<CmsRegistration> createMultiTicketRegistrations(
            Long memberId, Integer ownerType, Long ownerId, Integer isUsePoint,
            String realName, String phone, String address, String email,
            List<RegistrationTicketItem> tickets) {
        return createMultiTicketRegistrationsInternal(memberId, ownerType, ownerId, isUsePoint,
                realName, phone, address, email, tickets, null, null, null);
    }

    private List<CmsRegistration> createMultiTicketRegistrationsInternal(
            Long memberId, Integer ownerType, Long ownerId, Integer isUsePoint,
            String realName, String phone, String address, String email,
            List<RegistrationTicketItem> tickets, String requestId,
            Long sourceClubId, String sourceChannelCode) {
        String parentOrderNo = "P" + DateUtils.parseDateToStr("yyyyMMdd", new Date())
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // 1) 先累出【整单票面】,再对整单做一次积分抵扣 —— 与 quoteRegistration 完全同口径,
        //    保证「报价=实付」分毫不差。
        //    ⚠️ 不能沿用「每行各算、只给首行传 usePoint」的老写法:computePrice 把抵扣封顶降到
        //    「本行 totalAmount」,于是实际封顶 = min(50元, tickets[0] 行金额) —— 同一个购物车
        //    排序不同优惠不同。抵扣是订单级概念。
        // 整单的卖方主题:ownerType/ownerId 是**整单级**的 ⇒ 一单里所有行必属同一主题
        // ⇒「这单是不是 ③」是整单属性,不必逐行判。两道整单闸共用它,只解析一次。
        CmsTopic orderTopic = resolveSellerTopic(Convert.toLong(ownerType), ownerId);
        Integer orderProductType = orderTopic == null ? null : orderTopic.getProductType();
        int requestedExploreQty = 0;
        if (tickets != null) {
            for (RegistrationTicketItem item : tickets) {
                int qty = item.getCount() == null || item.getCount() <= 0 ? 1 : item.getCount();
                if (PurchaseKind.derive(item.getTicketId(), orderProductType) == PurchaseKind.EXPLORE_PASS) {
                    requestedExploreQty += qty;
                }
            }
        }
        if (requestedExploreQty > 1) throw new RuntimeException("探索票每单限购 1 张");
        CmsActivity orderActivity = ownerType != null && ownerType == 2
                ? cmsActivityMapper.selectCmsActivityById(ownerId) : null;
        assertShopDayPointsUnsupported(orderTopic, isUsePoint);
        assertExploreAccountTicketAvailable(orderTopic, memberId);
        UmsMember member = validateAndGetMember(memberId);
        long userPoint = member.getPoint() != null ? member.getPoint() : 0L;
        ClubOperationBenefit clubBenefit = resolveClubOperationBenefit(memberId, orderActivity);
        BigDecimal orderTotal = BigDecimal.ZERO;
        BigDecimal memberPriceTotal = BigDecimal.ZERO;
        for (RegistrationTicketItem it : tickets) {
            int qty = (it.getCount() == null || it.getCount() <= 0) ? 1 : it.getCount();
            OmsTicket t = validateAndGetTicket(it.getTicketId(), ownerType, ownerId);
            // W3f 整单金额按这次读到的票价累加,行内建单会重读票 ⇒ 计价的这张票自己也必须过可售闸。
            if (orderActivity != null && orderTopic != null) {
                assertRegistrationSellable(orderActivity, orderTopic, t, new Date());
            }
            orderTotal = orderTotal.add(t.getPrice().multiply(BigDecimal.valueOf(qty)));
            memberPriceTotal = memberPriceTotal.add(clubBenefit.effectivePrice(t.getPrice())
                    .multiply(BigDecimal.valueOf(qty)));
            assertClubReservationAvailable(clubBenefit, t, qty);
        }
        PriceCalc order = computePrice(orderTotal, memberPriceTotal, 1, userPoint, isUsePoint);

        // [#22 二清闸] **整单层必须自己判一次,不能靠 createRegistration 的行级闸兜底。**
        // 行级闸看的是每行自己的 payable,而真正要收的钱是整单的 order.payableAmount,它要到下面
        // :首单汇总 才被写回去。⇒「第一张票 0 元 + 第二张 100 元」时,每行过闸都是 payable=0(从票
        // 还被显式传 ZERO)⇒ 全部放行 ⇒ 首单事后变成 100 元待支付,第三方照收。
        // 这个洞是 code-review 从本闸初版里挖出来的,ErqingGateTest#multiTicket_freeFirstPaidSecond_rejected 守着。
        assertCollectible(orderTopic, order.payableAmount, orderActivity);

        // 2) 建各行:行内一律不动积分(整单层统一处理);从单 payable=0,由首单汇总承担
        List<CmsRegistration> allCreated = new java.util.ArrayList<>();
        for (int i = 0; i < tickets.size(); i++) {
            RegistrationTicketItem it = tickets.get(i);
            int qty = (it.getCount() == null || it.getCount() <= 0) ? 1 : it.getCount();
            CmsRegistration r = createRegistrationInternal(
                    memberId, ownerType, ownerId, it.getTicketId(),
                    0,                                 // 积分在整单层扣,行内不碰
                    realName, phone, address, email, qty, it.getParticipateDate(),
                    parentOrderNo,
                    i == 0 ? null : BigDecimal.ZERO,
                    i == 0 ? requestId : null,
                    sourceClubId, sourceChannelCode, null);
            allCreated.add(r);
        }

        // 3) 整单结果落到首单 —— 它是唯一有微信交易(out_trade_no)的支付主体。
        //    ⚠️ paymentType 必须一并写:整单抵扣后首行 payable 会变负(如 10-50),若沿用行级算出的
        //    paymentType=0(纯积分),控制器会判成零元单直接伪造回调标记已支付 = 白嫖整单。
        CmsRegistration first = allCreated.get(0);
        first.setPayableAmount(order.payableAmount);
        first.setWechatPaymentAmount(order.wechatPaymentAmount);
        first.setPaymentType(order.paymentType);
        first.setFeePlatformRate(resolveFeeSnapshot(orderTopic, ownerType, order.payableAmount));
        first.setPointUsed(Convert.toLong(order.pointUsed));
        first.setPointDeductAmount(order.pointDeductAmount);
        first.setPointPaymentAmount(order.pointPaymentAmount);
        updateCmsRegistration(first);

        // 4) 积分扣减:整单一次,挂在首单
        deductPointsAndLog(memberId, userPoint, order.pointUsed, ownerType, first.getId());
        return allCreated;
    }

    /**
     * 探店日一期一票。主题行锁把“查现有票→插新单”串行化，跨 activity shell 仍共用同一源 topic。
     * 门槛只读 finalize 冻结快照，绝不回退 live 票/章/offer 或另造配置。
     */
    private void assertExploreAccountTicketAvailable(CmsTopic sellerTopic, Long memberId) {
        ExploreAccountTicketGate.assertAvailable(
                sellerTopic, memberId, topicSaleSnapshotMapper, cmsRegistrationMapper);
    }

    /**
     * M2 自玩票:购买主题通行证。owner_type=1 + topic_id,金额取 topic.self_play_price,不走 ticket。
     * 复用现有 pay/回调/退款通道(handleWechatPayCallback 支付成功置 registration_status=2)。
     */
    @Override
    @Transactional
    public CmsRegistration createSelfPlayPass(Long memberId, Long topicId, String realName, String phone) {
        return createSelfPlayPassInternal(memberId, topicId, realName, phone, null, null);
    }

    private CmsRegistration createSelfPlayPassInternal(Long memberId, Long topicId,
                                                       String realName, String phone, String requestId,
                                                       String requestFingerprint) {
        // 自玩配额没有独立库存列，锁主题行作为同主题建单的串行点；不能先做普通读再计数，
        // 否则并发非会员会同时看见同一个剩余名额并绕过会员保留规则。
        CmsTopic lockedTopic = cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId);
        // 兼容旧 mapper mock；真实库中锁读为空只代表主题不存在，随后普通校验仍会给出原有业务错误。
        CmsTopic topic = lockedTopic != null ? lockedTopic : cmsTopicMapper.selectCmsTopicById(topicId);
        // 9-15 拍板:免费自玩(统一免费口径)上架且未截止即可领,不要求 SELLING;商业主题照旧走合同二闸。
        boolean freeTopic = isFreeTopic(topic);
        if (freeTopic) {
            assertFreeTopicOpen(topic, new Date());
            // 免费判定(evaluate)是另一次读;按加锁行复核真正要收的自玩价,收费即回「刷新重下」。
            if (topic.getSelfPlayPrice() != null && topic.getSelfPlayPrice().signum() != 0) {
                throw new RuntimeException("票价已变更,请刷新后重新下单");
            }
        } else {
            topic = validateTopicSellable(topic);
        }
        assertExploreAccountTicketAvailable(topic, memberId);
        lockActiveOwningClubs(null, topic);
        if (!freeTopic) SaleAvailabilityPolicy.assertSellable(topic, null, new Date());
        if (topic.getSelfPlay() == null || topic.getSelfPlay() != 1) {
            throw new RuntimeException("该主题未开放自玩");
        }
        if (topic.getSelfPlayPrice() == null || topic.getSelfPlayPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new RuntimeException("自玩票价未配置");
        }
        validateAndGetMember(memberId);
        // H2 兼容边界:旧客户端不带 requestId 时，维持会员级复用，避免连点产生两张待支付单。
        // 新客户端一旦带 requestId，只能重放同一 requestId + 指纹；不能借用旧客户端或另一请求的
        // 待支付单，否则会把“不同请求”伪装为成功重放。查询在主题行锁之后，查已有→建新单仍串行。
        CmsRegistration reusable = findLiveSelfPlayPass(topicId, memberId);
        if (reusable != null) {
            if (requestId != null && (requestFingerprint == null || !requestId.equals(reusable.getRequestId())
                    || !requestFingerprint.equals(reusable.getRequestFingerprint()))) {
                throw new RegistrationRequestConflictException("该主题已有待支付自玩通行证，请继续原订单或取消后重新提交");
            }
            return reusable;
        }
        ClubOperationBenefit clubBenefit = resolveClubOperationBenefit(memberId, topic.getClubId());
        // M2 自玩配额：待支付单也占位，过期/取消单由 orderCloseTask 置为 3 后自动释放。
        // topic 行锁保证「计数 → 插单」对同一主题串行，避免非会员并发越过会员保留名额。
        Integer quota = topic.getSelfPlayQuota();
        if (quota != null && quota > 0) {
            int occupied = cmsRegistrationMapper.countActiveSelfPlayPasses(topicId);
            if (occupied >= quota) {
                throw new RuntimeException("自玩票已售罄");
            }
            if (clubBenefit.shouldKeepReservation()
                    && occupied >= quota - clubBenefit.memberReservedQuota) {
                throw new RuntimeException("剩余自玩名额已为俱乐部会员保留");
            }
        }
        BigDecimal originalPrice = topic.getSelfPlayPrice();
        BigDecimal price = clubBenefit.effectivePrice(originalPrice);

        // [#22 二清闸] 自玩恒收费(上面 :509 已强制 selfPlayPrice > 0)且 ② 的 CONTENT 槽恒为第三方
        // (玩家/俱乐部)⇒ 每一张自玩票都是「平台替第三方收钱」。生产实测 21 笔付费单是 ownerType=1,
        // 全走这条 —— 只在活动分支设闸会把它整条漏掉。
        if (price.compareTo(BigDecimal.ZERO) > 0) {
            assertCollectible(topic, price);
            assertFeeRateValid(topic.getFeePlatformRate());
        }

        CmsRegistration r = new CmsRegistration();
        r.setRegistrationNo(generateRegistrationNo(1)); // T 前缀=主题
        r.setRequestId(requestId);
        r.setOwnerType(1L);
        r.setOwnerId(topicId);
        r.setTopicId(topicId);
        r.setActivityTitle(topic.getName());
        r.setMemberId(memberId);
        r.setRealName(realName);
        r.setPhone(phone);
        r.setOrderNum(1L);
        r.setOriginalAmount(originalPrice);
        r.setDiscountAmount(originalPrice.subtract(price));
        r.setTotalAmount(price);
        r.setPointUsed(0L);
        r.setPointDeductAmount(BigDecimal.ZERO);
        r.setPointDeductRate(pointsValuePolicy.rate());
        r.setPayableAmount(price);
        r.setPointPaymentAmount(BigDecimal.ZERO);
        r.setWechatPaymentAmount(price);
        r.setFeePlatformRate(topic.getFeePlatformRate());
        r.setPaymentType(price.compareTo(BigDecimal.ZERO) > 0 ? 1 : 0);
        r.setPaymentStatus(0);
        r.setRegistrationStatus(1);
        r.setPayExpireTime(DateUtils.addMinutes(new Date(), 30));
        r.setExpiresAt(DateUtils.addDays(new Date(), 90)); // M2 通行证有效期 90 天
        // [P0-02] 自玩通行证恒为 ②。此前「是不是自玩票」只能靠 (owner_type=1 + ticket_id 为空 + expires_at 非空)
        // 的组合约定反推,没有任何一列直说 —— 现在有了。
        r.setPurchaseKind(PurchaseKind.SELF_PLAY_PASS);
        r.setCreateBy(memberId.toString());
        r.setCreateTime(new Date());
        if (cmsRegistrationMapper.insertCmsRegistration(r) != 1 || r.getId() == null) {
            throw new IllegalStateException("自玩报名原订单写入失败");
        }
        return r;
    }

    /**
     * 会员级幂等判定。
     *
     * @return 可以直接复用的待支付通行证；已持有有效权益时抛业务异常；都没有时返回 null 让调用方建新单。
     */
    private CmsRegistration findLiveSelfPlayPass(Long topicId, Long memberId) {
        List<CmsRegistration> live = cmsRegistrationMapper.selectLiveSelfPlayPasses(topicId, memberId);
        if (live == null || live.isEmpty()) {
            return null;
        }
        for (CmsRegistration pass : live) {
            if (pass != null && Integer.valueOf(2).equals(pass.getRegistrationStatus())) {
                // 已经买过且还在 90 天有效期内:再卖一张就是重复权益 + 重复扣款。
                throw new RuntimeException("你已持有该主题的自玩通行证,可直接开玩");
            }
        }
        // 剩下的是待支付且付款窗口未过的单:回到同一张,不再叠第二张付费单。
        return live.get(0);
    }

    /**
     * 报名报价（M0-1）：不落库、不扣减，与 createRegistration 共用 computePrice 计算。
     */
    @Override
    public RegistrationQuoteResult quoteRegistration(Long memberId, Integer ownerType, Long ownerId,
            Long ticketId, List<RegistrationTicketItem> tickets, Integer isUsePoint) {
        return quoteRegistrationInternal(memberId, ownerType, ownerId, ticketId, tickets, isUsePoint, null);
    }

    @Override
    public RegistrationQuoteResult quoteRegistrationForOffer(Long memberId, RegistrationRequest request) {
        if (request == null || clubWaitlistOrderBridge == null) {
            throw new IllegalStateException("候补报价桥未配置");
        }
        ClubWaitlistClaim hold = clubWaitlistOrderBridge.validateForQuote(memberId, request, new Date());
        if (hold == null) throw new IllegalStateException("候补报价凭证不存在");
        return quoteRegistrationInternal(memberId, request.getOwnerType(), request.getOwnerId(),
                request.getTicketId(), request.getTickets(), request.getIsUsePoint(), hold);
    }

    private RegistrationQuoteResult quoteRegistrationInternal(Long memberId, Integer ownerType, Long ownerId,
            Long ticketId, List<RegistrationTicketItem> tickets, Integer isUsePoint,
            ClubWaitlistClaim inventoryHold) {
        // 主题级报名已下线，仅活动层报价（与 create 一致）
        CmsActivity activity = null;
        if (ownerType != null && ownerType == 2) {
            activity = validateAndGetActivity(ownerId, memberId);
        } else {
            CmsTopic topic = cmsTopicMapper.selectCmsTopicById(ownerId);
            if (isFreeTopic(topic)) {
                assertFreeTopicOpen(topic, new Date());
            } else {
                validateTopicSellable(topic);
            }
        }
        CmsTopic sellerTopic = resolveSellerTopic(Convert.toLong(ownerType), ownerId);
        assertShopDayPointsUnsupported(sellerTopic, isUsePoint);
        UmsMember member = validateAndGetMember(memberId);
        long userPoint = member.getPoint() != null ? member.getPoint() : 0L;
        int usePoint = (isUsePoint != null && isUsePoint == 1) ? 1 : 0;
        ClubOperationBenefit clubBenefit = resolveClubOperationBenefit(memberId, activity);

        BigDecimal aggregatePayable = BigDecimal.ZERO;
        int pointsUsed = 0;
        BigDecimal pointsDeduct = BigDecimal.ZERO;
        BigDecimal memberDiscountYuan = BigDecimal.ZERO;
        StringBuilder ticketsKey = new StringBuilder();

        if (tickets == null || tickets.isEmpty()) {
            // 单票模式
            OmsTicket ticket = validateAndGetTicket(ticketId, ownerType, ownerId, inventoryHold != null);
            // F03 报价与报名同一可售口径:报价成功而下单被闸拒 = 用户走到末端才失败。
            if (activity != null && sellerTopic != null) assertRegistrationSellable(activity, sellerTopic, ticket, new Date());
            if (inventoryHold == null) assertClubReservationAvailable(clubBenefit, ticket, 1);
            PriceCalc c = computePrice(ticket.getPrice(), clubBenefit.effectivePrice(ticket.getPrice()),
                    1, userPoint, usePoint);
            aggregatePayable = c.payableAmount;
            pointsUsed = c.pointUsed;
            pointsDeduct = c.pointDeductAmount;
            memberDiscountYuan = c.memberDiscountAmount;
            ticketsKey.append(ticketId).append(':').append(1);
            if (inventoryHold != null) ticketsKey.append("@offer:").append(inventoryHold.getOfferId());
        } else {
            if (inventoryHold != null) throw new RuntimeException("候补 offer 不支持多票报价");
            // 多票模式：先累出【整单票面】，再对整单做一次积分抵扣。
            // 不能每票各调一次 computePrice —— 它把抵扣封顶降到「本行 totalAmount」，
            // 于是实际封顶 = min(50元, tickets[0] 那一行的金额)，导致同一个购物车排序不同优惠不同
            // （早鸟¥10 在前只抵¥10，全价¥200 在前能抵¥50）。抵扣是订单级概念，必须按整单算。
            BigDecimal orderTotal = BigDecimal.ZERO;
            BigDecimal memberPriceTotal = BigDecimal.ZERO;
            for (int i = 0; i < tickets.size(); i++) {
                RegistrationTicketItem it = tickets.get(i);
                int qty = (it.getCount() == null || it.getCount() <= 0) ? 1 : it.getCount();
                OmsTicket ticket = validateAndGetTicket(it.getTicketId(), ownerType, ownerId);
                if (activity != null && sellerTopic != null) assertRegistrationSellable(activity, sellerTopic, ticket, new Date());
                orderTotal = orderTotal.add(ticket.getPrice().multiply(BigDecimal.valueOf(qty)));
                memberPriceTotal = memberPriceTotal.add(clubBenefit.effectivePrice(ticket.getPrice())
                        .multiply(BigDecimal.valueOf(qty)));
                assertClubReservationAvailable(clubBenefit, ticket, qty);
                if (i > 0) ticketsKey.append('|');
                ticketsKey.append(it.getTicketId()).append(':').append(qty);
            }
            // qty 传 1：orderTotal 已是整单票面，computePrice 内部再乘 qty 会重复计数
            PriceCalc c = computePrice(orderTotal, memberPriceTotal, 1, userPoint, usePoint);
            aggregatePayable = c.payableAmount;
            pointsUsed = c.pointUsed;
            pointsDeduct = c.pointDeductAmount;
            memberDiscountYuan = c.memberDiscountAmount;
        }

        BigDecimal couponDeductYuan = BigDecimal.ZERO; // 券为商家核销型，不抵扣报名支付
        BigDecimal payAmount = aggregatePayable.setScale(2, BigDecimal.ROUND_HALF_UP);
        assertCollectible(sellerTopic, payAmount, activity);
        BigDecimal pointsDeductYuan = pointsDeduct.setScale(2, BigDecimal.ROUND_HALF_UP);
        String quoteSign = buildQuoteSign(memberId, ownerType, ownerId, ticketsKey.toString(),
                usePoint, payAmount, pointsUsed, couponDeductYuan);
        RegistrationQuoteResult result = new RegistrationQuoteResult(payAmount, pointsUsed, pointsDeductYuan,
                couponDeductYuan, quoteSign,
                memberDiscountYuan.setScale(2, BigDecimal.ROUND_HALF_UP), clubBenefit.isMember,
                clubBenefit.prioritySignupEnabled);
        // 1-22 附注(拍板 2026-09-19 第6条):开场前 24h 内的 ¥ 单,下单确认弹层必须写明「本单不可退」。
        // 截止点只许从 RefundPolicy 唯一场次钟取,报价侧不参与 quoteSign(它是展示性字段,
        // 不随时间翻转而使已发出的报价失效)。非场次单(主题路径)不下发 ⇒ 前端 tri-state 保持默认。
        if (activity != null) {
            boolean free = payAmount.signum() == 0 && pointsUsed == 0;
            Date deadline = free ? activity.getStartDate() : RefundPolicy.sessionRefundDeadline(activity.getStartDate());
            result.setRefundDeadline(deadline);
            result.setRefundableNow(deadline == null ? null
                    : free ? new Date().before(deadline) : !new Date().after(deadline));
        }
        return result;
    }

    /** 无状态报价签名：仅服务端计算，客户端只回传。 */
    private String buildQuoteSign(Long memberId, Integer ownerType, Long ownerId, String ticketsKey,
            int usePoint, BigDecimal payAmount, int pointsUsed, BigDecimal couponDeductYuan) {
        String canonical = memberId + "|" + ownerType + "|" + ownerId + "|" + ticketsKey + "|" + usePoint
                + "|" + payAmount.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString()
                + "|" + pointsUsed
                + "|" + couponDeductYuan.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString()
                + "|" + QUOTE_SIGN_SALT;
        return Md5Utils.hash(canonical);
    }

    /**
     * 金额/积分抵扣/支付方式计算（M0-1 抽取）：createRegistration 与 quoteRegistration 共用，
     * 保证「报价 = 实付」分毫不差。逻辑与原 createRegistration 内联段完全等价。
     */
    private PriceCalc computePrice(BigDecimal ticketPrice, int qty, long userPoint, Integer isUsePoint) {
        return computePrice(ticketPrice, ticketPrice, qty, userPoint, isUsePoint);
    }

    private PriceCalc computePrice(BigDecimal ticketPrice, BigDecimal effectiveTicketPrice,
                                   int qty, long userPoint, Integer isUsePoint) {
        PriceCalc c = new PriceCalc();
        int use = (isUsePoint != null && isUsePoint == 1) ? 1 : 0;
        c.originalAmount = ticketPrice.multiply(BigDecimal.valueOf(qty));
        c.totalAmount = effectiveTicketPrice.multiply(BigDecimal.valueOf(qty));
        c.memberDiscountAmount = c.originalAmount.subtract(c.totalAmount);
        c.pointDeductAmount = BigDecimal.ZERO;
        c.pointUsed = 0;
        // 报价与下单同一次读:汇率与上限取自 PointsValuePolicy,落库的 point_deduct_rate 也用这一份
        BigDecimal pointDeductRate = pointsValuePolicy.rate();
        c.pointDeductRate = pointDeductRate;
        if (use == 1 && userPoint > 0) {
            BigDecimal maxDeductAmount = pointsValuePolicy.maxPerOrder();
            if (c.totalAmount.compareTo(maxDeductAmount) < 0) {
                maxDeductAmount = c.totalAmount;
            }
            BigDecimal userPointCanDeduct = new BigDecimal(userPoint).multiply(pointDeductRate);
            c.pointDeductAmount = userPointCanDeduct.compareTo(maxDeductAmount) > 0 ? maxDeductAmount : userPointCanDeduct;
            try {
                c.pointUsed = c.pointDeductAmount.divide(pointDeductRate, 0, RoundingMode.UP).intValueExact();
            } catch (ArithmeticException unsupportedPoints) {
                throw new ServiceException("积分抵扣数量超过系统支持范围，请核对积分配置");
            }
        }
        c.payableAmount = c.totalAmount.subtract(c.pointDeductAmount);
        if (use == 1) {
            if (c.payableAmount.compareTo(BigDecimal.ZERO) <= 0) {
                c.paymentType = 0;
                c.pointPaymentAmount = c.totalAmount;
                c.wechatPaymentAmount = BigDecimal.ZERO;
            } else {
                c.paymentType = 2;
                c.pointPaymentAmount = c.pointDeductAmount;
                c.wechatPaymentAmount = c.payableAmount;
            }
        } else {
            c.paymentType = 1;
            c.pointPaymentAmount = BigDecimal.ZERO;
            c.wechatPaymentAmount = c.totalAmount;
        }
        return c;
    }

    /** 解析活动归属俱乐部并冻结本次报名的会员经营权益。 */
    private ClubOperationBenefit resolveClubOperationBenefit(Long memberId, CmsActivity activity) {
        if (activity == null || memberId == null) {
            return ClubOperationBenefit.NONE;
        }
        Long clubId = activity.getClubId();
        if ((clubId == null || clubId <= 0) && activity.getTopicId() != null) {
            CmsTopic topic = cmsTopicMapper.selectCmsTopicById(activity.getTopicId());
            clubId = topic == null ? null : topic.getClubId();
        }
        return resolveClubOperationBenefit(memberId, clubId);
    }

    private ClubOperationBenefit resolveClubOperationBenefit(Long memberId, Long clubId) {
        if (memberId == null || clubId == null || clubId <= 0) {
            return ClubOperationBenefit.NONE;
        }
        Club club = clubMapper.selectClubById(clubId);
        if (club == null || !Long.valueOf(1L).equals(club.getStatus())) {
            return ClubOperationBenefit.NONE;
        }
        boolean isMember = memberId.equals(club.getMemberId());
        if (!isMember) {
            ClubMember membership = clubMemberMapper.selectByClubAndMember(clubId, memberId);
            isMember = membership != null && Integer.valueOf(1).equals(membership.getStatus())
                    && (membership.getDelFlag() == null || membership.getDelFlag() == 0);
        }
        int reservedQuota = club.getMemberReservedQuota() == null ? 0 : club.getMemberReservedQuota();
        boolean priorityEnabled = Integer.valueOf(1).equals(club.getPrioritySignupEnabled()) && reservedQuota > 0;
        // [2026-08-12 拍板] 俱乐部会员价**全局停用**:恒传 null,effectivePrice 随之退化成原价。
        //
        // 为什么在这一处关而不是逐个调用点关:effectivePrice 有 5 个调用点(算价/多票汇总/自玩票/
        // 购物车结算),散在报名主链路上;它们全从本方法拿值 ⇒ 这里置 null 就一次覆盖干净,漏不掉。
        //
        // 为什么要关:联盟局里俱乐部**既是主办方(分账收款方)又能自己设会员折扣价**,两个身份重合
        // ⇒ 它能把某些票压到低于席位合同成本,而商家成本一分不少照付,打穿的是平台的合同残差
        // ——「平台残差 ≥ 0.1M + |E|×c > 0」这条不变式的前提正是"每笔实付 > 该单成本"。
        // 判据是**「设价的人是不是分账受益方」**,不是玩法;经典定向里俱乐部是被邀带队方、让利是自己掏,
        // 本无套利动机,但用户 2026-08-12 拍板一并全局关,想恢复也是改这一处。
        //
        // ⚠️ 停用的是**读取**不是删列:`club.member_discount_price` 与后台配置入口保留,
        // 恢复只需把这一行换回 club.getMemberDiscountPrice() 的原逻辑。
        BigDecimal memberDiscountPrice = null;
        return new ClubOperationBenefit(isMember, priorityEnabled, reservedQuota, memberDiscountPrice);
    }

    /** 报价阶段提前提示；真正的并发约束在 deductInventoryKeepingReserved 的 SQL 条件中。 */
    private void assertClubReservationAvailable(ClubOperationBenefit benefit, OmsTicket ticket, int qty) {
        if (!benefit.shouldKeepReservation()) {
            return;
        }
        long remaining = ticket.getRemainingInventory() == null ? 0L : ticket.getRemainingInventory();
        if (remaining - qty < benefit.memberReservedQuota) {
            throw new RuntimeException("剩余名额已为俱乐部会员保留");
        }
    }

    /** computePrice 的返回载体 */
    private static class PriceCalc {
        BigDecimal originalAmount = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal memberDiscountAmount = BigDecimal.ZERO;
        BigDecimal pointDeductAmount = BigDecimal.ZERO;
        BigDecimal pointDeductRate = BigDecimal.ZERO;
        int pointUsed = 0;
        BigDecimal payableAmount = BigDecimal.ZERO;
        int paymentType = 1;
        BigDecimal pointPaymentAmount = BigDecimal.ZERO;
        BigDecimal wechatPaymentAmount = BigDecimal.ZERO;
    }

    private static class ClubOperationBenefit {
        static final ClubOperationBenefit NONE = new ClubOperationBenefit(false, false, 0, null);

        final boolean isMember;
        final boolean prioritySignupEnabled;
        final int memberReservedQuota;
        final BigDecimal memberDiscountPrice;

        ClubOperationBenefit(boolean isMember, boolean prioritySignupEnabled,
                             int memberReservedQuota, BigDecimal memberDiscountPrice) {
            this.isMember = isMember;
            this.prioritySignupEnabled = prioritySignupEnabled;
            this.memberReservedQuota = memberReservedQuota;
            this.memberDiscountPrice = memberDiscountPrice;
        }

        BigDecimal effectivePrice(BigDecimal ticketPrice) {
            if (!isMember || memberDiscountPrice == null) {
                return ticketPrice;
            }
            return ticketPrice.min(memberDiscountPrice);
        }

        boolean shouldKeepReservation() {
            return prioritySignupEnabled && !isMember;
        }

    }

    /**
     * 验证报名参数
     */
    private void validateRegistrationParams(
            Integer ownerType, Long ownerId, Long ticketId,
            Integer isUsePoint, String realName, String phone) {

        if (ownerType == null || (ownerType != 1 && ownerType != 2)) {
            throw new RuntimeException("无效的类型");
        }

        if (ownerId == null || ownerId <= 0) {
            throw new RuntimeException("无效的活动/主题ID");
        }

        if (ticketId == null || ticketId <= 0) {
            throw new RuntimeException("请选择票种");
        }

//        if (paymentType == null || (paymentType != 0 && paymentType != 1 && paymentType != 2)) {
//            throw new RuntimeException("无效的支付方式");
//        }

        if (realName == null || realName.trim().isEmpty()) {
            throw new RuntimeException("请输入姓名");
        }

        if (phone == null || phone.trim().isEmpty()) {
            throw new RuntimeException("请输入联系电话");
        }
    }

    private static boolean isLiveParentTopic(CmsTopic topic) {
        return topic != null
                && Integer.valueOf(1).equals(topic.getStatus())
                && Integer.valueOf(1).equals(topic.getUserStatus())
                && Integer.valueOf(0).equals(topic.getDelFlag());
    }

    /**
     * 验证并获取活动信息
     */
    private CmsActivity validateAndGetActivity(Long activityId, Long memberId) {
        CmsActivity activity = cmsActivityMapper.selectCmsActivityById(activityId);
        if (activity == null) {
            throw new RuntimeException("活动不存在");
        }

        if (activity.getStatus() != 1) {
            throw new RuntimeException("活动未发布或已下架");
        }

        if (activity.getPublishStatus() == 0) {
            throw new RuntimeException("活动已下架");
        }

        if (activity.getDelFlag() == 2) {
            throw new RuntimeException("活动已下架");
        }

        Date now = new Date();
        if (activity.getEndDate() != null && now.after(activity.getEndDate())) {
            throw new RuntimeException("活动已结束");
        }

        // 与活动详情使用同一有效成员查询，报价、建单和重试付款均在占库存前检查。
        if (activity.getClubId() != null && activity.getClubId() > 0
                && !(memberId != null && memberId > 0 && memberId.equals(activity.getMemberId()))
                && (memberId == null || memberId <= 0
                    || clubMemberMapper.selectByClubAndMember(activity.getClubId(), memberId) == null)) {
            throw new ServiceException("请先加入俱乐部再报名", HttpStatus.FORBIDDEN);
        }

        // AUDIT-G1-1:父主题被驳回/作者下架/违规下架/仍待审时,其场次不再接新单(已有订单不动)。
        // 口径与公开列表的 livePublicParentTopic 一致:status=1 · user_status=1 · del_flag=0。
        // 此前「壳只在过审后存在」是唯一防线;主题事后下架不回写壳,这里补上第二道。
        if (activity.getTopicId() != null && activity.getTopicId() > 0
                && !isLiveParentTopic(cmsTopicMapper.selectCmsTopicById(activity.getTopicId()))) {
            throw new RuntimeException("主题活动已下架");
        }

        return activity;
    }

    /**
     * 验证并获取主题信息
     */
    /**
     * [P0-50] ③ 自由探索**必须锁价后才能卖**(lifecycle=3)。
     *
     * <p>这是四道闸(地板价 / 费率快照 / 每必选章 SUPPLY≥1 / 计酬全通关必亏)**唯一的承重点** ——
     * 它们全挂在 confirmPrice 上,而 confirmPrice 只认 lifecycle=2。lifecycle=0 一放行,
     * 那四道闸就一道都走不到。
     *
     * <p>⚠️ <b>必须被两条购票分支共用</b>:主题单(ownerType=1)走 validateAndGetTopic,
     * 活动单(ownerType=2)走 validateAndGetActivity —— 后者**不看 lifecycle 也不看 product_type**,
     * 而活动能挂 topicId 指向 ③ 主题。只在一条分支上加闸 = 另一条照样卖。
     *
     * <p>⚠️ 为什么闸不能放在建题路径:建题时加「③ 必须传 recruitDeadline」拦不住 ——
     * 票在建题时就由 saveTickets 插好了(与 lifecycle 无关);processRecruitDeadlines 在
     * accepted==0 时把 lifecycle **推回 0** 且只改 lifecycle、不碰 status ⇒ 主题仍在架上;
     * 而 ③ 的 accepted **恒为 0**(它数 CoopInvite,③ 的供给却是 merchant_chapter_offer,
     * 后者还没有 insert 调用点)。**闸放错层就是假闸。**
     *
     * <p>后果(是对的,不是副作用):入驻链路建好之前 ③ 卖不出票。③ 一个主题都还没建
     * (2026-07-16 实测 product_type 全是 1)⇒ 零存量代价的 fail-closed,防的正是「③ 无闸上线」。
     * ①② 完全不受影响。
     */
    private void assertExploreSellable(CmsTopic topic) {
        if (topic == null) {
            return;
        }
        if (ProductType.FREE_EXPLORE == (topic.getProductType() == null ? -1 : topic.getProductType())) {
            TopicLifecycleState lifecycle;
            try {
                lifecycle = TopicLifecycleState.fromCode(topic.getLifecycle());
            } catch (IllegalArgumentException ex) {
                throw new RuntimeException("该主题生命周期未知，暂未开售", ex);
            }
            if (!lifecycle.isDirectSaleOpen()) {
                throw new RuntimeException("该主题尚未完成招商与锁价,暂未开售");
            }
        }
    }

    /**
     * [#22 二清闸] 对**已存在的单**再判一次 —— 给幂等重放路径用。
     *
     * <p>⚠️ 2026-09-15 核实现码:幂等重放路径({@code RegistrationCheckoutServiceImpl.finishOrder})
     * 只在**非重放**时才发支付参数,重放命中已有单不再直接换参数;重放单要支付必须先走
     * {@link #beginPaymentAttempt}(→ {@link #preparePaymentRetry},含 payExpireTime 校验)。
     * 本方法因此没有生产调用点(仅测试用) —— 保留它是给「重放路径哪天重新发参数」留一道现成的二清闸。
     *
     * <p>⚠️ 生产实测(2026-07-16)带 request_id 的待支付单为 0 ⇒ 今天无单可利用。堵它不是为了今天,
     * 是因为平台自营单建成后若内容槽被 reassignContentSlot 改判成第三方,这条路立刻就活。
     */
    @Override
    public void assertCollectible(CmsRegistration registration) {
        if (registration == null) {
            return;
        }
        BigDecimal payable = registration.getPayableAmount();
        if (payable == null || payable.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        CmsActivity activity = Long.valueOf(2L).equals(registration.getOwnerType())
                ? cmsActivityMapper.selectCmsActivityById(registration.getOwnerId()) : null;
        assertCollectible(resolveSellerTopic(registration), payable, activity);
    }

    @Override
    public java.util.Map<Long, CmsRegistration> selectExplorePassesByActivity(Long memberId) {
        java.util.Map<Long, CmsRegistration> byActivity = new java.util.LinkedHashMap<>();
        if (memberId == null) {
            return byActivity;
        }
        // selectCmsRegistrationList 的 where 全是 <if> ⇒ 支持只给 memberId + ownerType +
        // registrationStatus 而不给 ownerId,一次捞全。
        CmsRegistration probe = new CmsRegistration();
        probe.setMemberId(memberId);
        probe.setOwnerType(2L);          // ③ 定死走活动路径
        probe.setRegistrationStatus(2);  // 报名成功=已支付
        List<CmsRegistration> regs = cmsRegistrationMapper.selectCmsRegistrationList(probe);
        if (regs == null) {
            return byActivity;
        }
        for (CmsRegistration r : regs) {
            // 只认 purchase_kind 固化值。⚠️ 不回落 product_type:NULL 的三个来源
            //(存量未回填 / 后台手工建单 / 独立活动无 topicId)都产生不了「带 entitlement 的 ③ 活动单」
            // —— 后台手工建单走 insertCmsRegistration,压根不经 reserveEntitlements ⇒ 没有 entitlement
            // ⇒ 就算判成 ③ 也恒不通关,回落老口径反而更对。
            if (r.getPurchaseKind() != null && r.getPurchaseKind() == PurchaseKind.EXPLORE_PASS
                    && r.getOwnerId() != null) {
                byActivity.putIfAbsent(r.getOwnerId(), r);
            }
        }
        return byActivity;
    }

    /**
     * 该玩家可投诉的主题。判据见接口 javadoc —— 选择源与受理口共用这一份,别在 controller 里再抄。
     *
     * <p>payment_status=2 这条判据直接进 SQL(mapper 的 where 是 &lt;if&gt; 动态拼的);
     * 退款会把它置 4(RefundServiceImpl / TeamGroupServiceImpl 两条路都是 4+3 同时置),
     * 所以已退款的单天然不在结果里,不需要额外再判 registration_status。
     */
    @Override
    public java.util.List<com.chengyinhub.business.domain.vo.ComplainableTopicVO> selectComplainableTopics(Long memberId) {
        java.util.List<com.chengyinhub.business.domain.vo.ComplainableTopicVO> out = new ArrayList<>();
        if (memberId == null || memberId <= 0) {
            return out;
        }
        CmsRegistration probe = new CmsRegistration();
        probe.setMemberId(memberId);
        probe.setPaymentStatus(2);
        List<CmsRegistration> regs = cmsRegistrationMapper.selectCmsRegistrationList(probe);
        if (regs == null) {
            return out;
        }
        java.util.Set<Long> seen = new java.util.LinkedHashSet<>();
        for (CmsRegistration r : regs) {
            // ★ fail-closed:反查不出主题(独立活动 topic_id=null = 平台自办)就是不可投诉,
            //   绝不兜底成 ownerId —— 那会让玩家投诉到一个 id 恰好撞上的别的主题,
            //   而受理即冻结该主题结算放款。cms_topic 与 cms_activity 是两条独立自增序列、
            //   id 区间大量重叠,撞上是常态不是巧合(同 assertScanTypeMatchesOwner 的教训)。
            CmsTopic topic = resolveSellerTopic(r);
            if (topic == null || topic.getId() == null) {
                continue;
            }
            if (!seen.add(topic.getId())) {
                continue;   // 同一主题多单:保留先到的那张(与 selectExplorePassesByActivity 同口径)
            }
            out.add(new com.chengyinhub.business.domain.vo.ComplainableTopicVO(
                    topic.getId(), topic.getName(), r.getId()));
        }
        return out;
    }

    /**
     * 从单反查「票款归谁」的主题。与 createRegistration 里的分支同口径:
     * ownerType=1 → ownerId 即 topicId;ownerType=2 → 活动溯源到主题;独立活动 → null(平台自办)。
     *
     * <p>⚠️ 不用 {@code registration.getTopicId()} —— 那一列**只有自玩通行证填**(全类唯一写入点),
     * 活动单恒 null,拿它判会把整个 ① 活动单静默放过去。
     */
    private CmsTopic resolveSellerTopic(CmsRegistration registration) {
        return resolveSellerTopic(registration.getOwnerType(), registration.getOwnerId());
    }

    /** 同上,按 (ownerType, ownerId) 直接解析 —— 多票整单闸在建单前就要判,那时还没有 registration。 */
    private CmsTopic resolveSellerTopic(Long ownerType, Long ownerId) {
        if (ownerType == null || ownerId == null) {
            return null;
        }
        if (ownerType == 1L) {
            return cmsTopicMapper.selectCmsTopicById(ownerId);
        }
        CmsActivity activity = cmsActivityMapper.selectCmsActivityById(ownerId);
        if (activity == null || activity.getTopicId() == null) {
            return null;
        }
        return cmsTopicMapper.selectCmsTopicById(activity.getTopicId());
    }

    /** 探店日首三期只允许全价购买；必须在算价、建单、扣库存、占权益和扣积分前拒绝。 */
    private void assertShopDayPointsUnsupported(CmsTopic sellerTopic, Integer isUsePoint) {
        if (Integer.valueOf(1).equals(isUsePoint) && sellerTopic != null
                && Integer.valueOf(ProductType.FREE_EXPLORE).equals(sellerTopic.getProductType())) {
            throw new RuntimeException("探店日不支持积分抵扣，请关闭积分后按全价购买");
        }
    }

    private void assertCollectible(CmsTopic topic, BigDecimal payableAmount) {
        assertCollectible(topic, payableAmount, null);
    }

    private void assertCollectible(CmsTopic topic, BigDecimal payableAmount, CmsActivity activity) {
        if (payableAmount == null || payableAmount.signum() <= 0) return;
        boolean platformOwned = topic != null ? productRoleService.isPlatformOwned(topic.getId())
                : activity != null && activity.getTopicId() == null && platformMemberId != null
                        && platformMemberId > 0L && platformMemberId.equals(activity.getMemberId());
        if (!platformOwned) {
            throw new RuntimeException("该产品暂不支持在线支付(平台正在办理支付资质,预计 2 个月内开放);"
                    + "如需报名请联系主办方");
        }
    }

    private BigDecimal resolveFeeSnapshot(CmsTopic sellerTopic, Integer ownerType, BigDecimal payableAmount) {
        if (payableAmount == null || payableAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal rate = sellerTopic == null ? null : sellerTopic.getFeePlatformRate();
        if (rate == null) {
            String bizType = Integer.valueOf(1).equals(ownerType) ? "topic" : "activity";
            SettlementRule rule = settlementRuleService.getActiveRule(0L, bizType);
            rate = rule == null ? null : rule.getPlatformCommissionRate();
        }
        assertFeeRateValid(rate);
        return rate;
    }

    @Override
    public CmsRegistration preparePaymentRetry(Long registrationId, Long memberId) {
        CmsRegistration registration = cmsRegistrationMapper.selectCmsRegistrationById(registrationId);
        if (registration == null || registration.getMemberId() == null
                || !registration.getMemberId().equals(memberId)) {
            throw new RuntimeException("报名不存在或无权操作");
        }
        if (!Integer.valueOf(1).equals(registration.getRegistrationStatus())
                || !(Integer.valueOf(0).equals(registration.getPaymentStatus())
                || Integer.valueOf(1).equals(registration.getPaymentStatus()))) {
            throw new RuntimeException("订单不是待支付状态");
        }
        Date now = new Date();
        if (registration.getPayExpireTime() == null || !registration.getPayExpireTime().after(now)) {
            throw new RegistrationOrderExpiredException("订单已过期,请重新报名");
        }
        if (registration.getRegistrationNo() == null || registration.getRegistrationNo().trim().isEmpty()) {
            throw new IllegalStateException("报名业务单号缺失,请联系客服处理");
        }
        if (registration.getPayableAmount() == null
                || registration.getPayableAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("该订单无需微信支付");
        }
        CmsTopic sellerTopic = validateRegistrationSellableNow(registration, now);
        assertFeeRateValid(registration.getFeePlatformRate());
        CmsActivity activity = Long.valueOf(2L).equals(registration.getOwnerType())
                ? cmsActivityMapper.selectCmsActivityById(registration.getOwnerId()) : null;
        assertCollectible(sellerTopic, registration.getPayableAmount(), activity);
        return registration;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<CmsRegistration> create(Long memberId, RegistrationRequest request,
                                        String normalizedRequestId, String requestFingerprint) {
        List<CmsRegistration> created = new ArrayList<>();
        ClubWaitlistClaim waitlistClaim;
        if (clubWaitlistOrderBridge == null) {
            if (request.getWaitlistOfferId() != null || request.getWaitlistOfferToken() != null) {
                throw new IllegalStateException("候补报名桥未配置");
            }
            waitlistClaim = null;
        } else {
            waitlistClaim = clubWaitlistOrderBridge.claimForOrder(memberId, request, new Date());
        }
        if (Integer.valueOf(1).equals(request.getOwnerType())) {
            if (waitlistClaim != null) throw new RuntimeException("自玩通行证不支持候补 offer");
            created.add(createSelfPlayPassInternal(memberId, request.getOwnerId(),
                    request.getRealName(), request.getPhone(), normalizedRequestId, requestFingerprint));
        } else if (request.getTickets() != null && !request.getTickets().isEmpty()) {
            if (waitlistClaim != null) throw new RuntimeException("多票报名不支持候补 offer");
            created = createMultiTicketRegistrationsInternal(memberId, request.getOwnerType(),
                    request.getOwnerId(), request.getIsUsePoint(), request.getRealName(),
                    request.getPhone(), request.getAddress(), request.getEmail(),
                    request.getTickets(), normalizedRequestId,
                    request.getSourceClubId(), request.getSourceChannelCode());
        } else {
            created.add(createRegistrationInternal(memberId, request.getOwnerType(), request.getOwnerId(),
                    request.getTicketId(), request.getIsUsePoint(), request.getRealName(), request.getPhone(),
                    request.getAddress(), request.getEmail(), 1, null, null, null, normalizedRequestId,
                    request.getSourceClubId(), request.getSourceChannelCode(), waitlistClaim));
        }
        bindRequestFingerprint(created, normalizedRequestId, requestFingerprint);
        return created;
    }

    private void bindRequestFingerprint(List<CmsRegistration> created, String requestId,
                                        String requestFingerprint) {
        if (requestId == null || requestFingerprint == null || created == null) {
            return;
        }
        for (CmsRegistration registration : created) {
            if (registration != null && requestId.equals(registration.getRequestId())) {
                if (cmsRegistrationMapper.bindRequestFingerprint(
                        registration.getId(), requestId, requestFingerprint) != 1) {
                    CmsRegistration persisted = cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(
                            registration.getId());
                    if (persisted != null && requestId.equals(persisted.getRequestId())
                            && requestFingerprint.equals(persisted.getRequestFingerprint())) {
                        registration.setRequestFingerprint(requestFingerprint);
                        continue;
                    }
                    throw new IllegalStateException("报名请求指纹绑定失败");
                }
                registration.setRequestFingerprint(requestFingerprint);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean bindLegacyRequestFingerprint(Long registrationId, String requestId,
                                                String requestFingerprint) {
        if (registrationId == null || StringUtils.isBlank(requestId)
                || StringUtils.isBlank(requestFingerprint)) {
            return false;
        }
        if (cmsRegistrationMapper.bindRequestFingerprint(
                registrationId, requestId, requestFingerprint) == 1) {
            return true;
        }
        CmsRegistration current = cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(registrationId);
        return current != null
                && requestId.equals(current.getRequestId())
                && requestFingerprint.equals(current.getRequestFingerprint());
    }

    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = RegistrationOrderExpiredException.class)
    public CmsRegistration beginPaymentAttempt(Long registrationId, Long memberId) {
        CmsRegistration registration;
        try {
            registration = preparePaymentRetry(registrationId, memberId);
        } catch (RegistrationOrderExpiredException expired) {
            // [CR-527] 过期即拒 + 即时关单:拒绝之后这单永远不会再被支付,只拒不管会让库存/积分/权益
            // 干等定时任务(甚至 p_expire_time 为空的历史单永远等不到)。关单与拒绝在同一事务里,
            // noRollbackFor 让关单随事务提交 —— 否则紧接着抛出的拒绝异常会把关单一起回滚,退回半套状态。
            // closeExpired 是条件更新(仍待支付才关),与支付回调竞态时 0 行跳过。
            closeExpiredOne(registrationId);
            throw expired;
        }
        if (Integer.valueOf(1).equals(registration.getPaymentStatus())) {
            return registration;
        }
        Date now = new Date();
        if (cmsRegistrationMapper.claimPaymentProcessing(registrationId, memberId, now) == 0) {
            CmsRegistration current = cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(registrationId);
            if (current != null && memberId.equals(current.getMemberId())
                    && Integer.valueOf(1).equals(current.getRegistrationStatus())
                    && Integer.valueOf(1).equals(current.getPaymentStatus())) {
                return current;
            }
            throw new RuntimeException("订单状态已变化,无法发起支付");
        }
        registration.setPaymentStatus(1);
        registration.setUpdateTime(now);
        return registration;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releasePaymentAttempt(Long registrationId) {
        if (cmsRegistrationMapper.releasePaymentProcessing(registrationId, new Date()) > 0) {
            return;
        }
        CmsRegistration current = cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(registrationId);
        if (current == null) {
            throw new IllegalStateException("报名不存在");
        }
        if (Integer.valueOf(0).equals(current.getPaymentStatus())
                || Integer.valueOf(2).equals(current.getPaymentStatus())) {
            return;
        }
        throw new IllegalStateException("支付状态已变化,无法释放预下单尝试");
    }

    /** 已有订单重试时重新读取当前活动/主题与票种开售事实，但不重复扣库存。 */
    private CmsTopic validateRegistrationSellableNow(CmsRegistration registration, Date now) {
        CmsTopic sellerTopic;
        CmsActivity activity = null;
        if (Long.valueOf(1L).equals(registration.getOwnerType())) {
            sellerTopic = validateAndGetTopic(registration.getOwnerId());
        } else if (Long.valueOf(2L).equals(registration.getOwnerType())) {
            activity = validateAndGetActivity(registration.getOwnerId(), registration.getMemberId());
            CmsTopic topic = activity.getTopicId() == null ? null
                    : cmsTopicMapper.selectCmsTopicById(activity.getTopicId());
            // 免费 UGC 不走商业生命周期,在架/截止由 assertRegistrationSellable 判;其余仍要求 SELLING。
            sellerTopic = topic == null || isFreeUgcActivityTopic(activity, topic) ? topic
                    : validateTopicSellable(topic);
        } else {
            throw new RuntimeException("报名类型无效");
        }
        if (registration.getTicketId() != null) {
            OmsTicket ticket = omsTicketMapper.selectOmsTicketById(registration.getTicketId());
            if (ticket == null || Integer.valueOf(2).equals(ticket.getDelFlag())
                    || !registration.getOwnerId().equals(ticket.getOwnerId())
                    || !registration.getOwnerType().equals(ticket.getOwnerType())) {
                throw new RuntimeException("票种已停售");
            }
            if (sellerTopic != null) {
                assertRegistrationSellable(activity, sellerTopic, ticket, now);
            }
        } else if (sellerTopic != null) {
            assertRegistrationSellable(activity, sellerTopic, null, now);
        }
        return sellerTopic;
    }

    /**
     * F03 报名/报价/支付重试共用的主题可售判定。
     *
     * <p>合同二售卖闸(SELLING + 开售/售止事实)只有商业生命周期会产出:纯 UGC 主题按
     * {@link TopicCommercialReviewServiceImpl} 不建审核任务,用户侧也没有任何路径把它推到 SELLING。
     * 对它调合同二闸 = 审核上架了却永远报不了名。所以分叉点在「这个主题归谁管」,由
     * {@link CommercialTopicPolicy} 单一真源判;免费 UGC 的可报名事实 = 主题在架 + 活动在架未结束
     * (validateAndGetActivity)+ 票种报名截止未到。商业主题或已进入售卖阶段的主题一律回合同二闸。
     */
    /**
     * F-W1-05:商业主题未到 SELLING 是「尚未开售」，不是支付故障。玩家侧拿到的必须是这句话，
     * 而不是内部状态码;其余售卖时间拒绝保持原文。
     */
    private void assertCommercialSaleOpen(CmsTopic topic, OmsTicket ticket, Date now) {
        if (topic != null) {
            com.chengyinhub.business.domain.enums.TopicLifecycleState state;
            try {
                state = com.chengyinhub.business.domain.enums.TopicLifecycleState.fromCode(topic.getLifecycle());
            } catch (IllegalArgumentException unknownLifecycle) {
                state = null;
            }
            if (state == null || !state.isDirectSaleOpen()) {
                // PR10 复审:进行中/结算中/已关闭是「已过售卖期」,不能对玩家说「尚未开售」;
                // 未知状态继续 fail-closed,按未开售文案拦截。
                if (state != null && state.isSaleStarted()) {
                    throw new RuntimeException("报名已结束，本主题已停止售卖");
                }
                throw new RuntimeException("该主题尚未开售，开售审核通过后才可报名");
            }
        }
        SaleAvailabilityPolicy.assertSellable(topic, ticket, now);
    }

    private void assertRegistrationSellable(CmsActivity activity, CmsTopic topic, OmsTicket ticket, Date now) {
        if (activity == null || !isFreeUgcActivityTopic(activity, topic)) {
            assertCommercialSaleOpen(topic, ticket, now);
            return;
        }
        assertFreeTopicOnShelf(topic);
        if (ticket != null && ticket.getSignupDeadline() != null && !now.before(ticket.getSignupDeadline())) {
            throw new RuntimeException("票种报名已截止");
        }
        // W3f 资损竞态:免费判定与计价是两次无锁读,中间改价会让收费票走免费分支绕过开售审。
        // 复核真正计价的这张票:免费分支只放行 0 元票,任何收费都回到「刷新后重下」。
        if (ticket != null && (ticket.getPrice() == null || ticket.getPrice().signum() != 0)) {
            throw new RuntimeException("票价已变更,请刷新后重新下单");
        }
    }

    /**
     * 挂在活动上的纯免费 UGC 主题:主题满足 {@link CommercialTopicPolicy#isFreeTopic}(与俱乐部池同一口径),
     * 且活动上没有收费票。活动票(ownerType=2)不在 CommercialTopicPolicy 统计口径内(它只数主题票),
     * 活动票被单独改成收费时必须回到合同二闸,否则就是一条绕过开售审的收款路径。
     */
    private boolean isFreeUgcActivityTopic(CmsActivity activity, CmsTopic topic) {
        if (!isFreeTopic(topic)) return false;
        OmsTicket query = new OmsTicket();
        query.setOwnerType(2L);
        query.setOwnerId(activity.getId());
        List<OmsTicket> activityTickets = omsTicketMapper.selectOmsTicketList(query);
        if (activityTickets != null) for (OmsTicket t : activityTickets) {
            if (t != null && !Integer.valueOf(2).equals(t.getDelFlag())
                    && (t.getPrice() == null || t.getPrice().signum() > 0)) {
                return false;
            }
        }
        return true;
    }

    /** 统一免费口径;依赖缺失(仅旧单测 mock 场景)时 fail-closed 按商业主题处理。 */
    private boolean isFreeTopic(CmsTopic topic) {
        return commercialTopicPolicy != null && commercialTopicPolicy.isFreeTopic(topic);
    }

    private void assertFreeTopicOnShelf(CmsTopic topic) {
        if (!Integer.valueOf(1).equals(topic.getStatus()) || !Integer.valueOf(1).equals(topic.getUserStatus())
                || Integer.valueOf(2).equals(topic.getDelFlag())) {
            throw new RuntimeException("主题活动已下架");
        }
    }

    /** 免费主题级入口(自玩/主题报价)的开放事实:在架 + 主题截止(saleEndAt,缺省取 endDate)未到。 */
    private void assertFreeTopicOpen(CmsTopic topic, Date now) {
        assertFreeTopicOnShelf(topic);
        Date deadline = topic.getSaleEndAt() != null ? topic.getSaleEndAt() : topic.getEndDate();
        if (deadline != null && !now.before(deadline)) {
            throw new RuntimeException("主题已截止");
        }
    }

    /** 主题交易必须使用锁价时冻结的合法费率，禁止缺失/非法时回退默认值继续收款。 */
    private void assertFeeRateValid(BigDecimal rate) {
        if (rate == null || rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
            throw new RuntimeException("支付费率快照缺失或非法,暂不可支付");
        }
    }

    private CmsTopic validateAndGetTopic(Long topicId) {
        return validateTopicSellable(cmsTopicMapper.selectCmsTopicById(topicId));
    }

    /**
     * 报名落库与俱乐部解散共用 club 行锁。活动可同时有直接 club_id 与父主题 club_id，
     * 两者都必须仍在营；排序后取锁避免两个俱乐部交叉关联时死锁。
     */
    private List<Club> lockActiveOwningClubs(CmsActivity activity, CmsTopic topic) {
        List<Long> clubIds = new ArrayList<Long>();
        if (activity != null && activity.getClubId() != null && activity.getClubId() > 0) {
            clubIds.add(activity.getClubId());
        }
        if (topic != null && topic.getClubId() != null && topic.getClubId() > 0
                && !clubIds.contains(topic.getClubId())) {
            clubIds.add(topic.getClubId());
        }
        Collections.sort(clubIds);
        List<Club> locked = new ArrayList<Club>();
        for (Long clubId : clubIds) {
            Club club = clubMapper.selectClubByIdForUpdate(clubId);
            if (club == null) {
                throw new RuntimeException("关联俱乐部已解散，暂不能报名");
            }
            locked.add(club);
        }
        return locked;
    }

    /**
     * 候补 offer 的首次会员判断发生在 claim CAS 之前；退出事务可能在 CAS 后、建单前删除关系。
     * owning club 已按固定顺序加锁后，再锁同一 club_member 行重验，和退出的 club -> member
     * 锁序一致。失败抛出，使外层 create 的 REQUIRED 事务连同 CLAIMED CAS 一起回滚。
     */
    private void revalidateWaitlistParticipant(ClubWaitlistClaim claim, Long memberId,
                                               List<Club> lockedOwningClubs) {
        if (claim == null) return;
        if (claim.getClubId() == null || memberId == null || lockedOwningClubs == null) {
            throw new RuntimeException("候补俱乐部范围不一致");
        }
        Club claimClub = null;
        for (Club club : lockedOwningClubs) {
            if (club != null && claim.getClubId().equals(club.getId())) {
                claimClub = club;
                break;
            }
        }
        if (claimClub == null) {
            throw new RuntimeException("候补俱乐部范围不一致");
        }
        if (memberId.equals(claimClub.getMemberId())) return;
        ClubMember membership = clubMemberMapper.selectByClubAndMemberForUpdate(
                claim.getClubId(), memberId);
        if (membership == null) {
            throw new RuntimeException("仅俱乐部正常成员可认领候补名额");
        }
    }

    private CmsTopic validateTopicSellable(CmsTopic topic) {
        if (topic == null) {
            throw new RuntimeException("主题活动不存在");
        }

        if (topic.getStatus() != 1) {
            throw new RuntimeException("主题活动未发布或已下架");
        }

        // 内容安全命中违规时只写 user_status=0(WxContentSecurityServiceImpl),status 保持 1;
        // 只看 status 会让违规主题从列表消失却仍能带 topicId 直接下单。0/null 均为未上架。
        if (topic.getUserStatus() == null || topic.getUserStatus() != 1) {
            throw new RuntimeException("主题活动已下架");
        }

        if (topic.getDelFlag() == 2) {
            throw new RuntimeException("主题活动已下架");
        }

        TopicLifecycleState lifecycle;
        try {
            lifecycle = TopicLifecycleState.fromCode(topic.getLifecycle());
        } catch (IllegalArgumentException ex) {
            // CL-37:与 assertCommercialSaleOpen 同口径,未知生命周期 fail-closed 按未开售拦截,不露内部状态名。
            throw new RuntimeException("该主题尚未开售，开售审核通过后才可报名", ex);
        }
        if (!lifecycle.isDirectSaleOpen()) {
            if (lifecycle.isSaleStarted()) {
                throw new RuntimeException("报名已结束，本主题已停止售卖");
            }
            throw new RuntimeException("该主题尚未开售，开售审核通过后才可报名");
        }
        assertExploreSellable(topic);

//        Date now = new Date();
//        if (topic.getEndDate() != null && now.after(topic.getEndDate())) {
//            throw new RuntimeException("活动已结束");
//        }

        return topic;
    }


    /**
     * 验证并获取票种信息
     */
    private OmsTicket validateAndGetTicket(Long ticketId, Integer ownerType, Long ownerId) {
        return validateAndGetTicket(ticketId, ownerType, ownerId, false);
    }

    private OmsTicket validateAndGetTicket(Long ticketId, Integer ownerType, Long ownerId,
                                            boolean inventoryAlreadyHeld) {
        OmsTicket ticket = omsTicketMapper.selectOmsTicketById(ticketId);
        if (ticket == null) {
            throw new RuntimeException("票种不存在");
        }
        System.out.println("ownerId1:" + ownerId);
        System.out.println("ownerId2:" + ticket.getOwnerId());

        System.out.println("getOwnerType1:" + ownerType);
        System.out.println("getOwnerType2:" + ticket.getOwnerType());


        System.out.println("getOwnerType1:" + ticket.getOwnerId().equals(ownerId));
        System.out.println("getOwnerType1:" + ticket.getOwnerType().equals(Long.valueOf(ownerType)));


        if (!ticket.getOwnerId().equals(ownerId) || !ticket.getOwnerType().equals(Long.valueOf(ownerType))) {
            throw new RuntimeException("票种不属于当前活动");
        }

        if (ticket.getDelFlag() == 2) {
            throw new RuntimeException("票种未上架");
        }

//        Date now = new Date();
//        if (ticket.getSaleStartTime() != null && now.before(ticket.getSaleStartTime())) {
//            throw new RuntimeException("票种尚未开始销售");
//        }
//
//        if (ticket.getSaleEndTime() != null && now.after(ticket.getSaleEndTime())) {
//            throw new RuntimeException("票种销售已结束");
//        }

        if (!inventoryAlreadyHeld
                && (ticket.getRemainingInventory() == null || ticket.getRemainingInventory() <= 0)) {
            throw new RuntimeException("票种已售罄");
        }

        // CU-C-32(9-25 裁决:场次开始即停售):活动票以 startTime(场次开始时间)为准,
        // 一到开始即不可建单 —— 原 #1169 拿 endTime 当截止,于是开场后到散场前这一段仍能买。
        // 判据与小程序 utils/ticket-window.js 同尺:startTime 缺失(该列可空)时才退到
        // endTime 这唯一已知边界;两个都缺就不判。候补凭证(inventoryAlreadyHeld=true)不在此列
        // ——它的售止口径是 signupDeadline,持有效名额的人不该被误伤。
        // 只管活动票(ownerType=2):主题票的售卖窗口由 SaleAvailabilityPolicy 决定,不看票的起止。
        if (!inventoryAlreadyHeld && Integer.valueOf(2).equals(ownerType)) {
            if (Boolean.TRUE.equals(ticket.getSelfGuided())) {
                Date now = new Date();
                if (ticket.getEndTime() == null || !now.before(ticket.getEndTime())) {
                    throw new ServiceException("自助玩法开放期已结束，不能再领票");
                }
                if (ticket.getSignupDeadline() != null && !now.before(ticket.getSignupDeadline())) {
                    throw new ServiceException("票种报名已截止");
                }
                return ticket;
            }
            Date sessionStart = ticket.getStartTime() != null ? ticket.getStartTime() : ticket.getEndTime();
            if (sessionStart != null && !sessionStart.after(new Date())) {
                throw new ServiceException("该场次已开始，不能再报名");
            }
        }

        return ticket;
    }

    /**
     * 验证并获取用户信息
     */
    private UmsMember validateAndGetMember(Long userId) {
        UmsMember member = umsMemberMapper.selectUmsMemberById(userId);
        if (member == null) {
            throw new RuntimeException("用户不存在");
        }

        if (member.getStatus() != 1) {
            throw new RuntimeException("用户账号已停用");
        }
        if (member.getDelFlag() == 2) {
            throw new RuntimeException("账号已删除");
        }

        return member;
    }

    /**
     * 计算积分抵扣金额
     */
    private BigDecimal calculatePointDeductAmount(
            Integer paymentType, BigDecimal totalAmount,
            BigDecimal maxPointDeduct, int availablePoints) {

        // 纯微信支付，不使用积分
        if (paymentType == 1) {
            return BigDecimal.ZERO;
        }

        // 计算可抵扣的最大积分金额
        BigDecimal maxDeductByPoints = new BigDecimal(availablePoints).multiply(pointsValuePolicy.rate());
        BigDecimal maxDeduct = maxPointDeduct.min(maxDeductByPoints);

        // 纯积分支付，使用最大可抵扣金额，但不超过总金额
        if (paymentType == 0) {
            return maxDeduct.min(totalAmount);
        }

        // 积分+微信组合支付，使用最大可抵扣金额
        return maxDeduct;
    }

    /**
     * 生成报名编号
     */
    private String generateRegistrationNo(Integer ownerType) {
        String typePrefix = ownerType == 1 ? "T" : "A"; // T-主题，A-活动
        String dateStr = DateUtils.parseDateToStr("yyyyMMdd", new Date());
        String randomStr = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return typePrefix + dateStr + randomStr;
    }

    /**
     * 完成积分支付
     */
    private void completePointPayment(CmsRegistration registration, UmsMember member, int pointUsed) {
        // 扣减积分
        member.setPoint(member.getPoint() - pointUsed);
        umsMemberMapper.updateUmsMember(member);

        // 更新报名状态
        registration.setPaymentStatus(2); // 2-已支付
        registration.setRegistrationStatus(2); // 2-报名成功
        registration.setPaymentTime(new Date());
        //registration.setUpdateBy(SecurityUtils.getUsername());
        registration.setUpdateTime(new Date());

        cmsRegistrationMapper.updateCmsRegistration(registration);

        if (clubWaitlistOrderBridge != null) {
            clubWaitlistOrderBridge.convertedByRegistration(registration.getId());
        }

        // 生成核销二维码
        generateVerificationQrcode(registration.getId());
    }

    /**
     * 根据报名编号查询报名记录
     *
     * @param registrationNo 报名编号
     * @return
     */
    @Override
    public CmsRegistration getByRegistrationNo(String registrationNo) {
        return cmsRegistrationMapper.selectByRegistrationNo(registrationNo);
    }

    @Override
    public CmsRegistration selectByverificationCode(String verificationCode) {
        return cmsRegistrationMapper.selectByverificationCode(verificationCode);
    }


    /**
     * 更新支付状态
     *
     * @param registration 报名记录
     * @return
     */
    @Override
    @Transactional
    public boolean updatePaymentStatus(CmsRegistration registration) {
        registration.setUpdateTime(new Date());
        return cmsRegistrationMapper.updateCmsRegistration(registration) > 0;
    }

    /**
     * 生成核销二维码
     *
     * @param registrationId 报名ID
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String generateVerificationQrcode(Long registrationId) {
        CmsRegistration registration = cmsRegistrationMapper.selectCmsRegistrationById(registrationId);
        if (registration == null) {
            throw new RuntimeException("报名记录不存在");
        }

        registration = requireCurrentUsableRegistration(registration);

        // 如果已经生成过二维码，直接返回
        if (registration.getQrcodeUrl() != null && !registration.getQrcodeUrl().isEmpty()) {
            return registration.getQrcodeUrl();
        }

        // 生成核销码
        String verificationCode = "VER" + System.currentTimeMillis() +
                UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        registration.setVerificationCode(verificationCode);

        // 生成二维码，内容为核销码或报名编号
        //String qrcodeContent = "REG:" + registration.getRegistrationNo() + ":" + verificationCode;
        String qrcodeContent = "";

        if (registration.getOwnerType() == 1) {
            qrcodeContent = "{\"type\":\"topic\",\"code\":\"" + verificationCode + "\"}";
        } else {
            qrcodeContent = "{\"type\":\"activity\",\"code\":\"" + verificationCode + "\"}";
        }
        String qrcodeUrl = qrcodeService.generateQrcode(qrcodeContent, 300, 300);

        if (StringUtils.isBlank(qrcodeUrl)) throw new IllegalStateException("核销二维码生成结果为空");
        registration.setQrcodeUrl(qrcodeUrl);
        //registration.setUpdateBy(SecurityUtils.getUsername());
        registration.setUpdateTime(new Date());

        if (cmsRegistrationMapper.updateCmsRegistration(registration) != 1) {
            throw new IllegalStateException("报名核销码保存失败");
        }
        return qrcodeUrl;
    }

    /**
     * 处理微信支付回调
     *
     * <p>与退款/内容回调同一道 inbox fence:先 register(独立提交,同 hash 换业务即冲突拒收),
     * 再 {@code process} 领取处理权,业务状态推进与 inbox DONE 在同一事务内提交。
     * 因 claim 是 REQUIRES_NEW,本方法不能在外层未提交事务里调用(会读不到/锁死自己刚登记的行);
     * 已在事务内确认支付的调用方用 {@link #applyConfirmedWechatPaymentWithinTransaction}。</p>
     */
    @Override
    public String handleWechatPayCallback(Map<String, String> resultMap) {
        if (resultMap == null) {
            return registrationPaymentCallbackFailure(null, "payload_missing");
        }
        String outTradeNo = resultMap.get("out_trade_no");
        String transactionId = resultMap.get("transaction_id");
        String tradeState = resultMap.get("trade_state");

        if (outTradeNo == null || outTradeNo.trim().isEmpty()
                || tradeState == null || tradeState.trim().isEmpty()) {
            return registrationPaymentCallbackFailure(outTradeNo, "required_field_missing");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return registrationPaymentCallbackFailure(outTradeNo, "callback_inside_transaction");
        }

        // 查询报名记录
        final CmsRegistration registration = getByRegistrationNo(outTradeNo);
        if (registration == null) {
            String reference = outTradeNo.matches("[A-Za-z0-9_-]{1,64}") ? outTradeNo : "INVALID_FORMAT";
            logger.error("Verified payment references missing registration order={}", reference);
            alarmService.alarmThrottled("payment.callback.registration_missing", "支付回调报名不存在",
                    "order=" + reference + " registration=missing result=FAIL");
            return "FAIL";
        }
        // 金额在登记前核对:同一笔回调换了金额不会进 inbox,更不会被当作重复成功。
        if ("SUCCESS".equals(tradeState) && !matchesFrozenWechatAmount(registration, resultMap.get("total"))) {
            return registrationPaymentCallbackFailure(outTradeNo, "amount_mismatch");
        }

        // P1:回调先落 inbox 去重(表 wx_payment_callback_inbox,notify_hash 唯一键)。
        final String notifyId = "PAY:" + (StringUtils.isNotEmpty(transactionId) ? transactionId : outTradeNo)
                + ":" + tradeState;
        try {
            wxPaymentCallbackInboxService.register(notifyId,
                    WxPaymentCallbackInbox.BIZ_TYPE_PAYMENT, registration.getId(), tradeState);
        } catch (IWxPaymentCallbackInboxService.IdentityConflictException conflict) {
            return registrationPaymentCallbackFailure(outTradeNo, "inbox_identity_conflict");
        }
        final String[] outcome = new String[1];
        boolean completed = wxPaymentCallbackInboxService.process(notifyId,
                new IWxPaymentCallbackInboxService.Mutation() {
                    @Override
                    public boolean isCurrent(WxPaymentCallbackInbox inbox) {
                        if (!registration.getId().equals(inbox.getBizId())
                                || !Integer.valueOf(WxPaymentCallbackInbox.BIZ_TYPE_PAYMENT).equals(inbox.getBizType())
                                || !tradeState.equals(inbox.getEventType())) {
                            throw new IWxPaymentCallbackInboxService.IdentityConflictException(
                                    "支付回调 inbox 行与本次业务身份不一致");
                        }
                        // 迟到/冲突的资金事件交给下方状态机判定,不标 STALE 假 ACK。
                        return true;
                    }

                    @Override
                    public boolean apply(WxPaymentCallbackInbox inbox) {
                        outcome[0] = applyWechatPayCallback(registration, resultMap);
                        return "SUCCESS".equalsIgnoreCase(outcome[0]);
                    }
                });
        if (completed) {
            return "SUCCESS";
        }
        // 业务判定失败时 applyWechatPayCallback 已按原因告警,事务回滚、inbox 释放待微信重投。
        return outcome[0] != null && !"SUCCESS".equalsIgnoreCase(outcome[0]) ? outcome[0]
                : registrationPaymentCallbackFailure(outTradeNo, "callback_not_completed");
    }

    /**
     * 已在调用方事务内向微信查单确认 SUCCESS 的补记(如取消场次前把已付 PAYING 单补成已支付)。
     * 不走 inbox:那道 fence 的领取需独立提交;这里由调用方事务的行锁 + 支付 CAS 保证只补一次。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public String applyConfirmedWechatPaymentWithinTransaction(Map<String, String> resultMap) {
        if (resultMap == null || !"SUCCESS".equals(resultMap.get("trade_state"))
                || StringUtils.isBlank(resultMap.get("out_trade_no"))) {
            return registrationPaymentCallbackFailure(resultMap == null ? null : resultMap.get("out_trade_no"),
                    "required_field_missing");
        }
        CmsRegistration registration = getByRegistrationNo(resultMap.get("out_trade_no"));
        if (registration == null) {
            return registrationPaymentCallbackFailure(resultMap.get("out_trade_no"), "order_disappeared");
        }
        if (!matchesFrozenWechatAmount(registration, resultMap.get("total"))) {
            return registrationPaymentCallbackFailure(resultMap.get("out_trade_no"), "amount_mismatch");
        }
        return applyWechatPayCallback(registration, resultMap);
    }

    /** 支付回调的业务状态机;只在 inbox fence 的事务或调用方已开启的事务内执行。 */
    private String applyWechatPayCallback(CmsRegistration registration, Map<String, String> resultMap) {
        String outTradeNo = resultMap.get("out_trade_no");
        String transactionId = resultMap.get("transaction_id");
        String tradeState = resultMap.get("trade_state");

        // 根据交易状态处理不同的业务逻辑
        switch (tradeState) {
            case "SUCCESS": // 支付成功
                if (!matchesFrozenWechatAmount(registration, resultMap.get("total"))) {
                    return registrationPaymentCallbackFailure(outTradeNo, "amount_mismatch");
                }
                String paymentResult = completePaymentSuccess(registration, transactionId);
                return "SUCCESS".equalsIgnoreCase(paymentResult) ? paymentResult
                        : registrationPaymentCallbackFailure(outTradeNo, "payment_state_conflict");

            case "REFUND": // 转入退款
                Date refundedAt = new Date();
                if (cmsRegistrationMapper.claimRegistrationRefundedByGateway(
                        registration.getId(), "system", refundedAt) == 0) {
                    CmsRegistration current = cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(
                            registration.getId());
                    if (current != null && Integer.valueOf(4).equals(current.getPaymentStatus())
                            && Integer.valueOf(3).equals(current.getRegistrationStatus())) {
                        returnPointsToUser(current);
                        return "SUCCESS";
                    }
                    return registrationPaymentCallbackFailure(outTradeNo, "refund_state_conflict");
                }
                registration = cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(registration.getId());
                if (registration == null) {
                    throw new IllegalStateException("退款报名在状态迁移后不存在");
                }

                // [赏金] clawback 必须在 registration_status=3 落库**之后**调:countStillValidAmong 读的就是
                // 这一列,早调会数到这条还没置 3 的记录 ⇒ stillValid 偏高 ⇒ 该追的追不回。
                //
                // ⚠️ 本分支实际很难走到,但**不删**:这里是「查单发现该单已退」的 trade_state=REFUND 分支,
                //    要有人主动去查那笔老订单才触发。运营在微信商户后台手工退款走的是另一条
                //    (/refund-callback → handleRefundNotify),那条按 "RF"+regId 查 RefundApplication、
                //    手工退款没有那行 ⇒ 直接 ACK,且它压根不写 registration_status。
                //    真正跑得到的 clawback 入口是 RefundServiceImpl.markRegistrationRefunded(后台审核路)。
                merchantReferralBountyService.onRefund(registration.getVerifierId());
                firstReviewPointsClawback.onRegistrationRefunded(registration);   // FX-10

                // 退款时返还积分
                returnPointsToUser(registration);
                break;

            case "NOTPAY": // 未支付
            case "CLOSED": // 已关闭
            case "REVOKED": // 已撤销
            case "PAYERROR": // 支付失败
                // Gateway notifications carry the exact provider attempt. Hold the same business lock
                // as alias creation until this inbox mutation commits; old CLOSED cannot close a new attempt.
                if ("CLOSED".equals(tradeState)
                        && (resultMap.get("wx_out_trade_no") == null || paymentAttemptGateway == null
                            || !paymentAttemptGateway.getObject()
                            .lockPaymentAttemptForUpdate(outTradeNo, resultMap.get("wx_out_trade_no")))) {
                    return registrationPaymentCallbackFailure(outTradeNo, "payment_attempt_conflict");
                }
                Date failedAt = new Date();
                if (cmsRegistrationMapper.claimPaymentFailure(registration.getId(), failedAt) == 0) {
                    CmsRegistration current = cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(
                            registration.getId());
                    if (current == null) {
                        return registrationPaymentCallbackFailure(outTradeNo, "order_disappeared");
                    }
                    return Integer.valueOf(3).equals(current.getPaymentStatus())
                            && Integer.valueOf(1).equals(current.getRegistrationStatus())
                            ? "SUCCESS" : registrationPaymentCallbackFailure(outTradeNo, "failure_state_conflict");
                }
                CmsRegistration failed = cmsRegistrationMapper.selectCmsRegistrationById(registration.getId());
                if (failed == null) {
                    throw new IllegalStateException("失败态报名在状态迁移后不存在");
                }
                returnPointsToUser(failed);
                break;

            case "USERPAYING": // 用户支付中
                // 支付中状态，不需要处理，等待后续回调
                break;

            default:
                // 未知状态，记录日志
                logger.warn("未知的支付状态: {}, 订单号: {}", tradeState, outTradeNo);
                break;
        }

        return "SUCCESS";
    }

    private String registrationPaymentCallbackFailure(String outTradeNo, String reason) {
        String reference = outTradeNo != null && outTradeNo.matches("[A-Za-z0-9_-]{1,64}")
                ? outTradeNo : "INVALID_FORMAT";
        // AlarmService 只记标题与长度;单号/原因必须自己落一行 WARN,告警未配置时事后才查得到。
        logger.warn("Registration payment callback returned FAIL order={} reason={}", reference, reason);
        alarmService.alarmThrottled("payment.callback.registration", "支付回调报名处理失败",
                "order=" + reference + " reason=" + reason + " result=FAIL");
        return "FAIL";
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String completeZeroAmountPayment(String registrationNo) {
        CmsRegistration registration = getByRegistrationNo(registrationNo);
        if (registration == null || registration.getPayableAmount() == null
                || registration.getPayableAmount().compareTo(BigDecimal.ZERO) != 0
                || (registration.getWechatPaymentAmount() != null
                && registration.getWechatPaymentAmount().compareTo(BigDecimal.ZERO) != 0)) {
            return "FAIL";
        }
        return completePaymentSuccess(registration, "");
    }

    private boolean matchesFrozenWechatAmount(CmsRegistration registration, String totalCents) {
        if (registration == null || registration.getWechatPaymentAmount() == null
                || registration.getWechatPaymentAmount().compareTo(BigDecimal.ZERO) <= 0
                || StringUtils.isBlank(totalCents)) {
            return false;
        }
        try {
            long expected = registration.getWechatPaymentAmount().setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2).longValueExact();
            return expected == Long.parseLong(totalCents.trim());
        } catch (ArithmeticException | NumberFormatException invalidAmount) {
            return false;
        }
    }

    /** 新支付和历史半成品重放共用同一依赖项对账；各下游用自己的业务键幂等。 */
    private String completePaymentSuccess(CmsRegistration registration, String transactionId) {
        Date paidAt = new Date();
        boolean paymentTransitioned = cmsRegistrationMapper.claimPaymentSuccess(
                registration.getId(), transactionId, paidAt) == 1;
        if (!paymentTransitioned) {
            CmsRegistration current = cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(registration.getId());
            boolean historicalHalfState = current != null && Integer.valueOf(2).equals(current.getPaymentStatus())
                    && Integer.valueOf(1).equals(current.getRegistrationStatus());
            current = repairHistoricalPaidHalfState(current, transactionId,
                    registration.getWechatPaymentAmount(), paidAt);
            if (!isSameCompletedPayment(current, transactionId)) {
                return "FAIL";
            }
            registration = current;
            paymentTransitioned = historicalHalfState;
        } else {
            registration = cmsRegistrationMapper.selectCmsRegistrationById(registration.getId());
            if (registration == null) {
                throw new IllegalStateException("支付报名在状态迁移后不存在");
            }
        }

        List<Long> paidRegistrationIds = new ArrayList<>();
        paidRegistrationIds.add(registration.getId());
        registration = freezeTicketSourceOnPayment(registration);
        settlementEntryService.recordOnPayment(registration);

        if (StringUtils.isNotEmpty(registration.getParentOrderNo())) {
            List<CmsRegistration> siblings = cmsRegistrationMapper.selectByParentOrderNo(
                    registration.getParentOrderNo());
            for (CmsRegistration sibling : siblings) {
                if (sibling.getId().equals(registration.getId())) {
                    continue;
                }
                if (cmsRegistrationMapper.claimPaymentSuccess(sibling.getId(), transactionId, paidAt) == 0) {
                    sibling = cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(sibling.getId());
                    sibling = repairHistoricalPaidHalfState(sibling, transactionId,
                            sibling == null ? null : sibling.getWechatPaymentAmount(), paidAt);
                    if (!isSameCompletedPayment(sibling, transactionId)) {
                        throw new IllegalStateException("支付从单状态冲突,registrationId=" + sibling.getId());
                    }
                } else {
                    sibling = cmsRegistrationMapper.selectCmsRegistrationById(sibling.getId());
                }
                sibling = freezeTicketSourceOnPayment(sibling);
                settlementEntryService.recordOnPayment(sibling);
                paidRegistrationIds.add(sibling.getId());
            }
        }

        if (clubWaitlistOrderBridge != null) {
            for (Long paidRegistrationId : paidRegistrationIds) {
                clubWaitlistOrderBridge.convertedByRegistration(paidRegistrationId);
            }
        }

        UmsMember member = umsMemberMapper.selectUmsMemberById(registration.getMemberId());
        awardPointsOnce(registration.getMemberId(), pointsSettingValue(2L),
                "首次活动报名", 2L, 0L);
        awardPointsOnce(registration.getMemberId(), pointsSettingValue(3L),
                "参与活动", 3L, registration.getId());
        Long inviterId = parsePositiveLong(member == null ? null : member.getInviteUserId());
        if (inviterId != null && !inviterId.equals(registration.getMemberId())) {
            awardPointsOnce(inviterId, pointsSettingValue(5L),
                    "邀请1位完成首次购买的用户", 5L, registration.getMemberId());
            awardPointsOnce(inviterId, pointsSettingValue(6L),
                    "被邀请用户报名任意活动", 6L, registration.getId());
        }
        ensureRegistrationScans(registration);
        if (paymentTransitioned) {
            registerPaymentAfterCommit(paidRegistrationIds, registration.getMemberId(),
                    registration.getActivityTitle());
        }
        return "SUCCESS";
    }

    private CmsRegistration freezeTicketSourceOnPayment(CmsRegistration registration) {
        if (registration == null || registration.getPurchaseKind() == null
                || registration.getPurchaseKind() != PurchaseKind.EXPLORE_PASS) {
            return registration;
        }
        Date paymentTime = registration.getPaymentTime();
        if (paymentTime == null) {
            throw new IllegalStateException("TICKET_SOURCE_FREEZE_UNKNOWN:paymentTime");
        }
        cmsRegistrationMapper.freezeTicketSourceOnPayment(registration.getId(), paymentTime);
        CmsRegistration frozenFact = cmsRegistrationMapper.selectTicketSourceFactById(registration.getId());
        if (frozenFact == null || frozenFact.getSourceFrozenAt() == null) {
            throw new IllegalStateException("TICKET_SOURCE_FREEZE_UNKNOWN:registrationId="
                    + registration.getId());
        }
        registration.setSourceClubId(frozenFact.getSourceClubId());
        registration.setSourceFrozenAt(frozenFact.getSourceFrozenAt());
        registration.setSourceChannelCode(frozenFact.getSourceChannelCode());
        return registration;
    }

    /**
     * 票源俱乐部归因的信任边界 —— 恒返回 null，客户端自报永远不会变成资金事实。
     *
     * <p><b>为什么必须 fail-closed</b>：`sourceClubId` 与 `clubCode` 都只是分享深链上的 query 参数
     * （{@code chengyinhub-xcx/utils/ticket-source.js}），任何登录玩家都能自己填。旧实现只校验
     * {@code > 0} 就写进 {@code cms_registration.source_club_id}，支付冻结
     * （{@code CmsRegistrationMapper.freezeTicketSourceOnPayment}）再拿它跟执行俱乐部比一次相等 ——
     * <b>被伪造的正是那个相等条件</b>：买家把公开可得的执行俱乐部 id 填进 body，
     * 平台自然流量就变成该俱乐部 {@code G_earned × 10%} 的 TICKET_SOURCE 应付，账面还完全自洽。
     * 校验 {@code > 0} 或「等于执行俱乐部」都挡不住它，前端校验更挡不住 —— 攻击面就在前端之外。
     *
     * <p>⚠️ <b>2026-08-25 起 TICKET_SOURCE 分量已整条退役</b>（{@code club_payable} 的枚举里没有它了），
     * 上面那条资损路径的<b>下游</b>因此不复存在。<b>但这道边界照旧守着，且必须守</b>：
     * 它挡的是「客户端自报值写进服务端归因列」这件事本身，而不是某一个下游。
     * 归因列仍在（{@code source_club_id} 落 FINAL 逐票归因），将来若恢复引流归因，
     * 恢复的是下游算法，不是这道边界 —— 拆了它，新下游一上线就是同一个洞。
     *
     * <p><b>为什么是恒 null 而不是换一条服务端判据</b>：全仓没有任何「这个买家<b>这一单</b>真的被某俱乐部
     * 引流过」的服务端事实。逐个排查过：{@code product_role} 的 DISTRIBUTION 槽与
     * {@code club_edition_compensation_terms} 记的都是<b>执行</b>俱乐部（它本来就在拿 F+Q，且 id 公开）；
     * {@code merchant_referral_bounty} 是商家拓展赏金，与购票渠道无关；
     * {@code cms_topic.roam_referral_enabled} 是逐期开关不是逐单事实。
     * 唯一真正「服务端签发 + 落库 + 一次性绑定（{@code MemberInvitationMapper.bindInviterIfAbsent}
     * 只在 {@code invite_user_id} 为空时写）」的是<b>会员邀请人</b>，但它是 member→member、
     * 账号终身一次，既不指向俱乐部，也不绑定本期售票窗，拿它归因票源同样是另造一条规则。
     * 拿执行俱乐部当引流证据更是把伪造结果直接写成规则 —— 那是臆造奖励规则，不是修复。
     *
     * <p><b>产品能力降级（须业务决策）</b>：在服务端签发的引流凭据落地前，票源归因恒为平台，
     * 俱乐部拿不到 10% 票源奖励。恢复该能力需要产品先定义凭据的四段语义
     * （签发 → 持久化 → 消费 → 防重放），届时把本方法换成读该凭据即可，
     * 归因列、冻结闸、FINAL 逐票金额归因与 10% 计算链路都原样保留。
     *
     * <p>自报值不落库，但要留声：审计码仍写进 {@code source_channel_code}，配合这条日志
     * 可以回答「谁在自报、报的是哪一家」。级别取 INFO 不取 WARN —— 现在每一次分享链路下单都会带自报值，
     * 丢弃是常态而不是异常，用 WARN 会把真告警淹掉。
     */
    private void auditDiscardedTicketSourceClaim(Long claimedClubId, Long memberId, Long ownerId) {
        if (claimedClubId != null) {
            logger.info("[R9-P1] 丢弃客户端自报票源俱乐部 claimedClubId={} memberId={} ownerId={} "
                    + "—— 无服务端引流凭据可证，归因保持平台", claimedClubId, memberId, ownerId);
        }
    }

    private String normalizeTicketSourceChannel(String sourceChannelCode) {
        if (sourceChannelCode == null || sourceChannelCode.trim().isEmpty()) {
            return null;
        }
        String normalized = sourceChannelCode.trim();
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("TICKET_SOURCE_INVALID:sourceChannelCode");
        }
        return normalized;
    }

    private CmsRegistration repairHistoricalPaidHalfState(CmsRegistration current, String transactionId,
                                                          BigDecimal frozenWechatAmount, Date paidAt) {
        if (current == null || StringUtils.isBlank(transactionId) || frozenWechatAmount == null
                || !Integer.valueOf(2).equals(current.getPaymentStatus())
                || !Integer.valueOf(1).equals(current.getRegistrationStatus())) {
            return current;
        }
        if (cmsRegistrationMapper.repairHistoricalPaidHalfState(current.getId(), transactionId,
                frozenWechatAmount, paidAt) != 1) {
            return current;
        }
        return cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(current.getId());
    }

    private void ensureRegistrationScans(CmsRegistration registration) {
        Long topicId = authoritativeTopicId(registration);
        if (topicId != null) {
            cmsRegistrationScanMapper.insertMissingScansForTopic(registration.getId(), topicId, new Date());
        }
    }

    private Long authoritativeTopicId(CmsRegistration registration) {
        if (registration == null || registration.getOwnerType() == null) {
            return null;
        }
        if (Long.valueOf(1L).equals(registration.getOwnerType())) {
            return registration.getOwnerId();
        }
        if (!Long.valueOf(2L).equals(registration.getOwnerType()) || registration.getOwnerId() == null) {
            return null;
        }
        CmsActivity activity = cmsActivityMapper.selectCmsActivityById(registration.getOwnerId());
        return activity == null ? null : activity.getTopicId();
    }

    private boolean isSameCompletedPayment(CmsRegistration registration, String transactionId) {
        if (registration == null || !Integer.valueOf(2).equals(registration.getPaymentStatus())
                || !Integer.valueOf(2).equals(registration.getRegistrationStatus())) {
            return false;
        }
        if (transactionId == null || transactionId.trim().isEmpty()) {
            return registration.getTransactionId() == null || registration.getTransactionId().trim().isEmpty();
        }
        return transactionId.equals(registration.getTransactionId());
    }

    private int pointsSettingValue(Long settingId) {
        CmsPointsSetting setting = cmsPointsSettingService.selectCmsPointsSettingById(settingId);
        return setting == null || setting.getValue() == null ? 0 : setting.getValue().intValue();
    }

    /** 先用奖励业务键抢占流水，再更新余额；任一步失败由支付回调外层事务整体回滚。 */
    private boolean awardPointsOnce(Long memberId, int points, String reason, Long eventType, Long eventId) {
        if (memberId == null || points <= 0 || eventType == null || eventId == null) {
            return false;
        }
        if (umsMemberMapper.lockMemberById(memberId) == null) {
            return false;
        }
        UmsMember member = umsMemberMapper.selectUmsMemberById(memberId);
        if (member == null) {
            return false;
        }
        long current = member.getPoint() == null ? 0L : member.getPoint();
        UmsMemberPointsDetail detail = new UmsMemberPointsDetail();
        detail.setMemberId(memberId);
        detail.setCurrentPoints(current);
        detail.setAfterPoints(current + points);
        detail.setChangePoints(Long.valueOf(points));
        detail.setChangeReason(reason);
        detail.setEventType(eventType);
        detail.setEventId(eventId);
        detail.setRewardBusinessKey("registration:" + eventType + ":" + eventId);
        detail.setChangeType(1L);
        detail.setDelFlag(0);
        detail.setCreateTime(new Date());
        try {
            if (umsMemberPointsDetailMapper.insertUmsMemberPointsDetailWithBusinessKey(detail) != 1) {
                throw new IllegalStateException("奖励积分流水写入失败 memberId=" + memberId);
            }
        } catch (DuplicateKeyException duplicate) {
            UmsMemberPointsDetail existing = umsMemberPointsDetailMapper.selectByRewardBusinessKey(
                    memberId, detail.getRewardBusinessKey());
            if (isSameReward(existing, detail)) {
                return false;
            }
            throw duplicate;
        }
        if (umsMemberMapper.addPoints(memberId, points) != 1) {
            throw new IllegalStateException("奖励积分余额更新失败 memberId=" + memberId);
        }
        return true;
    }

    private boolean isSameReward(UmsMemberPointsDetail existing, UmsMemberPointsDetail expected) {
        return existing != null
                && expected.getEventType().equals(existing.getEventType())
                && expected.getEventId().equals(existing.getEventId())
                && expected.getChangePoints().equals(existing.getChangePoints())
                && expected.getChangeReason().equals(existing.getChangeReason())
                && Long.valueOf(1L).equals(existing.getChangeType());
    }

    private Long parsePositiveLong(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            logger.warn("支付奖励忽略非法邀请人标识");
            return null;
        }
    }

    /**
     * 二维码与订阅消息不参与数据库事务；提交成功后再执行。二维码失败由 paid-missing-qrcode
     * 补偿任务重试，不能让外部/文件副作用先于资金事实提交。
     */
    private void registerPaymentAfterCommit(List<Long> registrationIds, Long memberId, String activityTitle) {
        Runnable effects = () -> {
            for (Long registrationId : registrationIds) {
                committedBusinessNotice.dispatchInNewTransaction("registration-qrcode", registrationId, () -> {
                    String url = generateVerificationQrcode(registrationId);
                    return url != null && !url.isEmpty();
                });
            }
            try {
                if (imService != null && !registrationIds.isEmpty()) {
                    CmsRegistration paid = cmsRegistrationMapper.selectCmsRegistrationById(registrationIds.get(0));
                    CmsTopic topic = paid == null ? null : resolveSellerTopic(paid);
                    committedBusinessNotice.dispatchInNewTransaction("registration-signup", paid == null ? null : paid.getId(), () -> {
                        Long messageId = imService.sendSystem(memberId, ImMessage.MSG_CARD, "报名成功",
                                RegistrationCheckoutServiceImpl.signupNoticeExtra(topic == null ? null : topic.getId()));
                        return messageId != null && messageId > 0L;
                    });
                }
            } catch (Exception e) {
                logger.warn("[支付回调] 报名成功站内信发送失败 memberId={}", memberId);
            }
            try {
                if (notifyService != null) {
                    notifyService.notifySignupSuccess(memberId, activityTitle);
                }
            } catch (Exception e) {
                logger.warn("[支付回调] 报名成功订阅消息发送失败 memberId={}", memberId);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    effects.run();
                }
            });
        } else {
            effects.run();
        }
    }

    /** 报名积分列为 Long，现有余额 Mapper 入参为 Integer；禁止在任何恢复出口静默截断。 */
    private int exactReturnedPoints(Long used) {
        if (used == null || used == 0) return 0;
        try {
            int points = Math.toIntExact(used);
            if (points < 0) throw new ArithmeticException("negative points");
            return points;
        } catch (ArithmeticException invalidPoints) {
            throw new IllegalStateException("报名积分快照超出支持范围，请人工核对", invalidPoints);
        }
    }

    /** 返还积分给用户。 */
    private void returnPointsToUser(CmsRegistration registration) {
        if (registration == null) return;
        final int points = exactReturnedPoints(registration.getPointUsed());
        if (points == 0) return;
        Date now = new Date();
        if (cmsRegistrationMapper.claimPointsReturn(registration.getId(), "system", now) == 0) {
            CmsRegistration current = cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(registration.getId());
            if (current != null && Integer.valueOf(1).equals(current.getPointsReturned())) {
                return;
            }
            throw new IllegalStateException("报名积分返还资格状态冲突");
        }
        if (umsMemberMapper.addPoints(registration.getMemberId(), points) != 1) {
            throw new IllegalStateException("报名积分返还失败");
        }
        logger.info("返还积分成功，用户ID: {}, 返还积分: {}",
                registration.getMemberId(), registration.getPointUsed());
    }


    @Override
    @Transactional
    public boolean cancelRegistration(Long registrationId, Long memberId, String userName) {
        CmsRegistration registration = cmsRegistrationMapper.selectCmsRegistrationById(registrationId);
        if (registration == null) {
            throw new RuntimeException("报名记录不存在");
        }

        // 验证是否是本人操作
        if (!registration.getMemberId().equals(memberId)) {
            throw new RuntimeException("无权取消他人报名");
        }

        // 未支付取消入口不办理已支付订单，后者须在订单详情按实际取消规则处理。
        if (Integer.valueOf(1).equals(registration.getVerificationStatus())) {
            throw new RuntimeException("已核销的报名不能取消");
        }
        if (Integer.valueOf(2).equals(registration.getPaymentStatus())) {
            throw new RuntimeException("已完成支付，请从订单详情申请取消报名，是否可取消以订单规则为准");
        }

        boolean shouldReturnPoints = registration.getPointUsed() != null && registration.getPointUsed() != 0
                && (registration.getPointsReturned() == null || registration.getPointsReturned() == 0);
        int points = shouldReturnPoints ? exactReturnedPoints(registration.getPointUsed()) : 0;
        if (cmsRegistrationMapper.cancelUnpaidIfActive(registrationId, memberId, userName) == 0) {
            CmsRegistration current = cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(registrationId);
            if (current != null && memberId.equals(current.getMemberId())
                    && Integer.valueOf(3).equals(current.getRegistrationStatus())) {
                return true;
            }
            throw new RuntimeException("该报名状态已变化,无法取消");
        }
        if (registration.getTicketId() != null) {
            long qty = registration.getOrderNum() != null && registration.getOrderNum() > 0
                    ? registration.getOrderNum() : 1L;
            restoreTicketInventory(registration.getTicketId(), qty);
        }
        if (shouldReturnPoints
                && umsMemberMapper.addPoints(memberId, points) != 1) {
            throw new IllegalStateException("取消报名积分返还失败");
        }
        if (Integer.valueOf(PurchaseKind.EXPLORE_PASS).equals(registration.getPurchaseKind())) {
            perkLedgerService.releaseOnRefund(registrationId, EntitlementInvalidReason.ORGANIZER_CANCEL);
        }
        return true;
    }

    @Override
    public List<Long> listExpiredUnpaidIds() {
        return cmsRegistrationMapper.selectExpiredUnpaidIds(new Date());
    }

    @Override
    public Long getExpiredUnpaidScanUpperBound(Date now) {
        return cmsRegistrationMapper.selectExpiredUnpaidMaxId(now);
    }

    @Override
    public List<Long> listExpiredUnpaidIdsAfter(Date now, long afterId, long maxId, int limit) {
        return cmsRegistrationMapper.selectExpiredUnpaidIdsAfter(now, afterId, maxId,
                Math.max(1, Math.min(200, limit)));
    }

    @Override
    public List<Long> listExpiredPayingIds(long afterId) {
        return cmsRegistrationMapper.selectExpiredPayingIds(new Date(), afterId);
    }

    @Override
    public List<Long> listPaidMissingQrcodeIds() {
        return cmsRegistrationMapper.selectPaidMissingQrcodeIds();
    }

    @Override
    public Long getPaidMissingQrcodeScanUpperBound() {
        return cmsRegistrationMapper.selectPaidMissingQrcodeMaxId();
    }

    @Override
    public List<Long> listPaidMissingQrcodeIdsAfter(long afterId, long maxId, int limit) {
        return cmsRegistrationMapper.selectPaidMissingQrcodeIdsAfter(afterId, maxId,
                Math.max(1, Math.min(100, limit)));
    }

    /**
     * RUN-01 安全缓冲(天):活动结束满 7 天才自动收口。留给末场散场、支付回调迟到与售后窗口的余量,
     * 口径对齐列表侧 HIDE_AFTER_END_DAYS 的保守做法 —— 宁可多悬一天,不可错杀一张在途票。
     */
    private static final int AUTO_VERIFY_END_BUFFER_DAYS = 7;

    /** 自动收口写进 update_by 的来源标识:库里必须能区分「任务收口」与「商家核销」。 */
    private static final String AUTO_VERIFY_UPDATE_BY = "registration-auto-verify-task";

    @Override
    public List<Long> listExpiredUnverifiedIds() {
        return cmsRegistrationMapper.selectExpiredUnverifiedIds(new Date(), AUTO_VERIFY_END_BUFFER_DAYS);
    }

    @Override
    public List<Long> listExpiredUnverifiedIdsAfter(long afterId) {
        return cmsRegistrationMapper.selectExpiredUnverifiedIdsAfter(new Date(), AUTO_VERIFY_END_BUFFER_DAYS, afterId);
    }

    /**
     * RUN-01 到期自动核销:纯状态收口(条件置位,0 行=竞态已变则跳过),不发奖励、不写台账、不做结算。
     * 全部判据在 {@code autoVerifyExpiredSession} 的当前读里,这里不做任何先读后判。
     */
    @Override
    @Transactional
    public boolean autoVerifyExpiredOne(Long registrationId) {
        if (cmsRegistrationMapper.autoVerifyExpiredSession(registrationId, AUTO_VERIFY_UPDATE_BY,
                AUTO_VERIFY_END_BUFFER_DAYS) == 0) {
            return false;
        }
        logger.info("到期未核销场次票已自动收口 registrationId={}, bufferDays={}",
                registrationId, AUTO_VERIFY_END_BUFFER_DAYS);
        return true;
    }

    /**
     * P0-10 弃单关单:条件关单成功(仍待支付)才回补库存/退积分;
     * 经代理调用 per-单独立事务;竞态下(刚支付/已取消)closeExpired 0 行直接跳过。
     */
    @Override
    @Transactional
    public boolean closeExpiredOne(Long registrationId) {
        if (cmsRegistrationMapper.closeExpired(registrationId, "order-close-task", 4) == 0) {
            return false;
        }
        CmsRegistration r = cmsRegistrationMapper.selectCmsRegistrationById(registrationId);
        if (r == null) {
            return true;
        }
        releaseHeldResources(r, EntitlementInvalidReason.ORGANIZER_CANCEL, "order-close-task");
        logger.info("过期未支付订单已关单 registrationId={}, memberId={}", registrationId, r.getMemberId());
        return true;
    }

    /**
     * PAYING 单确认未支付后的收口(对账任务与主办方取消活动共用,见接口 javadoc)。
     * 条件关单成功(仍处支付中)才回补库存/退积分/放权益;竞态下(刚支付成功)0 行直接跳过。
     * 对账任务经代理调用 = per-单独立事务;取消活动在外层事务内调用 = 一起回滚。
     */
    @Override
    @Transactional
    public boolean settlePayingOne(Long registrationId, String releaseReason, String updateBy, int finalStatus) {
        if (cmsRegistrationMapper.closeExpiredPaying(registrationId, updateBy, finalStatus) == 0) {
            return false;
        }
        CmsRegistration r = cmsRegistrationMapper.selectCmsRegistrationById(registrationId);
        if (r == null) {
            return true;
        }
        releaseHeldResources(r, releaseReason, updateBy);
        // P1-PT-7:多票父单整单关 —— 从单停 0 态且被老关单任务排除,主单确认未支付时一起关、一起回补。
        // 从单终态跟主单一致(清单 3):同一次关单动作,真实原因只有一个。
        if (StringUtils.isNotEmpty(r.getParentOrderNo())) {
            for (CmsRegistration sibling : cmsRegistrationMapper.selectByParentOrderNo(r.getParentOrderNo())) {
                if (!sibling.getId().equals(registrationId)
                        && cmsRegistrationMapper.closeExpired(sibling.getId(), updateBy, finalStatus) == 1) {
                    releaseHeldResources(sibling, releaseReason, updateBy);
                }
            }
        }
        logger.info("支付中订单经查单确认未支付后关单 registrationId={}, memberId={}, updateBy={}",
                registrationId, r.getMemberId(), updateBy);
        return true;
    }

    /**
     * 关单路径共用的持有资源释放:回补库存(原子)、退还预扣积分(标记防重)、释放 ③ 权益容量。
     * 必须与条件关单更新在同一事务内 —— 任一步失败整体回滚,订单不关,等下轮重试。
     */
    private void releaseHeldResources(CmsRegistration r, String releaseReason, String updateBy) {
        int points = exactReturnedPoints(r.getPointUsed());
        // 回补库存(原子,与取消口径一致)
        if (r.getTicketId() != null) {
            long qty = r.getOrderNum() != null && r.getOrderNum() > 0 ? r.getOrderNum() : 1L;
            restoreTicketInventory(r.getTicketId(), qty);
        }
        // 下单时预扣的积分原数退还。★先 CAS 抢 points_returned 标记、抢到才加积分:
        // 「先读 points_returned 再判」在并发下两个事务能同时读到 0 ⇒ 双份返还。
        if (points > 0
                && cmsRegistrationMapper.claimPointsReturn(r.getId(), updateBy, new Date()) == 1) {
            if (umsMemberMapper.addPoints(r.getMemberId(), points) != 1) {
                throw new IllegalStateException("过期订单积分返还失败");
            }
        }
        // [P0-15] 释放 ③ 权益容量 —— 与 cancelRegistration 同理:预占在建单,弃单不放 = 永久占坑。
        // 弃单关单是**定时任务**路径,静默失效在这里最危险:没人会发现容量在慢慢漏光。
        perkLedgerService.releaseOnRefund(r.getId(), releaseReason);
    }

    /**
     * 回补必须不超过总库存。0 行不是“无事发生”，而是库存/订单状态已不一致；当前事务必须回滚，
     * 让调用方重试或进入人工核对，不能把报名标成已取消却没有真实回补。
     */
    private void restoreTicketInventory(Long ticketId, long qty) {
        if (omsTicketMapper.restoreInventory(ticketId, qty) != 1) {
            throw new ServiceException("票务库存回补失败，请刷新后重试", HttpStatus.CONFLICT);
        }
    }

    /**
     * P0-21 核销收口。三步同事务:条件置位 → 入账 → 台账幂等补写。
     * 任一步抛异常整体回滚,商家重扫即可重来 —— 这正是「条件置位」不能裸放在无事务的 controller
     * 里的原因:置位一旦先提交,下游一失败就再也补不回来了。
     *
     * <p>★★ <b>[资损·根因闸] ③ 探索票绝不能走这里</b>,见方法体第一行。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean verifyAndSettle(CmsRegistration registration, Long verifierId, Long payeeId,
                                   Integer validationMethod) {
        return verifyAndSettle(registration, verifierId, payeeId, validationMethod,
                verifierId, "OWNER");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean verifyAndSettle(CmsRegistration registration, Long verifierId, Long payeeId,
                                   Integer validationMethod, Long actorMemberId,
                                   String actorRoleCode) {
        return verifyAndSettleInternal(registration, verifierId, payeeId, validationMethod,
                null, null, actorMemberId, actorRoleCode, "REGISTRATION_REDEEM");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean verifyAndSettleForOwnStation(CmsRegistration registration, Long verifierId, Long payeeId,
                                                CmsRegistrationMerchant station) {
        return verifyAndSettleForOwnStation(registration, verifierId, payeeId, station,
                verifierId, "OWNER");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean verifyAndSettleForOwnStation(CmsRegistration registration, Long verifierId, Long payeeId,
                                                CmsRegistrationMerchant station, Long actorMemberId,
                                                String actorRoleCode) {
        // 唯一中标:station 由服务端按 memberId + topicId + audit_status=1 + del_flag=0 查出,
        // 客户端指定不了它,所以不再回读复核 —— 行为与原 verifyAndSettle 逐字一致,
        // 唯一的差别是把这一站的身份透传给流水。
        return verifyAndSettleInternal(registration, verifierId, payeeId,
                station == null ? null : station.getValidationMethod(), null, station,
                actorMemberId, actorRoleCode, "REGISTRATION_STATION_REDEEM");
    }

    /**
     * 多据点选择后的事务收口。所选行是客户端输入,归属、主题、中标态和验证方式都必须在
     * CAS/结算/台账所在的同一事务内回读复核,不能信任 controller 之前返回过的候选快照。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean verifyAndSettleForStation(CmsRegistration registration, Long verifierId,
                                             Long registrationMerchantId) {
        return verifyAndSettleForStation(registration, verifierId, registrationMerchantId,
                verifierId, "OWNER");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean verifyAndSettleForStation(CmsRegistration registration, Long verifierId,
                                             Long registrationMerchantId, Long actorMemberId,
                                             String actorRoleCode) {
        if (registration == null || registration.getId() == null
                || verifierId == null || registrationMerchantId == null) {
            throw new IllegalStateException("所选据点不属于当前商家或路线");
        }
        CmsRegistration current = cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(registration.getId());
        if (current == null || !Long.valueOf(1L).equals(current.getOwnerType())
                || current.getOwnerId() == null) {
            throw new IllegalStateException("订单数据异常,无法核销");
        }
        if (!Integer.valueOf(2).equals(current.getPaymentStatus())) {
            throw new IllegalStateException("订单未完成支付或已退款,无法核销");
        }
        CmsRegistrationMerchant station =
                cmsRegistrationMerchantMapper.selectCmsRegistrationMerchantByIdForUpdate(registrationMerchantId);
        if (station == null
                || !verifierId.equals(station.getMemberId())
                || !current.getOwnerId().equals(station.getTopicId())
                || !Long.valueOf(1L).equals(station.getAuditStatus())
                || !Integer.valueOf(0).equals(station.getDelFlag())) {
            throw new IllegalStateException("所选据点不属于当前商家或路线");
        }
        // ⚠️ 不因 validation_method 为空就拒绝核销。
        // cms_registration_merchant.validation_method 可空,而生产代码里**没有任何路径**给它赋值
        // (只有模板侧 TemplatePublishServiceImpl 写自己的那个同名字段)—— 也就是说线上存量大概率为 NULL。
        // 在这里 fail-closed 等于凭一个从来没人填的元数据字段,把真实商家的真实核销挡在门外;
        // 那是本次改动**新造**的故障模式,比「流水少一个方法字段」严重得多。
        // 本次要解决的是「流水答不出是哪一站」,归属由 station 落库,与该字段是否为空无关。
        return verifyAndSettleInternal(current, verifierId, verifierId,
                station.getValidationMethod(), null, station, actorMemberId, actorRoleCode,
                "REGISTRATION_STATION_REDEEM");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean verifyAndSettleForGroupActivity(CmsRegistration registration, Long verifierId,
                                                   Long activityId) {
        return verifyAndSettleForGroupActivity(registration, verifierId, activityId,
                verifierId, "CLUB_OWNER");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean verifyAndSettleForGroupActivity(CmsRegistration registration, Long verifierId,
                                                   Long activityId, Long actorMemberId,
                                                   String actorRoleCode) {
        if (activityId == null || activityId <= 0) {
            throw new IllegalArgumentException("带队场次不能为空");
        }
        return verifyAndSettleInternal(registration, verifierId, null, null, activityId, null,
                actorMemberId, actorRoleCode, "GROUP_MEMBER_REDEEM");
    }

    private boolean verifyAndSettleInternal(CmsRegistration registration, Long verifierId, Long payeeId,
                                            Integer validationMethod, Long activityId,
                                            CmsRegistrationMerchant station, Long actorMemberId,
                                            String actorRoleCode, String action) {
        // ★★ [资损·根因闸] ③ 走按章核销(redeemExploreChapterAndRecord),**绝不能**走整单收口。
        //
        // 为什么闸在这里、而不是在每个调用方各判一次:
        // 这个方法是**共用收口**,它的语义是「整单票款按 settlement_rule 结算给单一 payee」。
        // 而 ③ 一张票跨 N 家商家逐章核销 ⇒ 整单给谁都是错的 ⇒ 谁调它谁就制造资损。
        // 本轮**同一个病犯了三次**,每次都是「我以为我普查完了调用方」:
        //   ① ③ 分流放错分支(topic vs activity);
        //   ② 拿「③ 到不了这里」这个假不变量拆掉守卫;
        //   ③ 漏掉 ApiClubLeadController 的领队全团结算 —— 它把该活动下**所有已付单**
        //      (无 purchase_kind 过滤)逐个丢进来,payee=俱乐部主理人
        //      ⇒ ③ 的整单票款结给主理人,真正端出权益的 N 家商家一分钱没有。
        // ⇒ 闸放在调用方就永远漏。放在被调方,**现在的三个入口 + 将来任何新入口**一次性全保住。
        //
        // 抛而不是静默返 false:走到这里 = 调用方漏了 ③ 分流 = 编程错误,必须响亮。
        // 返 false 的语义是「已被核销」,拿它掩盖会让新入口静默地把 ③ 结算掉 —— 正是要杀的病。
        // ⚠️ 调用方若是「批量遍历」(如领队全团结算),应在**进来之前**就把 ③ 过滤掉,
        //    别指望靠这里的异常来跳过 —— 它会中断整批。
        if (registration != null && registration.getPurchaseKind() != null
                && registration.getPurchaseKind() == PurchaseKind.EXPLORE_PASS) {
            throw new IllegalStateException(
                    "探索票不能整单核销,请按章节核销(registrationId=" + registration.getId() + ")");
        }
        assertActivityRedeemable(registration);
        if (cmsRegistrationMapper.verifyIfNotVerified(registration.getId(), verifierId) == 0) {
            if (cmsRegistrationMapper.hasActiveManualRefundCaseForUpdate(registration.getId())) {
                throw new IllegalStateException("订单正在人工退款处理中，暂不可核销");
            }
            CmsRegistration current =
                    cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(registration.getId());
            if (current == null) {
                throw new IllegalStateException("订单数据异常,无法核销");
            }
            if (!Integer.valueOf(2).equals(current.getPaymentStatus())
                    || !Integer.valueOf(2).equals(current.getRegistrationStatus())) {
                throw new IllegalStateException("订单未完成支付或已退款,无法核销");
            }
            if (Integer.valueOf(1).equals(current.getVerificationStatus())) {
                return false;
            }
            throw new IllegalStateException("订单数据异常,无法核销");
        }
        settlementEntryService.recordPaidRegistration(registration, payeeId);
        writeScanLedger(registration.getId(), verifierId, validationMethod, station);
        if (activityId != null) {
            CoopGroupMemberVerify groupVerify = new CoopGroupMemberVerify();
            groupVerify.setTopicId(registration.getOwnerId());
            groupVerify.setActivityId(activityId);
            groupVerify.setRegistrationId(registration.getId());
            groupVerify.setVerifierMemberId(verifierId);
            groupVerify.setVerifyTime(new Date());
            if (coopGroupMemberVerifyMapper.insertCoopGroupMemberVerify(groupVerify) != 1) {
                throw new IllegalStateException("带队场次核销事实写入失败");
            }
        }
        writeVerificationAudit("REGISTRATION", registration.getId(), verifierId,
                actorMemberId, actorRoleCode, action, "CMS_REGISTRATION", registration.getId(),
                activityId != null ? activityId : (station == null ? null : station.getId()),
                verificationAuditRequestId(registration.getId()),
                "{\"verificationStatus\":0}", "{\"verificationStatus\":1}");
        growthService.enqueueStoreVerification(registration.getId());
        return true;
    }

    static final String WHOLE_ORDER_VERIFY_REVERSE = "REGISTRATION_VERIFY_REVERSE";

    /**
     * 核销审计幂等键:首次核销沿用原键;每被后台撤销一次,下一次核销换一代键,
     * 否则 uk_business_audit_request 会让撤销后的重新核销永远写不进审计而整单回滚。
     */
    private String verificationAuditRequestId(Long registrationId) {
        int reversals = wholeOrderReversalCount(registrationId);
        return "registration-redeem-" + registrationId + (reversals == 0 ? "" : "-r" + reversals);
    }

    private int wholeOrderReversalCount(Long registrationId) {
        List<BusinessAuditEvent> events = businessAuditEventMapper.selectByTarget("CMS_REGISTRATION", registrationId);
        int count = 0;
        if (events != null) for (BusinessAuditEvent event : events) {
            if (event != null && "REGISTRATION".equals(event.getDomainType())
                    && WHOLE_ORDER_VERIFY_REVERSE.equals(event.getAction())) count++;
        }
        return count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean reverseWholeOrderVerification(Long registrationId, String reason, Long operatorUserId) {
        return reverseWholeOrderVerification(registrationId, reason, operatorUserId, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean reverseWholeOrderVerification(Long registrationId, String reason, Long operatorUserId,
                                                 boolean clawbackRewards) {
        String why = reason == null ? "" : reason.trim();
        if (registrationId == null || registrationId <= 0 || operatorUserId == null || operatorUserId <= 0
                || why.isEmpty() || why.length() > 255) {
            throw new IllegalArgumentException("订单、撤销原因(1-255字)或操作人无效");
        }
        CmsRegistration located = cmsRegistrationMapper.selectCmsRegistrationById(registrationId);
        if (located == null || located.getOwnerId() == null) throw new IllegalStateException("订单不存在");
        if (located.getPurchaseKind() != null && located.getPurchaseKind() == PurchaseKind.EXPLORE_PASS) {
            throw new IllegalStateException("探索票请在核销记录中按章撤销");
        }
        if (!Long.valueOf(2L).equals(located.getOwnerType())) {
            throw new IllegalStateException("主题整单核销已计入执行商家结算，请走人工售后处理");
        }
        // 锁序与核销/取消一致:activity -> registration。
        CmsActivity activity = cmsActivityMapper.selectCmsActivityByIdForUpdate(located.getOwnerId());
        if (activity == null || activity.getMemberId() == null) throw new IllegalStateException("订单关联活动不存在");
        if (activity.getIsSettlement() != null && activity.getIsSettlement() != 0) {
            throw new IllegalStateException("活动已结算，不能撤销核销，请走人工售后处理");
        }
        CmsRegistration current = cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(registrationId);
        if (current == null || !Long.valueOf(2L).equals(current.getOwnerType())
                || !located.getOwnerId().equals(current.getOwnerId())) {
            throw new IllegalStateException("订单归属已变化，请刷新后重试");
        }
        if (!Integer.valueOf(1).equals(current.getVerificationStatus())) {
            throw new IllegalStateException("该订单未核销或已撤销，无需重复撤销");
        }
        for (com.chengyinhub.business.domain.Redemption row : perkLedgerService.listRedemptions(registrationId)) {
            if (row != null && "ACTIVE".equals(row.getStatus())) {
                throw new IllegalStateException("该票有按章核销流水，请在核销记录中逐条撤销");
            }
        }
        // 条件置位(NOT EXISTS ACTIVE redemption 在 SQL 内当前读判定),0 行 = 状态刚变化。
        if (cmsRegistrationMapper.clearVerification(registrationId) != 1) {
            throw new IllegalStateException("核销状态刚发生变化，请刷新后重试");
        }
        BusinessAuditEvent audit = new BusinessAuditEvent();
        audit.setDomainType("REGISTRATION");
        audit.setDomainId(registrationId);
        audit.setOwnerMemberId(activity.getMemberId());
        audit.setActorType(BusinessAuditEvent.ACTOR_TYPE_SYS_USER);
        audit.setActorMemberId(operatorUserId);
        audit.setActorRoleCode("SYS_USER");
        audit.setAction(WHOLE_ORDER_VERIFY_REVERSE);
        audit.setTargetType("CMS_REGISTRATION");
        audit.setTargetId(registrationId);
        audit.setRelationId(activity.getId());
        audit.setBeforeJson("{\"verificationStatus\":1,\"verifierId\":"
                + (current.getVerifierId() == null ? "null" : current.getVerifierId()) + "}");
        audit.setAfterJson("{\"verificationStatus\":0}");
        audit.setReason(why);
        int generation = wholeOrderReversalCount(registrationId) + 1;
        audit.setRequestId("registration-verify-reverse-" + registrationId + "-" + generation);
        audit.setCreateTime(new Date());
        if (businessAuditEventMapper.insertBusinessAuditEvent(audit) != 1) {
            throw new IllegalStateException("撤销核销审计写入失败，撤销已回滚");
        }
        // PR10 复审:与按章撤销(redemption→REVERSED)对齐 —— 整单票的核销台账行就是 cms_registration_scan,
        // 标成已撤销,商家赏金/合作结算证据/核销风控/商家人头都不再把这次扫码算作核销;
        // 存量无台账行的老单 0 行也不算失败。重新核销时 writeScanLedger 会另落一行新台账。
        cmsRegistrationScanMapper.markWholeOrderScansReversed(registrationId);
        if (clawbackRewards) {
            CmsRegistration owner = cmsRegistrationMapper.selectCmsRegistrationById(registrationId);
            if (owner == null || owner.getMemberId() == null) {
                throw new IllegalStateException("冲回奖励找不到报名单 registrationId=" + registrationId);
            }
            redemptionRewardClawbackService.clawbackWholeOrderVerification(owner, activity.getMemberId(), why,
                    operatorUserId, "registration-verify-clawback-" + registrationId + "-" + generation);
        } else {
            // 未勾冲回:已发的奖励按按章撤销同口径保留;但还在队列里没发的到店成长不能为已撤销的核销迟到补发。
            storeVerifyGrowthDeliveryMapper.deletePendingByRegistration(registrationId);
        }
        logger.info("整单核销已撤销 registrationId={} operatorUserId={}", registrationId, operatorUserId);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CmsRegistration requireCurrentUsableRegistration(CmsRegistration snapshot) {
        if (snapshot == null || snapshot.getId() == null) {
            throw new IllegalStateException("报名记录不存在");
        }
        CmsActivity activity = null;
        if (Long.valueOf(2L).equals(snapshot.getOwnerType())) {
            // 与活动取消相同 activity→registration 锁序；不持旧快照核销。
            activity = cmsActivityMapper.selectCmsActivityByIdForUpdate(snapshot.getOwnerId());
            if (activity == null || Integer.valueOf(2).equals(activity.getDelFlag())) {
                throw new IllegalStateException("活动不存在");
            }
            if (activity.getCancelTime() != null) throw new IllegalStateException("活动已取消，无法使用");
        }
        CmsRegistration current = cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(snapshot.getId());
        if (current == null || !Objects.equals(snapshot.getMemberId(), current.getMemberId())
                || !Objects.equals(snapshot.getOwnerType(), current.getOwnerType())
                || !Objects.equals(snapshot.getOwnerId(), current.getOwnerId())) {
            throw new IllegalStateException("订单归属已变化，请重新获取订单");
        }
        if (!Integer.valueOf(2).equals(current.getPaymentStatus())
                || !Integer.valueOf(2).equals(current.getRegistrationStatus())) {
            throw new IllegalStateException("订单未完成支付或已退款，无法使用");
        }
        String rejection = ExploreTicketUsePolicy.rejection(current.getPurchaseKind(),
                activity == null ? null : activity.getEndDate(), new Date());
        if (rejection != null) throw new IllegalStateException(rejection);
        return current;
    }

    private CmsActivity assertActivityRedeemable(CmsRegistration registration) {
        if (!Long.valueOf(2L).equals(registration.getOwnerType())) return null;
        // 与取消共用 activity→registration 锁序，避免核销继续使用取消前的快照。
        CmsActivity activity = cmsActivityMapper.selectCmsActivityByIdForUpdate(registration.getOwnerId());
        if (activity == null || Integer.valueOf(2).equals(activity.getDelFlag())) {
            throw new IllegalStateException("活动不存在");
        }
        if (activity.getCancelTime() != null) {
            throw new IllegalStateException("活动已取消，无法核销");
        }
        return activity;
    }

    /**
     * 核销台账补写(商家维度:「我核销过这张票」)。
     *
     * <p>⚠️ 探针必须带 registration_type=1:不带会命中报名创建时按节点模板插的 type=0 行
     * (member_id = 模板主,常即正主商家)→ 误判「已有台账」→ 不插核销行。
     *
     * <p>幂等键是 (registrationId, memberId, type=1) ⇒ 同一商家对同一张票只留一行。
     * 对 ③ 而言,同一商家核销该票的第 2 个章节时不会再插 —— 这是**刻意的**:
     * 台账答的是「哪些商家核销过这张票」,而「这票的哪一章被谁核销了」的真源是 redemption 表。
     */
    private void writeScanLedger(Long registrationId, Long verifierId, Integer validationMethod) {
        writeScanLedger(registrationId, verifierId, validationMethod, null);
    }

    private void writeScanLedger(Long registrationId, Long verifierId, Integer validationMethod,
                                 CmsRegistrationMerchant station) {
        CmsRegistrationScan probe = new CmsRegistrationScan();
        probe.setRegistrationId(registrationId);
        probe.setMemberId(verifierId);
        probe.setRegistrationType(1);
        if (cmsRegistrationScanMapper.selectCmsRegistrationScanList(probe).isEmpty()) {
            CmsRegistrationScan ins = new CmsRegistrationScan();
            ins.setRegistrationId(registrationId);
            ins.setMemberId(verifierId);
            ins.setRegistrationType(1);
            ins.setValidationMethod(validationMethod);
            if (station != null) {
                String stationName = station.getAddressName();
                if (StringUtils.isEmpty(stationName)) stationName = station.getNodeName();
                if (StringUtils.isEmpty(stationName)) stationName = station.getAddress();
                if (StringUtils.isEmpty(stationName)) stationName = "据点 " + station.getId();
                ins.setName(stationName);
                ins.setAddress(station.getAddress());
                ins.setLatitude(station.getLatitude());
                ins.setLongitude(station.getLongitude());
                ins.setTemplateId(station.getTemplateId());
            }
            ins.setStatus(Long.valueOf(0));
            ins.setCreateTime(new Date());
            cmsRegistrationScanMapper.insertCmsRegistrationScan(ins);
        }

        // [赏金] 拓展人激活赏金唯一认的真实信号就是「某商家核销了某张票」,而本方法是它的**唯一收口点**
        // —— verifyAndSettle(①/② 整单核销)与 redeemExploreChapterAndRecord(③ 按章核销)都汇到这里。
        //
        // ★ 挂在这层而不是挂调用方(2026-07-17 rebase 时改的):本分支原先挂在 verifyAndSettle 末尾,
        //   而 PR #95 新增了 ③ 按章核销这条路径 —— 挂调用方就会让 ③ 商家**永远不触发赏金且零告警**,
        //   偏偏 ③(探索通行证/商家权益)正是这个冷启动功能最该覆盖的那类商家。挂收口点,新路径自动覆盖。
        //
        // 放在 if 外面:tryActivate 每次都从库里重新数人头、且有 CAS 兜底,重复触发无害;
        // 而放进 if 里会让「台账已存在但赏金那次没算成」的情况永远补不回来。
        // onVerification 自己吞异常:核销是收钱路径,赏金是实验性冷启动功能,任何情况下都不能连累它。
        // 只有锁冲突例外:它原样抛出,交给外层 @DeadlockRetry 整笔回滚重试(见 SavepointLockFailures)。
        merchantReferralBountyService.onVerification(verifierId);
        try {
            verificationRiskService.inspectAndQueue(verifierId, registrationId);
        } catch (Exception riskProbeFailure) {
            logger.error("核销异常抽检入队失败,不回滚核销: registrationId={}, verifierId={}",
                    registrationId, verifierId, riskProbeFailure);
            String failureType = riskProbeFailure.getClass().getSimpleName();
            try {
                committedBusinessNotice.executeAfterCommit("verification.risk.queue-failed", registrationId,
                        () -> alarmService.deliver("核销异常抽检入队失败", "registrationId=" + registrationId + " type=" + failureType)
                                == AlarmService.AlarmDelivery.SUCCESS);
            } catch (RuntimeException notificationFailure) {
                logger.error("Verification risk failure dispatch unavailable type={}", notificationFailure.getClass().getSimpleName());
            }
        }
    }

    @Override
    @DeadlockRetry
    @Transactional(rollbackFor = Exception.class)
    public ExploreChapterRedemptionResult redeemExploreChapterAndRecord(CmsRegistration registration,
                                              Long chapterId, Long topicId, Long merchantOwnerMemberId,
                                              Long operatorMemberId, Integer validationMethod) {
        return redeemExploreChapterAndRecord(registration, chapterId, topicId, merchantOwnerMemberId,
                operatorMemberId, "MERCHANT_OPERATOR", validationMethod);
    }

    @Override
    @DeadlockRetry
    @Transactional(rollbackFor = Exception.class)
    public ExploreChapterRedemptionResult redeemExploreChapterAndRecord(CmsRegistration registration,
                                               Long chapterId, Long topicId, Long merchantOwnerMemberId,
                                               Long operatorMemberId, String actorRoleCode,
                                               Integer validationMethod) {
        if (merchantOwnerMemberId == null || merchantOwnerMemberId <= 0
                || operatorMemberId == null || operatorMemberId <= 0) {
            throw new IllegalStateException("商家主体或核销操作人无效");
        }
        if (registration == null || registration.getId() == null || registration.getOwnerId() == null
                || !Long.valueOf(2L).equals(registration.getOwnerType())) {
            throw new IllegalStateException("报名单类型或关联活动无效");
        }
        CmsActivity activity = assertActivityRedeemable(registration);
        CmsRegistration current = cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(registration.getId());
        if (current == null || !Integer.valueOf(2).equals(current.getPaymentStatus())
                || !Integer.valueOf(2).equals(current.getRegistrationStatus())) {
            throw new IllegalStateException("订单未完成支付、已退款或已失效，无法核销");
        }
        if (!Long.valueOf(2L).equals(current.getOwnerType())
                || !registration.getOwnerId().equals(current.getOwnerId())
                || !java.util.Objects.equals(registration.getMemberId(), current.getMemberId())
                || activity == null || topicId == null || !topicId.equals(activity.getTopicId())) {
            throw new IllegalStateException("订单与当前活动或路线不一致，无法核销");
        }
        registration = current;
        // A包：按章核销同样拒绝非探索票与已到期场次探索票(master 已先校验 ownerType=2 再锁活动)。
        if (!Integer.valueOf(PurchaseKind.EXPLORE_PASS).equals(registration.getPurchaseKind())) {
            throw new IllegalStateException("探索票或路线归属已变化，请重新扫码");
        }
        String useRejection = ExploreTicketUsePolicy.rejection(registration.getPurchaseKind(), activity.getEndDate(), new Date());
        if (useRejection != null) throw new IllegalStateException(useRejection);
        if (cmsRegistrationMapper.hasActiveManualRefundCaseForUpdate(registration.getId())) {
            throw new IllegalStateException("订单正在人工退款处理中，暂不可核销");
        }
        Long replayedRedemptionId = perkLedgerService.findReplayableChapterRedemptionIdForUpdate(
                registration.getId(), topicId, chapterId, merchantOwnerMemberId);
        if (replayedRedemptionId != null) {
            return new ExploreChapterRedemptionResult(replayedRedemptionId, 0, true);
        }

        // ⓪ ★ 事务内复核 chapterId 属于「该商家对该票的可核销候选集」。
        //
        // 为什么不能只信 controller 反推出来的那个:交集是在**事务外**算的,而
        // /scan_qr_code_chapter 让商家可以**直接把 chapterId 打进来**(多章候选时选哪一章)。
        // 不在这儿复核 ⇒ 商家能指定核销**任意一章** —— 挑最便宜的那章交差,玩家在贵的那章白跑;
        // 或者干脆核销一个他压根没入驻的章。
        // ⚠️ redeemChapter 的 findLiveOffer 只按 (chapterId, merchantId) 找 offer,**不校验这票欠不欠这章**;
        //    markRedeemed 的 CAS 只保证「该章 status=0」,也不校验「这商家有没有资格碰这章」。
        //    两者交叉的那个洞只有这里能堵。
        if (!perkLedgerService.resolveRedeemableChapters(
                registration.getId(), topicId, merchantOwnerMemberId)
                .contains(chapterId)) {
            throw new IllegalStateException("该章节不可由您核销");
        }

        // ① 按章核销(entitlement CAS → 档位分支扣 quota → 插 redemption 流水)。
        //    它自己也是 @Transactional(REQUIRED) ⇒ 并入本事务,任一步抛全回滚。
        Long redemptionId = perkLedgerService.redeemChapter(
                registration.getId(), chapterId, merchantOwnerMemberId, operatorMemberId);

        // ② 台账:商家侧要能查到「我核销过这张票」。与 ① 同事务 —— 核销成立了台账就必须在。
        writeScanLedger(registration.getId(), merchantOwnerMemberId, validationMethod);

        // ③ **首次核销就置位** verification_status(拍板:玩家只要核销一个就不能退款)。
        //
        // ★ 为什么是首次而不是通关时:商家已经把权益端出去了,钱就不能退。
        //   verification_status 是三个退款入口共用的唯一判据(RefundPolicy.verifiedVerdict 的
        //   ③ 分支「已核销订单需人工核实」正 key 在它上面)⇒ 置位在这里,三个闸自动全部生效,
        //   不必去改 6 个调用点(那才是「闸放错层」的温床)。
        //   语义上也自洽:verification_status 是**报名级布尔**「这张票被核销过」,不是「核销完了」——
        //   CmsTopicServiceImpl:897 的既有注释就是这么用的。
        //
        // ⚠️ 置位会让 issueDynCode 的「已核销不再出码」闸对 ③ 当场断链(核销完第 1 章就拿不到码
        //    去核销第 2~5 章)⇒ 已同步把那处的 ③ 判据换成 hasPendingEntitlements。两者绑死,别只改一边。
        //
        // ⚠️ CAS 返 0 = 已置过位(第 2 章及以后必然如此),**不是错误**,不抛。
        cmsRegistrationMapper.verifyIfNotVerified(registration.getId(), merchantOwnerMemberId);

        // ④ 计酬档:记一笔**平台应付台账**(含核销那一刻的费率快照)。
        //
        // ⚠️ **刻意不调 recordPaidRegistration** —— 那是「整单票款给单一 payee」的口径,
        //    ③ 一张票跨 N 家核销 ⇒ 照搬 = 最后扫的那家白拿全额 = 资损。**别为了「接上入账」去动它。**
        //    ③ 的口径是应付台账(D21:票款全额归平台,人头费是平台的采购成本 ⇒ 对公打款 + 商家开票,
        //    不是分账、不碰二清、不经 settlement_detail)。
        //
        // ⚠️ 必须在**本事务内**。分开写 = 核销提交了、应付没落 = 又一个丢账口,且零告警。
        // ⚠️ 两道闸(只有计酬档 + 只有 ③-自营)封在 accrueOnRedemption 里,不在这里重写一遍口径:
        //    闸和账在同一个类里,新调用方接一个方法就够,不会只接半边。
        merchantPayableService.accrueOnRedemption(redemptionId);
        // ⑤ 通关副作用与核销、权益、应付同一 REQUIRED 事务；失败必须整体回滚，商家可重扫。
        int completionEarnedXp = 0;
        if (perkLedgerService.isExploreCompleted(registration.getId(), topicId)) {
            com.chengyinhub.business.domain.Redemption redemption = redemptionMapper.selectById(redemptionId);
            if (redemption == null || redemption.getCreateTime() == null) {
                throw new IllegalStateException("探店日通关来源时间缺失");
            }
            TopicCompletionAwardVO award = topicCompletionAwardService.grantOnce(registration.getMemberId(), topicId,
                    registration.getOwnerId(), redemption.getCreateTime());
            completionEarnedXp = award == null ? 0 : Math.max(0, award.getEarnedXp());
        }
        writeVerificationAudit("REGISTRATION", registration.getId(), merchantOwnerMemberId,
                operatorMemberId, actorRoleCode, "EXPLORE_CHAPTER_REDEEM",
                "CMS_REGISTRATION", registration.getId(), chapterId,
                "explore-redeem-" + redemptionId,
                "{\"chapterRedeemed\":false}", "{\"chapterRedeemed\":true}");
        growthService.enqueueStoreVerification(registration.getId());
        return new ExploreChapterRedemptionResult(redemptionId, completionEarnedXp);
    }

    private void writeVerificationAudit(String domainType, Long domainId, Long ownerMemberId,
                                        Long actorMemberId, String actorRoleCode, String action,
                                        String targetType, Long targetId, Long relationId,
                                        String requestId, String beforeJson, String afterJson) {
        if (domainId == null || ownerMemberId == null || actorMemberId == null
                || StringUtils.isEmpty(actorRoleCode)) {
            throw new IllegalStateException("核销审计上下文缺失");
        }
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setDomainType(domainType);
        event.setDomainId(domainId);
        event.setOwnerMemberId(ownerMemberId);
        event.setActorMemberId(actorMemberId);
        event.setActorRoleCode(actorRoleCode);
        event.setAction(action);
        event.setTargetType(targetType);
        event.setTargetId(targetId);
        event.setRelationId(relationId);
        event.setBeforeJson(beforeJson);
        event.setAfterJson(afterJson);
        event.setRequestId(requestId != null && requestId.length() > 64
                ? requestId.substring(0, 64) : requestId);
        event.setCreateTime(new Date());
        if (businessAuditEventMapper.insertBusinessAuditEvent(event) != 1) {
            throw new IllegalStateException("核销审计写入失败");
        }
    }
}
