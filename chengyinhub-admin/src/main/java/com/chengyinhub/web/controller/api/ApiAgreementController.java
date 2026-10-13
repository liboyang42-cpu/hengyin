package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.AgreementDocument;
import com.chengyinhub.business.service.IAgreementDocumentService;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.ip.IpUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/** 公开协议文档与登录后的同意留痕。版本始终以服务端生效文档为准。 */
@RestController
@Tag(name = "协议文档")
@RequestMapping("/api/agreement")
public class ApiAgreementController extends BaseController {
    @Autowired
    private IAgreementDocumentService agreementDocumentService;

    @Autowired
    private IConsentRecordService consentRecordService;

    @Anonymous
    @GetMapping("/get")
    @Operation(summary = "读取生效协议")
    public AjaxResult get(String type) {
        if (!isSupportedType(type)) {
            return error("协议类型不合法");
        }
        AgreementDocument document = agreementDocumentService.getActive(type);
        if (document == null) {
            return error("协议暂未发布");
        }
        return success(document);
    }

    @Anonymous
    @GetMapping("/versions")
    @Operation(summary = "读取生效协议版本")
    public AjaxResult versions() {
        return success(agreementDocumentService.activeVersions());
    }

    @PostMapping("/consent")
    @Operation(summary = "记录当前用户对生效协议的同意")
    public AjaxResult consent(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        String type = value(body, "type");
        String requestId = value(body, "requestId");
        if (!isSupportedType(type) || StringUtils.isEmpty(requestId) || requestId.length() > 64) {
            return error("同意参数不合法");
        }
        AgreementDocument document = agreementDocumentService.getActive(type);
        if (document == null) {
            return error("协议暂未发布");
        }
        consentRecordService.record(memberId, type, document.getVersion(), "app_launch", "AGREE", requestId,
                IpUtils.getIpAddr(request));
        return success();
    }

    private boolean isSupportedType(String type) {
        return "privacy".equals(type) || "user_agreement".equals(type);
    }

    private String value(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return "";
        }
        return String.valueOf(body.get(key)).trim();
    }
}
