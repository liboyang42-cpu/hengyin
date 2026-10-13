package com.chengyinhub.business.domain.vo;

import java.io.File;

/** 仅供鉴权后的 controller 流式读取，不作为 JSON 响应。 */
public class MerchantCrmExportFile {
    private final File file;
    private final String downloadName;
    private final Long taskId;
    private final Integer taskVersion;
    private final String storageKey;
    private final Long merchantId;
    private final Long ownerMemberId;
    private final Long actorMemberId;
    private final String actorRoleCode;

    public MerchantCrmExportFile(File file, String downloadName,
                                 Long taskId, Integer taskVersion, String storageKey,
                                 Long merchantId, Long ownerMemberId,
                                 Long actorMemberId, String actorRoleCode) {
        this.file = file;
        this.downloadName = downloadName;
        this.taskId = taskId;
        this.taskVersion = taskVersion;
        this.storageKey = storageKey;
        this.merchantId = merchantId;
        this.ownerMemberId = ownerMemberId;
        this.actorMemberId = actorMemberId;
        this.actorRoleCode = actorRoleCode;
    }

    public File getFile() { return file; }
    public String getDownloadName() { return downloadName; }
    public Long getTaskId() { return taskId; }
    public Integer getTaskVersion() { return taskVersion; }
    public String getStorageKey() { return storageKey; }
    public Long getMerchantId() { return merchantId; }
    public Long getOwnerMemberId() { return ownerMemberId; }
    public Long getActorMemberId() { return actorMemberId; }
    public String getActorRoleCode() { return actorRoleCode; }
}
