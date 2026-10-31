package com.chengyinhub.business.domain.dto;

import java.util.List;

/**
 * 定向广播请求:筛选条件与客户列表同源(keyword/segment/tagId/sourceType/时间窗),
 * scope+groups 是页面上的「全选/队伍/角色」收窄,scope=MEMBERS 时按 memberIds 逐个选人。
 * content/requestId 仅发送时必填。
 */
public class MerchantCrmBroadcastRequest {
    private String keyword;
    private String segment;
    private Long tagId;
    private Integer sourceType;
    private String sourceStart;
    private String sourceEnd;
    /** ALL / TEAM / ROLE / MEMBERS;空按 ALL。 */
    private String scope;
    /** 选中的队伍名或角色中文名;仅 TEAM/ROLE 使用。 */
    private List<String> groups;
    /** 逐个选中的客户会员 id;仅 MEMBERS 使用,且必须落在当前筛选结果内。 */
    private List<Long> memberIds;
    /** 只发给这个主题的买票玩家(走查 v2 R07,主题工作台客户弹窗「定向广播」);空 = 不按主题收窄。 */
    private Long topicId;
    private String content;
    private String requestId;

    public String getKeyword() { return keyword; }
    public void setKeyword(String value) { keyword = value; }
    public String getSegment() { return segment; }
    public void setSegment(String value) { segment = value; }
    public Long getTagId() { return tagId; }
    public void setTagId(Long value) { tagId = value; }
    public Integer getSourceType() { return sourceType; }
    public void setSourceType(Integer value) { sourceType = value; }
    public String getSourceStart() { return sourceStart; }
    public void setSourceStart(String value) { sourceStart = value; }
    public String getSourceEnd() { return sourceEnd; }
    public void setSourceEnd(String value) { sourceEnd = value; }
    public String getScope() { return scope; }
    public void setScope(String value) { scope = value; }
    public List<String> getGroups() { return groups; }
    public void setGroups(List<String> value) { groups = value; }
    public List<Long> getMemberIds() { return memberIds; }
    public void setMemberIds(List<Long> value) { memberIds = value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { topicId = value; }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
}
