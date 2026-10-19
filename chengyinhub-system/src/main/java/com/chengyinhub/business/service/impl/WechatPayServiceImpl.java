package com.chengyinhub.business.service.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IClubMembershipService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.PaymentPreorderException;
import com.chengyinhub.business.service.WechatOrderNotExistException;
import com.chengyinhub.business.service.RefundRequestResult;
import com.chengyinhub.common.config.WxPayConfig;
import com.chengyinhub.system.service.impl.SysUserServiceImpl;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.cipher.AeadCipher;
import com.wechat.pay.java.core.cipher.Verifier;
import com.wechat.pay.java.core.exception.ValidationException;
import com.wechat.pay.java.core.notification.NotificationConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.jsapi.JsapiService;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.*;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.AmountReq;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.QueryByOutRefundNoRequest;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import com.wechat.pay.java.service.refund.model.Status;
import com.wechat.pay.java.service.transferbatch.TransferBatchService;
import com.wechat.pay.java.service.transferbatch.model.InitiateBatchTransferRequest;
import com.wechat.pay.java.service.transferbatch.model.InitiateBatchTransferResponse;
import com.wechat.pay.java.service.transferbatch.model.TransferDetailInput;
import com.wechat.pay.java.service.transferbatch.model.GetTransferBatchByOutNoRequest;
import com.wechat.pay.java.service.transferbatch.model.TransferBatchEntity;
import com.wechat.pay.java.service.transferbatch.model.GetTransferDetailByOutNoRequest;
import com.wechat.pay.java.service.transferbatch.model.TransferDetailEntity;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

/**
 * 微信支付服务实现 (适配新版SDK v3) - 修正版
 */
@Service
public class WechatPayServiceImpl implements IWechatPayService {

    private static final Logger logger = LoggerFactory.getLogger(WechatPayServiceImpl.class);

    @Autowired
    private ICmsRegistrationService registrationService;

    @Autowired
    private AlarmService alarmService;

    @Autowired
    @Lazy
    private ICoopInviteService coopInviteService;

    @Autowired
    @Lazy
    private com.chengyinhub.business.service.IMerchantCommerceService merchantCommerceService;

    /** ObjectProvider 打破 membership 下单依赖 IWechatPayService 形成的启动期依赖环。 */
    @Autowired
    private ObjectProvider<IClubMembershipService> clubMembershipServiceProvider;

    // 业务退款服务(退款结果通知回调回写打款态);SDK 的 RefundService 另名 refundService,勿混
    @Autowired
    private com.chengyinhub.business.service.IRefundService refundBizService;

    @Autowired
    private WxPayConfig wxPayConfig;

    private JsapiService jsapiService;
    private JsapiServiceExtension jsapiServiceExtension;
    /** App 支付(trade_type=APP)。与 JSAPI 共用同一套商户配置,只是下单 appid 不同。 */
    private com.wechat.pay.java.service.payments.app.AppServiceExtension appServiceExtension;
    private RefundService refundService;
    private TransferBatchService transferBatchService;
    private Config config;

    private NotificationParser notificationParser;

    /** New W18 ingress only; no default bean, checkout activation or subscription fallback. */
    @Autowired(required = false)
    private ObjectProvider<com.chengyinhub.business.marketplace.paid.WorkshopPaidPaymentHandler> workshopPaidPaymentHandler;

    @Autowired
    private IUmsMemberService memberService;

    /**
     * 初始化微信支付服务 - 容错版本
     */
    @PostConstruct
    public void init() {
        if (!wxPayConfig.isWechatPayEnabled()) {
            logger.info("微信支付功能已禁用");
            return;
        }
        try {
            // 获取配置
            config = wxPayConfig.getConfig();

            // 如果配置获取成功，初始化服务
            if (config != null) {
                NotificationConfig notificationConfig = wxPayConfig.getNotificationConfig();

                // 初始化服务
                jsapiService = new JsapiService.Builder().config(config).build();
                jsapiServiceExtension = new JsapiServiceExtension.Builder().config(config).build();
                appServiceExtension = new com.wechat.pay.java.service.payments.app.AppServiceExtension.Builder().config(config).build();
                refundService = new RefundService.Builder().config(config).build();
                transferBatchService = new TransferBatchService.Builder().config(config).build();

                // 初始化NotificationParser
                notificationParser = new NotificationParser(notificationConfig);

                logger.info("微信支付服务初始化成功");
            } else {
                logger.warn("微信支付配置获取失败，支付功能将不可用");
            }

        } catch (Exception e) {
            logger.warn("微信支付服务初始化失败，但应用继续启动。错误: {}", e.getMessage());
            logger.warn("这不会影响其他功能的正常使用，请在准备好正确的SDK版本后重新启动");
            // 可以选择将相关服务设置为null或标记为不可用状态
            jsapiService = null;
            jsapiServiceExtension = null;
            appServiceExtension = null;
            notificationParser = null;
            refundService = null;
            transferBatchService = null;
        }
    }

    @Override
    public boolean isReady() {
        return wxPayConfig.isWechatPayEnabled()
                && jsapiService != null
                && jsapiServiceExtension != null
                && refundService != null
                && notificationParser != null;
    }

