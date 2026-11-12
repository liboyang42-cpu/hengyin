package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 商品绑定的作品与冻结内容修订 marketing_product_item。
 *
 * <p>一个城市包/通行证可包含多个主题；每个主题在商品配置时绑定
 * {@code cms_topic.published_config_version}。购买时把该修订复制进
 * {@code cms_registration} 授权行，让"内容修订 / 商品 / 订单 / 授权"四层各自留证。
 *
 * <p>本表只存标题快照与修订号，不复制题目正文、答案或付费内容。
 */
public class MarketingProductItem {

    private Long id;
    private Long productId;
    private Long topicId;
    private String topicTitle;
    private Long contentRevision;
    private Integer sortNo;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public String getTopicTitle() { return topicTitle; }
    public void setTopicTitle(String topicTitle) { this.topicTitle = topicTitle; }

    public Long getContentRevision() { return contentRevision; }
    public void setContentRevision(Long contentRevision) { this.contentRevision = contentRevision; }

    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
