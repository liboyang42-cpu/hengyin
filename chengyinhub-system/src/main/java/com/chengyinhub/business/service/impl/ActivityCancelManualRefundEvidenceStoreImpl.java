package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.IActivityCancelManualRefundEvidenceStore;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import org.springframework.stereotype.Service;

/** 私有 OSS 证据回读适配器；读取失败时不允许资金状态机前进。 */
@Service
public class ActivityCancelManualRefundEvidenceStoreImpl
        implements IActivityCancelManualRefundEvidenceStore {
    private static final int PREVIEW_EXPIRATION_SECONDS = 300;

    @Override
    public String sha256(String objectKey, String versionId) {
        try {
            return FileOssUploadUtils.calculateAftercareEvidenceSha256(objectKey, versionId);
        } catch (IllegalArgumentException invalid) {
            throw new ServiceException(invalid.getMessage(), 400);
        } catch (Exception unavailable) {
            throw new ServiceException("凭证对象暂不可读取，case 未推进", 503);
        }
    }

    @Override
    public String shortLivedPreviewUrl(String objectKey, String versionId) {
        try {
            return FileOssUploadUtils.generateShortLivedAftercareEvidenceUrl(
                    objectKey, versionId, PREVIEW_EXPIRATION_SECONDS);
        } catch (IllegalArgumentException invalid) {
            throw new ServiceException(invalid.getMessage(), 400);
        } catch (Exception unavailable) {
            throw new ServiceException("凭证预览暂不可用", 503);
        }
    }
}
