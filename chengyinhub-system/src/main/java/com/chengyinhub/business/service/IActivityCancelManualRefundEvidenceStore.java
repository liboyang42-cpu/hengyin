package com.chengyinhub.business.service;

/** 服务端受控的人工退款凭证读取；返回值只能来自私有对象存储回读。 */
public interface IActivityCancelManualRefundEvidenceStore {
    String sha256(String objectKey, String versionId);

    String shortLivedPreviewUrl(String objectKey, String versionId);
}
