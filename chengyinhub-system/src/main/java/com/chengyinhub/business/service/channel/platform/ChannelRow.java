package com.chengyinhub.business.service.channel.platform;

import java.util.Date;

/** im_channel 一行的读模型（MyBatis 按列别名直接写字段）。 */
public class ChannelRow
{
    private Long id;
    private Integer guildKind;
    private Long guildId;
    private Long parentId;
    private Integer kind;
    private String name;
    private String topic;
    private Integer position;
    private Integer isDefault;
    private Integer status;
    private String archiveReason;
    private Date purgeAt;
    private Integer visibility;
    private Integer slowmodeSec;
    private Long permVersion;
    private Long metaVersion;
    private Integer delFlag;

    public Long getId() { return id; }
    public Integer getGuildKind() { return guildKind; }
    public Long getGuildId() { return guildId; }
    public Long getParentId() { return parentId; }
    public Integer getKind() { return kind; }
    public String getName() { return name; }
    public String getTopic() { return topic; }
    public Integer getPosition() { return position; }
    public Integer getIsDefault() { return isDefault; }
    public Integer getStatus() { return status; }
    public String getArchiveReason() { return archiveReason; }
    public Date getPurgeAt() { return purgeAt; }
    public Integer getVisibility() { return visibility; }
    public Integer getSlowmodeSec() { return slowmodeSec; }
    public Long getPermVersion() { return permVersion; }
    public Long getMetaVersion() { return metaVersion; }
    public Integer getDelFlag() { return delFlag; }

    public void setId(Long id) { this.id = id; }
    public void setGuildKind(Integer guildKind) { this.guildKind = guildKind; }
    public void setGuildId(Long guildId) { this.guildId = guildId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public void setKind(Integer kind) { this.kind = kind; }
    public void setName(String name) { this.name = name; }
    public void setTopic(String topic) { this.topic = topic; }
    public void setPosition(Integer position) { this.position = position; }
    public void setIsDefault(Integer isDefault) { this.isDefault = isDefault; }
    public void setStatus(Integer status) { this.status = status; }
    public void setArchiveReason(String archiveReason) { this.archiveReason = archiveReason; }
    public void setPurgeAt(Date purgeAt) { this.purgeAt = purgeAt; }
    public void setVisibility(Integer visibility) { this.visibility = visibility; }
    public void setSlowmodeSec(Integer slowmodeSec) { this.slowmodeSec = slowmodeSec; }
    public void setPermVersion(Long permVersion) { this.permVersion = permVersion; }
    public void setMetaVersion(Long metaVersion) { this.metaVersion = metaVersion; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }

    public boolean isCategory() { return kind != null && kind == 0; }
}
