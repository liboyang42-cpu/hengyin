package com.chengyinhub.web.controller.api;

import java.util.LinkedHashMap;
import com.chengyinhub.business.service.IMerchantClawbackService;
import com.chengyinhub.business.domain.MerchantClawbackApplication;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantOperationScope;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.dto.DispatchResult;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.MerchantCircleSupplyProfile;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IMerchantOfferService;
import com.chengyinhub.business.service.IDispatchService;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopPerkTemplate;
import com.chengyinhub.business.domain.CoopReview;
import com.chengyinhub.business.domain.CoopPerk;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.vo.CoopPartnerVO;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.UserSafeMessage;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.chengyinhub.business.domain.dto.CoopInviteRequestResult;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.common.utils.OwnershipUtils;
/**
 * 协作邀请(小程序端,P1.5)。
 *
 * 自审核握手:发起方发起(status=0)+ 受邀方同意(status=1)= 通过,平台不进 web 审核。
 * 新建类型只允许 0 主题发布者→商家 / 1 主题发布主体→俱乐部；历史 type2 仅只读兼容。
 */
@RestController
@Tag(name = "协作邀请")
@RequestMapping("/api/coop")
public class ApiCoopController extends BaseController {
    private static final BigDecimal MAX_PERK_AMOUNT = new BigDecimal("99999999.99");

    private static final Logger log = LoggerFactory.getLogger(ApiCoopController.class);

    @Autowired
    private ICoopInviteService coopInviteService;

    @Autowired
    private IMerchantOfferService merchantOfferService;

    @Autowired
    private IMerchantAccessService merchantAccessService;

    @Autowired
    private com.chengyinhub.business.service.ICoopComplaintService coopComplaintService;

    @Autowired
    private com.chengyinhub.business.service.ICmsRegistrationService cmsRegistrationService;

    @Autowired
    private ICmsActivityService cmsActivityService;

    @Autowired
    private IClubService clubService;

    @Autowired
    private IImService imService;

    @Autowired
    private ICmsRegistrationMerchantService cmsRegistrationMerchantService;

    @Autowired
    private IDispatchService dispatchService;

    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired
    private INotifyService notifyService;

    @Autowired
    private com.chengyinhub.business.service.ICoopClubApplyService coopClubApplyService;

    @Autowired
    private IUmsMemberService umsMemberService;
    @Autowired
    private com.chengyinhub.business.service.IWxContentSecurityService wxContentSecurityService;

    @Autowired
    private com.chengyinhub.business.service.IMmsMerchantService mmsMerchantService;

    @Autowired
    private com.chengyinhub.business.service.IWechatPayService wechatPayService;

    @Autowired
    private com.chengyinhub.business.service.impl.AlarmService alarmService;

    /** 锁价后保证金缴纳:仅当前应缴方可为其已接受合作拉起 ¥50 微信支付。 */
    @PostMapping("/deposit/create")
    public AjaxResult depositCreate(@RequestBody java.util.Map<String, Object> body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (!wechatPayService.isReady()) return new AjaxResult(503, "支付服务暂不可用，请稍后再试");
        Long inviteId = body == null ? null : toLong(body.get("inviteId"));
        if (inviteId == null) return error("缺少邀约");
        try {
            UmsMember payer = umsMemberService.selectUmsMemberById(memberId);
            String openid = payer == null ? null : payer.getWxOpenId();
            if (openid == null || openid.isEmpty()) return error("缺少微信身份,无法支付");
            java.util.Map<String, Object> prep = coopInviteService.prepareDeposit(inviteId, memberId);
            java.util.Map<String, String> payParams = wechatPayService.createGenericOrder(
                    (String) prep.get("outTradeNo"),
                    (java.math.BigDecimal) prep.get("amount"),
                    "城市定向合作保证金", openid);
            return AjaxResult.success(payParams);
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            return error("发起保证金支付失败");
        }
    }

    /** 支付 SDK success 后只以服务端保证金状态为终态，且仅应缴方可回读。 */
    @PostMapping("/deposit/status")
    public AjaxResult depositStatus(@RequestBody java.util.Map<String, Object> body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        Long inviteId = body == null ? null : toLong(body.get("inviteId"));
        if (inviteId == null) return error("缺少邀约");
        CoopInvite invite = coopInviteService.selectCoopInviteById(inviteId);
        Long payer = invite == null ? null : coopInviteService.depositOwedParty(invite);
        if (invite == null || !Integer.valueOf(0).equals(invite.getDelFlag()) || !memberId.equals(payer)) {
            return error("无权查看该保证金状态");
        }
        if (Integer.valueOf(1).equals(invite.getDepositStatus()) && invite.getDepositOutTradeNo() != null) {
            try {
                String originalNo = invite.getDepositOutTradeNo();
                java.util.Map<String, String> facts = wechatPayService.queryOrderStatus(originalNo);
                if (facts != null && originalNo.equals(facts.get("out_trade_no"))
                        && "SUCCESS".equals(facts.get("trade_state")) && "CNY".equals(facts.get("currency"))
                        && facts.get("total") != null && facts.get("total").matches("[0-9]{1,10}")) {
                    // Gateway verifies original merchant/app/channel; domain keeps amount, transaction binding and CAS.
                    if (!coopInviteService.markDepositPaid(originalNo, Long.valueOf(facts.get("total")), facts.get("transaction_id"),
                            new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(
                                    Long.parseLong(facts.get("verified_fact_id")),facts.get("verified_fact_hash")))) {
                        return new AjaxResult(409, "支付已成功，本地确认尚未完成，请稍后重试");
                    }
                    invite = coopInviteService.selectCoopInviteById(inviteId);
                    if (invite == null) return new AjaxResult(409, "保证金状态暂不可确认");
                }
            } catch (Exception unknown) {
                return new AjaxResult(409, "支付结果确认中，请稍后重试");
            }
        }
        Integer status = invite.getDepositStatus();
        String paymentStatus;
        if (Integer.valueOf(2).equals(status)
                || ((Integer.valueOf(3).equals(status) || Integer.valueOf(4).equals(status))
                    && invite.getDepositPaidTransactionId() != null
                    && !invite.getDepositPaidTransactionId().trim().isEmpty())) {
            paymentStatus = "success";
        } else if (Integer.valueOf(0).equals(status) || Integer.valueOf(5).equals(status)) {
            paymentStatus = "failed";
        } else if (Integer.valueOf(1).equals(status)) {
            paymentStatus = "pending";
        } else {
            paymentStatus = "unknown";
        }
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("inviteId", inviteId);
        result.put("paymentStatus", paymentStatus);
        return success(result);
    }

