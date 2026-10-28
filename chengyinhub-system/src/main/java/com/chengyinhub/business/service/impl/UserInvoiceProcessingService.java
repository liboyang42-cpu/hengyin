package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.UserInvoiceMapper;
import com.chengyinhub.business.mapper.UserInvoiceProcessingMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.invoice.UserInvoiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Scoped manual workbench. Records evidence; NEVER calls an external invoice provider. */
@Service
public class UserInvoiceProcessingService implements com.chengyinhub.business.service.IUserInvoiceRefundFactConsumer {
    private final UserInvoiceMapper invoices;
    private final UserInvoiceProcessingMapper processing;
    private final IRefundService refunds;
    private final PiiCryptoService crypto;

    public UserInvoiceProcessingService(UserInvoiceMapper invoices, UserInvoiceProcessingMapper processing,
                                       IRefundService refunds, PiiCryptoService crypto) {
        this.invoices = invoices; this.processing = processing; this.refunds = refunds; this.crypto = crypto;
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Command(String requestId, Long version, String action, String operationId,
                          String invoiceNo, String evidenceRef, String outcome) { }

    @Transactional
    public List<Map<String,Object>> list(Long actor, Long before) {
        requireActor(actor);
        List<Map<String,Object>> out = new ArrayList<>();
        for (Long id : processing.listScoped(actor, before)) {
            Map<String,Object> row=detail(actor,id);
            row.remove("evidenceRef"); row.remove("orders");
            out.add(row);
        }
        return out;
    }

    @Transactional
    public Map<String,Object> detail(Long actor, Long id) {
        requireActor(actor);
        Map<String,Object> scope = processing.scopeRead(id,actor);
        if(scope==null) throw denied();
        UserInvoiceApplication app = invoices.selectApplicationById(id);
        if (app == null) throw denied();
        app.setOrders(invoices.selectOrderLines(id));
        return view(app, scope);
    }

    /** Successful transaction commits the audit BEFORE the tax number is returned to the controller.
     * No @Log: general request/response logging must never capture this response. */
    @Transactional
    public Map<String,Object> revealTaxNumber(Long actor, Long id, String requestId) {
        requireRequest(requestId);
        Map<String,Object> scope = requireScope(actor, id);
        requireHandler(scope);
        UserInvoiceApplication app = invoices.selectApplicationById(id);
        if (app == null) throw denied();
        if (!crypto.isEnabled()) throw conflict("税号解密服务不可用");
        String fp = fingerprint("TAX_VIEW|" + id);
        Map<String,Object> existing = processing.receipt(id, actor, requestId);
        if (existing == null) processing.audit(id, actor, requestId, fp, "TAX_VIEW", app.getStatus(), number(scope,"version"), null);
        else if (!fp.equals(existing.get("fingerprint"))) throw reused();
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("id", id.toString()); out.put("requestId", requestId); out.put("taxNo", app.getTaxNo());
        return out;
    }

    @Transactional
    public Map<String,Object> receipt(Long actor, Long id, String requestId) {
        requireRequest(requestId); requireScope(actor, id);
        Map<String,Object> receipt = processing.receipt(id, actor, requestId);
        if (receipt == null) throw new UserInvoiceException(UserInvoiceException.NOT_FOUND, "未查到回执，不代表未执行，请保留原请求");
        return receiptView(id, requestId, receipt);
    }

    /** Explicit cancellation of an UNRECEIPTED command identity, not cancellation of an invoice.
     * The same application lock serializes this tombstone against a delayed command commit. */
    @Transactional
    public Map<String,Object> closeUnconfirmed(Long actor, Long id, String requestId, String action) {
        requireActor(actor); requireRequest(requestId);
        if (action==null || !Set.of("CLAIM","START","UNKNOWN","RECORD_ISSUED","REJECT","RECONCILE").contains(action)) throw conflict("处理动作不合法");
        UserInvoiceApplication app=processing.lockApplication(id);
        Map<String,Object> scope=requireScope(actor,id);
        if (app==null) throw denied();
        if ("RECONCILE".equals(action)) requireReconciler(actor,scope); else requireHandler(scope);
        Map<String,Object> receipt=processing.receipt(id,actor,requestId);
        if(receipt!=null) {
            if(!action.equals(receipt.get("action"))) throw reused();
            return receiptView(id,requestId,receipt);
        }
        long version=number(scope,"version");
        processing.audit(id,actor,requestId,fingerprint("FENCED|"+action),action,"FENCED",version,null);
        return receiptView(id,requestId,Map.of("action",action,"result_status","FENCED","result_version",version));
    }

    @Transactional
    public Map<String,Object> command(Long actor, Long id, Command c) {
        requireActor(actor); validate(c);
        // All advancing writes use registration -> refund history -> application -> processing.
        // Never acquire an application/processing lock and then wait for a registration lock.
        UserInvoiceApplication observed = invoices.selectApplicationById(id);
        if (observed == null) throw denied();
        boolean refund = lockOrdersAndReadRefunds(invoices.selectOrderLines(id));
        UserInvoiceApplication app = processing.lockApplication(id);
        Map<String,Object> state = processing.lockProcessing(id);
        Map<String,Object> scope = requireScope(actor,id);
        if (app == null || state == null) throw denied();
        boolean reconcile = "RECONCILE".equals(c.action());
        if (reconcile) requireReconciler(actor,scope); else requireHandler(scope);
        if(app.getTaxNo()!=null && !app.getTaxNo().isEmpty()
                && ((c.evidenceRef()!=null && c.evidenceRef().contains(app.getTaxNo()))
                    || (c.invoiceNo()!=null && c.invoiceNo().contains(app.getTaxNo())))) {
            throw conflict("票据号码或证据编号不得包含税号");
        }
        String fp = fingerprint(frame(c.action())+frame(Long.toString(c.version()))+frame(c.operationId())
                +frame(c.invoiceNo())+frame(c.evidenceRef())+frame(c.outcome()));
        Map<String,Object> old = processing.receipt(id,actor,c.requestId());
        if (old != null) {
            if (!fp.equals(old.get("fingerprint"))) throw reused();
            return receiptView(id,c.requestId(),old);
        }
        long version = number(state,"version");
        String status = app.getStatus(), next = status;
        Long handler = nullableLong(state.get("handler_id"));
        String operation = string(state.get("operation_id"));
        String reconciliation = string(state.get("reconciliation_status"));
        String evidence = string(state.get("evidence_ref"));
        String invoiceNo = null, reason = null;
        boolean release = false;
        try {
        if (version != c.version()) throw conflict("申请已变化，请回读后确认新操作");
        if (Set.of("CANCELLED","REJECTED").contains(status)) throw conflict("申请已结束");
        // Refund history is authoritative even if the post-commit notification is delayed/lost.
        // An externally issued invoice is a fact: recording it keeps ISSUED and its number, flagged for tax review.
        boolean recordIssued = "RECORD_ISSUED".equals(c.action()) && Set.of("PROCESSING","ISSUE_UNKNOWN").contains(status);
        if (refund && !recordIssued && !Set.of("PAUSED","ISSUED").contains(status)) {
            next="PAUSED"; reconciliation="REQUIRED";
        } else if ("CLAIM".equals(c.action())) {
            if (!"SUBMITTED".equals(status)) throw conflict("仅待处理申请可以认领");
            handler=actor; next="CLAIMED";
        } else if ("START".equals(c.action())) {
            owns(actor,handler);
            if (!"CLAIMED".equals(status) || operation != null) throw conflict("不能重复开始外部处理");
            operation=id+"_"+c.requestId(); next="PROCESSING";
        } else if ("UNKNOWN".equals(c.action())) {
            owns(actor,handler); sameOperation(operation,c.operationId());
            if (!Set.of("PROCESSING","ISSUE_UNKNOWN").contains(status)) throw conflict("当前状态不可标记未知");
            next="ISSUE_UNKNOWN";
        } else if ("RECORD_ISSUED".equals(c.action())) {
            owns(actor,handler); sameOperation(operation,c.operationId());
            if (!Set.of("PROCESSING","ISSUE_UNKNOWN").contains(status)) throw conflict("请先核对退款和外部处理结果");
            requireEvidence(c.evidenceRef());
            if (c.invoiceNo()==null || !c.invoiceNo().matches("[A-Za-z0-9-]{1,64}")) throw conflict("请填写已核验的发票号码");
            invoiceNo=c.invoiceNo(); evidence=c.evidenceRef(); next="ISSUED";
            if (refund) reconciliation="TAX_REVIEW_REQUIRED";
        } else if ("REJECT".equals(c.action())) {
            if (!Set.of("SUBMITTED","CLAIMED").contains(status) || operation!=null) throw conflict("外部处理开始后不能直接驳回");
            if (handler!=null) owns(actor,handler);
            requireEvidence(c.evidenceRef()); evidence=c.evidenceRef();
            // A controlled reason avoids tax numbers being copied into member messages/audit.
            reason="申请资料核对未通过"; next="REJECTED"; release=true;
        } else if (reconcile) {
            if (!"PAUSED".equals(status)) throw conflict("仅暂停申请可人工核对");
            if (operation!=null) sameOperation(operation,c.operationId());
            requireEvidence(c.evidenceRef()); evidence=c.evidenceRef();
            if ("NO_EXTERNAL_ISSUE".equals(c.outcome()) && app.getInvoiceNo()==null) {
                next="REJECTED"; reconciliation="NO_ISSUE_CONFIRMED";
                reason="退款核对确认未开具，申请已关闭"; release=true;
            } else if ("EXTERNAL_ISSUED".equals(c.outcome())) {
                // Red/partial tax policy is not authorized. Keep paused and occupied.
                reconciliation="TAX_REVIEW_REQUIRED";
            } else if ("UNRESOLVED".equals(c.outcome())) reconciliation="REQUIRED";
            else throw conflict("核对结果不合法或已有开具事实");
        } else throw conflict("不支持的处理动作");
        } catch (UserInvoiceException rejected) {
            // An explicit, committed negative receipt is distinct from an absent query result.
            processing.audit(id,actor,c.requestId(),fp,c.action(),"DECLINED",version,null);
            return receiptView(id,c.requestId(),Map.of("action",c.action(),"result_status","DECLINED","result_version",version));
        }
        if ("ISSUED".equals(next)) {
            try {
                if(processing.reserveIssuedReference(id,nullableLong(state.get("issuer_id")),invoiceNo)!=1) throw new IllegalStateException("Invoice reference not recorded");
            } catch(org.springframework.dao.DuplicateKeyException duplicate) {
                processing.audit(id,actor,c.requestId(),fp,c.action(),"DECLINED",version,null);
                return receiptView(id,c.requestId(),Map.of("action",c.action(),"result_status","DECLINED","result_version",version));
            }
        }
        if (processing.transition(id,status,next,invoiceNo,reason)!=1
                || processing.advance(id,version,handler,operation,reconciliation,evidence)!=1) throw conflict("状态竞争，请回读");
        if (release) invoices.releaseOrderLines(id);
        processing.audit(id,actor,c.requestId(),fp,c.action(),next,version+1,c.evidenceRef());
        return receiptView(id,c.requestId(),Map.of("action",c.action(),"result_status",next,"result_version",version+1));
    }

    /** Invoice-owned scanner/manual recovery consumer. Does not gate or veto refunds; reads facts itself.
     * Invoke outside the refund transaction, never under a refund-history lock. */
    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, timeout = 10)
    public void acceptRegistrationRefundFact(Long registrationId) {
        CmsRegistration seed = invoices.selectInvoiceRegistrationById(registrationId);
        if (seed == null) return;
        List<CmsRegistration> rows = lockGroup(seed);
        boolean refund = refundFacts(rows);
        if (!refund) return;
        List<Long> ids = rows.stream().map(CmsRegistration::getId).toList();
        for (Long id : processing.affectedApplications(ids)) {
            UserInvoiceApplication app=processing.lockApplication(id);
            Map<String,Object> state=processing.lockProcessing(id);
            if (state==null || Set.of("CANCELLED","REJECTED","PAUSED").contains(app.getStatus())) continue;
            long version=number(state,"version");
            if ("ISSUED".equals(app.getStatus())) {
                // Issued invoices keep status and number; a later refund only flags tax review (red-invoice policy is manual).
                if ("TAX_REVIEW_REQUIRED".equals(string(state.get("reconciliation_status")))) continue;
                if (processing.advance(id,version,nullableLong(state.get("handler_id")),string(state.get("operation_id")),"TAX_REVIEW_REQUIRED",string(state.get("evidence_ref")))!=1) throw conflict("核对状态竞争");
                processing.audit(id,0L,"refund_review_"+(version+1),fingerprint("REFUND|"+registrationId),"REFUND_TAX_REVIEW","ISSUED",version+1,null);
                continue;
            }
            if (processing.transition(id,app.getStatus(),"PAUSED",null,null)!=1
                    || processing.advance(id,version,nullableLong(state.get("handler_id")),string(state.get("operation_id")),"REQUIRED",string(state.get("evidence_ref")))!=1) throw conflict("暂停状态竞争");
            processing.audit(id,0L,"refund_pause_"+(version+1),fingerprint("REFUND|"+registrationId),"REFUND_PAUSE","PAUSED",version+1,null);
        }
    }

