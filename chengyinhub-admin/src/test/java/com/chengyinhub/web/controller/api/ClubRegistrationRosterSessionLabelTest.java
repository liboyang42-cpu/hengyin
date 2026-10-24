package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.vo.ClubRegistrationRosterVO;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IViewRegistrationService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * CU-C-153:核销台账里同日多场的票组标题必须彼此可辨。
 *
 * 现场证据:主题 #990030 连开多场,台账展开后有多组逐字相同的「9月22日 · 标准场次票」,
 * 分别对应不同场次的复制票,里面有已核销、有待核销、还有无人报名的 —— 工作人员在场上
 * 无法判断该点哪一组核销或退款。根因只有一处:场次前缀 sessionLabel 格式到「M月d日」
 * 就停了,把一天里不同的两场压成同一个字符串。
 *
 * 判据因此不是「标题里有日期」,而是「同一主题下任意两张不同场次的票组标题不相等」,
 * 并且不许为了显得可辨而伪造一个没人填过的时刻:存量场次没回填集合时刻,库里存的是 00:00,
 * 那是「没填」而不是「半夜集合」,显示出来就是在骗带队的人 —— 这一档同名改由场次序号兜。
 */
class ClubRegistrationRosterSessionLabelTest {

    private static final Long CLUB = 42L;
    private static final Long TOPIC = 77L;
    private static final Long OWNER = 9L;

    private ApiClubController controller;
    private IClubService clubService;
    private IClubMemberService memberService;
    private ICmsTopicService topicService;
    private IOmsTicketService ticketService;
    private IViewRegistrationService registrationService;
    private ICmsActivityService activityService;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiClubController());
        clubService = mock(IClubService.class);
        memberService = mock(IClubMemberService.class);
        topicService = mock(ICmsTopicService.class);
        ticketService = mock(IOmsTicketService.class);
        registrationService = mock(IViewRegistrationService.class);
        activityService = mock(ICmsActivityService.class);
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "clubMemberService", memberService);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "omsTicketService", ticketService);
        ReflectionTestUtils.setField(controller, "viewRegistrationService", registrationService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", activityService);
    }

    @Test
    void sameDaySessionsWithDifferentAssemblyTimesGetDistinguishableTicketTitles() {
        List<String> names = rosterTicketNames(
                session(501L, assemblyTime(9, 22, 9, 0)),
                session(502L, assemblyTime(9, 22, 15, 30)));

        assertEquals(2, names.size());
        assertNotEquals(names.get(0), names.get(1),
                "同日两场的标题逐字相同 ⇒ 台账里点哪组全凭运气(「9月22日 · 标准场次票」×2)");
        assertTrue(names.get(0).contains("09:00"), "集合时刻是唯一的区分信息,标题必须带上:" + names.get(0));
        assertTrue(names.get(1).contains("15:30"), "标题必须带上:" + names.get(1));
        assertTrue(names.get(0).startsWith("9月22日"), "日期不能丢:" + names.get(0));
        assertTrue(names.get(1).contains("标准场次票"), "票种名还要在,场上认的是它:" + names.get(1));
    }

    @Test
    void sessionsWithoutAnAssemblyTimeFallBackToSequenceInsteadOfAFabricatedMidnight() {
        List<String> names = rosterTicketNames(
                session(501L, assemblyTime(9, 22, 0, 0)),
                session(502L, assemblyTime(9, 22, 0, 0)));

        assertNotEquals(names.get(0), names.get(1),
                "存量场次没回填过时刻,同日两场仍必须可辨 —— 只剩场次序号这一条路");
        assertTrue(names.get(0).contains("第1场") && names.get(1).contains("第2场"),
                "序号按场次顺序给:" + names);
        for (String name : names) {
            assertFalse(name.contains("00:00"), "00:00 是「没填」不是「半夜集合」,不许显示:" + name);
        }
    }

    @Test
    void aSingleSessionKeepsAQuietTitleAndStillCarriesItsTime() {
        List<String> dated = rosterTicketNames(session(501L, assemblyTime(9, 22, 19, 45)));
        assertEquals(1, dated.size());
        assertEquals("9月22日 19:45 · 标准场次票", dated.get(0),
                "只有一场时不必加场次序号,但时刻仍要显出来(核销看的就是这个点)");

        List<String> dateless = rosterTicketNames(session(501L, assemblyTime(9, 22, 0, 0)));
        assertEquals("9月22日 · 标准场次票", dateless.get(0),
                "没填时刻就只给日期,别把 00:00 或「第1场」塞给唯一一场");
    }

    @Test
    void aSessionWithoutAnyStartDateStillShowsUpUnlabeled() {
        List<String> names = rosterTicketNames(
                newSession(503L), session(501L, assemblyTime(9, 22, 9, 0)));

        assertEquals(2, names.size(), "缺开始时间的场次不得被整场丢掉,否则它的票在名册里凭空消失");
        assertEquals("标准场次票", names.get(0), "无标签时保持原票名,不拼一个假日期");
        assertTrue(names.get(1).contains("09:00"));
    }

    // ------------------------------------------------------------------ 夹具

    private List<String> rosterTicketNames(CmsActivity... sessions) {
        doReturn(OWNER).when(controller).getAppUserId();
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(OWNER);
        when(clubService.selectClubById(CLUB)).thenReturn(club);
        when(memberService.canGovernClub(club, OWNER)).thenReturn(true);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setClubId(CLUB);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
        doReturn(Arrays.asList(sessions)).when(activityService).selectCmsActivityList(any());
        // doAnswer 而不是 when().thenAnswer():本用例会连着两次取名册,后者若走 when()
        // 会真的把上一次装上的 Answer 再执行一遍(参数是 null),自己把自己打挂。
        doAnswer(invocation -> {
            OmsTicket probe = invocation.getArgument(0);
            // 只查主题票(owner_type=1)时给空:俱乐部开一场后票是复制到场次上的
            if (!Long.valueOf(2L).equals(probe.getOwnerType())) {
                return Collections.emptyList();
            }
            return Collections.singletonList(sessionTicket(probe.getOwnerId()));
        }).when(ticketService).selectOmsTicketList(any());
        when(registrationService.selectRegistrationSummaries(any())).thenReturn(Collections.emptyList());

        Map<String, Object> body = new HashMap<>();
        body.put("clubId", CLUB);
        body.put("topicId", TOPIC);
        AjaxResult result = controller.topicRegistrations(body);

        assertEquals(200, result.get("code"), "名册必须真的读到,否则标题断言是空的:" + result.get("msg"));
        ClubRegistrationRosterVO roster = (ClubRegistrationRosterVO) result.get(AjaxResult.DATA_TAG);
        List<String> names = new ArrayList<>();
        for (ClubRegistrationRosterVO.Ticket ticket : roster.getOmsTicketList()) {
            names.add(ticket.getName());
        }
        return names;
    }

    private OmsTicket sessionTicket(Long ownerId) {
        OmsTicket ticket = new OmsTicket();
        ticket.setId(ownerId == null ? 1L : 600L + ownerId);
        ticket.setName("标准场次票");
        ticket.setMode(1);
        return ticket;
    }

    private CmsActivity newSession(Long id) {
        CmsActivity session = new CmsActivity();
        session.setId(id);
        session.setTopicId(TOPIC);
        session.setClubId(CLUB);
        return session;
    }

    private CmsActivity session(Long id, Date start) {
        CmsActivity session = newSession(id);
        session.setStartDate(start);
        return session;
    }

    private Date assemblyTime(int month, int day, int hour, int minute) {
        Calendar cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        cal.set(2026, month - 1, day, hour, minute, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }
}
