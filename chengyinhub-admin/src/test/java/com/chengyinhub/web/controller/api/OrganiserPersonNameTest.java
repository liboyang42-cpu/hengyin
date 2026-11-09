package com.chengyinhub.web.controller.api;

import com.alibaba.druid.wall.WallCheckResult;
import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.spi.MySqlWallProvider;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.mapper.ClubCrmMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.IClubCrmAppService;
import com.chengyinhub.business.service.IClubLeaderService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.impl.ClubCrmAppServiceImpl;
import com.chengyinhub.business.util.OrganiserPersonName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第十轮 f2 · j2 F-08:主理人要选人的地方(成员名单、角色、定向广播、排行榜、转让主理人及其不可撤回的确认)
 * 每个人都是「城瘾用户」。名字来源 = 本俱乐部 / 本场报名时填写的姓名(C-05 已在报名参与者里这样显示),
 * 主理人本人 = 昵称(业主 11-05);都没有用昵称,默认昵称带加入时刻(第十二轮,不给编号)。只对能治理本俱乐部的人生效。
 */
class OrganiserPersonNameTest {

    private static final long CLUB = 990004L;
    private static final long LEAD = 990005L;
    private static final long PLAYER = 990006L;

    @Test
    void pickPrefersSignupNameThenRealNicknameThenMemberNumber() {
        assertEquals("林一帆", OrganiserPersonName.pick(" 林一帆 ", "城瘾用户", PLAYER));
        assertEquals("小舟", OrganiserPersonName.pick(null, "小舟", PLAYER));
        // 第十二轮 k3(p3 N4):不再给「成员#id」;默认昵称带上本俱乐部里的时刻,没有时刻只给「城瘾用户」。
        assertEquals("城瘾用户", OrganiserPersonName.pick("", "城瘾用户", PLAYER));
        assertEquals("城瘾用户", OrganiserPersonName.pick(null, null, PLAYER));
        java.util.Date joined = java.util.Date.from(java.time.ZonedDateTime.of(2026, 11, 5, 10, 21, 0, 0,
                java.time.ZoneId.of("Asia/Shanghai")).toInstant());
        assertEquals("城瘾用户 · 11月5日 10:21 加入", OrganiserPersonName.pick(null, "城瘾用户", PLAYER, joined, "加入"));
        assertEquals("小舟", OrganiserPersonName.pick(null, "小舟", PLAYER, joined, "加入"));
    }

    /** 业主 2026-11-05:主理人在所有地方都用昵称。 */
    @Test
    void ownerNameIsTheNickname() {
        assertEquals("阿舟", OrganiserPersonName.ownerName(" 阿舟 "));
        assertEquals("城瘾用户", OrganiserPersonName.ownerName(null));
    }

    private static ClubMember member(long id, String nickname, boolean owner) {
        ClubMember cm = new ClubMember();
        cm.setMemberId(id);
        cm.setNickname(nickname);
        cm.setIsOwner(owner);
        return cm;
    }

    private static ApiClubController controller(boolean governs, Map<Long, String> names) {
        ApiClubController controller = new ApiClubController();
        IClubMemberService members = mock(IClubMemberService.class);
        when(members.canGovernClub(any(Club.class), eq(LEAD))).thenReturn(governs);
        IClubCrmAppService crm = mock(IClubCrmAppService.class);
        when(crm.signupNamesForOrganiser(eq(LEAD), eq(CLUB), anyCollection())).thenReturn(names);
        IClubLeaderService leaders = mock(IClubLeaderService.class);
        ClubLeader leader = new ClubLeader();
        leader.setLeaderName("林小舟");
        when(leaders.selectClubLeaderByMemberId(LEAD)).thenReturn(leader);
        ReflectionTestUtils.setField(controller, "clubMemberService", members);
        ReflectionTestUtils.setField(controller, "clubCrmAppService", crm);
        ReflectionTestUtils.setField(controller, "clubLeaderService", leaders);
        return controller;
    }

    private static Club club() {
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(LEAD);
        return club;
    }

    @Test
    void organiserMemberListShowsSignupNamesAndTheLeadsNickname() {
        List<ClubMember> list = new ArrayList<>(Arrays.asList(member(LEAD, "阿舟", true),
                member(PLAYER, "城瘾用户", false), member(990007L, "城瘾用户", false), member(990008L, "阿满", false)));
        list.get(2).setJoinTime(java.util.Date.from(java.time.ZonedDateTime.of(2026, 11, 5, 10, 21, 0, 0,
                java.time.ZoneId.of("Asia/Shanghai")).toInstant()));
        list.get(2).setStatus(1);
        Map<Long, String> names = new HashMap<>();
        names.put(PLAYER, "林一帆");
        controller(true, names).applyOrganiserNames(club(), LEAD, list);
        assertEquals("阿舟", list.get(0).getNickname(), "业主 11-05:主理人用昵称,不用主理人档案里的真实姓名");
        assertEquals("林一帆", list.get(1).getNickname(), "转让确认「转让主理人给 …」读的就是这个名字");
        assertEquals("城瘾用户 · 11月5日 10:21 加入", list.get(2).getNickname(), "没报过名的成员分得清,且不给编号");
        assertEquals("阿满", list.get(3).getNickname());
    }

