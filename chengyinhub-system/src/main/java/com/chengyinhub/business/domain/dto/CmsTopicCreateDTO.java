package com.chengyinhub.business.domain.dto;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 后台新增主题请求(POST /business/topic)· 施工图 Task 2。
 *
 * <p><b>核心不变式:发布者身份决定归属。</b>「官方发的就是官方发的,俱乐部发的就是俱乐部发的」
 * —— 定稿 §6.5-B「票款归内容槽持有者」。所以发布者必须【显式声明】,不从数据反推。
 *
 * <p><b>为什么改掉原来的「按入口判平台」</b>:原 {@code insertCmsTopic} 无条件声明内容槽 = PLATFORM,
 * 理由是「能过 business:topic:add 权限的就是平台运营」。判据取【入口】在只有「官方发」一种用法时
 * 是对的,但本轨要的正是让后台【代俱乐部发】—— 入口相同、归属不同,入口就再也判不出归属了。
 * 恒定 PLATFORM 会把俱乐部的票款判给平台,这是一笔错账。
 *
 * <p>同样也不回退成「拿 memberId 反推」(memberId==100000 就算平台)—— 那正是
 * {@code copyTopicBasicInfo} 里那句裸魔数 {@code setMemberId(100000L)} 干的事,
 * 用组合约定反推事实是 P0-02/P0-35 反复治过的病。<b>所以第三条路:让调用方直说。</b>
 *
 * <p>三种发布者与内容槽的对应(见 {@code RoleEntityType}):
 * <table border="1">
 *   <tr><th>publisherType</th><th>publisherId</th><th>内容槽</th><th>票款归</th></tr>
 *   <tr><td>PLATFORM</td><td>必须为空</td><td>PLATFORM(entity_id=NULL)</td><td>平台(③-自营)</td></tr>
 *   <tr><td>CLUB</td><td>clubId</td><td>CLUB(clubId)</td><td>俱乐部,payeeMemberId=主理人</td></tr>
 *   <tr><td>MEMBER</td><td>memberId</td><td>MEMBER(memberId)</td><td>该会员</td></tr>
 * </table>
 */
public class CmsTopicCreateDTO {

    /**
     * 发布者类型:PLATFORM / CLUB / MEMBER。必填,不接受推断。
     * <p>不开放 MERCHANT —— {@code CmsTopic.memberId} 的注释写着「商家不能发布」,
     * 商家经【报名】进供给槽,不占内容槽(Task 3)。
     */
    private String publisherType;

    /**
     * 发布者主体 id。PLATFORM 必须为空;CLUB 填 clubId;MEMBER 填 memberId。
     * <p>与库里的 {@code ck_role_entity_id} 同口径 —— 一行自相矛盾的数据就是一笔错账。
     */
    private Long publisherId;

    /**
     * 资格承接会员 id = H8 event 15 结算积分发给谁。
     * <p>PLATFORM:可空(留空则落平台账号)。CLUB:必填,= 该俱乐部主理人 ——
     * 俱乐部不是一个 ums_member，event 15 积分入账读的是
     * {@code topic.memberId},不给它一个真人 id 就会静默不打款。MEMBER:留空则取 publisherId。
     */
    private Long payeeMemberId;

    private String name;

    private String subtitle;

    private String description;

    private String imgUrl;

    private String imgArr;

    private String categoryIds;

    /** 1经典定向 2自由定向 */
    private Integer productType;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startDate;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endDate;

    /** 主题所有可售 SKU 的硬售止（排他上界）。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date saleEndAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date merchantSignUpStartDate;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date merchantSignUpEndDate;

    private String addressName;

    public String getPublisherType() { return publisherType; }
    public void setPublisherType(String publisherType) { this.publisherType = publisherType; }
    public Long getPublisherId() { return publisherId; }
    public void setPublisherId(Long publisherId) { this.publisherId = publisherId; }
    public Long getPayeeMemberId() { return payeeMemberId; }
    public void setPayeeMemberId(Long payeeMemberId) { this.payeeMemberId = payeeMemberId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getImgUrl() { return imgUrl; }
    public void setImgUrl(String imgUrl) { this.imgUrl = imgUrl; }
    public String getImgArr() { return imgArr; }
    public void setImgArr(String imgArr) { this.imgArr = imgArr; }
    public String getCategoryIds() { return categoryIds; }
    public void setCategoryIds(String categoryIds) { this.categoryIds = categoryIds; }
    public Integer getProductType() { return productType; }
    public void setProductType(Integer productType) { this.productType = productType; }
    public Date getStartDate() { return startDate; }
    public void setStartDate(Date startDate) { this.startDate = startDate; }
    public Date getEndDate() { return endDate; }
    public void setEndDate(Date endDate) { this.endDate = endDate; }
    public Date getSaleEndAt() { return saleEndAt; }
    public void setSaleEndAt(Date saleEndAt) { this.saleEndAt = saleEndAt; }
    public Date getMerchantSignUpStartDate() { return merchantSignUpStartDate; }
    public void setMerchantSignUpStartDate(Date merchantSignUpStartDate) { this.merchantSignUpStartDate = merchantSignUpStartDate; }
    public Date getMerchantSignUpEndDate() { return merchantSignUpEndDate; }
    public void setMerchantSignUpEndDate(Date merchantSignUpEndDate) { this.merchantSignUpEndDate = merchantSignUpEndDate; }
    public String getAddressName() { return addressName; }
    public void setAddressName(String addressName) { this.addressName = addressName; }
}
