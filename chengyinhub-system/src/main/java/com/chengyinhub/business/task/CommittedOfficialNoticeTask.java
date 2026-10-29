package com.chengyinhub.business.task;

import com.chengyinhub.business.service.support.CommittedOfficialNoticeService;
import org.springframework.stereotype.Component;

/** Separate, default-paused Quartz target: the existing local-only IM task keeps its original boundary. */
@Component("committedOfficialNoticeTask")
public class CommittedOfficialNoticeTask {
    private final CommittedOfficialNoticeService notices;
    public CommittedOfficialNoticeTask(CommittedOfficialNoticeService notices) { this.notices=notices; }
    public void deliverPending() { notices.reconcilePending(); }
}
