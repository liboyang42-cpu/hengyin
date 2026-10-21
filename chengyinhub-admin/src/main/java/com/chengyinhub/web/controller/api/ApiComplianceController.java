package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.ip.IpUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 用户协议同意/撤回事件，不接收客户端提供的文档版本。 */
@RestController
@Tag(name = "合规留痕")
@RequestMapping("/api/compliance")
public class ApiComplianceController extends BaseController {

    // 文档版本号来自配置(compliance.doc-versions.*),发布新版改 yml 即可,不改代码。
    // 场景白名单为结构性约束,随 docType 一并维护在代码内。
    private static final Map<String, Set<String>> DOCUMENT_SCENES;

    static {
        Map<String, Set<String>> scenes = new HashMap<>();
        scenes.put("privacy_policy", setOf("app_launch", "roam_location", "home_city_location"));
        scenes.put("user_service_agreement", setOf("app_launch"));
        scenes.put("photo_model_processing", setOf("object_photo"));
        scenes.put("voice_clone_processing", setOf("merchant_voice"));
        scenes.put("community_content_rules", setOf("publish"));
        scenes.put("refund_rules", setOf("payment"));
        scenes.put("merchant_host_rules", setOf("merchant_onboarding"));
        scenes.put("account_cancellation_notice", setOf("account_cancel"));
        // 报名时向活动主办方提供姓名/手机号的单独同意(PIPL 单独同意)
        scenes.put("activity_host_data_sharing", setOf("activity_signup"));
        scenes.put("merchant_onsite_data_sharing", setOf("merchant_redeem"));
        // 提现时向城瘾提供持卡人姓名/银行账号/预留手机号的单独同意(PIPL 单独同意)。
        // ⚠️ 前端 utils/withdraw-form.js 的 CONSENT_DOC_TYPE/CONSENT_SCENE 必须与这里一致:
        // 对不上时 isAllowedScene 返回 false ⇒ 接口报「协议类型或场景不合法」⇒ 小程序侧
        // .catch 只弹一句网络提示,提现根本不会提交(三个入口全废,且看不出真因)。
        scenes.put("bank_account_collection", setOf("withdrawal"));
        DOCUMENT_SCENES = Collections.unmodifiableMap(scenes);
    }

    @Autowired
    private IConsentRecordService consentRecordService;
    @Autowired
    private com.chengyinhub.business.service.impl.RoamLocationConsentService roamLocationConsentService;

    @Autowired
    private ComplianceProperties complianceProperties;

