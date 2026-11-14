package com.chengyinhub.web.controller.api.appgaps;

import com.chengyinhub.business.appgaps.AppOriginalEnvelope;
import com.chengyinhub.business.appgaps.ProjectStateRequests;
import com.chengyinhub.web.controller.api.ApiProjectController;
import com.chengyinhub.web.controller.api.ApiTopicController;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.web.controller.api.ApiActivityController;
import com.chengyinhub.web.controller.api.ApiTemplateController;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * App 原始请求信封：活动创建（/api/activity/create-requests/*）与个人模板草稿/发布（/api/template/author-requests/*）。
 * 原写原样复用 /api/activity/publish、/api/template/draft|publish 的控制器方法（含全部鉴权、送检、配额），
 * 由 AppOriginalEnvelope 保证同一 requestId 只执行一次并可读回原结果。
 */
@RestController
@Tag(name = "App 原始请求信封")
public class ApiAppGapsOriginalRequestController extends BaseController {
    private final AppOriginalEnvelope envelopes;
    private final ObjectProvider<ApiActivityController> activities;
    private final ObjectProvider<ApiTemplateController> templates;
    private final ObjectProvider<Validator> validator;
    @org.springframework.beans.factory.annotation.Autowired private ProjectStateRequests stateRequests;
    @org.springframework.beans.factory.annotation.Autowired private ObjectProvider<ApiProjectController> projects;
    @org.springframework.beans.factory.annotation.Autowired private ObjectProvider<ApiTopicController> topics;
    private static final ObjectMapper FIELDS = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public ApiAppGapsOriginalRequestController(AppOriginalEnvelope envelopes, ObjectProvider<ApiActivityController> activities,
                                               ObjectProvider<ApiTemplateController> templates, ObjectProvider<Validator> validator) {
        this.envelopes = envelopes; this.activities = activities; this.templates = templates; this.validator = validator;
    }

    private Long actor() {
        try { return getAppUserId(); } catch (Exception e) { return null; }
    }

    private static AjaxResult fail(ServiceException e) { return AjaxResult.error(e.getCode() == null ? 400 : e.getCode(), e.getMessage()); }

    @PostMapping("/api/activity/create-requests/submit")
    public AjaxResult activitySubmit(@RequestBody(required = false) Map<String, Object> body) {
        Long actor = actor();
        if (actor == null) return AjaxResult.error(401, "请先登录");
        try {
            AppOriginalEnvelope.Original original = AppOriginalEnvelope.parseSubmit(AppOriginalEnvelope.ACTIVITY, body);
            return AjaxResult.success(envelopes.submit(actor, original, o -> {
                CmsActivityPublishRequest request = convert(o.payload(), CmsActivityPublishRequest.class);
                request.setRequestId(o.requestId());
                return activities.getObject().publish(request);
            }));
        } catch (ServiceException e) { return fail(e); }
    }

    @PostMapping({"/api/activity/create-requests/read", "/api/activity/create-requests/resume-post-processing"})
    public AjaxResult activityRead(@RequestBody(required = false) Map<String, Object> body) {
        Long actor = actor();
        if (actor == null) return AjaxResult.error(401, "请先登录");
        try { return AjaxResult.success(envelopes.read(actor, AppOriginalEnvelope.ACTIVITY, body)); }
        catch (ServiceException e) { return fail(e); }
    }

    @PostMapping("/api/template/author-requests/submit")
    public AjaxResult templateSubmit(@RequestBody(required = false) Map<String, Object> body) {
        Long actor = actor();
        if (actor == null) return AjaxResult.error(401, "请先登录");
        try {
            AppOriginalEnvelope.Original original = AppOriginalEnvelope.parseSubmit(AppOriginalEnvelope.TEMPLATE, body);
            return AjaxResult.success(envelopes.submit(actor, original, o -> {
                @SuppressWarnings("unchecked") Map<String, Object> fields = (Map<String, Object>) o.payload().get("fields");
                CmsTemplatePublishRequest request = convert(fields, CmsTemplatePublishRequest.class);
                request.setRequestId(o.requestId());
                ApiTemplateController controller = templates.getObject();
                return "api/template/publish".equals(o.payload().get("originalPath")) ? controller.publish(request) : controller.draft(request);
            }));
        } catch (ServiceException e) { return fail(e); }
    }

