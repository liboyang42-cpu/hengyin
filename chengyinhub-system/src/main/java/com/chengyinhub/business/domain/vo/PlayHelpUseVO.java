package com.chengyinhub.business.domain.vo;

/**
 * POST /api/play/help/use 的权威回执:本次获准的内容 + 扣减事实。
 *
 * @param level          使用的层级序号
 * @param type           该层形式(TEXT/IMAGE/LETTER/DIGIT/ANSWER)
 * @param content        只含本次获准层的内容,绝不回整份答案表
 * @param applied        本次真的落了一次使用事实(免费或已扣)
 * @param alreadyUnlocked 该层此前已解锁:本次 0 扣,只回放内容(BE-16 验收①)
 * @param chargedPoints  本次真实扣减(只有积分账本 ums_member.point 才可能非 0)
 * @param balanceAfter   扣后余额;免费或重看时为当前余额
 */
public record PlayHelpUseVO(
        long runId,
        long nodeId,
        int level,
        String type,
        String content,
        boolean applied,
        boolean alreadyUnlocked,
        int chargedPoints,
        long balanceAfter) {
}
