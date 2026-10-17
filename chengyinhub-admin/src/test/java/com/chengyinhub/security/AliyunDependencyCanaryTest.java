package com.chengyinhub.security;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.util.StreamUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AliyunDependencyCanaryTest {
    @Test
    void teaRsaSigningUsesOnlyApprovedBouncyCastleArtifacts() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair pair = generator.generateKeyPair();
        String secret = java.util.Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        String message = "local-tea-signature-fixture";
        byte[] signed = com.aliyun.openapiutil.Client.SignatureMethod(message, secret, "ACS3-RSA-SHA256");
        java.security.Signature verifier = java.security.Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pair.getPublic());
        verifier.update(message.getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(signed));
        verifier.initVerify(pair.getPublic());
        verifier.update("tampered".getBytes(StandardCharsets.UTF_8));
        assertFalse(verifier.verify(signed));
        com.aliyun.tea.TeaRequest request = new com.aliyun.tea.TeaRequest();
        request.method = "POST"; request.pathname = "/";
        request.headers.put("host", "fixture.invalid");
        String hash = com.aliyun.openapiutil.Client.hexEncode(
                com.aliyun.openapiutil.Client.hash(new byte[0], "ACS3-RSA-SHA256"));
        assertTrue(com.aliyun.openapiutil.Client.getAuthorization(request, "ACS3-RSA-SHA256", hash,
                "fixture-id", secret).startsWith("ACS3-RSA-SHA256 Credential=fixture-id,"));
    }

    @Test
    void explicitCredentialsSignSmsWithoutUnusedIniParser() throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.ini4j.Wini"));
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> signedRequest = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(StreamUtils.copyToByteArray(exchange.getRequestBody()), StandardCharsets.UTF_8);
            signedRequest.set(exchange.getRequestHeaders().getFirst("Authorization") + exchange.getRequestURI().toString() + body);
            calls.incrementAndGet();
            byte[] response = "{\"Code\":\"OK\",\"Message\":\"OK\",\"RequestId\":\"local-canary\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            Config config = new Config().setAccessKeyId("fixture-ak").setAccessKeySecret("fixture-secret")
                    .setEndpoint("127.0.0.1:" + server.getAddress().getPort()).setProtocol("http")
                    .setConnectTimeout(2000).setReadTimeout(2000);
            Client client = new Client(config);
            assertEquals("fixture-ak", client.getAccessKeyId());
            assertEquals("OK", client.sendSms(new SendSmsRequest().setPhoneNumbers("13800138000")
                    .setSignName("fixture").setTemplateCode("fixture")
                    .setTemplateParam("{\"code\":\"123456\"}")).getBody().getCode());
            assertEquals(1, calls.get());
            assertTrue(signedRequest.get().contains("fixture-ak"));
            assertTrue(signedRequest.get().toLowerCase().contains("signature"));
            assertEquals("fixture-ak", new DefaultCredentialProvider("fixture-ak", "fixture-secret")
                    .getCredentials().getAccessKeyId());
        } finally {
            server.stop(0);
        }
    }
}