    @Override
    public boolean isAppReady() {
        if (!isReady() || appServiceExtension == null) {
            return false;
        }
        String appAppid = wxPayConfig.getAppAppid();
        return appAppid != null && !appAppid.trim().isEmpty();
    }

    /**
     * 微信原路退款。out_refund_no 由调用方按业务确定性生成,微信按 (mchid,out_refund_no) 幂等,
     * 重复调用同号同额不会二次退款。
     */
    @Override
    public RefundRequestResult submitRefund(String outTradeNo, String outRefundNo,
                                            java.math.BigDecimal refundAmount,
                                            java.math.BigDecimal totalAmount, String reason) {
        if (refundService == null) {
            return RefundRequestResult.UNKNOWN;
        }
        if (refundAmount == null || refundAmount.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            return RefundRequestResult.DEFINITIVE_FAILED;
        }
        java.math.BigDecimal effectiveTotal = totalAmount == null ? refundAmount : totalAmount;
        final long refundCents;
        final long totalCents;
        try {
            refundCents = toWechatRefundCents(refundAmount);
            totalCents = toWechatRefundCents(effectiveTotal);
            if (refundCents > totalCents) {
                return RefundRequestResult.DEFINITIVE_FAILED;
            }
        } catch (ArithmeticException | IllegalArgumentException invalidAmount) {
            return RefundRequestResult.DEFINITIVE_FAILED;
        }
        try {
            CreateRequest request = new CreateRequest();
            request.setOutTradeNo(outTradeNo);
            request.setOutRefundNo(outRefundNo);
            if (reason != null && !reason.isEmpty()) {
                request.setReason(reason.length() > 80 ? reason.substring(0, 80) : reason);
            }
            // 退款结果通知回调:配了才设,未配则依赖商户平台默认退款回调(P3 异步回写 payout 终态)
            String refundNotifyUrl = wxPayConfig.getRefundNotifyUrl();
            if (refundNotifyUrl != null && !refundNotifyUrl.trim().isEmpty()) {
                request.setNotifyUrl(refundNotifyUrl);
            }
            AmountReq amount = new AmountReq();
            amount.setRefund(refundCents);
            amount.setTotal(totalCents);
            amount.setCurrency("CNY");
            request.setAmount(amount);

            Refund refund = refundService.create(request);
            Status st = (refund == null) ? null : refund.getStatus();
            if (st == Status.SUCCESS || st == Status.PROCESSING) {
                return RefundRequestResult.ACCEPTED;
            }
            if (st == Status.CLOSED || st == Status.ABNORMAL) {
                return RefundRequestResult.DEFINITIVE_FAILED;
            }
            return RefundRequestResult.UNKNOWN;
        } catch (Exception e) {
            logger.warn("微信原路退款结果未知 outTradeNo={} outRefundNo={}: {}",
                    outTradeNo, outRefundNo, e.getMessage());
            return RefundRequestResult.UNKNOWN;
        }
    }

    /**
     * 商家转账到零钱(批量转账 API,单笔一批)。微信按 (mchid, out_batch_no) 幂等。
     * 返回 ok=true 表示微信已受理(WAIT_PAY/ACCEPTED);失败返回 ok=false + error,调用方须兜底退回余额。
     */
    @Override
    public Map<String, String> transferToBalance(String outBatchNo, String outDetailNo,
                                                  java.math.BigDecimal amountYuan, String openid, String remark,
                                                  String appid) {
        Map<String, String> r = new HashMap<>();
        if (transferBatchService == null) {
            r.put("ok", "false");
            r.put("error", "转账服务未就绪(商户号需开通「商家转账到零钱」并配置证书)");
            return r;
        }
        if (amountYuan == null || amountYuan.compareTo(java.math.BigDecimal.ZERO) <= 0
                || openid == null || openid.isEmpty()) {
            r.put("ok", "false");
            r.put("error", "转账参数不合法(金额或openid为空)");
            return r;
        }
        // appid 不再由本方法私自决定 —— 它必须与 openid 同源,而只有调用方知道
        // 这个 openid 是哪个应用发的。为空时明确拒绝,不回落到某个"默认"appid:
        // 回落会让微信按 (appid,openid) 判不匹配而拒付,错误指向 openid 而非配置。
        if (appid == null || appid.trim().isEmpty()) {
            r.put("ok", "false");
            r.put("error", "转账 appid 未指定(必须与 openid 同源)");
            return r;
        }
        final long fen;
        try {
            fen = toWechatPaymentCents(amountYuan);
        } catch (ArithmeticException | IllegalArgumentException invalidAmount) {
            r.put("ok", "false");
            r.put("error", "转账金额不合法");
            return r;
        }
        try {
            String rmk = (remark == null || remark.isEmpty()) ? "分润提现" : (remark.length() > 32 ? remark.substring(0, 32) : remark);

            TransferDetailInput detail = new TransferDetailInput();
            detail.setOutDetailNo(outDetailNo);
            detail.setTransferAmount(fen);
            detail.setTransferRemark(rmk);
            detail.setOpenid(openid);
            List<TransferDetailInput> details = new ArrayList<>();
            details.add(detail);

            InitiateBatchTransferRequest request = new InitiateBatchTransferRequest();
            request.setAppid(appid);
            request.setOutBatchNo(outBatchNo);
            request.setBatchName("城瘾分润提现");
            request.setBatchRemark(rmk);
            request.setTotalAmount(fen);
            request.setTotalNum(1);
            request.setTransferDetailList(details);

            InitiateBatchTransferResponse resp = transferBatchService.initiateBatchTransfer(request);
            r.put("ok", "true");
            if (resp != null) {
                r.put("batchId", resp.getBatchId());
                r.put("batchStatus", resp.getBatchStatus());
            }
            return r;
        } catch (com.wechat.pay.java.core.exception.ServiceException e) {
            // 微信已明确响应错误(HTTP 非 2xx):批次未受理,钱未出 → 确定性失败,调用方可安全退款
            logger.error("微信商家转账被拒 outBatchNo={} openid={} code={}: {}", outBatchNo, openid, e.getErrorCode(), e.getErrorMessage());
            r.put("ok", "false");
            r.put("error", "微信拒绝(" + e.getErrorCode() + ")");
            return r;
        } catch (Exception e) {
            // ★结果不确定:网络超时/连接中断/响应验签或解析失败——微信可能已受理并已打款。
            // 打上 ambiguous 标记,调用方禁止退款+置终态,须保持处理中交查询任务用 out_batch_no 核实,避免双花丢钱。
            logger.error("微信商家转账结果不确定(疑似超时/网络异常) outBatchNo={} openid={}: {}", outBatchNo, openid, e.getMessage());
            r.put("ok", "false");
            r.put("ambiguous", "true");
            r.put("error", e.getMessage() == null ? "转账结果不确定" : e.getMessage());
            return r;
        }
    }

