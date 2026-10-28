package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.TopicPlaySetupDTO;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.support.TopicAuthorGate;
import com.chengyinhub.business.service.support.TopicPlaySetupService;
import com.chengyinhub.business.util.JourneyReviewTexts;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档二 L8:主题的可玩时段与开局前准备。创作者读写走归属闸 + 编辑窗口闸;
 * 玩家读只看已发布主题,准备项勾选不落库。契约见 docs/design/doc2-ui-impl-20261028/api-contracts.md §1。
 */
@RestController
@Tag(name = "主题开局设置")
public class ApiTopicPlaySetupController extends BaseController {

    @Autowired
    private TopicAuthorGate topicAuthorGate;

    @Autowired
    private TopicPlaySetupService topicPlaySetupService;

    @Autowired
    private IWxContentSecurityService wxContentSecurityService;

    @Operation(summary = "可玩时段与开局前准备(仅创作者本人)")
    @GetMapping("/api/topic/play-setup")
    public AjaxResult authorView(Long topicId, String scope) {
        CmsTopic topic = topicAuthorGate.topic(topicId);
        String denial = topicAuthorGate.denial(memberId(), topic, scope);
        if (denial != null) {
            return error(denial);
        }
        return success(topicPlaySetupService.authorView(topic));
    }

    @Operation(summary = "保存可玩时段与开局前准备(仅创作者本人;整体覆盖)")
    @PutMapping("/api/topic/play-setup")
    public AjaxResult save(@RequestBody TopicPlaySetupDTO dto) {
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
            // 准备项是玩家可见文本,与状态键 label 同一道内容安全闸。
            List<JourneyReviewTexts.Entry> entries = new ArrayList<JourneyReviewTexts.Entry>();
            for (String text : TopicPlaySetupService.prepTexts(dto)) {
                entries.add(new JourneyReviewTexts.Entry("playSetup.prepItems", text));
            }
            String secMsg = JourneyReviewTexts.check(wxContentSecurityService, topicAuthorGate.openid(memberId), entries);
            if (secMsg != null) {
                return error(secMsg);
            }
            return success(topicPlaySetupService.save(topic, dto));
        } catch (ServiceException e) {
            return coded(e);
        }
    }

    @Operation(summary = "玩家看到的可玩时段与开局前准备(已发布主题)")
    @GetMapping("/api/play/setup")
    public AjaxResult playerView(Long topicId) {
        if (memberId() == null) {
            return error("请先登录");
        }
        CmsTopic topic = topicAuthorGate.topic(topicId);
        if (topic == null || (topic.getDelFlag() != null && topic.getDelFlag() == 2)
                || !Integer.valueOf(1).equals(topic.getStatus())) {
            return error("主题不存在");
        }
        return success(topicPlaySetupService.playerView(topic));
    }

    static AjaxResult coded(ServiceException e) {
        if (e.getCode() == null) {
            return AjaxResult.error(e.getMessage());
        }
        AjaxResult result = new AjaxResult(e.getCode(), e.getMessage());
        if (e.getDetailMessage() != null) {
            result.put("errorCode", e.getDetailMessage());
        }
        if (e.getData() != null) {
            result.put(AjaxResult.DATA_TAG, e.getData());
        }
        return result;
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
