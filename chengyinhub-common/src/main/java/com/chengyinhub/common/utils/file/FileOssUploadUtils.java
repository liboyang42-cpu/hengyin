package com.chengyinhub.common.utils.file;
import com.alibaba.fastjson2.JSONObject;
import com.aliyun.oss.ClientConfiguration;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSErrorCode;
import com.aliyun.oss.OSSClient;
import com.aliyun.oss.common.comm.Protocol;
import com.aliyun.oss.ServiceException;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.CannedAccessControlList;
import com.aliyun.oss.model.GetObjectRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectResult;
import com.chengyinhub.common.config.RuoYiConfig;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.exception.file.FileNameLengthLimitExceededException;
import com.chengyinhub.common.exception.file.FileSizeLimitExceededException;
import com.chengyinhub.common.exception.file.InvalidExtensionException;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.spring.SpringUtils;
import com.chengyinhub.common.utils.uuid.IdUtils;
import com.chengyinhub.common.utils.uuid.Seq;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URI;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 文件上传工具类
 *
 * @author ruoyi
 */
public class FileOssUploadUtils {
    private static final Logger log = LoggerFactory.getLogger(FileOssUploadUtils.class);

    private static final String OSS_ENDPOINT_ENV = "CHENGYIN_OSS_ENDPOINT";
    private static final String OSS_ACCESS_KEY_ID_ENV = "CHENGYIN_OSS_ACCESS_KEY_ID";
    private static final String OSS_ACCESS_KEY_SECRET_ENV = "CHENGYIN_OSS_ACCESS_KEY_SECRET";
    private static final String OSS_BUCKET_NAME_ENV = "CHENGYIN_OSS_BUCKET_NAME";
    private static final Pattern AFTERCARE_EVIDENCE_OBJECT_KEY = Pattern.compile(
            "upload/merchant-aftercare-evidence/[0-9a-f]{32}\\.(?:jpg|jpeg|png|gif)");
    private static final Pattern AFTERCARE_EVIDENCE_VERSION_ID = Pattern.compile(
            "[A-Za-z0-9._~+/=-]{1,256}");
    /**
     * 普通上传(/api/common/uploadOSS)生成的照片对象键：服务端随机 uuid + 已验证图片扩展名。
     * 拍照完成只认这一种来源，售后凭证/音频/其它业务前缀都不在拍照合同内。
     */
    private static final Pattern UPLOADED_PHOTO_OBJECT_KEY = Pattern.compile(
            "upload/[0-9a-f]{32}\\.(?:jpg|jpeg|png|gif)");
    /**
     * 上传归属与业务类型写在 OSS 对象 user metadata(x-oss-meta-*)里,与 putObject 同一次写入;
     * 只有持桶写权限的服务端能写,photo 首次提交时用它核对「当前登录身份可用」。
     */
    static final String OWNER_MEMBER_METADATA_KEY = "member-id";
    static final String UPLOAD_BIZ_TYPE_METADATA_KEY = "biz-type";
    static final String AFTERCARE_MERCHANT_METADATA_KEY = "merchant-id";
    static final String AFTERCARE_REFUND_METADATA_KEY = "refund-id";
    private static final String AFTERCARE_EVIDENCE_BIZ_TYPE = "merchant_aftercare_evidence";
    /**
     * 默认大小 50M
     */
    public static final long DEFAULT_MAX_SIZE = 50 * 1024 * 1024L;

    /**
     * 默认的文件名最大长度 100
     */
    public static final int DEFAULT_FILE_NAME_LENGTH = 100;

    /**
     * 默认上传的地址
     */
    private static String defaultBaseDir = RuoYiConfig.getProfile();

    //过期时间（秒）
    private static int expirationInSeconds = 300;

    public static void setDefaultBaseDir(String defaultBaseDir) {
        FileOssUploadUtils.defaultBaseDir = defaultBaseDir;
    }

    public static String getDefaultBaseDir() {
        return defaultBaseDir;
    }

    /**
     * 以默认配置进行文件上传
     *
     * @param file 上传的文件
     * @return 文件名称
     * @throws Exception
     */
    public static final JSONObject upload(MultipartFile file) throws IOException {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        return upload(file, false);
    }

    /**
     * 售后凭证使用独立对象前缀，使服务端能拒绝从普通长期 URL 上传通道取得的 key。
     * 商家上传时服务端已校验过的门店(merchantId)与退款单(refundId)与对象同一次 putObject 写入
     * user metadata(W6f:一张凭证只能用于一张退款单),提交/读取据此核对;上传者 member-id 仅留痕。
     */
    public static JSONObject uploadAftercareEvidence(MultipartFile file, String uploaderMemberId,
                                                     Long merchantId, Long refundId) throws IOException {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        OSSClient ossClient = createOssClient();
        try {
            return uploadAftercareEvidence(file, uploaderMemberId, merchantId, refundId,
                    ossClient, requiredEnv(OSS_BUCKET_NAME_ENV));
        } finally {
            ossClient.shutdown();
        }
    }

