package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * AI 组题候选快照 ai_merchant_match_snapshot(M1 专属,复盘/申诉用)。
 *
 * @author chengyinhub
 */
public class AiMerchantMatchSnapshot extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long memberId;
    private String traceId;
    private String idea;
    private String conditionsJson;
    private String candidatesJson;
    private String choiceJson;
    private Integer delFlag;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getIdea() { return idea; }
    public void setIdea(String idea) { this.idea = idea; }
    public String getConditionsJson() { return conditionsJson; }
    public void setConditionsJson(String conditionsJson) { this.conditionsJson = conditionsJson; }
    public String getCandidatesJson() { return candidatesJson; }
    public void setCandidatesJson(String candidatesJson) { this.candidatesJson = candidatesJson; }
    public String getChoiceJson() { return choiceJson; }
    public void setChoiceJson(String choiceJson) { this.choiceJson = choiceJson; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
}
