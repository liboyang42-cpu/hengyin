package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.constant.HttpStatus;
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
import com.chengyinhub.common.config.ComplianceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private com.chengyinhub.web.service.registration.RegistrationQueryApplication registrationQueryApplication;

    @Autowired
    private com.chengyinhub.web.service.registration.RegistrationRedemptionApplication registrationRedemptionApplication;

    @Autowired
    private com.chengyinhub.business.service.ITeamGroupService teamGroupService;
    @Autowired
    private ICmsRegistrationService registrationService;
    @Autowired
    private IRegistrationCheckoutService registrationCheckoutService;

    @Autowired
    private IConsentRecordService consentRecordService;

    @Autowired
    private ComplianceProperties complianceProperties;

    private static final String SIGNUP_DATA_SHARING_DOC = "activity_host_data_sharing";
    private static final String SIGNUP_DATA_SHARING_SCENE = "activity_signup";

    @Autowired
    private IWechatPayService wechatPayService;

    @Autowired
    private IRefundService refundService;

    @Autowired
    private com.chengyinhub.business.service.support.RegistrationCancellationReadService cancellationReadService;

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
        Long memberId = 0L;
        try { memberId = getAppUserId(); } catch (Exception e) { }
        return registrationQueryApplication.getRegistrationDetail(memberId, id);
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
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        return registrationQueryApplication.exploreCompletion(memberId, id);
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
        Long memberId = 0L;
        try { memberId = getAppUserId(); } catch (Exception e) { }
        return registrationQueryApplication.getJoinRegistrationDetail(memberId, member_id);
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
        Long principalMemberId;
        try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        return registrationQueryApplication.countByDate(principalMemberId, owner_type, owner_id);
    }

    @Operation(summary = "我的报名")
    @PostMapping("/list")
    @Parameters({
            @Parameter(name = "owner_type", description = "类型1、主题  2、活动  3、全部"),
            @Parameter(name = "status", description = "0全部 1待支付 2待使用 3已完成 4已取消"),
            @Parameter(name = "is_online", description = "0全部 1未结束"),
    })
    public AjaxResult myRegistrationList(String owner_type,String status,Integer is_online ) {
        Long member_id;
        try { member_id = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        AjaxResult result = registrationQueryApplication.myRegistrationList(member_id, owner_type, status, is_online);
        // Preserve the original HTTP-thread PageInfo/TableDataInfo boundary, without enabling startPage.
        if (Integer.valueOf(HttpStatus.SUCCESS).equals(result.get(AjaxResult.CODE_TAG))) {
            result.put(AjaxResult.DATA_TAG, getDataTable((List<?>) result.get(AjaxResult.DATA_TAG)));
        }
        return result;
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




















    /**
     * 我参与中（聚合主题+活动）
     */
    @Operation(summary = "我参与中的主题和活动（进行中Tab）")
    @PostMapping("/my-joined")
    public AjaxResult myJoined() {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        return registrationQueryApplication.myJoined(memberId);
    }

    @Operation(summary = "③ 按指定章节核销(交集≥2 时商家选章后调用)")
    @PostMapping("/scan_qr_code_chapter")
    @Parameters({
            @Parameter(name = "code", description = "核销码(与 scan_qr_code 同一个)"),
            @Parameter(name = "chapterId", description = "从 scan_qr_code 返回的 chapterIds 里选的那一个")
    })
    public AjaxResult scanQrCodeChapter(String code, Long chapterId) {
        return registrationRedemptionApplication.scanQrCodeChapter(redemptionActor(), code, chapterId);
    }

    @Operation(summary = "城市定向按所选据点核销")
    @PostMapping("/scan_qr_code_station")
    @Parameters({
            @Parameter(name = "code", description = "核销码(与 scan_qr_code 同一个)"),
            @Parameter(name = "registrationMerchantId", description = "stations 中明确选择的中标记录ID")
    })
    public AjaxResult scanQrCodeStation(String code, Long registrationMerchantId) {
        return registrationRedemptionApplication.scanQrCodeStation(redemptionActor(), code, registrationMerchantId);
    }

    @Operation(summary = "扫描玩家动态核销码")
    @PostMapping("/scan_dynamic_code")
    @Parameter(name = "code", description = "玩家动态核销码")
    public AjaxResult scanDynamicCode(String code) {
        return registrationRedemptionApplication.scanDynamicCode(redemptionActor(), code);
    }

    @Operation(summary = "带队场次核销主题玩家票")
    @PostMapping("/scan_group_member_ticket")
    @Parameters({
            @Parameter(name = "code", description = "主题玩家票核销码"),
            @Parameter(name = "activityId", description = "当前带队场次ID")
    })
    public AjaxResult scanGroupMemberTicket(String code, Long activityId) {
        return registrationRedemptionApplication.scanGroupMemberTicket(redemptionActor(), code, activityId);
    }

    @Operation(summary = "扫描二维码")
    @PostMapping("/scan_qr_code")
    @Parameters({
            @Parameter(name = "type", description = "核销类型coupon/topic/activity"),
            @Parameter(name = "code", description = "核销码/优惠券码(动态签名码或旧静态码)"),
            @Parameter(name = "activityId", description = "带队场次ID；仅组织槽主题玩家票核销必填")
    })
    public AjaxResult scanQrCode(String type, String code, Long activityId) {
        return registrationRedemptionApplication.scanQrCode(redemptionActor(), type, code, activityId);
    }

    /** 保持 Java 调用方/既有测试的两参数契约。 */
    public AjaxResult scanQrCode(String type, String code) {
        return scanQrCode(type, code, null);
    }

    /** Authentication adaptation only; the application retains each original admission check. */
    private com.chengyinhub.web.service.registration.RegistrationRedemptionApplication.Actor redemptionActor() {
        try {
            return new com.chengyinhub.web.service.registration.RegistrationRedemptionApplication.Actor(getAppUserId(), false);
        } catch (Exception unauthenticated) {
            return new com.chengyinhub.web.service.registration.RegistrationRedemptionApplication.Actor(null, true);
        }
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
