package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.MmsMerchant;

import java.util.List;

import com.chengyinhub.business.domain.vo.ai.NpcProfileVO;

/**
 * 商家公开主页白名单。
 *
 * <p>只承载统一主页「关于」消费的展示字段，禁止改为继承或包装 {@link MmsMerchant}：
 * 那会让 phone / wechat / businessLicense / reson / status / accountStatus 等
 * 隐私与审核字段随实体新增字段自动扩面。新增字段必须显式加在这里。</p>
 */
public class PublicMerchantVO {

    private Long id;
    private Long memberId;
    private String name;
    private String logo;
    private String coverImage;
    private String cityRole;
    private String slogan;
    /** 品牌故事标题 */
    private String storyTitle;
    /** 品牌故事正文 */
    private String description;
    /** 门店相册(CSV) */
    private String gallery;
    /** 特色标签(CSV) */
    private String tags;
    /** 1营业中 0已打烊 */
    private Integer businessStatus;
    private String businessTime;
    private String address;
    /** 门店纬度。与 address 同级的店铺公开位置,供前端点击地址唤起导航。 */
    private Double locationLat;
    /** 门店经度。同上。 */
    private Double locationLng;
    /** 承接能力:可接待人数 */
    private Integer capacity;
    /** 承接能力:可承接时段 */
    private String availableTime;
    /** 承接能力:适合的活动类型 */
    private String suitActivityTypes;
    /** 承接能力:合作需求自由文本 */
    private String demand;
    /** 品类。复用会员侧的公开分类白名单,避免透出分类状态/删除标记/审计字段。 */
    private List<PublicMemberVO.PublicCategoryVO> sysCategoryList;
    /**
     * 门店 AI 形象(npc_profile scope_type=2)。没配 / 未审 / 已停用时为 null,
     * 前端据此整块不渲染 —— 不给空壳卡片。
     */
    private NpcProfileVO npc;
    /**
     * 招牌主推卡(装修里选中的 1 个活动或 1 张券)。运行时只会是
     * {@link PublicFeaturedActivityVO} 或 {@link PublicFeaturedCouponVO};
     * null = 未设置 / 已下架 / 已易主 / 查询失败,前端整块不渲染。
     * 实体里的 featuredType/featuredId 是装修输入,不进本白名单;卡片 VO 自带自己的
     * featuredType/featuredId(1=活动/2=券),前端按它选跳转目标(4-12/S19-d)。
     */
    private Object featured;

    /** 按白名单逐字段搬运;不做反射拷贝,漏字段要比多字段安全。 */
    public static PublicMerchantVO from(MmsMerchant merchant) {
        return fill(merchant, new PublicMerchantVO());
    }

    /** 仅供更窄的公开场景 VO 复用同一份基础白名单。 */
    protected static <T extends PublicMerchantVO> T fill(MmsMerchant merchant, T vo) {
        vo.setId(merchant.getId());
        vo.setMemberId(merchant.getMemberId());
        vo.setName(merchant.getName());
        vo.setLogo(merchant.getLogo());
        vo.setCoverImage(merchant.getCoverImage());
        vo.setCityRole(merchant.getCityRole());
        vo.setSlogan(merchant.getSlogan());
        vo.setStoryTitle(merchant.getStoryTitle());
        vo.setDescription(merchant.getDescription());
        vo.setGallery(merchant.getGallery());
        vo.setTags(merchant.getTags());
        vo.setBusinessStatus(merchant.getBusinessStatus());
        vo.setBusinessTime(merchant.getBusinessTime());
        vo.setAddress(merchant.getAddress());
        vo.setLocationLat(merchant.getLocationLat());
        vo.setLocationLng(merchant.getLocationLng());
        vo.setCapacity(merchant.getCapacity());
        vo.setAvailableTime(merchant.getAvailableTime());
        vo.setSuitActivityTypes(merchant.getSuitActivityTypes());
        vo.setDemand(merchant.getDemand());
        return vo;
    }

    public NpcProfileVO getNpc() {
        return npc;
    }

    public void setNpc(NpcProfileVO npc) {
        this.npc = npc;
    }

    public Object getFeatured() {
        return featured;
    }

    public void setFeatured(Object featured) {
        this.featured = featured;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMemberId() {
        return memberId;
    }

    public void setMemberId(Long memberId) {
        this.memberId = memberId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLogo() {
        return logo;
    }

    public void setLogo(String logo) {
        this.logo = logo;
    }

    public String getCoverImage() {
        return coverImage;
    }

    public void setCoverImage(String coverImage) {
        this.coverImage = coverImage;
    }

    public String getCityRole() {
        return cityRole;
    }

    public void setCityRole(String cityRole) {
        this.cityRole = cityRole;
    }

    public String getSlogan() {
        return slogan;
    }

    public void setSlogan(String slogan) {
        this.slogan = slogan;
    }

    public String getStoryTitle() {
        return storyTitle;
    }

    public void setStoryTitle(String storyTitle) {
        this.storyTitle = storyTitle;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getGallery() {
        return gallery;
    }

    public void setGallery(String gallery) {
        this.gallery = gallery;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public Integer getBusinessStatus() {
        return businessStatus;
    }

    public void setBusinessStatus(Integer businessStatus) {
        this.businessStatus = businessStatus;
    }

    public String getBusinessTime() {
        return businessTime;
    }

    public void setBusinessTime(String businessTime) {
        this.businessTime = businessTime;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public Double getLocationLat() {
        return locationLat;
    }

    public void setLocationLat(Double locationLat) {
        this.locationLat = locationLat;
    }

    public Double getLocationLng() {
        return locationLng;
    }

    public void setLocationLng(Double locationLng) {
        this.locationLng = locationLng;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public String getAvailableTime() {
        return availableTime;
    }

    public void setAvailableTime(String availableTime) {
        this.availableTime = availableTime;
    }

    public String getSuitActivityTypes() {
        return suitActivityTypes;
    }

    public void setSuitActivityTypes(String suitActivityTypes) {
        this.suitActivityTypes = suitActivityTypes;
    }

    public String getDemand() {
        return demand;
    }

    public void setDemand(String demand) {
        this.demand = demand;
    }

    public List<PublicMemberVO.PublicCategoryVO> getSysCategoryList() {
        return sysCategoryList;
    }

    public void setSysCategoryList(List<PublicMemberVO.PublicCategoryVO> sysCategoryList) {
        this.sysCategoryList = sysCategoryList;
    }
}
