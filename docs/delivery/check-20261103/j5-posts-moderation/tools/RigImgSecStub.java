package com.chengyinhub.web.controller.api;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Rig-only (never in product code). Replaces ONLY the two outbound calls of the upload-time
 * WeChat img_sec_check (token + the multipart POST) so they hit a local stub on 127.0.0.1:8095
 * whose answer (pass / risky / busy) is read from /tmp/rig-imgsec-mode. The known-bad-image
 * library check, the switch/appid gates and the result parsing stay the real AppUploadService.
 */
@Service
@Primary
public class RigImgSecStub extends AppUploadService {
    @Override
    String requestWxAccessToken(String appId, String secret) {
        return "{\"access_token\":\"rig-token\",\"expires_in\":7200}";
    }

    @Override
    HttpURLConnection openImageSecurityConnection(String accessToken) throws IOException {
        return (HttpURLConnection) new URL("http://127.0.0.1:8095/wxa/img_sec_check?access_token=" + accessToken).openConnection();
    }
}
