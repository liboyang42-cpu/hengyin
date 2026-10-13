package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** C1 防绕过：入口闸之外，IM 发送层必须再次从 club_ 会话反解作用域。 */
class ClubGovernanceParticipationWiringTest {
    @Test
    void joinChatPostAndCommentWritesAllUseTheGovernanceSeam() throws Exception {
        String clubController = source("src/main/java/com/chengyinhub/web/controller/api/ApiClubController.java");
        assertTrue(clubController.contains(
                "clubParticipationGuard.requireAllowed(body.getId(), memberId)"));
        assertTrue(clubController.contains(
                "clubParticipationGuard.requireAllowed(body.getClubId(), memberId)"));
        assertTrue(clubController.contains(
                "clubParticipationGuard.requireAllowed(post.getClubId(), memberId)"));

        String membership = source("../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ClubMemberServiceImpl.java");
        assertTrue(membership.contains("clubParticipationGuard.requireAllowed(clubId, memberId)"),
                "重新入会申请和直入必须共用治理封禁闸");
    }

    @Test
    void staleClubConversationCannotBypassTheDeepImSendGuard() throws Exception {
        String im = source("../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ImServiceImpl.java");
        assertTrue(im.contains("Long governedClubId = clubIdOf(observed)"));
        assertTrue(im.contains(
                "clubParticipationGuard.requireAllowed(governedClubId, memberId)"));
    }

    private String source(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
