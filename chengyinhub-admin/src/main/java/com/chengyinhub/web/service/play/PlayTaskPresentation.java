package com.chengyinhub.web.service.play;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.common.constant.ValidationMethod;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.service.support.SensorChallengePolicy;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.common.utils.StringUtils;

import org.springframework.stereotype.Service;
import static com.chengyinhub.common.core.domain.AjaxResult.error;

/** Shared shop-day task projection for command responses. */
@Service
public class PlayTaskPresentation {

    @Autowired
    private ICmsMemberTemplateService cmsMemberTemplateService;
    @Autowired
    private com.chengyinhub.business.service.INodeMerchantOverrideService nodeMerchantOverrideService;

    public Map<String, Object> shopDayProofData(CmsTopicNode node, PlayerNodeProgress proof) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodeId", node.getId());
        data.put("arrived", proof != null && proof.getCheckinTime() != null);
        data.put("arrivedAt", proof == null ? null : proof.getCheckinTime());
        data.put("selfReported", proof != null && StringUtils.isNotBlank(proof.getPicUrl()));
        data.put("done", false);
        if (node.getTemplateId() != null) {
            CmsMemberTemplate template = cmsMemberTemplateService.selectCmsMemberTemplateById(node.getTemplateId());
            if (template != null) {
                // ★ 直接发客户端读的归一化名。这条回包在前端是被裸 Object.assign 合并进 node 的
                //   (merchant/index.js 与 play/index.js 各一处),不过 normNode ——
                //   发原始名 ⇒ 归一化键 rule/photoDesc 仍停在进店前的空串 ⇒ 任务卡与三步条第 2 步恒空,
                //   而没配游戏的店整张任务卡就只有这两样,等于弹一张空卡。
                //   一个字段一个名字:归一化点要么是 normNode(列表),要么是这里(进店回包),没有第三处。
                data.put("rule", nodeMerchantOverrideService.forNode(node.getId())
                        .ruleInstructionsOr(template.getRuleInstructions()));
                data.put("photoDesc", template.getPhotoRequireDesc());
                // B5 收窄:进店回包直接带题面,商家页扫码成功即可弹游戏,不必等 /nodes 重拉。
                // 答案字段(questionAnswer/correctAnswer)照旧绝不下发;收费 hint 不在此下发。
                Integer vm = template.getValidationMethod();
                boolean appGame = SensorChallengePolicy.isAppSensor(vm)
                        || SensorChallengePolicy.isPlayablePhotoFilter(vm,
                        template.getSensorType(), template.getSensorConfig());
                boolean questionGame = vm != null && (vm == 1 || vm == 3)
                        && StringUtils.isNotBlank(template.getQuestionName());
                // 偏好题组也是一类可玩内容，和 App 传感器/答题并列
                boolean preferenceGame = vm != null && vm == ValidationMethod.PREFERENCE_GROUP;
                boolean hasGame = appGame || questionGame || preferenceGame;
                data.put("hasGame", hasGame);
                if (preferenceGame) {
                    // 偏好题组只公开「本站有这么个玩法」；题干与评分分支走进店后的 GET /preference，
                    // 不在这里提前发 preferenceJson（评分口径属于玩家私有，不进公开投影）。
                    data.put("validationMethod", vm);
                }
                if (appGame) {
                    data.put("validationMethod", vm);
                    data.put("sensorType", template.getSensorType());
                    data.put("sensorConfig", template.getSensorConfig());
                }
                if (questionGame) {
                    data.put("gameTitle", template.getTitle());
                    data.put("question", template.getQuestionName());
                    data.put("validationMethod", vm);
                    if (vm == 3) {
                        Map<String, Object> options = new LinkedHashMap<>();
                        options.put("A", template.getQuestionA());
                        options.put("B", template.getQuestionB());
                        options.put("C", template.getQuestionC());
                        options.put("D", template.getQuestionD());
                        data.put("options", options);
                    }
                }
            }
        }
        return data;
    }
}
