package com.chengyinhub.business.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 整局完成口径的 SQL 合同。
 *
 * <p>锁定的缺陷：{@code game_runtime_session} 有 {@code node_id}/{@code template_id}，
 * 它是**节点小游戏**的运行会话，一个玩家在一个节点上通关就会写 status=1。
 * 把它并进 {@code OFFICIAL_COMPLETION} 会把「玩完一个节点」记成「整局完成」。
 *
 * <p>本测试从 XML 文本层面证明该来源已被移除，并证明替换用的是真实整局终局事实。
 */
class PostGameOutcomeMapperContractTest {

    private static final Path SYSTEM = Paths.get("../chengyinhub-system");
    private static final Path MAPPER_XML = SYSTEM.resolve(
            "src/main/resources/mapper/business/PostGameOutcomeMapper.xml");
    private static final String RESOURCE = "mapper/business/PostGameOutcomeMapper.xml";

    @Test
    void mapperIsSelectOnly() throws IOException {
        String xml = stripXmlComments(read(MAPPER_XML).toLowerCase(Locale.ROOT));
        assertTrue(xml.contains("<select"));
        assertFalse(Pattern.compile("<(insert|update|delete)\\b").matcher(xml).find(),
                "完局报表 mapper 只能有 select");
        assertFalse(Pattern.compile("\\b(insert|update|delete|call|truncate|drop)\\b").matcher(xml).find(),
                "报表 SQL 不得出现写语句或存储过程调用");
    }

    /**
     * 回归：节点小游戏完成不得进入整局完成口径。
     *
     * <p>{@code game_runtime_session} 的 status=1 只代表「本节点的 minigame 通关」，
     * 与「整条路线走到终点」不是同一件事 —— 路线仍在进行时它也会是 1。
     */
    @Test
    void nodeMinigameSessionIsNotAnOfficialCompletionSource() throws IOException {
        String xml = read(MAPPER_XML).toLowerCase(Locale.ROOT);
        String completion = extractSelect(xml, "selectcompletionfacts");
        assertTrue(completion.contains("player_badge"), "整局完成必须包含通关发奖事实");
        assertFalse(completion.contains("game_runtime_session"),
                "节点小游戏会话不是整局完成事实；一个节点通关就会写 status=1");
        assertFalse(completion.contains("game_session"),
                "活动级总局只有 finished_by，没有逐会员参与事实");
    }

    /** 回归：替换来源必须是真实的整局终局事实（路线走到终点），且按会员归属。 */
    @Test
    void routeFinalStateIsTheCompletionSource() throws IOException {
        String completion = extractSelect(read(MAPPER_XML).toLowerCase(Locale.ROOT), "selectcompletionfacts");
        assertTrue(completion.contains("topic_route_session"), "必须读路线会话终局");
        assertTrue(Pattern.compile("status\\s*=\\s*1").matcher(completion).find(),
                "路线会话必须限定已到终点 status=1");
        assertTrue(completion.contains("completed_at is not null"),
                "没有完成时刻的行不能进窗口统计");
        assertTrue(completion.contains("t.member_id"), "必须按会话所有者会员归属，而不是看 finished_by");
    }

    /** 漫游结算也要按会员归属并带完成时刻。 */
    @Test
    void roamSettlementIsMemberOwned() throws IOException {
        String completion = extractSelect(read(MAPPER_XML).toLowerCase(Locale.ROOT), "selectcompletionfacts");
        assertTrue(completion.contains("roam_session"));
        assertTrue(completion.contains("s.member_id"));
        assertTrue(completion.contains("s.finished_at"));
    }

