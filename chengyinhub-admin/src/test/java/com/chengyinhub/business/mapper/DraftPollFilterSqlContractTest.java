package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;

import java.io.ByteArrayInputStream;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        String activity = read("../chengyinhub-system/src/main/resources/mapper/business/CmsActivityMapper.xml");
        String topic = read("../chengyinhub-system/src/main/resources/mapper/business/CmsTopicMapper.xml");
        assertActivityScheduleUsesOwnDates(normalized(activity));
        assertPublicLocationBoundSql(activity, topic);
    }

    @Test
    void removingCityFilterFromSharedTopicWhereFailsTheContract() throws Exception {
        String activity = read("../chengyinhub-system/src/main/resources/mapper/business/CmsActivityMapper.xml");
        String topic = read("../chengyinhub-system/src/main/resources/mapper/business/CmsTopicMapper.xml");
        String mutated = topic.replaceAll("(?s)<if test=\"cityKeyword != null and cityKeyword != ''\">.*?</if>", "");
        assertFalse(mutated.equals(topic), "负控必须确实摘掉城市筛选");
        assertThrows(AssertionError.class, () -> assertPublicLocationBoundSql(activity, mutated));
    }

    @Test
    void replacingExplicitRadiusWithFixedRadiusFailsTheContract() throws Exception {
        String activity = read("../chengyinhub-system/src/main/resources/mapper/business/CmsActivityMapper.xml");
        String topic = read("../chengyinhub-system/src/main/resources/mapper/business/CmsTopicMapper.xml");
        assertTrue(activity.contains("#{distanceMeters}"));
        assertThrows(AssertionError.class, () -> assertPublicLocationBoundSql(
                activity.replace("#{distanceMeters}", "100000"), topic));
    }

    @Test
    void reintroducingTopicExpiryOrRemovingNearestSortFailsTheContract() throws Exception {
        String activity = read("../chengyinhub-system/src/main/resources/mapper/business/CmsActivityMapper.xml");
        String topic = read("../chengyinhub-system/src/main/resources/mapper/business/CmsTopicMapper.xml");
        String expired = topic.replace("<include refid=\"publicTopicListWhere\"/>",
                "<include refid=\"publicTopicListWhere\"/> and end_date &gt; now()");
        assertFalse(expired.equals(topic));
        assertThrows(AssertionError.class, () -> assertPublicLocationBoundSql(activity, expired));
        String unsorted = activity.replace("distance,</if>", "</if>");
        assertFalse(unsorted.equals(activity));
        assertThrows(AssertionError.class, () -> assertPublicLocationBoundSql(unsorted, topic));
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
        boolean hasTopicExpiryGate = Pattern.compile("\\bend_date\\s*(?:&gt;|>)\\s*now\\(\\)")
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

    private void assertPublicLocationBoundSql(String activityXml, String topicXml) {
        CmsActivity activity = new CmsActivity();
        activity.setAddressName("上海");
        activity.setLatitude("31.2");
        activity.setLongitude("121.5");
        activity.setDistanceMeters(500);
        activity.setSortType(1);
        BoundSql activities = boundSql("CmsActivity", activityXml, "selectPublicCmsActivityList", activity);
        CmsTopic topic = new CmsTopic();
        topic.setCityKeyword("上海");
        BoundSql topics = boundSql("CmsTopic", topicXml, "selectPublicCmsTopicList", topic);
        BoundSql count = boundSql("CmsTopic", topicXml, "selectPublicCmsTopicList_COUNT", topic);
        assertCityMapping(activities, "addressName");
        assertCityMapping(topics, "cityKeyword");
        assertCityMapping(count, "cityKeyword");
        assertNoTopicExpiryGate(normalized(topics.getSql()));
        assertNoTopicExpiryGate(normalized(count.getSql()));
        assertTrue(hasParameter(activities, "distanceMeters"), "显式半径必须进入主查询参数");
        assertFalse(Pattern.compile("<\\s*100000").matcher(activities.getSql()).find(), "不得恢复硬编码半径");
        assertTrue(normalized(activities.getSql()).contains("order by distance,"), "NEAREST必须实际按distance排序");
        activity.setDistanceMeters(null);
        BoundSql nearestOnly = boundSql("CmsActivity", activityXml, "selectPublicCmsActivityList", activity);
        assertFalse(hasParameter(nearestOnly, "distanceMeters"), "单独NEAREST不能暗加半径过滤");
        assertTrue(normalized(nearestOnly.getSql()).contains("order by distance,"));
    }

    private BoundSql boundSql(String type, String xml, String statement, Object parameter) {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("CmsActivity", CmsActivity.class);
        configuration.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), configuration,
                type + "Mapper.xml", configuration.getSqlFragments()).parse();
        return configuration.getMappedStatement("com.chengyinhub.business.mapper." + type + "Mapper." + statement)
                .getBoundSql(parameter);
    }

    private boolean hasParameter(BoundSql sql, String property) {
        return sql.getParameterMappings().stream().anyMatch(mapping -> property.equals(mapping.getProperty()));
    }

    private void assertCityMapping(BoundSql sql, String property) {
        assertTrue(hasParameter(sql, property)
                && Pattern.compile("address_name\\s+like\\s+concat\\('%',\\s*\\?,\\s*'%'")
                .matcher(normalized(sql.getSql())).find(), "城市筛选必须进入真实公开主查询及参数映射");
    }

    private boolean hasBooleanTautology(String statement) {
        return Pattern.compile("\\bor\\s+(?:1\\s*=\\s*1|true)\\b").matcher(statement).find();
    }
}
