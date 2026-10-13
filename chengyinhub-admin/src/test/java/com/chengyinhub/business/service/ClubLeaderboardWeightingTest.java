package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.impl.ClubMemberServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本团贡献榜 Service 层:综合分加权 + 三种排序 + 配速。
 *
 * 【分工】SQL 口径(select 哪列、join 哪表、跨团会不会泄漏)由 ClubLeaderboardContributionMyBatisIT
 * 打真库验 —— mock 掉 mapper 的用例对 SQL 内容一无所知,证不了那半。本用例只管
 * 「拿到原始度量之后算得对不对、排得对不对」,这半恰恰是真库 IT 不方便穷举的。
 * 两个用例合起来才覆盖完 —— 任一单独存在都是半盲。
 *
 * 负控实测(2026-07-16):把 W_MILEAGE 由 1 改 0 ⇒ compositeScore_weights 红;
 * 把配速排序的 nullsLast 改 nullsFirst ⇒ paceSort_ascendingWithNullsLast 红;撤销后均绿。
 */
@ExtendWith(MockitoExtension.class)
class ClubLeaderboardWeightingTest {

    private static final long CLUB = 1L;
    private static final long OWNER = 100L, ACE = 101L, MID = 102L, ZERO = 103L;

    @Mock
    private ClubMemberMapper clubMemberMapper;

    @InjectMocks
    private ClubMemberServiceImpl service;

    /** 原始度量行,形状与 selectClubContribution 的返回一致(MyBatis 那边 count 是 Long、sum 是 BigDecimal)。 */
    private static Map<String, Object> row(long memberId, long clearCount, String mileage,
                                           long durationMin, long hostedCount) {
        Map<String, Object> r = new HashMap<>();
        r.put("memberId", memberId);
        r.put("nickname", "m" + memberId);
        r.put("avatar", "a" + memberId + ".png");
        r.put("clearCount", clearCount);
        r.put("mileage", new BigDecimal(mileage));
        r.put("durationMin", durationMin);
        r.put("hostedCount", hostedCount);
        return r;
    }

