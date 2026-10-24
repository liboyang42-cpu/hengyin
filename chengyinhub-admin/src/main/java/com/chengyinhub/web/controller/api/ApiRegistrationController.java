package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.domain.vo.ExploreChapterRedemptionResult;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.QrcodeServiceImpl;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.business.service.support.ExploreTicketUsePolicy;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import com.chengyinhub.common.config.ComplianceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.validation.annotation.Validated;

import java.util.*;
import java.util.function.Supplier;
import com.alibaba.fastjson2.JSON;
import org.springframework.http.ResponseEntity;

import com.chengyinhub.common.utils.OwnershipUtils;
/**
 * 活动/主题报名
 *
 * @author webxh
 * @date 2025-08-31
 */
@RestController
@Tag(name = "活动/主题报名")
@RequestMapping("/api/registration")
public class ApiRegistrationController extends BaseController {

    private static final Logger logger = LoggerFactory.getLogger(ApiRegistrationController.class);

    @Autowired
    private com.chengyinhub.business.service.ITeamGroupService teamGroupService;
    @Autowired
    private ICmsRegistrationService registrationService;
    @Autowired
    private IMerchantAccessService merchantAccessService;
    @Autowired
    private IRegistrationCheckoutService registrationCheckoutService;

    @Autowired
    private IConsentRecordService consentRecordService;

    @Autowired
    private ComplianceProperties complianceProperties;

    private static final String SIGNUP_DATA_SHARING_DOC = "activity_host_data_sharing";
    private static final String SIGNUP_DATA_SHARING_SCENE = "activity_signup";
    @Autowired
    private com.chengyinhub.business.mapper.CoopOrderMapper coopOrderMapper;
    @Autowired
    private IClubMemberService clubMemberService;

    @Autowired
    private IWechatPayService wechatPayService;

    @Autowired
    private IRefundService refundService;

    @Autowired
    private com.chengyinhub.business.service.support.RegistrationCancellationReadService cancellationReadService;


    @Autowired
    private ICmsTopicService topicService;

    @Autowired
    private ICmsActivityService activityService;

    @Autowired
    private com.chengyinhub.business.service.IOmsTicketService omsTicketService;

    @Autowired
    private ICmsRegistrationScanService registrationScanService;

    @Autowired
    private ICmsMemberTemplateService memberTemplateService;

    @Autowired
    private ICmsRegistrationMerchantService registrationMerchantService;

    /** [P0-14] ③ 按章核销 + 扫码反推章节。 */
    @Autowired
    private com.chengyinhub.business.service.IPerkLedgerService perkLedgerService;

    /** 用不可变 redemption 的真实落库时间归因主题 evidence，不能拿 controller 处理时间冒充事实时间。 */
    @Autowired
    private com.chengyinhub.business.mapper.RedemptionMapper redemptionMapper;

    @Autowired
    private ITopicCompletionAwardService topicCompletionAwardService;

    @Autowired
    private IExploreCollectibleService exploreCollectibleService;

    /** 批5 完局面(图鉴 / 通关奖励到账明细 / J·R90 入口)的只读读面。纯读,不发奖不写库。 */
    @Autowired
    private com.chengyinhub.business.service.IExploreCompletionReadService exploreCompletionReadService;

    /** [P0-2] 选章面板要的章节名(候选 id → name);只在交集 ≥2 时用。 */
    @Autowired
    private ICmsTopicChapterService topicChapterService;


    @Autowired
    private ICmsActivityService cmsActivityService;

    @Autowired
    private QrcodeServiceImpl qrcodeService;


    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private com.chengyinhub.business.service.IPointsAwardService pointsAwardService;

    @Autowired
    private com.chengyinhub.business.service.IGrowthService growthService;


    @Autowired
    private IUmsMemberBalanceDetailService umsMemberBalanceDetailService;


    @Autowired
    private IMmsMerchantService mmsMerchantService;

    @Autowired
    private ISysCategoryService sysCategoryService;