    @Operation(summary = "记录协议同意或撤回")
    @PostMapping("/consents")
    public AjaxResult record(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        String docType = value(body, "docType");
        String scene = value(body, "scene");
        String eventType = value(body, "eventType");
        String requestId = value(body, "requestId");
        String scopeType = value(body, "scopeType");
        Long scopeId = positiveLong(body, "scopeId");
        String docVersion = complianceProperties.versionOf(docType);
        if (docVersion == null || !isAllowedScene(docType, scene)) {
            return error("协议类型或场景不合法");
        }
        if (!"AGREE".equals(eventType) && !"REVOKE".equals(eventType)) {
            return error("同意事件不合法");
        }
        if (StringUtils.isEmpty(requestId) || requestId.length() > 64) {
            return error("请求标识不合法");
        }
        try {
            if (isMerchantSharing(docType)) {
                if (!"MERCHANT".equals(scopeType) || scopeId == null) return error("授权商家范围不合法");
                consentRecordService.record(memberId, docType, docVersion, scene, scopeType, scopeId,
                        eventType, requestId, IpUtils.getIpAddr(request));
                ConsentRecord readback = consentRecordService.byRequestId(memberId, requestId);
                if (!matchesWrite(readback, docType, docVersion, scene, scopeType, scopeId, eventType) || !requestId.equals(readback.getRequestId())) {
                    return error("授权状态回读失败");
                }
                return success(consentData(readback));
            }
            if (!StringUtils.isEmpty(scopeType) || scopeId != null) return error("授权范围不合法");
            if ("privacy_policy".equals(docType) && ("roam_location".equals(scene) || "home_city_location".equals(scene))) {
                String expected = body.containsKey("expectedRequestId") ? value(body,"expectedRequestId") : null;
                ConsentRecord receipt = "roam_location".equals(scene)
                        ? roamLocationConsentService.record(memberId,eventType,requestId,expected,IpUtils.getIpAddr(request))
                        : roamLocationConsentService.record(memberId,scene,eventType,requestId,expected,IpUtils.getIpAddr(request));
                if (!matchesWrite(receipt, docType, docVersion, scene, null, null, eventType)
                        || !requestId.equals(receipt.getRequestId())) return error("授权状态回读失败");
                return success(consentData(receipt));
            }
            consentRecordService.record(memberId, docType, docVersion, scene, eventType, requestId,
                    IpUtils.getIpAddr(request));
            ConsentRecord readback = consentRecordService.byRequestId(memberId, requestId);
            if (!matchesWrite(readback, docType, docVersion, scene, null, null, eventType)
                    || !requestId.equals(readback.getRequestId())) return error("授权状态回读失败");
            return success(consentData(readback));
        } catch (com.chengyinhub.business.service.impl.RoamLocationConsentService.ConsentChangedException e) {
            return AjaxResult.error(409,e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (RuntimeException e) {
            return error("授权状态暂不可用");
        }
    }

    @Operation(summary = "查询当前用户某协议场景的最新同意状态")
    @PostMapping("/consents/latest")
    public AjaxResult latest(@RequestBody Map<String, Object> body) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        String docType = value(body, "docType");
        String scene = value(body, "scene");
        if (complianceProperties.versionOf(docType) == null || !isAllowedScene(docType, scene)) {
            return error("协议类型或场景不合法");
        }
        String scopeType = value(body, "scopeType");
        Long scopeId = positiveLong(body, "scopeId");
        ConsentRecord latest;
        try {
            if (isMerchantSharing(docType)) {
                if (!"MERCHANT".equals(scopeType) || scopeId == null) return error("授权商家范围不合法");
                latest = consentRecordService.latest(memberId, docType, scene, scopeType, scopeId);
            } else {
                if (!StringUtils.isEmpty(scopeType) || scopeId != null) return error("授权范围不合法");
                latest = consentRecordService.latest(memberId, docType, scene);
            }
        } catch (RuntimeException e) {
            return error("授权状态暂不可用");
        }
        if (latest == null) {
            return success();
        }
        return success(consentData(latest));
    }

    private static Map<String, Object> consentData(ConsentRecord latest) {
        Map<String, Object> data = new HashMap<>();
        data.put("requestId", latest.getRequestId());
        data.put("docType", latest.getDocType());
        data.put("docVersion", latest.getDocVersion());
        data.put("scene", latest.getScene());
        if (latest.getScopeType() != null) data.put("scopeType", latest.getScopeType());
        if (latest.getScopeId() != null) data.put("scopeId", latest.getScopeId());
        data.put("eventType", latest.getEventType());
        data.put("occurredAt", latest.getOccurredAt());
        return data;
    }

    private static boolean matchesWrite(ConsentRecord record, String docType, String docVersion, String scene,
                                        String scopeType, Long scopeId, String eventType) {
        return record != null && docType.equals(record.getDocType()) && docVersion.equals(record.getDocVersion())
                && scene.equals(record.getScene()) && java.util.Objects.equals(scopeType, record.getScopeType())
                && java.util.Objects.equals(scopeId, record.getScopeId()) && eventType.equals(record.getEventType());
    }

    private static boolean isMerchantSharing(String docType) {
        return "merchant_onsite_data_sharing".equals(docType);
    }

    private static Long positiveLong(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) return null;
        try {
            long value = Long.parseLong(String.valueOf(body.get(key)).trim());
            return value > 0L ? value : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isAllowedScene(String docType, String scene) {
        Set<String> scenes = DOCUMENT_SCENES.get(docType);
        return scenes != null && scenes.contains(scene);
    }

    private static String value(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return "";
        }
        return String.valueOf(body.get(key)).trim();
    }

    private static Set<String> setOf(String... values) {
        java.util.HashSet<String> result = new java.util.HashSet<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }
}
