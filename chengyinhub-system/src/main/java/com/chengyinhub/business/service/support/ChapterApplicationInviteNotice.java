package com.chengyinhub.business.service.support;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Only a fresh invitation INSERT or a successful original reinvite CAS submits this event. */
@Service
public class ChapterApplicationInviteNotice {
    private final CommittedOfficialNoticeService notices;
    public ChapterApplicationInviteNotice(CommittedOfficialNoticeService notices) { this.notices=notices; }

    @Transactional(propagation=Propagation.MANDATORY, rollbackFor=Exception.class)
    public Long submit(Long topicId, Long applicationId, Long round, Long recipient) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("邀请通知必须与原申请事务一起登记");
        if (topicId==null || topicId<=0 || applicationId==null || applicationId<=0 || round==null || round<=0)
            throw new IllegalStateException("缺少可核实的原邀请轮次");
        // Domain scope + original application + persisted attempt + event; never a clock or a random request key.
        String key="chapter-app:"+topicId+":"+applicationId+":"+round+":invite";
        return notices.enqueueOfficial(key,recipient,"章节承接邀请","请在主题承接进度中接受或拒绝邀请");
    }
}
