package com.chengyinhub.business.service.support;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.CommittedOfficialNoticeWx;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.mapper.CommittedOfficialNoticeWxMapper;
import com.chengyinhub.business.notify.NotifyEvent;
import com.chengyinhub.business.notify.NotifyMessage;
import com.chengyinhub.business.notify.WxSubscribeChannel;
import com.chengyinhub.business.service.IImService;
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

    private void wake(Long id) {
        try { executor.schedule(() -> {
            try { deliver(id); }
            catch(RuntimeException failure) { pending(id,failure); }
        },10,TimeUnit.MILLISECONDS); }
        catch(Throwable rejected) { pending(id,rejected); }
    }

    /** Invoked after commit or by the existing recovery task; no business connection spans a WeChat call. */
    public void deliver(Long id) {
        outsideTransaction();
        CommittedOfficialNoticeWx frozen=independent.execute(tx -> {
            CommittedOfficialNoticeWx row=mapper.selectByIdForUpdate(id);validate(row);return row;
        });
        if(frozen==null) throw new IllegalStateException("通知原意图不存在");
        try {
            Long messageId=independent.execute(tx -> im.deliverSystemNotification(frozen.getImIntentId()));
            if(messageId==null || messageId<=0) throw new IllegalStateException("站内通知未返回原消息收据");
        } catch(RuntimeException failure) { pending(id,failure); }

        CommittedOfficialNoticeWx claim=independent.execute(tx -> {
            CommittedOfficialNoticeWx row=mapper.selectByIdForUpdate(id);validate(row);
            if(!CommittedOfficialNoticeWx.PENDING.equals(row.getWxState())) return null;
            String owner=UUID.randomUUID().toString();
            if(mapper.claimUnknown(id,owner)!=1) throw new IllegalStateException("通知尝试领取失败");
            row.setOwnerToken(owner);row.setWxState(CommittedOfficialNoticeWx.UNKNOWN);return row;
        });
        if(claim==null) return;
        outsideTransaction();
        boolean accepted;
        try {
            accepted=wx.send(NotifyMessage.of(NotifyEvent.OFFICIAL_BROADCAST,claim.getRecipient())
                    .with("title",claim.getTitle()).with("sub",claim.getBody()));
        } catch(Throwable unknown) { pending(id,unknown);return; }
        // false includes both definitive skips and swallowed I/O failures. It cannot authorize a resend.
        if(!accepted) { log.warn("Official notice WeChat result UNKNOWN id={}",id);return; }
        try {
            independent.executeWithoutResult(tx -> {
                if(mapper.accepted(id,claim.getOwnerToken())!=1)
                    throw new IllegalStateException("通知尝试回执归属已变化");
            });
        } catch(RuntimeException unknownReceipt) { pending(id,unknownReceipt); }
    }

    /** Enumerated entries, not a delivered count. Fixed high-water mark; failed entries cannot starve later pages. */
    public int reconcilePending() {
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
        return CanonicalDigest.sha256Hex(JSON.toJSONString(Arrays.asList(row.getEventKey(),row.getRecipient(),
                row.getImIntentId(),row.getTitle(),row.getBody())));
    }

    private static void validate(CommittedOfficialNoticeWx row) {
        if(row==null || row.getId()==null || row.getId()<=0 || row.getRecipient()==null || row.getRecipient()<=0
                || row.getImIntentId()==null || row.getImIntentId()<=0 || row.getTitle()==null || row.getBody()==null
                || row.getEventKey()==null || !row.getEventKey().matches("[A-Za-z0-9:_-]{1,96}")
                || !fingerprint(row).equals(row.getRequestHash()))
            throw new IllegalStateException("通知冻结事实无法核实");
        if(!CommittedOfficialNoticeWx.PENDING.equals(row.getWxState())
                && !CommittedOfficialNoticeWx.UNKNOWN.equals(row.getWxState())
                && !CommittedOfficialNoticeWx.ACCEPTED.equals(row.getWxState()))
            throw new IllegalStateException("通知原状态待人工核实");
        if(CommittedOfficialNoticeWx.PENDING.equals(row.getWxState()) ? row.getOwnerToken()!=null
                : row.getOwnerToken()==null || row.getOwnerToken().isEmpty())
            throw new IllegalStateException("通知尝试归属无法核实");
    }

    private static void outsideTransaction() {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("通知外部动作不得在数据库事务内执行");
    }

    private static void pending(Long id,Throwable failure) {
        log.warn("Official notice recovery/verification required id={} type={}",id,failure.getClass().getSimpleName());
    }
}
