package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.config.WxPayConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import javax.imageio.*;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class WxImageSecurityCopyTest {
    @Test void largeStaticImageIsReviewedWithinOfficialBoundsWithoutReplacingTheOriginal() throws Exception {
        BufferedImage original=new BufferedImage(2400,1600,BufferedImage.TYPE_INT_RGB);
        byte[] bytes=encode(original,"png"); MockMultipartFile file=new MockMultipartFile("file","original.png","image/png",bytes);
        WxImageSecurityCopy copy=WxImageSecurityCopy.prepare(file);
        BufferedImage review=ImageIO.read(new ByteArrayInputStream(copy.getBytes()));
        assertEquals(750,review.getWidth()); assertEquals(500,review.getHeight());
        assertTrue(copy.getBytes().length<=1_000_000); assertEquals("image/jpeg",copy.getContentType());
        assertArrayEquals(bytes,file.getBytes());
        assertEquals(2400,ImageIO.read(file.getInputStream()).getWidth());
    }

    @Test void portraitAndTransparentNoiseRemainBoundedAndPreserveTransparency() throws Exception {
        BufferedImage image=new BufferedImage(800,1600,BufferedImage.TYPE_INT_ARGB);
        Random random=new Random(42);
        for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) image.setRGB(x,y,random.nextInt());
        MockMultipartFile file=new MockMultipartFile("file","alpha.png","image/png",encode(image,"png"));
        WxImageSecurityCopy copy=WxImageSecurityCopy.prepare(file);
        BufferedImage review=ImageIO.read(new ByteArrayInputStream(copy.getBytes()));
        assertTrue(review.getWidth()<=750); assertTrue(review.getHeight()<=1334);
        assertTrue(copy.getBytes().length<=1_000_000); assertTrue(review.getColorModel().hasAlpha());
        assertEquals("image/png",copy.getContentType());
        assertTrue(Math.abs((double)review.getWidth()/review.getHeight()-0.5)<0.003);
    }

    @Test void animatedGifIsExplicitlyRefusedInsteadOfReviewingOnlyItsFirstFrame() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); ImageWriter writer=ImageIO.getImageWritersByFormatName("gif").next();
        try(ImageOutputStream output=ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output); writer.prepareWriteSequence(null);
            for(int n=0;n<2;n++) writer.writeToSequence(new IIOImage(new BufferedImage(20,20,BufferedImage.TYPE_INT_RGB),null,null),null);
            writer.endWriteSequence();
        } finally {writer.dispose();}
        UploadSecurityUnavailableException failure=assertThrows(UploadSecurityUnavailableException.class,
                ()->WxImageSecurityCopy.prepare(new MockMultipartFile("file","animated.gif","image/gif",bytes.toByteArray())));
        assertTrue(failure.getMessage().contains("静态图"));
    }

    @Test void invalidImageCannotBecomeAnUncheckedCopyOrReachTheProvider() throws Exception {
        StubService service=new StubService("{\"errcode\":0}");
        MockMultipartFile file=new MockMultipartFile("file","broken.jpg","image/jpeg",new byte[]{1,2,3});
        assertThrows(UploadSecurityUnavailableException.class,()->ReflectionTestUtils.invokeMethod(service,"verifyContentSecurity",file));
        assertEquals(0,service.connections);
    }

    @Test void actualUploadReviewSendsTheBoundedCopyAndKeepsRiskAndUnavailableFailClosed() throws Exception {
        byte[] original=encode(new BufferedImage(2200,1000,BufferedImage.TYPE_INT_RGB),"png");
        MockMultipartFile file=new MockMultipartFile("file","original.png","image/png",original);
        StubService safe=new StubService("{\"errcode\":0}");
        assertDoesNotThrow(()->ReflectionTestUtils.invokeMethod(safe,"verifyContentSecurity",file));
        String multipart=new String(safe.output.toByteArray(),StandardCharsets.ISO_8859_1);
        assertTrue(multipart.contains("filename=\"security.jpg\"")); assertTrue(multipart.contains("Content-Type: image/jpeg"));
        assertFalse(multipart.contains("filename=\"original.png\"")); assertArrayEquals(original,file.getBytes());
        assertThrows(UploadRejectedException.class,()->ReflectionTestUtils.invokeMethod(new StubService("{\"errcode\":87014}"),"verifyContentSecurity",file));
        assertThrows(UploadSecurityUnavailableException.class,()->ReflectionTestUtils.invokeMethod(new StubService("{\"errcode\":45009}"),"verifyContentSecurity",file));
    }

    private byte[] encode(BufferedImage image,String format) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); assertTrue(ImageIO.write(image,format,bytes)); return bytes.toByteArray();
    }

    private static class StubService extends AppUploadService {
        private final String response;
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        int connections;
        StubService(String response) {
            this.response=response; WxPayConfig config=new WxPayConfig();
            ReflectionTestUtils.setField(config,"secCheckEnabled",true);
            ReflectionTestUtils.setField(config,"miniAppId","wx-fixture"); ReflectionTestUtils.setField(config,"miniSecret","fixture-secret");
            ReflectionTestUtils.setField(this,"wxConfig",config);
        }
        @Override String getWxAccessToken(String appId,String secret){return "fixture-token";}
        @Override HttpURLConnection openImageSecurityConnection(String accessToken) throws IOException {
            connections++;
            return new HttpURLConnection(new URL("http://127.0.0.1/")) {
                @Override public OutputStream getOutputStream(){return output;}
                @Override public int getResponseCode(){return 200;}
                @Override public InputStream getInputStream(){return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8));}
                @Override public void disconnect(){}
                @Override public boolean usingProxy(){return false;}
                @Override public void connect(){}
            };
        }
    }
}
