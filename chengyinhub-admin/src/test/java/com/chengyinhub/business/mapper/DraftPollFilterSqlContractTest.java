package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DraftPollFilterSqlContractTest {
    @Test
    void migrationUsesNonNullStableKeysAndDatabaseEnforcedOnePersonOneVote() throws Exception {
        String sql = normalized(read("sql/migration_content_draft_im_poll_20260828.sql"));

        assertNotNullColumn(sql, "content_draft", "client_draft_key", "草稿幂等键不允许 NULL，避免唯一键假闸");
        assertUniqueKey(sql, "content_draft", "uk_content_draft_business_key",
                Arrays.asList("owner_member_id", "business_type", "client_draft_key"),
                "草稿重放必须由稳定业务键唯一约束兜底");
        assertNotNullColumn(sql, "im_poll", "client_poll_key", "投票创建幂等键不允许 NULL");
        assertUniqueKey(sql, "im_poll_vote", "uk_im_poll_vote_member",
                Arrays.asList("poll_id", "member_id"), "一人一票必须由数据库唯一键保证");
        assertUniqueKey(sql, "im_poll_audience", "uk_im_poll_audience_member",
                Arrays.asList("poll_id", "member_id"), "退群后只读范围必须冻结为创建时成员快照");
    }

    @Test
    void everyStateTransitionIsAStatusAndVersionCas() throws Exception {
        String draft = normalized(read("../chengyinhub-system/src/main/resources/mapper/business/ContentDraftMapper.xml"));
        String poll = normalized(read("../chengyinhub-system/src/main/resources/mapper/business/ImPollMapper.xml"));

        assertStatusVersionCas(draft, "updatedraftcas", "draft",
                "草稿保存必须同时比较状态与 expectedVersion");
        assertStatusVersionCas(draft, "deletedraftcas", "draft", "草稿删除必须 CAS，不能查后再改");
        assertStatusVersionCas(draft, "publishdraftcas", "draft", "草稿发布归档必须 CAS");
        assertStatusVersionCas(poll, "closepollcas", "open", "投票结束必须 CAS");
        assertStatusVersionCas(poll, "expirepollcas", "open", "截止时间触发的结束也必须 CAS 落库");
    }

    @Test
    void activityScheduleFiltersUseActivityDatesAndTopicHasNoDefaultEndDateGate() throws Exception {
        String activity = normalized(read("../chengyinhub-system/src/main/resources/mapper/business/CmsActivityMapper.xml"));
        String topic = normalized(read("../chengyinhub-system/src/main/resources/mapper/business/CmsTopicMapper.xml"));
        String publicActivities = section(activity, "<select id=\"selectpubliccmsactivitylist\"", "</select>");
        String publicTopics = section(topic, "<select id=\"selectpubliccmstopiclist\"", "</select>");

        assertActivityScheduleUsesOwnDates(activity);
        assertNoTopicExpiryGate(publicTopics);
        assertPublicLocationContractIsConsumed(publicActivities, publicTopics);
    }

    @Test
    void pollAudienceAndVoteUseRealtimeBusinessMembershipInsteadOfImIndexAlone() throws Exception {
        String poll = normalized(read("../chengyinhub-system/src/main/resources/mapper/business/ImPollMapper.xml"));

        assertRealtimeGroupMembershipFence(section(poll,
                "<insert id=\"insertaudiencefromconversation\">", "</insert>"),
                "成员快照不能直接信任可能过期的 IM 会话索引");
        assertRealtimeGroupMembershipFence(section(poll,
                "<insert id=\"insertvoteifopen\">", "</insert>"),
                "投票写入必须在同一 SQL 内重验真实群成员，封住退出并发窗口");
        assertRealtimeGroupMembershipFence(section(poll,
                "<update id=\"closepollcas\">", "</update>"),
                "投票结束必须在同一 CAS 内重验真实群成员，封住退出并发窗口");
    }

    private String read(String relative) throws Exception {
        Path base = Paths.get(System.getProperty("user.dir"));
        return new String(Files.readAllBytes(base.resolve(relative).normalize()), StandardCharsets.UTF_8);
    }

    private String normalized(String value) { return value.toLowerCase().replaceAll("\\s+", " ").trim(); }

    private String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = from < 0 ? -1 : source.indexOf(end, from);
        return from < 0 || to < 0 ? "" : source.substring(from, to);
    }

    private void assertNotNullColumn(String sql, String table, String column, String message) {
        String body = createTableBody(sql, table);
        Matcher matcher = Pattern.compile("`" + Pattern.quote(column) + "`\\s+[^,]+", Pattern.CASE_INSENSITIVE)
                .matcher(body);
        boolean enforced = matcher.find() && matcher.group().contains("not null");
        assertTrue(enforced, message);
    }

    private void assertUniqueKey(String sql, String table, String key, List<String> expectedColumns, String message) {
        String body = createTableBody(sql, table);
        Matcher matcher = Pattern.compile("unique key `" + Pattern.quote(key) + "`\\s*\\(([^)]*)\\)")
                .matcher(body);
        List<String> actual = matcher.find()
                ? Arrays.asList(matcher.group(1).replace("`", "").replace(" ", "").split(","))
                : java.util.Collections.emptyList();
        assertTrue(expectedColumns.equals(actual), message + "; actualColumns=" + actual);
    }

    private String createTableBody(String sql, String table) {
        String start = "create table if not exists `" + table + "` (";
        return section(sql, start, ") engine=innodb");
    }

    private void assertStatusVersionCas(String xml, String updateId, String expectedStatus, String message) {
        String statement = section(xml, "<update id=\"" + updateId + "\">", "</update>");
        boolean checksStatus = Pattern.compile("(?:\\w+\\.)?status\\s*=\\s*'" + expectedStatus + "'")
                .matcher(statement).find();
        boolean checksExpectedVersion = Pattern.compile("(?:\\w+\\.)?version\\s*=\\s*#\\{expectedversion}")
                .matcher(statement).find();
        assertTrue(checksStatus && checksExpectedVersion && !hasBooleanTautology(statement), message);
    }

    private void assertActivityScheduleUsesOwnDates(String activityMapper) {
        String ongoing = section(activityMapper,
                "<if test=\"schedulefilter == 'ongoing'\">", "</if>");
        boolean usesBothActivityDates = ongoing.contains("start_date &lt;= now()")
                && ongoing.contains("end_date &gt;= now()");
        assertTrue(usesBothActivityDates, "活动 ONGOING 必须真实接入活动自身起止日期");
    }

    private void assertNoTopicExpiryGate(String publicTopics) {
        boolean hasTopicExpiryGate = Pattern.compile("\\bend_date\\s*&gt;\\s*now\\(\\)")
                .matcher(publicTopics).find();
        assertFalse(hasTopicExpiryGate, "主题是常青内容，公开筛选不得再次引入会恒空的 end_date 闸");
    }

    private void assertRealtimeGroupMembershipFence(String statement, String message) {
        boolean checksTeamTruth = Pattern.compile("join\\s+play_team_member\\s+\\w+[^<]+\\.status\\s*=\\s*1")
                .matcher(statement).find();
        boolean checksClubTruth = Pattern.compile("from\\s+club_member\\s+\\w+[^<]+\\.status\\s*=\\s*1[^<]+\\.del_flag\\s*=\\s*0")
                .matcher(statement).find();
        boolean checksConversationIndex = Pattern.compile("join\\s+im_conversation_member\\s+\\w+[^<]+\\.deleted\\s*=\\s*0")
                .matcher(statement).find();
        assertTrue(checksTeamTruth && checksClubTruth && checksConversationIndex
                && !hasBooleanTautology(statement), message);
    }

    private void assertPublicLocationContractIsConsumed(String activities, String topics) {
        boolean activityCity = Pattern.compile("address_name\\s+like\\s+concat\\('%',\\s*#\\{addressname},\\s*'%'")
                .matcher(activities).find();
        boolean topicCity = Pattern.compile("address_name\\s+like\\s+concat\\('%',\\s*#\\{citykeyword},\\s*'%'")
                .matcher(topics).find();
        boolean explicitRadius = activities.contains("#{distancemeters}")
                && !Pattern.compile("&lt;\\s*100000").matcher(activities).find();
        boolean nearestOnlySorts = activities.contains("sorttype == 1") && activities.contains("distance,");
        assertTrue(activityCity && topicCity, "城市筛选必须进入活动/主题公开主查询，不能被静默忽略");
        assertTrue(explicitRadius && nearestOnlySorts, "距离筛选和 NEAREST 排序必须解耦且消费显式半径");
    }

    private boolean hasBooleanTautology(String statement) {
        return Pattern.compile("\\bor\\s+(?:1\\s*=\\s*1|true)\\b").matcher(statement).find();
    }
}