    /**
     * 查询商家转账最终结果:先查批次(CLOSED=整批关闭失败),再查明细最终态。
     * result: SUCCESS=到账 / FAIL=失败(调用方应退余额) / PROCESSING=处理中(保持) / UNKNOWN=查询异常(勿判失败,重试)。
     */
    @Override
    public Map<String, String> queryTransferResult(String outBatchNo, String outDetailNo) {
        Map<String, String> r = new HashMap<>();
        if (transferBatchService == null) {
            r.put("result", "UNKNOWN");
            r.put("error", "转账服务未就绪");
            return r;
        }
        try {
            GetTransferBatchByOutNoRequest bq = new GetTransferBatchByOutNoRequest();
            bq.setOutBatchNo(outBatchNo);
            bq.setNeedQueryDetail(false);
            TransferBatchEntity be = transferBatchService.getTransferBatchByOutNo(bq);
            String batchStatus = (be != null && be.getTransferBatch() != null) ? be.getTransferBatch().getBatchStatus() : null;
            r.put("batchStatus", batchStatus == null ? "" : batchStatus);
            if ("CLOSED".equals(batchStatus)) {
                r.put("result", "FAIL");
                return r;
            }
            GetTransferDetailByOutNoRequest dq = new GetTransferDetailByOutNoRequest();
            dq.setOutBatchNo(outBatchNo);
            dq.setOutDetailNo(outDetailNo);
            TransferDetailEntity de = transferBatchService.getTransferDetailByOutNo(dq);
            String detailStatus = de == null ? null : de.getDetailStatus();
            r.put("detailStatus", detailStatus == null ? "" : detailStatus);
            String detailId = de == null ? null : de.getDetailId();
            r.put("detailId", detailId == null ? "" : detailId);
            String failReasonCode = (de != null && de.getFailReason() != null) ? de.getFailReason().name() : "";
            r.put("failReasonCode", failReasonCode);
            if ("SUCCESS".equals(detailStatus)) {
                r.put("result", "SUCCESS");
            } else if ("FAIL".equals(detailStatus)) {
                r.put("result", "FAIL");
            } else {
                r.put("result", "PROCESSING");
            }
            return r;
        } catch (Exception e) {
            logger.warn("查询转账结果异常 outBatchNo={} outDetailNo={}: {}", outBatchNo, outDetailNo, e.getMessage());
            r.put("result", "UNKNOWN");
            r.put("error", e.getMessage() == null ? "查询异常" : e.getMessage());
            return r;
        }
    }

    /**
     * 创建微信支付订单 - 主方法
     */
    @Override
    public Map<String, String> createOrder(CmsRegistration registration) {
        if (!isReady()) {
            throw PaymentPreorderException.definiteFailure("支付服务暂不可用，请稍后再试", null);
        }
        requireNotifyUrl();
        PrepayRequest request;
        try {
            request = buildPrepayRequest(registration);
        } catch (Exception e) {
            throw PaymentPreorderException.definiteFailure("创建微信支付订单参数异常：" + e.getMessage(), e);
        }
        try {
            // 一旦进入 SDK 网络调用，异常无法证明微信未受理；保持 PAYING 等回调自动恢复。
            return createOrderWithExtension(request);
        } catch (Exception e) {
            throw PaymentPreorderException.resultUncertain("创建微信支付订单结果未知：" + e.getMessage(), e);
        }
    }