    /**
     * 水位必须覆盖被修正后的来源：路线终局有水位，节点小游戏没有；
     * 退款水位必须限定到报表真正读的 registration 退款，否则等于用一个宽口径冒充「排除已刷新」。
     */
    @Test
    void watermarksCoverCorrectedSourceAndScopeRefunds() throws IOException {
        String watermarks = extractSelect(read(MAPPER_XML).toLowerCase(Locale.ROOT), "selectsourcewatermarks");
        assertTrue(watermarks.contains("topic_route_session.completed_at"), "路线终局必须有水位");
        assertTrue(watermarks.contains("topic_route_session.update_time"), "路线会话更新也要有水位");
        assertFalse(watermarks.contains("game_runtime_session"), "节点小游戏不再是本报表来源，不应留水位");
        assertTrue(watermarks.contains("refund_application f"), "退款水位必须按来源范围收敛");
        assertTrue(watermarks.contains("f.source_type = 'registration'"), "退款水位必须限定 registration 来源");
        assertTrue(watermarks.contains("f.del_flag = 0"), "退款水位必须与报表实际过滤一致");
    }

    /** 分列口径：客户端观测只能出现在它自己的语句里，不能混进服务端完成。 */
    @Test
    void clientObservedEventsStayOutOfServerCompletion() throws IOException {
        String xml = read(MAPPER_XML).toLowerCase(Locale.ROOT);
        String completion = extractSelect(xml, "selectcompletionfacts");
        for (String clientEvent : new String[] {"mission_complete", "play_arrive_success",
                "roam_finish", "finish_route_recommendation_shown"}) {
            assertFalse(completion.contains(clientEvent),
                    "客户端自报事件不得进入服务端完成口径：" + clientEvent);
        }
        String observed = extractSelect(xml, "selectclientobservedoutcomefacts");
        assertTrue(observed.contains("mission_complete"), "客户端观测应在其独立的派生语句里");
    }

    @Test
    void mapperXmlLoadsAndRegistersEveryStatement() throws IOException {
        Configuration configuration = new Configuration();
        try (InputStream in = Files.newInputStream(MAPPER_XML)) {
            new XMLMapperBuilder(in, configuration, RESOURCE, configuration.getSqlFragments()).parse();
        }
        Set<String> namespaces = new HashSet<>(Arrays.asList(
                "selectCohortMemberIds", "selectPaidRegistrationHistory", "selectCompletionFacts",
                "selectRewardFacts", "selectPrivatePhotoFacts", "selectPublicShareFacts",
                "selectRecommendationClickFacts", "selectClientObservedOutcomeFacts",
                "selectSourceWatermarks"));
        for (String name : namespaces) {
            assertTrue(configuration.hasStatement(
                            "com.chengyinhub.business.mapper.PostGameOutcomeMapper." + name),
                    "缺少语句契约：" + name);
        }
    }

    /** xml 已整体 toLowerCase，因此语句 id 也用全小写定位。 */
    private static String extractSelect(String loweredXml, String normalizedId) {
        int index = loweredXml.indexOf("<select id=\"" + normalizedId + "\"");
        assertTrue(index >= 0, "找不到 select 语句：" + normalizedId);
        int end = loweredXml.indexOf("</select>", index);
        assertTrue(end > index, "语句未闭合：" + normalizedId);
        return loweredXml.substring(index, end);
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String stripXmlComments(String xml) {
        return Pattern.compile("(?s)<!--.*?-->").matcher(xml).replaceAll("");
    }

    @Test
    void completionSourceListIsExactlyTheThreeWholeGameFacts() throws IOException {
        String completion = extractSelect(read(MAPPER_XML).toLowerCase(Locale.ROOT), "selectcompletionfacts");
        Matcher matcher = Pattern.compile("'([a-z_]+)' as source_kind").matcher(completion);
        Set<String> kinds = new HashSet<>();
        while (matcher.find()) kinds.add(matcher.group(1).toUpperCase(Locale.ROOT));
        assertEquals(new HashSet<>(Arrays.asList(
                "PLAYER_BADGE_TOPIC_FINISH",
                "ROAM_SESSION_FINISHED",
                "TOPIC_ROUTE_SESSION_COMPLETED")), kinds,
                "整局完成只允许这三类整局终局事实");
    }
}
