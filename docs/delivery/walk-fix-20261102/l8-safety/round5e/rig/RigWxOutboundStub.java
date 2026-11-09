package com.chengyinhub.business.service.impl;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Rig-only (never in product code): replaces ONLY the outbound HTTP call to WeChat
 * (package-private sendPost). Everything else is the real WxContentSecurityServiceImpl:
 * the Round5D library check, the switch/openid gates, token caching, errcode handling.
 * msg_sec_check outcome is read from a file so a test can flip WeChat between
 * pass / busy / review / risky while the app runs.
 */
@Component
@Primary
public class RigWxOutboundStub extends WxContentSecurityServiceImpl {

    static final Path MODE = Paths.get(System.getenv().getOrDefault("RIG_WXSEC_MODE_FILE", "/tmp/rig-wxsec-mode"));

    static String mode() {
        try {
            return new String(Files.readAllBytes(MODE), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "pass";
        }
    }

    @Override
    String sendPost(String url, String body, String contentType) {
        if (url.startsWith("https://api.weixin.qq.com/cgi-bin/stable_token") || url.contains("/cgi-bin/token")) {
            return "{\"access_token\":\"rig-token\",\"expires_in\":7200}";
        }
        if (url.contains("/wxa/msg_sec_check")) {
            switch (mode()) {
                case "busy": return "{\"errcode\":-1,\"errmsg\":\"system busy\"}";
                case "review": return "{\"errcode\":0,\"result\":{\"suggest\":\"review\",\"label\":100}}";
                case "risky": return "{\"errcode\":0,\"result\":{\"suggest\":\"risky\",\"label\":20001}}";
                default: return "{\"errcode\":0,\"result\":{\"suggest\":\"pass\",\"label\":100}}";
            }
        }
        if (url.contains("/wxa/media_check_async")) {
            return "{\"errcode\":0,\"trace_id\":\"rig-" + UUID.randomUUID() + "\"}";
        }
        return "{\"errcode\":-1}";
    }
}
