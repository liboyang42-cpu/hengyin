package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.ApiClubSettlementController;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CU-C-40:俱乐部分润卡上的主题名必须来自 cms_topic.name,不是展示层拼的「主题 #id」。
 *
 * <p><b>为什么必须真跑 SQL</b>:缺陷在 {@code summarySelectFrom} 的取数列 —— 名字从来没被
 * 选出来过,展示层只能拿 topic_id 拼一个编号。Mockito 单测把 SQL 原样放过(它不解析 SQL),
 * 只有真引擎才知道 join 有没有把 {@code t.name} 带出来。这里用 H2(MySQL 兼容模式)真跑
 * {@code CoopSettlementMapper.xml},再喂给真控制器读回响应里的 name。
 *
 * <p>夹具刻意留了「主题行不存在」的第二条结算行:cms_topic 被删时退回可辨认的编号
 * (空串会让小程序整块拒收,本来能看的几行也跟着没了)。
 * 负控:把 join 摘掉或让 {@code topicName()} 回到恒拼编号,第一条断言即红。
 */
class ClubSettlementTopicNameH2Test {

    private static final long CLUB = 9L;
    private static final long OWNER = 555L;
    private static final long T_Named = 990028L;      // cms_topic 里有名字
    private static final long T_NoRow = 990029L;      // cms_topic 里查不到

    private JdbcTemplate jdbc;
    private ApiClubSettlementController api;

    @BeforeEach
    void setUp() throws Exception {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:club_settle_topic_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");

        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table coop_settlement ("
                + "id bigint primary key, topic_id bigint, merchant_id bigint, payee_type varchar(20),"
                + "beneficiary_role varchar(20), beneficiary_id bigint, amount decimal(12,2), status int,"
                + "payable_time datetime, settle_time datetime, payout_time datetime,"
                + "del_flag int default 0, create_time datetime)");
        jdbc.execute("create table coop_settlement_adjustment ("
                + "settlement_id bigint, amount decimal(12,2), status varchar(20))");
        jdbc.execute("create table ums_member_balance_detail ("
                + "member_id bigint, change_balance decimal(12,2), change_type int, del_flag int,"
                + "coop_settlement_payout_id bigint, create_time datetime)");
        jdbc.execute("create table cms_topic (id bigint primary key, name varchar(255), del_flag int default 0)");

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/CoopSettlementMapper.xml"));
        SqlSessionFactory sqlSessionFactory = factory.getObject();
        CoopSettlementMapper settlements = new SqlSessionTemplate(sqlSessionFactory).getMapper(CoopSettlementMapper.class);

        IClubService clubService = mock(IClubService.class);
        IClubAccessService clubAccessService = mock(IClubAccessService.class);
        ICashWithdrawService cashWithdrawService = mock(ICashWithdrawService.class);
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(OWNER);
        when(clubService.selectClubById(CLUB)).thenReturn(club);
        when(clubAccessService.resolve(OWNER, CLUB, null)).thenReturn(new ClubAccessContext(
                OWNER, CLUB, OWNER, null,
                new LinkedHashSet<>(Arrays.asList(ClubRoleCode.CLUB_OWNER))));

        api = new ApiClubSettlementController(settlements, clubService, clubAccessService, cashWithdrawService) {
            @Override
            public Long getAppUserId() {
                return OWNER;
            }
        };

        jdbc.execute("insert into cms_topic (id, name, del_flag) values (" + T_Named + ", 'E2E 探店日一期', 0)");
        settlement(31L, T_Named, "130.00");
        settlement(32L, T_NoRow, "20.00");
    }

    private void settlement(long id, long topicId, String amount) {
        jdbc.execute("insert into coop_settlement (id, topic_id, merchant_id, payee_type, beneficiary_role,"
                + " beneficiary_id, amount, status, del_flag, create_time) values (" + id + ", " + topicId + ", 1,"
                + " 'club', 'CLUB', " + CLUB + ", " + amount + ", 0, 0, now())");
    }

    @Test
    void summaryCarriesTheTopicNameFromCmsTopicAndOnlyFallsBackWhenMissing() {
        Map<String, Object> body = new HashMap<>();
        body.put("clubId", CLUB);

        AjaxResult result = api.summary(body);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topics = (List<Map<String, Object>>) data.get("topics");
        assertEquals(2, topics.size());

        Map<Long, String> nameByTopic = new HashMap<>();
        for (Map<String, Object> row : topics) {
            nameByTopic.put((Long) row.get("topicId"), (String) row.get("name"));
        }
        assertEquals("E2E 探店日一期", nameByTopic.get(T_Named),
                "有名字的主题必须回真名 —— 恒拼「主题 #id」正是 CU-C-40 的走查现象");
        assertEquals("主题 #" + T_NoRow, nameByTopic.get(T_NoRow),
                "cms_topic 查不到时才退回编号,且不能给空串(前端整块拒收)");
    }
}
