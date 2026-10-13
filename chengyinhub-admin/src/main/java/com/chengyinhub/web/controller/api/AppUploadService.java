package com.chengyinhub.web.controller.api;

import com.alibaba.fastjson2.JSONObject;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.ServiceException;
import com.chengyinhub.common.config.RuoYiConfig;
import com.chengyinhub.common.config.WxPayConfig;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.exception.file.FileNameLengthLimitExceededException;
import com.chengyinhub.common.exception.file.FileSizeLimitExceededException;
import com.chengyinhub.common.exception.file.InvalidExtensionException;
import com.chengyinhub.common.utils.JsonUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import com.chengyinhub.common.utils.file.FileUploadUtils;
import com.chengyinhub.common.utils.file.FileUtils;
import com.chengyinhub.common.utils.http.HttpRequestException;
import com.chengyinhub.common.utils.http.HttpUtils;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** App 上传入口的内容校验、安全检查和存储编排。 */
@Service
public class AppUploadService
{
    static final String[] IMAGE_EXTENSIONS = { "jpg", "jpeg", "png", "gif" };
    // glb:显形档 AR 的 3D 模型(2026-09-23);严格校验按 MimeTypeUtils 的 glTF 2.0 文件头放行
    static final String[] OSS_EXTENSIONS = { "jpg", "jpeg", "png", "gif", "mp3", "m4a", "aac", "wav", "glb" };
    private static final int MAX_FILES = 9;
    private static final long MAX_TOTAL_SIZE = FileUploadUtils.DEFAULT_MAX_SIZE;
    private static final String WX_ACCESS_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/stable_token";
    private static final String WX_ACCESS_TOKEN_KEY = "wx:mini:access_token";

    private final OssUploader ossUploader;
    private final AftercareEvidenceUploader aftercareEvidenceUploader;
    private final ContentSecurityVerifier contentSecurityVerifier;
    private final PhotoOwnerUploader photoOwnerUploader;

    @Autowired
    private WxPayConfig wxConfig;

    @Autowired
    private RedisCache redisCache;
    @Autowired
    private com.chengyinhub.business.service.impl.AppMediaAssetService appMediaAssetService;

    public AppUploadService()
    {
        this(FileOssUploadUtils::upload, FileOssUploadUtils::uploadAftercareEvidence, null,
                (file,owner,bizType) -> isImageFile(file) ? FileOssUploadUtils.uploadWithOwner(file,owner,bizType)
                        : FileOssUploadUtils.uploadPendingWithOwner(file,owner,bizType));
    }

    AppUploadService(OssUploader ossUploader, ContentSecurityVerifier contentSecurityVerifier)
    {
        this(ossUploader, ossUploader, contentSecurityVerifier);
    }

    AppUploadService(OssUploader ossUploader, OssUploader aftercareEvidenceOssUploader,
                     ContentSecurityVerifier contentSecurityVerifier)
    {
        this(ossUploader, aftercareEvidenceOssUploader, contentSecurityVerifier,
                (file, ownerMemberId, bizType) -> ossUploader.upload(file));
    }

    AppUploadService(OssUploader ossUploader, OssUploader aftercareEvidenceOssUploader,
                     ContentSecurityVerifier contentSecurityVerifier,
                     PhotoOwnerUploader photoOwnerUploader)
    {
        this(ossUploader, (AftercareEvidenceUploader) (file, uploaderMemberId, merchantId, refundId)
                        -> aftercareEvidenceOssUploader.upload(file),
                contentSecurityVerifier, photoOwnerUploader);
    }

    /** 售后凭证 uploader 带门店+退款单:绑定与对象同一次写入(W6f),供提交与签名读取核对。 */
    AppUploadService(OssUploader ossUploader, AftercareEvidenceUploader aftercareEvidenceUploader,
                     ContentSecurityVerifier contentSecurityVerifier,
                     PhotoOwnerUploader photoOwnerUploader)
    {
        this.ossUploader = ossUploader;
        this.aftercareEvidenceUploader = aftercareEvidenceUploader;
        this.contentSecurityVerifier = contentSecurityVerifier;
        this.photoOwnerUploader = photoOwnerUploader;
    }

