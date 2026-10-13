package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PlayTeam;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayTeamMapperSqlTest {

    private static final String XML = "mapper/business/PlayTeamMapper.xml";
    private static final String NS = "com.chengyinhub.business.mapper.PlayTeamMapper.";

    @Test
    void reserveSlotIsOneConditionalUpdateInsteadOfReadThenWrite() throws Exception {
        String sql = sql("reserveSlot", Collections.singletonMap("teamId", 1L));
        assertTrue(sql.contains("joined_count = joined_count + 1"), sql);
        assertTrue(sql.contains("joined_count < max_members"), sql);
        assertTrue(sql.contains("status = 0"), sql);
        // 满员边界由 PlayTeamMybatisIntegrationTest 在真实 MySQL 上验证。
    }

    @Test
    void expirationIsPerTeamConditionalAndContainsNoRefundWrite() throws Exception {
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        p.put("teamId", 1L);
        p.put("now", new Date());
        String sql = sql("endExpiredTeam", p);
        assertTrue(sql.contains("where t.id = ?"), sql);
        assertTrue(sql.contains("t.status in (0, 1)"), sql);
        assertTrue(sql.contains("t.expire_time <= ?"), sql);
        assertFalse(sql.contains("refund"), sql);
        assertFalse(sql.contains("cms_registration"), sql);
    }

    @Test
    void gameModuleTeamExpiresAfterActivityInsteadOfAtInviteDeadline() throws Exception {
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        p.put("teamId", 1L);
        p.put("now", new Date());

        assertGameLifecycleDeadline(sql("selectExpiredTeamIds", p));
        assertGameLifecycleDeadline(sql("endExpiredTeam", p));
    }

    @Test
    void memberRosterSelectsNicknameAndAvatarButNoPrivateProfileFields() throws Exception {
        String sql = sql("selectActiveMembers", Collections.singletonMap("teamId", 1L));
        assertTrue(sql.contains("u.nickname as member_name"), sql);
        assertTrue(sql.contains("u.avatar as member_avatar"), sql);
        assertFalse(sql.contains("u.name"), sql);
        assertFalse(sql.matches(".*(?:phone|mobile|real_name|id_card|latitude|longitude).*"), sql);
    }

    @Test
    void invitationCodeIsPersistedAndResolvedWithoutEnumerableId() throws Exception {
        String insert = sql("insertPlayTeam", new PlayTeam());
        String select = sql("selectPlayTeamByInviteCode", Collections.singletonMap("inviteCode", "safe"));

        assertTrue(insert.contains("invite_code"), insert);
        assertTrue(select.contains("where invite_code = ?"), select);
        assertFalse(select.contains("where id ="), select);
    }

    private void assertGameLifecycleDeadline(String sql) {
        assertTrue(sql.contains("left join cms_activity activity"), sql);
        assertTrue(sql.contains("left join cms_topic topic"), sql);
        assertTrue(sql.contains("left join game_session game on t.owner_type = 2 and game.activity_id = t.owner_id"), sql);
        assertTrue(sql.contains("game.id is null and topic.game_module_config_json is null"), sql);
        assertTrue(sql.contains("activity.end_date <= ?"), sql);
    }

    private String sql(String statement, Object parameter) throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream in = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(in, configuration, XML, configuration.getSqlFragments()).parse();
        }
        return configuration.getMappedStatement(NS + statement).getBoundSql(parameter).getSql()
                .replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
