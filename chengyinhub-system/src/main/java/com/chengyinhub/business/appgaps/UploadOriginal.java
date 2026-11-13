package com.chengyinhub.business.appgaps;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * App IM 图片上传原始回执（original-upload-request-v1）：/api/common/uploadOSS 带 X-Upload-Request-ID 时同一请求号只上传一次，
 * /api/common/uploadOSS/receipt?request_id=&source_sha256= 读回。字段平铺在 AjaxResult 顶层（与旧 uploadOSS 的 url 同层）。
 * 摘要由服务端对文件原字节重算。未过审的上传只回 PENDING，不下发地址。
 */
@Service
public class UploadOriginal {
    public static final String PROTOCOL = "original-upload-request-v1";
    private final AppOriginalRequestLedger ledger;

    public UploadOriginal(AppOriginalRequestLedger ledger) { this.ledger = ledger; }

    public static boolean requestId(String v) {
        return v != null && v.matches("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}");
    }

    /** legacy 返回旧 uploadOSS 的 AjaxResult（code/fileName/url/reviewState）。 */
    public AjaxResult upload(long actor, String requestId, byte[] bytes, Supplier<AjaxResult> legacy) {
        if (!requestId(requestId)) return AjaxResult.error(400, "上传请求编号无效");
        if (bytes == null || bytes.length == 0) return AjaxResult.error(400, "文件为空");
        String sha = AppOriginalRequestLedger.sha256Hex(bytes);
        AppOriginalRequestLedger.Fact fact;
        try {
            fact = ledger.run(PROTOCOL, actor, requestId, "", sha, () -> {
                AjaxResult r = AppOriginalRequestLedger.requireOk(legacy.get());
                Map<String, Object> stored = new LinkedHashMap<>();
                stored.put("fileName", r.get("fileName")); stored.put("url", r.get("url"));
                stored.put("reviewState", r.get("reviewState") == null ? "APPROVED" : r.get("reviewState"));
                return stored;
            });
        } catch (ServiceException e) {
            return AjaxResult.error(e.getCode() == null ? 409 : e.getCode(), e.getMessage());
        }
        return present(requestId, sha, fact);
    }

    public AjaxResult receipt(long actor, String requestId, String sha) {
        if (!requestId(requestId) || !AppOriginalRequestLedger.sha256(sha)) return AjaxResult.error(400, "读回参数无效");
        AppOriginalRequestLedger.Fact fact;
        try { fact = ledger.read(PROTOCOL, actor, requestId, "", sha); }
        catch (ServiceException e) { return AjaxResult.error(e.getCode() == null ? 409 : e.getCode(), e.getMessage()); }
        return present(requestId, sha, fact);
    }

    static AjaxResult present(String requestId, String sha, AppOriginalRequestLedger.Fact fact) {
        if (AppOriginalRequestLedger.REJECTED.equals(fact.state()))
            return AjaxResult.error(fact.errorCode() == null ? 500 : fact.errorCode(), fact.errorMessage());
        AjaxResult r = AjaxResult.success();
        r.put("uploadProtocol", PROTOCOL); r.put("uploadRequestId", requestId); r.put("sourceSHA256", sha);
        if (!fact.committed()) { r.put("uploadState", "NOT_FOUND"); r.put("url", null); return r; }
        Map<String, Object> s = fact.result();
        boolean approved = "APPROVED".equals(s.get("reviewState")) && s.get("url") != null;
        r.put("uploadState", approved ? "UPLOADED" : "PENDING");
        r.put("url", approved ? s.get("url") : null);
        r.put("fileName", s.get("fileName")); r.put("reviewState", s.get("reviewState"));
        return r;
    }
}
