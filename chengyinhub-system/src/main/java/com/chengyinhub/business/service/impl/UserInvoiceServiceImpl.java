package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UserInvoiceApplication;
import com.chengyinhub.business.domain.UserInvoiceOrderLine;
import com.chengyinhub.business.domain.UserInvoiceTitle;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.UserInvoiceMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.IUserInvoiceService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.invoice.InvoiceIssuer;
import com.chengyinhub.business.service.invoice.UserInvoiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * DOC2 F7 用户发票实现。
 *
 * <p>★ 一单不得重复开票：可开票查询排除已占用订单 + 申请事务内 FOR UPDATE 锁订单行 +
 * user_invoice_application_order 唯一键 (order_source, order_id, active_key) 兜底，三层任一命中都拒绝。
 * <p>★ 幂等：(member_id, request_id) 唯一；重放只认同一订单集合。
 * <p>★ 隐私：抬头名称/税号落密文（缺密钥 fail-closed），回读税号恒掩码。
 */
@Service
public class UserInvoiceServiceImpl implements IUserInvoiceService {
    static final int MAX_TITLES = 20;
    static final int MAX_ORDERS_PER_APPLICATION = 50;
    static final int MAX_TITLE_NAME = 100;
    static final int INVOICEABLE_LIST_LIMIT = 100;
    /** 统一社会信用代码 18 位，存量纳税人识别号 15/17/20 位；只收大写字母与数字。 */
    private static final Pattern TAX_NO = Pattern.compile("^[0-9A-Z]{15,20}$");
    private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserInvoiceMapper mapper;
    private final PiiCryptoService cryptoService;
    private final InvoiceIssuer issuer;
    private final IRefundService refundService;

    public UserInvoiceServiceImpl(UserInvoiceMapper mapper, PiiCryptoService cryptoService, InvoiceIssuer issuer, IRefundService refundService) {
        this.mapper = mapper;
        this.cryptoService = cryptoService;
        this.issuer = issuer;
        this.refundService = refundService;
    }

    @Override
    public List<Map<String, Object>> listTitles(Long memberId) {
        requireMember(memberId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserInvoiceTitle title : mapper.selectTitlesByMember(memberId)) out.add(titleView(title));
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveTitle(Long memberId, UserInvoiceTitle input) {
        requireMember(memberId);
        if (input == null) throw new UserInvoiceException(UserInvoiceException.ARGUMENT_INVALID, "抬头信息不能为空");
        UserInvoiceTitle draft = normalizeTitle(input);
        requireCrypto();
        Date now = new Date();
        boolean makeDefault = input.getIsDefault() != null && input.getIsDefault() == 1;
        draft.setMemberId(memberId);
        draft.setIsDefault(makeDefault ? 1 : 0);
        if (input.getId() == null) {
            if (mapper.countTitles(memberId) >= MAX_TITLES) {
                throw new UserInvoiceException(UserInvoiceException.TITLE_LIMIT, "最多保存 " + MAX_TITLES + " 个抬头");
            }
            draft.setCreateTime(now);
            mapper.insertTitle(draft);
        } else {
            if (mapper.selectTitle(memberId, input.getId()) == null) {
                throw new UserInvoiceException(UserInvoiceException.TITLE_NOT_FOUND, "抬头不存在");
            }
            draft.setId(input.getId());
            draft.setUpdateTime(now);
            if (mapper.updateTitle(draft) != 1) {
                throw new UserInvoiceException(UserInvoiceException.TITLE_NOT_FOUND, "抬头不存在");
            }
        }
        if (makeDefault) mapper.clearDefaultTitles(memberId, draft.getId());
        UserInvoiceTitle readback = mapper.selectTitle(memberId, draft.getId());
        if (readback == null) throw new UserInvoiceException(UserInvoiceException.TITLE_NOT_FOUND, "抬头保存失败");
        return titleView(readback);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveTitleWithRequest(Long memberId, String requestId, UserInvoiceTitle input) {
        requireMember(memberId);
        requireRequestId(requestId);
        if (input == null) throw new UserInvoiceException(UserInvoiceException.ARGUMENT_INVALID, "抬头信息不能为空");
        UserInvoiceTitle normalized = normalizeTitle(input);
        requireCrypto();
        // Length framing prevents field-boundary collisions; HMAC prevents offline tax-number guessing.
        String payload = "invoice-title-save:v1:" + framed(String.valueOf(memberId))
                + framed(input.getId() == null ? "" : input.getId().toString())
                + framed(normalized.getTitleType()) + framed(normalized.getTitleName())
                + framed(normalized.getTaxNo()) + framed(Integer.valueOf(1).equals(input.getIsDefault()) ? "1" : "0");
        String fingerprint = cryptoService.blindIndex(payload);
        mapper.reserveTitleReceipt(memberId, requestId, fingerprint);
        Map<String, Object> receipt = mapper.lockTitleReceipt(memberId, requestId);
        if (receipt == null || !fingerprint.equals(receipt.get("fingerprint"))) {
            throw new UserInvoiceException(UserInvoiceException.REQUEST_REUSED, "请求标识已用于另一份抬头输入");
        }
        if (receipt.get("titleId") != null) return titleReceiptView(receipt);
        // Self-call participates in this outer transaction, including default-title updates.
        Map<String, Object> saved = saveTitle(memberId, input);
        Long titleId = Long.valueOf(saved.get("id").toString());
        if (mapper.finishTitleReceipt(memberId, requestId, titleId) != 1) {
            throw new UserInvoiceException(UserInvoiceException.STATE_CONFLICT, "抬头保存回执无法确认");
        }
        return Map.of("requestId", requestId, "titleId", titleId.toString(), "status", "SAVED");
    }

    @Override
    public Map<String, Object> titleSaveReceipt(Long memberId, String requestId) {
        requireMember(memberId);
        requireRequestId(requestId);
        Map<String, Object> receipt = mapper.selectTitleReceipt(memberId, requestId);
        if (receipt == null) throw new UserInvoiceException(UserInvoiceException.NOT_FOUND, "暂未查到原保存回执");
        return titleReceiptView(receipt);
    }

    private static void requireRequestId(String requestId) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw new UserInvoiceException(UserInvoiceException.ARGUMENT_INVALID, "请求标识不合法");
        }
    }

