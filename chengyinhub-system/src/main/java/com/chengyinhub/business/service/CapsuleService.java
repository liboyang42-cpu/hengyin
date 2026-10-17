package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CapsuleRecord;
import com.chengyinhub.business.mapper.CapsuleMapper;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
public class CapsuleService {
    private final CapsuleMapper mapper;
    public CapsuleService(CapsuleMapper mapper) { this.mapper = mapper; }
    protected long now() { return System.currentTimeMillis(); }
    protected void verifyPhoto(String url, Long owner) { FileOssUploadUtils.verifyUploadedPhoto(url, owner); }
    protected String signPhoto(String key) { return FileOssUploadUtils.generateShortLivedPresignedUrl(key, 300); }
    public static void requireOwner(Long owner) { if (owner == null || owner <= 0) throw new ServiceException("请先登录"); }
    private static void requireId(Long id) { if (id == null || id <= 0) throw new ServiceException("胶囊编号不合法"); }
    public static long unlockMillis(String date) {
        try {
            if (date == null || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
            return LocalDate.parse(date).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        } catch (RuntimeException e) { throw new ServiceException("开启日期应为有效的 YYYY-MM-DD"); }
    }
    private static String hash(String body, String photo, long unlock) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String value : Arrays.asList(body, photo, String.valueOf(unlock))) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                md.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII)); md.update(bytes);
            }
            StringBuilder result = new StringBuilder();
            for (byte b : md.digest()) result.append(String.format("%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    /** Autocommit insert + unique constraint: duplicate recovery must see the winner's committed row. */
    public Map<String,Object> create(Long owner, String requestId, String text, String photoUrl, String unlockDate) {
        requireOwner(owner);
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}")) throw new ServiceException("请保留有效的原请求编号重试");
        String body = text == null ? "" : text.trim();
        String photo = photoUrl == null ? "" : photoUrl.trim();
        if (body.length() > 4000 || photo.length() > 4096 || (body.isEmpty() && photo.isEmpty())) throw new ServiceException("请填写文字或上传照片，文字最多4000字");
        long unlock = unlockMillis(unlockDate);
        String payloadHash = hash(body, photo, unlock);
        CapsuleRecord old = mapper.byRequest(owner, requestId);
        if (old != null) return replay(old, payloadHash);
        long current = now();
        if (unlock <= current || unlock > current + 3660L * 86400000L) throw new ServiceException("请选择未来十年内的开启日期");
        String key = null;
        if (!photo.isEmpty()) {
            try { verifyPhoto(photo, owner); }
            catch (IllegalArgumentException invalidPhoto) {
                // Validation only; transport/configuration failures remain unknown and must not release retry state.
                throw new ServiceException("照片凭据无效，请使用本人上传的有效照片", 400);
            }
            // Extract only AFTER the existing verifier checks host/signature/type/object/owner.
            key = URI.create(photo).getRawPath().substring(1);
        }
        CapsuleRecord row = new CapsuleRecord(); row.setMemberId(owner); row.setRequestId(requestId);
        row.setPayloadHash(payloadHash); row.setBody(body); row.setPhotoKey(key); row.setUnlockAtMs(unlock);
        row.setCreatedAtMs(current); row.setDeleted(0);
        try { if (mapper.insert(row) != 1) throw new IllegalStateException("胶囊保存未确认"); }
        catch (DuplicateKeyException collision) {
            old = mapper.byRequest(owner, requestId);
            if (old == null) throw collision;
            return replay(old, payloadHash);
        }
        return metadata(row);
    }
    private Map<String,Object> replay(CapsuleRecord row, String hash) {
        if (!hash.equals(row.getPayloadHash())) throw new ServiceException("原请求编号已用于不同内容", 409);
        return metadata(row);
    }
    private Map<String,Object> metadata(CapsuleRecord row) {
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("id", String.valueOf(row.getId())); data.put("unlockAtMs", row.getUnlockAtMs());
        data.put("createdAtMs", row.getCreatedAtMs()); data.put("timeZone", "Asia/Shanghai");
        data.put("state", Integer.valueOf(1).equals(row.getDeleted()) ? "DELETED" : now() < row.getUnlockAtMs() ? "LOCKED" : "OPEN");
        return data;
    }
    public Map<String,Object> receipt(Long owner, String requestId) {
        requireOwner(owner);
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}")) throw new ServiceException("请求编号不合法");
        CapsuleRecord row = mapper.byRequest(owner, requestId);
        if (row == null) throw new ServiceException("尚未查询到原请求，请用相同编号重试");
        return metadata(row);
    }
    public Map<String,Object> detail(Long owner, Long id) {
        requireOwner(owner); requireId(id); CapsuleRecord row = mapper.byId(owner, id);
        if (row == null) throw new ServiceException("胶囊不存在");
        Map<String,Object> data = metadata(row);
        if ("OPEN".equals(data.get("state"))) {
            data.put("body", row.getBody());
            data.put("photoUrl", row.getPhotoKey() == null ? null : signPhoto(row.getPhotoKey()));
        }
        return data;
    }
    public Map<String,Object> list(Long owner, Long beforeId, Integer requestedLimit) {
        requireOwner(owner); if (beforeId != null) requireId(beforeId);
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (limit < 1 || limit > 50) throw new ServiceException("每页应为1至50条");
        List<CapsuleRecord> rows = mapper.page(owner, beforeId, limit + 1);
        boolean more = rows.size() > limit; List<Map<String,Object>> items = new ArrayList<>();
        for (int i=0; i<Math.min(limit, rows.size()); i++) items.add(metadata(rows.get(i)));
        Map<String,Object> data = new LinkedHashMap<>(); data.put("items", items); data.put("hasMore", more);
        data.put("nextBeforeId", more ? String.valueOf(rows.get(limit-1).getId()) : null); return data;
    }
    public Map<String,Object> delete(Long owner, Long id) {
        requireOwner(owner); requireId(id); mapper.delete(owner, id);
        Map<String,Object> result = new LinkedHashMap<>(); result.put("id", String.valueOf(id)); result.put("state", "DELETED"); return result;
    }
}