    /** 旧入口:不带上传者身份(后台/凭证等非小程序会员链路);普通图片归属/售后凭证绑定请走五参重载。 */
    JSONObject uploadOss(MultipartFile file, String bizType)
    {
        return uploadOss(file, bizType, null, null, null);
    }

    /**
     * 小程序/App 认证上传入口:把可信 memberId 与 bizType 在上传同一次写入绑定到对象;
     * memberId 为空(后台等非会员身份)时保持旧行为,不会伪造归属。
     * 商家售后凭证另带 merchantId/refundId,必须是调用方已按当前店员服务端校验过的门店与退款单。
     */
    JSONObject uploadOss(MultipartFile file, String bizType, Long memberId, Long merchantId, Long refundId)
    {
        MultipartFile sanitized = prepareOssUpload(file, bizType);
        verifyContentSecurity(sanitized);
        boolean pendingMedia=!isImageFile(sanitized);
        if(pendingMedia && (memberId==null || memberId<=0 || appMediaAssetService==null))
            throw new UploadRejectedException("音频和模型需登录后提交审核",null);
        try
        {
            boolean aftercareEvidence = UploadImagePolicy.MERCHANT_AFTERCARE_EVIDENCE_BIZ_TYPE.equals(bizType);
            String ownerMemberId = memberId == null || memberId <= 0 ? null : String.valueOf(memberId);
            JSONObject uploaded;
            if (aftercareEvidence)
            {
                uploaded = requireOssResult(
                        aftercareEvidenceUploader.upload(sanitized, ownerMemberId, merchantId, refundId), false);
            }
            else
            {
                uploaded = requireOssResult(photoOwnerUploader.upload(sanitized, ownerMemberId, bizType), !pendingMedia);
                if(pendingMedia) {
                    uploaded.remove("url");
                    uploaded.putAll(appMediaAssetService.stage(memberId,uploaded.getString("fileName")));
                } else if(memberId!=null && memberId>0 && appMediaAssetService!=null) {
                    uploaded.putAll(appMediaAssetService.stageApprovedImage(memberId,uploaded.getString("fileName")));
                }
            }
            uploaded.put("byteSize", sanitized.getSize());
            return uploaded;
        }
        catch (IOException | ClientException | ServiceException e)
        {
            throw new UploadStorageException("OSS文件上传失败", e);
        }
        catch (IllegalStateException e)
        {
            if (e.getMessage() != null && e.getMessage().startsWith("对象存储配置缺失:"))
            {
                throw new UploadStorageException("OSS文件上传失败", e);
            }
            throw e;
        }
    }

    /** 后台退款审核专用上传：只返回受控 object key，不返回长期 bearer URL。 */
    public ManualRefundEvidenceUpload uploadManualRefundEvidence(MultipartFile file)
    {
        try
        {
            JSONObject uploaded = uploadOss(file, UploadImagePolicy.MERCHANT_AFTERCARE_EVIDENCE_BIZ_TYPE);
            String objectKey = uploaded == null ? null : uploaded.getString("fileName");
            FileOssUploadUtils.validateAftercareEvidenceObjectKey(objectKey);
            String versionId = uploaded.getString("versionId");
            if (StringUtils.isBlank(versionId))
            {
                throw new UploadSecurityUnavailableException("对象存储版本控制不可用，凭证未受理");
            }
            try
            {
                FileOssUploadUtils.validateAftercareEvidenceVersionId(versionId);
            }
            catch (IllegalArgumentException invalidVersion)
            {
                throw new UploadSecurityUnavailableException("对象存储版本控制不可用，凭证未受理",
                        invalidVersion);
            }
            return new ManualRefundEvidenceUpload(objectKey, versionId);
        }
        catch (UploadRejectedException e)
        {
            throw new com.chengyinhub.common.exception.ServiceException(e.getMessage(), 400);
        }
        catch (UploadSecurityUnavailableException e)
        {
            throw new com.chengyinhub.common.exception.ServiceException(e.getMessage(), 503);
        }
        catch (UploadStorageException | IllegalArgumentException e)
        {
            throw new com.chengyinhub.common.exception.ServiceException("凭证上传失败", 500);
        }
    }