    /** 原生 App 保证金支付:复用同一应缴方与锁价约束,仅切换微信 APP 下单通道。 */
    @PostMapping("/deposit/create/app")
    public AjaxResult depositCreateApp(@RequestBody java.util.Map<String, Object> body) {
        Long memberId;
        try {
            memberId = currentMemberId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (memberId == null || memberId <= 0) return error("请先登录");
        if (!wechatPayService.isAppReady()) return new AjaxResult(503, "App 支付服务暂不可用，请稍后再试");
        Long inviteId = body == null ? null : toLong(body.get("inviteId"));
        if (inviteId == null) return error("缺少邀约");
        try {
            java.util.Map<String, Object> prep = coopInviteService.prepareDeposit(inviteId, memberId);
            java.util.Map<String, String> payParams = wechatPayService.createGenericAppOrder(
                    (String) prep.get("outTradeNo"),
                    (java.math.BigDecimal) prep.get("amount"),
                    "城市定向合作保证金");
            return AjaxResult.success(payParams);
        } catch (com.chengyinhub.business.service.PaymentPreorderException e) {
            String message = e.isResultUncertain()
                    ? "支付结果确认中，请稍后查看，勿重复支付"
                    : UserSafeMessage.of(e.getMessage(), "发起保证金支付失败");
            return new AjaxResult(e.getCode(), message);
        } catch (IllegalStateException e) {
            return error(UserSafeMessage.of(e.getMessage(), "发起保证金支付失败"));
        } catch (Exception e) {
            return error("发起保证金支付失败");
        }
    }

    /** 锁价前取消时,保证金处于冻结(2)则原路退还付款方并置已退(4)。 */
    private boolean refundDepositIfHeld(Long inviteId, String reason) {
        // 取消事务已持久记录退款意图；这里只回读，不能再发第二次渠道退款。
        CoopInvite row = coopInviteService.selectCoopInviteById(inviteId);
        if (row == null || row.getDepositStatus() == null
                || Integer.valueOf(CoopInvite.TYPE_LEGACY_MERCHANT_NODE).equals(row.getInviteType())) return true;
        return !Integer.valueOf(2).equals(row.getDepositStatus());
    }

    private String nickOf(Long memberId) {
        try {
            if (memberId == null) return "";
            UmsMember m = umsMemberService.selectUmsMemberById(memberId);
            return m == null || m.getNickname() == null ? "" : m.getNickname();
        } catch (Exception e) { return ""; }
    }

    private String topicNameOf(Long topicId) {
        try {
            if (topicId == null) return "";
            CmsTopic t = cmsTopicService.selectCmsTopicById(topicId);
            return t == null || t.getName() == null ? "" : t.getName();
        } catch (Exception e) { return ""; }
    }

    private Long currentMemberId() {
        return getAppUserId();
    }

    @Operation(summary = "发起协作邀请/申请")
    @PostMapping("/invite")
    public AjaxResult invite(@RequestBody CoopInvite body) {
        Long principalMemberId;
        try { principalMemberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body == null) return error("缺少邀约参数");
        if (body.getInviteType() == null || body.getInviteType() < 0 || body.getInviteType() > 1) {
            return error("邀请类型不合法");
        }
        if (body.getToId() == null || body.getToType() == null || body.getToType().trim().isEmpty()) {
            return error("缺少受邀方");
        }
        if ((body.getInviteType() == 0 && !CoopInvite.TO_MERCHANT.equals(body.getToType()))
                || (body.getInviteType() == 1 && !"club".equals(body.getToType()))) {
            return error("邀请类型与受邀方类型不匹配");
        }
        // type0/type1 均须关联主题，否则接受生成的 coop_order 无法参与结算、条款为空。
        if (body.getTopicId() == null) {
            return error("该类型邀请需关联主题");
        }
        CmsTopic topic = cmsTopicService.selectCmsTopicById(body.getTopicId());
        if (topic == null) {
            return error("主题不存在");
        }
        // 防伪造:合同主体只能由服务端从主题发布归属解析；操作员 principal 只属于审计身份，
        // 不能被客户端 fromId/fromType 写成合同或结算主体。
        Long contractOwnerMemberId = principalMemberId;
        body.setId(null);
        if (body.getInviteType() == 1) {
            // club_id 是主题发布主体真源。发布者同时有商家身份时不能按“有商家档案”抢占为
            // merchant，否则俱乐部发布、执行俱乐部承接会被记成“商家 → 俱乐部”。
            if (topic.getClubId() != null && topic.getClubId() > 0) {
                Club publisherClub = clubService.selectClubById(topic.getClubId());
                if (publisherClub == null || publisherClub.getMemberId() == null) {
                    return error("主题发布俱乐部不存在");
                }
                contractOwnerMemberId = publisherClub.getMemberId();
                if (!contractOwnerMemberId.equals(principalMemberId)) {
                    return error("只能以自己管理的主题发布俱乐部发起合作");
                }
                body.setFromType("club");
            } else {
                try {
                    MerchantAccessContext access = merchantAccessService.require(
                            principalMemberId, MerchantPermission.COOP_MANAGE);
                    contractOwnerMemberId = access.getOwnerMemberId();
                } catch (ServiceException e) {
                    return new AjaxResult(e.getCode(), e.getMessage());
                }
                body.setFromType("merchant");
            }
        } else if (MerchantOperationScope.isMerchant(body.getScope())) {
            try {
                MerchantAccessContext access = merchantAccessService.require(
                        principalMemberId, MerchantPermission.PROJECT_MANAGE);
                contractOwnerMemberId = access.getOwnerMemberId();
            } catch (ServiceException e) {
                return new AjaxResult(e.getCode(), e.getMessage());
            }
            body.setFromType("merchant");
        } else {
            body.setFromType("talent");
        }
        body.setFromId("club".equals(body.getFromType()) ? topic.getClubId() : contractOwnerMemberId);
        // [P0-越权] 禁止自邀自接:受邀方解析后若等于发起人则拒绝。
        // 否则发起方可对自己的另一身份自邀自接，伪造已接受合作。
        Long toOwner = resolveToOwner(body);
        if (toOwner != null && toOwner.equals(contractOwnerMemberId)) {
            return error("不能向自己发起协作邀请");
        }

        // [P0-越权] 发起人必须是该主题的发布者 —— **两类统一**。
        // 上面那道自邀自接闸只挡「发给自己」,挡不住「小号 A 发给小号 B」:此前没有任何一条校验
        // 发起人与 topicId 的关系 ⇒ 任何登录会员可为**任意主题**发邀约,对方接受即 status=1
        // ⇒ 伪造出「已接受邀约」,经 acceptInviteWithLock 落 coop_order **塞进 ① 的结算阵容**。
        // ⚠️ 两类统一是有据的(别按类型名反推方向):前端 pages/coop/invite/index.js 对两类一视同仁,
        // topicId 只能来自 /api/topic/list {is_my:1},而后端对 is_my=1 的处理就是
        // cmsTopic.setMemberId(登录人) ⇒ 两类的发起人本来就都是主题发布者。
        // type1 的真实语义 = 主题发布主体请俱乐部承接,不是往别人主题上贴。
        // ⚠️ member_id 为空(③-自营/存量脏数据)⇒ 拒:判不出归属就别放行,放行等于把
        // 「归属不明」当成「归属是我」。③-自营 的商家不走邀约,走报名/中标。
        if (OwnershipUtils.notOwner(topic.getMemberId(), contractOwnerMemberId)) {
            return error("只能为自己发布的主题发起合作");
        }
        // Freeze the already-authorized contract member for both entry paths.
        // The principal remains the operator/security identity, including inside the receipt callback.
        final Long checkedContractOwnerMemberId = contractOwnerMemberId;
        if (body.getRequestId() != null) {
            // 微信内容安全是网络调用:在原请求事务(回执唯一键行锁)之外先做,事务回调里只留 DB 校验。
            String risk = textRisk(principalMemberId, body.getMessage());
            if (risk != null) return error(risk);
            try {
                CoopInviteRequestResult result = coopInviteService.createOrReplayInvite(principalMemberId, body, () -> {
                    String invalid = prepareFirstInvite(body, principalMemberId, checkedContractOwnerMemberId, topic, true);
                    if (invalid != null) throw new IllegalStateException(invalid);
                });
                if (!Boolean.TRUE.equals(result.getReplayed())) notifyNewInvite(body, toOwner, contractOwnerMemberId);
                return AjaxResult.success("已发起", result);
            } catch (ServiceException e) {
                return new AjaxResult(e.getCode(), e.getMessage());
            } catch (IllegalStateException e) {
                return error(e.getMessage());
            }
        }
        try {
            String invalid = prepareFirstInvite(body, principalMemberId, checkedContractOwnerMemberId, topic, false);
            if (invalid != null) return error(invalid);
            coopInviteService.insertCoopInvite(body);
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
        notifyNewInvite(body, toOwner, contractOwnerMemberId);
        Map<String, Object> d = new HashMap<>();
        d.put("inviteId", body.getId());
        return AjaxResult.success("已发起", d);
    }

    private String prepareFirstInvite(CoopInvite body, Long principalMemberId, Long contractOwnerMemberId,
                                      CmsTopic topic, boolean originalRequest) {
        // node_id 只属于已撤销的历史 type2，新建 type0/type1 一律清掉客户端伪造值。
        body.setNodeId(null);
        if (body.getInviteType() == 0
                && Integer.valueOf(com.chengyinhub.common.constant.ProductType.ORIENTEERING).equals(topic.getProductType())) {
            if (body.getSupplyStationId() == null || body.getSupplyStationId() <= 0)
                return "请选择本主题该商家的真实核销据点";
            try {
                boolean selected = false;
                for (CmsRegistrationMerchant station : coopInviteService.listSupplyStations(
                        topic.getId(), body.getToId(), contractOwnerMemberId))
                    if (body.getSupplyStationId().equals(station.getId())) selected = true;
                if (!selected) return "所选据点不属于本主题受邀商家或已绑定原合作单";
            } catch (IllegalStateException e) {
                return e.getMessage() == null ? "核销据点无法确认" : e.getMessage();
            }
        } else if (body.getSupplyStationId() != null) {
            return "该邀约不支持据点计酬确认";
        }
        // B1-3 条款校验:报酬模式与配套字段一致性(字段可空,向后兼容;传了才校验)
        Integer shareMode = body.getShareMode();
        if (shareMode != null) {
            if (shareMode < 0 || shareMode > 2) return "报酬模式不合法";
            if (shareMode == 1) {
                if (body.getShareRate() == null
                        || body.getShareRate().compareTo(BigDecimal.ZERO) <= 0
                        || body.getShareRate().compareTo(new BigDecimal("90")) > 0)
                    return "分成型需填写 0~90% 的分成比例";
            } else if (shareMode == 2) {
                if (body.getFixedFee() == null || body.getFixedFee().compareTo(BigDecimal.ZERO) <= 0)
                    return "固定型需填写每人固定合作费";
            }
        }
        // 槽位粒度归一:带 game_id = 游戏级(经典定向),否则主题级
        body.setTargetLevel(body.getGameId() != null ? 1 : 0);
        // 经典定向游戏级:同一游戏同时挂起(待确认)邀约 ≤ 3
        if (body.getGameId() != null) {
            CoopInvite pendQ = new CoopInvite();
            pendQ.setGameId(body.getGameId());
            pendQ.setStatus(0);
            if (mutableInviteCount(originalRequest ? coopInviteService.selectCoopInviteListReadOnly(pendQ) : coopInviteService.selectCoopInviteList(pendQ)) >= 3)
                return "该游戏挂起邀约已达上限(3),请等待处理或先取消";
        }
        // Σ 已接受分成型比例 ≤ 90(给发布者留 ≥10% 余量);平台佣金率待拍板,先按此保守闸
        if (shareMode != null && shareMode == 1 && body.getTopicId() != null) {
            BigDecimal sum = body.getShareRate();
            CoopInvite acceptedQ = new CoopInvite();
            acceptedQ.setTopicId(body.getTopicId());
            acceptedQ.setStatus(1);
            for (CoopInvite a : (originalRequest ? coopInviteService.selectCoopInviteListReadOnly(acceptedQ) : coopInviteService.selectCoopInviteList(acceptedQ))) {
                if (Integer.valueOf(CoopInvite.TYPE_LEGACY_MERCHANT_NODE).equals(a.getInviteType())) continue;
                if (a.getShareMode() != null && a.getShareMode() == 1 && a.getShareRate() != null)
                    sum = sum.add(a.getShareRate());
            }
            if (sum.compareTo(new BigDecimal("90")) > 0)
                return "分成比例之和超过 90%,请调整";
        }
        // expire_time 先取主题招募截止，Service 再封顶为服务端创建时间+48h；不接受客户端时间。
        body.setConfigDeadline(null);
        body.setExpireTime(null);
        // 复用上面发起人闸已取到的 topic(同一行,别再查一次)
        if (topic.getRecruitDeadline() != null) body.setExpireTime(topic.getRecruitDeadline());

        body.setStatus(0);
        body.setHandleTime(null);
        body.setDelFlag(0);
        return originalRequest ? null : textRisk(principalMemberId, body.getMessage());
    }

    private void notifyNewInvite(CoopInvite body, Long toOwner, Long contractOwnerMemberId) {
        // 订阅消息:通知受邀方收到合作邀约。失败不回滚邀约写入,但必须留可检索日志。
        try {
            notifyService.notifyCoopInvited(toOwner, topicNameOf(body.getTopicId()), nickOf(contractOwnerMemberId));
        } catch (Exception e) {
            log.error("[订阅消息] 通知调用异常 templateKey=tplCoopInvited businessEvent=coop_invited "
                    + "memberId={} inviteId={} cause={}",
                    toOwner, body.getId(), e.getClass().getSimpleName());
        }
    }

    @GetMapping("/invite/request")
    public AjaxResult inviteRequest(@RequestParam String requestId) {
        Long principal;
        try { principal = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        try {
            return success(coopInviteService.findInviteRequest(principal, requestId));
        } catch (ServiceException e) { return new AjaxResult(e.getCode(), e.getMessage()); }
    }

    /** Cursor scans receipt rows, including unavailable rows, so an unauthorized page still advances. */
    @GetMapping("/invite/requests")
    public AjaxResult inviteRequests(@RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "20") int limit) {
        Long principal;
        try { principal = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        try {
            return success(coopInviteService.listInviteRequests(principal, before, limit));
        } catch (ServiceException e) { return new AjaxResult(e.getCode(), e.getMessage()); }
    }

    @GetMapping("/supply-stations")
    public AjaxResult supplyStations(@org.springframework.web.bind.annotation.RequestParam("topicId") Long topicId,
                                    @org.springframework.web.bind.annotation.RequestParam("merchantMemberId") Long merchantMemberId) {
        Long principal;
        try { principal = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (topicId == null || topicId <= 0 || merchantMemberId == null || merchantMemberId <= 0)
            return error("参数不合规");
        CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
        if (topic == null) return error("主题不存在");
        Long creator = principal;
        if (OwnershipUtils.notOwner(topic.getMemberId(), creator)) {
            try { creator = merchantAccessService.require(principal, MerchantPermission.PROJECT_MANAGE).getOwnerMemberId(); }
            catch (ServiceException e) { return new AjaxResult(e.getCode(), e.getMessage()); }
        }
        if (OwnershipUtils.notOwner(topic.getMemberId(), creator)) return error("只能为自己发布的主题选择合作据点");
        try {
            List<Map<String,Object>> stations = new ArrayList<>();
            for (CmsRegistrationMerchant station : coopInviteService.listSupplyStations(topicId, merchantMemberId, creator)) {
                Map<String,Object> row = new HashMap<>();
                row.put("supplyStationId", station.getId().toString());
                row.put("stationName", station.getAddressName());
                stations.add(row);
            }
            Map<String,Object> data = new HashMap<>();
            data.put("requiresSupplyStation", true); data.put("stations", stations);
            return AjaxResult.success(data);
        } catch (IllegalStateException e) { return error(e.getMessage()); }
    }

    private String textRisk(Long principalMemberId, String content) {
        if (content == null || content.trim().isEmpty()) return null;
        return com.chengyinhub.business.service.IWxContentSecurityService.checkTextAsMember(wxContentSecurityService, umsMemberService,
                principalMemberId, content);
    }

    /**
     * 处理邀请:status 1已接受 / 2已拒绝 / 3已取消。
     * 接受/拒绝须受邀方本人;取消须发起方本人。自审核:接受即通过。
     * 历史 type2 只允许读取展示，任何处理动作都拒绝。
     */
    @Operation(summary = "处理协作邀请(接受/拒绝/取消)")
    @PostMapping("/handle")
    public AjaxResult handle(@RequestBody CoopInvite body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body.getId() == null || body.getStatus() == null) return error("缺少参数");
        int target = body.getStatus();
        if (target != 1 && target != 2 && target != 3) return error("操作不合法");
        // F-L5C-02:拒绝/撤回/取消的理由会发给对方(详情 + 订阅消息),与 /invite 留言同一道闸。
        String handleRisk = textRisk(memberId, com.chengyinhub.business.service.IWxContentSecurityService.joinText(
                body.getMessage(), body.getHandleReason()));
        if (handleRisk != null) return error(handleRisk);

        CoopInvite inv = coopInviteService.selectCoopInviteById(body.getId());
        if (inv == null) return error("邀请不存在");
        if (inv.getStatus() == null) return error("邀请状态异常");
        if (Integer.valueOf(CoopInvite.TYPE_LEGACY_MERCHANT_NODE).equals(inv.getInviteType())) {
            return error("历史商家节点邀约仅供查看，不能再处理");
        }

        Long toOwner = resolveToOwner(inv);
        Long fromOwner = resolveFromOwner(inv);
        // 员工以真实 principal 登录，但旧协作表以商家 ownerMemberId 作为合同主体。
        // 接受、拒绝与取消必须走同一解析口径；商家侧每次动作都重新校验 active + COOP_MANAGE。
        Long contractMemberId;
        try {
            boolean allowFrom = target == 3;
            boolean allowTo = target == 1 || target == 2 || (target == 3 && inv.getStatus() == 1);
            contractMemberId = resolveActionContractMemberId(
                    memberId, inv, fromOwner, toOwner, allowFrom, allowTo);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        boolean isFrom = fromOwner != null && contractMemberId.equals(fromOwner);
        boolean isTo = toOwner != null && contractMemberId.equals(toOwner);

        // ---- 接受 / 拒绝:受邀方本人,且邀约须仍在待确认 ----
        if (target == 1 || target == 2) {
            if (inv.getStatus() != 0) return error("该邀请已处理");
            if (!isTo) return error("仅受邀方可处理");
            if (inv.getInviteType() != null && inv.getInviteType() == CoopInvite.TYPE_OFFICIAL_MERCHANT
                    && CoopInvite.FROM_OFFICIAL.equals(inv.getFromType())) {
                try {
                    if (target == 2) {
                        coopInviteService.rejectOfficialInviteWithLock(
                                inv.getId(), contractMemberId, body.getHandleReason());
                        try {
                            notifyService.notifyCoopStatus(fromOwner, topicNameOf(inv.getTopicId()),
                                    nickOf(memberId), "已拒绝", "商家已拒绝官方活动承接邀约");
                        } catch (Exception ignore) {}
                        return AjaxResult.success("已拒绝");
                    }
                    Long registrationId = coopInviteService.acceptOfficialInviteWithLock(
                            inv.getId(), contractMemberId);
                    Map<String, Object> data = new HashMap<>();
                        data.put("registrationId", registrationId);
                        try {
                            notifyService.notifyCoopStatus(fromOwner, topicNameOf(inv.getTopicId()),
                                nickOf(contractMemberId), "已接受", "商家已接受官方活动承接邀约");
                    } catch (Exception ignore) {}
                    return AjaxResult.success("已接受", data);
                } catch (IllegalStateException e) {
                    return error(e.getMessage());
                }
            }
            if (target == 2) {
                try {
                    coopInviteService.rejectPendingInviteWithLock(
                            inv.getId(), contractMemberId, body.getHandleReason());
                } catch (IllegalStateException e) {
                    return error(e.getMessage());
                }
                // 订阅消息:通知发起方被拒绝(best-effort)
                try {
                    notifyService.notifyCoopStatus(fromOwner, topicNameOf(inv.getTopicId()), nickOf(memberId), "已拒绝", "对方婉拒了本次合作");
                } catch (Exception ignore) {}
                return AjaxResult.success("已拒绝");
            }
            // 接受:走事务(B1-4 锁槽位 + 容量复核 + 生成合作单 + 顶替其他挂起)
            try {
                coopInviteService.acceptInviteWithLock(inv.getId(), null, body.getSupplyStationId());
            } catch (IllegalStateException e) {
                return error(e.getMessage());
            }
            Map<String, Object> d = new HashMap<>();
            // 接受 → 建/取双方 1:1 IM 会话(best-effort)
            try {
                if (toOwner != null) d.put("conversationId", imService.startSingle(fromOwner, toOwner));
            } catch (Exception ignore) {}
            // 订阅消息:通知发起方被接受(best-effort)
            try {
                notifyService.notifyCoopStatus(fromOwner, topicNameOf(inv.getTopicId()), nickOf(toOwner), "已接受", "已进入合作,可去沟通");
            } catch (Exception ignore) {}
            return AjaxResult.success("已接受", d);
        }

        // ---- 取消(target==3)----
        if (inv.getStatus() == 0) {
            if (!isFrom) return error("仅发起方可取消");
            try {
                coopInviteService.cancelPendingInviteWithLock(inv.getId(), contractMemberId, body.getMessage());
            } catch (IllegalStateException e) {
                return error(e.getMessage());
            }
            // CU-C-86:撤回页写明「理由会发给对方」,接受/拒绝都通知,撤回原来一声不吭。订阅消息字段有字数上限,截短;全文在详情里。
            String reason = body.getMessage() == null ? "" : body.getMessage().trim();
            try {
                notifyService.notifyCoopStatus(toOwner, topicNameOf(inv.getTopicId()), nickOf(contractMemberId), "已撤回",
                        reason.isEmpty() ? "对方撤回了协作邀约" : (reason.length() > 16 ? reason.substring(0, 16) + "…" : reason));
            } catch (Exception ignore) {}
            return AjaxResult.success("已取消");
        }
        if (inv.getStatus() == 1) {
            // 锁价前双方可取消并仅记录信用;锁价后由 service 用 lifecycle 闸拒绝单方取消。
            if (!isFrom && !isTo) return error("无权取消");
            String reason = body.getMessage();
            if (reason == null || reason.trim().isEmpty()) return error("取消已接受的合作需填写理由");
            try {
                // 锁价前取消仅记信用事件,已缴保证金由下层原路退回。
                coopInviteService.cancelAcceptedInvite(inv.getId(), contractMemberId, reason.trim(), 2);
            } catch (IllegalStateException e) {
                return error(e.getMessage());
            }
            // 锁价前取消:任何已冻结保证金一律原路退回,不再按取消方扣款。
            if (!refundDepositIfHeld(inv.getId(), "合作取消")) {
                return error("合作已取消，保证金退款处理中，请联系客服");
            }
            return AjaxResult.success("已取消合作");
        }
        return error("该邀请无法取消");
    }

    /** 取消后或主题履约完成后,付款方可重试尚未成功的原路退款。 */
    @PostMapping("/deposit/refund/retry")
    public AjaxResult retryDepositRefund(@RequestBody Map<String, Object> body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        Long inviteId = body == null ? null : toLong(body.get("inviteId"));
        if (inviteId == null) return error("缺少邀约");
        try {
            coopInviteService.requestDepositRefund(inviteId, memberId);
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
        CoopInvite current = coopInviteService.selectCoopInviteById(inviteId);
        if (current == null || !inviteId.equals(current.getId())) return depositRefundFeedback(null);
        return depositRefundFeedback(current);
    }

    /** 保证金业务记录只证明当前处理状态，不证明原支付渠道已到账。 */
    private AjaxResult depositRefundFeedback(CoopInvite invite) {
        Integer status = invite == null ? null : invite.getDepositStatus();
        String state = "UNKNOWN";
        String message = "保证金记录暂无法确认，请联系客服核对";
        int code = 500;
        if (status != null && !Integer.valueOf(CoopInvite.TYPE_LEGACY_MERCHANT_NODE).equals(invite.getInviteType())) {
            switch (status) {
                case 0:
                    state = "NOT_REQUIRED"; message = "该合作无需保证金退款"; code = 200; break;
                case 1:
                    state = "PAYMENT_PENDING"; message = "保证金支付待确认，尚未发起退款"; code = 200; break;
                case 2:
                    state = "HELD"; message = "保证金仍处于冻结状态，退款结果待核对，请联系客服";
                    if ("PENDING".equals(invite.getDepositRefundState()) || "DISPATCHING".equals(invite.getDepositRefundState())) {
                        state = "DISPATCH_PENDING"; message = "保证金退款申请已记录，等待渠道处理"; code = 200;
                    } else if ("PROCESSING".equals(invite.getDepositRefundState())) {
                        state = "PROCESSING"; message = "保证金退款处理中，尚未确认到账"; code = 200;
                    } else if ("MANUAL".equals(invite.getDepositRefundState())) {
                        state = "MANUAL"; message = "保证金退款需人工核对，请联系客服";
                    } else if ("UNKNOWN".equals(invite.getDepositRefundState())) {
                        state = "UNKNOWN"; message = "保证金退款结果暂无法确认，请先核对，勿重复提交";
                    }
                    break;
                case 3:
                    state = "DEDUCTED"; message = "保证金已扣划，无法退款，请联系客服"; code = 200; break;
                case 4:
                    if (invite.getDepositOutTradeNo() != null && !invite.getDepositOutTradeNo().trim().isEmpty()
                            && ("R" + invite.getDepositOutTradeNo()).equals(invite.getDepositRefundNo())) {
                        state = "LEGACY_UNCONFIRMED"; message = "历史退款记录尚无到账凭据，请联系客服核对";
                        if ("SUCCESS".equals(invite.getDepositRefundState()) && invite.getDepositRefundConfirmedAt() != null) {
                            state = "SUCCESS"; message = "保证金已原路退回（渠道确认）"; code = 200;
                        } else if ("PROCESSING".equals(invite.getDepositRefundState())) {
                            state = "PROCESSING"; message = "保证金退款处理中，尚未确认到账"; code = 200;
                        } else if ("MANUAL".equals(invite.getDepositRefundState())) {
                            state = "MANUAL"; message = "保证金退款需人工核对，请联系客服";
                        }
                    }
                    break;
                case 5:
                    state = "UNPAID"; message = "保证金尚未缴纳，无需发起退款"; code = 200; break;
                default: break;
            }
        }
        Map<String, Object> data = new HashMap<>();
        data.put("depositStatus", status);
        data.put("refundState", state);
        data.put("channelConfirmed", "SUCCESS".equals(state));
        return new AjaxResult(code, message, data);
    }

    @Operation(summary = "我的常备权益模板 - 列表")
    @PostMapping("/perk-template/list")
    public AjaxResult perkTemplateList(@RequestBody(required = false) Map<String, Object> body) {
        Long principalMemberId;
        try { principalMemberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        Long memberId;
        try {
            memberId = merchantAccessService.require(
                    principalMemberId, MerchantPermission.COOP_MANAGE).getOwnerMemberId();
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        return AjaxResult.success(coopInviteService.listPerkTemplates(memberId));
    }

    @Operation(summary = "我的常备权益模板 - 新增")
    @PostMapping("/perk-template/save")
    public AjaxResult perkTemplateSave(@RequestBody CoopPerkTemplate body) {
        Long principalMemberId;
        try { principalMemberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        Long memberId;
        try {
            memberId = merchantAccessService.require(
                    principalMemberId, MerchantPermission.COOP_MANAGE).getOwnerMemberId();
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        if (body == null || body.getName() == null || body.getName().trim().isEmpty()) return error("请填写权益名称");
        if (body.getPerkType() == null || body.getPerkType() < 0 || body.getPerkType() > 2) return error("权益类型不合法");
        if (!validPerkAmount(body.getRetailValue(), false)) return error("权益零售价须为不超过99999999.99的正数，最多两位小数");
        if (!validPerkAmount(body.getUnitCost(), true)) return error("成本价须为0至99999999.99，最多两位小数");
        if (body.getQuota() == null || body.getQuota() <= 0) return error("请填写正整数可接待份数");
        // CU-M-79:过期权益模板不允许创建。validEnd 存当日 00:00,下游 offer 一律按
        // validEnd.after(now) 判在售 ⇒ 选今天等于一建就过期;最早只能选明天。
        if (body.getValidEnd() != null && !body.getValidEnd().after(DateUtils.getNowDate())) {
            return error("有效期至少要到明天");
        }
        // F-L5C-02:权益名称与核销规则会展示给俱乐部与玩家。
        String perkRisk = textRisk(principalMemberId, com.chengyinhub.business.service.IWxContentSecurityService.joinText(
                body.getName(), body.getRedeemRule()));
        if (perkRisk != null) return error(perkRisk);
        body.setId(null);
        body.setMerchantId(memberId);
        Long id = coopInviteService.savePerkTemplate(body);
        Map<String, Object> d = new HashMap<>();
        d.put("id", id);
        return AjaxResult.success("已保存", d);
    }

    @Operation(summary = "我的常备权益模板 - 删除")
    @PostMapping("/perk-template/delete")
    public AjaxResult perkTemplateDelete(@RequestBody Map<String, Object> body) {
        Long principalMemberId;
        try { principalMemberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        Long memberId;
        try {
            memberId = merchantAccessService.require(
                    principalMemberId, MerchantPermission.COOP_MANAGE).getOwnerMemberId();
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        Long id = body == null ? null : toLong(body.get("id"));
        if (id == null) return error("缺少ID");
        coopInviteService.deletePerkTemplate(id, memberId);
        return AjaxResult.success("已删除");
    }

    /**
     * 商家把已申报的供给挂到具体章节上,落 merchant_chapter_offer 的**生效行**。
     *
     * <p>⚠️ 这是 ③ 的开卖前置:在此之前 merchant_chapter_offer 零 insert 入口 ⇒ 供给池恒空
     * ⇒ reserveEntitlements 算出的容量恒 ≤0 ⇒ 任何有章节的 ③ 主题购票 100% 被拒。
     *
     * <p>服务端强制的字段(不信前端传的):merchantId 取登录态、topicId 由章节反查、
     * status/quotaUsed 由 service 定死。三档的字段形状与门槛校验全在 service 层,
     * 与 DDL 的 ck_offer_terms 同口径。
     */
    @Operation(summary = "商家入驻章节:落一行生效供给")
    @PostMapping("/offer/enroll")
    public AjaxResult offerEnroll(@RequestBody MerchantChapterOffer body) {
        Long principalMemberId;
        try { principalMemberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        MerchantAccessContext access;
        try {
            access = merchantAccessService.require(principalMemberId, MerchantPermission.COOP_MANAGE);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        Long memberId = access.getOwnerMemberId();
        if (body == null) return error("参数为空");
        // 服务端接管的字段一律清掉,防前端越权塞值
        body.setId(null);
        body.setTopicId(null);
        body.setMerchantId(null);
        body.setQuotaUsed(null);
        body.setStatus(null);
        try {
            Long id = merchantOfferService.enroll(body, memberId);
            Map<String, Object> d = new HashMap<>();
            d.put("id", id);
            return AjaxResult.success("已入驻", d);
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "商家退出自己的普通自由探索章节供给")
    @PostMapping("/offer/withdraw")
    public AjaxResult offerWithdraw(@RequestBody(required = false) Map<String, Object> body) {
        Long principalMemberId;
        try { principalMemberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (principalMemberId == null || principalMemberId <= 0) return error("请先登录");
        Long ownerMemberId;
        try {
            ownerMemberId = merchantAccessService.require(
                    principalMemberId, MerchantPermission.COOP_MANAGE).getOwnerMemberId();
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        if (ownerMemberId == null || ownerMemberId <= 0) return error("请先登录");
        Long offerId = body == null ? null : toLong(body.get("offerId"));
        if (offerId == null || offerId <= 0) return error("缺少供给记录");
        Object rawReason = body.get("reason");
        if (rawReason != null && !(rawReason instanceof String)) return error("退出原因格式不正确");
        String reason = rawReason == null ? null : ((String) rawReason).trim();
        try {
            boolean enrollmentPaused = merchantOfferService.withdrawOwnChapterOffer(
                    offerId, ownerMemberId, reason);
            Map<String, Object> data = new HashMap<>();
            data.put("enrollmentPaused", enrollmentPaused);
            return AjaxResult.success("已退出供给", data);
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "圈层供给重新确认：刷新商家声明快照")
    @PostMapping("/offer/circle-supply/reconfirm")
    public AjaxResult reconfirmCircleSupply(@RequestBody MerchantCircleSupplyProfile body) {
        Long principalMemberId;
        try { principalMemberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        Long memberId;
        try {
            memberId = merchantAccessService.require(
                    principalMemberId, MerchantPermission.COOP_MANAGE).getOwnerMemberId();
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        if (body == null || body.getOfferId() == null) return error("缺少供给记录");
        Long offerId = body.getOfferId();
        body.setId(null);
        try {
            merchantOfferService.reconfirmCircleSupply(offerId, body, memberId);
            return AjaxResult.success("已重新确认");
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "圈层供给无变化重新确认：服务端复制当前快照并刷新确认时间")
    @PostMapping("/offer/circle-supply/reconfirm-current")
    public AjaxResult reconfirmCurrentCircleSupply(@RequestBody MerchantCircleSupplyProfile body) {
        Long principalMemberId;
        try { principalMemberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        Long memberId;
        try {
            memberId = merchantAccessService.require(
                    principalMemberId, MerchantPermission.COOP_MANAGE).getOwnerMemberId();
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        if (memberId == null || memberId <= 0) return error("请先登录");
        if (body == null || body.getOfferId() == null) return error("缺少供给记录");
        try {
            merchantOfferService.reconfirmCurrentCircleSupply(body.getOfferId(), memberId);
            return AjaxResult.success("已重新确认");
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "商家暂停自己的圈层供给")
    @PostMapping("/offer/circle-supply/pause")
    public AjaxResult pauseOwnCircleSupply(@RequestBody MerchantCircleSupplyProfile body) {
        Long principalMemberId;
        try { principalMemberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        Long memberId;
        try {
            memberId = merchantAccessService.require(
                    principalMemberId, MerchantPermission.COOP_MANAGE).getOwnerMemberId();
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        if (memberId == null || memberId <= 0) return error("请先登录");
        if (body == null || body.getOfferId() == null) return error("缺少供给记录");
        try {
            merchantOfferService.deactivateOwnCircleSupply(body.getOfferId(), memberId);
            return AjaxResult.success("供给已暂停");
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "供给申报:受邀方从常备权益模板申报本次供给")
    @PostMapping("/perks/attach")
    public AjaxResult perksAttach(@RequestBody Map<String, Object> body) {
        Long principalMemberId;
        try { principalMemberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        Long inviteId = body == null ? null : toLong(body.get("inviteId"));
        if (inviteId == null) return error("缺少邀约");
        CoopInvite invite = coopInviteService.selectCoopInviteById(inviteId);
        if (invite == null) return error("邀请不存在");
        Long memberId = principalMemberId;
        if ("merchant".equals(invite.getToType())) {
            try {
                memberId = merchantAccessService.require(
                        principalMemberId, MerchantPermission.COOP_MANAGE).getOwnerMemberId();
            } catch (ServiceException e) {
                return new AjaxResult(e.getCode(), e.getMessage());
            }
        }
        List<Long> templateIds = new ArrayList<>();
        Object raw = body.get("templateIds");
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) { Long v = toLong(o); if (v != null) templateIds.add(v); }
        }
        try {
            int n = coopInviteService.attachPerks(inviteId, memberId, templateIds);
            Map<String, Object> d = new HashMap<>();
            d.put("count", n);
            return AjaxResult.success("已申报", d);
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "某邀约已申报的供给")
    @PostMapping("/perks/list")
    public AjaxResult perksList(@RequestBody Map<String, Object> body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        Long inviteId = body == null ? null : toLong(body.get("inviteId"));
        if (inviteId == null) return error("缺少邀约");
        // 仅合作双方(发起方/受邀方,club 受邀=群主)可看该邀约的供给,防 IDOR
        CoopInvite inv = coopInviteService.selectCoopInviteById(inviteId);
        if (inv == null) return error("邀约不存在");
        Long toOwner = resolveToOwner(inv);
        Long fromOwner = resolveFromOwner(inv);
        if (!memberId.equals(fromOwner) && !memberId.equals(toOwner)) {
            return error("无权查看");
        }
        List<CoopPerk> perks = coopInviteService.listPerks(inviteId);
        if (!memberId.equals(toOwner) && perks != null) {
            for (CoopPerk perk : perks) {
                perk.setUnitCost(null);
            }
        }
        return AjaxResult.success(perks);
    }

    @Operation(summary = "提交合作互评")
    @PostMapping("/review/save")
    public AjaxResult reviewSave(@RequestBody CoopReview body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body == null || body.getTopicId() == null || body.getToId() == null) return error("缺少参数");
        if (body.getRating() == null || body.getRating() < 1 || body.getRating() > 5) return error("请打 1~5 分");
        if (memberId.equals(body.getToId())) return error("不能评价自己");
        body.setId(null);
        body.setFromId(memberId);
        String risk = textRisk(memberId, body.getComment());
        if (risk != null) return error(risk);
        try {
            coopInviteService.saveReview(body);
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
        return AjaxResult.success("已评价");
    }

    @Operation(summary = "某人的评价摘要(均分+条数)")
    @PostMapping("/review/summary")
    public AjaxResult reviewSummary(@RequestBody Map<String, Object> body) {
        try { currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        Long toId = body == null ? null : toLong(body.get("toId"));
        if (toId == null) return error("缺少被评人");
        Map<String, Object> d = new HashMap<>();
        d.put("summary", coopInviteService.reviewSummary(toId));
        d.put("reviews", coopInviteService.listReviews(toId));
        return AjaxResult.success(d);
    }

    @Operation(summary = "商家结算工作台(履约率 + 结算记录)")
    @PostMapping("/mybiz")
    public AjaxResult mybiz(@RequestBody(required = false) Map<String, Object> body) {
        Long principalMemberId;
        try { principalMemberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        Long memberId;
        try {
            memberId = merchantAccessService.require(
                    principalMemberId, MerchantPermission.FINANCE_READ).getOwnerMemberId();
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        Map<String, Object> d = new HashMap<>();
        d.put("credit", coopInviteService.creditSummary(memberId));
        d.put("settlements", coopInviteService.mySettlements(memberId));
        d.put("review", coopInviteService.reviewSummary(memberId));
        return AjaxResult.success(d);
    }

    @Operation(summary = "可投诉的主题(投诉页的选择源;与受理口同一份判据)")
    @PostMapping("/complaint/topics")
    public AjaxResult complaintTopics() {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        // ★ 为什么不复用 /api/registration/list?owner_type=3(2026-08-18 真库实测的两个缺陷):
        //   ① 那条链路末端会过 ApiRegistrationController.filterEndedOverWeek(HIDE_AFTER_END_DAYS=7),
        //      把结束超过 7 天的主题从选择源砍掉 —— 那是给「我参与的」列表做视觉收纳的规则,
        //      而投诉本就是事后行为,受理口这边压根没有时间窗。两边不一致 = 玩家选不到、后端却允许。
        //      ⚠️ 修法是**不走那条链路**,不是去改 filterEndedOverWeek:它服务的是订单列表那个用途,
        //      在那个用途上是对的,改它会顺带改坏订单列表。
        //   ② 那条链路只对 owner_type=1 挂 cmsTopic,活动单(owner_type=2)既没有挂载也没有
        //      registration.topic_id ⇒ 前端两个取 id 的来源全空,③ 自由探索玩家整条选不到。
        //      主题该由服务端反查出来,不该让前端从挂载对象里猜。
        return AjaxResult.success(cmsRegistrationService.selectComplainableTopics(memberId));
    }

    @Operation(summary = "本人投诉处理进展")
    @PostMapping("/complaint/mine")
    public AjaxResult complaintMine() {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) {
            return AjaxResult.error(com.chengyinhub.common.constant.HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return AjaxResult.success(coopComplaintService.listMine(memberId));
    }

    @Operation(summary = "玩家投诉(建客诉工单;平台受理即冻结该主题结算放款)")
    @PostMapping("/complaint/report")
    public AjaxResult complaintReport(@RequestBody com.chengyinhub.business.domain.CoopComplaint body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body == null || body.getTopicId() == null) return error("缺少主题");
        Long topicId = body.getTopicId();
        // 参与校验:投诉人须是该主题报名参与者;否则任意人可对任意主题投诉刷冻结结算(IDOR/资损)。
        // ★ 判据不在这里手写(2026-08-18):选择源与受理口各写一份必然漂 —— 上一版这里比的是
        //   reg.getTopicId(),而那一列全类只有 createSelfPlayPass 写,活动单恒 null;
        //   选择源那边又是另一套取法。现在两边都读 selectComplainableTopics,判据只有一份。
        //   它内部只捞 member_id=本人 且 payment_status=2 的单(退款会置 4,天然排除),
        //   反查不出主题的单 fail-closed 不进集合 ⇒ IDOR / 非参与者 / 已退款刷冻结这三条防线都在。
        com.chengyinhub.business.domain.vo.ComplainableTopicVO target = null;
        for (com.chengyinhub.business.domain.vo.ComplainableTopicVO t
                : cmsRegistrationService.selectComplainableTopics(memberId)) {
            if (topicId.equals(t.getTopicId())) { target = t; break; }
        }
        if (target == null) return error("仅本主题已支付参与者可投诉");
        // 防刷冻结:同一投诉人对同主题已有未结工单则不重复建
        for (com.chengyinhub.business.domain.CoopComplaint ex : coopComplaintService.listByTopic(topicId)) {
            if (memberId.equals(ex.getReporterMemberId()) && ex.getStatus() != null
                    && ex.getStatus() < com.chengyinhub.business.domain.CoopComplaint.STATUS_CLOSED) {
                return error("你对该主题已有处理中的投诉");
            }
        }
        // 白名单:只信任 topicId + reason;过错方/垫付额/过错比例/扣划额/status/handler 一律由客服后台设,绝不从 body 取
        com.chengyinhub.business.domain.CoopComplaint safe = new com.chengyinhub.business.domain.CoopComplaint();
        safe.setTopicId(topicId);
        safe.setReporterMemberId(memberId);
        safe.setRegistrationId(target.getRegistrationId());
        safe.setReason(body.getReason());
        return AjaxResult.success(coopComplaintService.create(safe));
    }

    @Operation(summary = "联系合作方(已签约双方任意时刻取/建 1:1 会话)")
    @PostMapping("/contact")
    public AjaxResult contact(@RequestBody CoopInvite body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body == null || body.getId() == null) return error("缺少邀约");
        CoopInvite inv = coopInviteService.selectCoopInviteById(body.getId());
        if (inv == null) return error("邀约不存在");
        if (Integer.valueOf(CoopInvite.TYPE_LEGACY_MERCHANT_NODE).equals(inv.getInviteType())) {
            return error("历史商家节点邀约仅供查看，不能再创建会话");
        }
        if (inv.getStatus() == null || inv.getStatus() != 1) return error("仅已合作可联系");
        Long toOwner = resolveToOwner(inv);
        Long fromOwner = resolveFromOwner(inv);
        // SC-FREE-TERMS:与 /list 同一口径 —— 商家员工(COOP_MANAGE)按商家 ownerMemberId 认合同主体,
        // 否则 /list 能看到合作方、/contact 却报无权联系。第三方仍拒。
        Long contractMemberId;
        try {
            contractMemberId = resolveActionContractMemberId(memberId, inv, fromOwner, toOwner, true, true);
        } catch (ServiceException e) {
            return error("无权联系");
        }
        boolean isFrom = fromOwner != null && contractMemberId.equals(fromOwner);
        boolean isTo = toOwner != null && contractMemberId.equals(toOwner);
        if (!isFrom && !isTo) return error("无权联系");
        Long other = isFrom ? toOwner : fromOwner;
        if (other == null) return error("对方暂不可联系");
        try {
            Map<String, Object> d = new HashMap<>();
            d.put("conversationId", imService.startSingle(memberId, other));
            return AjaxResult.success(d);
        } catch (Exception e) {
            return error("暂不可联系:" + e.getMessage());
        }
    }

    @Autowired private IMerchantClawbackService merchantClawbackService;

    @Operation(summary = "俱乐部/发布者财务(按主题:我的分润/商家应收/打款状态/到账时间)")
    @PostMapping("/finance")
    public AjaxResult finance(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        Map<String, Object> d = new HashMap<>();
        d.put("topics", coopInviteService.clubFinance(memberId));
        d.put("adjustments", clawbackAdjustments(memberId));
        d.put("isOwner", isFundsOwner(memberId));
        return AjaxResult.success(d);
    }

    /**
     * 2026-09-17 总控裁定:余额三段(/api/wallet/stages 返回的是登录者本人的钱)只给主体本人看。
     * 商家员工(操作员)进分润页不显示;不是任何商家的员工 = 看的就是自己的分润 ⇒ 显示。
     * 解析失败(账号异常/多主体)一律不显示,不猜。
     */
    private boolean isFundsOwner(Long memberId) {
        try {
            MerchantAccessContext access = merchantAccessService.resolve(memberId);
            return access == null
                    || (com.chengyinhub.business.access.merchant.MerchantRoleCode.MERCHANT_OWNER.equals(access.getRoleCode())
                        && memberId.equals(access.getOwnerMemberId()));
        } catch (RuntimeException e) {
            logger.warn("分润页主体本人判定失败,不显示余额三段 memberId={}", memberId, e);
            return false;
        }
    }

    /**
     * 结算调整(Figma G2「调整待处理 / 已执行调整」)。
     * 扣回按「被扣回的那个 member」记账,俱乐部主理人收分润时正是这个 member。
     * ⚠️ 取不到时三个字段一律给 null,不给 0 —— 页面会渲染成「—」。
     *    把「查不到」显示成「没有待处理调整」,等于告诉主理人这笔钱是干净的。
     */
    private Map<String, Object> clawbackAdjustments(Long memberId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pendingAmount", null);
        out.put("executedAmount", null);
        out.put("pendingReason", null);
        try {
            List<MerchantClawbackApplication> pending = merchantClawbackService.list(
                    memberId, MerchantClawbackApplication.STATUS_PENDING_REVIEW);
            List<MerchantClawbackApplication> executed = merchantClawbackService.list(
                    memberId, MerchantClawbackApplication.STATUS_EXECUTED);
            if (pending == null || executed == null) return out;
            out.put("pendingAmount", sumAmount(pending));
            out.put("executedAmount", sumAmount(executed));
            for (MerchantClawbackApplication row : pending) {
                if (row != null && row.getReason() != null && !row.getReason().trim().isEmpty()) {
                    out.put("pendingReason", row.getReason().trim());
                    break;
                }
            }
        } catch (RuntimeException e) {
            // 保持三个 null:查不到就是不知道
        }
        return out;
    }

    private static BigDecimal sumAmount(List<MerchantClawbackApplication> rows) {
        BigDecimal total = BigDecimal.ZERO;
        for (MerchantClawbackApplication row : rows) {
            if (row != null && row.getAmount() != null) total = total.add(row.getAmount());
        }
        return total;
    }

    @Operation(summary = "近90天信用摘要(履约率/违约计数)")
    @PostMapping("/credit")
    public AjaxResult credit(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        // 默认查本人;传 memberId 可查他人(主页展示合作方履约率)
        Long target = memberId;
        if (body != null && body.get("memberId") != null) {
            try { target = Long.valueOf(String.valueOf(body.get("memberId")).trim()); } catch (Exception ignore) {}
        }
        return AjaxResult.success(coopInviteService.creditSummary(target));
    }

    @Operation(summary = "统一候选池(发布者视角:某主题的邀约候选 + 报名候选)")
    @PostMapping("/candidates")
    public AjaxResult candidates(@RequestBody CoopInvite query) {
        Long principalMemberId;
        try { principalMemberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (query == null || query.getTopicId() == null) return error("缺少主题");
        Long memberId;
        try {
            memberId = projectOwnerMemberId(principalMemberId, query.getScope());
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        CmsTopic topic = cmsTopicService.selectCmsTopicById(query.getTopicId());
        if (topic == null) return error("主题不存在");
        if (OwnershipUtils.notOwner(topic.getMemberId(), memberId)) return error("仅主题发布者可查看候选池");
        if (topic.getClubId() != null && clubService.selectClubById(topic.getClubId()) == null) {
            return error("主题所属俱乐部已解散，候选池不可用");
        }

        // 邀约候选(我发出的对该主题的邀约)
        CoopInvite iq = new CoopInvite();
        iq.setTopicId(query.getTopicId());
        List<CoopInvite> invites = visibleInvites(coopInviteService.selectCoopInviteList(iq));

        // 报名候选(主动报名该主题的商家)
        CmsRegistrationMerchant rq = new CmsRegistrationMerchant();
        rq.setTopicId(query.getTopicId());
        rq.setDelFlag(0);
        List<Map<String, Object>> registrationRows =
                registrationRows(cmsRegistrationMerchantService.selectCmsRegistrationMerchantList(rq));

        // 俱乐部承接申请(P0-4 开放报名路径):带俱乐部名 + 是否已对该俱乐部发过邀约,
        // 供发布者婉拒或直接跳发 type1 带条款邀约(申请不成单,§3.6)
        List<Map<String, Object>> clubApplies = new ArrayList<>();
        for (com.chengyinhub.business.domain.CoopClubApply a : coopClubApplyService.selectByTopic(query.getTopicId())) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", a.getId());
            row.put("clubId", a.getClubId());
            row.put("memberId", a.getMemberId());
            row.put("status", a.getStatus());
            row.put("message", a.getMessage());
            row.put("createTime", a.getCreateTime());
            Club applyClub = a.getClubId() == null ? null : clubService.selectClubById(a.getClubId());
            // 申请行保留审计；父俱乐部已解散时不再作为发布者可操作的候选展示。
            if (applyClub == null) continue;
            row.put("clubName", applyClub.getName() == null ? "" : applyClub.getName());
            row.put("clubLogo", applyClub.getLogo());
            Integer inviteStatus = null;
            for (CoopInvite i : invites) {
                if ("club".equals(i.getToType()) && a.getClubId() != null && a.getClubId().equals(i.getToId())) {
                    // 同俱乐部多条邀约取「最活跃」的一条:已接受 > 待确认 > 其他
                    if (inviteStatus == null || (i.getStatus() != null && i.getStatus() == 1)
                            || (i.getStatus() != null && i.getStatus() == 0 && inviteStatus != 1)) {
                        inviteStatus = i.getStatus();
                    }
                }
            }
            row.put("inviteStatus", inviteStatus);
            clubApplies.add(row);
        }

        Map<String, Object> d = new HashMap<>();
        d.put("invites", invites);
        d.put("registrations", registrationRows);
        d.put("clubApplies", clubApplies);
        return AjaxResult.success(d);
    }

    @Operation(summary = "候选池确认报名占槽(合作邀约前置 + 清扫同节点候选)")
    @PostMapping("/candidates/confirm")
    public AjaxResult confirmCandidate(@RequestBody Map<String, Object> body) {
        Long principalMemberId;
        try { principalMemberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        Long regId = body == null ? null : toLong(body.get("registrationId"));
        if (regId == null) return error("缺少报名");
        Long memberId;
        try {
            memberId = projectOwnerMemberId(principalMemberId,
                    body == null || body.get("scope") == null
                            ? null : String.valueOf(body.get("scope")));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        CmsRegistrationMerchant reg = cmsRegistrationMerchantService.selectCmsRegistrationMerchantById(regId);
        if (reg == null) return error("报名不存在");
        // 校验:仅该报名对应主题的发布者可确认
        CmsTopic topic = reg.getTopicId() != null ? cmsTopicService.selectCmsTopicById(reg.getTopicId()) : null;
        if (topic == null) return error("主题不存在");
        if (OwnershipUtils.notOwner(topic.getMemberId(), memberId)) return error("仅主题发布者可确认候选");
        DispatchResult dispatch = dispatchService.confirmRegistration(regId);
        if (!dispatch.isSelected()) return error(dispatch.getMessage());

        // 【§3.6】选中 = 占住这个竞争位,【不是】成交 —— 这里不生成 coop_order。
        //
        // 为什么:条款不挂在报名上,挂在【邀约】上。定稿 §6.5-C:「主理人/俱乐部在 coop 邀约页选商家、
        // 随邀约带分账条款,对方接受即生成 coop_order(条款快照冻结,结算只认合作单)」。三条理由:
        //   ① 定价权归吃残差的人(D15):发布者拿 margin、卖不掉是他亏 ⇒ 他定价;
        //   ② 商家有否决权:接受即成交、不接受就不接 —— 平台不定规则、不设指导价;
        //   ③ ★ 报名那一刻定价所需的信息还不存在(不知道能卖多少、有几家在争)⇒ 逼他此刻报价 = 逼他瞎猜。
        //      这正是 sharing_rate 至今没人填、最后被结算兜底污染成 100 的根因。
        //      加字段解决不了,换时点才行。
        //
        // 所以「从报名里抄条款生成合作单」这条路【整条不存在】—— 它既抄不到真条款(报名没有),
        // 也会绕开 acceptInviteWithLock 的四道闸(主题锁价禁入 / 主题级合作方上限 / 被邀商家封顶 5 家 / 保证金)。
        //
        // 下一步由发布者走现成的 POST /api/coop/invite 发 type0 邀约(带条款),
        // 商家接受 → acceptInviteWithLock → coop_order。结算只认那张单。

        return AjaxResult.success("已选定该候选,请向该商家发出带条款的合作邀约(接受后才成合作单)");
    }

    /** 单次聚合上限:主题数与候选行数都封顶,防止主办方主题一多把响应拉爆。 */
    private static final int RECEIVED_CANDIDATE_TOPIC_LIMIT = 200;
    private static final int RECEIVED_CANDIDATE_ROW_LIMIT = 300;

    /**
     * 收到的报名候选(跨主题聚合,铃铛「收到的」用)。
     *
     * <p>与 {@link #candidates} 同一归属口径:只枚举 member_id = 登录人(scope=MERCHANT 时为
     * owner)的主题,逐主题复用同一套报名查询与行装配 —— 别人的主题连枚举都进不来。
     */
    @Operation(summary = "收到的报名候选(跨主题聚合,发布者视角)")
    @PostMapping("/candidates/received")
    public AjaxResult candidatesReceived(@RequestBody(required = false) Map<String, Object> body) {
        Long principalMemberId;
        try { principalMemberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        Long memberId;
        try {
            memberId = projectOwnerMemberId(principalMemberId,
                    body == null || body.get("scope") == null ? null : String.valueOf(body.get("scope")));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        CmsTopic probe = new CmsTopic();
        probe.setMemberId(memberId);
        probe.setDelFlag(0);
        List<CmsTopic> topics = cmsTopicService.selectCmsTopicList(probe);
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean hasMore = false;
        if (topics != null && !topics.isEmpty()) {
            List<CmsTopic> sorted = new ArrayList<>(topics);
            sorted.sort(Comparator.comparing(CmsTopic::getId, Comparator.nullsLast(Comparator.reverseOrder())));
            int inspected = 0;
            for (CmsTopic topic : sorted) {
                if (topic == null || topic.getId() == null) continue;
                if (inspected >= RECEIVED_CANDIDATE_TOPIC_LIMIT) { hasMore = true; break; }
                inspected++;
                // 父俱乐部已解散的主题与 /candidates 同闸:不再作为可操作候选
                if (topic.getClubId() != null && clubService.selectClubById(topic.getClubId()) == null) continue;
                CmsRegistrationMerchant rq = new CmsRegistrationMerchant();
                rq.setTopicId(topic.getId());
                rq.setDelFlag(0);
                List<CmsRegistrationMerchant> registrations =
                        cmsRegistrationMerchantService.selectCmsRegistrationMerchantList(rq);
                if (registrations != null && !registrations.isEmpty()) {
                    registrations = new ArrayList<>(registrations);
                    // 待处理在前,同状态新报名在前 —— 截断时被丢掉的必须是历史行
                    registrations.sort((a, b) -> {
                        int pa = a.getAuditStatus() != null && a.getAuditStatus() == 0L ? 0 : 1;
                        int pb = b.getAuditStatus() != null && b.getAuditStatus() == 0L ? 0 : 1;
                        if (pa != pb) return pa - pb;
                        long ia = a.getId() == null ? 0L : a.getId();
                        long ib = b.getId() == null ? 0L : b.getId();
                        return Long.compare(ib, ia);
                    });
                }
                for (Map<String, Object> row : registrationRows(registrations)) {
                    if (rows.size() >= RECEIVED_CANDIDATE_ROW_LIMIT) { hasMore = true; break; }
                    row.put("topicId", topic.getId());
                    row.put("topicName", topic.getName() == null ? "" : topic.getName());
                    rows.add(row);
                }
                if (rows.size() >= RECEIVED_CANDIDATE_ROW_LIMIT) { hasMore = true; break; }
            }
        }
        Map<String, Object> d = new HashMap<>();
        d.put("rows", rows);
        d.put("hasMore", hasMore);
        return AjaxResult.success(d);
    }

    /** 候选池报名行统一装配:只给公开展示身份,不透出联系方式。 */
    private List<Map<String, Object>> registrationRows(List<CmsRegistrationMerchant> registrations) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (registrations == null) return rows;
        for (CmsRegistrationMerchant registration : registrations) {
            if (registration == null) continue;
            Map<String, Object> row = new HashMap<>();
            row.put("id", registration.getId());
            row.put("memberId", registration.getMemberId());
            row.put("auditStatus", registration.getAuditStatus());
            row.put("addressName", registration.getAddressName());
            row.put("address", registration.getAddress());
            MmsMerchant merchant = registration.getMemberId() == null ? null
                    : mmsMerchantService.selectMmsMerchantByMemberId(registration.getMemberId());
            row.put("merchantName", merchant == null ? null : merchant.getName());
            row.put("merchantLogo", merchant == null ? null : merchant.getLogo());
            row.put("merchantMeta", merchant == null ? null : merchant.getAddress());
            rows.add(row);
        }
        return rows;
    }

    private Long projectOwnerMemberId(Long principalMemberId, String scope) {
        if (!MerchantOperationScope.isMerchant(scope)) return principalMemberId;
        return merchantAccessService.require(principalMemberId, MerchantPermission.PROJECT_MANAGE)
                .getOwnerMemberId();
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        try { return Long.valueOf(String.valueOf(o).trim()); } catch (Exception e) { return null; }
    }

    /** 列表的站内可见提醒:当前会员应缴、以及已取消/结算后仍待退款的合作。 */
    private void markDepositOwed(List<CoopInvite> invites, Long memberId) {
        if (invites == null) return;
        for (CoopInvite invite : invites) {
            boolean owed = invite.getStatus() != null && invite.getStatus() == 1
                    && invite.getDepositStatus() != null && invite.getDepositStatus() == 5
                    && memberId.equals(coopInviteService.depositOwedParty(invite));
            invite.setDepositOwed(owed);
            boolean refundPending = invite.getDepositStatus() != null && invite.getDepositStatus() == 2
                    && invite.getStatus() != null && invite.getStatus() == 3;
            if (!refundPending && invite.getDepositStatus() != null && invite.getDepositStatus() == 2
                    && invite.getStatus() != null && invite.getStatus() == 1 && invite.getTopicId() != null) {
                CmsTopic topic = cmsTopicService.selectCmsTopicById(invite.getTopicId());
                refundPending = topic != null && topic.getIsSettlement() != null && topic.getIsSettlement() == 1;
            }
            invite.setDepositRefundPending(refundPending);
        }
    }

    /**
     * B5 合作方身份:列表每条邀约补上「对方是谁」。
     *
     * <p>此前出参只有 to_type/to_id 这类内部标识,合作区只能渲染成「俱乐部 · 合作中」,
     * 商家看不出到底是谁在跟他合作。
     *
     * <p><b>名称恒给</b>(可公开信息);<b>联系方式(负责人/电话)按 §3.8 四行表分两种闸</b> ——
     * 两行的闸不同,别拿第一行那条套全场:
     * <ul>
     *   <li><b>我发出的(sent)= 主办方视角</b>,§3.8 第二行,闸是 {@code topic.memberId == 我},
     *       <b>没有 status 条件</b>(§3.4 也写明「招募中 → 名单(名称 + 电话)」)。
     *       /invite 已强制「只能为自己发布的主题发起合作」⇒ sent 恒满足这个闸 ⇒ 不摘。</li>
     *   <li><b>我收到的(received)= 承接方视角</b>,§3.8 第一行,闸是邀约 {@code status=1}(已接受)。</li>
     * </ul>
     * 「承接商家 → 其他承接商家」(表第三行 ❌)不在我的邀约里,天然拿不到;
     * 玩家(第四行 ❌)根本不走这个端点。
     */
    private void fillPartner(List<CoopInvite> invites, Long memberId) {
        if (invites == null) return;
        for (CoopInvite inv : invites) {
            // 我发出的 → 对方是受邀方;我收到的 → 对方是发起方
            boolean iAmInviter = memberId.equals(resolveFromOwner(inv));
            CoopPartnerVO partner = iAmInviter
                    ? partnerOfInvitee(inv)
                    : partnerOfInviter(inv);
            if (partner != null && !iAmInviter && (inv.getStatus() == null || inv.getStatus() != 1)) {
                partner.stripContact();
            }
            inv.setPartner(partner);
        }
    }

    /** 受邀方展示信息:club 取俱乐部档案(有负责人),merchant/talent 取该会员的商家档案。 */
    private CoopPartnerVO partnerOfInvitee(CoopInvite inv) {
        if ("club".equals(inv.getToType())) {
            return partnerOfClub(inv.getToId());
        }
        return partnerOfMember(inv.getToId());
    }

    private CoopPartnerVO partnerOfInviter(CoopInvite inv) {
        return "club".equals(inv.getFromType())
                ? partnerOfClub(inv.getFromId()) : partnerOfMember(inv.getFromId());
    }

    private CoopPartnerVO partnerOfClub(Long clubId) {
        Club club = clubId == null ? null : clubService.selectClubById(clubId);
        return club == null ? null : CoopPartnerVO.of(club.getName(), club.getLeaderName(), club.getPhone());
    }

    /** 会员侧合作方:优先商家档案(商家之间认的是店名),没档案退回昵称、且没有联系方式可给。 */
    private CoopPartnerVO partnerOfMember(Long targetMemberId) {
        if (targetMemberId == null) return null;
        MmsMerchant merchant = null;
        // 查商家档案失败时退回昵称档(无联系方式),属 fail-closed 的有意降级 —— 但**不能静默**:
        // 原来这里是 catch(Exception ignore){},商家名与电话会悄悄退化成昵称+无联系方式,
        // 页面只是"显示得少一点",没有任何信号,排查时无从下手。
        try {
            merchant = mmsMerchantService.selectMmsMerchantByMemberId(targetMemberId);
        } catch (Exception e) {
            log.warn("[coop] 合作方商家档案读取失败,降级为昵称档 targetMemberId={} reason={}",
                    targetMemberId, e.getClass().getSimpleName());
        }
        if (merchant != null) return CoopPartnerVO.of(merchant.getName(), null, merchant.getPhone());
        String nick = nickOf(targetMemberId);
        return nick.isEmpty() ? null : CoopPartnerVO.of(nick, null, null);
    }

    @Operation(summary = "我的协作邀请(发出的+收到的)")
    @PostMapping("/list")
    public AjaxResult list(@RequestBody(required = false) CoopInvite query) {
        Long principalMemberId;
        try { principalMemberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        MerchantAccessContext merchantAccess = null;
        try {
            merchantAccess = merchantAccessService.require(
                    principalMemberId, MerchantPermission.COOP_MANAGE);
        } catch (ServiceException e) {
            // 普通发布者/俱乐部主理人仍可使用个人或俱乐部合作路径；但只要存在商家行，
            // 旧 JWT 就不能把“商家已停用”降级成普通用户后继续读取结算与合作方电话。
            MmsMerchant merchant = mmsMerchantService.selectMmsMerchantByMemberId(principalMemberId);
            if (merchant != null) return new AjaxResult(e.getCode(), e.getMessage());
        }
        Long memberId = merchantAccess == null
                ? principalMemberId : merchantAccess.getOwnerMemberId();
        Integer statusFilter = query == null ? null : query.getStatus();
        List<Club> myClubs = clubService.selectClubsByMemberId(principalMemberId);

        // 发出的
        CoopInvite sentQ = new CoopInvite();
        sentQ.setFromId(memberId);
        sentQ.setStatus(statusFilter);
        // ⚠️ 只按 from_id 查会把**官方邀约**一起捞进来:官方邀约写的是同一张 coop_invite,
        //    from_type=official 且 from_id = 官方发布账号的 memberId。而 official_publish_whitelist
        //    当前配的 100092 本身就是商家 ⇒ 它打开合作列表会看到本该只出现在
        //    /api/official/invites 的官方邀约(那边是按 FROM_OFFICIAL 正向过滤的,两个界面本就分开)。
        //    mapper 的 fromType 只支持正向等值过滤,拿不到「排除某个值」,故在此按域剔除。
        List<CoopInvite> sent = new ArrayList<>();
        for (CoopInvite it : coopInviteService.selectCoopInviteListReadOnly(sentQ)) {
            if (CoopInvite.FROM_OFFICIAL.equals(it.getFromType()) || "club".equals(it.getFromType())) continue;
            // 解散后保留邀约行做审计，但普通合作列表不能继续展示一个已不存在的俱乐部。
            if (!isInviteVisible(it)) continue;
            sent.add(it);
        }
        if (myClubs != null) {
            for (Club club : myClubs) {
                CoopInvite sentClub = new CoopInvite();
                sentClub.setFromType("club");
                sentClub.setFromId(club.getId());
                sentClub.setStatus(statusFilter);
                for (CoopInvite invite : visibleInvites(coopInviteService.selectCoopInviteListReadOnly(sentClub))) {
                    invite.setViewerPartyName(club.getName());
                    sent.add(invite);
                }
            }
        }

        // 收到的:作为商家(to_type=merchant,to_id=memberId)
        List<CoopInvite> received = new ArrayList<>();
        if (merchantAccess != null) {
            CoopInvite recvMerchant = new CoopInvite();
            recvMerchant.setToType("merchant");
            recvMerchant.setToId(memberId);
            recvMerchant.setStatus(statusFilter);
            received.addAll(visibleInvites(coopInviteService.selectCoopInviteListReadOnly(recvMerchant)));
        }
        // 收到的:作为俱乐部群主(to_type=club,to_id ∈ 我的俱乐部)
        if (myClubs != null) {
            for (Club c : myClubs) {
                CoopInvite recvClub = new CoopInvite();
                recvClub.setToType("club");
                recvClub.setToId(c.getId());
                recvClub.setStatus(statusFilter);
                for (CoopInvite invite : visibleInvites(coopInviteService.selectCoopInviteListReadOnly(recvClub))) {
                    invite.setViewerPartyName(c.getName());
                    received.add(invite);
                }
            }
        }
        markDepositOwed(sent, memberId);
        markDepositOwed(received, memberId);
        fillPartner(sent, memberId);
        fillPartner(received, memberId);

        // B1-8 槽位提示:经典定向按 game(挂起≤3)、自由定向按 topic(已接受≤5),前端渲染「2/3」占用
        Set<Long> games = new HashSet<>();
        Set<Long> topics = new HashSet<>();
        for (CoopInvite i : sent) {
            if (i.getGameId() != null) games.add(i.getGameId());
            else if (i.getTopicId() != null) topics.add(i.getTopicId());
        }
        Map<String, Object> byGame = new HashMap<>();
        for (Long g : games) {
            CoopInvite pq = new CoopInvite();
            pq.setGameId(g);
            pq.setStatus(0);
            Map<String, Object> m = new HashMap<>();
            m.put("pending", mutableInviteCount(coopInviteService.selectCoopInviteListReadOnly(pq)));
            m.put("cap", 3);
            byGame.put(String.valueOf(g), m);
        }
        Map<String, Object> byTopic = new HashMap<>();
        for (Long tp : topics) {
            CoopInvite aq = new CoopInvite();
            aq.setTopicId(tp);
            aq.setStatus(1);
            Map<String, Object> m = new HashMap<>();
            m.put("accepted", mutableInviteCount(coopInviteService.selectCoopInviteListReadOnly(aq)));
            m.put("cap", 5);
            byTopic.put(String.valueOf(tp), m);
        }
        Map<String, Object> slots = new HashMap<>();
        slots.put("byGame", byGame);
        slots.put("byTopic", byTopic);

        // 卡面要的主题信息现填(见 fillTopicFacts)。两条列表一起填,同一个主题只查一次。
        fillTopicFacts(sent, received);

        Map<String, Object> d = new HashMap<>();
        d.put("sent", sent);
        d.put("received", received);
        d.put("slots", slots);
        return AjaxResult.success(d);
    }

    /**
     * 给邀约卡补主题信息:封面 / 档期 / 路线名 / 地点(稿 261:277 · 261:349)。
     *
     * <p>coop_invite 表里只有 topic_id,而卡面上商家要看的是「哪条路线、什么时候、在哪」。
     * 原来一个字段都没下发,前端只能显示「主题 #123」—— 把一个内部 id 摆给商家看,
     * 他既认不出是哪条路线,也没法据此决定接不接。
     *
     * <p>⚠️ 按 topicId 去重后再查:两条列表加起来可能有几十条邀约,但它们往往指向同几条路线。
     * 不去重就是 N+1,而这是一个首屏接口。
     *
     * <p>⚠️ 查不到的主题(已删/无权)保持字段为空 —— 前端那边 topicText 会退回「主题 #id」,
     * 与其编一个名字,不如让它维持原样。
     */
    private void fillTopicFacts(List<CoopInvite> sent, List<CoopInvite> received) {
        List<CoopInvite> all = new ArrayList<>();
        if (sent != null) all.addAll(sent);
        if (received != null) all.addAll(received);
        Map<Long, CmsTopic> cache = new HashMap<>();
        for (CoopInvite it : all) {
            Long tid = it == null ? null : it.getTopicId();
            if (it == null) continue;
            boolean supplyCandidate = Integer.valueOf(0).equals(it.getInviteType())
                    && CoopInvite.TO_MERCHANT.equals(it.getToType()) && it.getNodeId() == null;
            // A recorded V1 station proves applicability; missing historical topic/type is not false.
            it.setRequiresSupplyStation(supplyCandidate
                    ? (it.getSupplyStationId() == null ? null : Boolean.TRUE) : Boolean.FALSE);
            if (tid == null || tid <= 0) continue;
            if (!cache.containsKey(tid)) {
                CmsTopic t = null;
                try { t = cmsTopicService.selectCmsTopicById(tid); } catch (Exception ignored) { }
                cache.put(tid, t);       // 查不到也记 null,免得同一个坏 id 反复查
            }
            CmsTopic topic = cache.get(tid);
            if (topic == null) continue;
            if (supplyCandidate && topic.getProductType() != null)
                it.setRequiresSupplyStation(Integer.valueOf(com.chengyinhub.common.constant.ProductType.ORIENTEERING).equals(topic.getProductType()));
            it.setTopicName(topic.getName());
            it.setTopicImgUrl(topic.getImgUrl());
            it.setTopicStartDate(topic.getStartDate());
            it.setTopicAddressName(topic.getAddressName());
            // 发件箱「取消合作」置灰用:与取消闸同一判据(锁价后单方取消由后端拒绝转客服)
            it.setTermsFrozen(com.chengyinhub.business.domain.enums.TopicLifecycleState
                    .commercialTermsFrozen(topic.getLifecycle()));
        }
    }

    /** 普通消费面隐藏已解散俱乐部关联邀约；数据库历史仍由后台审计查询保留。 */
    private List<CoopInvite> visibleInvites(List<CoopInvite> source) {
        List<CoopInvite> visible = new ArrayList<>();
        if (source == null) return visible;
        for (CoopInvite invite : source) {
            if (isInviteVisible(invite)) visible.add(invite);
        }
        return visible;
    }

    private int mutableInviteCount(List<CoopInvite> invites) {
        int count = 0;
        if (invites == null) return count;
        for (CoopInvite invite : invites) {
            if (invite != null
                    && !Integer.valueOf(CoopInvite.TYPE_LEGACY_MERCHANT_NODE).equals(invite.getInviteType())) {
                count++;
            }
        }
        return count;
    }

    private boolean isInviteVisible(CoopInvite invite) {
        if (invite == null) return false;
        if ("club".equals(invite.getToType())
                && (invite.getToId() == null || clubService.selectClubById(invite.getToId()) == null)) {
            return false;
        }
        if (invite.getTopicId() != null) {
            CmsTopic topic = cmsTopicService.selectCmsTopicById(invite.getTopicId());
            if (topic == null) return false;
            if (topic.getClubId() != null && topic.getClubId() > 0
                    && clubService.selectClubById(topic.getClubId()) == null) {
                return false;
            }
        }
        return true;
    }

    private boolean validPerkAmount(BigDecimal value, boolean allowNull) {
        if (value == null) return allowNull;
        if (value.compareTo(BigDecimal.ZERO) < 0) return false;
        if (!allowNull && value.compareTo(BigDecimal.ZERO) == 0) return false;
        return value.compareTo(MAX_PERK_AMOUNT) <= 0
                && value.stripTrailingZeros().scale() <= 2;
    }

    /** 受邀方的"处理人 memberId":club 受邀=该俱乐部群主;merchant/talent 受邀=to_id 本身 */
    private Long resolveToOwner(CoopInvite inv) {
        if ("club".equals(inv.getToType())) {
            Club club = clubService.selectClubById(inv.getToId());
            return club == null ? null : club.getMemberId();
        }
        return inv.getToId();
    }

    /** 发起方的处理人 memberId；不将 club.id 当作 memberId 直接比较。 */
    private Long resolveFromOwner(CoopInvite inv) {
        if ("club".equals(inv.getFromType())) {
            Club club = clubService.selectClubById(inv.getFromId());
            return club == null ? null : club.getMemberId();
        }
        return inv.getFromId();
    }

    /**
     * 旧协作域把商家合同主体记为 ownerMemberId；员工 principal 不能直接拿来与 from/to_id 比。
     * 非商家一侧仍保留本人身份，商家一侧则每次通过访问服务重验 active 与 COOP_MANAGE。
     */
    private Long resolveActionContractMemberId(Long principalMemberId, CoopInvite invite,
                                               Long fromOwner, Long toOwner,
                                               boolean allowFrom, boolean allowTo) {
        if (allowFrom && !"merchant".equals(invite.getFromType())
                && fromOwner != null && fromOwner.equals(principalMemberId)) {
            return principalMemberId;
        }
        if (allowTo && !CoopInvite.TO_MERCHANT.equals(invite.getToType())
                && toOwner != null && toOwner.equals(principalMemberId)) {
            return principalMemberId;
        }
        boolean merchantFrom = allowFrom && "merchant".equals(invite.getFromType());
        boolean merchantTo = allowTo && CoopInvite.TO_MERCHANT.equals(invite.getToType());
        if (merchantFrom || merchantTo) {
            MerchantAccessContext access = merchantAccessService.require(
                    principalMemberId, MerchantPermission.COOP_MANAGE);
            Long merchantOwner = access.getOwnerMemberId();
            if ((merchantFrom && merchantOwner != null && merchantOwner.equals(fromOwner))
                    || (merchantTo && merchantOwner != null && merchantOwner.equals(toOwner))) {
                return merchantOwner;
            }
        }
        return principalMemberId;
    }
}
