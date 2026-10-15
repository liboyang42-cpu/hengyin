package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.enums.LimitType;

import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** 通用主题接口；所有局身份按登录会员校验，不接受客户端传入数值或发放结果。 */
@RestController
@RequestMapping("/api/play/theme")
public class ApiThemeRuntimeController extends BaseController {
    @Autowired private ITopicRouteRuntimeService runtime;
    @GetMapping("/support-matrix")
    public AjaxResult supportMatrix() {
        return success(com.chengyinhub.business.service.support.GameplaySupportMatrix.view());
    }
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @GetMapping("/state")
    public AjaxResult state(Long topicId, Long activityId, Long sessionId) {
        return success(runtime.themeView(member(),topicId,activityId,sessionId));
    }
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @PostMapping("/action")
    public AjaxResult action(@RequestBody Action request) {
        if(request==null)throw new IllegalArgumentException("参数不正确");
        if(request.weRun!=null)throw new IllegalArgumentException("仅步数恢复接受微信运动密文");
        if(request.costOption!=null)throw new IllegalArgumentException("此动作不接受任选费用组");
        if(request.choiceKey!=null || request.discardRewards!=null || request.previewVersion!=null)throw new IllegalArgumentException("物资动作不接受新奖励或任务选项");
        return success(runtime.themeAction(member(),request.sessionId,request.expectedVersion,request.actionId,request.operation,request.contentId,request.discardOwned));
    }
    @RateLimiter(time = 10, count = 60, limitType = LimitType.MEMBER)
    @PostMapping("/gameplay-preview")
    public AjaxResult gameplayPreview(@RequestBody Action request) {
        if(request==null)throw new IllegalArgumentException("参数不正确");
        if(request.weRun!=null)throw new IllegalArgumentException("仅步数恢复接受微信运动密文");
        if(request.discardOwned!=null)throw new IllegalArgumentException("只能在已完成局的待领取结算中丢弃持有物资");
        return success(runtime.gameplayPreview(member(),request.sessionId,request.expectedVersion,request.contentId,request.choiceKey,request.discardRewards,request.costOption,request.operation));
    }
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @PostMapping("/gameplay-action")
    public AjaxResult gameplayAction(@RequestBody Action request) {
        if(request==null)throw new IllegalArgumentException("参数不正确");
        if(request.discardOwned!=null)throw new IllegalArgumentException("只能在已完成局的待领取结算中丢弃持有物资");
        if("ZERO_HP_STEPS".equals(request.operation)) {
            if(request.contentId!=null || request.choiceKey!=null || request.costOption!=null || request.discardRewards!=null || request.previewVersion!=null)throw new IllegalArgumentException("步数恢复仅接受当前归零实例与认证微信运动数据");
            return success(runtime.syncZeroHpSteps(member(),request.sessionId,request.expectedVersion,request.actionId,request.weRun));
        }
        if(request.weRun!=null)throw new IllegalArgumentException("仅步数恢复接受微信运动密文");
        return success(runtime.gameplayAction(member(),request.sessionId,request.expectedVersion,request.actionId,request.operation,request.contentId,request.choiceKey,request.discardRewards,request.previewVersion,request.costOption));
    }
    @RateLimiter(time = 10, count = 10, limitType = LimitType.MEMBER)
    @PostMapping("/replay")
    public AjaxResult replay(@RequestBody Action request) { if(request==null)throw new IllegalArgumentException("参数不正确"); if(request.weRun!=null)throw new IllegalArgumentException("仅步数恢复接受微信运动密文"); return success(runtime.replayTheme(member(),request.sessionId)); }
    @GetMapping("/card")
    public AjaxResult card(Long sessionId) {return success(runtime.themeCard(member(),sessionId));}
    @GetMapping("/history")
    public AjaxResult historyPage(Long topicId,Long beforeSessionId,Integer limit) {return success(runtime.themeHistory(member(),topicId,beforeSessionId,limit));}
    public AjaxResult history(Long topicId) { return success(runtime.themeHistory(member(),topicId)); }
    private Long member() {
        Long id=getAppUserId();
        if(id==null || id<=0)throw new com.chengyinhub.common.exception.ServiceException("请先登录",403);
        return id;
    }
    public static class CostOptionDeserializer extends com.fasterxml.jackson.databind.JsonDeserializer<Integer> {
        @Override public Integer deserialize(com.fasterxml.jackson.core.JsonParser parser, com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            if(!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT))throw com.fasterxml.jackson.databind.JsonMappingException.from(parser,"费用组下标须为整数");
            return parser.getIntValue();
        }
    }
    public static class Action {
        public com.fasterxml.jackson.databind.JsonNode weRun;
        public Long sessionId;
        public Integer expectedVersion;
        public String actionId;
        public String operation;
        public String contentId;
        public String choiceKey;
        public com.fasterxml.jackson.databind.JsonNode discardRewards;
        public com.fasterxml.jackson.databind.JsonNode discardOwned;
        public String previewVersion;
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=CostOptionDeserializer.class)
        public Integer costOption;
    }
}