    private static String framed(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }

    private static Map<String, Object> titleReceiptView(Map<String, Object> receipt) {
        if (receipt.get("titleId") == null) throw new UserInvoiceException(UserInvoiceException.REQUEST_IN_PROGRESS, "原保存结果待确认");
        return Map.of("requestId", receipt.get("requestId"), "titleId", receipt.get("titleId").toString(), "status", "SAVED");
    }

    @Override
    public void deleteTitle(Long memberId, Long titleId) {
        requireMember(memberId);
        if (titleId == null || mapper.deleteTitle(memberId, titleId) != 1) {
            throw new UserInvoiceException(UserInvoiceException.TITLE_NOT_FOUND, "抬头不存在");
        }
    }

    @Override
    public List<UserInvoiceOrderLine> listInvoiceableOrders(Long memberId) {
        requireMember(memberId);
        List<UserInvoiceOrderLine> rows = mapper.selectInvoiceableRegistrations(memberId, null, INVOICEABLE_LIST_LIMIT);
        if (!rows.isEmpty()) {
            Map<String,Map<String,Object>> byOrder = new java.util.HashMap<>();
            for (Map<String,Object> preview : mapper.selectInvoiceIssuerPreviews(rows.stream().map(UserInvoiceOrderLine::getOrderId).toList())) {
                byOrder.put(String.valueOf(preview.get("order_id")), preview);
            }
            for (UserInvoiceOrderLine row : rows) {
                Map<String,Object> preview=byOrder.get(row.getOrderId().toString());
                if (preview!=null) {
                    row.setIssuerId(Long.valueOf(preview.get("issuer_id").toString()));
                    row.setIssuerName(String.valueOf(preview.get("issuer_name")));
                }
            }
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> apply(Long memberId, String requestId, Long titleId, List<Long> orderIds) {
        requireMember(memberId);
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches() || titleId == null) {
            throw new UserInvoiceException(UserInvoiceException.ARGUMENT_INVALID, "申请参数不合法");
        }
        List<Long> ids = distinctOrderIds(orderIds);

        UserInvoiceApplication replay = mapper.selectApplicationByRequest(memberId, requestId);
        if (replay != null) return replayView(replay, titleId, ids);

        requireCrypto();
        UserInvoiceTitle title = mapper.selectTitle(memberId, titleId);
        if (title == null) throw new UserInvoiceException(UserInvoiceException.TITLE_NOT_FOUND, "抬头不存在");

        lockAndCheckRegistrationRefunds(memberId, ids);
        List<UserInvoiceOrderLine> orders = mapper.selectInvoiceableRegistrationsForUpdate(memberId, ids);
        if (orders.size() != ids.size()) {
            List<Long> occupied = mapper.selectOccupiedOrderIds(memberId, UserInvoiceOrderLine.SOURCE_REGISTRATION, ids);
            if (occupied != null && !occupied.isEmpty()) {
                throw new UserInvoiceException(UserInvoiceException.ORDER_ALREADY_INVOICED, "所选订单已申请过发票");
            }
            throw new UserInvoiceException(UserInvoiceException.ORDER_NOT_ELIGIBLE, "所选订单不可开票（未支付、已退款或不属于本人）");
        }

        List<Long> issuerIds = mapper.resolveInvoiceIssuers(ids);
        if (issuerIds == null || issuerIds.size() != ids.size()) {
            throw new UserInvoiceException(UserInvoiceException.ORDER_NOT_ELIGIBLE, "开票主体、处理人或核对负责人未配置");
        }
        if (new HashSet<>(issuerIds).size() != 1) {
            throw new UserInvoiceException(UserInvoiceException.ORDER_NOT_ELIGIBLE, "不同开票主体的订单须分开申请");
        }
        Date now = new Date();
        BigDecimal total = BigDecimal.ZERO;
        for (UserInvoiceOrderLine order : orders) total = total.add(order.getAmount());

        UserInvoiceApplication app = new UserInvoiceApplication();
        app.setApplicationNo(newApplicationNo(now));
        app.setMemberId(memberId);
        app.setRequestId(requestId);
        app.setTitleId(title.getId());
        app.setTitleType(title.getTitleType());
        app.setTitleName(title.getTitleName());
        app.setTaxNo(title.getTaxNo());
        app.setTotalAmount(total);
        app.setOrderCount(orders.size());
        app.setStatus(UserInvoiceApplication.STATUS_SUBMITTED);
        app.setProvider(issuer.name());
        app.setCreateTime(now);
        try {
            mapper.insertApplication(app);
            if (mapper.insertProcessing(app.getId(), issuerIds.get(0)) != 1) throw new IllegalStateException("Invoice issuer snapshot was not inserted");
        } catch (DuplicateKeyException e) {
            // 同一 requestId 的并发请求：另一条已在落库，客户端用同一 requestId 重试即得重放结果。
            throw new UserInvoiceException(UserInvoiceException.REQUEST_IN_PROGRESS, "申请正在处理，请稍后刷新");
        }

        List<UserInvoiceOrderLine> lines = new ArrayList<>();
        for (UserInvoiceOrderLine order : orders) {
            UserInvoiceOrderLine line = new UserInvoiceOrderLine();
            line.setApplicationId(app.getId());
            line.setMemberId(memberId);
            line.setOrderSource(UserInvoiceOrderLine.SOURCE_REGISTRATION);
            line.setOrderId(order.getOrderId());
            line.setAmount(order.getAmount());
            line.setCreateTime(now);
            lines.add(line);
        }
        try {
            mapper.insertOrderLines(lines);
        } catch (DuplicateKeyException e) {
            throw new UserInvoiceException(UserInvoiceException.ORDER_ALREADY_INVOICED, "所选订单已申请过发票");
        }
        // 人工适配器不外呼；真实适配器接入时须挪到提交后异步执行（见 InvoiceIssuer 约定）。
        issuer.submit(new InvoiceIssuer.Request(app.getApplicationNo(), app.getTitleType(),
                app.getTitleName(), app.getTaxNo(), total));
        app.setOrders(orders);
        Map<String, Object> view = memberApplicationView(app);
        view.put("replayed", false);
        return view;
    }

    private void lockAndCheckRegistrationRefunds(Long memberId, List<Long> ids) {
        List<CmsRegistration> seeds = mapper.selectInvoiceRegistrationSeeds(memberId, ids);
        if (seeds == null || seeds.size() != ids.size()) throw ineligibleOrder();
        // Same lock order as refunds: all registrations of a parent in ascending ID,
        // followed by current refund-history reads. A child is never a cash carrier.
        java.util.TreeMap<Long, CmsRegistration> locked = new java.util.TreeMap<>();
        for (CmsRegistration seed : seeds.stream().sorted(java.util.Comparator.comparing(CmsRegistration::getId)).toList()) {
            boolean parent = seed.getParentOrderNo() != null && !seed.getParentOrderNo().isEmpty();
            List<CmsRegistration> rows;
            if (parent) rows = mapper.lockInvoiceParentOrder(seed.getParentOrderNo());
            else {
                CmsRegistration row = mapper.lockInvoiceRegistration(seed.getId());
                rows = row == null ? List.of() : List.of(row);
            }
            if (rows == null || rows.isEmpty() || !seed.getId().equals(rows.get(0).getId())) throw ineligibleOrder();
            for (CmsRegistration row : rows) {
                if (!memberId.equals(row.getMemberId())
                        || !java.util.Objects.equals(seed.getParentOrderNo(), row.getParentOrderNo())
                        || !java.util.Objects.equals(seed.getOwnerType(), row.getOwnerType())
                        || !java.util.Objects.equals(seed.getOwnerId(), row.getOwnerId())
                        || !Integer.valueOf(2).equals(row.getPaymentStatus())
                        || !Integer.valueOf(2).equals(row.getRegistrationStatus())) throw ineligibleOrder();
                if (parent && (row.getOwnerType() == null || row.getOwnerId() == null || row.getPayableAmount() == null
                        || (row.getId().equals(seed.getId()) ? row.getPayableAmount().signum() <= 0 : row.getPayableAmount().signum() != 0))) {
                    throw ineligibleOrder();
                }
                locked.put(row.getId(), row);
            }
        }
        for (Long registrationId : locked.keySet()) {
            List<RefundApplication> history = refundService.selectRegistrationHistoryForUpdate(registrationId);
            if (history == null) throw ineligibleOrder();
            for (RefundApplication refund : history) {
                // Approval remains blocking even if payout failed or is still processing.
                if (refund == null || !Integer.valueOf(2).equals(refund.getStatus())) {
                    throw ineligibleOrder();
                }
            }
        }
        if (!mapper.selectActiveInvoiceOrderIdsForUpdate(new ArrayList<>(locked.keySet())).isEmpty()) {
            throw new UserInvoiceException(UserInvoiceException.ORDER_ALREADY_INVOICED, "原订单已有发票申请占用");
        }
    }

    private static UserInvoiceException ineligibleOrder() {
        return new UserInvoiceException(UserInvoiceException.ORDER_NOT_ELIGIBLE, "原订单现金或退款状态无法确认，暂不可开票");
    }

    @Override
    public List<Map<String, Object>> listApplications(Long memberId) {
        requireMember(memberId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserInvoiceApplication app : mapper.selectApplicationsByMember(memberId)) out.add(applicationView(app));
        enrichMemberReconciliation(memberId, out);
        return out;
    }

    @Override
    public Map<String, Object> applicationDetail(Long memberId, Long applicationId) {
        requireMember(memberId);
        UserInvoiceApplication app = ownedApplication(memberId, applicationId);
        app.setOrders(mapper.selectOrderLines(app.getId()));
        return memberApplicationView(app);
    }

    @Override
    public Map<String, Object> applicationByRequest(Long memberId, String requestId) {
        requireMember(memberId);
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw new UserInvoiceException(UserInvoiceException.ARGUMENT_INVALID, "请求标识不合法");
        }
        UserInvoiceApplication app = mapper.selectApplicationByRequest(memberId, requestId);
        if (app == null) throw new UserInvoiceException(UserInvoiceException.NOT_FOUND, "暂未查到原申请");
        app.setOrders(mapper.selectOrderLines(app.getId()));
        return memberApplicationView(app);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancel(Long memberId, Long applicationId) {
        requireMember(memberId);
        UserInvoiceApplication app = ownedApplication(memberId, applicationId);
        if (mapper.cancelApplication(memberId, app.getId(), new Date()) != 1) {
            throw new UserInvoiceException(UserInvoiceException.STATE_CONFLICT, "该申请已处理，不能撤回");
        }
        mapper.releaseOrderLines(app.getId());
        return applicationDetail(memberId, app.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserInvoiceApplication markIssued(Long applicationId, String invoiceNo) {
        throw new UserInvoiceException(UserInvoiceException.STATE_CONFLICT, "请使用受主体授权的发票处理入口");
    }

    @Override
    public UserInvoiceApplication markRejected(Long applicationId, String reason) {
        throw new UserInvoiceException(UserInvoiceException.STATE_CONFLICT, "请使用受主体授权的发票处理入口");
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    private Map<String, Object> replayView(UserInvoiceApplication replay, Long titleId, List<Long> ids) {
        List<UserInvoiceOrderLine> lines = mapper.selectOrderLines(replay.getId());
        Set<Long> recorded = new HashSet<>();
        for (UserInvoiceOrderLine line : lines) recorded.add(line.getOrderId());
        if (!java.util.Objects.equals(titleId, replay.getTitleId()) || !recorded.equals(new HashSet<>(ids))) {
            throw new UserInvoiceException(UserInvoiceException.REQUEST_REUSED, "请求标识已用于另一抬头或订单组合");
        }
        replay.setOrders(lines);
        Map<String, Object> view = memberApplicationView(replay);
        view.put("replayed", true);
        return view;
    }

    private UserInvoiceApplication ownedApplication(Long memberId, Long applicationId) {
        UserInvoiceApplication app = applicationId == null ? null : mapper.selectApplication(memberId, applicationId);
        if (app == null) throw new UserInvoiceException(UserInvoiceException.NOT_FOUND, "发票申请不存在");
        return app;
    }

    private UserInvoiceException stateConflictOrMissing(Long applicationId) {
        return mapper.selectApplicationById(applicationId) == null
                ? new UserInvoiceException(UserInvoiceException.NOT_FOUND, "发票申请不存在")
                : new UserInvoiceException(UserInvoiceException.STATE_CONFLICT, "该申请已处理");
    }

    private static List<Long> distinctOrderIds(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            throw new UserInvoiceException(UserInvoiceException.ORDERS_REQUIRED, "请选择要开票的订单");
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : orderIds) {
            if (id == null || id <= 0) throw new UserInvoiceException(UserInvoiceException.ARGUMENT_INVALID, "订单不合法");
            ids.add(id);
        }
        if (ids.size() > MAX_ORDERS_PER_APPLICATION) {
            throw new UserInvoiceException(UserInvoiceException.ORDERS_TOO_MANY,
                    "一次最多合并 " + MAX_ORDERS_PER_APPLICATION + " 笔订单");
        }
        return new ArrayList<>(ids);
    }

    static UserInvoiceTitle normalizeTitle(UserInvoiceTitle input) {
        String type = trim(input.getTitleType());
        if (!UserInvoiceTitle.TYPE_PERSONAL.equals(type) && !UserInvoiceTitle.TYPE_COMPANY.equals(type)) {
            throw new UserInvoiceException(UserInvoiceException.TITLE_TYPE_INVALID, "抬头类型不合法");
        }
        String name = trim(input.getTitleName());
        if (name == null || name.length() > MAX_TITLE_NAME) {
            throw new UserInvoiceException(UserInvoiceException.TITLE_NAME_INVALID, "抬头名称不合法");
        }
        String taxNo = trim(input.getTaxNo());
        if (UserInvoiceTitle.TYPE_COMPANY.equals(type)) {
            taxNo = taxNo == null ? null : taxNo.toUpperCase();
            if (taxNo == null || !TAX_NO.matcher(taxNo).matches()) {
                throw new UserInvoiceException(UserInvoiceException.TAX_NO_INVALID, "纳税人识别号不合法");
            }
        } else if (taxNo != null) {
            // 个人抬头不收税号：多收一项就多一份要保护的数据。
            throw new UserInvoiceException(UserInvoiceException.TAX_NO_INVALID, "个人抬头无需填写税号");
        }
        UserInvoiceTitle out = new UserInvoiceTitle();
        out.setTitleType(type);
        out.setTitleName(name);
        out.setTaxNo(taxNo);
        return out;
    }

    static Map<String, Object> titleView(UserInvoiceTitle title) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", title.getId() == null ? null : title.getId().toString());
        view.put("titleType", title.getTitleType());
        view.put("titleName", title.getTitleName());
        view.put("taxNoMasked", maskTaxNo(title.getTaxNo()));
        view.put("isDefault", title.getIsDefault() != null && title.getIsDefault() == 1);
        return view;
    }

    private Map<String, Object> memberApplicationView(UserInvoiceApplication app) {
        Map<String, Object> view = applicationView(app);
        enrichMemberReconciliation(app.getMemberId(), List.of(view));
        return view;
    }

    private void enrichMemberReconciliation(Long memberId, List<Map<String, Object>> views) {
        if (views.isEmpty()) return;
        List<Long> ids = new ArrayList<>();
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> view : views) {
            String id = String.valueOf(view.get("id"));
            ids.add(Long.valueOf(id));
            byId.put(id, view);
        }
        List<Map<String, Object>> rows = mapper.selectMemberReconciliation(memberId, ids);
        if (rows == null) return;
        for (Map<String, Object> row : rows) {
            Map<String, Object> view = byId.get(String.valueOf(row.get("applicationId")));
            Object status = row.get("reconciliationStatus");
            if (view != null && ("REQUIRED".equals(status) || "NO_ISSUE_CONFIRMED".equals(status)
                    || "TAX_REVIEW_REQUIRED".equals(status))) view.put("reconciliationStatus", status);
        }
    }

    static Map<String, Object> applicationView(UserInvoiceApplication app) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", app.getId() == null ? null : app.getId().toString());
        view.put("requestId", app.getRequestId());
        view.put("applicationNo", app.getApplicationNo());
        view.put("status", app.getStatus());
        view.put("titleType", app.getTitleType());
        view.put("titleName", app.getTitleName());
        view.put("taxNoMasked", maskTaxNo(app.getTaxNo()));
        view.put("totalAmount", app.getTotalAmount());
        view.put("orderCount", app.getOrderCount());
        view.put("provider", app.getProvider());
        view.put("invoiceNo", app.getInvoiceNo());
        view.put("rejectReason", app.getRejectReason());
        view.put("issuedAt", app.getIssuedAt());
        view.put("createTime", app.getCreateTime());
        List<Map<String, Object>> orders = new ArrayList<>();
        for (UserInvoiceOrderLine line : app.getOrders()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("orderSource", line.getOrderSource() == null ? UserInvoiceOrderLine.SOURCE_REGISTRATION : line.getOrderSource());
            o.put("orderId", line.getOrderId() == null ? null : line.getOrderId().toString());
            o.put("amount", line.getAmount());
            o.put("orderTitle", line.getOrderTitle());
            o.put("ticketName", line.getTicketName());
            o.put("paymentTime", line.getPaymentTime());
            orders.add(o);
        }
        view.put("orders", orders);
        return view;
    }

    /** 税号掩码：保留前 2 后 4，中间固定 6 个 *，不暴露真实长度。 */
    static String maskTaxNo(String taxNo) {
        if (taxNo == null || taxNo.isEmpty()) return null;
        if (taxNo.length() <= 6) return "******";
        return taxNo.substring(0, 2) + "******" + taxNo.substring(taxNo.length() - 4);
    }

    private void requireCrypto() {
        if (cryptoService == null || !cryptoService.isEnabled()) {
            throw new UserInvoiceException(UserInvoiceException.CRYPTO_UNAVAILABLE, "发票服务暂不可用");
        }
    }

    private static void requireMember(Long memberId) {
        if (memberId == null || memberId <= 0) {
            throw new UserInvoiceException(UserInvoiceException.ARGUMENT_INVALID, "请先登录");
        }
    }

    private static String newApplicationNo(Date now) {
        return "INV" + new SimpleDateFormat("yyyyMMddHHmmss").format(now)
                + String.format("%08d", RANDOM.nextInt(100_000_000));
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
