package com.chengyinhub.business.service.channel.media;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.service.impl.AppMediaAssetService;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;

/**
 * 生产存放：与 /api/common/uploadOSS 的待审媒体同一条链 —— 私有 OSS 对象（带作者元数据）+ AppMediaAssetService.stage
 * （app_media_asset HOLD + MEDIA_ASSET 审核单 + 音频走微信异步机审，视频/文件走人工审核）。
 */
@Component
public class OssImMediaStore implements ImMediaStore
{
    static final String BIZ_TYPE = "IM_MEDIA";

    @Autowired
    private AppMediaAssetService assets;
    @Autowired
    private AppMediaAssetMapper assetMapper;

    @Override
    public long put(long ownerMemberId, byte[] bytes, String ext, String mime)
    {
        try
        {
            String objectKey = FileOssUploadUtils.uploadPendingWithOwner(new Bytes(bytes, "im." + ext, mime),
                    String.valueOf(ownerMemberId), BIZ_TYPE).getString("fileName");
            Map<String, Object> staged = assets.stage(ownerMemberId, objectKey);
            return ((Number) staged.get("assetId")).longValue();
        }
        catch (IOException e)
        {
            throw new IllegalStateException("IM 媒体写入对象存储失败", e);
        }
    }

    @Override
    public String shortLivedUrl(long assetId, int seconds)
    {
        Map<String, Object> row = assetMapper.select(assetId);
        if (row == null) throw new IllegalStateException("媒体不存在");
        return FileOssUploadUtils.generateShortLivedPresignedUrl(String.valueOf(row.get("objectKey")), seconds);
    }

    @Override
    public boolean delete(long assetId)
    {
        Map<String, Object> row = assetMapper.select(assetId);
        return row != null && FileOssUploadUtils.deleteOwnedObjectIfPresent(String.valueOf(row.get("objectKey")));
    }

    /** 服务端已核对过的字节；对象键扩展名取自服务端识别结果，不用客户端文件名。 */
    static final class Bytes implements MultipartFile
    {
        private final byte[] bytes;
        private final String name;
        private final String contentType;

        Bytes(byte[] bytes, String name, String contentType)
        {
            this.bytes = bytes;
            this.name = name;
            this.contentType = contentType;
        }

        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return name; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File dest) throws IOException { java.nio.file.Files.write(dest.toPath(), bytes); }
    }
}
