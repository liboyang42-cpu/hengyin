package com.chengyinhub.business.service.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
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

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate paymentIntentJdbc;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager paymentIntentTransactions;

    private JsapiService jsapiService;
    private JsapiServiceExtension jsapiServiceExtension;
    /** App 支付(trade_type=APP)。与 JSAPI 共用同一套商户配置,只是下单 appid 不同。 */
    private com.wechat.pay.java.service.payments.app.AppServiceExtension appServiceExtension;
    private RefundService refundService;
    private TransferBatchService transferBatchService;
    /** One immutable publication couples the client with its SDK credential merchant, never a mutable property. */
    private record TransferClientBinding(TransferBatchService client, String mchid) { }
    private volatile TransferClientBinding transferClientBinding;
    private Config config;

    private NotificationParser notificationParser;

    @Autowired
    private IUmsMemberService memberService;

    /**
     * 初始化微信支付服务 - 容错版本
     */
    @PostConstruct
    public void init() {
        transferClientBinding = null;
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
                com.wechat.pay.java.core.auth.Credential credential = config.createCredential();
                if (credential == null || credential.getMerchantId() == null || credential.getMerchantId().isBlank()) {
                    throw new IllegalStateException("微信转账client缺少真实商户绑定");
                }
                // The very same credential is used by this HTTP client and by the immutable merchant binding.
                // Do not ask a mutable Config for another credential after the transfer client is constructed.
                String boundMerchantId = credential.getMerchantId();
                com.wechat.pay.java.core.http.HttpClient transferHttpClient =
                        new com.wechat.pay.java.core.http.DefaultHttpClientBuilder()
                                .credential(credential).validator(config.createValidator()).build();
                TransferBatchService builtTransferClient = new TransferBatchService.Builder()
                        .httpClient(transferHttpClient).encryptor(config.createEncryptor())
                        .decryptor(config.createDecryptor()).build();

                // 初始化NotificationParser
                notificationParser = new NotificationParser(notificationConfig);
                transferBatchService = builtTransferClient;
                transferClientBinding = new TransferClientBinding(builtTransferClient, boundMerchantId);

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
            transferClientBinding = null;
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
            request.setOutTradeNo(currentWechatPaymentNo(outTradeNo));
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
     * ok=true 仅表示受理。进入 SDK 后的异常一律保留原号查证，不能据此回补余额。
     * 仅本地调用前拒绝返回 ok=false, ambiguous=false；调用方仍须校验原回执与扣款状态。
     */
    @Override
    public Map<String, String> transferToBalance(String outBatchNo, String outDetailNo,
                                                  java.math.BigDecimal amountYuan, String openid, String remark,
                                                  String appid) {
        return submitTransfer(transferBatchService, outBatchNo, outDetailNo, amountYuan, openid, remark, appid);
    }

    private Map<String, String> submitTransfer(TransferBatchService client, String outBatchNo, String outDetailNo,
            java.math.BigDecimal amountYuan, String openid, String remark, String appid) {
        Map<String, String> r = new HashMap<>();
        r.put("ambiguous", "false");
        if (client == null) {
            r.put("ok", "false");
            r.put("error", "转账服务未就绪(商户号需开通「商家转账到零钱」并配置证书)");
            return r;
        }
        if (amountYuan == null || amountYuan.compareTo(java.math.BigDecimal.ZERO) <= 0
                || openid == null || openid.trim().isEmpty()
                || outBatchNo == null || !outBatchNo.matches("[A-Za-z0-9]{1,32}")
                || outDetailNo == null || !outDetailNo.matches("[A-Za-z0-9]{1,32}")) {
            r.put("ok", "false");
            r.put("error", "转账参数不合法(金额、openid或原批次/明细号)");
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

            InitiateBatchTransferResponse resp = client.initiateBatchTransfer(request);
            if (resp == null || !outBatchNo.equals(resp.getOutBatchNo())
                    || resp.getBatchId() == null || resp.getBatchId().trim().isEmpty()) {
                r.put("ok", "false");
                r.put("ambiguous", "true");
                r.put("error", "转账受理响应缺失或身份不符，须按原号查证");
                return r;
            }
            r.put("ok", "true");
            r.remove("ambiguous");
            r.put("batchId", resp.getBatchId());
            r.put("batchStatus", resp.getBatchStatus());
            return r;
        } catch (com.wechat.pay.java.core.exception.ServiceException e) {
            // 官方批量转账协议：SYSTEM_ERROR/429 必须原号原参数重试；INVALID_REQUEST
            // 还包含创建冲突/超出重试期。即使本次请求被拒，也不能证明同号原意图未被受理。
            // 不从 HTTP 非 2xx、异常类型或易变的错误文案推导可退款，统一交原号查询/人工核实。
            logger.warn("微信商家转账结果待查证 outBatchNo={} status={} code={}",
                    outBatchNo, e.getHttpStatusCode(), e.getErrorCode());
            r.put("ok", "false");
            r.put("ambiguous", "true");
            r.put("error", "微信转账结果不确定，须按原批次号及原参数查证");
            return r;
        } catch (Exception e) {
            // ★结果不确定:网络超时/连接中断/响应验签或解析失败——微信可能已受理并已打款。
            // 打上 ambiguous 标记,调用方禁止退款+置终态,须保持处理中交查询任务用 out_batch_no 核实,避免双花丢钱。
            logger.warn("微信商家转账结果不确定 outBatchNo={} type={}", outBatchNo, e.getClass().getSimpleName());
            r.put("ok", "false");
            r.put("ambiguous", "true");
            r.put("error", "转账结果不确定，须按原号查证");
            return r;
        }
    }

    /**
     * 查询商家转账最终结果:FINISHED 后查明细；单纯 CLOSED 不推断资金未达。
     * result: SUCCESS=到账 / FAIL=明细失败证据 / PROCESSING=处理中(保持) / UNKNOWN=查询异常(勿判失败,重试)。
     * 业务回补还须核验原DEBIT/SUBMIT_INTENT，不能仅凭本次返回值释放原业务身份。
     */
    @Override
    public Map<String, String> queryTransferResult(String outBatchNo, String outDetailNo) {
        return queryTransfer(transferBatchService, wxPayConfig == null ? null : wxPayConfig.getMerchantId(),
                outBatchNo, outDetailNo);
    }

    private Map<String, String> queryTransfer(TransferBatchService client, String merchantId,
            String outBatchNo, String outDetailNo) {
        Map<String, String> r = new HashMap<>();
        if (client == null) {
            r.put("result", "UNKNOWN");
            r.put("error", "转账服务未就绪");
            return r;
        }
        try {
            GetTransferBatchByOutNoRequest bq = new GetTransferBatchByOutNoRequest();
            bq.setOutBatchNo(outBatchNo);
            bq.setNeedQueryDetail(false);
            TransferBatchEntity be = client.getTransferBatchByOutNo(bq);
            var batch = be == null ? null : be.getTransferBatch();
            if (batch == null || outBatchNo == null || !outBatchNo.equals(batch.getOutBatchNo())
                    || batch.getMchid() == null || !batch.getMchid().equals(merchantId)
                    || batch.getBatchId() == null || batch.getBatchId().trim().isEmpty()
                    || batch.getAppid() == null || batch.getAppid().trim().isEmpty()
                    || !Integer.valueOf(1).equals(batch.getTotalNum())
                    || batch.getTotalAmount() == null || batch.getTotalAmount() <= 0) {
                r.put("result", "UNKNOWN");
                r.put("error", "批次身份或单笔事实缺失/不符");
                return r;
            }
            String batchStatus = batch.getBatchStatus();
            r.put("batchStatus", batchStatus == null ? "" : batchStatus);
            r.put("batchId", batch.getBatchId());
            r.put("mchid", batch.getMchid());
            r.put("appid", batch.getAppid());
            r.put("outBatchNo", batch.getOutBatchNo());
            if (!"FINISHED".equals(batchStatus)) {
                // 官方仅将 CLOSED 描述为“已关闭，查关闭原因”；不足以推导本明细资金未达。
                // WAIT_PAY/ACCEPTED/PROCESSING 也不能查明细终态；保留原意图等待确认。
                boolean pending = "WAIT_PAY".equals(batchStatus) || "ACCEPTED".equals(batchStatus)
                        || "PROCESSING".equals(batchStatus);
                r.put("result", pending ? "PROCESSING" : "UNKNOWN");
                if (!pending) r.put("error", "批次无明确明细终态，须按原号核实");
                return r;
            }
            GetTransferDetailByOutNoRequest dq = new GetTransferDetailByOutNoRequest();
            dq.setOutBatchNo(outBatchNo);
            dq.setOutDetailNo(outDetailNo);
            TransferDetailEntity de = client.getTransferDetailByOutNo(dq);
            if (de == null || !outBatchNo.equals(de.getOutBatchNo()) || outDetailNo == null
                    || !outDetailNo.equals(de.getOutDetailNo()) || !batch.getBatchId().equals(de.getBatchId())
                    || !batch.getMchid().equals(de.getMchid()) || !batch.getAppid().equals(de.getAppid())
                    || de.getDetailId() == null || de.getDetailId().trim().isEmpty()
                    || !batch.getTotalAmount().equals(de.getTransferAmount())) {
                r.put("result", "UNKNOWN");
                r.put("error", "明细身份或金额事实缺失/不符");
                return r;
            }
            String detailStatus = de == null ? null : de.getDetailStatus();
            r.put("detailStatus", detailStatus == null ? "" : detailStatus);
            String detailId = de == null ? null : de.getDetailId();
            r.put("detailId", detailId == null ? "" : detailId);
            r.put("outDetailNo", de.getOutDetailNo());
            r.put("transferAmount", String.valueOf(de.getTransferAmount()));
            // Provider evidence only. Never fill an absent receiver from the caller's expected intent.
            if (de.getOpenid() != null) r.put("openid", de.getOpenid());
            String failReasonCode = (de != null && de.getFailReason() != null) ? de.getFailReason().name() : "";
            r.put("failReasonCode", failReasonCode);
            if ("SUCCESS".equals(detailStatus)) {
                r.put("result", "SUCCESS");
            } else if ("FAIL".equals(detailStatus)) {
                r.put("result", "FAIL");
            } else {
                boolean pending = "INIT".equals(detailStatus) || "WAIT_PAY".equals(detailStatus)
                        || "PROCESSING".equals(detailStatus);
                r.put("result", pending ? "PROCESSING" : "UNKNOWN");
            }
            return r;
        } catch (Exception e) {
            logger.warn("查询转账结果异常 outBatchNo={} outDetailNo={} type={}",
                    outBatchNo, outDetailNo, e.getClass().getSimpleName());
            r.put("result", "UNKNOWN");
            r.put("error", "查询结果暂无法核实，须保留原号查证");
            return r;
        }
    }

    @Override
    public Map<String, String> transferToBalance(CashWithdrawTransferIntent intent) {
        try {
            TransferClientBinding bound = originalTransferClient(intent);
            // No normalization here: these exact parameters were sealed by the DTO owner before submission.
            return submitTransfer(bound.client(), intent.outBatchNo(), intent.outDetailNo(),
                    java.math.BigDecimal.valueOf(intent.amountFen(), 2), intent.openid(),
                    intent.effectiveRemark(), intent.appid());
        } catch (RuntimeException invalidOriginal) {
            Map<String, String> result = new HashMap<>();
            result.put("ok", "false"); result.put("ambiguous", "true");
            result.put("error", "原提现意图或client商户绑定无法核验，保留原号查证");
            return result;
        }
    }

    @Override
    public Map<String, String> queryTransferResult(CashWithdrawTransferIntent intent) {
        try {
            TransferClientBinding bound = originalTransferClient(intent);
            Map<String, String> result = queryTransfer(bound.client(), bound.mchid(),
                    intent.outBatchNo(), intent.outDetailNo());
            String state = result.get("result");
            // Pending/CLOSED/missing detail cannot prove the receiver or authorize any economic transition.
            if (!("SUCCESS".equals(state) || "FAIL".equals(state))) {
                result.put("result", "UNKNOWN");
                return result;
            }
            if (!"FINISHED".equals(result.get("batchStatus")) || !state.equals(result.get("detailStatus"))
                    || !intent.mchid().equals(result.get("mchid")) || !intent.appid().equals(result.get("appid"))
                    || !intent.outBatchNo().equals(result.get("outBatchNo"))
                    || !intent.outDetailNo().equals(result.get("outDetailNo"))
                    || !intent.openid().equals(result.get("openid"))
                    || intent.amountFen() != Long.parseLong(result.get("transferAmount"))) {
                result.put("result", "UNKNOWN");
                result.put("error", "微信转账查单与原提现提交事实不一致");
            }
            return result;
        } catch (RuntimeException unverifiable) {
            Map<String, String> result = new HashMap<>(); result.put("result", "UNKNOWN");
            result.put("error", "原提现提交或查单事实暂无法核验，保留原号查证");
            return result;
        }
    }

    private TransferClientBinding originalTransferClient(CashWithdrawTransferIntent intent) {
        if (intent == null) throw new IllegalArgumentException("原提现意图缺失");
        // The DTO is the ONLY requestVersion/requestHash canonicalization authority; no second hash here.
        intent.requireValid();
        String canonicalHash = intent.canonicalRequestHash();
        if (canonicalHash == null || !canonicalHash.equals(intent.requestHash())) {
            throw new IllegalArgumentException("原提现请求hash不符");
        }
        long fen = intent.amountFen();
        if (fen <= 0 || fen > Integer.MAX_VALUE || intent.mchid() == null || intent.mchid().isBlank()
                || intent.appid() == null || intent.appid().isBlank() || intent.openid() == null || intent.openid().isBlank()
                || intent.outBatchNo() == null || !intent.outBatchNo().matches("[A-Za-z0-9]{1,32}")
                || intent.outDetailNo() == null || !intent.outDetailNo().matches("[A-Za-z0-9]{1,32}")
                || intent.effectiveRemark() == null || intent.effectiveRemark().isEmpty()
                || intent.effectiveRemark().length() > 32) {
            throw new IllegalArgumentException("原提现参数不能被重写或归一化");
        }
        TransferClientBinding bound = transferClientBinding;
        if (bound == null || bound.client() == null || !intent.mchid().equals(bound.mchid())) {
            throw new IllegalStateException("原商户与SDK client绑定不符，不得改渠道重发");
        }
        return bound;
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
        request.setOutTradeNo(freezePaymentIntent(request.getOutTradeNo(), request.getMchid(), request.getAppid(), "APP",
                request.getAmount().getCurrency(), request.getAmount().getTotal(), "",
                request.getDescription(), request.getNotifyUrl()));
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
        request.setOutTradeNo(freezePaymentIntent(request.getOutTradeNo(), request.getMchid(), request.getAppid(), "JSAPI",
                request.getAmount().getCurrency(), request.getAmount().getTotal(),
                request.getPayer() == null ? "" : request.getPayer().getOpenid(),
                request.getDescription(), request.getNotifyUrl()));
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
        request.setOutTradeNo(freezePaymentIntent(request.getOutTradeNo(), request.getMchid(), request.getAppid(), "JSAPI",
                request.getAmount().getCurrency(), request.getAmount().getTotal(),
                request.getPayer() == null ? "" : request.getPayer().getOpenid(),
                request.getDescription(), request.getNotifyUrl()));
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
            safeReference = transaction != null && transaction.getOutTradeNo() != null
                    && transaction.getOutTradeNo().matches("[A-Za-z0-9_-]{1,64}")
                    ? transaction.getOutTradeNo() : "UNKNOWN";

            // 验签只证明通知来自微信，不能证明这笔钱属于本订单创建时的应用/商户/币种。
            // 四条路线共享同一道事实闸门；按原商户/原单号查询微信创建单据，不猜默认 appid。
            if (!matchesExpectedPayment(transaction)) {
                alarmService.alarmThrottled("payment.callback.facts", "支付回调原单事实缺失或不符",
                        "order=" + safeReference + " result=FAIL");
                return "{\"code\": \"FAIL\",\"message\": \"支付事实核验失败\"}";
            }

            // 构造结果映射
            Map<String, String> resultMap = new HashMap<>();
            resultMap.put("out_trade_no", businessPaymentOrderNo(transaction.getOutTradeNo()));
            resultMap.put("wx_out_trade_no", transaction.getOutTradeNo());
            resultMap.put("transaction_id", transaction.getTransactionId());
            resultMap.put("trade_state", transaction.getTradeState().toString());
            resultMap.put("appid", transaction.getAppid());
            resultMap.put("mchid", transaction.getMchid());
            resultMap.put("trade_type", transaction.getTradeType().toString());

            if (transaction.getAmount() != null) {
                resultMap.put("total", String.valueOf(transaction.getAmount().getTotal()));
                resultMap.put("currency", transaction.getAmount().getCurrency());
            }

            // 专属业务订单必须先路由；未知 CLM 一律 FAIL，绝不能掉进活动报名回调。
            String result;
            String otn = resultMap.get("out_trade_no");
            String route = "registration";
            IClubMembershipService clubMembershipService = clubMembershipServiceProvider == null
                    ? null : clubMembershipServiceProvider.getIfAvailable();
            if (otn != null && otn.startsWith("CLM")) {
                route = "club-membership";
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
            logger.warn("微信支付回调签名验证失败 type={}", e.getClass().getSimpleName());
            // 未验签的请求体不可信：只发固定事件，不带任何请求内容；按事件限频防止伪造请求刷屏。
            alarmService.alarmThrottled("payment.callback.signature", "支付回调验签失败",
                    "type=" + e.getClass().getSimpleName() + " result=FAIL");
            return "{\"code\": \"FAIL\",\"message\": \"签名验证失败\"}";
        } catch (Exception e) {
            logger.warn("微信支付回调处理异常 order={} type={}", safeReference, e.getClass().getSimpleName());
            if (verifiedNotification) {
                alarmService.alarmThrottled("payment.callback.business_exception", "支付回调业务处理异常",
                        "order=" + safeReference + " type=" + e.getClass().getSimpleName() + " result=FAIL");
            }
            return "{\"code\": \"FAIL\",\"message\": \"支付结果暂无法核验，请重试\"}";
        }
    }

    private boolean matchesExpectedPayment(Transaction transaction) {
        if (transaction == null || (transaction.getTradeState() != Transaction.TradeStateEnum.SUCCESS
                && transaction.getTradeState() != Transaction.TradeStateEnum.CLOSED
                && transaction.getTradeState() != Transaction.TradeStateEnum.REFUND)
                || transaction.getOutTradeNo() == null
                || !transaction.getOutTradeNo().matches("[A-Za-z0-9_-]{1,64}")
                || transaction.getTransactionId() == null
                || !transaction.getTransactionId().matches("[A-Za-z0-9_-]{1,64}")
                || transaction.getAppid() == null || transaction.getAppid().trim().isEmpty()
                || transaction.getMchid() == null || transaction.getMchid().trim().isEmpty()
                || wxPayConfig == null || !transaction.getMchid().equals(wxPayConfig.getMerchantId())
                || transaction.getTradeType() == null
                || transaction.getAmount() == null || transaction.getAmount().getTotal() == null
                || transaction.getAmount().getTotal() <= 0
                || !"CNY".equals(transaction.getAmount().getCurrency())) {
            return false;
        }
        // APP/JSAPI 共用 /v3/pay/transactions/out-trade-no/{out_trade_no}。
        // 官方应答中的 appid/mchid 是创建原单时传入的值；SDK 验签后才能作为渠道证据。
        // 不从当前配置的 appid 集合、通知正文或可过期 payParams 反推“预期应用”。
        Transaction original = queryVerifiedOrder(transaction.getOutTradeNo());
        return original != null
                && transaction.getOutTradeNo().equals(original.getOutTradeNo())
                && transaction.getAppid().equals(original.getAppid())
                && transaction.getMchid().equals(original.getMchid())
                && transaction.getTransactionId().equals(original.getTransactionId())
                && transaction.getTradeState() == original.getTradeState()
                && (transaction.getTradeType() == Transaction.TradeTypeEnum.JSAPI
                    || transaction.getTradeType() == Transaction.TradeTypeEnum.APP)
                && transaction.getTradeType() == original.getTradeType()
                && original.getAmount() != null
                && "CNY".equals(original.getAmount().getCurrency())
                && transaction.getAmount().getTotal().equals(original.getAmount().getTotal());
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
        facts.put("outTradeNo", tradeNo == null ? null : businessPaymentOrderNo(tradeNo));
        facts.put("wxOutTradeNo", tradeNo); facts.put("status", state);
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
        Map<String, String> facts = queryRefundFacts(outRefundNo);
        return facts != null && outRefundNo != null && outRefundNo.equals(facts.get("outRefundNo"))
                && refundBizService.handleRefundNotify(facts) ? facts.get("status") : null;
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
                    : refundBizService.handleRefundNotify(refundFacts(notif.getOutRefundNo(),
                        notif.getOutTradeNo(), st == null ? null : st.name(), notif.getAmount()));
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
            String wxOrderNo = currentWechatPaymentNo(outTradeNo);
            Transaction transaction = queryVerifiedOrder(wxOrderNo);
            if (transaction.getTradeState() == Transaction.TradeStateEnum.CLOSED) {
                recordConfirmedClosedAttempt(transaction);
            }

            Map<String, String> result = new HashMap<>();
            result.put("trade_state", transaction.getTradeState().toString());
            result.put("transaction_id", transaction.getTransactionId());
            result.put("out_trade_no", businessPaymentOrderNo(transaction.getOutTradeNo()));
            result.put("wx_out_trade_no", transaction.getOutTradeNo());
            result.put("appid", transaction.getAppid());
            result.put("mchid", transaction.getMchid());
            result.put("trade_type", transaction.getTradeType() == null ? null : transaction.getTradeType().toString());

            if (transaction.getAmount() != null) {
                result.put("total", String.valueOf(transaction.getAmount().getTotal()));
                result.put("currency", transaction.getAmount().getCurrency());
            }

            return result;
        } catch (com.wechat.pay.java.core.exception.ServiceException e) {
            if (e.getHttpStatusCode() == 404 && "ORDER_NOT_EXIST".equals(e.getErrorCode())) {
                throw new WechatOrderNotExistException("微信侧订单不存在：" + outTradeNo, e);
            }
            throw new RuntimeException("查询订单状态异常：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("查询订单状态异常：" + e.getMessage(), e);
        }
    }

    /** 回调与主动确认共用官方查单事实；不能将串单/缺字段应答交领域写入支付终态。 */
    private Transaction queryVerifiedOrder(String outTradeNo) {
        QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
        request.setMchid(wxPayConfig.getMerchantId());
        request.setOutTradeNo(outTradeNo);
        Transaction original = jsapiService.queryOrderByOutTradeNo(request);
        if (original == null || outTradeNo == null || !outTradeNo.equals(original.getOutTradeNo())
                || original.getMchid() == null || original.getMchid().trim().isEmpty()
                || !original.getMchid().equals(wxPayConfig.getMerchantId())
                || original.getAppid() == null || original.getAppid().trim().isEmpty()
                || original.getTradeState() == null || original.getAmount() == null
                || original.getAmount().getTotal() == null || original.getAmount().getTotal() <= 0
                || !"CNY".equals(original.getAmount().getCurrency())) {
            throw new IllegalStateException("微信原单身份或金额事实缺失/不符");
        }
        // 未付款/关闭订单可能尚无交易号和交易类型，不因缺少可选字段改变既有关单策略。
        if ((original.getTradeState() == Transaction.TradeStateEnum.SUCCESS
                || original.getTradeState() == Transaction.TradeStateEnum.REFUND)
                && (original.getTransactionId() == null || original.getTransactionId().trim().isEmpty()
                    || (original.getTradeType() != Transaction.TradeTypeEnum.JSAPI
                        && original.getTradeType() != Transaction.TradeTypeEnum.APP))) {
            throw new IllegalStateException("微信已付原单交易身份缺失");
        }
        java.util.List<java.util.Map<String, Object>> frozen = paymentIntentJdbc.queryForList(
                "SELECT appid,trade_type,currency,total_fen FROM wx_pay_order_intent WHERE mchid=? AND out_trade_no=?",
                original.getMchid(), outTradeNo);
        // 已存在的微信原单是历史不可变证据；新下单均先持久冻结。缺表/读取异常不能降级跳过。
        if (!frozen.isEmpty()) {
            java.util.Map<String, Object> row = frozen.get(0);
            if (!original.getAppid().equals(row.get("appid"))
                    || (original.getTradeType() != null && !original.getTradeType().name().equals(row.get("trade_type")))
                    || !"CNY".equals(row.get("currency"))
                    || !(row.get("total_fen") instanceof Number total)
                    || total.longValue() != original.getAmount().getTotal().longValue()) {
                throw new IllegalStateException("微信原单与永久冻结意图不一致");
            }
        }
        return original;
    }

    /** 四路首次提交共同入口：先独立提交永久渠道和原请求指纹，UNKNOWN 只能原意图恢复。 */
    String freezePaymentIntent(String outTradeNo, String mchid, String appid, String tradeType,
                             String currency, Integer totalFen, String openid, String description, String notifyUrl) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw PaymentPreorderException.resultUncertain("支付调用必须在领域意图提交后、事务外执行", null);
        }
        if (outTradeNo == null || outTradeNo.isBlank() || mchid == null || mchid.isBlank()
                || appid == null || appid.isBlank() || totalFen == null || totalFen <= 0 || !"CNY".equals(currency)
                || !("APP".equals(tradeType) || "JSAPI".equals(tradeType))) {
            throw PaymentPreorderException.definiteFailure("支付意图必填事实不完整", null);
        }
        final String fingerprint;
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream encoded = new java.io.DataOutputStream(bytes);
            for (String part : new String[]{outTradeNo, mchid, appid, tradeType, currency, totalFen.toString(),
                    openid == null ? "" : openid, description == null ? "" : description, notifyUrl == null ? "" : notifyUrl}) {
                byte[] value = part.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                encoded.writeInt(value.length);
                encoded.write(value);
            }
            fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (Exception error) {
            throw PaymentPreorderException.resultUncertain("支付原请求指纹无法建立", error);
        }
        try {
            // Without a local frozen intent, an existing provider order is historical evidence, not permission
            // to overwrite its channel/request. Recovery must query/close it or use retained original params.
            // Valid legacy external numbers are <=32; longer domain numbers were never valid WeChat numbers.
            var known = paymentIntentJdbc.queryForList("SELECT out_trade_no FROM wx_pay_order_intent WHERE mchid=? AND business_out_trade_no=? ORDER BY attempt_no DESC LIMIT 1", mchid, outTradeNo);
            if (known.isEmpty() && outTradeNo.matches("[A-Za-z0-9_-]{1,32}")) {
                QueryOrderByOutTradeNoRequest legacy = new QueryOrderByOutTradeNoRequest();
                legacy.setMchid(mchid);
                legacy.setOutTradeNo(outTradeNo);
                try {
                    jsapiService.queryOrderByOutTradeNo(legacy);
                    throw new IllegalStateException("历史微信原单缺少冻结请求，须保留原单查证，不得以当前渠道覆盖");
                } catch (com.wechat.pay.java.core.exception.ServiceException missing) {
                    if (missing.getHttpStatusCode() != 404 || !"ORDER_NOT_EXIST".equals(missing.getErrorCode())) throw missing;
                    // Same original external number only; NOT_FOUND cannot release any previously frozen intent.
                }
            }
            var transaction = new org.springframework.transaction.support.TransactionTemplate(paymentIntentTransactions);
            transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return transaction.execute(status -> {
                PaymentBusiness business = lockPaymentBusiness(outTradeNo);
                if (!business.payable()) throw new IllegalStateException("业务订单已不可支付，不能提交旧准备结果");
                String bizType = business.type();
                Number bizId = business.id();
                long expectedFen = business.cents() ? business.amount().longValueExact()
                        : business.amount().movePointRight(2).longValueExact();
                if (expectedFen != totalFen.longValue()) throw new IllegalStateException("支付金额与业务冻结金额不符");
                var active = paymentIntentJdbc.queryForList("SELECT * FROM wx_pay_order_intent WHERE biz_type=? AND biz_id=? AND closed_confirmed_at IS NULL FOR UPDATE", bizType, bizId.longValue());
                var previous = paymentIntentJdbc.queryForList("SELECT * FROM wx_pay_order_intent WHERE biz_type=? AND biz_id=? ORDER BY attempt_no DESC LIMIT 1 FOR UPDATE", bizType, bizId.longValue());
                int attempt;
                String wxOrderNo;
                if (!active.isEmpty()) {
                    var original = active.get(0);
                    if (!mchid.equals(original.get("mchid")) || !outTradeNo.equals(original.get("business_out_trade_no")))
                        throw new IllegalStateException("未关闭原意图的业务身份不能替换");
                    attempt = Math.toIntExact(((Number) original.get("attempt_no")).longValue());
                    wxOrderNo = (String) original.get("out_trade_no");
                } else {
                    attempt = 1;
                    if (!previous.isEmpty()) {
                        var prior = previous.get(0);
                        if (prior.get("closed_confirmed_at") == null || !mchid.equals(prior.get("mchid"))
                                || !outTradeNo.equals(prior.get("business_out_trade_no"))
                                || ((Number) prior.get("total_fen")).longValue() != totalFen.longValue())
                            throw new IllegalStateException("未确认关闭或金额/商户变化不能新发起");
                        attempt = Math.addExact(Math.toIntExact(((Number) prior.get("attempt_no")).longValue()), 1);
                    }
                    wxOrderNo = attempt == 1 && outTradeNo.matches("[A-Za-z0-9_-]{1,32}") ? outTradeNo
                            : "WXP" + sha256(mchid + ":" + bizType + ":" + bizId + ":" + attempt + ":" + outTradeNo).substring(0, 29);
                }
                String wireHash = sha256(wxOrderNo + ":" + fingerprint);
                paymentIntentJdbc.update("INSERT INTO wx_pay_order_intent(mchid,out_trade_no,business_out_trade_no,biz_type,biz_id,attempt_no,appid,trade_type,currency,total_fen,request_hash,payer_openid,request_description,notify_url) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE out_trade_no=out_trade_no",
                        mchid, wxOrderNo, outTradeNo, bizType, bizId.longValue(), attempt, appid, tradeType, currency, totalFen.longValue(), wireHash, openid == null ? "" : openid, description == null ? "" : description, notifyUrl);
                var rows = paymentIntentJdbc.queryForList("SELECT * FROM wx_pay_order_intent WHERE mchid=? AND out_trade_no=? FOR UPDATE", mchid, wxOrderNo);
                if (rows.size() != 1) throw new IllegalStateException("原业务仍有未确认关闭的支付意图");
                var row = rows.get(0);
                if (!bizType.equals(row.get("biz_type")) || !(row.get("biz_id") instanceof Number storedId)
                        || storedId.longValue() != bizId.longValue() || !wireHash.equals(row.get("request_hash"))
                        || !appid.equals(row.get("appid")) || !tradeType.equals(row.get("trade_type"))
                        || !currency.equals(row.get("currency")) || !(row.get("total_fen") instanceof Number storedFen)
                        || storedFen.longValue() != totalFen.longValue()
                        || row.get("closed_confirmed_at") != null) {
                    throw new IllegalStateException("支付渠道/参数已锁定，关闭原单前不能改号或换渠道；关闭原号也不得复用");
                }
                return wxOrderNo;
            });
        } catch (Exception error) {
            // 原意图可能已经调用过微信；本次拒绝不能释放原 PAYING 或变更原身份。
            throw PaymentPreorderException.resultUncertain("支付永久意图未确认或与首次提交冲突", error);
        }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private String currentWechatPaymentNo(String businessOrderNo) {
        var rows = paymentIntentJdbc.queryForList("SELECT out_trade_no FROM wx_pay_order_intent WHERE mchid=? AND business_out_trade_no=? ORDER BY attempt_no DESC LIMIT 1",
                wxPayConfig.getMerchantId(), businessOrderNo);
        return rows.isEmpty() ? businessOrderNo : (String) rows.get(0).get("out_trade_no");
    }

    private String businessPaymentOrderNo(String wxOrderNo) {
        var rows = paymentIntentJdbc.queryForList("SELECT business_out_trade_no FROM wx_pay_order_intent WHERE mchid=? AND out_trade_no=?",
                wxPayConfig.getMerchantId(), wxOrderNo);
        if (rows.isEmpty()) return wxOrderNo; // Legacy existing order: still verified against the actual WeChat order.
        Object value = rows.get(0).get("business_out_trade_no");
        if (!(value instanceof String no) || no.isBlank()) throw new IllegalStateException("永久支付身份映射缺失");
        return no;
    }

    /**
     * 关闭订单
     */
    @Override
    public boolean closeOrder(String outTradeNo) {
        return closePaymentAttempt(outTradeNo, currentWechatPaymentNo(outTradeNo));
    }

    @Override
    public boolean closePaymentAttempt(String businessOutTradeNo, String observedWxOutTradeNo) {
        if (businessOutTradeNo == null || observedWxOutTradeNo == null
                || !businessOutTradeNo.equals(businessPaymentOrderNo(observedWxOutTradeNo))) {
            throw new IllegalArgumentException("关单必须携带原业务及所观察的微信尝试身份");
        }
        try {
            CloseOrderRequest request = new CloseOrderRequest();
            request.setMchid(wxPayConfig.getMerchantId());
            request.setOutTradeNo(observedWxOutTradeNo);
            jsapiService.closeOrder(request);
            Transaction observed = queryVerifiedOrder(observedWxOutTradeNo);
            if (observed.getTradeState() != Transaction.TradeStateEnum.CLOSED) return false;
            recordConfirmedClosedAttempt(observed);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("关闭原支付尝试未确认", e);
        }
    }

    /** Joining an existing domain transaction avoids suspending a caller that already owns the business row. */
    private void recordConfirmedClosedAttempt(Transaction observed) {
        String businessNo = businessPaymentOrderNo(observed.getOutTradeNo());
        var transaction = new org.springframework.transaction.support.TransactionTemplate(paymentIntentTransactions);
        transaction.execute(status -> {
            lockPaymentBusiness(businessNo);
            paymentIntentJdbc.update(
                    "UPDATE wx_pay_order_intent SET closed_confirmed_at=COALESCE(closed_confirmed_at,CURRENT_TIMESTAMP(6)) WHERE mchid=? AND out_trade_no=?",
                    observed.getMchid(), observed.getOutTradeNo());
            return null;
        });
    }

    @Override
    public boolean lockPaymentAttemptForUpdate(String businessOutTradeNo, String observedWxOutTradeNo) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("支付尝试校验必须与领域关闭CAS共用事务");
        }
        if (businessOutTradeNo == null || observedWxOutTradeNo == null || observedWxOutTradeNo.isBlank()) return false;
        PaymentBusiness business = lockPaymentBusiness(businessOutTradeNo);
        var rows = paymentIntentJdbc.queryForList(
                "SELECT * FROM wx_pay_order_intent WHERE biz_type=? AND biz_id=? ORDER BY attempt_no DESC LIMIT 1 FOR UPDATE",
                business.type(), business.id().longValue());
        if (rows.isEmpty()) return businessOutTradeNo.equals(observedWxOutTradeNo); // Verified historical provider number.
        var current = rows.get(0);
        return observedWxOutTradeNo.equals(current.get("out_trade_no"))
                && businessOutTradeNo.equals(current.get("business_out_trade_no"))
                && wxPayConfig.getMerchantId().equals(current.get("mchid"));
    }

    private record PaymentBusiness(String type, Number id, java.math.BigDecimal amount, boolean cents, boolean payable) { }

    /** The same business row is the serialization point for new aliases, CLOSED facts and domain closure. */
    private PaymentBusiness lockPaymentBusiness(String businessNo) {
        String type;
        String sql;
        boolean cents = false;
        if (businessNo.startsWith("CLM")) {
            type = "CLM"; cents = true;
            sql = "SELECT id,amount_fen AS amount,CASE WHEN status='PAYING' THEN 1 ELSE 0 END AS payable FROM club_membership_invoice WHERE out_trade_no=? FOR UPDATE";
        } else if (businessNo.startsWith("DEP")) {
            type = "DEP";
            sql = "SELECT id,deposit_amount AS amount,CASE WHEN deposit_status=1 AND status=1 AND del_flag=0 THEN 1 ELSE 0 END AS payable FROM coop_invite WHERE deposit_out_trade_no=? FOR UPDATE";
        } else if (businessNo.startsWith("TPLTM_") || businessNo.startsWith("PROMOT_") || businessNo.startsWith("CUSTOM_")) {
            type = "COMMERCE";
            sql = "SELECT id,pay_amount AS amount,CASE WHEN status=0 THEN 1 ELSE 0 END AS payable FROM oms_order WHERE order_sn=? FOR UPDATE";
        } else {
            type = "REGISTRATION";
            sql = "SELECT id,wechat_payment_amount AS amount,CASE WHEN payment_status=1 AND registration_status=1 THEN 1 ELSE 0 END AS payable FROM cms_registration WHERE registration_no=? FOR UPDATE";
        }
        var rows = paymentIntentJdbc.queryForList(sql, businessNo);
        if (rows.size() != 1 || !(rows.get(0).get("id") instanceof Number id)
                || rows.get(0).get("amount") == null) throw new IllegalStateException("已提交业务支付身份缺失");
        var row = rows.get(0);
        boolean payable = row.get("payable") instanceof Number flag && flag.intValue() == 1;
        return new PaymentBusiness(type, id, new java.math.BigDecimal(row.get("amount").toString()), cents, payable);
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
