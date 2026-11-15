package com.chengyinhub.web.controller.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.common.annotation.Anonymous;

class WellKnownControllerTest
{
    @Test
    void servesAasaAsAnonymousJson() throws Exception
    {
        ResponseEntity<byte[]> response = new WellKnownController().appleAppSiteAssociation();
        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        String appId = JSON.parseObject(new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                .getJSONObject("applinks").getJSONArray("details").getJSONObject(0).getJSONArray("appIDs").getString(0);
        assertEquals("9VHY8N755R.com.chengyinhub.chengyinApp", appId);
        java.lang.reflect.Method m = WellKnownController.class.getMethod("appleAppSiteAssociation");
        assertNotNull(m.getAnnotation(Anonymous.class));
        assertEquals("/.well-known/apple-app-site-association", m.getAnnotation(GetMapping.class).value()[0]);
    }

    @Test
    void backendCopyMatchesTheFrontendStaticCopy() throws Exception
    {
        Path backend = Path.of("src/main/resources/well-known/apple-app-site-association");
        Path frontend = Path.of("../chengyinhub-ui/public/.well-known/apple-app-site-association");
        assertArrayEquals(Files.readAllBytes(frontend), Files.readAllBytes(backend));
    }
}
