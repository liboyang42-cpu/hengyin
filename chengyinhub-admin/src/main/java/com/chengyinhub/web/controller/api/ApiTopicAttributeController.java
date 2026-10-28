package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.TopicKeyRequestDTO;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.support.ThemeAttributeService;
import com.chengyinhub.business.service.support.ThemeKeyNamingService;
import com.chengyinhub.business.service.support.TopicAuthorGate;
import com.chengyinhub.business.util.JourneyReviewTexts;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.function.Function;

/**
 * 文档二 L8:d20 属性与「作者只写名字」的线索 / 标签 / 数值。键名一律系统生成,界面不显示。
 *
 * <p>归属闸、编辑窗口闸与状态键接口同口径({@link TopicAuthorGate});玩家可见的名字走内容安全闸。
 * 契约见 docs/design/doc2-ui-impl-20261028/api-contracts.md §2.4 / §3。
 */
@RestController
@Tag(name = "主题属性与线索")
@RequestMapping("/api/topic")
public class ApiTopicAttributeController extends BaseController {

    @Autowired
    private TopicAuthorGate topicAuthorGate;

    @Autowired
    private ThemeAttributeService themeAttributeService;

    @Autowired
    private ThemeKeyNamingService themeKeyNamingService;

    @Autowired
    private IWxContentSecurityService wxContentSecurityService;

    // ==================== 属性 ====================

    @Operation(summary = "d20 属性列表、规则难度与保存主题用的绑定(仅创作者本人)")
    @GetMapping("/attributes")
    public AjaxResult attributes(Long topicId, String scope) {
        return read(topicId, scope, topic -> themeAttributeService.view(topic, !topicAuthorGate.frozen(topic)));
    }

    @Operation(summary = "添加属性:点预设或自定义名字(仅创作者本人)")
    @PostMapping("/attributes")
    public AjaxResult addAttribute(@RequestBody TopicKeyRequestDTO dto) {
        return write(dto, dto == null ? null : dto.getLabel(),
                topic -> themeAttributeService.add(topic, dto.getPreset(), dto.getLabel()));
    }

    @Operation(summary = "修改属性范围与开局值(仅创作者本人)")
    @PutMapping("/attributes")
    public AjaxResult updateAttribute(@RequestBody TopicKeyRequestDTO dto) {
        return write(dto, null, topic -> themeAttributeService.updateRange(topic, dto.getKey(), dto.getMin(),
                dto.getMax(), dto.getInitial()));
    }

    @Operation(summary = "属性排序(仅创作者本人)")
    @PutMapping("/attributes/order")
    public AjaxResult orderAttributes(@RequestBody TopicKeyRequestDTO dto) {
        return write(dto, null, topic -> themeAttributeService.reorder(topic, dto.getKeys()));
    }

    @Operation(summary = "删除属性(被用到时拒绝并列出用在哪里)")
    @DeleteMapping("/attributes")
    public AjaxResult deleteAttribute(Long topicId, String key, String scope) {
        return write(request(topicId, key, scope), null, topic -> {
            themeAttributeService.delete(topic, key);
            return null;
        });
    }

    // ==================== 线索 / 标签 / 数值 ====================

    @Operation(summary = "按名字新建线索、标签或数值,键名系统生成(仅创作者本人)")
    @PostMapping("/state-keys/named")
    public AjaxResult createNamed(@RequestBody TopicKeyRequestDTO dto) {
        return write(dto, dto == null ? null : dto.getLabel(),
                topic -> themeKeyNamingService.create(topic, dto.getKind(), dto.getLabel()));
    }

    @Operation(summary = "改名:只改名字,键名不变(仅创作者本人)")
    @PutMapping("/state-keys/label")
    public AjaxResult rename(@RequestBody TopicKeyRequestDTO dto) {
        return write(dto, dto == null ? null : dto.getLabel(),
                topic -> themeKeyNamingService.rename(topic, dto.getKey(), dto.getLabel()));
    }

    @Operation(summary = "开局带着的线索与标签(整组覆盖;仅创作者本人)")
    @PutMapping("/state-keys/start")
    public AjaxResult startWith(@RequestBody TopicKeyRequestDTO dto) {
        return write(dto, null, topic -> Collections.singletonMap("items",
                themeKeyNamingService.setStartWith(topic, dto.getKeys())));
    }

    @Operation(summary = "线索、标签、数值列表与用在哪里(只读;仅创作者本人)")
    @GetMapping("/state-keys/usage")
    public AjaxResult usage(Long topicId, String scope) {
        return read(topicId, scope, topic -> Collections.singletonMap("items", themeKeyNamingService.usage(topic)));
    }

    @Operation(summary = "删除线索、标签或数值(被用到时拒绝并列出用在哪里)")
    @DeleteMapping("/state-keys/named")
    public AjaxResult deleteNamed(Long topicId, String key, String scope) {
        return write(request(topicId, key, scope), null, topic -> {
            themeKeyNamingService.delete(topic, key);
            return null;
        });
    }

    // ==================== 小工具 ====================

    private AjaxResult read(Long topicId, String scope, Function<CmsTopic, Object> action) {
        CmsTopic topic = topicAuthorGate.topic(topicId);
        String denial = topicAuthorGate.denial(memberId(), topic, scope);
        if (denial != null) {
            return error(denial);
        }
        try {
            return success(action.apply(topic));
        } catch (ServiceException e) {
            return codedServiceException(e);
        }
    }

    private AjaxResult write(TopicKeyRequestDTO dto, String playerText, Function<CmsTopic, Object> action) {
        Long memberId = memberId();
        CmsTopic topic = topicAuthorGate.topic(dto == null ? null : dto.getTopicId());
        String denial = topicAuthorGate.denial(memberId, topic, dto == null ? null : dto.getScope());
        if (denial != null) {
            return error(denial);
        }
        if (topicAuthorGate.frozen(topic)) {
            return error(TopicAuthorGate.MSG_FROZEN).put("errorCode", TopicAuthorGate.ERR_FROZEN);
        }
        try {
            if (playerText != null && !playerText.trim().isEmpty()) {
                String secMsg = JourneyReviewTexts.check(wxContentSecurityService, topicAuthorGate.openid(memberId),
                        Collections.singletonList(new JourneyReviewTexts.Entry("themeStateKey.label", playerText.trim())));
                if (secMsg != null) {
                    return error(secMsg);
                }
            }
            Object data = action.apply(topic);
            return data == null ? success("删除成功") : success(data);
        } catch (ServiceException e) {
            return codedServiceException(e);
        }
    }

    private static TopicKeyRequestDTO request(Long topicId, String key, String scope) {
        TopicKeyRequestDTO dto = new TopicKeyRequestDTO();
        dto.setTopicId(topicId);
        dto.setKey(key);
        dto.setScope(scope);
        return dto;
    }

    private Long memberId() {
        try {
            Long id = getAppUserId();
            return id == null || id <= 0L ? null : id;
        } catch (Exception e) {
            return null;
        }
    }
}
