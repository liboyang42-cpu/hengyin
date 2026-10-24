package com.chengyinhub.business.domain.vo;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 注销预检/状态的统一返回。 */
public class DeregistrationStatusVO {
    private String status;
    private String verificationMethod = "UNAVAILABLE";
    private Date executeAfter;
    private List<String> blockers = new ArrayList<>();

    public String getVerificationMethod() { return verificationMethod; }
    public void setVerificationMethod(String verificationMethod) { this.verificationMethod = verificationMethod; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Date getExecuteAfter() { return executeAfter; }
    public void setExecuteAfter(Date executeAfter) { this.executeAfter = executeAfter; }
    public List<String> getBlockers() { return blockers; }
    public void setBlockers(List<String> blockers) { this.blockers = blockers == null ? new ArrayList<String>() : blockers; }
}