    /**
     * 创建 App 支付订单(trade_type=APP),给原生 App 端 SDK 调起支付用。
     *
     * 与 JSAPI 通道的关系:**只有下单这一步不同**。回调、退款、查单、关单、
     * 幂等与订单状态机全部复用 JSAPI 那套一行不改 —— 微信侧 outTradeNo 在
     * 同一商户号下全局唯一,两个渠道不会重复受理同一笔。
     *
     * ★ App 下单的 PrepayRequest **没有 payer 字段**,不需要 openid ——
     *   这正是它能绕开「小程序 openid 与 App unionid 不同源」那个坑的原因。
     *
     * ⚠️ appid 必须用开放平台移动应用的(wx.pay.app-appid),不能回落小程序 appid。
     */
    @Override
    public Map<String, String> createAppOrder(CmsRegistration registration) {
        requireAppChannelAppid();
        requireNotifyUrl();
        com.wechat.pay.java.service.payments.app.model.PrepayRequest request;
        try {
            request = buildAppPrepayRequest(registration);
        } catch (Exception e) {
            throw PaymentPreorderException.definiteFailure("创建 App 支付订单参数异常：" + e.getMessage(), e);
        }
        return prepayApp(request);
    }

    @Override
    public Map<String, String> createGenericAppOrder(String outTradeNo, java.math.BigDecimal amountYuan,
                                                      String description) {
        String appAppid = requireAppChannelAppid();
        requireNotifyUrl();
        com.wechat.pay.java.service.payments.app.model.PrepayRequest request;
        try {
            request = buildGenericAppPrepayRequest(outTradeNo, amountYuan, description, appAppid);
        } catch (Exception e) {
            throw PaymentPreorderException.definiteFailure("创建 App 支付订单参数异常：" + e.getMessage(), e);
        }
        return prepayApp(request);
    }

    static final String NOTIFY_URL_MISSING = "支付回调地址未配置";

    /**
     * 微信支付下单必须携带支付结果通知地址(wx.pay.notify-url,生产由 CHENGYIN_WXPAY_NOTIFY_URL 注入)。
     * 默认留空:未配置时在调用微信之前明确失败,既不带空值下单,也不会回落到生产回调地址。
     */
    private String requireNotifyUrl() {
        String notifyUrl = wxPayConfig.getNotifyUrl();
        if (notifyUrl == null || notifyUrl.trim().isEmpty()) {
            throw PaymentPreorderException.definiteFailure(NOTIFY_URL_MISSING, null);
        }
        return notifyUrl.trim();
    }

    private String requireAppChannelAppid() {
        if (!isReady() || appServiceExtension == null) {
            throw PaymentPreorderException.definiteFailure("支付服务暂不可用，请稍后再试", null);
        }
        String appAppid = wxPayConfig.getAppAppid();
        if (appAppid == null || appAppid.trim().isEmpty()) {
            // 明确报错而不是回落小程序 appid:回落会让微信侧签名校验失败且极难定位。
            throw PaymentPreorderException.definiteFailure(
                    "App 支付未配置移动应用 appid（wx.pay.app-appid）", null);
        }
        return appAppid;
    }

    private com.wechat.pay.java.service.payments.app.model.PrepayRequest buildGenericAppPrepayRequest(
            String outTradeNo, java.math.BigDecimal amountYuan, String description, String appAppid) {
        if (outTradeNo == null || outTradeNo.trim().isEmpty()) {
            throw new IllegalArgumentException("商户订单号不能为空");
        }
        if (description == null || description.trim().isEmpty()) {
            throw new IllegalArgumentException("商品描述不能为空");
        }
        com.wechat.pay.java.service.payments.app.model.Amount amount =
                new com.wechat.pay.java.service.payments.app.model.Amount();
        amount.setTotal(toWechatPaymentCents(amountYuan));
        amount.setCurrency("CNY");

        String body = description;
        if (body.length() > 128) body = body.substring(0, 125) + "...";
        com.wechat.pay.java.service.payments.app.model.PrepayRequest request =
                new com.wechat.pay.java.service.payments.app.model.PrepayRequest();
        request.setAmount(amount);
        request.setOutTradeNo(outTradeNo);
        request.setDescription(body);
        request.setAppid(appAppid);
        request.setMchid(wxPayConfig.getMerchantId());
        request.setNotifyUrl(requireNotifyUrl());
        return request;
    }

    private Map<String, String> prepayApp(
            com.wechat.pay.java.service.payments.app.model.PrepayRequest request) {
        try {
            // 进入 SDK 网络调用后异常无法证明微信未受理，按结果未知等待回调恢复。
            com.wechat.pay.java.service.payments.app.model.PrepayWithRequestPaymentResponse response =
                    appServiceExtension.prepayWithRequestPayment(request);
            Map<String, String> payParams = new HashMap<>();
            // 键名对齐 App 端微信 SDK(fluwx Payment)的六元组 + appId。
            payParams.put("appId", response.getAppid());
            payParams.put("partnerId", response.getPartnerId());
            payParams.put("prepayId", response.getPrepayId());
            payParams.put("packageValue", response.getPackageVal());
            payParams.put("nonceStr", response.getNonceStr());
            payParams.put("timeStamp", response.getTimestamp());
            payParams.put("sign", response.getSign());
            for (Map.Entry<String, String> field : payParams.entrySet()) {
                if (field.getValue() == null || field.getValue().trim().isEmpty()) {
                    throw new IllegalStateException("微信 App 支付返回缺少 " + field.getKey());
                }
            }
            return payParams;
        } catch (Exception e) {
            throw PaymentPreorderException.resultUncertain("创建 App 支付订单结果未知：" + e.getMessage(), e);
        }
    }