    static JSONObject uploadAftercareEvidence(MultipartFile file, String uploaderMemberId, Long merchantId,
                                              Long refundId, OSSClient ossClient, String bucketName)
            throws IOException {
        ObjectMetadata metadata = ownerMetadata(uploaderMemberId, AFTERCARE_EVIDENCE_BIZ_TYPE);
        if (merchantId != null) metadata.addUserMetadata(AFTERCARE_MERCHANT_METADATA_KEY, String.valueOf(merchantId));
        if (refundId != null) metadata.addUserMetadata(AFTERCARE_REFUND_METADATA_KEY, String.valueOf(refundId));
        return upload(file, true, ossClient, bucketName, metadata);
    }

    /**
     * 带归属的普通上传:认证身份 memberId 与 bizType 与对象在**同一次 putObject** 写入
     * OSS user metadata(x-oss-meta-member-id / x-oss-meta-biz-type)。只有持桶写权限的服务端
     * 能写这两个事实,photo 首次提交据此核对「当前登录身份可用」,不新建表/依赖。
     */
    public static JSONObject uploadWithOwner(MultipartFile file, String ownerMemberId, String bizType)
            throws IOException {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        OSSClient ossClient = "COMMUNITY_POST".equals(bizType)
                ? createCommunityOssClient() : createOssClient();
        try {
            return upload(file, false, ossClient, requiredEnv(OSS_BUCKET_NAME_ENV), ownerMemberId, bizType);
        } finally {
            ossClient.shutdown();
        }
    }

    public static JSONObject uploadPendingWithOwner(MultipartFile file,String ownerMemberId,String bizType) throws IOException {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (StringUtils.isBlank(ownerMemberId)) throw new IllegalArgumentException("待审媒体缺少作者归属");
        OSSClient client=createOssClient();
        try {
            return uploadPendingWithOwner(file,ownerMemberId,bizType,client,requiredEnv(OSS_BUCKET_NAME_ENV));
        } finally { client.shutdown(); }
    }

    static JSONObject uploadPendingWithOwner(MultipartFile file,String ownerMemberId,String bizType,OSSClient client,String bucket) throws IOException {
        if(StringUtils.isBlank(ownerMemberId))throw new IllegalArgumentException("待审媒体缺少作者归属");
        return upload(file,false,client,bucket,ownerMetadata(ownerMemberId,bizType),false);
    }

    private static JSONObject upload(MultipartFile file, boolean aftercareEvidence) throws IOException {
        OSSClient ossClient = createOssClient();
        try {
            return upload(file, aftercareEvidence, ossClient, requiredEnv(OSS_BUCKET_NAME_ENV));
        } finally {
            ossClient.shutdown();
        }
    }

    /**
     * SDK 缝：上传核心只依赖传入的 OSSClient 与桶名，生产入口用真实配置调用；
     * 测试可用本地替身在同一段真实 key 生成/putObject/预签名逻辑上跑通上传链。
     */
    static JSONObject upload(MultipartFile file, boolean aftercareEvidence, OSSClient ossClient,
                              String bucketName) throws IOException {
        return upload(file, aftercareEvidence, ossClient, bucketName, null, null);
    }

    static JSONObject upload(MultipartFile file, boolean aftercareEvidence, OSSClient ossClient,
                              String bucketName, String ownerMemberId, String bizType) throws IOException {
        return upload(file, aftercareEvidence, ossClient, bucketName, ownerMetadata(ownerMemberId, bizType));
    }

    private static JSONObject upload(MultipartFile file, boolean aftercareEvidence, OSSClient ossClient,
                                     String bucketName, ObjectMetadata ownerMetadata) throws IOException {
        return upload(file,aftercareEvidence,ossClient,bucketName,ownerMetadata,true);
    }
    private static JSONObject upload(MultipartFile file, boolean aftercareEvidence, OSSClient ossClient,
                                     String bucketName, ObjectMetadata ownerMetadata,boolean signPublic) throws IOException {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        JSONObject jsobj1 = new JSONObject();
        try {
            String fileRelName = aftercareEvidence
                    ? createAftercareEvidenceObjectKey(file) : createObjectKey(file);

            // Set the ACL in the upload itself; inheriting a public bucket would expose pending media.
            if (ownerMetadata == null) ownerMetadata = new ObjectMetadata();
            ownerMetadata.setObjectAcl(CannedAccessControlList.Private);
            PutObjectResult result;
            try (InputStream input = file.getInputStream()) {
                result = ownerMetadata == null
                        ? ossClient.putObject(bucketName, fileRelName, input)
                        : ossClient.putObject(bucketName, fileRelName, input, ownerMetadata);
            }
            if (result == null) {
                throw new IOException("OSS未确认对象写入");
            }
            log.info("OSS文件上传成功 object={}", fileRelName);
            jsobj1.put("fileName", fileRelName);
            if (aftercareEvidence) {
                jsobj1.put("versionId", result.getVersionId());
            } else if (signPublic) {
                if ("COMMUNITY_POST".equals(ownerMetadata.getUserMetadata().get(UPLOAD_BIZ_TYPE_METADATA_KEY))) {
                    GeneratePresignedUrlRequest preview = new GeneratePresignedUrlRequest(bucketName, fileRelName);
                    preview.setExpiration(shortLivedExpiration(new Date(), 300));
                    jsobj1.put("url", resolveGeneratedUrl(ossClient, preview));
                } else {
                    jsobj1.put("url", generatePresignedUrl(ossClient, fileRelName, bucketName));
                }
            }
//            jsobj1.put("orgName", orgName);
//            jsobj1.put("urls", "/" + fileRelName);
        } catch (IOException e) {
            log.error("OSS文件上传失败", e);
            throw e;
        }
        return jsobj1;
        //return visitUrl;
    }

