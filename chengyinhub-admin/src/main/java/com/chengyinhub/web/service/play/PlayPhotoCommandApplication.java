package com.chengyinhub.web.service.play;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.common.constant.PlayMode;
import com.chengyinhub.common.constant.RewardPolicy;
import com.chengyinhub.common.constant.ValidationMethod;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.service.support.SensorChallengePolicy;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.chengyinhub.common.core.domain.AjaxResult.error;
import com.chengyinhub.web.service.play.PlayNodeContext.PlayCtx;

/** Uploaded-photo fact validation and node photo command orchestration. */
@Service
public class PlayPhotoCommandApplication {
    private static final Logger logger = LoggerFactory.getLogger(PlayPhotoCommandApplication.class);

    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;
    @Autowired
    private IShopDayProofService shopDayProofService;
    @Autowired
    private PlayCompletionFacts playCompletionFacts;
    @Autowired
    private PlayNodeCompletionApplication playNodeCompletionApplication;
    @Autowired
    private PlayNodeContext playNodeContext;
    @Autowired
    private PlayBasicIntentApplication playBasicIntentApplication;
    @Autowired
    private PlayTaskPresentation playTaskPresentation;

    @FunctionalInterface
    public interface PlayPhotoUploadFactVerifier {
        void verify(String picUrl, Long memberId);
    }
    private PlayPhotoUploadFactVerifier photoUploadFactVerifier = FileOssUploadUtils::verifyUploadedPhoto;

    /**
     * 校验提交的 picUrl 是否来自现行上传链且对象真实存在(只用于**首次写入前**)。
     * 无效 → 业务错误(不落进度/不发奖/不清历史);存储/配置类不可用 → 503 可恢复(带栈),
     * 存储恢复后重试同一 URL 幂等放行;其余编程异常按 500 暴露并留栈,不得伪装成可恢复错误
     * 诱导无限重试。
     */
    public AjaxResult verifyPhotoUploadFactOrError(String picUrl, Long memberId)
    {
        try {
            photoUploadFactVerifier.verify(picUrl, memberId);
            return null;
        }
        catch (IllegalArgumentException invalid) {
            String message = StringUtils.isBlank(invalid.getMessage())
                    ? "照片无效，请重新拍摄上传" : invalid.getMessage();
            return error(message);
        }
        catch (IllegalStateException unavailable) {
            logger.error("照片上传事实校验暂不可用", unavailable);
            return AjaxResult.error(503, "照片校验服务暂不可用，请稍后重试");
        }
        catch (RuntimeException unexpected) {
            logger.error("照片上传事实校验失败", unexpected);
            return error("照片校验失败，请稍后重试");
        }
    }

