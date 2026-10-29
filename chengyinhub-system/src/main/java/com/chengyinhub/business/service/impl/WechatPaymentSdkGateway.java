package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.config.RestoreDispatchGuard;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.WechatPaymentOperations;
import com.chengyinhub.business.service.PaymentPreorderException;
import com.chengyinhub.business.service.WechatOrderNotExistException;
import com.chengyinhub.business.service.RefundRequestResult;
import com.chengyinhub.common.config.WxPayConfig;
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
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 微信支付服务实现 (适配新版SDK v3) - 修正版
 */
@Component
public class WechatPaymentSdkGateway implements WechatPaymentOperations {

    private static final Logger logger = LoggerFactory.getLogger(WechatPaymentSdkGateway.class);

    @Autowired
    private WxPayConfig wxPayConfig;

    @Autowired
    private WechatPaymentIntentStore intents;

    private JsapiService jsapiService;
    private JsapiServiceExtension jsapiServiceExtension;
    /** App 支付(trade_type=APP)。与 JSAPI 共用同一套商户配置,只是下单 appid 不同。 */
    private com.wechat.pay.java.service.payments.app.AppServiceExtension appServiceExtension;
    private RefundService refundService;
    private TransferBatchService transferBatchService;
    /** One immutable publication couples the client with its SDK credential merchant, never a mutable property. */
    private record TransferClientBinding(TransferBatchService client, String mchid, String wxAppid, String appAppid) {
        /** Legacy fixture shape contains no application proof and cannot authorize a typed transfer. */
        private TransferClientBinding(TransferBatchService client, String mchid) { this(client, mchid, null, null); }
    }
    private volatile TransferClientBinding transferClientBinding;
    private record VerifiedProviderClients(JsapiService payments, RefundService refunds, String mchid) { }
    private volatile VerifiedProviderClients verifiedProviderClients;
    @Autowired private WechatVerifiedPaymentFactService verifiedFacts;
    private Config config;

    private NotificationParser notificationParser;

    @Autowired
    private UmsMemberMapper paymentMemberMapper;

