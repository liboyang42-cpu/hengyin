package com.chengyinhub.business.service.support;

import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.service.impl.AppMediaAssetService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.HashMap;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Public media is always resolved from the immutable reviewed asset, never from a submitted URL. */
@Component
public class AppMediaReferenceGuard {
    private static final String LOCAL_IMAGE = "/profile/upload/[0-9]{4}/[0-9]{2}/[0-9]{2}/[A-Za-z0-9_-]+\\.(jpg|jpeg|png|gif)";
    private static final String OSS_IMAGE = "upload/[0-9a-f]{32}\\.(jpg|jpeg|png|gif)";
    private final ObjectMapper json = new ObjectMapper();
    @Autowired private AppMediaAssetMapper mapper;
    @Autowired private AppMediaAssetService assets;

    public String requireAttachment(Long memberId, String reference, boolean publish) {
        if (reference == null || reference.trim().isEmpty()) return reference;
        Map<String, Object> asset = resolve(reference);
        if (memberId != null && memberId > 0 && !registered(asset)) {
            // G-3:本站存储里没有登记的图(后台上传、模板自带、登记表上线前的旧图)不再要求重新上传。
            String system = systemImageReference(reference);
            if (system != null) return system;
        }
        if (memberId == null || memberId <= 0 || asset == null || !(asset.get("id") instanceof Number)
                || ((Number) asset.get("id")).longValue() <= 0) {
            throw new ServiceException("媒体来源未经审核，请重新上传", 422);
        }
        if (!"APPROVED".equals(asset.get("reviewState")) && !owned(asset, memberId)) {
            throw new ServiceException("媒体不属于当前账号，请重新上传", 403);
        }
        if (publish && !"APPROVED".equals(asset.get("reviewState"))) {
            throw new ServiceException("媒体尚未审核通过，暂不能公开", 422);
        }
        return "media-asset:" + asset.get("id");
    }

    public String requireAttachments(Long memberId, String references, boolean publish) {
        if (references == null || references.trim().isEmpty()) return references;
        if (references.trim().startsWith("[")) return requireJsonAttachments(memberId, references, publish);
        String[] items = references.split("[;,]", -1);
        StringBuilder safe = new StringBuilder();
        for (String item : items) {
            if (item.trim().isEmpty()) continue;
            if (safe.length() > 0) safe.append(';');
            safe.append(requireAttachment(memberId, item, publish));
        }
        return safe.toString();
    }

    /** 门店相册等多图字段存的是 JSON 字符串数组:逐张校验后按原形状写回,不能按分隔符切碎 JSON。 */
    private String requireJsonAttachments(Long memberId, String references, boolean publish) {
        JsonNode items;
        try { items = json.readTree(references); } catch (IOException invalid) { items = null; }
        if (items == null || !items.isArray()) throw new ServiceException("媒体配置格式不正确", 422);
        ArrayNode safe = json.createArrayNode();
        for (JsonNode item : items) {
            if (!item.isTextual()) throw new ServiceException("媒体配置格式不正确", 422);
            if (item.textValue().trim().isEmpty()) continue;
            safe.add(requireAttachment(memberId, item.textValue(), publish));
        }
        return safe.toString();
    }

    @SuppressWarnings("unchecked")
    public String visibleUrl(String reference, Long viewerMemberId) {
        ServletRequestAttributes request = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes
                ? (ServletRequestAttributes) RequestContextHolder.getRequestAttributes() : null;
        Map<String, String> cache = null;
        String key = String.valueOf(viewerMemberId) + "|" + reference;
        String attribute = AppMediaReferenceGuard.class.getName() + ".visibleCache";
        if (request != null) {
            cache = (Map<String, String>) request.getRequest().getAttribute(attribute);
            if (cache == null) {
                cache = new HashMap<>();
                request.getRequest().setAttribute(attribute, cache);
            }
            if (cache.containsKey(key)) return cache.get(key);
        }
        String safe = resolveVisibleUrl(reference, viewerMemberId);
        if (cache != null) {
            cache.put(key, safe);
            if (safe != null && !safe.isEmpty()) cache.put(String.valueOf(viewerMemberId) + "|" + safe, safe);
        }
        return safe;
    }

    private String resolveVisibleUrl(String reference, Long viewerMemberId) {
        if (reference == null || reference.trim().isEmpty()) return reference;
        if (AppMediaSchema.isBundledMerchantGameAudio(reference)) return reference;
        Map<String, Object> asset = resolve(reference);
        if (!registered(asset)) {
            String system = systemImageKey(reference);
            String url = system == null ? null : assets.systemImageUrl(system);
            return url == null ? "" : url;
        }
        Long id = ((Number) asset.get("id")).longValue();
        if ("APPROVED".equals(asset.get("reviewState"))) return assets.approvedUrl(id);
        return owned(asset, viewerMemberId) ? assets.ownerPreview(id, viewerMemberId) : "";
    }

