package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubGovernanceBan;
import com.chengyinhub.business.domain.ClubGovernanceCase;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ClubGovernanceMapperSqlTest {
    private static final String XML = "mapper/business/ClubGovernanceMapper.xml";
    private static final String NS = "com.chengyinhub.business.mapper.ClubGovernanceMapper.";

    @Test
    void effectiveLookupAndExpiryUseBothClubAndMemberScope() throws Exception {
        Map<String, Object> params = params();
        String lookup = sql("selectEffectiveByClubAndMember", params);
        assertTrue(lookup.contains("club_id = ?") && lookup.contains("target_member_id = ?"));
        assertTrue(lookup.contains("status = 'active'"));
        assertFalse(lookup.contains("source_type ="),
                "ACTIVE 脏行也必须返回 service fail-closed，不得被 SQL 过滤后误放行");

        String expiry = sql("expireStaleByClubAndMember", params);
        assertTrue(expiry.contains("set status = 'expired'") && expiry.contains("status = 'active'"));
        assertTrue(expiry.contains("source_type = 'club'"), "平台封禁永不自动过期");
        assertTrue(expiry.contains("club_id = ?") && expiry.contains("target_member_id = ?"));
    }

    @Test
    void duplicateLookupCoversBanAndUnbanKeysWithinOneClub() throws Exception {
        Map<String, Object> params = params();
        params.put("requestId", "req-1");
        String query = sql("selectByRequestIdForUpdate", params);
        assertTrue(query.contains("club_id = ?"));
        assertTrue(query.contains("ban_request_id = ?") && query.contains("unban_request_id = ?"));
        assertTrue(query.endsWith("for update"));
    }

    @Test
    void unbanIsVersionedCasAndNeverTouchesAnotherClub() throws Exception {
        Map<String, Object> params = params();
        params.put("id", 81L);
        params.put("expectedVersion", 2);
        params.put("unbannedByMemberId", 900L);
        params.put("unbanReason", "复核通过");
        params.put("unbanRequestId", "unban-1");
        params.put("updateBy", "900");
        String update = sql("unbanCas", params);
        assertTrue(update.contains("set status = 'unbanned'"));
        assertTrue(update.contains("where id = ? and club_id = ?"));
        assertTrue(update.contains("status = 'active' and version = ?"));
        assertTrue(update.contains("source_type = 'club'"),
                "俱乐部 owner/co-owner 不得解除平台永久封禁");
    }

    @Test
    void ownerTransferUsesSingleClubOwnerCasAndAtomicMembershipSwapStatements() throws Exception {
        Map<String, Object> params = params();
        params.put("expectedOwnerMemberId", 900L);
        params.put("newOwnerMemberId", 902L);
        params.put("memberId", 902L);
        params.put("joinTime", new Date());
        String transfer = sql("transferClubOwnerCas", params);
        assertTrue(transfer.contains("update club set member_id = ?"));
        assertTrue(transfer.contains("where id = ? and member_id = ?"));
        assertTrue(transfer.contains("status = 1 and del_flag = 0"));

        String removeNewOwnerMembership = sql("deleteActiveMemberForOwnerTransfer", params);
        assertTrue(removeNewOwnerMembership.contains("club_id = ? and member_id = ?"));
        String addFormerOwnerMembership = sql("upsertFormerOwnerAsMember", params);
        assertTrue(addFormerOwnerMembership.contains("on duplicate key update"));
    }

    @Test
    void governanceCasesHaveSeparateFilerAndPlatformScopedQueriesWithVersionedReview() throws Exception {
        Map<String, Object> params = params();
        params.put("filerMemberId", 903L);
        params.put("caseType", "APPEAL");
        params.put("status", "PENDING");
        String mine = sql("selectMyCases", params);
        assertTrue(mine.contains("club_id = ? and filer_member_id = ?"));

        params.put("id", 101L);
        params.put("expectedVersion", 2);
        params.put("decidedByUserId", 7001L);
        params.put("decisionReason", "申诉成立");
        params.put("decisionRequestId", "review-1");
        String review = sql("reviewCaseCas", params);
        assertTrue(review.contains("where id = ? and status = 'pending' and version = ?"));

        ClubGovernanceCase row = new ClubGovernanceCase();
        row.setClubId(11L);
        row.setCaseType(ClubGovernanceCase.TYPE_REPORT);
        row.setFilerMemberId(903L);
        row.setTargetType(ClubGovernanceCase.TARGET_ACTIVITY);
        row.setTargetId(501L);
        row.setEvidenceJson("{\"orderNo\":\"CY-1\"}");
        row.setReason("现场风险");
        row.setStatus(ClubGovernanceCase.STATUS_PENDING);
        row.setSubmitRequestId("report-1");
        row.setVersion(0);
        row.setCreateTime(new Date());
        row.setUpdateTime(new Date());
        String insert = sql("insertCase", row);
        assertTrue(insert.contains("target_type, target_id, evidence_json"),
                "工单必须实际持久受控目标和证据 JSON");

        String platform = sql("selectPlatformCases", params);
        assertTrue(platform.contains("target_type, target_id, evidence_json"),
                "只有平台工单查询使用完整证据行");
    }

    private Map<String, Object> params() {
        Map<String, Object> params = new HashMap<>();
        params.put("clubId", 11L);
        params.put("targetMemberId", 902L);
        params.put("now", new Date());
        return params;
    }

    private String sql(String statement, Object parameter) throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("ClubGovernanceBan", ClubGovernanceBan.class);
        try (InputStream in = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(in, configuration, XML, configuration.getSqlFragments()).parse();
        }
        return configuration.getMappedStatement(NS + statement).getBoundSql(parameter).getSql()
                .replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
