package com.chengyinhub.business.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 第十一轮 g2,j4 F1:队伍命令被拒时,回执带回队伍服务给玩家看的中文理由;基础设施异常文本不入回执。 */
class TeamEventCommandRejectionTextTest {

    @Test
    void teamServiceRefusalsArePlayerReadable() {
        assertTrue(TeamEventCommandService.playerReadable("暂时无法加入该队伍"));
        assertTrue(TeamEventCommandService.playerReadable("队伍已满或已停止招募"));
        assertTrue(TeamEventCommandService.playerReadable("队伍人数须为 2–4 人"));
        assertTrue(TeamEventCommandService.playerReadable("队长未同意，不能再次申请这支队伍"));
    }

    @Test
    void infrastructureTextStaysOut() {
        assertFalse(TeamEventCommandService.playerReadable(null));
        assertFalse(TeamEventCommandService.playerReadable(" "));
        assertFalse(TeamEventCommandService.playerReadable("Connection is not available, request timed out"));
        assertFalse(TeamEventCommandService.playerReadable("Duplicate entry '1' for key 'PRIMARY'"));
        assertFalse(TeamEventCommandService.playerReadable("12345"));
        assertFalse(TeamEventCommandService.playerReadable("超长".repeat(31)));
    }
}