    /** 后台退款凭证上传内部回执；Controller 不向客户端泄露 versionId。 */
    public static final class ManualRefundEvidenceUpload
    {
        private final String objectKey;
        private final String versionId;

        public ManualRefundEvidenceUpload(String objectKey, String versionId)
        {
            this.objectKey = objectKey;
            this.versionId = versionId;
        }

        public String getObjectKey()
        {
            return objectKey;
        }

        public String getVersionId()
        {
            return versionId;
        }
    }

    String uploadImage(String filePath, MultipartFile file)
    {
        return uploadImage(filePath, file, null);
    }

    private String uploadImage(String filePath, MultipartFile file, FileUploadUtils.UploadDirectoryContext directoryContext)
    {
        MultipartFile sanitized = validateImage(file);
        try
        {
            if (directoryContext == null)
            {
                return FileUploadUtils.upload(filePath, sanitized, IMAGE_EXTENSIONS, true);
            }
            return FileUploadUtils.upload(filePath, sanitized, IMAGE_EXTENSIONS, true, directoryContext);
        }
        catch (InvalidExtensionException | FileSizeLimitExceededException | FileNameLengthLimitExceededException e)
        {
            throw new UploadRejectedException("不支持的文件类型或文件过大", e);
        }
        catch (IOException e)
        {
            throw new UploadStorageException("本地文件上传失败", e);
        }
    }

    List<String> uploadImages(String filePath, List<MultipartFile> files)
    {
        assertBatchAllowed(files);
        for (MultipartFile file : files)
        {
            validateImage(file);
        }
        List<String> fileNames = new ArrayList<String>();
        FileUploadUtils.UploadDirectoryContext directoryContext = FileUploadUtils.newUploadDirectoryContext(filePath);
        try
        {
            for (MultipartFile file : files)
            {
                fileNames.add(uploadImage(filePath, file, directoryContext));
            }
            return fileNames;
        }
        catch (RuntimeException e)
        {
            rollback(fileNames);
            directoryContext.deleteOwnedEmptyDirectories();
            throw e;
        }
    }

    private MultipartFile prepareOssUpload(MultipartFile file, String bizType)
    {
        if (file == null || file.isEmpty())
        {
            throw new UploadRejectedException("上传文件不能为空");
        }
        try
        {
            FileUploadUtils.assertAllowedStrict(file, OSS_EXTENSIONS);
            UploadImagePolicy.validate(file, bizType);
            return UploadImagePolicy.sanitizeMetadata(file, bizType);
        }
        catch (InvalidExtensionException | FileSizeLimitExceededException | FileNameLengthLimitExceededException e)
        {
            throw new UploadRejectedException("不支持的文件类型或文件过大", e);
        }
        catch (IllegalArgumentException e)
        {
            throw new UploadRejectedException(e.getMessage(), e);
        }
    }

    private MultipartFile validateImage(MultipartFile file)
    {
        if (file == null || file.isEmpty())
        {
            throw new UploadRejectedException("上传文件不能为空");
        }
        try
        {
            FileUploadUtils.assertAllowedStrict(file, IMAGE_EXTENSIONS);
            UploadImagePolicy.validate(file, null);
            return UploadImagePolicy.sanitizeMetadata(file, null);
        }
        catch (InvalidExtensionException | FileSizeLimitExceededException | FileNameLengthLimitExceededException e)
        {
            throw new UploadRejectedException("不支持的文件类型或文件过大", e);
        }
        catch (IllegalArgumentException e)
        {
            throw new UploadRejectedException(e.getMessage(), e);
        }
    }

    public void verifyImageForPublication(MultipartFile file) {
        if(!isImageFile(file))throw new UploadRejectedException("仅支持图片审核",null);
        verifyContentSecurity(file);
    }