    /** 归属/业务类型任一为空则不写 user metadata,保持旧上传行为(无归属对象不可用于 photo 首次提交)。 */
    private static ObjectMetadata ownerMetadata(String ownerMemberId, String bizType) {
        boolean hasOwner = StringUtils.isNotBlank(ownerMemberId);
        boolean hasBizType = StringUtils.isNotBlank(bizType);
        if (!hasOwner && !hasBizType) {
            return null;
        }
        ObjectMetadata metadata = new ObjectMetadata();
        if (hasOwner) {
            metadata.addUserMetadata(OWNER_MEMBER_METADATA_KEY, ownerMemberId);
        }
        if (hasBizType) {
            metadata.addUserMetadata(UPLOAD_BIZ_TYPE_METADATA_KEY, bizType);
        }
        return metadata;
    }

    /**
     * OSS 对象名只保留服务端生成的随机标识和已验证的扩展名，避免原始文件名进入对象键、URL 或日志。
     */
    static String createObjectKey(MultipartFile file) {
        return createObjectKey(file, "upload/");
    }

    static String createAftercareEvidenceObjectKey(MultipartFile file) {
        return createObjectKey(file, "upload/merchant-aftercare-evidence/");
    }

    /**
     * 售后/人工退款凭证只接受服务端专用上传入口生成的不可猜测对象键。
     * 普通 upload key、URL、大小写变体、路径穿越和多 key 都必须拒绝。
     */
    public static void validateAftercareEvidenceObjectKey(String objectKey) {
        if (objectKey == null || !AFTERCARE_EVIDENCE_OBJECT_KEY.matcher(objectKey).matches()) {
            throw new IllegalArgumentException("售后凭证对象标识无效");
        }
    }

    /**
     * 售后凭证绑定(W6f):对象 user metadata merchant-id / refund-id(上传时服务端同一次 putObject 写入)
     * 必须等于该售后单的门店与退款单。不核对上传者本人、不限时效。门店或单据不符/无绑定旧对象/不存在对象抛
     * IllegalArgumentException;存储配置缺失或查询失败抛 IllegalStateException(可恢复,调用方不得当成功放行)。
     */
    public static void verifyAftercareEvidenceBinding(String objectKey, Long merchantId, Long refundId) {
        validateAftercareEvidenceObjectKey(objectKey);
        String bucketName = requiredEnv(OSS_BUCKET_NAME_ENV);
        OSSClient ossClient = createOssClient(verificationClientConfiguration());
        try {
            verifyAftercareEvidenceBinding(objectKey, merchantId, refundId, ossClient, bucketName);
        } finally {
            ossClient.shutdown();
        }
    }

    static void verifyAftercareEvidenceBinding(String objectKey, Long merchantId, Long refundId,
                                               OSSClient ossClient, String bucketName) {
        validateAftercareEvidenceObjectKey(objectKey);
        if (ossClient == null || StringUtils.isBlank(bucketName)) {
            throw new IllegalStateException("对象存储配置缺失: 无法校验售后凭证绑定");
        }
        if (merchantId == null || refundId == null) {
            throw new IllegalArgumentException("售后凭证缺少门店或售后单");
        }
        ObjectMetadata metadata = readObjectMetadata(ossClient, bucketName, objectKey);
        Map<String, String> user = metadata.getUserMetadata();
        // 无绑定元数据的旧对象不猜归属,一律拒绝(需重新上传)
        if (user == null || !String.valueOf(merchantId).equals(user.get(AFTERCARE_MERCHANT_METADATA_KEY))
                || !String.valueOf(refundId).equals(user.get(AFTERCARE_REFUND_METADATA_KEY))) {
            throw new IllegalArgumentException("售后凭证不属于本售后单，请重新上传");
        }
    }

    /** OSS versionId 只能作为 SDK 参数使用，拒绝空值、控制字符和超长值。 */
    public static void validateAftercareEvidenceVersionId(String versionId) {
        if (versionId == null || !AFTERCARE_EVIDENCE_VERSION_ID.matcher(versionId).matches()) {
            throw new IllegalArgumentException("售后凭证版本标识无效");
        }
    }

    /** 从私有 OSS 的固定版本回读并计算 SHA-256；禁止读取 latest key。 */
    public static String calculateAftercareEvidenceSha256(String objectKey,
                                                           String versionId) throws IOException {
        validateAftercareEvidenceObjectKey(objectKey);
        validateAftercareEvidenceVersionId(versionId);
        String bucketName = requiredEnv(OSS_BUCKET_NAME_ENV);
        OSSClient ossClient = createOssClient();
        try {
            return resolveAftercareEvidenceSha256(
                    ossClient, bucketName, objectKey, versionId, DEFAULT_MAX_SIZE);
        } finally {
            ossClient.shutdown();
        }
    }

