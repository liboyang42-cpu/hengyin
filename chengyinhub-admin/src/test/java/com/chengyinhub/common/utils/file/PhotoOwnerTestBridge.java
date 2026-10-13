package com.chengyinhub.common.utils.file;

import com.alibaba.fastjson2.JSONObject;
import com.aliyun.oss.OSSClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * C6 测试桥:让控制器侧用例用同一入口验证「当前身份可用」;桥接真实生产实现,业务断言只在用例里。
 */
public final class PhotoOwnerTestBridge
{
    private PhotoOwnerTestBridge() { }

    public static void verifyForMember(String picUrl, OSSClient client, String bucket, Long memberId)
    {
        FileOssUploadUtils.verifyUploadedPhoto(picUrl, client, bucket, memberId);
    }

    public static JSONObject uploadWithOwner(MultipartFile file, OSSClient client, String bucket,
                                             String memberId, String bizType) throws IOException
    {
        return FileOssUploadUtils.upload(file, false, client, bucket, memberId, bizType);
    }
}