    private void verifyContentSecurity(MultipartFile file)
    {
        if (contentSecurityVerifier != null)
        {
            contentSecurityVerifier.verify(file);
            return;
        }
        if (wxConfig == null)
        {
            throw new UploadSecurityUnavailableException("安全检查配置异常，请联系管理员");
        }
        if (!isImageFile(file)) return;
        if (!wxConfig.isSecCheckEnabled())
        {
            throw new UploadSecurityUnavailableException("安全检查服务暂不可用，请稍后重试");
        }
        String appId = wxConfig.getMiniAppId();
        String secret = wxConfig.getMiniSecret();
        if (StringUtils.isEmpty(appId) || StringUtils.isEmpty(secret))
        {
            throw new UploadSecurityUnavailableException("安全检查配置异常，请联系管理员");
        }
        String accessToken = getWxAccessToken(appId, secret);
        if (StringUtils.isEmpty(accessToken))
        {
            throw new UploadSecurityUnavailableException("安全检查服务异常，请稍后重试");
        }
        AjaxResult result = checkImageSecurity(file, accessToken);
        if (result.isSuccess())
        {
            return;
        }
        String message = (String) result.get(AjaxResult.MSG_TAG);
        if (Integer.valueOf(400).equals(result.get(AjaxResult.CODE_TAG)))
        {
            throw new UploadRejectedException(message);
        }
        throw new UploadSecurityUnavailableException(message);
    }

    String getWxAccessToken(String appId, String secret)
    {
        try
        {
            if (redisCache == null)
            {
                throw new UploadSecurityUnavailableException("安全检查服务异常，请稍后重试");
            }
            String accessToken = redisCache.getCacheObject(WX_ACCESS_TOKEN_KEY);
            if (StringUtils.isNotEmpty(accessToken))
            {
                return accessToken;
            }
            String result = requestWxAccessToken(appId, secret);
            if (StringUtils.isEmpty(result))
            {
                return null;
            }
            try
            {
                Map<String, Object> resultMap = JsonUtils.parseToMap(result);
                if (resultMap == null || !resultMap.containsKey("access_token"))
                {
                    return null;
                }
                accessToken = (String) resultMap.get("access_token");
                Integer expiresIn = (Integer) resultMap.get("expires_in");
                redisCache.setCacheObject(WX_ACCESS_TOKEN_KEY, accessToken,
                        expiresIn != null && expiresIn > 0 ? Math.max(60, expiresIn - 300) : 5400, TimeUnit.SECONDS);
                return accessToken;
            }
            catch (IllegalArgumentException e)
            {
                throw new UploadSecurityUnavailableException("安全检查服务异常，请稍后重试", e);
            }
        }
        catch (HttpRequestException e)
        {
            return null;
        }
    }

    /** HTTP 缝：测试覆写此方法注入网络异常，避免真实调用微信。 */
    String requestWxAccessToken(String appId, String secret)
    {
        Map<String, Object> credentials = new java.util.LinkedHashMap<>();
        credentials.put("grant_type", "client_credential");
        credentials.put("appid", appId);
        credentials.put("secret", secret);
        credentials.put("force_refresh", false);
        return HttpUtils.sendPost(WX_ACCESS_TOKEN_URL, JSONObject.toJSONString(credentials), "application/json");
    }

