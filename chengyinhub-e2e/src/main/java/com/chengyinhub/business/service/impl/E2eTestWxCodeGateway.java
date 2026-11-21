package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.common.utils.StringUtils;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * TEST-only WxACode QR renderer: generates real scannable ZXing PNG bytes encoding the target mini-program
 * page and signed scene when WeChat API credentials are not provisioned on TEST environments.
 * Loaded only with profile {@code e2e-test} AND {@code chengyin.e2e.test-providers=true}.
 */
@Service
@Profile("e2e-test")
@ConditionalOnProperty(name = "chengyin.e2e.test-providers", havingValue = "true")
public class E2eTestWxCodeGateway implements PlayerWxacodeServiceImpl.WxCodeGateway
{
    private static final Logger log = LoggerFactory.getLogger(E2eTestWxCodeGateway.class);

    public E2eTestWxCodeGateway()
    {
        log.warn("[e2e-test] TEST-only WxACode gateway active: rendering local ZXing PNG codes");
    }

    @Override
    public PlayerWxacodeServiceImpl.WxCodeResponse generate(String accessToken, String requestJson)
    {
        JSONObject req = JSON.parseObject(StringUtils.isEmpty(requestJson) ? "{}" : requestJson);
        String page = req == null ? "pages/index/index" : StringUtils.defaultIfBlank(req.getString("page"), "pages/index/index");
        String scene = req == null ? "" : StringUtils.defaultIfBlank(req.getString("scene"), "");
        int width = req != null && req.getInteger("width") != null ? req.getInteger("width") : 430;
        if (width < 128 || width > 1280)
        {
            width = 430;
        }
        String content = scene.isEmpty() ? page : page + "?scene=" + scene;
        try
        {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, width, width, hints);
            BufferedImage image = new BufferedImage(width, width, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < width; x++)
            {
                for (int y = 0; y < width; y++)
                {
                    image.setRGB(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", out);
            return new PlayerWxacodeServiceImpl.WxCodeResponse("image/png", out.toByteArray());
        }
        catch (Exception e)
        {
            throw new IllegalStateException("TEST 个人码渲染失败", e);
        }
    }
}