    /** 构建 App 支付请求。注意用的是 payments.app 包下的模型,与 JSAPI 的同名类不是一个。 */
    private com.wechat.pay.java.service.payments.app.model.PrepayRequest buildAppPrepayRequest(CmsRegistration registration) {
        com.wechat.pay.java.service.payments.app.model.PrepayRequest request =
                new com.wechat.pay.java.service.payments.app.model.PrepayRequest();

        com.wechat.pay.java.service.payments.app.model.Amount amount =
                new com.wechat.pay.java.service.payments.app.model.Amount();
        amount.setTotal(toWechatPaymentCents(registration.getWechatPaymentAmount()));
        amount.setCurrency("CNY");

        String body = registration.getActivityTitle() + "-" + registration.getTicketName();
        if (body.length() > 128) {
            body = body.substring(0, 125) + "...";
        }

        request.setAmount(amount);
        // 无 payer:App 支付不需要 openid。
        request.setOutTradeNo(registration.getRegistrationNo());
        request.setDescription(body);
        request.setAppid(wxPayConfig.getAppAppid());
        request.setMchid(wxPayConfig.getMerchantId());
        request.setNotifyUrl(requireNotifyUrl());
        return request;
    }

    @Override
    public Map<String, String> createGenericOrder(String outTradeNo, java.math.BigDecimal amountYuan, String description, String openid) {
        if (!isReady()) {
            throw new IllegalStateException("支付服务暂不可用，请稍后再试");
        }
        String notifyUrl = wxPayConfig.getNotifyUrl();
        if (notifyUrl == null || notifyUrl.trim().isEmpty()) {
            // 与「未就绪」同口径:调用方按 IllegalStateException 展示原因,此时尚未调用微信。
            throw new IllegalStateException(NOTIFY_URL_MISSING);
        }
        try {
            PrepayRequest request = new PrepayRequest();
            Amount amount = new Amount();
            amount.setTotal(toWechatPaymentCents(amountYuan));
            amount.setCurrency("CNY");
            Payer payer = new Payer();
            payer.setOpenid(openid);
            String body = description == null ? "城瘾" : description;
            if (body.length() > 128) body = body.substring(0, 125) + "...";
            request.setAmount(amount);
            request.setPayer(payer);
            request.setOutTradeNo(outTradeNo);
            request.setDescription(body);
            request.setAppid(wxPayConfig.getAppid());
            request.setMchid(wxPayConfig.getMerchantId());
            request.setNotifyUrl(notifyUrl.trim());
            return createOrderWithExtension(request);
        } catch (Exception e) {
            throw new RuntimeException("创建微信支付订单异常：" + e.getMessage(), e);
        }
    }

    /**
     * 构建支付请求
     */
    private PrepayRequest buildPrepayRequest(CmsRegistration registration) {
        PrepayRequest request = new PrepayRequest();

        // 设置金额（单位：分）
        Amount amount = new Amount();
        amount.setTotal(toWechatPaymentCents(registration.getWechatPaymentAmount()));
        amount.setCurrency("CNY");

        // 设置支付者
        Payer payer = new Payer();
        payer.setOpenid(getMemberOpenid(registration.getMemberId()));

        // 商品描述（注意长度限制）
        String body = registration.getActivityTitle() + "-" + registration.getTicketName();
        if (body.length() > 128) {
            body = body.substring(0, 125) + "...";
        }

        request.setAmount(amount);
        request.setPayer(payer);
        request.setOutTradeNo(registration.getRegistrationNo());
        request.setDescription(body);
        request.setAppid(wxPayConfig.getAppid());
        request.setMchid(wxPayConfig.getMerchantId());
        request.setNotifyUrl(requireNotifyUrl());

        return request;
    }

    private int toWechatPaymentCents(java.math.BigDecimal amount) {
        if (amount == null || amount.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("支付金额必须为正数");
        }
        return amount.setScale(2, java.math.RoundingMode.UNNECESSARY)
                .movePointRight(2).intValueExact();
    }

    private long toWechatRefundCents(java.math.BigDecimal amount) {
        if (amount == null || amount.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("退款金额必须为正数");
        }
        long cents = amount.setScale(2, java.math.RoundingMode.UNNECESSARY)
                .movePointRight(2).longValueExact();
        if (cents > Integer.MAX_VALUE) {
            throw new ArithmeticException("金额超过微信渠道上限");
        }
        return cents;
    }

    /**
     * 方法一：使用JsapiServiceExtension创建订单（推荐）- 修正版
     */
    private Map<String, String> createOrderWithExtension(PrepayRequest request) {
        try {
            // 正确调用方法：只需要传递PrepayRequest参数
            PrepayWithRequestPaymentResponse response =
                    jsapiServiceExtension.prepayWithRequestPayment(request);

            // 转换为前端需要的格式
            Map<String, String> payParams = new HashMap<>();
            payParams.put("appId", response.getAppId());
            payParams.put("timeStamp", response.getTimeStamp());
            payParams.put("nonceStr", response.getNonceStr());
            payParams.put("package", response.getPackageVal()); // 直接使用返回的packageVal
            payParams.put("signType", response.getSignType());
            payParams.put("paySign", response.getPaySign());

            return payParams;
        } catch (Exception e) {
            throw new RuntimeException("使用扩展服务创建订单异常：" + e.getMessage(), e);
        }
    }

