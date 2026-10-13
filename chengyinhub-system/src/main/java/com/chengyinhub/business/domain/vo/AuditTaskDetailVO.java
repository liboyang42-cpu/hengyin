package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.AuditTask;

import java.util.Collections;
import java.util.Date;
import java.util.List;

public class AuditTaskDetailVO {
    private AuditTask task;
    private List<AuditRecord> timeline = Collections.emptyList();
    private int reportCount;
    private boolean canRevert;
    private Date revertDeadline;
    private Date serverTime;

    public AuditTask getTask() { return task; }
    public void setTask(AuditTask task) { this.task = task; }
    public List<AuditRecord> getTimeline() { return timeline; }
    public void setTimeline(List<AuditRecord> timeline) { this.timeline = timeline; }
    public int getReportCount() { return reportCount; }
    public void setReportCount(int reportCount) { this.reportCount = reportCount; }
    public boolean isCanRevert() { return canRevert; }
    public void setCanRevert(boolean canRevert) { this.canRevert = canRevert; }
    public Date getRevertDeadline() { return revertDeadline; }
    public void setRevertDeadline(Date revertDeadline) { this.revertDeadline = revertDeadline; }
    public Date getServerTime() { return serverTime; }
    public void setServerTime(Date serverTime) { this.serverTime = serverTime; }
}
