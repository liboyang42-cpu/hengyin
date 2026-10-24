package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.UmsMember;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PR10 复审:「查会员 openid → checkText」收拢成一个入口,行为与原先逐处手写一致(含查不到会员时 openid 为 null)。 */
class WxContentSecurityMemberTextTest {

    @Test
    void checksWithTheMembersOpenIdAndPassesVerdictThrough() {
        IWxContentSecurityService security = mock(IWxContentSecurityService.class);
        IUmsMemberService members = mock(IUmsMemberService.class);
        UmsMember member = new UmsMember();
        member.setId(7L);
        member.setWxOpenId("openid-7");
        when(members.selectUmsMemberById(7L)).thenReturn(member);
        when(security.checkText("违规", "openid-7")).thenReturn("内容含违规信息");

        assertEquals("内容含违规信息", IWxContentSecurityService.checkTextAsMember(security, members, 7L, "违规"));
        assertNull(IWxContentSecurityService.checkTextAsMember(security, members, 7L, "正常"));
        verify(security).checkText("正常", "openid-7");
    }

    @Test
    void missingMemberChecksWithNullOpenIdSoTheServiceFailsClosed() {
        IWxContentSecurityService security = mock(IWxContentSecurityService.class);
        IUmsMemberService members = mock(IUmsMemberService.class);
        when(security.checkText("文本", null)).thenReturn(IWxContentSecurityService.REVIEW_REQUIRED);

        assertEquals(IWxContentSecurityService.REVIEW_REQUIRED,
                IWxContentSecurityService.checkTextAsMember(security, members, 99L, "文本"));
    }

    /** 收拢后各写口不再手写 actor.getWxOpenId() 送检。 */
    @Test
    void callersNoLongerInlineTheOpenIdLookup() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("chengyinhub-system"))) root = root.getParent();
        String[] files = {
                "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiChapterMerchantNodeController.java",
                "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiClubEventOpsController.java",
                "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiClubMembershipController.java",
                "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiMerchantChapterApplicationController.java",
                "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiClubLeadController.java",
                "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiCoopPoolController.java",
                "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiRoamShareController.java",
                "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiCoopController.java",
                "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/MerchantCrmCampaignServiceImpl.java"};
        for (String file : files) {
            String source = new String(Files.readAllBytes(root.resolve(file)), StandardCharsets.UTF_8);
            assertFalse(Stream.of(source.split("\n")).anyMatch(line -> line.contains("checkText(")
                            && line.contains("getWxOpenId()")), file);
        }
    }
}