    public String processingUrl(String reference, Long memberId) {
        return processingUrl(reference, memberId, null);
    }

    public String processingUrl(String reference, Long memberId, String expectedMediaType) {
        if (memberId == null || memberId <= 0) throw new ServiceException("请先登录", 401);
        Map<String, Object> asset = resolve(reference == null ? "" : reference);
        if (asset == null || !"APPROVED".equals(asset.get("reviewState")))
            throw new ServiceException("媒体尚未审核通过，暂不能处理", 422);
        if (!owned(asset, memberId))
            throw new ServiceException("仅上传者可发起该媒体的克隆或第三方处理", 403);
        if (expectedMediaType != null && !expectedMediaType.equals(asset.get("mediaType")))
            throw new ServiceException("媒体格式不符合当前操作", 422);
        String key = String.valueOf(asset.get("objectKey"));
        if ("LOCAL".equals(asset.get("storageType"))) {
            if (!key.matches("/profile/upload/[0-9]{4}/[0-9]{2}/[0-9]{2}/[0-9a-f]{32}\\.(jpg|jpeg|png|gif)"))
                throw new ServiceException("媒体存储事实不可用", 422);
            return key;
        }
        if (!key.matches("upload/[0-9a-f]{32}\\.(jpg|jpeg|png|gif|mp3|m4a|aac|wav|glb)"))
            throw new ServiceException("媒体存储事实不可用", 422);
        return FileOssUploadUtils.generateShortLivedPresignedUrl(key, 300);
    }

    private Map<String, Object> resolve(String reference) {
        String value = reference.trim();
        if (value.matches("media-asset:[1-9][0-9]{0,18}")) {
            try { return mapper.select(Long.valueOf(value.substring("media-asset:".length()))); }
            catch (NumberFormatException invalidId) { return null; }
        }
        String key = storageKey(value);
        return key == null ? null : mapper.selectByObjectKey(key);
    }

    /** 本站存储对象 key:本地 /profile/upload/... 或 OSS upload/...;认不出返回 null。 */
    private static String storageKey(String value) {
        if (value.matches(LOCAL_IMAGE)) return value;
        URI uri;
        try { uri = URI.create(value); } catch (IllegalArgumentException invalidUrl) { return null; }
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) return null;
        String rawPath = uri.getRawPath();
        if (rawPath != null && rawPath.matches("(?:/[A-Za-z0-9_-]+)*" + LOCAL_IMAGE)) {
            rawPath = rawPath.substring(rawPath.indexOf("/profile/upload/"));
        }
        if (rawPath != null && rawPath.matches(LOCAL_IMAGE)) return rawPath;
        if (rawPath == null || !rawPath.matches("/upload/[0-9a-f]{32}\\.(jpg|jpeg|png|gif|mp3|m4a|aac|wav|glb)")) return null;
        return rawPath.substring(1);
    }

    /**
     * 未登记、但出自本站存储的图片 key。本地图只可能经后台上传或同步机审写入;
     * OSS 图还要求主机就是本桶,外站同名路径不算。
     */
    private static String systemImageKey(String reference) {
        String value = reference.trim();
        String key = storageKey(value);
        if (key == null || key.startsWith("/profile/upload/")) return key;
        return key.matches(OSS_IMAGE) && FileOssUploadUtils.isChengyinPhotoAddress(value) ? key : null;
    }

    /** 系统图落库形状:本地存路径,OSS 存去掉签名参数的本桶地址,读时按 key 重签。 */
    private static String systemImageReference(String reference) {
        String key = systemImageKey(reference);
        if (key == null || key.startsWith("/profile/upload/")) return key;
        URI uri = URI.create(reference.trim());
        return uri.getScheme() + "://" + uri.getRawAuthority() + "/" + key;
    }

    private static boolean registered(Map<String, Object> asset) {
        return asset != null && asset.get("id") instanceof Number;
    }

    private static boolean owned(Map<String, Object> asset, Long memberId) {
        return memberId != null && memberId > 0 && asset != null && asset.get("id") instanceof Number
                && ((Number) asset.get("id")).longValue() > 0 && "APP".equals(asset.get("ownerType"))
                && asset.get("memberId") instanceof Number
                && memberId.longValue() == ((Number) asset.get("memberId")).longValue();
    }
}
