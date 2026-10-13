package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 商家侧准备功能的稳定接口契约。 */
public final class MerchantOpsVO {
    private MerchantOpsVO() { }

    public static class ActionTarget {
        private String type;
        private String path;
        private Boolean available;
        private String unavailableReason;
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public Boolean getAvailable() { return available; }
        public void setAvailable(Boolean available) { this.available = available; }
        public String getUnavailableReason() { return unavailableReason; }
        public void setUnavailableReason(String unavailableReason) { this.unavailableReason = unavailableReason; }
    }

    public static class Prerequisite {
        private String code;
        private String title;
        private String description;
        private String status;
        private String severity;
        private ActionTarget actionTarget;
        private String permissionRequired;
        private Date checkedAt;
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        public ActionTarget getActionTarget() { return actionTarget; }
        public void setActionTarget(ActionTarget actionTarget) { this.actionTarget = actionTarget; }
        public String getPermissionRequired() { return permissionRequired; }
        public void setPermissionRequired(String permissionRequired) { this.permissionRequired = permissionRequired; }
        public Date getCheckedAt() { return checkedAt; }
        public void setCheckedAt(Date checkedAt) { this.checkedAt = checkedAt; }
    }

    public static class PrerequisiteChecklist {
        private Long merchantId;
        private boolean canContinue;
        private Date checkedAt;
        private List<Prerequisite> items = new ArrayList<>();
        public Long getMerchantId() { return merchantId; }
        public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
        public boolean isCanContinue() { return canContinue; }
        public void setCanContinue(boolean canContinue) { this.canContinue = canContinue; }
        public Date getCheckedAt() { return checkedAt; }
        public void setCheckedAt(Date checkedAt) { this.checkedAt = checkedAt; }
        public List<Prerequisite> getItems() { return items; }
        public void setItems(List<Prerequisite> items) { this.items = items; }
    }

    public static class Detail {
        private Long merchantId;
        private String name;
        private Date updatedAt;
        private String source;
        private PlaySection play;
        private VibeSection vibe;
        private ReviewSection reviews;
        private List<QuickAction> quickActions = new ArrayList<>();
        public Long getMerchantId() { return merchantId; }
        public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Date getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public PlaySection getPlay() { return play; }
        public void setPlay(PlaySection play) { this.play = play; }
        public VibeSection getVibe() { return vibe; }
        public void setVibe(VibeSection vibe) { this.vibe = vibe; }
        public ReviewSection getReviews() { return reviews; }
        public void setReviews(ReviewSection reviews) { this.reviews = reviews; }
        public List<QuickAction> getQuickActions() { return quickActions; }
        public void setQuickActions(List<QuickAction> quickActions) { this.quickActions = quickActions; }
    }

    public static class PlaySection {
        private String businessTime;
        private String serviceText;
        private String serviceTag;
        private Integer featuredType;
        private Long featuredId;
        public String getBusinessTime() { return businessTime; }
        public void setBusinessTime(String businessTime) { this.businessTime = businessTime; }
        public String getServiceText() { return serviceText; }
        public void setServiceText(String serviceText) { this.serviceText = serviceText; }
        public String getServiceTag() { return serviceTag; }
        public void setServiceTag(String serviceTag) { this.serviceTag = serviceTag; }
        public Integer getFeaturedType() { return featuredType; }
        public void setFeaturedType(Integer featuredType) { this.featuredType = featuredType; }
        public Long getFeaturedId() { return featuredId; }
        public void setFeaturedId(Long featuredId) { this.featuredId = featuredId; }
    }

    public static class VibeSection {
        private String description;
        private String slogan;
        private String cityRole;
        private String tags;
        private String gallery;
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getSlogan() { return slogan; }
        public void setSlogan(String slogan) { this.slogan = slogan; }
        public String getCityRole() { return cityRole; }
        public void setCityRole(String cityRole) { this.cityRole = cityRole; }
        public String getTags() { return tags; }
        public void setTags(String tags) { this.tags = tags; }
        public String getGallery() { return gallery; }
        public void setGallery(String gallery) { this.gallery = gallery; }
    }

    public static class ReviewSection {
        private long total;
        private BigDecimal averageRating;
        private String summaryStatus;
        private Date updatedAt;
        private List<String> sources = new ArrayList<>();
        public long getTotal() { return total; }
        public void setTotal(long total) { this.total = total; }
        public BigDecimal getAverageRating() { return averageRating; }
        public void setAverageRating(BigDecimal averageRating) { this.averageRating = averageRating; }
        public String getSummaryStatus() { return summaryStatus; }
        public void setSummaryStatus(String summaryStatus) { this.summaryStatus = summaryStatus; }
        public Date getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
        public List<String> getSources() { return sources; }
        public void setSources(List<String> sources) { this.sources = sources; }
    }

    public static class QuickAction extends ActionTarget {
        private String code;
        private String label;
        private String value;
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }
}
