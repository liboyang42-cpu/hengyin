package com.chengyinhub.upgrade;

import com.aliyun.credentials.Client;
import com.aliyun.credentials.models.Config;
import com.aliyun.credentials.models.CredentialModel;
import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.InstanceProfileCredentialsFetcher;
import com.aliyuncs.http.HttpResponse;
import com.aliyun.tea.TeaRequest;
import com.aliyun.tea.okhttp.OkRequestBuilder;
import com.chengyinhub.business.domain.dto.MerchantLeadImportRow;
import com.chengyinhub.web.controller.business.MerchantLeadImportParser;
import okhttp3.Request;
import okio.Buffer;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Frozen POI 4.1.2 files and offline SDK contracts; never calls a provider or default credential chain. */
class RetainedDependencyGoldenCompatibilityTest {
    @Test void legacyXlsPreservesRowsTextNumbersAndFormulaRejection() throws Exception { assertLegacyImport("xls"); }
    @Test void legacyXlsxPreservesRowsTextNumbersAndFormulaRejection() throws Exception { assertLegacyImport("xlsx"); }

    private void assertLegacyImport(String extension) throws Exception {
        byte[] bytes;
        try (InputStream in = getClass().getResourceAsStream("/upgrade/legacy-poi412." + extension)) {
            assertNotNull(in); bytes = in.readAllBytes();
        }
        String expectedHash = extension.equals("xls")
                ? "43a674f8a982e2559a8478b280c7ec1fb2f5cfe7872fac6df57d5fe0758811f0"
                : "f151dd411845bdcee5a09ea929cabea27776d2b3d1a80de714424fbdd13db1bb";
        assertEquals(expectedHash, java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes)), "Do not regenerate pre-upgrade fixtures");
        List<MerchantLeadImportRow> rows = new MerchantLeadImportParser().parse(
                new MockMultipartFile("file", "legacy." + extension, "application/octet-stream", bytes));
        assertEquals(2, rows.size());
        MerchantLeadImportRow first = rows.get(0);
        assertEquals(2, first.getRowNumber());
        assertEquals("梧桐咖啡·合成样例", first.getName());
        assertEquals("0013800000000", first.getPhone());
        assertEquals("合成地址 10 号", first.getAddress());
        assertEquals("121.466", first.getLongitude());
        assertEquals("31.219", first.getLatitude());
        assertEquals("咖啡", first.getCategory());
        assertEquals("42", first.getMemberId());
        assertNull(first.getParseError());
        assertEquals(5, rows.get(1).getRowNumber());
        assertEquals("1+1", rows.get(1).getLongitude());
        assertEquals("经度不允许使用公式", rows.get(1).getParseError());
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertEquals("商家线索", workbook.getSheetAt(0).getSheetName());
            assertEquals(2.0, workbook.getSheetAt(0).getRow(4).getCell(3).getNumericCellValue());
        }
    }

    @Test void explicitAccessKeyCredentialsAndSmsClientRemainOfflineAndUnchanged() throws Exception {
        Client credentials = new Client(new Config().setType("access_key")
                .setAccessKeyId("synthetic-id").setAccessKeySecret("synthetic-secret"));
        CredentialModel value = credentials.getCredential();
        assertEquals("synthetic-id", value.getAccessKeyId());
        assertEquals("synthetic-secret", value.getAccessKeySecret());
        assertEquals("access_key", value.getType());
        assertNull(value.getSecurityToken());
        com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config()
                .setAccessKeyId("synthetic-id").setAccessKeySecret("synthetic-secret");
        config.endpoint = "unused.invalid";
        com.aliyun.dysmsapi20170525.Client sms = new com.aliyun.dysmsapi20170525.Client(config);
        assertEquals("synthetic-id", sms.getAccessKeyId());
        assertEquals("synthetic-secret", sms.getAccessKeySecret());
        assertEquals("access_key", sms.getType());
    }

    @Test void ossJettisonResponseParserKeepsSyntheticCredentialFieldsWithoutFetch() throws Exception {
        String json = "{\"Code\":\"Success\",\"AccessKeyId\":\"synthetic-id\","
                + "\"AccessKeySecret\":\"synthetic-secret\",\"SecurityToken\":\"synthetic-token\","
                + "\"Expiration\":\"2099-01-01T00:00:00Z\"}";
        HttpResponse response = new HttpResponse();
        response.setHttpContent(json.getBytes(StandardCharsets.UTF_8), "UTF-8", com.aliyuncs.http.FormatType.JSON);
        Credentials credentials = new InstanceProfileCredentialsFetcher().parse(response);
        assertEquals("synthetic-id", credentials.getAccessKeyId());
        assertEquals("synthetic-secret", credentials.getSecretAccessKey());
        assertEquals("synthetic-token", credentials.getSecurityToken());
    }

    @Test void explicitCredentialsProduceTheFrozenPreHardeningAcs3Signature() throws Exception {
        Client credentials = new Client(new Config().setType("access_key")
                .setAccessKeyId("synthetic-id").setAccessKeySecret("synthetic-secret"));
        CredentialModel value = credentials.getCredential();
        TeaRequest request = new TeaRequest();
        request.method = "POST"; request.protocol = "https"; request.pathname = "/";
        request.headers.put("host", "unused.invalid");
        request.headers.put("content-type", "application/x-www-form-urlencoded");
        request.headers.put("x-acs-action", "SendSms");
        request.headers.put("x-acs-version", "2017-05-25");
        request.headers.put("x-acs-date", "2026-01-01T00:00:00Z");
        request.headers.put("x-acs-signature-nonce", "synthetic-fixed-nonce");
        String body = "PhoneNumbers=synthetic-phone&TemplateParam=%7B%22code%22%3A%22123456%22%7D";
        String hash = com.aliyun.openapiutil.Client.hexEncode(com.aliyun.openapiutil.Client.hash(
                body.getBytes(StandardCharsets.UTF_8), "ACS3-HMAC-SHA256"));
        assertEquals("3a354738eab57efd3d183ec5a490c617eed7aa4ed073105aba85a86f88b4901b", hash);
        request.headers.put("x-acs-content-sha256", hash);
        assertEquals("ACS3-HMAC-SHA256 Credential=synthetic-id,SignedHeaders=content-type;host;x-acs-action;"
                        + "x-acs-content-sha256;x-acs-date;x-acs-signature-nonce;x-acs-version,"
                        + "Signature=de9d3f14b3d8dde07a76002bb0b8bc08bd5621a0f801ccec4f5d196e878d6086",
                com.aliyun.openapiutil.Client.getAuthorization(request, "ACS3-HMAC-SHA256", hash,
                        value.getAccessKeyId(), value.getAccessKeySecret()));
    }

    @Test void teaSignedRequestAdapterKeepsExactUrlHeadersAndBodyWithoutSending() throws Exception {
        String json = "{\"code\":\"123456\",\"label\":\"合成\"}";
        TeaRequest input = new TeaRequest();
        input.method = "POST";
        input.body = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        input.headers.put("Content-Type", "application/json; charset=utf-8");
        input.headers.put("Authorization", "ACS3-HMAC-SHA256 Credential=synthetic-id,Signature=fixture");
        Request request = new OkRequestBuilder(new Request.Builder())
                .url(new URL("https://unused.invalid/sms?name=a%20b&value=%2B"))
                .header(input.headers).buildRequest(input);
        assertEquals("POST", request.method());
        assertEquals("https://unused.invalid/sms?name=a%20b&value=%2B", request.url().toString());
        assertEquals(input.headers.get("Authorization"), request.header("Authorization"));
        assertEquals("application/json; charset=utf-8", request.header("Content-Type"));
        Buffer bytes = new Buffer();
        assertNotNull(request.body()); request.body().writeTo(bytes);
        assertEquals(json, bytes.readUtf8());
    }
}
