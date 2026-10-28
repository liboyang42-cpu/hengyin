package com.chengyinhub.business.service.support;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.CommittedOfficialNoticeWx;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.mapper.CommittedOfficialNoticeWxMapper;
import com.chengyinhub.business.notify.NotifyEvent;
import com.chengyinhub.business.notify.NotifyMessage;
import com.chengyinhub.business.notify.WxSubscribeChannel;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IWxSubscribeMsgService;
import com.chengyinhub.business.domain.dto.BeforeStartNotifyResult.Delivery;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Date;
import java.util.Objects;
import java.util.Set;
import com.chengyinhub.business.service.ISystemNotificationIntentService;
import com.chengyinhub.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Durable replacement for a typed CommittedBusinessNotice official notice, never a Runnable/event bus. */
@Service
public class CommittedOfficialNoticeService {
    private static final Logger log=LoggerFactory.getLogger(CommittedOfficialNoticeService.class);
    private final CommittedOfficialNoticeWxMapper mapper;
    private final ISystemNotificationIntentService intents;
    private final IImService im;
    private final WxSubscribeChannel wx;
    private final ScheduledExecutorService executor;
    private final TransactionTemplate independent;
    @Autowired
    private IWxSubscribeMsgService beforeStartWx;
    private java.time.Clock beforeStartClock=java.time.Clock.systemUTC();

    /** Frozen payload for the existing BEFORE_START template, never a second outbox or a broadcast. */
    public record BeforeStartPayload(Long ticketId, Long startsAt, String topicName,
                                     IWxSubscribeMsgService.PreparedBeforeStart message) { }