    @Operation(summary = "报名报价(M0-1 实扣一致):不落库,返回应付/抵扣明细+quoteSign")
    @PostMapping("/quote")
    public AjaxResult quote(@RequestBody RegistrationRequest param) {
        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {
        }
        if (member_id <= 0) {
            return error("请先登录");
        }
        try {
            com.chengyinhub.business.domain.dto.RegistrationQuoteResult q =
                    registrationCheckoutService.quote(member_id, param);
            return AjaxResult.success(q);
        } catch (RegistrationCheckoutException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @Operation(summary = "支付服务就绪状态")
    @PostMapping("/payment-readiness")
    public AjaxResult paymentReadiness() {
        boolean ready = registrationCheckoutService.isPaymentReady();
        Map<String, Object> data = new HashMap<>();
        data.put("ready", ready);
        data.put("message", ready ? "支付服务可用" : "支付服务暂不可用");
        return success(data);
    }

    /**
     * 创建活动报名
     */
    @Operation(summary = "创建活动报名")
    @PostMapping("/create")
    public AjaxResult createRegistration(@Validated @RequestBody RegistrationRequest param) {
        // [RUN-51] member_id 提到 try 外:catch 里的留痕日志要知道「这是谁的单」。
        Long member_id = 0L;
        try {
            try {
                member_id = getAppUserId();
            } catch (Exception e) {

            }
            if (member_id <= 0) {
                return error("请先登录");
            }
            if (!hasCurrentSignupDataSharingConsent(member_id)) {
                return error("请先完成报名信息提供的单独同意");
            }
            RegistrationCheckoutResult result = registrationCheckoutService.createOrReplay(member_id, param);
            return AjaxResult.success(result.getMessage(), result.getData());
        } catch (RegistrationCheckoutException e) {
            // [RUN-51] 业务异常也要留痕：以前 4xx/5xx 一律无声返回，线上无法复盘。
            logger.warn("报名建单业务异常 member_id={}, ownerType={}, ownerId={}, ticketId={}, requestId={}, code={}, msg={}",
                    member_id, param == null ? null : param.getOwnerType(),
                    param == null ? null : param.getOwnerId(), param == null ? null : param.getTicketId(),
                    param == null ? null : param.getRequestId(), e.getCode(), e.getMessage());
            return checkoutError(e);
        } catch (Exception e) {
            // [RUN-51] 这里曾是把 e.getMessage() 直接掩成「系统繁忙」却零日志的黑洞：
            // AjaxResult.error 走 UserSafeMessage 脱敏，技术异常(如 NPE 的 null message)在线上完全不可诊断。
            // 堆栈必须落日志（含会员与请求指纹），对外仍返回脱敏文案。
            logger.error("报名建单未预期异常 member_id={}, ownerType={}, ownerId={}, ticketId={}, requestId={}",
                    member_id, param == null ? null : param.getOwnerType(),
                    param == null ? null : param.getOwnerId(), param == null ? null : param.getTicketId(),
                    param == null ? null : param.getRequestId(), e);
            if ("SHOP_DAY_ACCOUNT_TICKET_LIMIT_REACHED".equals(e.getMessage())) {
                return AjaxResult.error("已达到该主题的购票上限");
            }
            return AjaxResult.error(e.getMessage());
        }
    }

    @Operation(summary = "重新支付")
    @PostMapping("/pay")
    @Parameters({
            @Parameter(name = "id", description = "报名id")
    })
    public AjaxResult pay(String id) {
        try {
            // 获取当前登录用户ID
            Long member_id = 0L;
            try {
                member_id = getAppUserId();
            } catch (Exception e) {

            }
            if (member_id <= 0) {
                return error("请先登录");
            }
            if (!hasCurrentSignupDataSharingConsent(member_id)) {
                return error("请先完成报名信息提供的单独同意");
            }

            RegistrationCheckoutResult result = registrationCheckoutService.retryPayment(
                    member_id, Long.valueOf(id));
            return AjaxResult.success(result.getMessage(), result.getData());
        } catch (RegistrationOrderExpiredException e) {
            // 2026-09-17 拍板 #21:过期待支付单被 /pay 拒时,前端要能机器识别并自动换幂等键重建,
            // 不能靠匹配文案。410 与漫游下线/券码失效同一语义约定(业务 code,HTTP 仍是 200)。
            return new AjaxResult(410, e.getMessage());
        } catch (RegistrationCheckoutException e) {
            return checkoutError(e);
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @Operation(summary = "获取原生 App 支付参数(trade_type=APP)")
    @PostMapping("/pay/app")
    @Parameters({
            @Parameter(name = "id", description = "报名id")
    })
    public AjaxResult payApp(String id) {
        try {
            Long member_id = 0L;
            try {
                member_id = getAppUserId();
            } catch (Exception e) {

            }
            if (member_id <= 0) {
                return error("请先登录");
            }
            // 与 /pay 同一道合规闸:没有当前版本的报名信息单独同意,一律拒。
            if (!hasCurrentSignupDataSharingConsent(member_id)) {
                return error("请先完成报名信息提供的单独同意");
            }

            RegistrationCheckoutResult result = registrationCheckoutService.retryPayment(
                    member_id, Long.valueOf(id), true);
            return AjaxResult.success(result.getMessage(), result.getData());
        } catch (RegistrationOrderExpiredException e) {
            // 与 /pay 同一口径:过期单给机器可识别的 410,App 端同样能自动换键重建。
            return new AjaxResult(410, e.getMessage());
        } catch (RegistrationCheckoutException e) {
            return checkoutError(e);
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    private boolean hasCurrentSignupDataSharingConsent(Long memberId) {
        String currentVersion = complianceProperties == null
                ? null : complianceProperties.versionOf(SIGNUP_DATA_SHARING_DOC);
        if (StringUtils.isEmpty(currentVersion)) {
            logger.error("报名信息单独同意版本未配置，拒绝创建或支付报名单");
            return false;
        }
        try {
            return consentRecordService.hasCurrentAgreement(memberId, SIGNUP_DATA_SHARING_DOC,
                    SIGNUP_DATA_SHARING_SCENE, currentVersion);
        } catch (RuntimeException e) {
            logger.error("报名信息单独同意校验失败，拒绝创建或支付报名单，memberId={}", memberId, e);
            return false;
        }
    }


    /**
     * 取消报名
     */
    @Operation(summary = "取消报名")
    @PostMapping("/cancel")
    @Parameters({
            @Parameter(name = "id", description = "报名id")
    })
    public AjaxResult cancelRegistration(String id) {
        try {
            // 获取当前登录用户ID
            Long memberId = 0L;
            try {
                memberId = getAppUserId();
            } catch (Exception e) {

            }
            if (memberId <= 0) {
                return error("请先登录");
            }

            String userName = getAppUserName();

            Long registrationId = Convert.toLong(id, 0L);

            boolean result = registrationService.cancelRegistration(registrationId, memberId, userName);
            return result ? AjaxResult.success("报名已取消") : AjaxResult.error("取消失败");
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * 取消已支付报名；资格、现金渠道退款与积分返还分别按提交后事实反馈
     */
    @Operation(summary = "取消已支付报名并查询退款处理结果")
    @PostMapping("/cancel-refund")
    @Parameters({
            @Parameter(name = "id", description = "报名id")
    })
    public AjaxResult cancelRefund(String id) {
        Long memberId = 0L;
        try { memberId = getAppUserId(); } catch (Exception e) { }
        if (memberId <= 0) return error("请先登录");
        Long registrationId = Convert.toLong(id, 0L);
        if (registrationId <= 0) return error("缺少报名ID");
        try {
            com.chengyinhub.business.service.support.RegistrationCancellationReadService.Result existingCase =
                    cancellationReadService.readPlayerManualRefundCase(registrationId, memberId);
            if (existingCase != null) {
                return AjaxResult.success(existingCase.getMessage(), existingCase.getData());
            }
            if (teamGroupService.cancelPaidRegistration(registrationId, memberId) == com.chengyinhub.business.service.ITeamGroupService.CANCEL_MANUAL_REVIEW) {
                return manualReviewFeedback(registrationId, "本单已转平台人工评估，尚未退款");
            }
            return cancellationFeedback(registrationId);
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * 主题主理人(owner)清退某条已支付报名并退款(按主题归属授权,非报名者本人)
     */
    @Operation(summary = "主理人清退报名并退款")
    @PostMapping("/cancel-by-owner")
    @Parameters({
            @Parameter(name = "id", description = "报名id")
    })
    public AjaxResult cancelByOwner(String id) {
        Long memberId = 0L;
        try { memberId = getAppUserId(); } catch (Exception e) { }
        if (memberId <= 0) return error("请先登录");
        Long registrationId = Convert.toLong(id, 0L);
        if (registrationId <= 0) return error("缺少报名ID");
        try {
            if (teamGroupService.cancelPaidRegistrationByOwner(registrationId, memberId) == com.chengyinhub.business.service.ITeamGroupService.CANCEL_MANUAL_REVIEW) {
                return manualReviewFeedback(registrationId, "本单已转平台人工评估，尚未退款");
            }
            return cancellationFeedback(registrationId);
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * 查询报名详情
     */
    @Operation(summary = "查询报名详情")
    @PostMapping("/info")
    @Parameters({
            @Parameter(name = "id", description = "报名id")
    })
    public AjaxResult getRegistrationDetail(String id) {
        try {
            // 获取当前登录用户ID
            Long memberId = 0L;
            try {
                memberId = getAppUserId();
            } catch (Exception e) {

            }
            if (memberId <= 0) {
                return error("请先登录");
            }

            Long registrationId = Convert.toLong(id, 0L);

            CmsRegistration registration = registrationService.selectCmsRegistrationById(registrationId);
            if (registration == null) {
                return AjaxResult.error("报名记录不存在");
            }

            // 验证是否是本人或管理员!SecurityUtils.isAdmin(userId)
            if (OwnershipUtils.notOwner(registration.getMemberId(), memberId)) {
                return AjaxResult.error("无权查看该报名信息");
            }
            if (registration.getOwnerType() == 1) {
                CmsTopic topic = topicService.selectCmsTopicById(registration.getOwnerId());
                if (topic != null) {
                    //查询主题的分类信息
                    String categoryIds = topic.getCategoryIds();
                    if (!StringUtils.isEmpty(categoryIds)) {
                        String[] ids = categoryIds.split(",");
                        List<SysCategory> listCategory = sysCategoryService.selectSysCategoryListByIds(ids);
                        if (listCategory.size() > 0) {
                            topic.setSysCategoryList(listCategory);
                        }
                    }
                    registration.setCmsTopic(topic);
                }

                // 合同二：票种履约窗与无票自玩主题履约窗统一从 FulfillmentWindowResolver 取。
                if (topic != null) {
                    com.chengyinhub.business.domain.OmsTicket ticket = registration.getTicketId() == null
                            ? null : omsTicketService.selectOmsTicketById(registration.getTicketId());
                    attachRefundVerdict(registration,
                            com.chengyinhub.business.util.RefundPolicy.evaluateTopicFulfillmentWindow(
                                    registration.getPaymentStatus(), registration.getVerificationStatus(),
                                    topic, ticket, new java.util.Date()));
                }

                CmsRegistrationScan cmsRegistrationScan = new CmsRegistrationScan();
                cmsRegistrationScan.setRegistrationId(Long.valueOf(id));
                List<CmsRegistrationScan> registrationScanList = registrationScanService.selectCmsRegistrationScanList(cmsRegistrationScan);
                for (int i = 0; i < registrationScanList.size(); i++) {
                    if (registrationScanList.get(i).getRegistrationType()==1){
                        CmsRegistrationMerchant cmsRegistrationMerchant = new CmsRegistrationMerchant();
                        cmsRegistrationMerchant.setTopicId(topic.getId());
                        cmsRegistrationMerchant.setMemberId(registrationScanList.get(i).getMemberId());
                        List<CmsRegistrationMerchant> registrationMerchantList = registrationMerchantService.selectCmsRegistrationMerchantList(cmsRegistrationMerchant);
                        if (registrationMerchantList.size() > 0) {
                            registrationScanList.get(i).setRegistrationMerchant(registrationMerchantList.get(0));

                            CmsMemberTemplate memberTemplate = memberTemplateService.selectCmsMemberTemplateById(registrationMerchantList.get(0).getTemplateId());
                            registrationScanList.get(i).setMemberTemplate(com.chengyinhub.business.util.TemplateSecrets.strip(memberTemplate)); // [防作弊] 玩家侧不带答案/提示

                            MmsMerchant mmsMerchant = new MmsMerchant();
                            mmsMerchant = mmsMerchantService.selectMmsMerchantByMemberId(registrationScanList.get(i).getMemberId());
                            registrationScanList.get(i).setMmsMerchant(mmsMerchant);
                        }
                    }else{
                        CmsMemberTemplate memberTemplate = memberTemplateService.selectCmsMemberTemplateById(Long.valueOf(registrationScanList.get(i).getTemplateId()));
                        registrationScanList.get(i).setMemberTemplate(com.chengyinhub.business.util.TemplateSecrets.strip(memberTemplate)); // [防作弊] 玩家侧不带答案/提示
                    }
                }
                registration.setRegistrationScanList(registrationScanList);
            }


            if (registration.getOwnerType() == 2) {
                CmsActivity activity = activityService.selectCmsActivityById(registration.getOwnerId());
                if (activity != null) {
                    String categoryIds = activity.getCategoryIds();
                    if (!StringUtils.isEmpty(categoryIds)) {
                        String[] ids = categoryIds.split(",");
                        List<SysCategory> listCategory = sysCategoryService.selectSysCategoryListByIds(ids);
                        if (listCategory.size() > 0) {
                            activity.setSysCategoryList(listCategory);
                        }
                    }
                    registration.setCmsActivity(activity);
                    // 退款可退判定(活动:活动开始 startDate 前可退),供退款详情页展示,与 cancel 路径同口径
                    attachRefundVerdict(registration,
                            com.chengyinhub.business.util.RefundPolicy.evaluate(
                                    2L, registration.getPaymentStatus(), registration.getVerificationStatus(),
                                    null, null, null, activity.getStartDate(),
                                    registration.getPurchaseKind(), registration.getRefundDeadline(),
                                    registration.getPayableAmount(), registration.getPointUsed(), new java.util.Date()));
                }

                // ③ 探索票:带上权益清单(哪几章待核销/已核销/已失效)。
                // 玩家侧唯一能看到"我这张票还剩什么"的地方 —— verification_status 对 ③ 只表示
                // "被核销过至少一次",看不出还欠几章。归属校验在 :540 已做过,这里不重复。
                //
                // ⚠️ 先判 purchase_kind 再查库:① 的活动票也走 ownerType==2 这条分支,
                //    且生产 12 笔付费单全在这里 —— 无条件查 = 每次订单详情白查一次。
                if (registration.getPurchaseKind() != null
                        && registration.getPurchaseKind() == PurchaseKind.EXPLORE_PASS) {
                    registration.setEntitlements(perkLedgerService.listEntitlements(registrationId));
                    // 探店日票面要能回答「几点、在哪集合」(总纲 §1.1/§1.2):带上票种快照。
                    if (registration.getTicketId() != null) {
                        registration.setOmsTicket(omsTicketService.selectOmsTicketById(registration.getTicketId()));
                    }
                }
            }



            if (Integer.valueOf(PurchaseKind.EXPLORE_PASS).equals(registration.getPurchaseKind())) {
                CmsActivity currentActivity = registration.getCmsActivity();
                if (!Integer.valueOf(2).equals(registration.getPaymentStatus())
                        || !Integer.valueOf(2).equals(registration.getRegistrationStatus())
                        || currentActivity == null || currentActivity.getCancelTime() != null
                        || ExploreTicketUsePolicy.rejection(registration.getPurchaseKind(),
                            currentActivity.getEndDate(), new Date()) != null) {
                    registration.setQrcodeUrl(null);
                    registration.setVerificationCode(null);
                }
            }
            attachOrganizer(registration);
            attachManualRefundInfo(registration);
            registration.setRefundApplication(latestRegistrationRefund(registration.getId(), memberId));
            // 订单详情展示 enrich:脱敏手机号 / 支付方式文案 / 退款截止文案 / 权益章节名
            com.chengyinhub.business.util.RegistrationDetailDisplay.enrich(registration, topicChapterService);
            registration.setServerNow(System.currentTimeMillis());
            return AjaxResult.success(registration);
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }


    /**
     * 批5 · 探店日完局面(只读):图鉴 coll_type=5 / 通关奖励到账明细 / 完局后的 J·R90 入口。
     *
     * <p>★为什么不并进 /info:完局面只对已核销过的 ③ 票有意义,而 /info 是每次打开订单详情都要拉的。
     * 无条件把这五张表查一遍 = 每笔订单白查五次(既有注释里 entitlements 那一处已经踩过同一个坑)。
     *
     * <p>纯读:不发奖、不补发、不写任何表。奖励发放的唯一入口仍是核销侧 grantOnce。
     */
    @Operation(summary = "探店日完局面(图鉴/通关奖励到账明细/回访入口)")
    @PostMapping("/explore-completion")
    @Parameters({
            @Parameter(name = "id", description = "报名id")
    })
    public AjaxResult exploreCompletion(String id) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        Long registrationId = Convert.toLong(id, 0L);
        if (registrationId <= 0) {
            return AjaxResult.error("参数不合规");
        }
        CmsRegistration registration = registrationService.selectCmsRegistrationById(registrationId);
        if (registration == null) {
            return AjaxResult.error("报名记录不存在");
        }
        if (!memberId.equals(registration.getMemberId())) {
            return AjaxResult.error("无权查看该报名信息");
        }
        // ③ 探索票之外的单没有完局面。这里 fail-closed 返错而不是返空壳:
        // 空壳会让 ①② 的订单详情也渲染出一块「图鉴 0/0」的假读面。
        if (registration.getPurchaseKind() == null
                || registration.getPurchaseKind() != PurchaseKind.EXPLORE_PASS) {
            return AjaxResult.error("该订单没有探店日完局面");
        }
        CmsActivity activity = registration.getOwnerId() == null ? null
                : activityService.selectCmsActivityById(registration.getOwnerId());
        Long topicId = activity == null ? null : activity.getTopicId();
        return AjaxResult.success(exploreCompletionReadService.read(
                registrationId, memberId, topicId, registration.getOwnerId()));
    }

    /**
     * 查询报名详情
     */
    @Operation(summary = "查询用户报名详情")
    @PostMapping("/join_info")
    @Parameters({
            @Parameter(name = "member_id", description = "用户ID")
    })
    public AjaxResult getJoinRegistrationDetail(String member_id) {
        try {
            // 获取当前登录用户ID
            Long memberId = 0L;
            try {
                memberId = getAppUserId();
            } catch (Exception e) {

            }
            if (memberId <= 0) {
                return error("请先登录");
            }

            // [P0-越权] 一律以 token 身份为准,忽略前端传入的 member_id
            // (原实现 `if(!member_id.equals("")) memberId = Long.valueOf(member_id)` 可传他人 id 越权读其报名/PII,
            //  且 member_id 为 null 时 .equals 还会 NPE)

            CmsRegistration cmsRegistration = new CmsRegistration();
            cmsRegistration.setMemberId(memberId);
            cmsRegistration.setRegistrationStatus(2);
            List<CmsRegistration> cmsRegistrations = registrationService.selectCmsRegistrationList(cmsRegistration);
            if (cmsRegistrations.size() > 0) {

                Long registrationId = Convert.toLong(cmsRegistrations.get(0).getId(), 0L);

                CmsRegistration registration = cmsRegistrations.get(0);
                if (registration == null) {
                    return AjaxResult.error("报名记录不存在");
                }

                // 验证是否是本人或管理员!SecurityUtils.isAdmin(userId)
                if (OwnershipUtils.notOwner(registration.getMemberId(), memberId)) {
                    return AjaxResult.error("无权查看该报名信息");
                }
                if (registration.getOwnerType() == 1) {
                    CmsTopic topic = topicService.selectCmsTopicById(registration.getOwnerId());
                    if (topic != null) {
                        registration.setCmsTopic(topic);
                    }

                    CmsRegistrationScan cmsRegistrationScan = new CmsRegistrationScan();
                    cmsRegistrationScan.setRegistrationId(Long.valueOf(registrationId));
                    List<CmsRegistrationScan> registrationScanList = registrationScanService.selectCmsRegistrationScanList(cmsRegistrationScan);
                    for (int i = 0; i < registrationScanList.size(); i++) {
                        CmsRegistrationMerchant cmsRegistrationMerchant = new CmsRegistrationMerchant();
                        cmsRegistrationMerchant.setTopicId(topic.getId());
                        cmsRegistrationMerchant.setMemberId(registrationScanList.get(i).getMemberId());
                        List<CmsRegistrationMerchant> registrationMerchantList = registrationMerchantService.selectCmsRegistrationMerchantList(cmsRegistrationMerchant);
                        if (registrationMerchantList.size() > 0) {
                            registrationScanList.get(i).setRegistrationMerchant(registrationMerchantList.get(0));

                            CmsMemberTemplate memberTemplate = memberTemplateService.selectCmsMemberTemplateById(registrationMerchantList.get(0).getTemplateId());
                            registrationScanList.get(i).setMemberTemplate(com.chengyinhub.business.util.TemplateSecrets.strip(memberTemplate)); // [防作弊] 玩家侧不带答案/提示
                        }
                    }
                    registration.setRegistrationScanList(registrationScanList);
                }
                if (registration.getOwnerType() == 2) {
                    CmsActivity activity = activityService.selectCmsActivityById(registration.getOwnerId());
                    if (activity != null) {
                        registration.setCmsActivity(activity);
                    }
                }
                return AjaxResult.success(registration);
            } else {
                return AjaxResult.success();
            }
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }


    /**
     * 验证答案 —— 已停用(第二轮拍板第 6 条,2026-09-17)。
     *
     * <p>真实流程是「商家出游戏二维码、玩家扫」:商家 game-node 页出一次性码
     * ({@code /api/merchant/chapter-node/live-checkin-code}),玩家在游玩页扫码走
     * {@code /api/play/checkin}(校验该玩家的报名/通行证)→ 首次完成时把模板券发给玩家本人。
     * 本端点是商家侧旧路径:要求商家核销权限、把同一张模板券发给商家 owner,全仓无调用方。
     * 留着就是第二条发券路径(同节点可双发),所以直接拒绝:不发券、不改扫码记录状态。
     */
    @Operation(summary = "验证答案(已停用,请在游戏内扫码完成)")
    @PostMapping("/verify_answer")
    public AjaxResult VerifyAnswer(String scan_id, String question_answer) {
        return AjaxResult.error("请在游戏内扫码完成");
    }

    /**
     * 商家端：按日统计某主题/活动的报名人数
     */
    @Operation(summary = "按日统计报名人数")
    @PostMapping("/count_by_date")
    @Parameters({
            @Parameter(name = "owner_type", description = "类型1主题 2活动"),
            @Parameter(name = "owner_id", description = "主题/活动ID")
    })
    public AjaxResult countByDate(String owner_type, String owner_id) {
        try {
            Long principalMemberId;
            try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
            if (principalMemberId == null || principalMemberId <= 0) return error("请先登录");
            Long ownerType = Convert.toLong(owner_type, 0L);
            Long ownerId = Convert.toLong(owner_id, 0L);
            if ((ownerType != 1L && ownerType != 2L) || ownerId <= 0) {
                return error("参数不合规");
            }
            if (ownerType == 1L) {
                CmsTopic topic = topicService.selectCmsTopicById(ownerId);
                if (topic == null) return error("主题不存在");
                boolean directOwner = principalMemberId.equals(topic.getMemberId());
                boolean clubOwner = clubMemberService.isTopicOwner(ownerId, principalMemberId);
                if (!directOwner && !clubOwner) {
                    MerchantAccessContext access = merchantAccessService.require(
                            principalMemberId, MerchantPermission.VERIFY_RECORD_READ);
                    CmsRegistrationMerchant awarded = new CmsRegistrationMerchant();
                    awarded.setMemberId(access.getOwnerMemberId());
                    awarded.setTopicId(ownerId);
                    awarded.setAuditStatus(1L);
                    awarded.setDelFlag(0);
                    if (registrationMerchantService.selectCmsRegistrationMerchantList(awarded).isEmpty()
                            && !hasEffectiveMerchantCoopOrder(ownerId, access.getOwnerMemberId())) {
                        return new AjaxResult(HttpStatus.FORBIDDEN, "无权查看该项目趋势");
                    }
                }
            } else {
                CmsActivity activity = activityService.selectCmsActivityById(ownerId);
                if (activity == null) return error("活动不存在");
                boolean directOwner = principalMemberId.equals(activity.getMemberId());
                boolean clubOwner = activity.getTopicId() != null
                        && clubMemberService.isTopicOwner(activity.getTopicId(), principalMemberId);
                if (!directOwner && !clubOwner) {
                    MerchantAccessContext access = merchantAccessService.require(
                            principalMemberId, MerchantPermission.VERIFY_RECORD_READ);
                    if (!access.getOwnerMemberId().equals(activity.getMemberId())) {
                        return new AjaxResult(HttpStatus.FORBIDDEN, "无权查看该项目趋势");
                    }
                }
            }
            List<Map<String, Object>> data = registrationService.countByParticipateDate(ownerType, ownerId);
            return AjaxResult.success(data);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @Operation(summary = "我的报名")
    @PostMapping("/list")
    @Parameters({
            @Parameter(name = "owner_type", description = "类型1、主题  2、活动  3、全部"),
            @Parameter(name = "status", description = "0全部 1待支付 2待使用 3已完成 4已取消"),
            @Parameter(name = "is_online", description = "0全部 1未结束"),
    })
    public AjaxResult myRegistrationList(String owner_type,String status,Integer is_online ) {


        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (member_id == null || member_id <= 0) {
            return error("请先登录");
        }

        Long ownerType = Convert.toLong(owner_type, 0L);
        if (ownerType < 1) {
            return error("请选择正确的类型");
        }

        CmsRegistration registration = new CmsRegistration();
        if(ownerType<3){
            registration.setRegistrationStatus(2);
            registration.setOwnerType(ownerType);
        }else{
            // status 可空:投诉页等只传 owner_type=3 不传 status,等同文档「0全部」;
            // 常量在前,避免 status=null 时 String.equals NPE。
            if ("1".equals(status)){
                registration.setRegistrationStatus(1);
            }else  if ("2".equals(status)){
                registration.setRegistrationStatus(2);
            }else  if ("3".equals(status)){
                registration.setRegistrationStatus(2);
            }else  if ("4".equals(status)){
                registration.setRegistrationStatus(3);
            }
        }
        if (is_online!=null && is_online.intValue()==1){
            registration.setIsOnline(is_online.intValue());
        }
        registration.setMemberId(member_id);

        List<CmsRegistration> list;
        //startPage();
        list = registrationService.selectCmsRegistrationList(registration);
        if (ownerType >= 3 && ("2".equals(status) || "3".equals(status))) {
            List<CmsRegistration> filtered = new ArrayList<>();
            for (CmsRegistration row : list) {
                boolean completed = (Integer.valueOf(2).equals(row.getPaymentStatus())
                        && row.getFirstSuccessfulScanAt() != null)
                        || Integer.valueOf(1).equals(row.getVerificationStatus());
                if ("3".equals(status) == completed) filtered.add(row);
            }
            list = filtered;
        }
        Map<Long, RefundApplication> latestRefunds = latestRegistrationRefunds(member_id);
        for (CmsRegistration reg : list) {
            if (reg.getOwnerType() == 1) {
                CmsTopic topic = topicService.selectCmsTopicById(reg.getOwnerId());
                if (topic != null) {
                    // 2026-09-18 UI-20 返修:存量主题 cms_topic.product_type 仍是 NULL 时,订单卡按它出
                    // 「城市定向/自由探索」会整块标签消失;而游玩页 resolveMode 对同一主题已按票 mode 回落
                    // 出「城市定向」,两边自相矛盾。这里复用同一条读侧回落规则,只改响应不下库。
                    if (topic.getProductType() == null) {
                        topic.setProductType(readTopicProductType(topic.getId()));
                    }
                    reg.setCmsTopic(topic);
                }
            }
            if (reg.getOwnerType() == 2) {
                CmsActivity activity = activityService.selectCmsActivityById(reg.getOwnerId());
                if (activity != null) {
                    reg.setCmsActivity(activity);
                }
            }
            attachRefundInfoForList(reg);
            attachManualRefundInfo(reg);
            reg.setRefundApplication(latestRefunds.get(reg.getId()));
        }

        // ⚠️ 这里**不再**套 filterEndedOverWeek(2026-09-16 截图冒烟实证):
        // 本接口就是「我的订单」账本,客户端七态 Tab 里的「已完成 / 已退款 / 不可退款」天生指结束过的单;
        // 按 owner.end_date 隐藏结束超 7 天的单,会让这些 Tab 恒空 —— 真实商家号(有已支付订单)打开看到整屏空列表。
        // 7 天视觉收纳只为「我参与中」(myJoined,进行中 Tab)保留,那条链路独立调用该 helper。
        stampServerNow(list);
        TableDataInfo data = getDataTable(list);
        return AjaxResult.success(data);
    }

    /** U-033:订单状态标签(未开始/进行中/已完成)按服务端时刻判,手机时钟只作缺字段时的兜底。 */
    private static void stampServerNow(List<CmsRegistration> rows) {
        if (rows == null) return;
        long now = System.currentTimeMillis();
        for (CmsRegistration row : rows) {
            if (row != null) row.setServerNow(now);
        }
    }

    /** 存量主题未写 product_type 时，与游玩页一致地按第一张票的 mode 回落。 */
    private Integer readTopicProductType(Long topicId) {
        if (topicId == null) {
            return ProductType.ORIENTEERING;
        }
        com.chengyinhub.business.domain.OmsTicket probe = new com.chengyinhub.business.domain.OmsTicket();
        probe.setOwnerType(1L);
        probe.setOwnerId(topicId);
        List<com.chengyinhub.business.domain.OmsTicket> tickets = omsTicketService.selectOmsTicketList(probe);
        if (tickets != null) {
            for (com.chengyinhub.business.domain.OmsTicket ticket : tickets) {
                if (ticket != null && ticket.getMode() != null) {
                    return ticket.getMode();
                }
            }
        }
        return ProductType.ORIENTEERING;
    }

    /** FX-02:同单部分核销,本次未退款、已记人工退款工单;字段齐全,小程序才会原样展示这句 msg。 */
    private AjaxResult manualReviewFeedback(Long registrationId, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("registrationId", registrationId);
        data.put("cancellationStatus", "MANUAL_REVIEW");
        data.put("cashRefundStatus", "MANUAL_REVIEW");
        data.put("cashRefundAmount", null);
        return AjaxResult.success(message, data);
    }

    /** 结算拒绝原样映射 code/msg；带机器码的（DOC2 F7 未成年挂点 MINOR_*）再附 errorCode。 */
    private AjaxResult checkoutError(RegistrationCheckoutException e) {
        AjaxResult result = new AjaxResult(e.getCode(), e.getMessage());
        if (e.getErrorCode() != null) result.put("errorCode", e.getErrorCode());
        return result;
    }

    private AjaxResult cancellationFeedback(Long registrationId) {
        com.chengyinhub.business.service.support.RegistrationCancellationReadService.Result result =
                cancellationReadService.read(registrationId);
        return AjaxResult.success(result.getMessage(), result.getData());
    }

    /** 只按报名所属玩家和本报名单查询最新退款单，打款状态不能由 registrationStatus 推断。 */
    private RefundApplication latestRegistrationRefund(Long registrationId, Long memberId) {
        if (registrationId == null || memberId == null || memberId <= 0) {
            return null;
        }
        RefundApplication query = new RefundApplication();
        query.setSourceType("registration");
        query.setSourceId(registrationId);
        query.setMemberId(memberId);
        List<RefundApplication> rows = refundService.selectRefundList(query);
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    /** 订单列表一次取回当前玩家的退款单，按 mapper 的倒序结果保留每笔报名最新一条，避免 N+1 查询。 */
    private Map<Long, RefundApplication> latestRegistrationRefunds(Long memberId) {
        Map<Long, RefundApplication> result = new HashMap<>();
        if (memberId == null || memberId <= 0) {
            return result;
        }
        RefundApplication query = new RefundApplication();
        query.setSourceType("registration");
        query.setMemberId(memberId);
        List<RefundApplication> rows = refundService.selectRefundList(query);
        if (rows == null) {
            return result;
        }
        for (RefundApplication row : rows) {
            if (row != null && row.getSourceId() != null && !result.containsKey(row.getSourceId())) {
                result.put(row.getSourceId(), row);
            }
        }
        return result;
    }

    /** 订单详情只投影公开昵称，避免把主办方完整会员资料(手机号等)暴露给报名者。 */
    private void attachOrganizer(CmsRegistration registration) {
        if (registration == null) {
            return;
        }
        Long organizerId = null;
        if (registration.getCmsTopic() != null) {
            organizerId = registration.getCmsTopic().getMemberId();
        } else if (registration.getCmsActivity() != null) {
            organizerId = registration.getCmsActivity().getMemberId();
        }
        if (organizerId == null || organizerId <= 0) {
            return;
        }
        UmsMember organizer = umsMemberService.selectUmsMemberById(organizerId);
        if (organizer != null && !StringUtils.isEmpty(organizer.getNickname())) {
            registration.setOrganizerName(organizer.getNickname());
        }
    }

    /** 列表也复用订单详情的退款口径，前端据此展示不可退款而非再按固定 24 小时猜测。 */
    private void attachRefundInfoForList(CmsRegistration registration) {
        if (registration == null || registration.getRegistrationStatus() == null
                || registration.getRegistrationStatus() != 2) {
            return;
        }
        if (registration.getOwnerType() != null && registration.getOwnerType() == 1L) {
            CmsTopic topic = registration.getCmsTopic() != null ? registration.getCmsTopic()
                    : topicService.selectCmsTopicById(registration.getOwnerId());
            com.chengyinhub.business.domain.OmsTicket ticket = registration.getTicketId() == null
                    ? null : omsTicketService.selectOmsTicketById(registration.getTicketId());
            if (topic != null) {
                attachRefundVerdict(registration,
                        com.chengyinhub.business.util.RefundPolicy.evaluateTopicFulfillmentWindow(
                                registration.getPaymentStatus(), registration.getVerificationStatus(), topic, ticket, new Date()));
            }
            return;
        }
        if (registration.getOwnerType() != null && registration.getOwnerType() == 2L
                && registration.getCmsActivity() != null) {
            attachRefundVerdict(registration,
                    com.chengyinhub.business.util.RefundPolicy.evaluate(
                            2L, registration.getPaymentStatus(), registration.getVerificationStatus(),
                            null, null, null, registration.getCmsActivity().getStartDate(),
                            registration.getPurchaseKind(), registration.getRefundDeadline(),
                            registration.getPayableAmount(), registration.getPointUsed(), new Date()));
        }
    }

    /**
     * 退款判定结果落到详情/列表上的唯一出口。
     *
     * <p>⚠️ {@code manualReview}(算不出来)这一档**没有真队列** —— 判定若只塞进展示就丢了,
     * 用户看到一句「需人工核实,请联系客服」,平台侧却零观测。这里先做到「不静默」:
     * 每有一条转人工的单被玩家读到,就打一条可 grep 的 {@code [退款转人工]} 日志
     * (玩家侧的取消按钮会被客户端提前拦住,所以读侧是后端唯一能看到这些单的地方)。
     */
    private void attachRefundVerdict(CmsRegistration registration,
                                     com.chengyinhub.business.util.RefundPolicy.Verdict verdict) {
        if (verdict != null && verdict.manualReview) {
            logger.warn("[退款转人工] registrationId={} ownerType={} ownerId={} reason={}",
                    registration.getId(), registration.getOwnerType(), registration.getOwnerId(),
                    verdict.reason);
        }
        // 1-21 F07:政策文案不看金额,零元单没有钱可「原路退回」。只改单张单(无父单号):
        // 多票父单的现金挂在首单上,从单自身金额不代表整单有没有付钱。
        // ponytail: 零元多票父单仍显示原文案,要改需按父单号查首单
        // 存量单 wechat_payment_amount 可能为空 ⇒ 回落应付额;两者都空就不下「零元」结论。
        java.math.BigDecimal cash = registration.getWechatPaymentAmount() != null
                ? registration.getWechatPaymentAmount() : registration.getPayableAmount();
        if (verdict != null && verdict.refundable && StringUtils.isEmpty(registration.getParentOrderNo())
                && cash != null && cash.signum() <= 0) {
            boolean usedPoints = registration.getPointUsed() != null && registration.getPointUsed() > 0;
            verdict = new com.chengyinhub.business.util.RefundPolicy.Verdict(true,
                    usedPoints ? "可免费取消,已用积分退回账户" : "可免费取消", verdict.deadline, verdict.manualReview);
        }
        registration.setRefundInfo(verdict);
    }

    private void attachManualRefundInfo(CmsRegistration registration) {
        if (registration != null && StringUtils.isNotEmpty(registration.getParentOrderNo())) {
            cancellationReadService.attachManualRefundInfo(registration);
        }
    }

    /** 「我参与中」(myJoined)列表:活动/主题结束满 N 天后从列表隐藏该单的窗口(天)。仅影响该列表展示,不删除数据。 */
    static final int HIDE_AFTER_END_DAYS = 7;

    /**
     * 过滤「我参与中」列表(myJoined):owner(主题/活动)结束时间早于 now - HIDE_AFTER_END_DAYS 天的单不返回。
     * 结束时间未知(end_date 为 null 或 owner 未挂载)一律保留 —— 保守处理,避免误隐藏。
     * ⚠️ 只给 myJoined 用;订单列表(myRegistrationList)是账本,不能套(见方法内注释)。
     */
    static List<CmsRegistration> filterEndedOverWeek(List<CmsRegistration> list, Date now) {
        if (list == null || list.isEmpty()) {
            return list;
        }
        long cutoff = now.getTime() - HIDE_AFTER_END_DAYS * 24L * 60L * 60L * 1000L;
        List<CmsRegistration> visible = new ArrayList<>();
        for (CmsRegistration reg : list) {
            Date endDate = resolveOwnerEndDate(reg);
            if (endDate != null && endDate.getTime() < cutoff) {
                continue; // 结束超过 N 天 → 隐藏
            }
            visible.add(reg);
        }
        return visible;
    }

    /** 取 owner 结束时间:owner_type=1 主题→cms_topic.end_date;=2 活动→cms_activity.end_date;其余/未挂载→null。 */
    private static Date resolveOwnerEndDate(CmsRegistration reg) {
        if (reg == null || reg.getOwnerType() == null) {
            return null;
        }
        long ownerType = reg.getOwnerType();
        if (ownerType == 1L && reg.getCmsTopic() != null) {
            return reg.getCmsTopic().getEndDate();
        }
        if (ownerType == 2L && reg.getCmsActivity() != null) {
            return reg.getCmsActivity().getEndDate();
        }
        return null;
    }


    /**
     * 我参与中（聚合主题+活动）
     */
    @Operation(summary = "我参与中的主题和活动（进行中Tab）")
    @PostMapping("/my-joined")
    public AjaxResult myJoined() {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }

        List<CmsRegistration> all = new ArrayList<>();
        for (long type = 1; type <= 2; type++) {
            CmsRegistration q = new CmsRegistration();
            q.setMemberId(memberId);
            q.setRegistrationStatus(2); // 已支付/进行中
            q.setOwnerType(type);
            all.addAll(registrationService.selectCmsRegistrationList(q));
        }

        Map<Long, RefundApplication> latestRefunds = latestRegistrationRefunds(memberId);
        // 附加主题/活动详情与真实退款资格；首次扫码字段由统一 registration mapper 读回。
        for (CmsRegistration reg : all) {
            if (reg.getOwnerType() == 1) {
                CmsTopic topic = topicService.selectCmsTopicById(reg.getOwnerId());
                if (topic != null) reg.setCmsTopic(topic);
            } else if (reg.getOwnerType() == 2) {
                CmsActivity activity = activityService.selectCmsActivityById(reg.getOwnerId());
                if (activity != null) reg.setCmsActivity(activity);
            }
            attachRefundInfoForList(reg);
            attachManualRefundInfo(reg);
            reg.setRefundApplication(latestRefunds.get(reg.getId()));
        }

        all = filterEndedOverWeek(all, new Date());
        all.sort((a, b) -> b.getCreateTime().compareTo(a.getCreateTime()));
        stampServerNow(all);
        return AjaxResult.success(all);
    }

    /** 核销码密钥:与 ApiVerifyController 签发同源(默认 JWT secret,可用 verify.dyncode-secret 覆盖) */
    @Value("${verify.dyncode-secret:${token.secret}}")
    private String dynSecret;

    /**
     * 把扫到的 code 解析成 CmsRegistration(BE-11):先试动态签名码(verify 通过→按 regId 查),
     * v1 动态码失败保留校验原因；只有旧静态码进入 verificationCode 查询。
     * 选择章节/据点期间过期不会被误报为无效码，不改核销/结算主逻辑。
     */
    private CmsRegistration resolveRegistrationByScanCode(String code) {
        VerifyDynCodeUtil.Result r = VerifyDynCodeUtil.verify(code, System.currentTimeMillis(), dynSecret);
        if (r.valid && r.regId != null) {
            return registrationService.selectCmsRegistrationById(r.regId);
        }
        if (code != null && code.startsWith("v1.")) {
            throw new ServiceException(r.reason);
        }
        return registrationService.selectByverificationCode(code);
    }

    /** 俱乐部场次整单核销的前置窗口，与集合签到(ClubGatherArrivalPolicy)同为开场前 60 分钟。 */
    private static final long CLUB_SESSION_VERIFY_LEAD_MS = 60L * 60L * 1000L;

    /**
     * F-R2F-01:俱乐部场次的票不能在开场前几天被整单核销 —— 核销即「已到场/已交付」，
     * 提前核销后场次取消就会落入「整单全核销不退」。只约束俱乐部场次的整单核销，③ 按章核销与普通活动不变。
     *
     * @return null = 放行；非 null = 直接返回给扫码方的提示
     */
    static AjaxResult clubSessionVerificationTooEarly(CmsActivity activity, Date now) {
        if (activity == null || activity.getClubId() == null || activity.getClubId() <= 0
                || activity.getStartDate() == null || now == null) {
            return null;
        }
        if (activity.getStartDate().getTime() - now.getTime() > CLUB_SESSION_VERIFY_LEAD_MS) {
            return AjaxResult.error("俱乐部场次开始前60分钟内才可核销");
        }
        return null;
    }

    /**
     * ★★ [资损·根因] {@code type} 与 {@code registration.owner_type} 必须一致。
     *
     * <p><b>为什么这是根因而不是防御性编程</b>:{@code scanQrCode(String type, String code)} 的
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
     * <b>operatorMemberId = getAppUserId()</b>,只记录真实操作人。绝不能把 principal、
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
    @Operation(summary = "③ 按指定章节核销(交集≥2 时商家选章后调用)")
    @PostMapping("/scan_qr_code_chapter")
    @Parameters({
            @Parameter(name = "code", description = "核销码(与 scan_qr_code 同一个)"),
            @Parameter(name = "chapterId", description = "从 scan_qr_code 返回的 chapterIds 里选的那一个")
    })
    public AjaxResult scanQrCodeChapter(String code, Long chapterId) {
        Long principalMemberId;
        try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        if (principalMemberId == null || principalMemberId <= 0) return error("请先登录");
        if (code == null || chapterId == null) return error("参数缺失");

        MerchantAccessContext access;
        try {
            access = merchantAccessService.require(principalMemberId, MerchantPermission.VERIFY);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
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
    @Operation(summary = "城市定向按所选据点核销")
    @PostMapping("/scan_qr_code_station")
    @Parameters({
            @Parameter(name = "code", description = "核销码(与 scan_qr_code 同一个)"),
            @Parameter(name = "registrationMerchantId", description = "stations 中明确选择的中标记录ID")
    })
    public AjaxResult scanQrCodeStation(String code, Long registrationMerchantId) {
        Long principalMemberId;
        try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        if (principalMemberId == null || principalMemberId <= 0) return error("请先登录");
        if (StringUtils.isEmpty(code) || registrationMerchantId == null) return error("参数缺失");

        MerchantAccessContext access;
        try {
            access = merchantAccessService.require(principalMemberId, MerchantPermission.VERIFY);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
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
        // 通关副作用已在 redeemExploreChapterAndRecord 的同一事务收口；这里不再吞异常或重复发奖。

        if (redemption.getCompletionEarnedXp() > 0) {
            d.put("completionEarnedXp", redemption.getCompletionEarnedXp());
        }
        return AjaxResult.success("核销成功", d);
    }

    /**
     * 扫描二维码
     */
    @Operation(summary = "扫描玩家动态核销码")
    @PostMapping("/scan_dynamic_code")
    @Parameter(name = "code", description = "玩家动态核销码")
    public AjaxResult scanDynamicCode(String code) {
        if (StringUtils.isEmpty(code)) {
            return AjaxResult.error("核销码无效");
        }
        VerifyDynCodeUtil.Result result = VerifyDynCodeUtil.verify(code, System.currentTimeMillis(), dynSecret);
        if (!result.valid) {
            return AjaxResult.error(result.reason == null ? "核销码无效" : result.reason);
        }
        // type 来自 HMAC 已验签的码，不能再收小程序回传的 type；团码/据点码各走自己的专用端点。
        if (!"activity".equals(result.type) && !"topic".equals(result.type)) {
            return AjaxResult.error("非玩家动态核销码");
        }
        return scanQrCode(result.type, code);
    }

    @Operation(summary = "带队场次核销主题玩家票")
    @PostMapping("/scan_group_member_ticket")
    @Parameters({
            @Parameter(name = "code", description = "主题玩家票核销码"),
            @Parameter(name = "activityId", description = "当前带队场次ID")
    })
    public AjaxResult scanGroupMemberTicket(String code, Long activityId) {
        if (StringUtils.isEmpty(code)) {
            return AjaxResult.error("核销码无效");
        }
        // 动态码的 type 只能信任服务端验签结果；活动票属于 D9 全团结算，不能落入 D22 主题商家分账。
        if (code.startsWith("v1.")) {
            VerifyDynCodeUtil.Result result = VerifyDynCodeUtil.verify(code, System.currentTimeMillis(), dynSecret);
            if (!result.valid) {
                return AjaxResult.error(result.reason == null ? "核销码无效" : result.reason);
            }
            if (!"topic".equals(result.type)) {
                return AjaxResult.error("本场次只可核销主题票");
            }
        }
        // 静态码没有可验签 type，仍由 scanQrCode 的 owner_type 断言拒绝活动票。
        return scanQrCode("topic", code, activityId);
    }

    @Operation(summary = "扫描二维码")
    @PostMapping("/scan_qr_code")
    @Parameters({
            @Parameter(name = "type", description = "核销类型coupon/topic/activity"),
            @Parameter(name = "code", description = "核销码/优惠券码(动态签名码或旧静态码)"),
            @Parameter(name = "activityId", description = "带队场次ID；仅组织槽主题玩家票核销必填")
    })
    public AjaxResult scanQrCode(String type, String code, Long activityId) {
        try {
            // 获取当前登录用户ID
            Long memberId = 0L;
            try {
                memberId = getAppUserId();
            } catch (Exception e) {

            }
            if (memberId <= 0) {
                return error("请先登录");
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
                    return tooEarly;
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
                            // 规则 1：同主题不同 activity 是不同团。核销事务里必须把报名单绑定当前场次，
                            // 结算只认这条事实，绝不再从 topic + 核销人猜归属。
                            verified = registrationService.verifyAndSettleForGroupActivity(cmsRegistration,
                                    memberId, activityId);
                        } else {
                            if (isWinner) {
                                // 走到这里必然只有一个中标行(多个的在上面就返回候选让商家选了)。
                                // 这一行是**服务端**按 memberId+topicId+audit_status+del_flag 查出来的,
                                // 客户端指定不了 ⇒ 不必再回读复核,沿用原核销行为,只额外落据点归属。
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
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    /** 保持 Java 调用方/既有测试的两参数契约；HTTP 入口由三参数方法接收可选 activityId。 */
    public AjaxResult scanQrCode(String type, String code) {
        return scanQrCode(type, code, null);
    }

    private AjaxResult duplicateVerificationResult() {
        Map<String, Object> data = new HashMap<>();
        data.put("alreadyVerified", true);
        return AjaxResult.error("该订单已核销", data);
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
        if (topicId == null || memberId == null) {
            return false;
        }
        for (CoopOrder o : coopOrderMapper.selectEffectiveOrdersByTopicId(topicId)) {
            if ("merchant".equals(o.getToType()) && memberId.equals(o.getToId())) {
                return true;
            }
        }
        return false;
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

    /**
     * 支付回调通知
     */
    @Operation(summary = "微信支付回调")
    @PostMapping("/callback")
    @Anonymous
    public ResponseEntity<?> payCallback(@RequestBody String requestBody,
                                         @RequestHeader Map<String, String> headers) {
        return callbackResponse(() -> wechatPayService.handlePayCallback(requestBody, headers));
    }

    /**
     * 退款结果通知回调(§6.1 P3):异步回写退款打款终态。
     * notify_url 配 wx.pay.refund-notify-url 指向本端点,或在商户平台配退款回调地址。
     */
    @Operation(summary = "微信退款结果回调")
    @PostMapping("/refund-callback")
    @Anonymous
    public ResponseEntity<?> refundCallback(@RequestBody String requestBody,
                                            @RequestHeader Map<String, String> headers) {
        return callbackResponse(() -> wechatPayService.handleRefundCallback(requestBody, headers));
    }

    private ResponseEntity<?> callbackResponse(Supplier<String> callback) {
        try {
            Map<String, Object> ack = JSON.parseObject(callback.get());
            if (ack != null && "SUCCESS".equals(ack.get("code"))) {
                return ResponseEntity.noContent().build();
            }
        } catch (Exception e) {
            logger.warn("支付或退款通知尚未确认，返回失败应答: {}", e.getClass().getSimpleName());
        }
        // 微信按 HTTP 状态决定是否重试，FAIL 正文不能配成功状态。
        return ResponseEntity.status(500)
                .body(Map.of("code", "FAIL", "message", "通知处理尚未确认"));
    }
}