    /**
     * 方法二：备用方案 - 使用基础JsapiService并手动生成支付参数
     */
    private Map<String, String> createOrderWithManualSign(PrepayRequest request) {
        try {
            // 调用统一下单接口获取prepay_id:cite[9]
            PrepayResponse response = jsapiService.prepay(request);
            String prepayId = response.getPrepayId();

            // 手动生成支付参数
            return generateMiniProgramPayParams(prepayId);
        } catch (Exception e) {
            throw new RuntimeException("手动签名创建订单异常：" + e.getMessage(), e);
        }
    }

    /**
     * 生成小程序支付参数 - 手动签名方式
     */
    private Map<String, String> generateMiniProgramPayParams(String prepayId) {
        try {
            Map<String, String> params = new HashMap<>();
            String timeStamp = String.valueOf(System.currentTimeMillis() / 1000);
            String nonceStr = generateNonceStr();
            String packageValue = "prepay_id=" + prepayId;

            params.put("appId", wxPayConfig.getAppid());
            params.put("timeStamp", timeStamp);
            params.put("nonceStr", nonceStr);
            params.put("package", packageValue);
            params.put("signType", "RSA");

            // 构建签名字符串:cite[3]
            String message = wxPayConfig.getAppid() + "\n" +
                    timeStamp + "\n" +
                    nonceStr + "\n" +
                    packageValue + "\n";

            // 生成签名
            String sign = config.createSigner().sign(message).getSign();
            params.put("paySign", sign);

            return params;
        } catch (Exception e) {
            throw new RuntimeException("生成支付参数异常：" + e.getMessage(), e);
        }
    }

