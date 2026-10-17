package com.chengyinhub.web.controller.common;

import com.chengyinhub.common.config.RuoYiConfig;
import com.chengyinhub.common.constant.CacheConstants;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.framework.config.CaptchaConfig;
import com.chengyinhub.framework.config.AppMediaOutputConfiguration;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import com.chengyinhub.system.service.ISysConfigService;
import com.google.code.kaptcha.Producer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CaptchaControllerImageTest {
    private final RedisCache cache = mock(RedisCache.class);
    private final ISysConfigService config = mock(ISysConfigService.class);
    private String originalType;

    @BeforeEach void rememberType() {
        originalType = RuoYiConfig.getCaptchaType();
        when(config.selectCaptchaEnabled()).thenReturn(true);
    }

    @AfterEach void restoreType() {
        new RuoYiConfig().setCaptchaType(originalType);
    }

    private CaptchaController controller(Producer chars, Producer math) {
        CaptchaController controller = new CaptchaController();
        ReflectionTestUtils.setField(controller, "captchaProducer", chars);
        ReflectionTestUtils.setField(controller, "captchaProducerMath", math);
        ReflectionTestUtils.setField(controller, "redisCache", cache);
        ReflectionTestUtils.setField(controller, "configService", config);
        return controller;
    }

    private void assertReadableJpeg(AjaxResult result) throws Exception {
        assertEquals(200, result.get("code"));
        byte[] bytes = Base64.getDecoder().decode((String) result.get("img"));
        assertTrue(bytes.length > 4);
        assertEquals(0xff, bytes[0] & 0xff);
        assertEquals(0xd8, bytes[1] & 0xff);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(decoded);
        assertEquals(160, decoded.getWidth());
        assertEquals(60, decoded.getHeight());
        assertFalse(decoded.getColorModel().hasAlpha());
        verify(cache).setCacheObject(eq(CacheConstants.CAPTCHA_CODE_KEY + result.get("uuid")),
                anyString(), eq(Constants.CAPTCHA_EXPIRATION), eq(TimeUnit.MINUTES));
    }

    @ParameterizedTest @ValueSource(strings = {"math", "char"})
    void actualConfiguredProducersReturnReadableImagesAndCacheTheChallenge(String type) throws Exception {
        new RuoYiConfig().setCaptchaType(type);
        CaptchaConfig producers = new CaptchaConfig();
        assertReadableJpeg(controller(producers.getKaptchaBean(), producers.getKaptchaBeanMath())
                .getCode(new MockHttpServletResponse()));
    }

    @ParameterizedTest @ValueSource(strings = {"math", "char"})
    void actualHttpSerializationKeepsTheGeneratedCaptchaWithTheMediaGateEnabled(String type) throws Exception {
        new RuoYiConfig().setCaptchaType(type);
        CaptchaConfig producers = new CaptchaConfig();
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        AppMediaReferenceGuard media = mock(AppMediaReferenceGuard.class);
        new AppMediaOutputConfiguration(media).reviewedMediaOutput().customize(builder);
        com.fasterxml.jackson.databind.ObjectMapper mapper = builder.build();
        String body = MockMvcBuilders.standaloneSetup(controller(producers.getKaptchaBean(), producers.getKaptchaBeanMath()))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build()
                .perform(get("/captchaImage").servletPath("/captchaImage"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode result = mapper.readTree(body);
        assertEquals(200, result.path("code").asInt());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(result.path("img").asText())));
        assertNotNull(image);
        assertEquals(160, image.getWidth());
        assertEquals(60, image.getHeight());
        verifyNoInteractions(media);
    }

    @Test void transparentCaptchaCannotBeSilentlyEncodedAsAnEmptyJpeg() throws Exception {
        BufferedImage argb = new BufferedImage(160, 60, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream originalEncoding = new ByteArrayOutputStream();
        assertFalse(ImageIO.write(argb, "jpg", originalEncoding));
        assertEquals(0, originalEncoding.size());
        Producer producer = mock(Producer.class);
        when(producer.createText()).thenReturn("Ab32");
        when(producer.createImage("Ab32")).thenReturn(argb);
        new RuoYiConfig().setCaptchaType("char");
        assertReadableJpeg(controller(producer, mock(Producer.class)).getCode(new MockHttpServletResponse()));
    }

    @Test void rejectedEncoderDoesNotCacheAnUnusableChallenge() throws Exception {
        Producer producer = mock(Producer.class);
        when(producer.createText()).thenReturn("Ab32");
        when(producer.createImage("Ab32")).thenReturn(new BufferedImage(160, 60, BufferedImage.TYPE_INT_RGB));
        new RuoYiConfig().setCaptchaType("char");
        try (MockedStatic<ImageIO> encoder = mockStatic(ImageIO.class)) {
            encoder.when(() -> ImageIO.write(any(BufferedImage.class), eq("jpg"), any(OutputStream.class)))
                    .thenReturn(false);
            AjaxResult result = controller(producer, mock(Producer.class)).getCode(new MockHttpServletResponse());
            assertNotEquals(200, result.get("code"));
            assertFalse(result.containsKey("img"));
            assertFalse(result.containsKey("uuid"));
            verifyNoInteractions(cache);
        }
    }

    @Test void missingImageDoesNotIssueOrCacheAChallenge() throws Exception {
        Producer producer = mock(Producer.class);
        when(producer.createText()).thenReturn("Ab32");
        new RuoYiConfig().setCaptchaType("char");
        AjaxResult result = controller(producer, mock(Producer.class)).getCode(new MockHttpServletResponse());
        assertNotEquals(200, result.get("code"));
        assertFalse(result.containsKey("uuid"));
        verifyNoInteractions(cache);
    }

    @Test void disabledCaptchaKeepsTheExistingNoImageContract() throws Exception {
        when(config.selectCaptchaEnabled()).thenReturn(false);
        Producer chars = mock(Producer.class), math = mock(Producer.class);
        AjaxResult result = controller(chars, math).getCode(new MockHttpServletResponse());
        assertEquals(200, result.get("code"));
        assertEquals(false, result.get("captchaEnabled"));
        assertFalse(result.containsKey("img"));
        verifyNoInteractions(chars, math, cache);
    }
}
