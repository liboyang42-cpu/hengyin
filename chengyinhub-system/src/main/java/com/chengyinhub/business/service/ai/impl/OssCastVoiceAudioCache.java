package com.chengyinhub.business.service.ai.impl;

import com.chengyinhub.business.service.support.CastVoiceAudioCache;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import org.springframework.stereotype.Component;

/** 角色台词配音缓存的 OSS 实现:私有对象 cast_voice/{sha256}.mp3 + 1 小时签名地址。 */
@Component
public class OssCastVoiceAudioCache implements CastVoiceAudioCache
{
    private final OssCardCutoutStorage storage;

    public OssCastVoiceAudioCache(OssCardCutoutStorage storage)
    {
        this.storage = storage;
    }

    static String objectKey(String sha256Hex)
    {
        return "cast_voice/" + sha256Hex + ".mp3";
    }

    @Override
    public String find(String sha256Hex)
    {
        String key = objectKey(sha256Hex);
        return FileOssUploadUtils.privateGeneratedObjectExists(key)
                ? FileOssUploadUtils.generateShortLivedPresignedUrl(key, 3600) : null;
    }

    @Override
    public String store(String sha256Hex, String vendorUrl)
    {
        return storage.cacheGeneratedAudio(objectKey(sha256Hex), vendorUrl);
    }
}