    /**
     * 生成随机字符串
     */
    private String generateNonceStr() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 32);
    }


    /**
     * 处理微信支付回调
     */
    @Override
    public String handlePayCallback(String requestBody, Map<String, String> headers) {
        boolean verifiedNotification = false;
        String safeReference = "UNKNOWN";
        try {
            // 构造请求参数
            RequestParam requestParam = new RequestParam.Builder()
                    .serialNumber(headers.get("wechatpay-serial"))
                    .nonce(headers.get("wechatpay-nonce"))
                    .signature(headers.get("wechatpay-signature"))
                    .timestamp(headers.get("wechatpay-timestamp"))
                    .body(requestBody)
                    .build();

            // 解析支付通知（含官方验签）
            Transaction transaction = notificationParser.parse(requestParam, Transaction.class);
            verifiedNotification = true;
            safeReference = transaction.getOutTradeNo() != null
                    && transaction.getOutTradeNo().matches("[A-Za-z0-9_-]{1,64}")
                    ? transaction.getOutTradeNo() : "UNKNOWN";

            // 构造结果映射
            Map<String, String> resultMap = new HashMap<>();
            resultMap.put("out_trade_no", transaction.getOutTradeNo());
            resultMap.put("transaction_id", transaction.getTransactionId());
            resultMap.put("trade_state", transaction.getTradeState().toString());

            if (transaction.getAmount() != null) {
                resultMap.put("total", String.valueOf(transaction.getAmount().getTotal()));
            }

            // 专属业务订单必须先路由；未知 CLM 一律 FAIL，绝不能掉进活动报名回调。
            String result;
            String otn = transaction.getOutTradeNo();
            String route = "registration";
            IClubMembershipService clubMembershipService = clubMembershipServiceProvider == null
                    ? null : clubMembershipServiceProvider.getIfAvailable();
<<<<<<< public
            if (otn != null && otn.startsWith("CLM")) {
                route = "club-membership";
=======
            if (otn != null && otn.startsWith("W18_")) {
                // This branch is reached ONLY after notificationParser.parse verifies the signature.
                // Missing capability rejects the dedicated prefix rather than falling into subscriptions/tickets.
                com.chengyinhub.business.marketplace.paid.WorkshopPaidPaymentHandler handler = workshopPaidPaymentHandler == null
                        ? null : workshopPaidPaymentHandler.getIfAvailable();
                result = WorkshopPaidNotificationRoute.dispatch(transaction, handler) ? "SUCCESS" : "FAIL";
            } else if (otn != null && otn.startsWith("CLM")) {
>>>>>>> private-github/featu
                boolean paid = "SUCCESS".equals(resultMap.get("trade_state"));
                Integer paidAmountFen = transaction.getAmount() == null ? null : transaction.getAmount().getTotal();
                result = clubMembershipService != null && clubMembershipService.supportsOutTradeNo(otn)
                        && clubMembershipService.handlePaidInvoice(otn, paid, paidAmountFen,
                        transaction.getTransactionId()) ? "SUCCESS" : "FAIL";
            } else if (merchantCommerceService.supportsOutTradeNo(otn)) {
                route = "merchant-commerce";
                boolean paid = "SUCCESS".equalsIgnoreCase(resultMap.get("trade_state"));
                Integer paidAmountFen = transaction.getAmount() == null ? null : transaction.getAmount().getTotal();
                result = merchantCommerceService.handlePaidOrder(otn, paid, paidAmountFen,
                        transaction.getTransactionId()) ? "SUCCESS" : "FAIL";
            } else if (otn != null && otn.startsWith("DEP")) {
                route = "coop-deposit";
                boolean paid = "SUCCESS".equalsIgnoreCase(resultMap.get("trade_state"));
                Long depositCents = transaction.getAmount() == null || transaction.getAmount().getTotal() == null
                        ? null : transaction.getAmount().getTotal().longValue();
                result = (paid && transaction.getAmount() != null
                        && "CNY".equals(transaction.getAmount().getCurrency())
                        && wxPayConfig.getMerchantId() != null
                        && wxPayConfig.getMerchantId().equals(transaction.getMchid())
                        && coopInviteService.markDepositPaid(otn, depositCents,
                        transaction.getTransactionId())) ? "SUCCESS" : "FAIL";
            } else {
                result = registrationService.handleWechatPayCallback(resultMap);
            }

            // [P0-资损] 按业务处理结果回 ACK:处理失败必须回 FAIL 触发微信重试,否则掉单
            // (原实现无论 result 如何都回 SUCCESS,业务失败后微信不再通知 → 用户付了钱但订单未处理)
            if (!"SUCCESS".equalsIgnoreCase(result)) {
                String reference = otn != null && otn.matches("[A-Za-z0-9_-]{1,64}") ? otn : "UNKNOWN";
                logger.error("Verified payment callback failed route={} order={}; returning FAIL", route, reference);
                // Registration lookup reports its own missing-order event; do not emit the same failure twice.
                if (!"registration".equals(route)) {
                    alarmService.alarmThrottled("payment.callback." + route, "支付回调业务订单处理失败",
                            "route=" + route + " order=" + reference + " result=FAIL");
                }
                return "{\"code\": \"FAIL\",\"message\": \"业务处理失败,请重试\"}";
            }

            // 返回成功响应
            return "{\"code\": \"SUCCESS\",\"message\": \"成功\"}";
        } catch (ValidationException e) {
            logger.error("微信支付回调签名验证失败", e);
            // 未验签的请求体不可信：只发固定事件，不带任何请求内容；按事件限频防止伪造请求刷屏。
            alarmService.alarmThrottled("payment.callback.signature", "支付回调验签失败",
                    "type=" + e.getClass().getSimpleName() + " result=FAIL");
            return "{\"code\": \"FAIL\",\"message\": \"签名验证失败: " + e.getMessage() + "\"}";
        } catch (Exception e) {
            logger.error("微信支付回调处理异常", e);
            if (verifiedNotification) {
                alarmService.alarmThrottled("payment.callback.business_exception", "支付回调业务处理异常",
                        "order=" + safeReference + " type=" + e.getClass().getSimpleName() + " result=FAIL");
            }
            return "{\"code\": \"FAIL\",\"message\": \"处理异常: " + e.getMessage() + "\"}";
        }
    }

    @Override
    public String queryRefundStatus(String outRefundNo) {
        if (refundService == null || outRefundNo == null || outRefundNo.trim().isEmpty()) {
            return null;
        }
        try {
            QueryByOutRefundNoRequest req = new QueryByOutRefundNoRequest();
            req.setOutRefundNo(outRefundNo);
            Refund refund = refundService.queryByOutRefundNo(req);
            Status st = (refund == null) ? null : refund.getStatus();
            return st == null ? null : st.name();
        } catch (Exception e) {
            logger.error("查询微信退款状态失败 outRefundNo={}: {}", outRefundNo, e.getMessage());
            return null;
        }
    }

    @Override
    public Map<String,String> queryRefundFacts(String outRefundNo) {
        if (refundService == null || outRefundNo == null || outRefundNo.trim().isEmpty()) return null;
        try {
            QueryByOutRefundNoRequest request = new QueryByOutRefundNoRequest(); request.setOutRefundNo(outRefundNo);
            Refund refund = refundService.queryByOutRefundNo(request);
            if (refund == null) return null;
            return refundFacts(refund.getOutRefundNo(), refund.getOutTradeNo(),
                    refund.getStatus() == null ? null : refund.getStatus().name(), refund.getAmount());
        } catch (com.wechat.pay.java.core.exception.ServiceException e) {
            if (e.getHttpStatusCode() == 404 && "RESOURCE_NOT_EXISTS".equals(e.getErrorCode())) {
                Map<String,String> missing = new HashMap<>(); missing.put("status", "NOT_FOUND"); return missing;
            }
            logger.warn("保证金退款查单失败 outRefundNo={}", outRefundNo, e); return null;
        } catch (Exception e) { logger.warn("保证金退款查单未知 outRefundNo={}", outRefundNo, e); return null; }
    }

    private Map<String,String> refundFacts(String refundNo, String tradeNo, String state,
            com.wechat.pay.java.service.refund.model.Amount amount) {
        Map<String,String> facts = new HashMap<>(); facts.put("outRefundNo", refundNo);
        facts.put("outTradeNo", tradeNo); facts.put("status", state);
        if (amount != null) {
            facts.put("refund", String.valueOf(amount.getRefund())); facts.put("total", String.valueOf(amount.getTotal()));
            facts.put("currency", amount.getCurrency());
        }
        return facts;
    }

    @Override
    public String refreshPayoutFromWx(String outRefundNo) {
        if (outRefundNo != null && outRefundNo.startsWith("RDEP")) {
            Map<String,String> facts = queryRefundFacts(outRefundNo);
            return facts != null && outRefundNo.equals(facts.get("outRefundNo"))
                    && outRefundNo.substring(1).equals(facts.get("outTradeNo"))
                    && coopInviteService.handleDepositRefundResult(facts) ? facts.get("status") : null;
        }
        // 主动查微信真实退款态并回写(P3 回调的拉取兜底;admin"刷新微信状态"也走它)
        String status = queryRefundStatus(outRefundNo);
        if (status != null) {
            refundBizService.handleRefundNotify(outRefundNo, status);
        }
        return status;
    }

    @Override
    public String handleRefundCallback(String requestBody, Map<String, String> headers) {
        boolean verifiedRefund = false;
        String safeRefundReference = "UNKNOWN";
        try {
            RequestParam requestParam = new RequestParam.Builder()
                    .serialNumber(headers.get("wechatpay-serial"))
                    .nonce(headers.get("wechatpay-nonce"))
                    .signature(headers.get("wechatpay-signature"))
                    .timestamp(headers.get("wechatpay-timestamp"))
                    .body(requestBody)
                    .build();
            // 解析退款结果通知(含官方验签),资源解密为 RefundNotification
            RefundNotification notif = notificationParser.parse(requestParam, RefundNotification.class);
            verifiedRefund = true;
            safeRefundReference = notif.getOutRefundNo() != null
                    && notif.getOutRefundNo().matches("[A-Za-z0-9_-]{1,64}") ? notif.getOutRefundNo() : "UNKNOWN";
            Status st = notif.getRefundStatus();
            boolean ok = notif.getOutRefundNo() != null && notif.getOutRefundNo().startsWith("RDEP")
                    ? coopInviteService.handleDepositRefundResult(refundFacts(notif.getOutRefundNo(),
                        notif.getOutTradeNo(), st == null ? null : st.name(), notif.getAmount()))
                    : refundBizService.handleRefundNotify(notif.getOutRefundNo(), st == null ? null : st.name());
            if (!ok) {
                logger.error("微信退款回调业务处理失败,回 FAIL 触发重试: out_refund_no={}", notif.getOutRefundNo());
                alarmService.alarmThrottled("refund.callback.failed", "退款回调业务处理失败",
                        "refund=" + safeRefundReference + " result=FAIL");
                return "{\"code\": \"FAIL\",\"message\": \"业务处理失败,请重试\"}";
            }
            return "{\"code\": \"SUCCESS\",\"message\": \"成功\"}";
        } catch (ValidationException e) {
            logger.error("微信退款回调签名验证失败", e);
            alarmService.alarmThrottled("refund.callback.signature", "退款回调验签失败",
                    "type=" + e.getClass().getSimpleName() + " result=FAIL");
            return "{\"code\": \"FAIL\",\"message\": \"签名验证失败: " + e.getMessage() + "\"}";
        } catch (Exception e) {
            logger.error("微信退款回调处理异常", e);
            if (verifiedRefund) {
                alarmService.alarmThrottled("refund.callback.exception", "退款回调业务处理异常",
                        "refund=" + safeRefundReference + " type=" + e.getClass().getSimpleName() + " result=FAIL");
            }
            return "{\"code\": \"FAIL\",\"message\": \"处理异常: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 查询订单支付状态
     */
    @Override
    public Map<String, String> queryOrderStatus(String outTradeNo) {
        try {
            QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
            request.setMchid(wxPayConfig.getMerchantId());
            request.setOutTradeNo(outTradeNo);

            Transaction transaction = jsapiService.queryOrderByOutTradeNo(request);

            Map<String, String> result = new HashMap<>();
            result.put("trade_state", transaction.getTradeState().toString());
            result.put("transaction_id", transaction.getTransactionId());
            result.put("out_trade_no", transaction.getOutTradeNo());

            if (transaction.getAmount() != null) {
                result.put("total", String.valueOf(transaction.getAmount().getTotal()));
                result.put("currency", transaction.getAmount().getCurrency());
            }

            return result;
        } catch (com.wechat.pay.java.core.exception.ServiceException e) {
            if ("ORDER_NOT_EXIST".equals(e.getErrorCode())) {
                throw new WechatOrderNotExistException("微信侧订单不存在：" + outTradeNo, e);
            }
            throw new RuntimeException("查询订单状态异常：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("查询订单状态异常：" + e.getMessage(), e);
        }
    }

    /**
     * 关闭订单
     */
    @Override
    public boolean closeOrder(String outTradeNo) {
        try {
            CloseOrderRequest request = new CloseOrderRequest();
            request.setMchid(wxPayConfig.getMerchantId());
            request.setOutTradeNo(outTradeNo);

            jsapiService.closeOrder(request);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("关闭订单异常：" + e.getMessage(), e);
        }
    }

    /**
     * 获取用户openid（实际项目中需要根据用户ID查询）
     */
    private String getMemberOpenid(Long memberId) {
        // 根据memberId查询openid
        UmsMember member = memberService.selectUmsMemberById(memberId);
        if (member != null) {
            return member.getWxOpenId();
        }
        else {
            throw new RuntimeException("用户不存在");
        }
    }
}
