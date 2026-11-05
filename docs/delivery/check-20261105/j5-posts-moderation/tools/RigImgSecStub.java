package com.chengyinhub.web.controller.api;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.common.config.RuoYiConfig;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.UUID;

/**
 * Rig-only (never in product code). Harness seams around the REAL AppUploadService:
 * 1) the upload-time WeChat img_sec_check (token + multipart POST) goes to a local stub on 127.0.0.1:8095
 *    whose answer (pass / risky / busy) is read from /tmp/rig-imgsec-mode;
 * 2) the OSS put (no OSS in the rig) writes the already-checked bytes under the local profile dir as
 *    rigoss/<hex>.jpg and returns the object key upload/<hex>.jpg the product expects.
 * The known-bad-image library check, metadata strip, switch/appid gates, result parsing and media-asset
 * staging all stay the product code. contentSecurityVerifier stays null so the real WeChat path runs.
 */
@Service
@Primary
public class RigImgSecStub extends AppUploadService {
    public RigImgSecStub() {
        super(f -> { throw new IOException("rig: legacy oss path not stubbed"); },
              (AftercareEvidenceUploader) (f, u, m, r) -> { throw new IOException("rig: aftercare oss not stubbed"); },
              null,
              (file, owner, bizType) -> {
                  String name = file.getOriginalFilename() == null ? "x.jpg" : file.getOriginalFilename().toLowerCase();
                  String ext = name.endsWith(".png") ? "png" : name.endsWith(".gif") ? "gif" : "jpg";
                  String hex = UUID.randomUUID().toString().replace("-", "");
                  File dir = new File(RuoYiConfig.getProfile(), "rigoss");
                  dir.mkdirs();
                  Files.write(new File(dir, hex + "." + ext).toPath(), file.getBytes());
                  JSONObject o = new JSONObject();
                  o.put("fileName", "upload/" + hex + "." + ext);
                  o.put("url", "https://rigbucket.oss-rig.invalid/upload/" + hex + "." + ext);
                  return o;
              });
    }

    @Override
    String requestWxAccessToken(String appId, String secret) {
        return "{\"access_token\":\"rig-token\",\"expires_in\":7200}";
    }

    @Override
    HttpURLConnection openImageSecurityConnection(String accessToken) throws IOException {
        return (HttpURLConnection) new URL("http://127.0.0.1:8095/wxa/img_sec_check?access_token=" + accessToken).openConnection();
    }
}