    static String resolveAftercareEvidenceSha256(OSSClient ossClient, String bucketName,
                                                  String objectKey, String versionId,
                                                  long maxBytes) throws IOException {
        validateAftercareEvidenceObjectKey(objectKey);
        validateAftercareEvidenceVersionId(versionId);
        if (ossClient == null || StringUtils.isBlank(bucketName) || maxBytes <= 0) {
            throw new IllegalArgumentException("售后凭证读取参数无效");
        }
        OSSObject object = ossClient.getObject(
                new GetObjectRequest(bucketName, objectKey, versionId));
        if (object == null || object.getObjectContent() == null) {
            if (object != null) object.close();
            throw new IOException("OSS 未返回售后凭证对象");
        }
        try (InputStream input = object.getObjectContent()) {
            MessageDigest digest = sha256Digest();
            byte[] buffer = new byte[8192];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("售后凭证对象超过允许大小");
                }
                digest.update(buffer, 0, read);
            }
            return toLowerHex(digest.digest());
        } finally {
            object.close();
        }
    }

    /** 仅为已鉴权详情生成短期预览 URL；对象 key 自身不作为 bearer URL 返回。 */
    public static String generateShortLivedAftercareEvidenceUrl(String objectKey,
                                                                 int expirationSeconds) {
        validateAftercareEvidenceObjectKey(objectKey);
        return generateShortLivedPresignedUrl(objectKey, expirationSeconds);
    }

    /** 退款人工凭证预览固定到上传时的不可变 OSS 版本，不允许签 latest key。 */
    public static String generateShortLivedAftercareEvidenceUrl(String objectKey,
                                                                 String versionId,
                                                                 int expirationSeconds) {
        validateAftercareEvidenceObjectKey(objectKey);
        validateAftercareEvidenceVersionId(versionId);
        Date expiration = shortLivedExpiration(new Date(), expirationSeconds);
        String bucketName = requiredEnv(OSS_BUCKET_NAME_ENV);
        GeneratePresignedUrlRequest request = versionedPresignedRequest(
                bucketName, objectKey, versionId, expiration);
        OSSClient ossClient = createOssClient();
        try {
            return resolveGeneratedUrl(ossClient, request);
        } finally {
            ossClient.shutdown();
        }
    }

    static GeneratePresignedUrlRequest versionedPresignedRequest(
            String bucketName, String objectKey, String versionId, Date expiration) {
        if (StringUtils.isBlank(bucketName) || expiration == null) {
            throw new IllegalArgumentException("售后凭证预签名参数无效");
        }
        validateAftercareEvidenceObjectKey(objectKey);
        validateAftercareEvidenceVersionId(versionId);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucketName, objectKey);
        request.setExpiration(expiration);
        request.addQueryParameter("versionId", versionId);
        return request;
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 不支持 SHA-256", impossible);
        }
    }

    private static String toLowerHex(byte[] bytes) {
        char[] hex = "0123456789abcdef".toCharArray();
        char[] output = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            output[index * 2] = hex[value >>> 4];
            output[index * 2 + 1] = hex[value & 0x0f];
        }
        return new String(output);
    }

    private static String createObjectKey(MultipartFile file, String prefix) {
        String extension = StringUtils.isNotEmpty(file.getOriginalFilename()) || StringUtils.isNotEmpty(file.getContentType())
                ? FileUploadUtils.getExtension(file).toLowerCase(Locale.ROOT) : "";
        return prefix + IdUtils.fastSimpleUUID() + (StringUtils.isBlank(extension) ? "" : "." + extension);
    }

    /** 校验路径专用短超时(毫秒):校验在 photo() 事务内执行,不能沿用 SDK 默认 50s 占用 DB 连接。 */
    static final int VERIFY_CONNECT_TIMEOUT_MS = 3000;
    static final int VERIFY_SOCKET_TIMEOUT_MS = 5000;

    /**
     * 校验拍照完成提交的 picUrl 确实来自现行 /api/common/uploadOSS 上传链，且对应对象真实存在。
     *
     * <p>无效上传(任意字符串/外域/换桶/非图片对象/伪造签名/篡改 AccessKeyId/过期凭证/不存在对象)抛
     * IllegalArgumentException，调用方必须拒绝，不得推进进度或发奖；
     * 存储配置缺失或查询失败抛 IllegalStateException，调用方必须按可恢复错误处理
     * (不推进、不清历史，存储恢复后重试同一 URL 幂等放行)。校验只对配置桶做事实查询，
     * 从不请求客户端 URL;本身不修改任何状态,重试安全。
     */
    public static void verifyUploadedPhoto(String picUrl, Long uploaderMemberId) {
        if (uploaderMemberId == null || uploaderMemberId <= 0) {
            throw new IllegalArgumentException("请先登录");
        }
        OSSClient ossClient = createOssClient(verificationClientConfiguration());
        try {
            verifyUploadedPhoto(picUrl, ossClient, requiredEnv(OSS_BUCKET_NAME_ENV), uploaderMemberId);
        } finally {
            ossClient.shutdown();
        }
    }

    /** 校验用短超时配置:连接 3s/读 5s;存储故障快速失败成可恢复错误,避免长事务阻塞。 */
    static ClientConfiguration verificationClientConfiguration() {
        ClientConfiguration configuration = new ClientConfiguration();
        configuration.setConnectionTimeout(VERIFY_CONNECT_TIMEOUT_MS);
        configuration.setSocketTimeout(VERIFY_SOCKET_TIMEOUT_MS);
        return configuration;
    }

    /**
     * SDK 缝:本地替身/单测注入同一个 OSSClient,解析、重签名比对、对象元数据与归属核对逻辑保持真实。
     * 归属取 OSS user metadata member-id(仅服务端持桶写权限时可写),必须等于当前登录会员。
     */
    static void verifyUploadedPhoto(String picUrl, OSSClient ossClient, String bucketName,
            Long expectedMemberId) {
        if (ossClient == null || StringUtils.isBlank(bucketName)) {
            throw new IllegalStateException("对象存储配置缺失: 无法校验照片上传事实");
        }
        if (expectedMemberId == null || expectedMemberId <= 0) {
            throw new IllegalArgumentException("请先登录");
        }
        String objectKey = resolveUploadedPhotoObjectKey(picUrl);
        verifyPresignedDownloadIssuedByThisStorage(picUrl, objectKey, ossClient, bucketName);
        ObjectMetadata metadata = readObjectMetadata(ossClient, bucketName, objectKey);
        String ownerMemberId = metadata.getUserMetadata() == null ? null
                : metadata.getUserMetadata().get(OWNER_MEMBER_METADATA_KEY);
        if (StringUtils.isBlank(ownerMemberId)) {
            throw new IllegalArgumentException("照片缺少上传归属信息，请重新拍摄上传");
        }
        if (!String.valueOf(expectedMemberId).equals(ownerMemberId)) {
            throw new IllegalArgumentException("该照片不是当前账号上传的，请重新拍摄上传");
        }
    }

    private static ObjectMetadata readObjectMetadata(OSSClient ossClient, String bucketName,
            String objectKey) {
        try {
            ObjectMetadata metadata = ossClient.getObjectMetadata(bucketName, objectKey);
            if (metadata == null) {
                throw new IllegalStateException("对象存储未返回对象元数据");
            }
            return metadata;
        } catch (ServiceException e) {
            if (OSSErrorCode.NO_SUCH_KEY.equals(e.getErrorCode())
                    || OSSErrorCode.NO_SUCH_BUCKET.equals(e.getErrorCode())) {
                throw new IllegalArgumentException("照片不存在或已失效，请重新拍摄上传", e);
            }
            throw new IllegalStateException("对象存储查询失败: " + e.getMessage(), e);
        } catch (ClientException e) {
            throw new IllegalStateException("对象存储查询失败: " + e.getMessage(), e);
        }
    }

    /**
     * 严格解析上传链生成的预签名 URL:对象键必须是服务端随机照片 key。
     * 拒外域/带端口/带用户信息/带片段、非 http(s) 协议、路径编码绕行与售后凭证等其它前缀。
     */
    static String resolveUploadedPhotoObjectKey(String picUrl) {
        URI uri = parseUploadedPhotoUri(picUrl);
        String objectKey = uri.getRawPath().substring(1);
        if (!UPLOADED_PHOTO_OBJECT_KEY.matcher(objectKey).matches()) {
            throw new IllegalArgumentException("照片地址不是现行上传链生成的照片对象");
        }
        return objectKey;
    }

    private static URI parseUploadedPhotoUri(String picUrl) {
        if (StringUtils.isBlank(picUrl)) {
            throw new IllegalArgumentException("照片地址无效");
        }
        URI uri;
        try {
            uri = new URI(picUrl.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("照片地址无效", e);
        }
        String scheme = uri.getScheme();
        boolean webScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!webScheme || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null
                || StringUtils.isBlank(uri.getHost()) || StringUtils.isBlank(uri.getRawPath())
                || StringUtils.isBlank(uri.getRawQuery())) {
            throw new IllegalArgumentException("照片地址无效");
        }
        return uri;
    }

    /**
     * 用同一 OSS SDK 按当前配置对同一 key/同一过期时间重算 v1 预签名，比对 host+AccessKeyId+签名:
     * 只有现行上传链签发的 URL 才通过；伪造/篡改签名、篡改 AccessKeyId(真实 GET 必 403)、外域、换桶、
     * 改期都会因重算结果不一致被拒。AccessKeyId 必须与当前配置一致，轮换后旧 URL 不再可用(须重传)。
     * 协议(http/https)不参与比对:v1 签名不绑定 scheme,同一对象的存量 URL 不因协议迁移失效。
     */
    private static void verifyPresignedDownloadIssuedByThisStorage(String picUrl, String objectKey,
            OSSClient ossClient, String bucketName) {
        URI uri = parseUploadedPhotoUri(picUrl);
        Map<String, String> query = strictSignedQuery(uri.getRawQuery());
        long expiresSeconds;
        try {
            expiresSeconds = Long.parseLong(query.get("Expires"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("照片上传凭证无效", e);
        }
        if (expiresSeconds <= 0 || expiresSeconds * 1000L <= System.currentTimeMillis()) {
            throw new IllegalArgumentException("照片上传凭证已过期，请重新拍摄上传");
        }
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucketName, objectKey);
        request.setExpiration(new Date(expiresSeconds * 1000L));
        URL expected;
        try {
            expected = ossClient.generatePresignedUrl(request);
        } catch (ClientException | ServiceException e) {
            throw new IllegalStateException("对象存储签名校验失败: " + e.getMessage(), e);
        }
        if (expected == null) {
            throw new IllegalStateException("对象存储未返回签名校验结果");
        }
        String expectedSignature = queryParameter(expected.getQuery(), "Signature");
        String expectedAccessKeyId = queryParameter(expected.getQuery(), "OSSAccessKeyId");
        boolean sameIssue = expected.getHost().equalsIgnoreCase(uri.getHost())
                && expectedAccessKeyId != null
                && expectedAccessKeyId.equals(query.get("OSSAccessKeyId"))
                && expectedSignature != null
                && expectedSignature.equals(query.get("Signature"));
        if (!sameIssue) {
            throw new IllegalArgumentException("照片上传凭证无效，请重新拍摄上传");
        }
    }

    /** v1 预签名只允许 Expires/OSSAccessKeyId/Signature 三个参数;多余参数、重复或缺参都拒绝。 */
    private static Map<String, String> strictSignedQuery(String rawQuery) {
        if (StringUtils.isBlank(rawQuery)) {
            throw new IllegalArgumentException("照片上传凭证无效");
        }
        Map<String, String> query = new HashMap<>();
        for (String pair : rawQuery.split("&")) {
            int split = pair.indexOf('=');
            if (split <= 0) {
                throw new IllegalArgumentException("照片上传凭证无效");
            }
            String name = pair.substring(0, split);
            String value = pair.substring(split + 1);
            if (!"Expires".equals(name) && !"OSSAccessKeyId".equals(name) && !"Signature".equals(name)) {
                throw new IllegalArgumentException("照片上传凭证无效");
            }
            if (StringUtils.isBlank(value) || query.put(name, value) != null) {
                throw new IllegalArgumentException("照片上传凭证无效");
            }
        }
        if (!query.containsKey("Expires") || !query.containsKey("OSSAccessKeyId")
                || !query.containsKey("Signature")) {
            throw new IllegalArgumentException("照片上传凭证无效");
        }
        return query;
    }

    private static String queryParameter(String rawQuery, String name) {
        if (StringUtils.isBlank(rawQuery)) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int split = pair.indexOf('=');
            if (split > 0 && name.equals(pair.substring(0, split))) {
                return pair.substring(split + 1);
            }
        }
        return null;
    }

    /**
     * 生成加密的下载链接
     *
     * @param objectName 对象名称，即文件路径
     * @return 带签名的 URL
     */
    public static String generatePresignedUrl(String objectName) {
        return generatePresignedUrl(objectName, defaultPresignedExpiration());
    }

    /** 普通上传合同的长期 URL 过期时间(10 年);上传回包与对象事实校验共用同一口径。 */
    private static Date defaultPresignedExpiration() {
        return new Date(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000);
    }

    /** SDK 缝:用传入 client/桶名生成预签名 URL,上传核心与本地替身测试共用同一段逻辑。 */
    static String generatePresignedUrl(OSSClient ossClient, String objectName, String bucketName) {
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucketName, objectName);
        request.setExpiration(defaultPresignedExpiration());
        return resolveGeneratedUrl(ossClient, request);
    }

    /**
     * 为需要逐次鉴权的私有对象生成短期 URL。调用方只能传 1 小时以内的有效期，
     * 避免业务代码误把长期 bearer URL 当成可持久化资源标识。
     */
    public static String generateShortLivedPresignedUrl(String objectName, int expirationSeconds) {
        if (StringUtils.isBlank(objectName)) {
            throw new IllegalArgumentException("OSS 对象名不能为空");
        }
        Date expiration = shortLivedExpiration(new Date(), expirationSeconds);
        return generatePresignedUrl(objectName, expiration);
    }

    /** 社区逐次授权读取只签 300 秒，裸 endpoint 也必须由 SDK 经 HTTPS 生成签名。 */
    public static String generateCommunityMediaPresignedUrl(String objectName) {
        String key = communityMediaObjectKey(objectName);
        String bucket = requiredEnv(OSS_BUCKET_NAME_ENV);
        OSSClient client = createCommunityOssClient();
        try {
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, key);
            request.setExpiration(shortLivedExpiration(new Date(), 300));
            String signed = resolveGeneratedUrl(client, request);
            if (!key.equals(communityMediaObjectKey(signed))) {
                throw new IllegalStateException("OSS 社区预览来源不匹配");
            }
            return signed;
        } finally {
            client.shutdown();
        }
    }

    /** Only persisted server-issued keys or HTTPS references to this configured storage are identities. */
    public static String communityMediaObjectKey(String reference) {
        if (reference != null && reference.matches("upload/[0-9a-f]{32}\\.[a-z0-9]{1,10}")) return reference;
        return communityMediaObjectKey(reference, requiredEnv(OSS_BUCKET_NAME_ENV), requiredEnv(OSS_ENDPOINT_ENV));
    }

    static String communityMediaObjectKey(String reference, String bucket, String endpoint) {
        if (reference == null || !reference.equals(reference.trim())) throw new IllegalArgumentException("社区媒体来源无效");
        if (reference.matches("upload/[0-9a-f]{32}\\.[a-z0-9]{1,10}")) return reference;
        try {
            URI storage = new URI(endpoint.contains("://") ? endpoint : "https://" + endpoint);
            URI uri = new URI(reference);
            String key = uri.getRawPath() == null || !uri.getRawPath().startsWith("/")
                    ? "" : uri.getRawPath().substring(1);
            if (!"https".equalsIgnoreCase(storage.getScheme()) || storage.getHost() == null
                    || storage.getUserInfo() != null || storage.getPort() != -1
                    || storage.getRawQuery() != null || storage.getRawFragment() != null
                    || !(storage.getRawPath().isEmpty() || "/".equals(storage.getRawPath()))
                    || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getRawFragment() != null
                    || !(bucket + "." + storage.getHost()).equalsIgnoreCase(uri.getHost())
                    || !key.matches("upload/[0-9a-f]{32}\\.[a-z0-9]{1,10}")) {
                throw new IllegalArgumentException("社区媒体来源无效");
            }
            return key;
        } catch (URISyntaxException | NullPointerException invalid) {
            throw new IllegalArgumentException("社区媒体来源无效", invalid);
        }
    }

    static Date shortLivedExpiration(Date now, int expirationSeconds) {
        if (now == null) {
            throw new IllegalArgumentException("短期 OSS URL 起始时间不能为空");
        }
        if (expirationSeconds < 1 || expirationSeconds > 3600) {
            throw new IllegalArgumentException("短期 OSS URL 有效期必须在 1 到 3600 秒之间");
        }
        return new Date(now.getTime() + expirationSeconds * 1000L);
    }

    private static String generatePresignedUrl(String objectName, Date expiration) {

        //Date expiration = new Date(System.currentTimeMillis() + expirationInSeconds * 1000);

        // 生成签名的URL
        String bucketName = requiredEnv(OSS_BUCKET_NAME_ENV);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucketName, objectName);
        request.setExpiration(expiration);

        OSSClient ossClient = createOssClient();
        try {
            return resolveGeneratedUrl(ossClient, request);
        } finally {
            ossClient.shutdown();
        }
    }

    /**
     * 保留 SDK 生成的 query 签名；手拼 bucket URL 只会得到未签名地址，私有桶必然 403。
     * 单独抽出供无网络单测验证“SDK 回什么就返回什么”，不触碰真实 OSS 凭据。
     */
    static String resolveGeneratedUrl(OSSClient ossClient, GeneratePresignedUrlRequest request) {
        URL signedUrl = ossClient.generatePresignedUrl(request);
        if (signedUrl == null) {
            throw new IllegalStateException("OSS 未返回预签名 URL");
        }
        return signedUrl.toString();
    }

    public static String getPresignedUrl(String url) {
        if(url==null) return "";
        String endpoint = ossProp(OSS_ENDPOINT_ENV);
        if (StringUtils.isNotEmpty(endpoint) && url.contains(endpoint)) {
            url = url.split("\\?")[0];
            url = url.replace("http://lie-channelwin.oss-cn-hangzhou.aliyuncs.com/", "");
            return generatePresignedUrl(url);
        }

        return url;
    }

    private static OSSClient createCommunityOssClient() {
        return createCommunityOssClient(requiredEnv(OSS_ENDPOINT_ENV),
                new DefaultCredentialProvider(requiredEnv(OSS_ACCESS_KEY_ID_ENV), requiredEnv(OSS_ACCESS_KEY_SECRET_ENV)));
    }

    static OSSClient createCommunityOssClient(String endpoint, DefaultCredentialProvider credentials) {
        ClientConfiguration configuration = new ClientConfiguration();
        configuration.setProtocol(Protocol.HTTPS);
        return new OSSClient(communityEndpoint(endpoint), credentials, configuration);
    }

    static String communityEndpoint(String endpoint) {
        if (endpoint == null || !endpoint.equals(endpoint.trim())) {
            throw new IllegalArgumentException("社区 OSS endpoint 无效");
        }
        try {
            URI storage = new URI(endpoint.contains("://") ? endpoint : "https://" + endpoint);
            if (!"https".equalsIgnoreCase(storage.getScheme()) || storage.getHost() == null
                    || storage.getUserInfo() != null || storage.getPort() != -1
                    || storage.getRawQuery() != null || storage.getRawFragment() != null
                    || !(storage.getRawPath().isEmpty() || "/".equals(storage.getRawPath()))) {
                throw new IllegalArgumentException("社区 OSS endpoint 无效");
            }
            return "https://" + storage.getHost();
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("社区 OSS endpoint 无效", invalid);
        }
    }

    private static OSSClient createOssClient() {
        return createOssClient(new ClientConfiguration());
    }

    private static OSSClient createOssClient(ClientConfiguration configuration) {
        return createOssClient(requiredEnv(OSS_ENDPOINT_ENV),
                new DefaultCredentialProvider(requiredEnv(OSS_ACCESS_KEY_ID_ENV), requiredEnv(OSS_ACCESS_KEY_SECRET_ENV)),
                configuration);
    }

    /** 新签出的链接一律 https(同 createCommunityOssClient):小程序 getImgUrl 只放行 https,http 签名链接会被拒。 */
    static OSSClient createOssClient(String endpoint, DefaultCredentialProvider credentials, ClientConfiguration configuration) {
        configuration.setProtocol(Protocol.HTTPS);
        String httpsEndpoint = endpoint.regionMatches(true, 0, "http://", 0, 7) ? "https://" + endpoint.substring(7) : endpoint;
        return new OSSClient(httpsEndpoint, credentials, configuration);
    }

    /**
     * 删除由本服务上传到当前 OSS bucket 的对象。外部历史 URL 返回 false，绝不越权删除。
     */
    public static boolean deleteOwnedObjectIfPresent(String objectKeyOrUrl) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (StringUtils.isBlank(objectKeyOrUrl)) return false;
        String bucket = requiredEnv(OSS_BUCKET_NAME_ENV);
        String endpoint = requiredEnv(OSS_ENDPOINT_ENV);
        String key = objectKeyOrUrl.trim();
        if (key.startsWith("http://") || key.startsWith("https://")) {
            try {
                URI objectUri = URI.create(key);
                URI endpointUri = URI.create(endpoint.contains("://") ? endpoint : "https://" + endpoint);
                String endpointHost = endpointUri.getHost();
                String objectHost = objectUri.getHost();
                if (endpointHost == null || objectHost == null
                        || !(objectHost.equals(endpointHost) || objectHost.equals(bucket + "." + endpointHost))) {
                    return false;
                }
                key = objectUri.getPath();
                if (key.startsWith("/")) key = key.substring(1);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        if (!key.startsWith("upload/") || key.contains("..")) return false;
        OSSClient client = createOssClient();
        try {
            client.deleteObject(bucket, key);
            return true;
        } finally {
            client.shutdown();
        }
    }

    /**
     * 判断一个图片地址是不是「本站产出的照片」:站内 /profile/upload 相对路径,或本桶域名的绝对地址。
     *
     * <p>给「要把地址交给外部服务」的调用当来源闸用(藏品卡交给 3D 生成服务是第一个):SSRF 的入口从来不是
     * 我们自己生成的地址,而是别人递进来的一句话 —— 判定必须发生在**发请求之前**,认不出就拒。
     * 主机口径与 deleteOwnedObjectIfPresent 一致,但刻意不复用它:那道闸的副作用是删对象,
     * 来源判定必须无副作用。
     */
    public static boolean isChengyinPhotoAddress(String url) {
        if (StringUtils.isBlank(url)) return false;
        String value = url.trim();
        if (value.startsWith("/profile/upload/")) return true;
        if (!value.startsWith("http://") && !value.startsWith("https://")) return false;
        String bucket = ossProp(OSS_BUCKET_NAME_ENV);
        String endpoint = ossProp(OSS_ENDPOINT_ENV);
        if (StringUtils.isBlank(bucket) || StringUtils.isBlank(endpoint)) {
            // 配置读不到时判「不是自家的」:宁可拒掉自己的图,也不能放行一个认不出的域。
            return false;
        }
        try {
            URI objectUri = URI.create(value);
            URI endpointUri = URI.create(endpoint.contains("://") ? endpoint : "https://" + endpoint);
            String endpointHost = endpointUri.getHost();
            String objectHost = objectUri.getHost();
            if (endpointHost == null || objectHost == null
                    || !(objectHost.equals(endpointHost) || objectHost.equals(bucket + "." + endpointHost))) {
                return false;
            }
            String key = objectUri.getPath();
            if (key != null && key.startsWith("/")) key = key.substring(1);
            return key != null && key.startsWith("upload/") && !key.contains("..");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String requiredEnv(String name) {
        String value = ossProp(name);
        if (StringUtils.isBlank(value)) {
            throw new IllegalStateException("对象存储配置缺失: " + name);
        }
        return value;
    }

    /**
     * 读 OSS 配置:优先 OS 环境变量,缺失时回退到 Spring 配置(外部 config/application.yml)。
     * 规避容器(宝塔)环境变量注入不可靠的问题;与 JWT/PII key 走同一外部配置文件机制。
     */
    private static String ossProp(String name) {
        String value = System.getenv(name);
        if (StringUtils.isBlank(value)) {
            try {
                value = SpringUtils.getBean(Environment.class).getProperty(name);
            } catch (Exception ignored) {
            }
        }
        return value;
    }

    public static String getPresignedUrls(String url) {
        if(url==null) return "";
        String[] array = url.split(";");
        if (array.length > 0) {
            List<String> urls = new ArrayList<>();
            for (String s : array) {
                urls.add(getPresignedUrl(s));
            }

            return String.join(";", urls);
        }

        return url;
    }
}
