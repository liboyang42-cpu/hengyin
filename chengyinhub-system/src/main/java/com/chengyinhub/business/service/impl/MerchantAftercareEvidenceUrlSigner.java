package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/** 售后凭证只持久化 OSS object key；每次已鉴权详情读取时生成五分钟访问地址。 */
@Service
public class MerchantAftercareEvidenceUrlSigner {
    private static final int EVIDENCE_URL_TTL_SECONDS = 300;
    private static final Pattern EVIDENCE_OBJECT_KEY = Pattern.compile(
            "^upload/merchant-aftercare-evidence/[0-9a-f]{32}\\.(?:jpe?g|png|gif)$");

    public String sign(String objectKey) {
        if (!isEvidenceObjectKey(objectKey)) {
            throw new IllegalArgumentException("售后凭证对象标识不合法");
        }
        return FileOssUploadUtils.generateShortLivedPresignedUrl(
                objectKey.trim(), EVIDENCE_URL_TTL_SECONDS);
    }

    /** 凭证对象须绑定该门店与退款单(OSS 元数据);不符抛 IllegalArgumentException,存储故障抛 IllegalStateException。 */
    public void requireBoundTo(String objectKey, Long merchantId, Long refundId) {
        if (!isEvidenceObjectKey(objectKey)) {
            throw new IllegalArgumentException("售后凭证对象标识不合法");
        }
        FileOssUploadUtils.verifyAftercareEvidenceBinding(objectKey.trim(), merchantId, refundId);
    }

    public static boolean isEvidenceObjectKey(String value) {
        return value != null && EVIDENCE_OBJECT_KEY.matcher(value.trim()).matches();
    }
}
