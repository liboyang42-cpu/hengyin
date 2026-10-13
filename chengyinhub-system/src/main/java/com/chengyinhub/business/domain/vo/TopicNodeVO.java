package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 公开主题节点白名单。刻意不声明 CmsTopicNode.checkinCode，核销码永不随详情下发。
 */
public class TopicNodeVO {
    private Long id;
    private Long topicId;
    private Long chapterId;
    private String name;
    private String description;
    private String address;
    private String longitude;
    private String latitude;
    private String imgUrl;
    private Integer nodeTime;
    private Long templateId;
    private String businessTime;
    private Long status;
    private Long sortId;
    private Integer xpValue;
    private String hookText;
    private String cardHookLong;
    private String fragmentText;

    @Schema(description = "节点下的模版（答案、提示已剥离）")
    private CmsMemberTemplate cmsMemberTemplate;
    @Schema(description = "节点下商家报名信息")
    private List<PublicMerchantRegistrationVO> registrationMerchantList;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getChapterId() { return chapterId; }
    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getLongitude() { return longitude; }
    public void setLongitude(String longitude) { this.longitude = longitude; }
    public String getLatitude() { return latitude; }
    public void setLatitude(String latitude) { this.latitude = latitude; }
    public String getImgUrl() { return imgUrl; }
    public void setImgUrl(String imgUrl) { this.imgUrl = imgUrl; }
    public Integer getNodeTime() { return nodeTime; }
    public void setNodeTime(Integer nodeTime) { this.nodeTime = nodeTime; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getBusinessTime() { return businessTime; }
    public void setBusinessTime(String businessTime) { this.businessTime = businessTime; }
    public Long getStatus() { return status; }
    public void setStatus(Long status) { this.status = status; }
    public Long getSortId() { return sortId; }
    public void setSortId(Long sortId) { this.sortId = sortId; }
    public Integer getXpValue() { return xpValue; }
    public void setXpValue(Integer xpValue) { this.xpValue = xpValue; }
    public String getHookText() { return hookText; }
    public void setHookText(String hookText) { this.hookText = hookText; }
    public String getCardHookLong() { return cardHookLong; }
    public void setCardHookLong(String cardHookLong) { this.cardHookLong = cardHookLong; }
    public String getFragmentText() { return fragmentText; }
    public void setFragmentText(String fragmentText) { this.fragmentText = fragmentText; }
    public CmsMemberTemplate getCmsMemberTemplate() { return cmsMemberTemplate; }
    public void setCmsMemberTemplate(CmsMemberTemplate cmsMemberTemplate) { this.cmsMemberTemplate = cmsMemberTemplate; }
    public List<PublicMerchantRegistrationVO> getRegistrationMerchantList() { return registrationMerchantList; }
    public void setRegistrationMerchantList(List<PublicMerchantRegistrationVO> registrationMerchantList) { this.registrationMerchantList = registrationMerchantList; }
}