    @PostMapping("/api/template/author-requests/read")
    public AjaxResult templateRead(@RequestBody(required = false) Map<String, Object> body) {
        Long actor = actor();
        if (actor == null) return AjaxResult.error(401, "请先登录");
        try { return AjaxResult.success(envelopes.read(actor, AppOriginalEnvelope.TEMPLATE, body)); }
        catch (ServiceException e) { return fail(e); }
    }

    /** 创作者项目原始状态请求：context 读基线，submit 只执行一次 delete/上下架，read 读回。 */
    @PostMapping("/api/project/state-requests/context")
    public AjaxResult stateContext(@RequestBody(required = false) Map<String, Object> body) {
        if (actor() == null) return AjaxResult.error(401, "请先登录");
        try { return AjaxResult.success(stateRequests.context(body, projectPort())); }
        catch (ServiceException e) { return fail(e); }
    }

    @PostMapping("/api/project/state-requests/submit")
    public AjaxResult stateSubmit(@RequestBody(required = false) Map<String, Object> body) {
        Long actor = actor();
        if (actor == null) return AjaxResult.error(401, "请先登录");
        try { return AjaxResult.success(stateRequests.submit(actor, body, projectPort())); }
        catch (ServiceException e) { return fail(e); }
    }

    @PostMapping("/api/project/state-requests/read")
    public AjaxResult stateRead(@RequestBody(required = false) Map<String, Object> body) {
        Long actor = actor();
        if (actor == null) return AjaxResult.error(401, "请先登录");
        try { return AjaxResult.success(stateRequests.read(actor, body)); }
        catch (ServiceException e) { return fail(e); }
    }

    @SuppressWarnings("unchecked")
    private ProjectStateRequests.Projects projectPort() {
        return new ProjectStateRequests.Projects() {
            public Map<String, Object> row(String kind, long id) {
                AjaxResult page = projects.getObject().my("all", null, null, 1, 10_000, null);
                if (!Integer.valueOf(200).equals(page.get(AjaxResult.CODE_TAG))) throw new ServiceException(String.valueOf(page.get(AjaxResult.MSG_TAG)), 500);
                Object rows = ((Map<String, Object>) page.get(AjaxResult.DATA_TAG)).get("rows");
                for (Map<String, Object> row : (java.util.List<Map<String, Object>>) rows)
                    if (kind.equals(row.get("bizType")) && row.get("id") instanceof Number n && n.longValue() == id) return row;
                return null;
            }
            public AjaxResult write(String path, Map<String, String> f) {
                return switch (path) {
                    case "api/topic/delete" -> topics.getObject().topicDelete(f.get("id"), null);
                    case "api/topic/update_user_status" -> topics.getObject().topicUpdateUserStatus(f.get("id"), f.get("expectedUserStatus"), null);
                    case "api/activity/delete" -> activities.getObject().activityDelete(f.get("id"), null);
                    case "api/activity/update_publish_status" -> activities.getObject().activityUpdatePublishStatus(f.get("id"), null);
                    case "api/template/delete" -> templates.getObject().deleteTemplate(f.get("template_id"), null);
                    case "api/template/updateLibraryStatus" -> templates.getObject().updateTemplateLibraryStatus(f.get("template_id"), f.get("status"), null);
                    default -> throw new ServiceException("不支持的原始路径", 400);
                };
            }
        };
    }

    private <T> T convert(Map<String, Object> fields, Class<T> type) {
        T value;
        try { value = FIELDS.convertValue(fields, type); }
        catch (IllegalArgumentException e) { throw new ServiceException("原始请求字段无法解析", 422); }
        Validator v = validator.getIfAvailable();
        if (v != null) {
            var violations = v.validate(value);
            if (!violations.isEmpty()) throw new ServiceException(violations.iterator().next().getMessage(), 422);
        }
        return value;
    }
}