    /** 第十一轮 h4 L4:贡献榜与成员名单同口径 —— 治理者看报名姓名,普通成员看各人昵称。 */
    @Test
    void contributionBoardFollowsTheSameNameRuleAsTheMemberList() {
        Map<Long, String> names = new HashMap<>();
        names.put(PLAYER, "林一帆");
        List<Map<String, Object>> board = new ArrayList<>();
        for (Object[] r : new Object[][]{{LEAD, "阿舟"}, {PLAYER, "城瘾用户"}, {990007L, "城瘾用户"}}) {
            Map<String, Object> row = new HashMap<>();
            row.put("memberId", r[0]);
            row.put("nickname", r[1]);
            row.put("score", 1);
            board.add(row);
        }
        controller(true, names).applyOrganiserNamesToBoard(club(), LEAD, board);
        assertEquals("阿舟", board.get(0).get("nickname"));
        assertEquals("林一帆", board.get(1).get("nickname"));
        assertEquals("城瘾用户", board.get(2).get("nickname"));

        List<Map<String, Object>> plain = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("memberId", PLAYER);
        row.put("nickname", "城瘾用户");
        plain.add(row);
        controller(false, names).applyOrganiserNamesToBoard(club(), LEAD, plain);
        assertEquals("城瘾用户", plain.get(0).get("nickname"), "报名姓名只给主理人一侧");
    }

    @Test
    void plainMemberSeesTheListUnchanged() {
        List<ClubMember> list = new ArrayList<>(Collections.singletonList(member(PLAYER, "城瘾用户", false)));
        ApiClubController controller = controller(false, Collections.singletonMap(PLAYER, "林一帆"));
        controller.applyOrganiserNames(club(), LEAD, list);
        assertEquals("城瘾用户", list.get(0).getNickname(), "报名姓名只给主理人一侧");
    }

    @Test
    void signupNamesAreOnlyReadForClubGovernors() {
        ClubCrmAppServiceImpl service = new ClubCrmAppServiceImpl();
        ClubCrmMapper mapper = mock(ClubCrmMapper.class);
        IClubService clubs = mock(IClubService.class);
        IClubMemberService members = mock(IClubMemberService.class);
        when(clubs.selectClubById(CLUB)).thenReturn(club());
        when(members.canGovernClub(any(Club.class), eq(LEAD))).thenReturn(true);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(PLAYER, "林一帆"));       // 最近一张
        rows.add(row(PLAYER, "林旧名"));
        rows.add(row(990007L, ""));
        when(mapper.selectSignupNames(eq(CLUB), anyCollection())).thenReturn(rows);
        ReflectionTestUtils.setField(service, "clubCrmMapper", mapper);
        ReflectionTestUtils.setField(service, "clubService", clubs);
        ReflectionTestUtils.setField(service, "clubMemberService", members);
        ReflectionTestUtils.setField(service, "piiCryptoService", new PiiCryptoService("", ""));

        Map<Long, String> names = service.signupNamesForOrganiser(LEAD, CLUB, Arrays.asList(PLAYER, 990007L));
        assertEquals(Collections.singletonMap(PLAYER, "林一帆"), names);

        assertTrue(service.signupNamesForOrganiser(PLAYER, CLUB, Arrays.asList(PLAYER)).isEmpty());
        verify(mapper, org.mockito.Mockito.times(1)).selectSignupNames(anyLong(), anyCollection());
    }

    private static Map<String, Object> row(long memberId, String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("memberId", memberId);
        row.put("realNameStored", name);
        return row;
    }

    @Test
    void newNameQueriesPassTheDruidWall() throws Exception {
        WallConfig config = new WallConfig(MySqlWallProvider.DEFAULT_CONFIG_DIR);
        config.setMultiStatementAllow(false);
        MySqlWallProvider wall = new MySqlWallProvider(config);
        String crm = select("ClubCrmMapper.xml", "selectSignupNames")
                .replaceAll("<foreach[\\s\\S]*?</foreach>", "(?, ?)");
        String director = select("GameSessionRuntimeMapper.xml", "selectClubMemberProjection")
                .replaceAll("<include refid=\"paidActivityMemberExists\"/>",
                        "exists (select 1 from cms_registration reg where reg.member_id=m.member_id and reg.owner_type=2"
                                + " and reg.owner_id=t.owner_id and reg.payment_status=2 and reg.registration_status=2)");
        for (String sql : new String[]{crm, director}) {
            WallCheckResult result = wall.check(sql);
            assertTrue(result.getViolations().isEmpty(), result.getViolations() + "\n" + sql);
        }
        assertTrue(crm.contains("a.club_id = ?") && crm.contains("t.club_id = ?"), "只取本俱乐部的报名");
        assertTrue(director.contains("name_reg.owner_id=t.owner_id"), "只取本场的报名");
    }

    private static String select(String file, String id) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get("..", "chengyinhub-system", "src", "main", "resources",
                "mapper", "business", file)), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<select id=\"" + id + "\"[^>]*>([\\s\\S]*?)</select>").matcher(text);
        assertTrue(m.find(), id);
        return m.group(1).replaceAll("<!--[\\s\\S]*?-->", "").replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("#\\{[^}]+}", "?");
    }
}