    public CommittedOfficialNoticeService(CommittedOfficialNoticeWxMapper mapper,
            ISystemNotificationIntentService intents,IImService im,WxSubscribeChannel wx,
            PlatformTransactionManager transactions,
            @Qualifier("scheduledExecutorService") ScheduledExecutorService executor) {
        this.mapper=mapper;this.intents=intents;this.im=im;this.wx=wx;this.executor=executor;
        independent=new TransactionTemplate(transactions);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The original business winner calls inside its transaction. Returned id means submitted, never delivered. */
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public Long enqueueOfficial(String originalEventKey,Long recipient,String title,String body) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("通知原意图必须在原业务事务内登记");
        if (originalEventKey==null || !originalEventKey.matches("[A-Za-z0-9:_-]{1,96}")
                || recipient==null || recipient<=0 || title==null || body==null)
            throw new IllegalArgumentException("通知原事件或冻结内容无效");
        Long imId=intents.enqueue(recipient,ImMessage.MSG_TEXT,title+"\n"+body,null,originalEventKey);
        if(imId==null || imId<=0) throw new IllegalStateException("站内通知原意图未登记");
        CommittedOfficialNoticeWx requested=new CommittedOfficialNoticeWx();
        requested.setEventKey(originalEventKey);requested.setRecipient(recipient);
        requested.setTitle(title);requested.setBody(body);requested.setImIntentId(imId);
        requested.setRequestHash(fingerprint(requested));
        mapper.insert(requested);
        CommittedOfficialNoticeWx stored=mapper.selectByKeyForUpdate(originalEventKey);
        validate(stored);
        if(!requested.getRequestHash().equals(stored.getRequestHash()))
            throw new ServiceException("通知原事件内容冲突",409);
        Long id=stored.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { wake(id); }
        });
        return id;
    }

    /** Called only by the existing current eligible-ticket/paid-recipient sweep, without an outer transaction.
     * An existing identity is read before any profile/config preparation. It is never re-rendered or rearmed. */
    @Transactional(propagation=Propagation.NEVER)
    public Delivery beforeStart(Long ticketId, Long recipient, String topicName, Date startsAt) {
        if (com.chengyinhub.common.config.RestoreDispatchGuard.isRestoreMode())
            return readBeforeStart(ticketId, recipient);
        outsideTransaction();
        String key=beforeStartKey(ticketId,recipient);
        long planned=startsAt==null ? 0L : startsAt.getTime();
        if(planned<=0) throw new IllegalArgumentException("原计划时间无效");
        CommittedOfficialNoticeWx stored=independent.execute(tx -> mapper.selectByKeyForUpdate(key));
        if(stored==null) {
            if(!beforeStartWindow(planned)) return new Delivery(null,"OUTSIDE_WINDOW",false);
            String whenText=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(planned));
            IWxSubscribeMsgService.PreparedBeforeStart prepared=beforeStartWx.prepareBeforeStart(recipient,topicName,whenText);
            if(prepared==null) return new Delivery(null,"NOT_READY",false); // definitely no provider invocation
            BeforeStartPayload payload=new BeforeStartPayload(ticketId,planned,topicName,prepared);
            CommittedOfficialNoticeWx requested=new CommittedOfficialNoticeWx();
            requested.setNoticeType(CommittedOfficialNoticeWx.BEFORE_START);
            requested.setEventKey(key);requested.setRecipient(recipient);
            requested.setTitle(prepared.fields().get("thing1"));requested.setBody(prepared.fields().get("date5"));
            requested.setWxPayloadJson(JSON.toJSONString(payload));
            requested.setRequestHash(fingerprint(requested));
            validateBeforeStartPayload(requested);
            stored=independent.execute(tx -> {
                mapper.insert(requested);
                CommittedOfficialNoticeWx original=mapper.selectByKeyForUpdate(key);
                requireBeforeStartCommand(original,ticketId,recipient,topicName,planned);
                return original; // concurrent preparation uses the first committed original, never replaces its config
            });
        }
        requireBeforeStartCommand(stored,ticketId,recipient,topicName,planned);
        if(!CommittedOfficialNoticeWx.PENDING.equals(stored.getWxState())) return view(stored,false);
        CommittedOfficialNoticeWx claim=claim(stored.getId(),CommittedOfficialNoticeWx.BEFORE_START);
        if(claim==null) return readBeforeStart(ticketId,recipient);
        boolean newlyAccepted=sendAndFinalize(claim);
        // UNKNOWN is already committed; losing the terminal DB ack cannot release or repeat this attempt.
        return new Delivery(claim.getId(),newlyAccepted ? CommittedOfficialNoticeWx.ACCEPTED
                : CommittedOfficialNoticeWx.UNKNOWN,newlyAccepted);
    }

    /** Original-result lookup only. It cannot prepare/claim/send, including on missing rows or database errors. */
    public Delivery readBeforeStart(Long ticketId,Long recipient) {
        String key=beforeStartKey(ticketId,recipient);
        return independent.execute(tx -> {
            CommittedOfficialNoticeWx row=mapper.selectByKeyForUpdate(key);
            if(row==null) return new Delivery(null,"NOT_FOUND",false);
            validate(row);
            if(!CommittedOfficialNoticeWx.BEFORE_START.equals(row.getNoticeType()))
                throw new ServiceException("通知原事件类型冲突",409);
            return view(row,false);
        });
    }

    private static String beforeStartKey(Long ticketId,Long recipient) {
        if(ticketId==null || ticketId<=0 || recipient==null || recipient<=0)
            throw new IllegalArgumentException("原票次或收件人无效");
        return "before-start:"+ticketId+":"+recipient;
    }

    private boolean beforeStartWindow(long planned) {
        long now=beforeStartClock.millis();
        return planned>=now && planned<=now+24L*3600*1000;
    }

    private static Delivery view(CommittedOfficialNoticeWx row,boolean newlyAccepted) {
        return new Delivery(row.getId(),row.getWxState(),newlyAccepted);
    }

    private static void requireBeforeStartCommand(CommittedOfficialNoticeWx row,Long ticketId,
            Long recipient,String topicName,long planned) {
        validate(row);
        if(!CommittedOfficialNoticeWx.BEFORE_START.equals(row.getNoticeType()))
            throw new ServiceException("通知原事件类型冲突",409);
        BeforeStartPayload original=validateBeforeStartPayload(row);
        if(!ticketId.equals(original.ticketId()) || !recipient.equals(row.getRecipient())
                || !Long.valueOf(planned).equals(original.startsAt()) || !Objects.equals(topicName,original.topicName()))
            throw new ServiceException("通知原事件内容冲突",409);
    }

    private void wake(Long id) {
        if (com.chengyinhub.common.config.RestoreDispatchGuard.isRestoreMode()) return;
        try { executor.schedule(() -> {
            try { deliver(id); }
            catch(RuntimeException failure) { pending(id,failure); }
        },10,TimeUnit.MILLISECONDS); }
        catch(Throwable rejected) { pending(id,rejected); }
    }

    /** Invoked after commit or by the existing recovery task; no business connection spans a WeChat call. */
    public void deliver(Long id) {
        if (com.chengyinhub.common.config.RestoreDispatchGuard.isRestoreMode()) return;
        outsideTransaction();
        CommittedOfficialNoticeWx frozen=independent.execute(tx -> {
            CommittedOfficialNoticeWx row=mapper.selectByIdForUpdate(id);validate(row);return row;
        });
        if(frozen==null) throw new IllegalStateException("通知原意图不存在");
        if(CommittedOfficialNoticeWx.BEFORE_START.equals(frozen.getNoticeType())) return;
        try {
            Long messageId=independent.execute(tx -> im.deliverSystemNotification(frozen.getImIntentId()));
            if(messageId==null || messageId<=0) throw new IllegalStateException("站内通知未返回原消息收据");
        } catch(RuntimeException failure) { pending(id,failure); }

        CommittedOfficialNoticeWx claim=claim(id,CommittedOfficialNoticeWx.OFFICIAL_BROADCAST);
        if(claim!=null) sendAndFinalize(claim);
    }

    private CommittedOfficialNoticeWx claim(Long id,String expectedType) {
        return independent.execute(tx -> {
            CommittedOfficialNoticeWx row=mapper.selectByIdForUpdate(id);validate(row);
            if(!expectedType.equals(row.getNoticeType())) throw new ServiceException("通知原事件类型冲突",409);
            if(!CommittedOfficialNoticeWx.PENDING.equals(row.getWxState())) return null;
            // Recovery never expands the original 24-hour/future start window.
            if(CommittedOfficialNoticeWx.BEFORE_START.equals(expectedType)
                    && !beforeStartWindow(validateBeforeStartPayload(row).startsAt())) return null;
            String owner=UUID.randomUUID().toString();
            if(mapper.claimUnknown(id,owner)!=1) throw new IllegalStateException("通知尝试领取失败");
            row.setOwnerToken(owner);row.setWxState(CommittedOfficialNoticeWx.UNKNOWN);return row;
        });
    }

    private boolean sendAndFinalize(CommittedOfficialNoticeWx claim) {
        outsideTransaction();
        boolean accepted;
        try {
            if(CommittedOfficialNoticeWx.BEFORE_START.equals(claim.getNoticeType())) {
                accepted=beforeStartWx.sendPreparedBeforeStart(validateBeforeStartPayload(claim).message());
            } else {
                accepted=wx.send(NotifyMessage.of(NotifyEvent.OFFICIAL_BROADCAST,claim.getRecipient())
                        .with("title",claim.getTitle()).with("sub",claim.getBody()));
            }
        } catch(Throwable unknown) { pending(claim.getId(),unknown);return false; }
        // false includes both definitive skips and swallowed I/O failures. It cannot authorize a resend.
        if(!accepted) { log.warn("Official notice WeChat result UNKNOWN id={}",claim.getId());return false; }
        try {
            independent.executeWithoutResult(tx -> {
                if(mapper.accepted(claim.getId(),claim.getOwnerToken())!=1)
                    throw new IllegalStateException("通知尝试回执归属已变化");
            });
            return true;
        } catch(RuntimeException unknownReceipt) { pending(claim.getId(),unknownReceipt);return false; }
    }

    /** Enumerated entries, not a delivered count. Fixed high-water mark; failed entries cannot starve later pages. */
    public int reconcilePending() {
        if (com.chengyinhub.common.config.RestoreDispatchGuard.isRestoreMode()) return 0;
        outsideTransaction();
        Long maximum=mapper.maxPendingId();
        if(maximum==null) return 0;
        long after=0;int enumerated=0;
        while(after<maximum) {
            List<Long> ids=mapper.selectPendingPage(after,maximum);
            if(ids==null || ids.isEmpty()) break;
            long previous=after;
            for(Long id:ids) {
                if(id==null || id<=after || id>maximum) continue;
                after=id;enumerated++;
                try { deliver(id); } catch(RuntimeException failure) { pending(id,failure); }
            }
            if(after<=previous) throw new IllegalStateException("通知恢复游标未前进");
        }
        return enumerated;
    }

    private static String fingerprint(CommittedOfficialNoticeWx row) {
        if(CommittedOfficialNoticeWx.BEFORE_START.equals(row.getNoticeType()))
            return CanonicalDigest.sha256Hex(JSON.toJSONString(Arrays.asList(row.getNoticeType(),row.getEventKey(),
                    row.getRecipient(),row.getTitle(),row.getBody(),row.getWxPayloadJson())));
        return CanonicalDigest.sha256Hex(JSON.toJSONString(Arrays.asList(row.getEventKey(),row.getRecipient(),
                row.getImIntentId(),row.getTitle(),row.getBody())));
    }

    private static void validate(CommittedOfficialNoticeWx row) {
        if(row==null || row.getId()==null || row.getId()<=0 || row.getRecipient()==null || row.getRecipient()<=0
                || row.getTitle()==null || row.getBody()==null
                || row.getEventKey()==null || !row.getEventKey().matches("[A-Za-z0-9:_-]{1,96}")
                || !fingerprint(row).equals(row.getRequestHash()))
            throw new IllegalStateException("通知冻结事实无法核实");
        if(CommittedOfficialNoticeWx.OFFICIAL_BROADCAST.equals(row.getNoticeType())) {
            if(row.getImIntentId()==null || row.getImIntentId()<=0 || row.getWxPayloadJson()!=null)
                throw new IllegalStateException("广播原站内通知无法核实");
        } else if(CommittedOfficialNoticeWx.BEFORE_START.equals(row.getNoticeType())) {
            validateBeforeStartPayload(row);
        } else throw new IllegalStateException("通知原类型无法核实");
        if(!CommittedOfficialNoticeWx.PENDING.equals(row.getWxState())
                && !CommittedOfficialNoticeWx.UNKNOWN.equals(row.getWxState())
                && !CommittedOfficialNoticeWx.ACCEPTED.equals(row.getWxState()))
            throw new IllegalStateException("通知原状态待人工核实");
        if(CommittedOfficialNoticeWx.PENDING.equals(row.getWxState()) ? row.getOwnerToken()!=null
                : row.getOwnerToken()==null || row.getOwnerToken().isEmpty())
            throw new IllegalStateException("通知尝试归属无法核实");
    }

    private static BeforeStartPayload validateBeforeStartPayload(CommittedOfficialNoticeWx row) {
        if(row.getImIntentId()!=null || row.getWxPayloadJson()==null)
            throw new IllegalStateException("出发提醒不得冒用广播站内意图");
        BeforeStartPayload p=JSON.parseObject(row.getWxPayloadJson(),BeforeStartPayload.class);
        if(p==null || p.ticketId()==null || p.ticketId()<=0 || p.startsAt()==null || p.startsAt()<=0
                || row.getRecipient()==null || !beforeStartKey(p.ticketId(),row.getRecipient()).equals(row.getEventKey())
                || p.message()==null || !row.getRecipient().equals(p.message().memberId())
                || p.message().openid()==null || p.message().openid().isEmpty()
                || p.message().templateId()==null || p.message().templateId().isEmpty()
                || p.message().page()==null || p.message().fields()==null
                || !p.message().fields().keySet().equals(Set.of("thing1","date5","thing8","thing7","thing12"))
                || p.message().fields().values().stream().anyMatch(Objects::isNull)
                || !Objects.equals(row.getTitle(),p.message().fields().get("thing1"))
                || !Objects.equals(row.getBody(),p.message().fields().get("date5"))
                || !"详见活动详情".equals(p.message().fields().get("thing8"))
                || !"请准时到场".equals(p.message().fields().get("thing7")))
            throw new IllegalStateException("出发提醒冻结输入无法核实");
        return p;
    }

    private static void outsideTransaction() {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("通知外部动作不得在数据库事务内执行");
    }

    private static void pending(Long id,Throwable failure) {
        log.warn("Official notice recovery/verification required id={} type={}",id,failure.getClass().getSimpleName());
    }
}