    private boolean lockOrdersAndReadRefunds(List<UserInvoiceOrderLine> lines) {
        if (lines==null || lines.isEmpty()) throw conflict("申请订单无法确认");
        TreeMap<Long,CmsRegistration> rows = new TreeMap<>();
        for (UserInvoiceOrderLine line : lines.stream().sorted(Comparator.comparing(UserInvoiceOrderLine::getOrderId)).toList()) {
            CmsRegistration seed=invoices.selectInvoiceRegistrationById(line.getOrderId());
            if (seed==null) throw conflict("申请订单不存在");
            if (parent(seed)) {
                List<CmsRegistration> group=lockGroup(seed);
                if (group.isEmpty() || !seed.getId().equals(group.get(0).getId())) throw conflict("历史从单申请需要独立核查");
                for(CmsRegistration row:group) rows.put(row.getId(),row);
            } else {
                for (CmsRegistration row : lockGroup(seed)) rows.put(row.getId(),row);
            }
        }
        return refundFacts(new ArrayList<>(rows.values()));
    }
    private List<CmsRegistration> lockGroup(CmsRegistration observed) {
        List<CmsRegistration> rows;
        if (parent(observed)) rows=invoices.lockInvoiceParentOrder(observed.getParentOrderNo());
        else {
            CmsRegistration locked=invoices.lockInvoiceRegistration(observed.getId());
            rows=locked==null?List.of():List.of(locked);
        }
        if (rows.isEmpty() || rows.stream().noneMatch(r->observed.getId().equals(r.getId())
                && Objects.equals(r.getParentOrderNo(),observed.getParentOrderNo()))) throw conflict("原订单归属发生变化");
        return rows;
    }
    private boolean refundFacts(List<CmsRegistration> rows) {
        boolean found=false;
        for(CmsRegistration row:rows) {
            if (!Integer.valueOf(2).equals(row.getPaymentStatus()) || !Integer.valueOf(2).equals(row.getRegistrationStatus())) found=true;
            List<RefundApplication> history=refunds.selectRegistrationHistoryForUpdate(row.getId());
            if (history==null) throw conflict("退款事实无法确认");
            if(history.stream().anyMatch(r->r==null || !Integer.valueOf(2).equals(r.getStatus()))) found=true;
        }
        return found;
    }
    private Map<String,Object> requireScope(Long actor,Long id) {
        requireActor(actor);
        Map<String,Object> scope=processing.scope(id,actor);
        if(scope==null) throw denied();
        return scope;
    }
    private static void requireHandler(Map<String,Object> s) { if(number(s,"can_handle")!=1) throw denied(); }
    private static void requireReconciler(Long a,Map<String,Object> s) {
        if(number(s,"can_reconcile")!=1 || !a.equals(nullableLong(s.get("reconciliation_user_id")))) throw denied();
    }
    private static Map<String,Object> view(UserInvoiceApplication app,Map<String,Object> s) {
        Map<String,Object> out=UserInvoiceServiceImpl.applicationView(app);
        out.put("issuerId",String.valueOf(s.get("issuer_id"))); out.put("issuerName",s.get("issuer_name"));
        out.put("version",String.valueOf(s.get("version"))); out.put("operationId",s.get("operation_id"));
        out.put("handlerId",s.get("handler_id")==null?null:String.valueOf(s.get("handler_id")));
        out.put("reconciliationStatus",s.get("reconciliation_status")); out.put("evidenceRef",s.get("evidence_ref"));
        return out;
    }
    private static Map<String,Object> receiptView(Long id,String request,Map<String,Object> r) {
        return Map.of("id",id.toString(),"requestId",request,"action",r.get("action"),
                "status",r.get("result_status"),"version",r.get("result_version").toString());
    }
    private String fingerprint(String value) { if(!crypto.isEnabled()) throw conflict("审计服务不可用"); return crypto.blindIndex("invoice-processing:v1:"+value); }
    private static String frame(String s) { return s==null?"-1:":s.length()+":"+s; }
    private static String string(Object v) {return v==null?null:v.toString();}
    private static long number(Map<String,Object> m,String k) {Object v=m.get(k);return v==null?0:Long.parseLong(v.toString());}
    private static Long nullableLong(Object v) {return v==null?null:Long.valueOf(v.toString());}
    private static boolean parent(CmsRegistration r) {return r.getParentOrderNo()!=null&&!r.getParentOrderNo().isEmpty();}
    private static void requireActor(Long a) {if(a==null||a<=0) throw denied();}
    private static void owns(Long a,Long h) {if(!a.equals(h)) throw denied();}
    private static void sameOperation(String a,String b) {if(a==null||!a.equals(b)) throw conflict("必须使用原处理号");}
    private static void requireRequest(String r) {if(r==null||!r.matches("[A-Za-z0-9_-]{1,64}")) throw conflict("请求编号不合法");}
    private static void requireEvidence(String r) {if(r==null||!r.matches("[A-Za-z0-9_:/.-]{1,128}")) throw conflict("请填写受控核对记录编号，不提交税号或票据正文");}
    private static void validate(Command c) {
        if(c==null||c.version()==null||c.version()<0||!Set.of("CLAIM","START","UNKNOWN","RECORD_ISSUED","REJECT","RECONCILE").contains(c.action()==null?"":c.action())) throw conflict("处理参数不合法");
        requireRequest(c.requestId());
        if(c.operationId()!=null && !c.operationId().matches("[A-Za-z0-9_-]{1,96}")) throw conflict("原处理号不合法");
        if(c.evidenceRef()!=null) requireEvidence(c.evidenceRef());
    }
    private static UserInvoiceException denied() {return new UserInvoiceException(UserInvoiceException.NOT_FOUND,"申请不可见或主体、处理人、核对负责人未配置");}
    private static UserInvoiceException conflict(String text) {return new UserInvoiceException(UserInvoiceException.STATE_CONFLICT,text);}
    private static UserInvoiceException reused() {return new UserInvoiceException(UserInvoiceException.REQUEST_REUSED,"原请求已用于不同处理内容");}
}
