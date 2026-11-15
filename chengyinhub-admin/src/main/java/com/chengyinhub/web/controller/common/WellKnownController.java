package com.chengyinhub.web.controller.common;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.common.annotation.Anonymous;

/**
 * Apple 通用链接：GET /.well-known/apple-app-site-association（application/json，匿名，不跳转）。
 * 内容与 chengyinhub-ui/public/.well-known/apple-app-site-association 同一份（App 仓 docs/release/aasa.json 原样）。
 * 公网 https://www.chengyinhub.com 根路径由 nginx 提供前端静态文件、/prod-api 反代到本服务；
 * 要让 Apple 取到，nginx 需把根路径这一条转到前端静态文件或反代到本端点（见 docs/roadmap/links-push/RESULT.md）。
 */
@RestController
public class WellKnownController
{
    static final String AASA_RESOURCE = "well-known/apple-app-site-association";

    private volatile byte[] cached;

    @Anonymous
    @GetMapping(value = "/.well-known/apple-app-site-association")
    public ResponseEntity<byte[]> appleAppSiteAssociation() throws IOException
    {
        byte[] body = cached;
        if (body == null)
        {
            try (InputStream in = new ClassPathResource(AASA_RESOURCE).getInputStream())
            {
                body = in.readAllBytes();
            }
            cached = body;
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePublic())
                .body(body);
    }
}
