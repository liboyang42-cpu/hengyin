package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopGroupVerifyMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.IQrcodeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CU-C-78:团码签发接口的「有码图才算成功」契约。
 *
 * <p>原来 {@code issue()} 把出图失败降级成「记一条 error 日志 + 仍然 success(data)」,
 * 于是 {@code qrcodeUrl} 为空也回 200；前端只看 {@code data.code} 就进 ready 态,
 * 码区只剩一句「出码失败」,页面却以为已出码。触发条件(隔离环境缺
 * {@code CHENGYIN_OSS_ENDPOINT})只是表象,错的是这个回执契约本身。
 *
 * <p>负控:把 catch 改回「吞掉异常后继续返回 success」,或删掉空值兜底,
 * {@link #qrcodeGenerationFailureIsReportedAsFailure()} /
 * {@link #blankQrcodeUrlIsNotReportedAsSuccess()} 立刻变红。
 */
class GroupCodeIssueQrcodeContractTest {

    private static final Long TOPIC = 990030L;
    private static final Long CLUB = 7002L;
    private static final Long ACTIVITY = 30L;
    private static final long ISSUER = 9008L;

    private ICmsActivityService activityService;
    private CoopOrderMapper orders;
    private IQrcodeService qrcode;

    @BeforeEach
    void setUp() {
        activityService = mock(ICmsActivityService.class);
        orders = mock(CoopOrderMapper.class);
        qrcode = mock(IQrcodeService.class);

        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activeActivity());
        // 自办场次:俱乐部靠一张生效的 to_type='club' 合作单拿到带团权
        CoopOrder order = new CoopOrder();
        order.setTopicId(TOPIC);
        order.setToType("club");
        order.setToId(CLUB);
        when(orders.selectEffectiveOrdersByTopicId(TOPIC)).thenReturn(Collections.singletonList(order));
    }

    @Test
    void endedActivityCannotIssueGroupCodeOrAttemptImageUpload() {
        CmsActivity ended = activeActivity();
        ended.setEndDate(new Date(System.currentTimeMillis() - 1L));
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(ended);
        when(qrcode.generateInlineQrcode(anyString(), anyInt(), anyInt())).thenReturn("https://oss.test/qr/group.png");
        AjaxResult result = service().issue(ACTIVITY, ISSUER);
        assertEquals(500, result.get("code"));
        assertEquals("该场次不可带队核销", result.get("msg"));
        verifyNoInteractions(qrcode);
    }

    @Test
    void groupCodeIssuedEarlierCannotBeRedeemedAfterActivityEnds() {
        CmsActivity activity = activeActivity();
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(qrcode.generateInlineQrcode(anyString(), anyInt(), anyInt())).thenReturn("https://oss.test/qr/group.png");
        GroupCodeServiceImpl service = service();
        AjaxResult issued = service.issue(ACTIVITY, ISSUER);
        assertEquals(200, issued.get("code"));
        String code = (String) ((Map<?, ?>) issued.get("data")).get("code");
        activity.setEndDate(new Date(System.currentTimeMillis() - 1L));
        AjaxResult redeemed = service.redeem(code, 9002L);
        assertEquals(500, redeemed.get("code"));
        assertEquals("团码场次无效", redeemed.get("msg"));
    }

    @Test
    void qrcodeGenerationFailureIsReportedAsFailure() {
        when(qrcode.generateInlineQrcode(anyString(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("二维码上传OSS失败"));

        AjaxResult result = service().issue(ACTIVITY, ISSUER);

        assertEquals(500, result.get("code"), "出图失败必须是失败回执,不能是 200");
        assertTrue(String.valueOf(result.get("msg")).contains("二维码"), "要说清是二维码没生成出来");
        assertNull(result.get("data"), "失败时不许再下发只能看不能扫的 code");
    }

    @Test
    void blankQrcodeUrlIsNotReportedAsSuccess() {
        // 出码服务不抛异常、但没给可用地址(OSS 返回体缺 url)同样是不可用
        when(qrcode.generateInlineQrcode(anyString(), anyInt(), anyInt())).thenReturn(null);

        AjaxResult result = service().issue(ACTIVITY, ISSUER);

        assertEquals(500, result.get("code"), "空 qrcodeUrl 不许算成功");
        assertNull(result.get("data"));
    }

    @Test
    void successfulIssueStillCarriesCodeAndQrcodeUrl() {
        when(qrcode.generateInlineQrcode(anyString(), anyInt(), anyInt())).thenReturn("https://oss.test/qr/group.png");

        AjaxResult result = service().issue(ACTIVITY, ISSUER);

        assertEquals(200, result.get("code"));
        Map<?, ?> data = (Map<?, ?>) result.get("data");
        assertNotNull(data.get("code"));
        assertEquals("https://oss.test/qr/group.png", data.get("qrcodeUrl"));
    }

    @Test
    void ticketCouponAndNodePayloadsKeepReadableImages() throws Exception {
        QrcodeServiceImpl renderer = new QrcodeServiceImpl();
        for (String type : new String[]{"activity", "topic", "coupon", "game_checkin_222"}) {
            for (long nonce = 0; nonce < 32; nonce++) {
                String code = com.chengyinhub.common.utils.VerifyDynCodeUtil.issue(
                        30L, type, 1791000000000L, 60000L, nonce, "test-secret");
                String imageUri = renderer.generateInlineQrcode(code, 300, 300);
                byte[] png = java.util.Base64.getDecoder().decode(imageUri.substring(imageUri.indexOf(',') + 1));
                java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
                int[] pixels = image.getRGB(0, 0, 300, 300, null, 0, 300);
                com.google.zxing.BinaryBitmap bitmap = new com.google.zxing.BinaryBitmap(
                        new com.google.zxing.common.HybridBinarizer(new com.google.zxing.RGBLuminanceSource(300, 300, pixels)));
                assertEquals(code, new com.google.zxing.MultiFormatReader().decode(bitmap).getText());
            }
        }
    }

    @Test
    void signedGroupImagesRemainReadableAcrossNonceMasks() throws Exception {
        QrcodeServiceImpl renderer = new QrcodeServiceImpl();
        long issuedAt = 1791000000000L;
        // 固定样本中 7/44/77/97/127 原来无法解码；不依赖随机碰到坏掩码。
        for (long nonce = 1000000000000000L; nonce < 1000000000000200L; nonce++) {
            String code = com.chengyinhub.common.utils.VerifyDynCodeUtil.issue(
                    ACTIVITY, "group_v2_30_9008", issuedAt, 60000L, nonce, "test-secret");
            String imageUri = renderer.generateInlineQrcode(code, 300, 300);
            byte[] png = java.util.Base64.getDecoder().decode(imageUri.substring(imageUri.indexOf(',') + 1));
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
            int[] pixels = image.getRGB(0, 0, 300, 300, null, 0, 300);
            com.google.zxing.BinaryBitmap bitmap = new com.google.zxing.BinaryBitmap(
                    new com.google.zxing.common.HybridBinarizer(new com.google.zxing.RGBLuminanceSource(300, 300, pixels)));
            assertEquals(code, new com.google.zxing.MultiFormatReader().decode(bitmap).getText(),
                    "签发图片必须保留原团码内容，样本 " + (nonce - 1000000000000000L));
        }
    }

    @Test
    void actualIssuedImageDecodesToTheSignedGroupCodeWithoutObjectStorage() throws Exception {
        qrcode = new QrcodeServiceImpl();
        AjaxResult result = service().issue(ACTIVITY, ISSUER);
        assertEquals(200, result.get("code"));
        Map<?, ?> data = (Map<?, ?>) result.get("data");
        String imageUri = (String) data.get("qrcodeUrl");
        assertTrue(imageUri.startsWith("data:image/png;base64,"));
        byte[] png = java.util.Base64.getDecoder().decode(imageUri.substring(imageUri.indexOf(',') + 1));
        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        assertEquals(300, image.getWidth());
        int[] pixels = image.getRGB(0, 0, 300, 300, null, 0, 300);
        com.google.zxing.BinaryBitmap bitmap = new com.google.zxing.BinaryBitmap(
                new com.google.zxing.common.HybridBinarizer(new com.google.zxing.RGBLuminanceSource(300, 300, pixels)));
        assertEquals(data.get("code"), new com.google.zxing.MultiFormatReader().decode(bitmap).getText());
        com.chengyinhub.common.utils.VerifyDynCodeUtil.Result verified =
                com.chengyinhub.common.utils.VerifyDynCodeUtil.verify((String) data.get("code"),
                        System.currentTimeMillis(), "test-secret");
        assertTrue(verified.valid);
        assertEquals("group_v2_30_9008", verified.type);
    }

    private GroupCodeServiceImpl service() {
        GroupCodeServiceImpl service = new GroupCodeServiceImpl(activityService,
                mock(IClubAccessService.class), orders, mock(CoopGroupVerifyMapper.class), qrcode, "test-secret");
        // 走合作单那条判据,主题表不会被读到,但字段不能是 null(构造器不注入它)
        ReflectionTestUtils.setField(service, "cmsTopicMapper", mock(CmsTopicMapper.class));
        return service;
    }

    private CmsActivity activeActivity() {
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        activity.setClubId(CLUB);
        activity.setDelFlag(0);
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        return activity;
    }
}
