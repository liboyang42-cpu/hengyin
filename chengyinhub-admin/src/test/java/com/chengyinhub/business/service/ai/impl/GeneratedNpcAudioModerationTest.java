package com.chengyinhub.business.service.ai.impl;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.service.impl.AppMediaAssetService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetAddress;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeneratedNpcAudioModerationTest {
    final String vendor="https://dashscope-result.oss-cn-hangzhou.aliyuncs.com/reply.mp3";
    static class Storage extends OssCardCutoutStorage {
        byte[] bytes=new byte[]{'I','D','3',4};int downloads;int requestedLimit;boolean privateAddress;
        @Override InetAddress[] resolveMediaHost(String host) throws java.net.UnknownHostException {
            return new InetAddress[]{InetAddress.getByAddress(privateAddress?new byte[]{127,0,0,1}:new byte[]{1,1,1,1})};
        }
        @Override byte[] download(String url,int maxBytes) {downloads++;requestedLimit=maxBytes;return bytes;}
    }
    @Test void boundedVendorBytesProduceOnlyPrivateHeldAssetForActualMerchantOwner() throws Exception {
        Storage storage=new Storage();AppMediaAssetService assets=mock(AppMediaAssetService.class);
        ReflectionTestUtils.setField(storage,"mediaAssets",assets);
        String key="upload/0123456789abcdef0123456789abcdef.mp3";
        when(assets.stage(99L,key)).thenReturn(java.util.Map.of("assetId",12L,"reviewState","HOLD"));
        try(MockedStatic<FileOssUploadUtils> oss=mockStatic(FileOssUploadUtils.class)) {
            JSONObject receipt=new JSONObject();receipt.put("fileName",key);
            oss.when(()->FileOssUploadUtils.uploadPendingWithOwner(any(),eq("99"),eq("npc_reply_audio"))).thenReturn(receipt);
            assertEquals("HOLD",storage.stageGeneratedAudio(99L,vendor).get("reviewState"));
            assertEquals(1,storage.downloads);assertEquals(10*1024*1024,storage.requestedLimit);
            verify(assets).stage(99L,key);
            oss.verify(()->FileOssUploadUtils.generatePresignedUrl(anyString()),never());
            oss.verify(()->FileOssUploadUtils.generateShortLivedPresignedUrl(anyString(),anyInt()),never());
        }
    }
    @Test void untrustedOriginsPrivateDnsUnavailableOrNonAudioNeverStageOrPublish() throws Exception {
        Storage storage=new Storage();AppMediaAssetService assets=mock(AppMediaAssetService.class);
        ReflectionTestUtils.setField(storage,"mediaAssets",assets);
        try(MockedStatic<FileOssUploadUtils> oss=mockStatic(FileOssUploadUtils.class)) {
            for(String url:java.util.List.of("http://dashscope-result.oss-cn-hangzhou.aliyuncs.com/a.mp3", "https://127.0.0.1/a.mp3",
                    "https://dashscope-result.oss-cn-hangzhou.aliyuncs.com:444/a.mp3",vendor+"#fragment",
                    "https://safe.aliyuncs.com@127.0.0.1/a.mp3","https://evilaliyuncs.com/a.mp3"))
                assertThrows(ServiceException.class,()->storage.stageGeneratedAudio(99L,url));
            assertEquals(0,storage.downloads);
            storage.privateAddress=true;assertThrows(ServiceException.class,()->storage.stageGeneratedAudio(99L,vendor));
            assertEquals(0,storage.downloads);
            storage.privateAddress=false;storage.bytes=null;assertThrows(ServiceException.class,()->storage.stageGeneratedAudio(99L,vendor));
            storage.bytes="<html>bad audio</html>".getBytes();assertThrows(ServiceException.class,()->storage.stageGeneratedAudio(99L,vendor));
            oss.verifyNoInteractions();verifyNoInteractions(assets);
        }
    }
    @Test void localLinkSiteUniqueLocalAndCgnatAddressesAreNotFetchable() throws Exception {
        for(byte[] bytes:java.util.List.of(new byte[]{0,1,2,3},new byte[]{127,0,0,1},new byte[]{10,1,1,1},
                new byte[]{(byte)169,(byte)254,1,1},new byte[]{100,64,1,1}))
            assertFalse(OssCardCutoutStorage.publicMediaAddress(InetAddress.getByAddress(bytes)));
        byte[] ipv6=new byte[16];ipv6[0]=(byte)0xfc;
        assertFalse(OssCardCutoutStorage.publicMediaAddress(InetAddress.getByAddress(ipv6)));
        assertTrue(OssCardCutoutStorage.publicMediaAddress(InetAddress.getByAddress(new byte[]{1,1,1,1})));
    }
}
