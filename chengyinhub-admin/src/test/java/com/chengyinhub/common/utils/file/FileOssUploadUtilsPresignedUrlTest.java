package com.chengyinhub.common.utils.file;

import com.aliyun.oss.OSSClient;
import com.aliyun.oss.ClientConfiguration;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.GetObjectRequest;
import com.aliyun.oss.model.OSSObject;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.net.URL;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class FileOssUploadUtilsPresignedUrlTest {
    private static final String EVIDENCE_KEY =
            "upload/merchant-aftercare-evidence/0123456789abcdef0123456789abcdef.jpg";
    private static final String EVIDENCE_VERSION_ID = "CAEQARiBgMC-example-version-1";


    @Test
    void communityIdentityAcceptsCanonicalKeysAndExpiredTrustedUrlsWithoutReusingTheirSignature() {
        String key = "upload/0123456789abcdef0123456789abcdef.png";
        assertEquals(key, FileOssUploadUtils.communityMediaObjectKey(key));
        assertEquals(key, FileOssUploadUtils.communityMediaObjectKey(
                "https://private-test.oss-cn-shanghai.aliyuncs.com/" + key + "?Expires=1&Signature=old",
                "private-test", "https://oss-cn-shanghai.aliyuncs.com"));
    }

    @Test
    void communityIdentityRejectsForeignHostsAmbiguousUrlsAndEncodedPaths() {
        String key = "upload/0123456789abcdef0123456789abcdef.png";
        String prefix = "https://private-test.oss-cn-shanghai.aliyuncs.com/";
        for (String reference : new String[] {
                "http://private-test.oss-cn-shanghai.aliyuncs.com/" + key,
                "https://foreign.example/" + key,
                "https://user@private-test.oss-cn-shanghai.aliyuncs.com/" + key,
                "https://private-test.oss-cn-shanghai.aliyuncs.com:443/" + key,
                prefix + key + "#fragment", prefix + "upload%2f0123456789abcdef0123456789abcdef.png",
                prefix + "upload/../" + key, prefix + key.replace(".png", "%2epng"),
                "/profile/" + key, "upload/not-a-server-key.png", " " + prefix + key }) {
            assertThrows(IllegalArgumentException.class, () ->
                    FileOssUploadUtils.communityMediaObjectKey(reference, "private-test",
                            "https://oss-cn-shanghai.aliyuncs.com"), reference);
        }
    }

    @Test
    void nativeSdkDefaultBareEndpointRetainsHttpForOtherBusinessTypes() throws Exception {
        OSSClient client = new OSSClient("oss-cn-shanghai.aliyuncs.com",
                new DefaultCredentialProvider("test-id", "test-secret"), new ClientConfiguration());
        try {
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest("private-test",
                    "upload/0123456789abcdef0123456789abcdef.png");
            request.setExpiration(new Date(System.currentTimeMillis() + 300_000L));
            assertEquals("http", client.generatePresignedUrl(request).getProtocol());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void nativeCommunitySdkSignsBareAndHttpsEndpointsWithoutChangingSignedQuery() throws Exception {
        String key = "upload/0123456789abcdef0123456789abcdef.png";
        Date now = new Date();
        Date expiration = FileOssUploadUtils.shortLivedExpiration(now, 300);
        for (String endpoint : new String[] {"oss-cn-shanghai.aliyuncs.com",
                "https://oss-cn-shanghai.aliyuncs.com", "https://oss-cn-shanghai.aliyuncs.com/"}) {
            OSSClient client = FileOssUploadUtils.createCommunityOssClient(endpoint,
                    new DefaultCredentialProvider("test-id", "test-secret"));
            try {
                GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest("private-test", key);
                request.setExpiration(expiration);
                URL signed = new URL(FileOssUploadUtils.resolveGeneratedUrl(client, request));
                assertEquals("https", signed.getProtocol());
                assertEquals("private-test.oss-cn-shanghai.aliyuncs.com", signed.getHost());
                assertEquals("/" + key, signed.getPath());
                assertEquals(key, FileOssUploadUtils.communityMediaObjectKey(
                        signed.toString(), "private-test", endpoint));
                java.util.Map<String, String> query = new java.util.HashMap<>();
                for (String part : signed.getQuery().split("&")) {
                    String[] pair = part.split("=", 2);
                    query.put(pair[0], java.net.URLDecoder.decode(pair[1], "UTF-8"));
                }
                assertEquals("test-id", query.get("OSSAccessKeyId"));
                assertEquals(expiration.getTime() / 1000L, Long.parseLong(query.get("Expires")));
                assertEquals(300_000L, expiration.getTime() - now.getTime());
                assertTrue(query.get("Signature") != null && !query.get("Signature").isEmpty());
            } finally {
                client.shutdown();
            }
        }
    }

    @Test
    void generalSdkClientSignsHttpsForBareHttpAndHttpsEndpoints() throws Exception {
        String key = "upload/0123456789abcdef0123456789abcdef.png";
        for (String endpoint : new String[] {"oss-cn-shanghai.aliyuncs.com",
                "http://oss-cn-shanghai.aliyuncs.com", "HTTP://oss-cn-shanghai.aliyuncs.com",
                "https://oss-cn-shanghai.aliyuncs.com"}) {
            ClientConfiguration configuration = new ClientConfiguration();
            OSSClient client = FileOssUploadUtils.createOssClient(endpoint,
                    new DefaultCredentialProvider("test-id", "test-secret"), configuration);
            try {
                GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest("private-test", key);
                request.setExpiration(new Date(System.currentTimeMillis() + 60_000L));
                URL signed = new URL(FileOssUploadUtils.resolveGeneratedUrl(client, request));
                assertEquals("https", signed.getProtocol(), endpoint);
                assertEquals("private-test.oss-cn-shanghai.aliyuncs.com", signed.getHost());
                assertEquals("/" + key, signed.getPath());
                assertTrue(signed.getQuery().contains("Signature="));
                assertEquals(com.aliyun.oss.common.comm.Protocol.HTTPS, configuration.getProtocol());
            } finally {
                client.shutdown();
            }
        }
    }

    @Test
    void communitySdkRejectsAmbiguousOrInsecureEndpointBeforeCreatingClient() {
        for (String endpoint : new String[] {null, "", "http://oss-cn-shanghai.aliyuncs.com",
                "https://user@oss-cn-shanghai.aliyuncs.com", "https://oss-cn-shanghai.aliyuncs.com:443",
                "https://oss-cn-shanghai.aliyuncs.com/upload", "https://oss-cn-shanghai.aliyuncs.com?x=1",
                "https://oss-cn-shanghai.aliyuncs.com#fragment", " oss-cn-shanghai.aliyuncs.com",
                "https://oss-cn-shanghai.aliyuncs.com/%2f"}) {
            assertThrows(IllegalArgumentException.class, () -> FileOssUploadUtils.createCommunityOssClient(
                    endpoint, new DefaultCredentialProvider("test-id", "test-secret")));
        }
    }

    @Test
    void returnsTheSignedUrlProducedByTheSdk() throws Exception {
        OSSClient client = mock(OSSClient.class);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest("bucket", "upload/object.png");
        URL signed = new URL("https://bucket.oss.example/upload/object.png?OSSAccessKeyId=key&Signature=sig");
        when(client.generatePresignedUrl(request)).thenReturn(signed);

        assertEquals(signed.toString(), FileOssUploadUtils.resolveGeneratedUrl(client, request));
        verify(client).generatePresignedUrl(request);
    }

    @Test
    void failsClosedWhenTheSdkReturnsNoUrl() {
        OSSClient client = mock(OSSClient.class);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest("bucket", "object");
        when(client.generatePresignedUrl(request)).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> FileOssUploadUtils.resolveGeneratedUrl(client, request));
    }

    @Test
    void publicGeneratorDoesNotDiscardTheSdkSignature() throws Exception {
        Path source = Paths.get(System.getProperty("user.dir"),
                "../chengyinhub-common/src/main/java/com/chengyinhub/common/utils/file/FileOssUploadUtils.java");
        String java = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

        assertTrue(java.contains("return resolveGeneratedUrl(ossClient, request);"));
        assertTrue(java.contains("return signedUrl.toString();"));
        assertFalse(java.contains("return \"https://\" + bucketName + \".\""));
    }

    @Test
    void shortLivedExpirationIsExactAndCannotExceedOneHour() {
        Date now = new Date(1_000L);

        assertEquals(new Date(301_000L), FileOssUploadUtils.shortLivedExpiration(now, 300));
        assertThrows(IllegalArgumentException.class,
                () -> FileOssUploadUtils.shortLivedExpiration(now, 0));
        assertThrows(IllegalArgumentException.class,
                () -> FileOssUploadUtils.shortLivedExpiration(now, 3_601));
    }

    @Test
    void aftercareEvidenceObjectKeyUsesDedicatedUnambiguousPrefix() {
        MockMultipartFile image = new MockMultipartFile(
                "file", "customer-name.jpg", "image/jpeg", new byte[] { 1 });

        String key = FileOssUploadUtils.createAftercareEvidenceObjectKey(image);

        assertTrue(key.matches(
                "upload/merchant-aftercare-evidence/[0-9a-f]{32}\\.jpg"));
        assertFalse(key.contains("customer-name"));
    }

    @Test
    void onlyServerIssuedAftercareObjectKeysCanBeReadOrSigned() {
        FileOssUploadUtils.validateAftercareEvidenceObjectKey(EVIDENCE_KEY);
        FileOssUploadUtils.validateAftercareEvidenceVersionId(EVIDENCE_VERSION_ID);

        assertThrows(IllegalArgumentException.class,
                () -> FileOssUploadUtils.validateAftercareEvidenceObjectKey(
                        "upload/0123456789abcdef0123456789abcdef.jpg"));
        assertThrows(IllegalArgumentException.class,
                () -> FileOssUploadUtils.validateAftercareEvidenceObjectKey(
                        "upload/merchant-aftercare-evidence/../0123456789abcdef0123456789abcdef.jpg"));
        assertThrows(IllegalArgumentException.class,
                () -> FileOssUploadUtils.validateAftercareEvidenceObjectKey(
                        "upload/merchant-aftercare-evidence/0123456789ABCDEF0123456789ABCDEF.jpg"));
        assertThrows(IllegalArgumentException.class,
                () -> FileOssUploadUtils.validateAftercareEvidenceObjectKey(EVIDENCE_KEY + "," + EVIDENCE_KEY));
        assertThrows(IllegalArgumentException.class,
                () -> FileOssUploadUtils.validateAftercareEvidenceVersionId(""));
        assertThrows(IllegalArgumentException.class,
                () -> FileOssUploadUtils.validateAftercareEvidenceVersionId("version\nsmuggle"));
    }

    @Test
    void hashesBytesReadBackFromPrivateOssInsteadOfTrustingTheClient() throws Exception {
        OSSClient client = mock(OSSClient.class);
        OSSObject object = new OSSObject();
        object.setObjectContent(new ByteArrayInputStream("server-evidence".getBytes(StandardCharsets.UTF_8)));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(object);

        String digest = FileOssUploadUtils.resolveAftercareEvidenceSha256(
                client, "private-bucket", EVIDENCE_KEY, EVIDENCE_VERSION_ID, 1024L);

        assertEquals("228b2078a880b630fac7e8097109b0b4c5bc1261b00f2fc16c3d471c50a733ee", digest);
        org.mockito.ArgumentCaptor<GetObjectRequest> request =
                org.mockito.ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObject(request.capture());
        assertEquals(EVIDENCE_KEY, request.getValue().getKey());
        assertEquals(EVIDENCE_VERSION_ID, request.getValue().getVersionId());
    }

    @Test
    void missingOrOversizedEvidenceFailsClosed() {
        OSSClient missingClient = mock(OSSClient.class);
        when(missingClient.getObject(any(GetObjectRequest.class))).thenReturn(null);
        assertThrows(IOException.class, () -> FileOssUploadUtils.resolveAftercareEvidenceSha256(
                missingClient, "private-bucket", EVIDENCE_KEY, EVIDENCE_VERSION_ID, 1024L));

        OSSClient oversizedClient = mock(OSSClient.class);
        OSSObject oversized = new OSSObject();
        oversized.setObjectContent(new ByteArrayInputStream(new byte[] { 1, 2, 3, 4 }));
        when(oversizedClient.getObject(any(GetObjectRequest.class))).thenReturn(oversized);
        assertThrows(IOException.class, () -> FileOssUploadUtils.resolveAftercareEvidenceSha256(
                oversizedClient, "private-bucket", EVIDENCE_KEY, EVIDENCE_VERSION_ID, 3L));
    }

    @Test
    void previewSignatureIsPinnedToTheImmutableVersion() {
        GeneratePresignedUrlRequest request = FileOssUploadUtils.versionedPresignedRequest(
                "private-bucket", EVIDENCE_KEY, EVIDENCE_VERSION_ID, new Date(301_000L));

        assertEquals(EVIDENCE_VERSION_ID, request.getQueryParameter().get("versionId"));
        assertEquals(new Date(301_000L), request.getExpiration());
    }

    @Test
    void publicEvidenceReadAndPreviewBothApplyTheStrictKeyGate() throws Exception {
        Path source = Paths.get(System.getProperty("user.dir"),
                "../chengyinhub-common/src/main/java/com/chengyinhub/common/utils/file/FileOssUploadUtils.java");
        String java = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

        assertTrue(java.contains("validateAftercareEvidenceObjectKey(objectKey);"));
        assertTrue(java.contains("new GetObjectRequest(bucketName, objectKey, versionId)"));
        assertTrue(java.contains("request.addQueryParameter(\"versionId\", versionId)"));
        assertTrue(java.contains("generateShortLivedAftercareEvidenceUrl"));
        assertTrue(java.contains("calculateAftercareEvidenceSha256"));
    }
}