    /** 与 MyBatis IT 同一套人物:ACE 40分/6.0配速、MID 20分/12.0、OWNER 5分/无配速、ZERO 零贡献。
     *  ZERO 留在入参里是**故意的**:SQL 那边他就是会返回一行(LEFT JOIN + ifnull),服务层得自己剔除。 */
    private void givenFourMembers() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(ACE, 2, "15.00", 90, 1));
        rows.add(row(MID, 1, "10.00", 120, 0));
        rows.add(row(OWNER, 0, "0.00", 0, 1));
        rows.add(row(ZERO, 0, "0.00", 0, 0));
        // 精确参数,不用 any() —— any() 会让「Service 把 clubId 传错」这类 bug 照样绿
        Mockito.when(clubMemberMapper.selectClubContribution(Mockito.eq(CLUB), Mockito.any())).thenReturn(rows);
    }

    private static List<Long> idsOf(List<Map<String, Object>> rows) {
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> r : rows) ids.add(((Number) r.get("memberId")).longValue());
        return ids;
    }

    private static Map<String, Object> pick(List<Map<String, Object>> rows, long memberId) {
        for (Map<String, Object> r : rows) {
            if (((Number) r.get("memberId")).longValue() == memberId) return r;
        }
        return null;
    }

    @Test
    void compositeScore_weights() {
        givenFourMembers();
        List<Map<String, Object>> out = service.selectClubLeaderboard(CLUB, "composite");

        // 综合分 = 10·场次 + 1·里程 + 5·发起团数
        assertEquals(0, new BigDecimal("40.0").compareTo((BigDecimal) pick(out, ACE).get("score")),
                "ACE: 10*2 + 15 + 5*1 = 40");
        assertEquals(0, new BigDecimal("20.0").compareTo((BigDecimal) pick(out, MID).get("score")),
                "MID: 10*1 + 10 + 5*0 = 20");
        assertEquals(0, new BigDecimal("5.0").compareTo((BigDecimal) pick(out, OWNER).get("score")),
                "团长: 0 + 0 + 5*1 = 5(只有发起分)");
        // CU-C-72:零贡献者不入榜 —— 不是「0 分排最后」,是压根不出现。
        assertNull(pick(out, ZERO), "零贡献成员不该出现在榜上");
    }

    /**
     * CU-C-72(9-25 裁决:按单个榜剔除):零成绩者不入「该榜」—— 每个榜各判各的成绩。
     *
     * <p>现场(用户 9-25 走查):#1169 只剔「三项全 0」,于是只发起过团的人(mileage=0)
     * 仍挂在里程榜显示「0.0 km」占一个名次、零用时的挂在完成用时榜显示「—」——
     * 界面没成绩却产生名次。裁决改成里程榜只留 mileage&gt;0、完成用时榜只留用时&gt;0。
     *
     * <p>人物:ACE/MID 四项全有;OWNER 只发起过团(clearCount=0, mileage=0, duration=0, hosted=1)
     * ⇒ 只该在综合榜;ZERO 三项全 0 ⇒ 任何榜都不在。
     */
    @Test
    void eachBoardKeepsOnlyMembersWithThatBoardsMetric() {
        givenFourMembers();
        // ZERO(三项全 0):任何榜都不该出现。
        // OWNER(仅发起过团):里程榜(0.0 km)、完成用时榜(0 min)、配速榜(null)都不该出现,只在综合榜。
        assertIds("composite", java.util.Arrays.asList(ACE, MID, OWNER));
        assertIds("mileage",   java.util.Arrays.asList(ACE, MID));
        assertIds("pace",      java.util.Arrays.asList(ACE, MID));
        assertIds("duration",  java.util.Arrays.asList(ACE, MID));
    }

    /** 名次连续的前置:每榜入榜者就是「该榜成绩&gt;0」的那批,榜里没有 0 值行(前端按下标生成名次)。 */
    private void assertIds(String sortBy, List<Long> expected) {
        assertEquals(expected, idsOf(service.selectClubLeaderboard(CLUB, sortBy)),
                "sortBy=" + sortBy + " 的入榜集合不符");
    }

    /** 全团零贡献 ⇒ 每个榜都为空,交给前端空态按榜各说一句。 */
    @Test
    void allZeroContributionClub_returnsEmptyLeaderboard() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(ZERO, 0, "0.00", 0, 0));
        rows.add(row(OWNER, 0, "0.00", 0, 0));
        Mockito.when(clubMemberMapper.selectClubContribution(Mockito.eq(CLUB), Mockito.any())).thenReturn(rows);

        for (String sortBy : java.util.Arrays.asList("composite", "mileage", "pace", "duration", null)) {
            assertTrue(service.selectClubLeaderboard(CLUB, sortBy).isEmpty(),
                    "全员该榜零成绩时榜单必须为空(sortBy=" + sortBy + ")—— 名次与空态文案不能自相矛盾");
        }
    }

    @Test
    void compositeSort_descending() {
        givenFourMembers();
        assertEquals(java.util.Arrays.asList(ACE, MID, OWNER),
                idsOf(service.selectClubLeaderboard(CLUB, "composite")),
                "综合榜按分降序;综合分>0 者(含仅发起团)都在,零贡献的 ZERO 不在(CU-C-72)");
    }

    @Test
    void mileageSort_excludesZeroMileage() {
        givenFourMembers();
        assertEquals(java.util.Arrays.asList(ACE, MID),
                idsOf(service.selectClubLeaderboard(CLUB, "mileage")),
                "里程榜只留 mileage>0:15 > 10;只靠发起团入综合榜的 OWNER(0.0 km)不该占里程榜名次(CU-C-72)");
    }

    @Test
    void paceSort_excludesNoPaceMembers() {
        givenFourMembers();
        List<Map<String, Object>> out = service.selectClubLeaderboard(CLUB, "pace");

        assertEquals(0, new BigDecimal("6.0").compareTo((BigDecimal) pick(out, ACE).get("pace")),
                "ACE 配速 = 90min / 15km = 6.0 min/km");
        assertEquals(0, new BigDecimal("12.0").compareTo((BigDecimal) pick(out, MID).get("pace")),
                "MID 配速 = 120min / 10km = 12.0 min/km");
        assertEquals(java.util.Arrays.asList(ACE, MID), idsOf(out),
                "配速榜升序(快的靠前);无配速(零里程/零用时)者不入该榜(CU-C-72)");
    }

    @Test
    void completionTimeSort_excludesZeroDuration() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(ACE, 0, "1.00", 30, 0));
        rows.add(row(MID, 3, "0.00", 90, 0));
        rows.add(row(OWNER, 5, "0.00", 0, 0));
        Mockito.when(clubMemberMapper.selectClubContribution(Mockito.eq(CLUB), Mockito.any())).thenReturn(rows);

        List<Map<String, Object>> out = service.selectClubLeaderboard(CLUB, "duration");

        assertEquals(java.util.Arrays.asList(ACE, MID), idsOf(out),
                "完成用时榜按真实打卡用时升序;没有有效用时(0 min)的 OWNER 不入该榜,不以「—」占名次(CU-C-72)");
    }

    /**
     * 0 用时 ≠ 无限快。单节点团/只打卡一次 ⇒ MAX-MIN=0(真库实测:
     * ClubLeaderboardContributionMyBatisIT.singleCheckin_zeroDuration)。
     * CU-C-72 后这类人直接不进配速榜,而不是「以 0.0 min/km 屠榜」或「以 null 沉底占名次」。
     */
    @Test
    void zeroDuration_or_zeroMileage_isExcludedFromPaceBoard() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(ACE, 1, "10.00", 0, 0));    // 有里程、零用时 ⇒ pace null
        rows.add(row(OWNER, 0, "0.00", 60, 1));  // 零里程 ⇒ pace null
        rows.add(row(MID, 1, "10.00", 60, 0));   // 正常:60/10 = 6.0
        Mockito.when(clubMemberMapper.selectClubContribution(Mockito.eq(CLUB), Mockito.any())).thenReturn(rows);

        List<Map<String, Object>> out = service.selectClubLeaderboard(CLUB, "pace");
        assertEquals(java.util.Arrays.asList(MID), idsOf(out),
                "配速榜只留真有配速者;零用时/零里程者 pace 为 null,不入榜(CU-C-72)");
    }

    @Test
    void unknownSortBy_fallsBackToComposite() {
        givenFourMembers();
        // 前端传错、老版本小程序没传、或有人试探注入,都必须落到 composite 而不是报错/裸奔
        assertEquals(idsOf(service.selectClubLeaderboard(CLUB, "composite")),
                idsOf(service.selectClubLeaderboard(CLUB, "'; drop table club_member; --")),
                "非法 sortBy 回落 composite");
    }

    @Test
    void nullSortBy_fallsBackToComposite() {
        givenFourMembers();
        assertEquals(java.util.Arrays.asList(ACE, MID, OWNER),
                idsOf(service.selectClubLeaderboard(CLUB, null)),
                "sortBy 缺省回落 composite");
    }

    @Test
    void emptyClub_returnsEmptyNotNull() {
        Mockito.when(clubMemberMapper.selectClubContribution(Mockito.eq(CLUB), Mockito.any())).thenReturn(new ArrayList<>());
        assertTrue(service.selectClubLeaderboard(CLUB, "composite").isEmpty());
    }

    @Test
    void limitsTo50() {
        List<Map<String, Object>> rows = new ArrayList<>();
        // 60 人,场次递增 ⇒ 综合分递增。截断必须发生在排序【之后】:
        // 若先截断再排,留下的会是 memberId 1..50(低分那批),榜首就不是真第一名。
        for (int i = 1; i <= 60; i++) {
            rows.add(row(i, i, "0.00", 0, 0));
        }
        Mockito.when(clubMemberMapper.selectClubContribution(Mockito.eq(CLUB), Mockito.any())).thenReturn(rows);

        List<Map<String, Object>> out = service.selectClubLeaderboard(CLUB, "composite");
        assertEquals(50, out.size(), "最多 50 行");
        assertEquals(60L, ((Number) out.get(0).get("memberId")).longValue(),
                "榜首必须是分最高的那位(60场),而不是 memberId 最小的 —— 证明先排序后截断");
        assertEquals(11L, ((Number) out.get(49).get("memberId")).longValue(),
                "第 50 名 = 第 60..11 位,即被截掉的是最低分的 10 位");
    }

    /**
     * ★UI 写死了「本团贡献榜 · 本周」「榜单每周一 0 点重置」,而在 2026-09-11 之前
     * SQL 里一个时间条件都没有 —— 榜是终身累计的,那两句文案是假的。
     * 这条钉的是:服务层必须把窗口传下去,且就是本周一零点。
     */
    @org.junit.jupiter.api.Test
    void leaderboardQueriesThisWeekNotAllTime() {
        ClubMemberServiceImpl service = new ClubMemberServiceImpl();
        ClubMemberMapper clubMemberMapper = Mockito.mock(ClubMemberMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "clubMemberMapper", clubMemberMapper);
        Mockito.when(clubMemberMapper.selectClubContribution(Mockito.eq(CLUB), Mockito.any()))
                .thenReturn(new ArrayList<>());

        service.selectClubLeaderboard(CLUB, null);

        org.mockito.ArgumentCaptor<java.util.Date> since =
                org.mockito.ArgumentCaptor.forClass(java.util.Date.class);
        Mockito.verify(clubMemberMapper).selectClubContribution(Mockito.eq(CLUB), since.capture());
        org.junit.jupiter.api.Assertions.assertNotNull(since.getValue(),
                "★传 null 就是全时段 —— 那正是「本周」这句话变成假话的原因");
        java.time.ZoneId shanghai = java.time.ZoneId.of("Asia/Shanghai");
        java.time.ZonedDateTime actual = since.getValue().toInstant().atZone(shanghai);
        org.junit.jupiter.api.Assertions.assertEquals(java.time.DayOfWeek.MONDAY, actual.getDayOfWeek(),
                "窗口起点必须是周一");
        org.junit.jupiter.api.Assertions.assertEquals(0, actual.getHour(), "必须是零点");
        org.junit.jupiter.api.Assertions.assertEquals(0, actual.getMinute());
        org.junit.jupiter.api.Assertions.assertTrue(
                !actual.toLocalDate().isAfter(java.time.LocalDate.now(shanghai)),
                "窗口起点不能落在未来");
        // ★只断言「是某个周一的零点」还不够:写成上上周一、甚至 1999 年的某个周一,上面三条照样绿。
        long daysAgo = java.time.temporal.ChronoUnit.DAYS.between(
                actual.toLocalDate(), java.time.LocalDate.now(shanghai));
        org.junit.jupiter.api.Assertions.assertTrue(daysAgo >= 0 && daysAgo <= 6,
                "窗口起点必须是**本**周一 —— 距今 " + daysAgo + " 天,超出 0~6");
    }
}
