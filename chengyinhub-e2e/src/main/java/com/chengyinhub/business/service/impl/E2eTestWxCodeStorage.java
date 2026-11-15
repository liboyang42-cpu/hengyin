package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.config.RuoYiConfig;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.UUID;

/**
 * TEST-only WxACode storage backed by {@link RuoYiConfig#getProfile()} and {@code chengyin.qrcode.public-base-url}.
 * Loaded only with profile {@code e2e-test} AND {@code chengyin.e2e.test-providers=true}.
 */
@Service
@Profile("e2e-test")
@ConditionalOnProperty(name = "chengyin.e2e.test-providers", havingValue = "true")
public class E2eTestWxCodeStorage implements PlayerWxacodeServiceImpl.WxCodeStorage
{
    private final String publicBaseUrl;

    public E2eTestWxCodeStorage(@Value("${chengyin.qrcode.public-base-url:http://127.0.0.1:8120}") String publicBaseUrl)
    {
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public String store(byte[] bytes, String extension)
    {
        String profile = RuoYiConfig.getProfile();
        if (StringUtils.isBlank(profile))
        {
            throw new IllegalStateException("本地二维码存储目录未配置");
        }
        String safeExt = StringUtils.defaultIfBlank(extension, "png").replaceAll("[^a-zA-Z0-9]", "");
        if (safeExt.isEmpty())
        {
            safeExt = "png";
        }
        String dateDir = DateUtils.parseDateToStr("yyyyMMdd", new Date());
        String objectKey = "qrcode/" + dateDir + "/" + UUID.randomUUID().toString().replace("-", "") + "." + safeExt;
        Path root = Paths.get(profile).toAbsolutePath().normalize();
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root))
        {
            throw new IllegalStateException("非法二维码存储路径");
        }
        try
        {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            return objectKey;
        }
        catch (IOException e)
        {
            throw new IllegalStateException("个人码本地写入失败", e);
        }
    }

    @Override
    public String url(String objectKey)
    {
        if (StringUtils.isBlank(objectKey) || objectKey.contains(".."))
        {
            throw new IllegalStateException("个人码对象路径非法");
        }
        String key = objectKey.replace('\\', '/').replaceAll("^/+", "");
        String base = StringUtils.defaultIfBlank(publicBaseUrl, "http://127.0.0.1:8120").trim().replaceAll("/+$", "");
        return base + "/profile/" + key;
    }
}
