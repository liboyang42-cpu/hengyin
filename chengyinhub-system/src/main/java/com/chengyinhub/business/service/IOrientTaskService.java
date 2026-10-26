package com.chengyinhub.business.service;

import java.util.Map;

/**
 * 城市定向 AR 替代任务(Doc2 F1d)。
 *
 * <p>同一逻辑任务(taskId = cms_topic_node.id)可以经主路径或 SCAN / PHOTO / QUIZ 任一替代方式完成:
 * <ul>
 *   <li>同分:替代配置没有分数字段,分数挂在 taskId 上(F1b 计分),走哪条路都一样;</li>
 *   <li>只能完成一次:orient_task_completion 的唯一键 + 既有主路径 player_node_progress(status=1)共同判定;</li>
 *   <li>拍照待审<b>不算完成</b>:PHOTO 提交只落 attempt(待审),主题发布者审核通过才写完成事实。</li>
 * </ul>
 * 没有替代配置的任务(含全部老主题)行为不变。
 */
public interface IOrientTaskService {

    enum AltType { SCAN, PHOTO, QUIZ }

    /** 发布者为自己主题的任务配置一种替代方式(按 taskId + 类型幂等覆盖)。SCAN/QUIZ 只存答案摘要。 */
    Map<String, Object> configureAlternative(Long memberId, Long topicId, Long taskId, String type,
                                             String promptText, String answer, Boolean enabled);

    /** 玩家视角的任务状态:是否已完成、经哪条路径、是否有待审照片、可用替代方式(不含答案)。 */
    Map<String, Object> taskState(Long memberId, Long topicId, Long activityId, String playInstanceId, Long taskId);

    /** 只读本人原尝试;不重放写入、不返回答案/照片,待审随审核结果更新。 */
    Map<String, Object> receipt(Long memberId, Long topicId, Long activityId, String playInstanceId,
                               Long taskId, String idempotencyKey);

    /** 替代方式提交。返回 outcome:COMPLETED / PENDING_REVIEW / REJECTED / ALREADY_COMPLETED / SUPERSEDED。 */
    Map<String, Object> submit(SubmitCommand command);

    /** 主题发布者审核拍照提交;通过才写完成事实,已被其他路径完成则记为 SUPERSEDED、不重复完成。 */
    Map<String, Object> review(Long reviewerId, Long attemptId, boolean approve);

    /** 照片上传事实校验(与 /api/play/photo 同一校验):IllegalArgumentException=照片无效,IllegalStateException=服务不可用。 */
    interface PhotoVerifier {
        void verify(String picUrl, Long memberId);
    }

    /** 替代提交入参。 */
    final class SubmitCommand {
        public Long memberId;
        public Long topicId;
        public Long activityId;
        public String playInstanceId;
        public Long taskId;
        public String alternativeTaskType;
        public String code;
        public String answer;
        public String picUrl;
        public String coordSys;
        public Double lng;
        public Double lat;
        public String idempotencyKey;
    }
}