    /**
     * 拍照打卡：节点绑定模板验证方式为拍照(2)时，上传照片即完成。
     * 提交即完成 + 存照片 + 置 reviewStatus(自动过=1，留人工审核位)。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public AjaxResult photo(Long actorMemberId, String activityId, String nodeId, String picUrl, String topicId,
            String routeActionId, Integer expectedRouteVersion, Long routeSessionId, String runtimeInstance, Integer authorityEpoch)
    {
        return playBasicIntentApplication.executeBasicIntent(actorMemberId, "PHOTO", activityId, topicId, nodeId,
                routeActionId, expectedRouteVersion, null, routeSessionId, runtimeInstance, authorityEpoch,
                java.util.Arrays.asList(picUrl), originalRun -> photoOnce(actorMemberId, activityId, nodeId, picUrl, topicId, routeActionId, expectedRouteVersion, originalRun));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public AjaxResult photo(Long actorMemberId, String activityId, String nodeId, String picUrl, String topicId,
            String routeActionId, Integer expectedRouteVersion)
    {
        return error("必须提供原运行时身份");
    }

    private AjaxResult photoOnce(Long actorMemberId, String activityId, String nodeId, String picUrl, String topicId,
            String routeActionId, Integer expectedRouteVersion, PlayOriginalRun originalRun)
    {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");
        if (StringUtils.isBlank(nodeId) || (StringUtils.isBlank(activityId) && StringUtils.isBlank(topicId))) return error("参数有误");
        if (StringUtils.isBlank(picUrl)) return error("请先上传照片");
        Long nId;
        try { nId = Long.valueOf(nodeId); }
        catch (Exception e) { return error("参数有误"); }

        CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(nId);
        if (node == null) return error("节点不存在");
        // 规则16:下架节点停止新完成;但本人已有正式完成行(照片已完成)时,重试只是回读同一事实,
        // 必须保留幂等回执,不能被下架闸拦成新错误(否则响应丢失后的重发从「该节点已完成」变成失败)。
        if (playNodeContext.isDelistedNode(node) && !playNodeContext.hasCompletedNodeFact(memberId, activityId, node)) {
            return error("该站已下架，暂时无法完成");
        }
        // M2 统一上下文:活动会话或自玩会话(topicId 二选一)
        PlayCtx ctx = playNodeContext.resolveCtx(memberId, activityId, topicId, node);
        if (ctx.error != null) return ctx.error;
        Long actId = ctx.actId;
        // R18-FREEZE:完成方式与审核开关按开局快照判定;老局快照没有该键时回退实时模板
        CmsMemberTemplate photoTemplate = playNodeContext.effectiveTemplate(memberId, ctx.topicId, ctx.actId, node, originalRun);
        boolean photoFilter = photoTemplate != null && SensorChallengePolicy.isPhotoFilter(
                photoTemplate.getValidationMethod(), photoTemplate.getSensorType());
        if (photoFilter) {
            try {
                SensorChallengePolicy.validatePhotoFilterConfig(photoTemplate.getSensorConfig());
            } catch (IllegalArgumentException e) {
                return error(e.getMessage());
            }
        }
        if (playNodeContext.resolveMode(actId, ctx.topicId) == PlayMode.FREE_COLLECT) {
            PlayerNodeProgress proof;
            try {
                if (photoFilter) {
                    String leadErr = playNodeContext.leadGateError(actId, node);
                    if (leadErr != null) return error(leadErr);
                    String unlockErr = playNodeContext.unlockGateError(memberId, actId, node);
                    if (unlockErr != null) return error(unlockErr);
                }
                if (playCompletionFacts.isExploreChapterRedeemed(memberId, actId, node.getChapterId())) {
                    return error("该章节已核销，不能改写到店凭证");
                }
                if (photoFilter && !shopDayProofService.hasArrived(memberId, actId, node.getId())) {
                    return error("先扫店内静态码进店，再开始任务");
                }
                // 已记录的到店凭证是权威事实:重试(同/异 URL)交给 submitPhoto 既有的幂等/不可覆盖
                // 合同,不再用来源校验打断对象过期或密钥轮换后的正常恢复;首次记录(无 picUrl)仍严验来源。
                PlayerNodeProgress recordedVoucher = playNodeContext.findNodeProgressRow(
                        memberId, actId, node.getTopicId(), node.getId(), originalRun);
                if (recordedVoucher == null || StringUtils.isBlank(recordedVoucher.getPicUrl())) {
                    AjaxResult voucherFactError = verifyPhotoUploadFactOrError(picUrl, memberId);
                    if (voucherFactError != null) return voucherFactError;
                }
                proof = shopDayProofService.submitPhoto(memberId, actId, node.getId(), picUrl);
            } catch (IllegalStateException e) {
                return error(e.getMessage());
            }
            if (photoFilter) {
                playNodeCompletionApplication.logAttempt(memberId, node, actId, 4, 1, null);
                // 奖励或路线推进异常必须越过 controller 边界触发事务回滚，不能转成正常 AjaxResult。
                Map<String, Object> data = playNodeCompletionApplication.advanceProgress(memberId, node, actId, picUrl,
                        null, null, RewardPolicy.XP_BADGE_ONLY, routeActionId,
                        expectedRouteVersion, null, null, originalRun);
                boolean firstTime = Boolean.TRUE.equals(data.get("firstTime"));
                return AjaxResult.success(firstTime ? "已提交,任务完成" : "该节点已完成", data);
            }
            return AjaxResult.success("到店照片已记录", playTaskPresentation.shopDayProofData(node, proof));
        }
        if (playNodeContext.isAppSensorNode(node, photoTemplate)) return error("该站需通过 App 传感器挑战完成");
        if (photoTemplate == null || !Integer.valueOf(ValidationMethod.PHOTO)
                .equals(photoTemplate.getValidationMethod())) {
            return error("该节点不使用拍照完成，请按节点要求验证");
        }
        // P1 带领场闸:集合中不可推进;行进中章节须队长已放行
        String leadErr = playNodeContext.leadGateError(actId, node);
        if (leadErr != null) return error(leadErr);
        String unlockErr = playNodeContext.routeAccessError(memberId, actId, ctx.topicId, node, routeActionId);
        if (unlockErr != null) return error(unlockErr);
        // 已证明完成的节点允许幂等回执(advanceProgress 的 DuplicateKey→firstTime=false 天然防重发),
        // 不再重复校验来源;未完成必须严验,任意 picUrl 不能首次产生进度或奖励。
        PlayerNodeProgress completedPhotoNode = playNodeContext.findNodeProgressRow(memberId, actId, ctx.topicId, node.getId(), originalRun);
        boolean photoNodeAlreadyCompleted = completedPhotoNode != null
                && Integer.valueOf(1).equals(completedPhotoNode.getStatus());
        // 2026-09-17 用户拍板:拍照任务不再有人工审核,一律机审(提交即完成,与 photo_review=0 原行为一致)。
        // 存量待审行(人工审核时代落下的 status=0)在这里按机审通过转正,玩家不再卡「审核中」。
        Long machineApproveProgressId = null;
        if (completedPhotoNode != null && !photoNodeAlreadyCompleted) {
            machineApproveProgressId = completedPhotoNode.getId();
        }
        if (!photoNodeAlreadyCompleted) {
            AjaxResult photoFactError = verifyPhotoUploadFactOrError(picUrl, memberId);
            if (photoFactError != null) return photoFactError;
        }

        // 复用 advanceProgress(幂等/发分/通关),并在首次写入时回填照片与审核位
        playNodeCompletionApplication.logAttempt(memberId, node, actId, 4, 1, null); // M9 拍照打卡成功
        RewardPolicy rewardPolicy = photoFilter
                ? RewardPolicy.XP_BADGE_ONLY : RewardPolicy.FULL;
        Map<String, Object> data = playNodeCompletionApplication.advanceProgress(memberId, node, actId, picUrl, null, null, rewardPolicy,
                routeActionId, expectedRouteVersion, null, machineApproveProgressId, originalRun);
        if (ctx.nightWarning) data.put("nightWarning", true); // M2 自玩夜间提示(不阻断)
        boolean firstTime = Boolean.TRUE.equals(data.get("firstTime"));
        return AjaxResult.success(firstTime ? "已提交,打卡成功" : "该节点已完成", data);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public AjaxResult photo(Long actorMemberId, String activityId, String nodeId, String picUrl, String topicId) {
        return photo(actorMemberId, activityId, nodeId, picUrl, topicId, null, null);
    }
}
