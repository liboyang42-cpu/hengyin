package com.chengyinhub.web.controller.api;

import com.alibaba.druid.wall.WallCheckResult;
import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.spi.MySqlWallProvider;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.ViewRegistration;
import com.chengyinhub.business.service.IViewRegistrationService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第十轮 f2 · j2 F-06:会员付了俱乐部场次(活动层报名 owner_type=2)后,主题详情仍当他没报名,
 * 主按钮还是「¥19.9 随时开玩」、「已参加 0」。
 */
class ApiTopicSessionSignUpTest {

    private static CmsActivity session(long id) {
        CmsActivity activity = new CmsActivity();
        activity.setId(id);
        return activity;
    }

    @Test
    void paidSessionTicketCountsAsSignedUp() {
        IViewRegistrationService registrations = mock(IViewRegistrationService.class);
        when(registrations.selectRegistrationSummaries(argThat(q -> q != null && Long.valueOf(2L).equals(q.getOwnerType())
                && Long.valueOf(9L).equals(q.getOwnerId()) && Long.valueOf(990006L).equals(q.getMemberId())
                && Integer.valueOf(2).equals(q.getRegistrationStatus()))))
                .thenReturn(Collections.singletonList(new ViewRegistration()));

        assertTrue(ApiTopicController.signedUpForAnySession(registrations, Arrays.asList(session(8), session(9)), 990006L));
        assertFalse(ApiTopicController.signedUpForAnySession(registrations, Arrays.asList(session(8)), 990006L),
                "别的场次的票不算");
    }

    @Test
    void guestAndNoSessionsAreNeverSignedUp() {
        IViewRegistrationService registrations = mock(IViewRegistrationService.class);
        assertFalse(ApiTopicController.signedUpForAnySession(registrations, Arrays.asList(session(9)), 0L));
        assertFalse(ApiTopicController.signedUpForAnySession(registrations, Collections.emptyList(), 990006L));
        assertFalse(ApiTopicController.signedUpForAnySession(registrations, null, 990006L));
        verify(registrations, never()).selectRegistrationSummaries(any());
    }

    /** 「已参加」的计数 SQL:并入已付报名后仍须过应用数据源的 Druid wall。 */
    @Test
    void playerCountSqlCountsPaidRegistrantsAndPassesTheWall() throws Exception {
        Path xml = Paths.get("..", "chengyinhub-system", "src", "main", "resources", "mapper", "business",
                "PlayerNodeProgressMapper.xml");
        String text = new String(Files.readAllBytes(xml), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<select id=\"countPlayersByTopicId\"[^>]*>([\\s\\S]*?)</select>").matcher(text);
        assertTrue(m.find());
        String sql = m.group(1).replace("&gt;", ">").replace("&lt;", "<").replace("#{topicId}", "?");
        assertTrue(sql.contains("from cms_registration reg"), "已付报名必须计入「已参加」");
        assertTrue(sql.contains("reg.payment_status = 2") && sql.contains("reg.registration_status = 2"));
        WallConfig config = new WallConfig(MySqlWallProvider.DEFAULT_CONFIG_DIR);
        config.setMultiStatementAllow(false);
        WallCheckResult result = new MySqlWallProvider(config).check(sql);
        assertTrue(result.getViolations().isEmpty(), "Druid wall 拒绝:" + result.getViolations());
    }
}