    private AjaxResult checkImageSecurity(MultipartFile file, String accessToken)
    {
        WxImageSecurityCopy reviewCopy = WxImageSecurityCopy.prepare(file);
        HttpURLConnection connection = null;
        try
        {
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            connection = openImageSecurityConnection(accessToken);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);
            try (DataOutputStream output = new DataOutputStream(connection.getOutputStream()))
            {
                output.writeBytes("--" + boundary + "\r\n");
                output.writeBytes("Content-Disposition: form-data; name=\"media\"; filename=\"" + reviewCopy.getFilename() + "\"\r\n");
                output.writeBytes("Content-Type: " + reviewCopy.getContentType() + "\r\n\r\n");
                output.write(reviewCopy.getBytes());
                output.writeBytes("\r\n--" + boundary + "--\r\n");
            }
            InputStream response = connection.getResponseCode() == 200 ? connection.getInputStream() : connection.getErrorStream();
            if (response == null)
            {
                return AjaxResult.error(503, "安全检查服务异常，请稍后重试");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response, "UTF-8")))
            {
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null)
                {
                    content.append(line);
                }
                return parseSecurityResult(content.toString());
            }
        }
        catch (IOException e)
        {
            throw new UploadSecurityUnavailableException("安全检查服务异常，请稍后重试", e);
        }
        finally
        {
            if (connection != null)
            {
                connection.disconnect();
            }
        }
    }

    HttpURLConnection openImageSecurityConnection(String accessToken) throws IOException
    {
        return (HttpURLConnection) new URL("https://api.weixin.qq.com/wxa/img_sec_check?access_token=" + accessToken).openConnection();
    }

    private AjaxResult parseSecurityResult(String response)
    {
        if (StringUtils.isEmpty(response))
        {
            return AjaxResult.error(503, "安全检查服务异常，请稍后重试");
        }
        try
        {
            Map<String, Object> result = JsonUtils.parseToMap(response);
            Integer errCode = result == null ? null : (Integer) result.get("errcode");
            if (Integer.valueOf(0).equals(errCode))
            {
                return AjaxResult.success();
            }
            if (Integer.valueOf(87014).equals(errCode))
            {
                return AjaxResult.error(400, "图片包含违法违规内容，请重新选择");
            }
            if (Integer.valueOf(40001).equals(errCode) && redisCache != null)
            {
                redisCache.deleteObject(WX_ACCESS_TOKEN_KEY);
            }
            return AjaxResult.error(503, "安全检查服务异常，请稍后重试");
        }
        catch (IllegalArgumentException e)
        {
            throw new UploadSecurityUnavailableException("安全检查服务异常，请稍后重试", e);
        }
    }

    private JSONObject requireOssResult(JSONObject result, boolean requireUrl) throws IOException
    {
        if (result == null || isBlank(result.getString("fileName"))
                || (requireUrl && isBlank(result.getString("url"))))
        {
            throw new IOException("对象存储未返回完整上传结果");
        }
        return result;
    }

    private void assertBatchAllowed(List<MultipartFile> files)
    {
        if (files == null || files.isEmpty() || files.size() > MAX_FILES)
        {
            throw new UploadRejectedException("上传文件数量超出限制");
        }
        long totalSize = 0L;
        for (MultipartFile file : files)
        {
            if (file == null || file.getSize() < 0 || file.getSize() > MAX_TOTAL_SIZE - totalSize)
            {
                throw new UploadRejectedException("上传文件总大小超出限制");
            }
            totalSize += file.getSize();
        }
    }

    private void rollback(List<String> fileNames)
    {
        String resourcePrefix = Constants.RESOURCE_PREFIX + "/";
        for (String fileName : fileNames)
        {
            if (fileName.startsWith(resourcePrefix))
            {
                FileUtils.deleteFile(RuoYiConfig.getProfile() + File.separator + fileName.substring(resourcePrefix.length()));
            }
        }
    }

    private static boolean isImageFile(MultipartFile file)
    {
        String filename = file.getOriginalFilename();
        if (filename == null)
        {
            return false;
        }
        String lower = filename.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif");
    }

    private boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }

    @FunctionalInterface
    interface OssUploader
    {
        JSONObject upload(MultipartFile file) throws IOException;
    }

    /** 认证会员上传:归属与 bizType 与对象同一次写入(生产默认 FileOssUploadUtils::uploadWithOwner)。 */
    @FunctionalInterface
    interface PhotoOwnerUploader
    {
        JSONObject upload(MultipartFile file, String ownerMemberId, String bizType) throws IOException;
    }

    /** 商家售后凭证:门店与退款单与对象同一次写入(生产默认 FileOssUploadUtils::uploadAftercareEvidence)。 */
    @FunctionalInterface
    interface AftercareEvidenceUploader
    {
        JSONObject upload(MultipartFile file, String uploaderMemberId, Long merchantId, Long refundId)
                throws IOException;
    }

    @FunctionalInterface
    interface ContentSecurityVerifier
    {
        void verify(MultipartFile file);
    }
}

final class UploadRejectedException extends RuntimeException
{
    UploadRejectedException(String message)
    {
        super(message);
    }

    UploadRejectedException(String message, Throwable cause)
    {
        super(message, cause);
    }
}

final class UploadSecurityUnavailableException extends RuntimeException
{
    UploadSecurityUnavailableException(String message)
    {
        super(message);
    }

    UploadSecurityUnavailableException(String message, Throwable cause)
    {
        super(message, cause);
    }
}

final class UploadStorageException extends RuntimeException
{
    UploadStorageException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
