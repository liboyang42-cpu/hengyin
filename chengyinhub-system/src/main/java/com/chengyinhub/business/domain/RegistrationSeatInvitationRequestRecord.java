package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 席位邀请幂等回执（requestId + 服务端请求指纹）。
 *
 * <p>同一 requestId 重放且指纹一致：返回已记录的邀请/操作结果，不产生第二次状态迁移；
 * 指纹不一致：409，零额外写入。外部只按 invitationId/status 恢复结果，明文 token 不在本表。
 */
public class RegistrationSeatInvitationRequestRecord {

    public static final String OP_INVITE = "INVITE";
    public static final String OP_CLAIM = "CLAIM";
    public static final String OP_CANCEL = "CANCEL";
    public static final String OP_RELEASE = "RELEASE";

    private Long id;
    private String requestId;
    private Long memberId;
    private String operation;
    private Long registrationId;
    private Long invitationId;
    private String requestFingerprint;
    private Integer resultStatus;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { memberId = value; }
    public String getOperation() { return operation; }
    public void setOperation(String value) { operation = value; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long value) { registrationId = value; }
    public Long getInvitationId() { return invitationId; }
    public void setInvitationId(Long value) { invitationId = value; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String value) { requestFingerprint = value; }
    public Integer getResultStatus() { return resultStatus; }
    public void setResultStatus(Integer value) { resultStatus = value; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date value) { createTime = value; }
}