    /**
     * 初始化微信支付服务 - 容错版本
     */
    @PostConstruct
    public void init() {
        transferClientBinding = null;
        verifiedProviderClients = null;
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
                transferClientBinding = new TransferClientBinding(builtTransferClient, boundMerchantId,
                        wxPayConfig.getAppid(), wxPayConfig.getAppAppid());
                // Publish query clients bound to the very same SDK credential; mutable properties are not provider evidence.
                jsapiService = new JsapiService.Builder().httpClient(transferHttpClient).build();
                refundService = new RefundService.Builder().httpClient(transferHttpClient).build();
                verifiedProviderClients = new VerifiedProviderClients(jsapiService,refundService,boundMerchantId);

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
        verifiedProviderClients = null;
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
        RestoreDispatchGuard.requireDispatchAllowed();
        requireObservationOutsideBusinessTransaction();
        try {
            if (toWechatRefundCents(refundAmount)>toWechatRefundCents(totalAmount)
                    || outRefundNo==null || !outRefundNo.matches("[A-Za-z0-9_-]{1,64}"))
                return RefundRequestResult.DEFINITIVE_FAILED;
        } catch (ArithmeticException | IllegalArgumentException invalidAmount) {
            return RefundRequestResult.DEFINITIVE_FAILED;
        }
        try {
            var original=verifiedFacts.findPaymentSuccess(outTradeNo);
            if (original==null) return RefundRequestResult.UNKNOWN;
            return submitRefund(original.reference(),outTradeNo,outRefundNo,refundAmount,totalAmount,reason);
        } catch (RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (RuntimeException unavailableOrConflicting) {
            return RefundRequestResult.UNKNOWN;
        }
    }

    @Override
    public RefundRequestResult submitRefund(WechatVerifiedPaymentFact.Reference originalPayment,
                                            String outTradeNo, String outRefundNo, java.math.BigDecimal refundAmount,
                                            java.math.BigDecimal totalAmount, String reason) {
        RestoreDispatchGuard.requireDispatchAllowed();
        requireObservationOutsideBusinessTransaction();
        final long refundCents;
        final long totalCents;
        try {
            refundCents=toWechatRefundCents(refundAmount);
            totalCents=toWechatRefundCents(totalAmount);
            if (refundCents>totalCents || outRefundNo==null || !outRefundNo.matches("[A-Za-z0-9_-]{1,64}"))
                return RefundRequestResult.DEFINITIVE_FAILED;
        } catch (ArithmeticException | IllegalArgumentException invalidAmount) {
            return RefundRequestResult.DEFINITIVE_FAILED;
        }
        VerifiedProviderClients clients=verifiedProviderClients;
        if (clients==null || clients.refunds()==null) return RefundRequestResult.UNKNOWN;
        try {
            var paid=verifiedFacts.requireVerified(originalPayment).facts();
            if (paid.kind()!=WechatVerifiedPaymentFact.Kind.PAYMENT || !paid.businessOutTradeNo().equals(outTradeNo)
                    || !paid.mchid().equals(clients.mchid())
                    || !"CNY".equals(paid.currency()) || paid.totalFen()!=totalCents
                    || paid.amountFen()!=paid.totalFen()) return RefundRequestResult.UNKNOWN;
            assertOriginalFactSnapshot(paid);
            // WeChat transaction_id identifies the paid attempt, even when another alias is now current.
            return submitRefundRequest(clients.refunds(),paid.wxOutTradeNo(),paid.transactionId(),
                    outRefundNo,refundCents,totalCents,reason);
        } catch (RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (RuntimeException unavailable) {
            logger.warn("原支付事实约束的退款提交未知 type={}",unavailable.getClass().getSimpleName());
            return RefundRequestResult.UNKNOWN;
        }
    }

    private RefundRequestResult submitRefundRequest(RefundService client,String wxOrderNo,String transactionId,
            String outRefundNo,long refundCents,long totalCents,String reason) {
        RestoreDispatchGuard.requireDispatchAllowed();
        try {
            CreateRequest request = new CreateRequest();
            request.setTransactionId(transactionId);
            request.setOutRefundNo(outRefundNo);
            if (reason != null && !reason.isEmpty()) {
                request.setReason(reason.length() > 80 ? reason.substring(0, 80) : reason);
            }
            String refundNotifyUrl = wxPayConfig.getRefundNotifyUrl();
            if (refundNotifyUrl != null && !refundNotifyUrl.trim().isEmpty()) {
                request.setNotifyUrl(refundNotifyUrl);
            }
            AmountReq amount = new AmountReq();
            amount.setRefund(refundCents);
            amount.setTotal(totalCents);
            amount.setCurrency("CNY");
            request.setAmount(amount);
            Refund refund = client.create(request);
            if (transactionId!=null && (refund==null || !transactionId.equals(refund.getTransactionId())
                    || !wxOrderNo.equals(refund.getOutTradeNo()) || !outRefundNo.equals(refund.getOutRefundNo())
                    || refund.getAmount()==null || refund.getAmount().getTotal()==null || refund.getAmount().getRefund()==null
                    || refund.getAmount().getTotal()!=totalCents || refund.getAmount().getRefund()!=refundCents
                    || !"CNY".equals(refund.getAmount().getCurrency()))) return RefundRequestResult.UNKNOWN;
            Status st = (refund == null) ? null : refund.getStatus();
            if (st == Status.SUCCESS || st == Status.PROCESSING) return RefundRequestResult.ACCEPTED;
            if (st == Status.CLOSED || st == Status.ABNORMAL) return RefundRequestResult.DEFINITIVE_FAILED;
            return RefundRequestResult.UNKNOWN;
        } catch (Exception e) {
            logger.warn("微信原路退款结果未知 outRefundNo={}: {}",outRefundNo,e.getMessage());
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
        RestoreDispatchGuard.requireDispatchAllowed();
        return submitTransfer(transferBatchService, outBatchNo, outDetailNo, amountYuan, openid, remark, appid);
    }

    private Map<String, String> submitTransfer(TransferBatchService client, String outBatchNo, String outDetailNo,
            java.math.BigDecimal amountYuan, String openid, String remark, String appid) {
        RestoreDispatchGuard.requireDispatchAllowed();
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
        RestoreDispatchGuard.requireDispatchAllowed();
        // A persisted master debit snapshot has its own existing validation/dispatch claim.
        // Do not reinterpret this as a ledger SUBMIT_INTENT or convert its V1 hash.
        boolean debitSnapshot = intent != null && intent.hasDebitSnapshot();
        if (debitSnapshot) {
            intent.requireValid();
            if (!intent.submitted() || intent.locallyRejected()
                    || !intent.mchid().equals(wxPayConfig.getMerchantId())
                    || !(intent.appid().equals(wxPayConfig.getAppid())
                        || intent.appid().equals(wxPayConfig.getAppAppid()))
                    || org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                return Map.of("ok", "false", "ambiguous", "false", "notSent", "true",
                        "requestHash", intent.requestHash(), "error", "原提现渠道未就绪或配置已变化，未发起转账");
            }
        }
        final TransferClientBinding bound;
        boolean originalValidated = false;
        try {
            requireOriginalTransferIntent(intent);
            originalValidated = true;
            bound = boundOriginalTransferClient(intent);
        } catch (RuntimeException invalidOriginal) {
            Map<String, String> result = new HashMap<>();
            // Only a fully validated original request may receive a pre-SDK rejection certificate.
            boolean notSent = debitSnapshot || originalValidated;
            result.put("ok", "false"); result.put("ambiguous", Boolean.toString(!notSent));
            result.put("notSent", Boolean.toString(notSent));
            if (intent != null) result.put("requestHash", intent.requestHash());
            result.put("error", "原提现意图或client商户绑定无法核验，保留原号查证");
            return result;
        }
        // The definitely-not-sent catch ends before SDK entry. Later exceptions remain unknown.
        Map<String, String> result = submitTransfer(bound.client(), intent.outBatchNo(), intent.outDetailNo(),
                java.math.BigDecimal.valueOf(intent.amountFen(), 2), intent.openid(),
                intent.effectiveRemark(), intent.appid());
        result.put("requestHash", intent.requestHash());
        result.put("notSent", String.valueOf("false".equals(result.get("ok"))
                && "false".equals(result.get("ambiguous"))));
        return result;
    }

    @Override
    public Map<String, String> queryTransferResult(CashWithdrawTransferIntent intent) {
        try {
            TransferClientBinding bound = originalTransferClient(intent);
            if (intent.hasDebitSnapshot() && !intent.mchid().equals(wxPayConfig.getMerchantId()))
                return Map.of("result", "UNKNOWN", "error", "原提现商户渠道未就绪，须按原号核查");
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
        requireOriginalTransferIntent(intent);
        return boundOriginalTransferClient(intent);
    }

    private void requireOriginalTransferIntent(CashWithdrawTransferIntent intent) {
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
    }

    private TransferClientBinding boundOriginalTransferClient(CashWithdrawTransferIntent intent) {
        TransferClientBinding bound = transferClientBinding;
        if (bound == null || bound.client() == null || !intent.mchid().equals(bound.mchid())
                || !(intent.appid().equals(bound.wxAppid()) || intent.appid().equals(bound.appAppid()))) {
            throw new IllegalStateException("原商户与SDK client绑定不符，不得改渠道重发");
        }
        return bound;
    }

    /**
     * 创建微信支付订单 - 主方法
     */
    @Override
    public Map<String, String> createOrder(CmsRegistration registration) {
        RestoreDispatchGuard.requireDispatchAllowed();
        if (!isReady()) {
            throw PaymentPreorderException.definiteFailure("支付服务暂不可用，请稍后再试", null);
        }
        requireNotifyUrl();
        PrepayRequest request;
        try {
            request = buildPrepayRequest(registration);
        } catch (RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (Exception e) {
            throw PaymentPreorderException.definiteFailure("创建微信支付订单参数异常：" + e.getMessage(), e);
        }
        try {
            // 一旦进入 SDK 网络调用，异常无法证明微信未受理；保持 PAYING 等回调自动恢复。
            return createOrderWithExtension(request);
        } catch (RestoreDispatchGuard.Blocked paused) {
            throw paused;
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
        RestoreDispatchGuard.requireDispatchAllowed();
        requireAppChannelAppid();
        requireNotifyUrl();
        com.wechat.pay.java.service.payments.app.model.PrepayRequest request;
        try {
            request = buildAppPrepayRequest(registration);
        } catch (RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (Exception e) {
            throw PaymentPreorderException.definiteFailure("创建 App 支付订单参数异常：" + e.getMessage(), e);
        }
        return prepayApp(request);
    }

    @Override
    public Map<String, String> createGenericAppOrder(String outTradeNo, java.math.BigDecimal amountYuan,
                                                      String description) {
        RestoreDispatchGuard.requireDispatchAllowed();
        String appAppid = requireAppChannelAppid();
        requireNotifyUrl();
        com.wechat.pay.java.service.payments.app.model.PrepayRequest request;
        try {
            request = buildGenericAppPrepayRequest(outTradeNo, amountYuan, description, appAppid);
        } catch (RestoreDispatchGuard.Blocked paused) {
            throw paused;
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
        RestoreDispatchGuard.requireDispatchAllowed();
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
        RestoreDispatchGuard.requireDispatchAllowed();
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
        } catch (PaymentPreorderException deferredOriginal) {
            throw deferredOriginal;
        } catch (RestoreDispatchGuard.Blocked paused) {
            throw paused;
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
        RestoreDispatchGuard.requireDispatchAllowed();
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
        RestoreDispatchGuard.requireDispatchAllowed();
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

    private boolean matchesExpectedPayment(Transaction transaction) {
        return verifiedPaymentOriginal(transaction) != null;
    }

    @Override
    public String queryRefundStatus(String outRefundNo) {
        Map<String,String> facts = queryRefundFacts(outRefundNo);
        return facts == null || "NOT_FOUND".equals(facts.get("status")) ? null : facts.get("status");
    }

    @Override
    public Map<String,String> queryRefundFacts(String outRefundNo) {
        return queryRefundFactsForOriginal(outRefundNo,null);
    }

    @Override
    public Map<String,String> queryRefundFacts(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference originalPayment, String outRefundNo) {
        try {
            var expected=verifiedFacts.requireVerified(originalPayment);
            if (expected.facts().kind()!=com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Kind.PAYMENT
                    || verifiedProviderClients==null || !expected.facts().mchid().equals(verifiedProviderClients.mchid())) return null;
            return queryRefundFactsForOriginal(outRefundNo,expected);
        } catch (RuntimeException unavailable) {
            logger.warn("原支付事实约束的退款查单未知 type={}",unavailable.getClass().getSimpleName());
            return null;
        }
    }

    private Map<String,String> queryRefundFactsForOriginal(String outRefundNo,WechatVerifiedPaymentFactService.Stored expected) {
        requireObservationOutsideBusinessTransaction();
        VerifiedProviderClients clients = verifiedProviderClients;
        if (expected!=null && (clients==null || !expected.facts().mchid().equals(clients.mchid()))) return null;
        if (clients == null || outRefundNo == null || !outRefundNo.matches("[A-Za-z0-9_-]{1,64}")) return null;
        try {
            QueryByOutRefundNoRequest request = new QueryByOutRefundNoRequest(); request.setOutRefundNo(outRefundNo);
            Refund refund = clients.refunds().queryByOutRefundNo(request);
            if (refund == null || !outRefundNo.equals(refund.getOutRefundNo()) || refund.getStatus() == null
                    || refund.getRefundId() == null || !refund.getRefundId().matches("[A-Za-z0-9_-]{1,64}")
                    || refund.getTransactionId() == null || refund.getAmount() == null
                    || refund.getAmount().getTotal() == null || refund.getAmount().getRefund() == null) return null;
            Transaction original = queryVerifiedOrder(refund.getOutTradeNo());
            if (!clients.mchid().equals(original.getMchid()) || !refund.getTransactionId().equals(original.getTransactionId())
                    || !"CNY".equals(refund.getAmount().getCurrency())
                    || refund.getAmount().getTotal().longValue()!=original.getAmount().getTotal().longValue()
                    || refund.getAmount().getRefund()<=0 || refund.getAmount().getRefund()>refund.getAmount().getTotal()) return null;
            if (expected!=null) {
                var paid=expected.facts();
                if (!paid.wxOutTradeNo().equals(refund.getOutTradeNo())
                        || !paid.businessOutTradeNo().equals(businessPaymentOrderNo(original.getOutTradeNo()))
                        || !paid.mchid().equals(original.getMchid()) || !paid.appid().equals(original.getAppid())
                        || !paid.transactionId().equals(original.getTransactionId())
                        || !paid.tradeType().equals(original.getTradeType().name())
                        || !paid.currency().equals(original.getAmount().getCurrency())
                        || paid.totalFen()!=original.getAmount().getTotal().longValue()
                        || !java.util.Objects.equals(paid.intentRequestHash(),frozenRequestHash(original))) return null;
            }
            Map<String,String> facts = refundFacts(refund.getOutRefundNo(),refund.getOutTradeNo(),
                    refund.getStatus().name(),refund.getAmount());
            facts.put("mchid",original.getMchid()); facts.put("appid",original.getAppid());
            facts.put("tradeType",original.getTradeType().name());
            facts.put("transactionId",refund.getTransactionId()); facts.put("refundId",refund.getRefundId());
            facts.put("successTime",refund.getSuccessTime());
            if (refund.getStatus()==Status.SUCCESS) {
                var fact = new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact(
                        com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Kind.REFUND,
                        original.getMchid(),original.getAppid(),facts.get("outTradeNo"),original.getOutTradeNo(),
                        refund.getOutRefundNo(),refund.getRefundId(),refund.getTransactionId(),original.getTradeType().name(),
                        "CNY",refund.getAmount().getTotal(),refund.getAmount().getRefund(),
                        providerSuccessTime(refund.getSuccessTime()),frozenRequestHash(original),original.getTradeState().name());
                addVerifiedReference(facts,verifiedFacts.recordVerified(fact));
            }
            return facts;
        } catch (com.wechat.pay.java.core.exception.ServiceException e) {
            if (e.getHttpStatusCode() == 404 && "RESOURCE_NOT_EXISTS".equals(e.getErrorCode())) {
                Map<String,String> missing = new HashMap<>(); missing.put("status", "NOT_FOUND"); return missing;
            }
            logger.warn("退款查单无法核验 type={}",e.getClass().getSimpleName()); return null;
        } catch (Exception e) { logger.warn("退款查单事实未知 type={}",e.getClass().getSimpleName()); return null; }
    }

    Map<String,String> refundFacts(String refundNo, String tradeNo, String state,
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
            if (transaction.getTradeState()==Transaction.TradeStateEnum.SUCCESS) {
                addVerifiedReference(result,recordPaymentSuccess(transaction));
            }
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
        requireObservationOutsideBusinessTransaction();
        VerifiedProviderClients clients = verifiedProviderClients;
        if (clients == null) throw new IllegalStateException("原商户SDK查询client缺失");
        QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
        request.setMchid(clients.mchid());
        request.setOutTradeNo(outTradeNo);
        Transaction original = clients.payments().queryOrderByOutTradeNo(request);
        if (original == null || outTradeNo == null || !outTradeNo.equals(original.getOutTradeNo())
                || original.getMchid() == null || original.getMchid().trim().isEmpty()
                || !original.getMchid().equals(clients.mchid())
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
        java.util.List<java.util.Map<String, Object>> frozen = intents.frozenFacts(original.getMchid(), outTradeNo);
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

    /**
     * 关闭订单
     */
    @Override
    public boolean closeOrder(String outTradeNo) {
        return closePaymentAttempt(outTradeNo, currentWechatPaymentNo(outTradeNo));
    }

    @Override
    public boolean closePaymentAttempt(String businessOutTradeNo, String observedWxOutTradeNo) {
        RestoreDispatchGuard.requireDispatchAllowed();
        requireObservationOutsideBusinessTransaction();
        if (businessOutTradeNo == null || observedWxOutTradeNo == null
                || !businessOutTradeNo.equals(businessPaymentOrderNo(observedWxOutTradeNo))) {
            throw new IllegalArgumentException("关单必须携带原业务及所观察的微信尝试身份");
        }
        try {
            VerifiedProviderClients clients=verifiedProviderClients;
            if (clients==null || !intents.currentAttemptClosable(businessOutTradeNo,observedWxOutTradeNo,clients.mchid())) return false;
            CloseOrderRequest request = new CloseOrderRequest();
            request.setMchid(clients.mchid());
            request.setOutTradeNo(observedWxOutTradeNo);
            clients.payments().closeOrder(request);
            Transaction observed = queryVerifiedOrder(observedWxOutTradeNo);
            if (observed.getTradeState() != Transaction.TradeStateEnum.CLOSED) return false;
            recordConfirmedClosedAttempt(observed);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("关闭原支付尝试未确认", e);
        }
    }

    /** Joining an existing domain transaction avoids suspending a caller that already owns the business row. */

    /** The same business row is the serialization point for new aliases, CLOSED facts and domain closure. */

    /**
     * 获取用户openid（实际项目中需要根据用户ID查询）
     */
    private String getMemberOpenid(Long memberId) {
        // 根据memberId查询openid
        UmsMember member = paymentMemberMapper.selectUmsMemberById(memberId);
        if (member != null) {
            return member.getWxOpenId();
        }
        else {
            throw new RuntimeException("用户不存在");
        }
    }

    String freezePaymentIntent(String outTradeNo, String mchid, String appid, String tradeType,
                             String currency, Integer totalFen, String openid, String description, String notifyUrl) {
        return intents.freezePaymentIntent(outTradeNo, mchid, appid, tradeType, currency, totalFen,
                openid, description, notifyUrl, () -> {
                    QueryOrderByOutTradeNoRequest legacy = new QueryOrderByOutTradeNoRequest();
                    legacy.setMchid(mchid); legacy.setOutTradeNo(outTradeNo);
                    try {
                        jsapiService.queryOrderByOutTradeNo(legacy);
                        throw new IllegalStateException("历史微信原单缺少冻结请求，须保留原单查证，不得以当前渠道覆盖");
                    } catch (com.wechat.pay.java.core.exception.ServiceException missing) {
                        if (missing.getHttpStatusCode() != 404 || !"ORDER_NOT_EXIST".equals(missing.getErrorCode())) throw missing;
                    }
                });
    }
    private String currentWechatPaymentNo(String businessNo) {
        return intents.currentWechatPaymentNo(businessNo, verifiedProviderMerchant());
    }
    private String businessPaymentOrderNo(String wxNo) {
        return intents.businessPaymentOrderNo(wxNo, verifiedProviderMerchant());
    }
    private void recordConfirmedClosedAttempt(Transaction observed) {
        intents.recordConfirmedClosedAttempt(observed.getMchid(), observed.getOutTradeNo());
    }
    @Override
    public boolean lockPaymentAttemptForUpdate(String businessNo, String observedWxNo) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("支付尝试校验必须与领域关闭CAS共用事务");
        }
        if (businessNo == null || observedWxNo == null || observedWxNo.isBlank()) return false;
        return intents.lockPaymentAttemptForUpdate(businessNo, observedWxNo, wxPayConfig.getMerchantId());
    }
    @Override
    public boolean lockClosablePaymentAttemptForUpdate(String businessNo,String observedWxNo) {
        return intents.lockClosablePaymentAttemptForUpdate(businessNo,observedWxNo,verifiedProviderMerchant());
    }
    Transaction parsePaymentNotification(String body, Map<String, String> headers) {
        requireObservationOutsideBusinessTransaction();
        return notificationParser.parse(notification(body, headers), Transaction.class);
    }
    RefundNotification parseRefundNotification(String body, Map<String, String> headers) {
        requireObservationOutsideBusinessTransaction();
        return notificationParser.parse(notification(body, headers), RefundNotification.class);
    }
    private RequestParam notification(String body, Map<String, String> headers) {
        return new RequestParam.Builder().serialNumber(headers.get("wechatpay-serial"))
                .nonce(headers.get("wechatpay-nonce")).signature(headers.get("wechatpay-signature"))
                .timestamp(headers.get("wechatpay-timestamp")).body(body).build();
    }
    Map<String, String> verifiedPaymentFacts(Transaction transaction) {
        if (transaction!=null && transaction.getTradeState()==Transaction.TradeStateEnum.SUCCESS) {
            try {
                Map<String,String> replay=replayVerifiedPaymentNotification(transaction);
                if (replay!=null) return replay;
            } catch (WechatVerifiedPaymentFactService.ConflictException | IllegalArgumentException inconsistent) {
                rejectPaymentNotification(transaction);
                return null;
            }
        }
        Transaction original=verifiedPaymentOriginal(transaction);
        if (original==null) {
            rejectPaymentNotification(transaction);
            return null;
        }
        if (transaction.getTradeState()==Transaction.TradeStateEnum.CLOSED
                && verifiedFacts.hasConfirmedExternalSuccess(businessPaymentOrderNo(original.getOutTradeNo()))) {
            // A signed CLOSED observation cannot undo earlier immutable payment/refund success evidence.
            rejectPaymentNotification(transaction);
            return null;
        }
        Map<String, String> facts = new HashMap<>();
        facts.put("out_trade_no", businessPaymentOrderNo(transaction.getOutTradeNo()));
        facts.put("wx_out_trade_no", transaction.getOutTradeNo());
        facts.put("transaction_id", transaction.getTransactionId());
        facts.put("trade_state", transaction.getTradeState().toString());
        facts.put("appid", transaction.getAppid()); facts.put("mchid", transaction.getMchid());
        facts.put("trade_type", transaction.getTradeType().toString());
        if (transaction.getAmount() != null) {
            facts.put("total", String.valueOf(transaction.getAmount().getTotal()));
            facts.put("currency", transaction.getAmount().getCurrency());
        }
        if (original.getTradeState()==Transaction.TradeStateEnum.SUCCESS) addVerifiedReference(facts,recordPaymentSuccess(original));
        return facts;
    }
    private Transaction verifiedPaymentOriginal(Transaction transaction) {
        if (transaction == null || (transaction.getTradeState() != Transaction.TradeStateEnum.SUCCESS
                && transaction.getTradeState() != Transaction.TradeStateEnum.CLOSED
                && transaction.getTradeState() != Transaction.TradeStateEnum.REFUND)
                || transaction.getOutTradeNo() == null
                || !transaction.getOutTradeNo().matches("[A-Za-z0-9_-]{1,64}")
                || transaction.getTransactionId() == null
                || !transaction.getTransactionId().matches("[A-Za-z0-9_-]{1,64}")
                || transaction.getAppid() == null || transaction.getAppid().trim().isEmpty()
                || transaction.getMchid() == null || transaction.getMchid().trim().isEmpty()
                || verifiedProviderClients == null || !transaction.getMchid().equals(verifiedProviderClients.mchid())
                || transaction.getTradeType() == null
                || transaction.getAmount() == null || transaction.getAmount().getTotal() == null
                || transaction.getAmount().getTotal() <= 0
                || !"CNY".equals(transaction.getAmount().getCurrency())) {
            return null;
        }
        // APP/JSAPI 共用 /v3/pay/transactions/out-trade-no/{out_trade_no}。
        // 官方应答中的 appid/mchid 是创建原单时传入的值；SDK 验签后才能作为渠道证据。
        // 不从当前配置的 appid 集合、通知正文或可过期 payParams 反推“预期应用”。
        Transaction original = queryVerifiedOrder(transaction.getOutTradeNo());
        boolean matches = original != null
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
        if (matches && transaction.getTradeState()==Transaction.TradeStateEnum.SUCCESS) {
            matches=matchesProviderSuccessTime(providerSuccessTime(original.getSuccessTime()),transaction.getSuccessTime());
        }
        return matches ? original : null;
    }

    private com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference recordPaymentSuccess(Transaction original) {
        if (original.getTradeState()!=Transaction.TradeStateEnum.SUCCESS) throw new IllegalArgumentException("不是支付成功事实");
        return verifiedFacts.recordVerified(new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact(
                com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Kind.PAYMENT,
                original.getMchid(),original.getAppid(),businessPaymentOrderNo(original.getOutTradeNo()),original.getOutTradeNo(),
                original.getOutTradeNo(),original.getTransactionId(),original.getTransactionId(),original.getTradeType().name(),
                original.getAmount().getCurrency(),original.getAmount().getTotal(),original.getAmount().getTotal(),
                providerSuccessTime(original.getSuccessTime()),frozenRequestHash(original),original.getTradeState().name()));
    }

    private static void requireObservationOutsideBusinessTransaction() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("官方SDK查证及独立事实写入必须在原业务事务外执行");
    }

    private String verifiedProviderMerchant() {
        VerifiedProviderClients clients=verifiedProviderClients;
        if (clients==null || clients.mchid()==null || clients.mchid().isBlank()) throw new IllegalStateException("原SDK商户绑定缺失");
        return clients.mchid();
    }

    private static java.time.Instant providerSuccessTime(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalStateException("微信成功时间缺失");
        return java.time.OffsetDateTime.parse(raw).toInstant();
    }

    private static void addVerifiedReference(Map<String,String> facts,
            com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference reference) {
        facts.put("verified_fact_id",Long.toString(reference.id())); facts.put("verified_fact_hash",reference.hash());
    }

    private String frozenRequestHash(Transaction original) {
        return intents.frozenRequestHash(original.getMchid(),original.getOutTradeNo());
    }
    boolean matchesRefundNotification(RefundNotification notification, Map<String,String> queried) {
        if (queried == null || notification.getRefundStatus()==null || notification.getAmount()==null
                || notification.getRefundId()==null || notification.getTransactionId()==null
                || notification.getAmount().getTotal()==null || notification.getAmount().getRefund()==null) return false;
        boolean matches = notification.getOutRefundNo().equals(queried.get("outRefundNo"))
                && notification.getOutTradeNo()!=null && notification.getOutTradeNo().equals(queried.get("wxOutTradeNo"))
                && notification.getRefundId().equals(queried.get("refundId"))
                && notification.getTransactionId().equals(queried.get("transactionId"))
                && notification.getRefundStatus().name().equals(queried.get("status"))
                && "CNY".equals(notification.getAmount().getCurrency())
                && notification.getAmount().getTotal().toString().equals(queried.get("total"))
                && notification.getAmount().getRefund().toString().equals(queried.get("refund"));
        if (matches && notification.getRefundStatus()==Status.SUCCESS) {
            try {
                matches = providerSuccessTime(notification.getSuccessTime()).equals(providerSuccessTime(queried.get("successTime")))
                        && queried.get("verified_fact_id")!=null && queried.get("verified_fact_hash")!=null;
            } catch (RuntimeException invalidTime) { return false; }
        }
        return matches;
    }

    void recordRejectedRefundNotification(RefundNotification notif, Map<String,String> facts) {
        verifiedFacts.recordRejectedRefundNotification(
                facts!=null && facts.get("verified_fact_id")!=null && facts.get("verified_fact_hash")!=null
                ? new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(
                        Long.parseLong(facts.get("verified_fact_id")),facts.get("verified_fact_hash")) : null,
                new String[]{notif.getOutRefundNo(),notif.getOutTradeNo(),notif.getRefundId(),notif.getTransactionId(),
                        notif.getRefundStatus()==null?null:notif.getRefundStatus().name(),
                        notif.getAmount()==null?null:String.valueOf(notif.getAmount().getTotal()),
                        notif.getAmount()==null?null:String.valueOf(notif.getAmount().getRefund()),
                        notif.getAmount()==null?null:notif.getAmount().getCurrency(),notif.getSuccessTime()});
    }
    /** Called only after the facade has verified/decrypted the incoming notification. Never a current-status query fallback. */
    private Map<String,String> replayVerifiedPaymentNotification(Transaction notification) {
        String mchid=verifiedProviderMerchant();
        if (!mchid.equals(notification.getMchid()) || notification.getOutTradeNo()==null
                || notification.getTransactionId()==null) throw new IllegalArgumentException("Original signed payment identity missing");
        var stored=verifiedFacts.findPaymentSuccess(mchid,notification.getOutTradeNo(),notification.getTransactionId());
        if (stored==null) return null;
        var fact=stored.facts();
        if (!fact.appid().equals(notification.getAppid()) || notification.getTradeType()==null
                || !fact.tradeType().equals(notification.getTradeType().name()) || notification.getAmount()==null
                || notification.getAmount().getTotal()==null || fact.totalFen()!=notification.getAmount().getTotal().longValue()
                || !fact.currency().equals(notification.getAmount().getCurrency())
                || !matchesProviderSuccessTime(fact.successAt(),notification.getSuccessTime())) {
            throw new WechatVerifiedPaymentFactService.ConflictException();
        }
        assertOriginalFactSnapshot(fact);
        if (!fact.wxOutTradeNo().equals(currentWechatPaymentNo(fact.businessOutTradeNo())))
            throw new WechatVerifiedPaymentFactService.ConflictException();
        Map<String,String> result=new HashMap<>();
        result.put("out_trade_no",fact.businessOutTradeNo());result.put("wx_out_trade_no",fact.wxOutTradeNo());
        result.put("transaction_id",fact.transactionId());result.put("trade_state","SUCCESS");
        result.put("appid",fact.appid());result.put("mchid",fact.mchid());result.put("trade_type",fact.tradeType());
        result.put("total",Long.toString(fact.totalFen()));result.put("currency",fact.currency());
        addVerifiedReference(result,stored.reference());return result;
    }
    /** Signature/decryption has already succeeded; persisted success never bypasses that SDK boundary. */
    Map<String,String> verifiedRefundFacts(RefundNotification notification) {
        if (notification==null) return null;
        try {
            var stored=verifiedFacts.findRefundSuccess(verifiedProviderMerchant(),notification.getOutRefundNo(),notification.getRefundId());
            if (stored!=null) {
                if (notification.getRefundStatus()!=Status.SUCCESS) {
                    recordRejectedRefundNotification(notification,null);
                    return null; // Keep UNKNOWN; never manufacture a SUCCESS callback from a conflicting status.
                }
                var fact=stored.facts();assertOriginalFactSnapshot(fact);
                Map<String,String> result=new HashMap<>();
                result.put("outRefundNo",fact.merchantEventNo());result.put("outTradeNo",fact.businessOutTradeNo());
                result.put("wxOutTradeNo",fact.wxOutTradeNo());result.put("status","SUCCESS");
                result.put("refund",Long.toString(fact.amountFen()));result.put("total",Long.toString(fact.totalFen()));
                result.put("currency",fact.currency());result.put("mchid",fact.mchid());result.put("appid",fact.appid());
                result.put("tradeType",fact.tradeType());result.put("refundId",fact.providerId());result.put("transactionId",fact.transactionId());
                result.put("successTime",fact.successAt().toString());addVerifiedReference(result,stored.reference());return result;
            }
        } catch (WechatVerifiedPaymentFactService.ConflictException | IllegalArgumentException inconsistent) {
            recordRejectedRefundNotification(notification,null);
            return null;
        }
        return queryRefundFacts(notification.getOutRefundNo());
    }
    private static boolean matchesProviderSuccessTime(java.time.Instant expected,String raw) {
        try { return expected.equals(providerSuccessTime(raw)); } catch (RuntimeException invalidTime) { return false; }
    }
    private void assertOriginalFactSnapshot(WechatVerifiedPaymentFact fact) {
        if (!verifiedProviderMerchant().equals(fact.mchid())
                || !fact.businessOutTradeNo().equals(businessPaymentOrderNo(fact.wxOutTradeNo()))
                || !java.util.Objects.equals(fact.intentRequestHash(),intents.frozenRequestHash(fact.mchid(),fact.wxOutTradeNo())))
            throw new WechatVerifiedPaymentFactService.ConflictException();
        var rows=intents.frozenFacts(fact.mchid(),fact.wxOutTradeNo());
        if (rows.isEmpty() && fact.intentRequestHash()==null) return; // Actual previously verified historical provider order.
        if (rows.size()!=1) throw new WechatVerifiedPaymentFactService.ConflictException();
        var row=rows.get(0);
        if (!fact.appid().equals(row.get("appid")) || !fact.tradeType().equals(row.get("trade_type"))
                || !fact.currency().equals(row.get("currency")) || !(row.get("total_fen") instanceof Number total)
                || total.longValue()!=fact.totalFen()) throw new WechatVerifiedPaymentFactService.ConflictException();
    }
    private void rejectPaymentNotification(Transaction transaction) {
        verifiedFacts.recordRejectedPaymentNotification(transaction==null?new String[]{null}:new String[]{
                transaction.getMchid(),transaction.getAppid(),transaction.getOutTradeNo(),transaction.getTransactionId(),
                transaction.getTradeState()==null?null:transaction.getTradeState().name(),
                transaction.getTradeType()==null?null:transaction.getTradeType().name(),
                transaction.getAmount()==null?null:String.valueOf(transaction.getAmount().getTotal()),
                transaction.getAmount()==null?null:transaction.getAmount().getCurrency(),transaction.getSuccessTime()});
    }

}
