package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantOperationScope;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.TopicStateKeyRegisterDTO;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.business.service.support.TopicPublishCheckService;
import com.chengyinhub.business.service.support.TopicStateKeyRefService;
import com.chengyinhub.business.util.JourneyReviewTexts;
import com.chengyinhub.business.util.TopicEditWindow;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.OwnershipUtils;
import com.chengyinhub.common.utils.StringUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;

/**
 * 创作者端主题状态键(R8)与发布前试检查。
 *
 * <p>权限口径照主题编辑接口:登录会员本人,或显式以商家 Owner 主体(scope=MERCHANT)代管;
 * 主题不是自己的直接拒。这些接口只读写主题自己的注册表 / 配置,不改主题发布状态。
 */
@RestController
@Tag(name = "主题状态键")
@RequestMapping("/api/topic")
public class ApiTopicStateKeyController extends BaseController {

    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired
    private ThemeStateKeyService themeStateKeyService;

    @Autowired
    private TopicStateKeyRefService topicStateKeyRefService;

    @Autowired
    private TopicPublishCheckService topicPublishCheckService;

    @Autowired
    private IMerchantAccessService merchantAccessService;

    /** R12:label 是玩家可见文本,与主题/模板同走内容安全闸。 */
    @Autowired
    private IWxContentSecurityService wxContentSecurityService;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Operation(summary = "主题状态键列表(仅创作者本人)")
    @GetMapping("/state-keys")
    public AjaxResult listStateKeys(Long topicId, String scope) {
        if (invalidTopicId(topicId)) {
            return error("参数不合规");
        }
        CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
        String denial = editableDenial(topic, scope);
        if (denial != null) {
            return error(denial);
        }
        return success(themeStateKeyService.listByTopic(topic.getId()));
    }

    @Operation(summary = "批量注册主题状态键(仅创作者本人;复用注册表校验)")
    @PostMapping("/state-keys")
    public AjaxResult registerStateKeys(@RequestBody TopicStateKeyRegisterDTO dto) {
        if (dto == null || invalidTopicId(dto.getTopicId())) {
            return error("参数不合规");
        }
        CmsTopic topic = cmsTopicService.selectCmsTopicById(dto.getTopicId());
        String denial = editableDenial(topic, dto.getScope());
        if (denial != null) {
            return error(denial);
        }
        String windowDenial = editWindowDenial(topic);
        if (windowDenial != null) {
            return error(windowDenial);
        }
        try {
            // R12:状态键 label 是玩家可见文本,与主题/模板同一道内容安全闸;命中即整批拒绝并指出字段。
            String secMsg = JourneyReviewTexts.check(wxContentSecurityService, resolveOpenid(),
                    JourneyReviewTexts.collectLabels(dto.getKeys()));
            if (secMsg != null) {
                return error(secMsg);
            }
            themeStateKeyService.register(topic.getId(), dto.getKeys());
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
        return success("注册成功");
    }

    /** 内容安全送检的 openid:取实际操作者;查不到返回 null(降级 scene 检测)。 */
    private String resolveOpenid() {
        try {
            Long memberId = getAppUserId();
            if (memberId != null && memberId > 0L) {
                UmsMember member = umsMemberService.selectUmsMemberById(memberId);
                if (member != null) {
                    return member.getWxOpenId();
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    /**
     * 删除一个状态键。被任何节点块配置或主题剧情配置引用时禁止删除,并列出引用位置:
     * 引用还在而键没了,发布闸以后再也拦不住这类坏引用。
     */
    @Operation(summary = "删除主题状态键(被引用时拒绝并列出引用位置)")
    @DeleteMapping("/state-keys")
    public AjaxResult deleteStateKey(Long topicId, String key, String scope) {
        if (invalidTopicId(topicId)) {
            return error("参数不合规");
        }
        CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
        String denial = editableDenial(topic, scope);
        if (denial != null) {
            return error(denial);
        }
        String windowDenial = editWindowDenial(topic);
        if (windowDenial != null) {
            return error(windowDenial);
        }
        if (StringUtils.isEmpty(key)) {
            return error("状态键名不能为空");
        }
        try {
            List<String> positions = topicStateKeyRefService.referencesOf(topic, key);
            if (!positions.isEmpty()) {
                return error("状态键 " + key + " 已被引用,不能删除。引用位置:" + String.join("、", positions));
            }
            return themeStateKeyService.delete(topic.getId(), key) ? success("删除成功") : error("状态键不存在");
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 发布前试检查:把发布路径会拦的问题一次列全(error),外加不拦但有话要说的说明(warn)。
     * 只读,不改变任何状态;真正发布时的拦截行为不受本接口影响。
     */
    @Operation(summary = "发布前试检查(结构化问题列表;仅创作者本人)")
    @GetMapping("/publish-check")
    public AjaxResult publishCheck(Long topicId, String scope) {
        if (invalidTopicId(topicId)) {
            return error("参数不合规");
        }
        CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
        String denial = editableDenial(topic, scope);
        if (denial != null) {
            return error(denial);
        }
        return success(topicPublishCheckService.check(topic));
    }

    private static boolean invalidTopicId(Long topicId) {
        return topicId == null || topicId <= 0L;
    }

    /**
     * 归属闸:登录 → 商家 Owner 主体(仅显式声明的代管场景)→ 主题本人。
     * 通过返回 null;否则返回给调用方的拒绝文案。
     */
    private String editableDenial(CmsTopic topic, String scope) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return "请先登录";
        }
        if (memberId == null || memberId <= 0L) {
            return "请先登录";
        }
        if (MerchantOperationScope.isMerchant(scope)) {
            try {
                memberId = merchantAccessService.require(memberId, MerchantPermission.PROJECT_MANAGE)
                        .getOwnerMemberId();
            } catch (ServiceException e) {
                return e.getMessage();
            }
        }
        if (topic == null || (topic.getDelFlag() != null && topic.getDelFlag() == 2)) {
            return "主题不存在";
        }
        return OwnershipUtils.notOwner(topic.getMemberId(), memberId) ? "无权操作该主题" : null;
    }

    /**
     * 编辑窗口闸:与主题编辑接口(/api/topic/update)同一口径 —— 复用
     * {@link TopicEditWindow#resolve}(生命周期 / 草稿 / 日期三档判定),不另写判断。
     * SELLING 起商业结构冻结:注册表同属结构,写接口一律拒;GET 与 publish-check 是只读,不拦。
     */
    private String editWindowDenial(CmsTopic topic) {
        TopicEditWindow.Scope scope;
        try {
            scope = TopicEditWindow.resolve(
                    topic, cmsTopicService.selectTopicIsDraft(topic.getId()), new Date());
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
        if (scope == TopicEditWindow.Scope.SELLING_FROZEN) {
            return "主题已进入 SELLING，创作者接口不能修改状态键注册表";
        }
        return null;
    }
}
