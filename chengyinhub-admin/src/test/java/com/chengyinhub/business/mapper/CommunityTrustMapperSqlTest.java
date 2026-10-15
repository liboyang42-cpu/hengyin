package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CommunityReportCase;
import com.chengyinhub.business.domain.MerchantContribution;
import com.chengyinhub.business.domain.MerchantSafetyRating;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunityTrustMapperSqlTest {
    @Test
    void recoveryKeyOnlyNarrowsTheExistingOwnerQueryWithoutTakingAWriteLock() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("reporterMemberId", 101L);
        params.put("stage", null);
        params.put("requestId", "report-recovery-key");
        String filtered = sql("selectReportsForOwner", params);
        assertTrue(filtered.contains("where reporter_member_id = ? and request_id = ?"));
        assertFalse(filtered.contains("report-recovery-key"));
        assertFalse(filtered.contains("for update"));
        params.put("stage", "CLOSED");
        assertTrue(sql("selectReportsForOwner", params).contains("request_id = ? and stage = ?"));
        params.put("requestId", null);
        String original = sql("selectReportsForOwner", params);
        assertTrue(original.contains("where reporter_member_id = ? and stage = ?"));
        assertFalse(original.contains("and request_id ="));
    }

    private static final String XML = "mapper/business/CommunityTrustMapper.xml";
    private static final String NS = "com.chengyinhub.business.mapper.CommunityTrustMapper.";
    private static final String REPORTER_COLUMN = "reporter_member_id";
    private static final String REPORT_DESCRIPTION_COLUMN = "description";
    private static final String REPORT_EVIDENCE_COLUMN = "evidence_asset_ids_json";
    private static final String INTERNAL_NOTE_COLUMN = "internal_note";
    private static final String EVENT_KEY_COLUMN = "event_key";

    @Test
    void subjectReportQueryEnforcesSubjectOwnershipAndCannotFetchReporterOrEvidence() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("id", 301L);
        params.put("subjectMemberId", 102L);

        String query = sql("selectSubjectReport", params);

        assertTrue(query.contains("target_type = 'member' and target_id = ?"),
                "被举报方查询必须把登录主体写进WHERE");
        assertFalse(query.contains(REPORTER_COLUMN), "数据层不得取出举报人标识");
        assertFalse(query.contains(REPORT_DESCRIPTION_COLUMN), "数据层不得向被举报方取出自由文本");
        assertFalse(query.contains(REPORT_EVIDENCE_COLUMN), "数据层不得向被举报方取出证据标识");
        assertTrue(query.contains("policy_version"), "申诉与透明度投影必须保留案件采用的规则版本");
    }

    @Test
    void platformListAndSubjectAppealQueriesAlsoStayInsideTheirPiiScopes() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("stage", null);
        params.put("targetType", null);
        String platform = sql("selectPlatformReports", params);

        params.clear();
        params.put("id", 301L);
        params.put("subjectMemberId", 102L);
        String subjectAppeal = sql("selectSubjectReportForUpdate", params);

        assertFalse(platform.contains(REPORTER_COLUMN), "通用审核列表SQL不得读取举报人标识");
        assertFalse(platform.contains(REPORT_DESCRIPTION_COLUMN), "通用审核列表SQL不得读取举报正文");
        assertTrue(platform.contains("left join")
                        && platform.contains("min(t.create_time)")
                        && platform.contains("group by t.report_id")
                        && platform.contains("t.actor_type = 'platform'")
                        && platform.contains("t.event_type = 'stage_changed'"),
                "首响SLA必须一次聚合平台首次阶段变更，不能按案件逐行相关查询或由当前阶段猜测");
        assertFalse(subjectAppeal.contains(REPORTER_COLUMN), "被举报方申诉锁行SQL不得读取举报人标识");
        assertTrue(subjectAppeal.endsWith("for update"), "被举报方申诉授权投影必须在同一事务锁行");
    }

    @Test
    void publicTimelineSelectsOnlyTheCuratedPublicNote() throws Exception {
        String query = sql("selectPublicTimeline", 301L);

        assertTrue(query.contains("public_note"));
        assertFalse(query.contains(INTERNAL_NOTE_COLUMN), "公开时间线不得读取内部审核备注");
        assertFalse(query.contains(EVENT_KEY_COLUMN), "公开时间线不得读取可关联原始请求的事件幂等键");
    }

    @Test
    void reportAppealAndPlatformStageChangesAreExpectedStateCas() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("id", 301L);
        params.put("expectedStage", CommunityReportCase.STAGE_RESOLVED);
        params.put("nextStage", CommunityReportCase.STAGE_APPEALED);
        params.put("expectedVersion", 2);
        params.put("decisionSummary", null);
        params.put("estimatedResolveAt", new Date());

        String appeal = sql("appealReportCas", params);
        String transition = sql("transitionReportCas", params);

        assertTrue(appeal.contains("where id = ? and stage = ? and version = ?"),
                "申诉必须同时CAS阶段与版本");
        assertTrue(transition.contains("where id = ? and stage = ? and version = ?"),
                "平台阶段跳变必须同时CAS阶段与版本");
    }

    @Test
    void contributionReviewAndMergeAreExpectedStatusCas() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("id", 401L);
        params.put("expectedStatus", MerchantContribution.STATUS_PENDING);
        params.put("nextStatus", MerchantContribution.STATUS_APPROVED);
        params.put("expectedVersion", 0);
        params.put("reviewerUserId", 9001L);
        params.put("reviewReason", "证据可采纳");
        params.put("canSupplement", false);

        String review = sql("reviewContributionCas", params);
        String merge = sql("mergeContributionCas", params);

        assertTrue(review.contains("review_status = ? and version = ?"),
                "审核必须带expected status和expected version");
        assertTrue(merge.contains("review_status = 'approved' and version = ?"),
                "合并只能从APPROVED通过CAS进入MERGED");
    }

    @Test
    void contributionSupplementReopensOnlyThroughExpectedStatusAndVersionCas() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("id", 401L);
        params.put("expectedStatus", MerchantContribution.STATUS_NEEDS_EVIDENCE);
        params.put("expectedVersion", 1);
        params.put("description", "补证");
        params.put("evidenceAssetIdsJson", "[703]");
        params.put("updateTime", new Date());

        String supplement = sql("supplementContributionCas", params);

        assertTrue(supplement.contains("review_status = ? and version = ?"),
                "补证重开必须带expected status和expected version，不能先查后改");
    }

    @Test
    void safetyAggregateCountsDistinctMembersInsideTheServerWindow() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("merchantId", 201L);
        params.put("cutoff", new Date());

        String aggregate = sql("selectSafetyAggregate", params);
        String dimensions = sql("selectSafetyDimensionAggregates", params);

        assertTrue(aggregate.contains("max(id) as latest_id")
                        && aggregate.contains("group by member_id")
                        && aggregate.contains("create_time >= ?"),
                "总档位必须先按用户取服务端时间窗内最新一票再聚合");
        assertTrue(dimensions.contains("max(id) as latest_id")
                        && dimensions.contains("group by member_id")
                        && dimensions.contains("create_time >= ?"),
                "维度档位必须复用同一按用户最新一票的时间窗");
    }

    @Test
    void everyReplayLookupLocksTheStableBusinessKey() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("reporterMemberId", 101L);
        params.put("requestId", "report-member-102-harassment-v1");
        String report = sql("selectReportByRequestForUpdate", params);

        params.clear();
        params.put("contributorMemberId", 101L);
        params.put("idempotencyKey", "merchant-201-address-v1");
        String contribution = sql("selectContributionByRequestForUpdate", params);

        params.clear();
        params.put("memberId", 101L);
        params.put("idempotencyKey", "merchant-201-safety-202608");
        String safety = sql("selectSafetyRatingByRequestForUpdate", params);

        assertTrue(report.endsWith("for update"));
        assertTrue(contribution.endsWith("for update"));
        assertTrue(safety.endsWith("for update"));
    }

    private String sql(String statement, Object parameter) throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("CommunityReportCase", CommunityReportCase.class);
        configuration.getTypeAliasRegistry().registerAlias("MerchantContribution", MerchantContribution.class);
        configuration.getTypeAliasRegistry().registerAlias("MerchantSafetyRating", MerchantSafetyRating.class);
        try (InputStream in = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(in, configuration, XML, configuration.getSqlFragments()).parse();
        }
        return configuration.getMappedStatement(NS + statement).getBoundSql(parameter).getSql()
                .replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
