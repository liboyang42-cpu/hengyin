package com.chengyinhub.web.controller.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附近的局「举报可处置」不是假保证:三条反向链路都必须真的接到 IRoamHangoutService / IImService。
 * 负控:删掉 applyModeration 的 BIZ_HANGOUT 分支 → 第一条红(审核员判违规后局照样挂在地图上,零告警)。
 */
class RoamHangoutModerationWiringContractTest {

    private static String src(String rel) throws Exception {
        Path p = Paths.get("../" + rel);
        if (!Files.exists(p)) p = Paths.get(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void 审核台处置有HANGOUT分支() throws Exception {
        String s = src("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AuditTaskServiceImpl.java");
        int at = s.indexOf("private void applyModeration(");
        assertTrue(at > 0);
        String body = s.substring(at, s.indexOf("\n    private ", at + 1));
        assertTrue(body.contains("AuditTask.BIZ_HANGOUT.equals(bizType)"), "applyModeration 缺 BIZ_HANGOUT 分支");
        assertTrue(body.contains(".moderate(bizId, down)"), "处置必须落到 IRoamHangoutService.moderate");
    }

    @Test
    void 注销执行期关局() throws Exception {
        String s = src("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AccountDeregistrationServiceImpl.java");
        int at = s.indexOf("private boolean executeOne(");
        String body = s.substring(at, s.indexOf("\n    private ", at + 1));
        assertTrue(body.contains("roamHangoutService.closeAllByMember(memberId)"), "注销执行期必须关掉该成员的局");
    }

    @Test
    void 封面违规回调走IImService关群_不再手抄() throws Exception {
        String s = src("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/WxContentSecurityServiceImpl.java");
        assertTrue(s.contains("imServiceProvider.getObject().closeHangoutGroup(bizId)"));
        assertTrue(!s.contains("imConversationMemberMapper.markAllDeleted"), "关群逻辑只许在 ImServiceImpl 一份");
    }
}
