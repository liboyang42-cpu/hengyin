package com.chengyinhub.business.service.channel.media;

import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.service.impl.AppMediaAssetService;
import com.chengyinhub.common.config.RuoYiConfig;
import com.chengyinhub.common.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

/**
 * TEST-only IM media store backed by {@link RuoYiConfig#getProfile()} while preserving
 * {@link AppMediaAssetService#stage(Long, String)} HOLD/audit registration.
 * Loaded only with profile {@code e2e-test} AND {@code chengyin.e2e.test-providers=true}.
 */
@Component
@Primary
@Profile("e2e-test")
@ConditionalOnProperty(name = "chengyin.e2e.test-providers", havingValue = "true")
public class E2eTestImMediaStore implements ImMediaStore
{
    private static final Logger log = LoggerFactory.getLogger(E2eTestImMediaStore.class);

    @Autowired
    private AppMediaAssetService assets;
    @Autowired
    private AppMediaAssetMapper assetMapper;
    @Value("${app.media.public-base-url:${chengyin.qrcode.public-base-url:http://127.0.0.1:8120}}")
    private String publicBaseUrl = "http://127.0.0.1:8120";

    public E2eTestImMediaStore()
    {
        log.warn("[e2e-test] TEST-only IM media store active: storing IM assets under local profile directory");
    }

    @Override
    public long put(long ownerMemberId, byte[] bytes, String ext, String mime)
    {
        if (bytes == null || bytes.length == 0)
        {
            throw new IllegalStateException("IM 媒体内容不能为空");
        }
        String safeExt = StringUtils.defaultIfBlank(ext, "bin").toLowerCase().replaceAll("[^a-z0-9]", "");
        String objectKey = "upload/" + UUID.randomUUID().toString().replace("-", "") + "." + safeExt;
        Path target = resolveUnderProfile(objectKey);
        try
        {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        }
        catch (IOException e)
        {
            throw new IllegalStateException("IM 媒体本地写入失败", e);
        }
        Map<String, Object> staged = assets.stage(ownerMemberId, objectKey);
        return ((Number) staged.get("assetId")).longValue();
    }

    @Override
    public String shortLivedUrl(long assetId, int seconds)
    {
        String key = objectKeyOf(assetId).replaceAll("^/+", "");
        String base = StringUtils.defaultIfBlank(publicBaseUrl, "http://127.0.0.1:8120").trim().replaceAll("/+$", "");
        return base + "/profile/" + key;
    }

    @Override
    public boolean delete(long assetId)
    {
        Map<String, Object> row = assetMapper.select(assetId);
        if (row == null || row.get("objectKey") == null)
        {
            return false;
        }
        try
        {
            return Files.deleteIfExists(resolveUnderProfile(String.valueOf(row.get("objectKey"))));
        }
        catch (IOException e)
        {
            throw new IllegalStateException("IM 媒体本地删除失败", e);
        }
    }

    @Override
    public byte[] readBytes(long assetId, long maxBytes, long expected)
    {
        Path file = resolveUnderProfile(objectKeyOf(assetId));
        if (!Files.isRegularFile(file))
        {
            throw new IllegalStateException("IM 媒体本地文件不存在");
        }
        try
        {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > maxBytes || bytes.length != expected)
            {
                throw new IllegalStateException("object size changed");
            }
            return bytes;
        }
        catch (IOException e)
        {
            throw new IllegalStateException("IM 媒体本地读取失败", e);
        }
    }

    private String objectKeyOf(long assetId)
    {
        Map<String, Object> row = assetMapper.select(assetId);
        if (row == null || row.get("objectKey") == null)
        {
            throw new IllegalStateException("媒体不存在");
        }
        return String.valueOf(row.get("objectKey")).replace('\\', '/');
    }

    private static Path resolveUnderProfile(String objectKey)
    {
        String profile = RuoYiConfig.getProfile();
        if (StringUtils.isBlank(profile))
        {
            throw new IllegalStateException("本地上传根目录未配置");
        }
        if (StringUtils.isBlank(objectKey) || objectKey.contains(".."))
        {
            throw new IllegalStateException("媒体对象路径非法");
        }
        String rel = objectKey.replace('\\', '/').replaceAll("^/+", "");
        if (rel.startsWith("profile/"))
        {
            rel = rel.substring("profile/".length());
        }
        Path root = Paths.get(profile).toAbsolutePath().normalize();
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root))
        {
            throw new IllegalStateException("媒体对象路径越界");
        }
        return target;
    }
}
