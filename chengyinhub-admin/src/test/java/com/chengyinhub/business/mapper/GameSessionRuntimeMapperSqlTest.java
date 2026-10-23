package com.chengyinhub.business.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameSessionRuntimeMapperSqlTest {

    private static final String XML = "mapper/business/GameSessionRuntimeMapper.xml";
    private static final String NS = "com.chengyinhub.business.mapper.GameSessionRuntimeMapper.";

    @Test
    void teamProjectionIsSameSessionSameTeamAndUsesOnlyOutcomesForCompletion() throws Exception {
        String sql = sql("selectTeamActionProjection", params());
        assertTeamProjection(sql);
        assertTrue(sql.contains("when 'pending' then 'submitted'"), sql);
        assertTrue(sql.contains("when 'completed' then 'completed'"), sql);
        assertTrue(sql.contains("when 'fallback_completed' then 'fallback_completed'"), sql);
        assertFalse(sql.contains("when 'approved' then 'completed'"), sql);
        assertFalse(sql.contains("when 'recorded' then 'completed'"), sql);

        String weakened = sql.replace("m.team_id=?", "m.team_id=m.team_id");
        assertThrows(AssertionError.class, () -> assertTeamProjection(weakened));
    }

    @Test
    void submissionHistoryIsActorScopedAndAllowlisted() throws Exception {
        String sql = sql("selectMySubmissionProjection", params());
        assertTrue(sql.contains("game_session_id=? and member_id=?"), sql);
        assertTrue(sql.contains("limit 50"), sql);
        assertFalse(sql.contains("evidence_json"), sql);
        assertTrue(sql.contains("decision_reason_code as reasoncode"), sql);
        assertTrue(sql.contains("decision_reason as reason"), sql);
    }

    @Test
    void activeSubmissionGuardMakesCreateAndReadbackRetrySafe() throws Exception {
        String insert = sql("insertSubmission", params());
        String active = sql("selectActiveSubmission", params());
        String locked = sql("selectActiveSubmissionForUpdate", params());
        String leaderboard = sql("selectSessionLeaderboard", params());

        assertTrue(insert.startsWith("insert ignore into game_task_submission"), insert);
        assertActiveSubmissionLookup(active);
        assertActiveSubmissionLookup(locked);
        assertTrue(locked.endsWith("for update"), locked);
        assertTrue(leaderboard.contains(
                "count(distinct case when o.status='completed' then o.node_id end) as score"), leaderboard);
    }

    @Test
    void playerNodeOutcomeIsTheImmutableCompletionAndLeaderboardAuthority() throws Exception {
        String insert = sql("insertPlayerNodeOutcome", params());
        String one = sql("selectPlayerNodeOutcome", params());
        String all = sql("selectPlayerNodeOutcomes", params());
        String leaderboard = sql("selectSessionLeaderboard", params());
        String progress = sql("selectClubTeamProgressProjection", params());
        String team = sql("selectTeamActionProjection", params());
        String activeSubmission = sql("selectActiveSubmission", params());
        String stationUpdate = sql("updateStationStatusCas", params());

        assertTrue(insert.startsWith("insert ignore into game_player_node_outcome"), insert);
        assertTrue(one.contains("game_session_id=? and member_id=? and node_id=?"), one);
        assertTrue(all.contains("game_session_id=? and member_id=?"), all);
        assertTrue(all.contains("order by id"), all);
        assertTrue(leaderboard.contains("left join game_player_node_outcome o"), leaderboard);
        assertTrue(leaderboard.contains("o.status='completed'"), leaderboard);
        assertFalse(leaderboard.contains("game_task_submission"), leaderboard);
        assertTrue(progress.contains("o.status in ('completed','fallback_completed')"), progress);
        assertTrue(team.contains("when 'fallback_completed' then 'fallback_completed'"), team);
        assertTrue(activeSubmission.contains("status in ('pending','approved','recorded')"), activeSubmission);
        assertTrue(stationUpdate.contains("fallback_plan_code=?"), stationUpdate);
        assertTrue(stationUpdate.contains("fallback_plan_version=?"), stationUpdate);
    }

    @Test
    void startActivatesEveryRequiredStationOrNoneWhenAnyStationIsNotRunnable() throws Exception {
        String sql = sql("activateReadyStations", params());

        assertAllOrNothingActivation(sql);

        String weakened = sql.replace("and blocker.id is null", "");
        assertThrows(AssertionError.class, () -> assertAllOrNothingActivation(weakened));
    }

    @Test
    void declinedClosedStationsRemainConcreteReadinessBlockers() throws Exception {
        String sql = sql("countUnreadyStations", params());

        assertTrue(sql.contains("status<>'ready'"), sql);
        assertFalse(sql.contains("'closed'"), sql);
    }

    @Test
    void clubProgressProjectionLocatesAuthoritativeStuckNodeHintAndRecentReceipt() throws Exception {
        String sql = sql("selectClubTeamProgressProjection", params());

        assertTrue(sql.contains("as currentstucknodeid"), sql);
        assertTrue(sql.contains("as currentstucknodename"), sql);
        assertTrue(sql.contains("as currentstuckstationstatus"), sql);
        assertTrue(sql.contains("as currentstucksubmissionstatus"), sql);
        assertTrue(sql.contains("as hintlevel"), sql);
        assertTrue(sql.contains("hint.action='player_hint' and hint.outcome='applied'"), sql);
        assertTrue(sql.contains("as recenteventrequestid"), sql);
        assertTrue(sql.contains("as recenteventaction"), sql);
        assertTrue(sql.contains("as recenteventoutcome"), sql);
        assertTrue(sql.contains("as recenteventat"), sql);
        assertTrue(sql.contains("recent.game_session_id=s.id"), sql);
        assertTrue(sql.contains("recent.actor_member_id=m_recent.member_id"), sql);
        assertTrue(sql.contains("recent.actor_perspective='player'"), sql);
        assertTrue(sql.contains("hint_reg.member_id=hint_member.member_id"), sql);
        assertTrue(sql.contains("recent_reg.member_id=m_recent.member_id"), sql);
        assertFalse(sql.contains("coalesce(max(cast(json_unquote(json_extract(hint.result_json"), sql);
    }

    @Test
    void roleAssignmentHistoryOnlyProjectsAndCountsActiveRows() throws Exception {
        String locked = sql("selectActiveRoleForUpdate", params());
        String supersede = sql("supersedeActiveRole", params());
        String insert = sql("insertRole", params());
        String count = sql("countRole", params());
        String roster = sql("selectClubMemberProjection", params());
        String team = sql("selectTeamActionProjection", params());
        String deliveries = sql("insertBroadcastDeliveries", params());

        assertTrue(locked.contains("status in ('assigned','confirmed')"), locked);
        assertTrue(locked.endsWith("for update"), locked);
        assertTrue(supersede.contains("set status='superseded'"), supersede);
        assertTrue(supersede.contains("superseded_by=?"), supersede);
        assertTrue(supersede.contains("status in ('assigned','confirmed')"), supersede);
        assertTrue(insert.startsWith("insert ignore into game_team_role_assignment"), insert);
        assertFalse(insert.contains("on duplicate key update"), insert);
        assertTrue(count.contains("status in ('assigned','confirmed')"), count);
        assertTrue(roster.contains("r.status in ('assigned','confirmed')"), roster);
        assertTrue(team.contains("r.status in ('assigned','confirmed')"), team);
        assertTrue(deliveries.contains("r.status in ('assigned','confirmed')"), deliveries);
    }

    @Test
    void storyStatePersistsHiddenAppliedChoiceSet() throws Exception {
        String select = sql("selectStoryState", params());
        String insert = sql("insertStoryState", params());
        String update = sql("updateStoryStateCas", params());
        assertTrue(select.contains("applied_choices_json"), select);
        assertTrue(insert.contains("applied_choices_json"), insert);
        assertTrue(update.contains("applied_choices_json=?"), update);
    }

    @Test
    void receiptCollisionCanBeReadBackUnderLock() throws Exception {
        String sql = sql("selectReceiptForUpdate", params());
        assertTrue(sql.contains("activity_id=? and actor_member_id=? and request_id=?"), sql);
        assertTrue(sql.endsWith("for update"), sql);
    }

    @Test
    void receiptRereadClearsSessionCacheIncludingAnEarlierMiss() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream in = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(in, configuration, XML, configuration.getSqlFragments()).parse();
        }
        var statement = configuration.getMappedStatement(NS + "selectReceiptForUpdate");
        assertTrue(statement.isFlushCacheRequired(), "锁后回读不能复用等待前的空结果");
        assertFalse(statement.isUseCache(), "命令回执锁读不能命中二阶缓存");
    }

    @Test
    void hintLevelsComeOnlyFromAppliedPlayerHintReceipts() throws Exception {
        String sql = sql("selectPlayerHintLevels", params());
        assertTrue(sql.contains("game_session_id=? and actor_member_id=?"), sql);
        assertTrue(sql.contains("action='player_hint' and outcome='applied'"), sql);
        assertTrue(sql.contains("json_extract(result_json, '$.hint.currentlevel')"), sql);
        assertTrue(sql.contains("group by node_id"), sql);
    }

    @Test
    void clubRostersAndProgressAreActivityScopedWithoutPrivateFields() throws Exception {
        String roster = sql("selectClubMemberProjection", params());
        String progress = sql("selectClubTeamProgressProjection", params());
        assertTrue(roster.contains("t.owner_id=s.activity_id"), roster);
        assertTrue(progress.contains("t.owner_id=s.activity_id"), progress);
        assertTrue(progress.contains("o.status in ('completed','fallback_completed')"), progress);
        assertFalse((roster + progress).matches(".*(?:phone|mobile|latitude|longitude|evidence_json).*"));
    }

    @Test
    void readinessRostersAndBroadcastsOnlyUsePaidValidActivityMembers() throws Exception {
        assertPaidActivityMemberScope(sql("countActiveTeams", params()));
        assertPaidActivityMemberScope(sql("countTeamMembersWithoutRole", params()));
        assertPaidActivityMemberScope(sql("isActiveTeamMember", params()));
        assertPaidActivityMemberScope(sql("selectClubMemberProjection", params()));
        assertPaidActivityMemberScope(sql("selectClubTeamProgressProjection", params()));
        assertPaidActivityMemberScope(sql("insertBroadcastDeliveries", params()));
        assertPaidActivityMemberScope(sql("selectTeamActionProjection", params()));
    }

    @Test
    void merchantEntryEnumerationIsServerScopedAndAllowlisted() throws Exception {
        String sql = sql("selectMerchantEntries", params());
        assertTrue(sql.contains("r.merchant_member_id=?"), sql);
        assertTrue(sql.contains("join game_session s on s.id=r.game_session_id"), sql);
        assertTrue(sql.contains("join cms_activity a on a.id=r.activity_id"), sql);
        assertTrue(sql.contains("group by r.activity_id"), sql);
        assertFalse(sql.matches(".*(?:phone|mobile|latitude|longitude|evidence_json|module_config_snapshot_json).*"), sql);

        String weakened = sql.replace("r.merchant_member_id=?", "r.merchant_member_id=r.merchant_member_id");
        assertThrows(AssertionError.class,
                () -> assertTrue(weakened.contains("r.merchant_member_id=?"), weakened));
    }

    @Test
    void merchantRecapIsDatabaseScopedPiiFreeAndSeparatesFallbackFromNormalCompletion() throws Exception {
        String sql = sql("selectMerchantStationRecaps", params());

        assertTrue(sql.contains("r.game_session_id=? and r.merchant_member_id=?"), sql);
        assertTrue(sql.contains("when o.status='completed'"), sql);
        assertTrue(sql.contains("as normalcompletedcount"), sql);
        assertTrue(sql.contains("when o.status='fallback_completed'"), sql);
        assertTrue(sql.contains("as fallbackcompletedcount"), sql);
        assertTrue(sql.contains("checkin_time is not null"), sql);
        assertTrue(sql.contains("'no_public_content' as contentpolicy"), sql);
        assertTrue(sql.contains("0 as authorizedcontentcount"), sql);
        assertFalse(sql.matches(".*(?:phone|mobile|avatar|latitude|longitude|evidence_json).*"), sql);
        assertFalse(sql.contains("as checkintime"), sql);

        String weakened = sql.replace("r.merchant_member_id=?", "r.merchant_member_id=r.merchant_member_id");
        assertThrows(AssertionError.class,
                () -> assertTrue(weakened.contains("r.merchant_member_id=?"), weakened));
    }

    @Test
    void clubRecapCountsOnlyAppliedCommandsAndNeverTreatsFallbackAsNormalCompletion() throws Exception {
        String aggregate = sql("selectClubRecapAggregate", params());
        String stations = sql("selectClubStationRecaps", params());
        String collaboration = sql("selectClubRoleCollaboration", params());
        String all = aggregate + " " + stations + " " + collaboration;

        assertTrue(aggregate.contains("s.id=? and s.activity_id=?"), aggregate);
        assertTrue(aggregate.contains("c.action='player_hint' and c.outcome='applied'"), aggregate);
        assertTrue(aggregate.contains("c.action='player_reveal' and c.outcome='applied'"), aggregate);
        assertTrue(aggregate.contains("c.action in ('station_pause','club_station_pause') and c.outcome='applied'"), aggregate);
        assertTrue(aggregate.contains("c.action='takeover_role' and c.outcome='applied'"), aggregate);
        assertTrue(aggregate.contains("when o.status='completed'"), aggregate);
        assertTrue(aggregate.contains("as normalcompleters"), aggregate);
        assertTrue(aggregate.contains("when o.status='fallback_completed'"), aggregate);
        assertTrue(aggregate.contains("as fallbackcompleters"), aggregate);
        assertTrue(stations.contains("when o.status='completed'"), stations);
        assertTrue(stations.contains("as normalcompletedcount"), stations);
        assertTrue(stations.contains("when o.status='fallback_completed'"), stations);
        assertTrue(stations.contains("as fallbackcompletedcount"), stations);
        assertTrue(collaboration.contains("r.status='confirmed'"), collaboration);
        assertTrue(collaboration.contains("r.status in ('confirmed','superseded') and o.id is not null"), collaboration);
        assertFalse(all.matches(".*(?:phone|mobile|avatar|latitude|longitude|evidence_json).*"), all);
        assertFalse(all.contains("as checkintime"), all);
    }

    @Test
    void prepareSnapshotsOnlyOnlineAndPubliclyApprovedTopicNodes() throws Exception {
        String sql = sql("insertStationsFromTopic", params());

        assertTrue(sql.contains("n.status=1"), sql);
        assertTrue(sql.contains("n.merchant_member_id is null or n.node_audit_status=1"), sql);
    }

    @Test
    void schemaLocksReceiptUniquenessAndFixtureChecklistLabels() throws Exception {
        String migration = readSql("migration_20260823_game_session_runtime.sql").toLowerCase();
        String seed = readFixture("game-module-mystery-market-seed.sql");
        assertTrue(migration.contains("unique key uk_game_receipt_actor_request (activity_id, actor_member_id, request_id)"));
        assertTrue(migration.contains("active_guard tinyint generated always as"));
        assertTrue(migration.contains("unique key uk_game_role_active (game_session_id, member_id, active_guard)"));
        assertTrue(migration.contains("unique key uk_game_submission_active (game_session_id, member_id, node_id, task_code, active_guard)"));
        assertTrue(migration.contains("applied_choices_json json not null"));
        assertTrue(migration.contains("superseded_by bigint null"));
        assertTrue(migration.contains("superseded_at datetime null"));
        assertTrue(migration.contains("leaderboard_visible tinyint(1) not null default 0"));
        assertTrue(seed.contains("'label','值班人员到位'"));
        assertTrue(seed.contains("$.stations[2].preparationChecklist[0].label"));
        assertTrue(seed.contains("WHERE member_id=100000 AND create_by='seed_game_module_mystery_market_20260823'"));
        assertFalse(seed.contains("WHERE name='商圈谜案季' AND del_flag=0;"));
        assertTrue(seed.contains("PRODUCTION: DO NOT EXECUTE"));
        assertFalse(seed.contains("拍下"));
        assertTrue(seed.contains("'stationCode','ARCHIVE','allowedRoles',JSON_ARRAY('INVESTIGATOR')"));
        assertTrue(seed.contains("'stationCode','COUNTER','allowedRoles',JSON_ARRAY('LIAISON')"));
        assertTrue(seed.contains("'stationCode','ALLEY','allowedRoles',JSON_ARRAY('SCRIBE')"));
        assertTrue(seed.contains("'choiceGroup','COUNTER_DECISION'"));
        assertTrue(seed.contains("'id','VERIFY'"));
        assertTrue(seed.contains("'id','SKIP_VERIFY'"));
        assertTrue(seed.contains("'code','TEAM_TRUTH'"));
        assertTrue(seed.contains("'code','OPEN_CASE'"));
        assertTrue(seed.contains("'chapterId',2,'choiceGroup','COUNTER_DECISION'"));
        assertTrue(seed.contains("'inputType','TEXT'"));
        assertTrue(seed.contains("'inputType','SCAN'"));
        assertTrue(seed.contains("'inputType','PHOTO'"));
        assertTrue(seed.contains("'expectedHash','d54123de468bd42ea00dafbd777f85fe5fa1ff6404d9838c007953c25c92a1c5'"));
        assertTrue(seed.contains("'expectedHash','41380f64cee57819f882c19b05601729aa4f573d1234299b53774e6bf0d6e376'"));
        assertTrue(seed.contains("'completionPolicy','EVIDENCE_ONLY'"));
        assertTrue(seed.contains("'fallbackPlans',JSON_ARRAY(JSON_OBJECT('planCode','ARCHIVE_TO_COUNTER'"));
        assertTrue(seed.contains("COUNT(DISTINCT n.merchant_member_id)=3"));
        assertTrue(seed.contains("MIN(n.merchant_member_id)=100000 AND MAX(n.merchant_member_id)=100002"));
        assertTrue(seed.contains("'merchantInstruction'"));
        assertTrue(seed.contains("'hiddenInfoReminder'"));
    }

    @Test
    void schemaDefinesImmutablePlayerNodeOutcomesAndExactNineTableReadback() throws Exception {
        String migration = normalizeSql(readSql("migration_20260823_game_session_runtime.sql"));

        assertTrue(migration.contains("create table if not exists game_player_node_outcome"), migration);
        assertTrue(migration.contains("status varchar(24) not null"), migration);
        assertTrue(migration.contains("completion_source varchar(32) not null"), migration);
        assertTrue(migration.contains("source_submission_id bigint null"), migration);
        assertTrue(migration.contains("source_receipt_id bigint null"), migration);
        assertTrue(migration.contains("fallback_plan_code varchar(64) null"), migration);
        assertTrue(migration.contains("fallback_plan_version int null"), migration);
        assertTrue(migration.contains("completed_at datetime not null"), migration);
        assertTrue(migration.contains(
                "unique key uk_game_outcome_player_node (game_session_id, member_id, node_id)"), migration);
        assertTrue(migration.contains("fallback_plan_code varchar(64) null"), migration);
        assertTrue(migration.contains("fallback_plan_version int null"), migration);
        assertTrue(migration.contains("status in ('pending','approved','recorded')"), migration);
        assertTrue(migration.contains("'game_command_receipt','game_task_submission','game_player_node_outcome'"), migration);
        assertTrue(migration.contains("and table_type='base table' and engine='innodb')=9"), migration);
        assertTrue(migration.contains("index_name='uk_game_outcome_player_node'"), migration);
        assertTrue(migration.contains("1:game_session_id:0,2:member_id:0,3:node_id:0"), migration);
        assertTrue(migration.contains("game_player_node_outcome.completion_source:varchar(32):no"), migration);
        assertTrue(migration.contains("game_player_node_outcome.completed_at:datetime:no"), migration);
        assertTrue(migration.contains("game_station_run.fallback_plan_code:varchar(64):yes"), migration);
        assertTrue(migration.contains("game_station_run.fallback_plan_version:int:yes"), migration);
    }

    @Test
    void schemaReadbackPinsUniqueIndexOrderGeneratedGuardsAndJsonNullability() throws Exception {
        String migration = normalizeSql(readSql("migration_20260823_game_session_runtime.sql"));
        String readback = migration.substring(migration.indexOf("-- runner: assert=scalar-true"));
        assertSchemaReadback(readback);

        String reorderedReceiptIndex = readback.replace(
                "1:activity_id:0,2:actor_member_id:0,3:request_id:0",
                "1:activity_id:0,2:request_id:0,3:actor_member_id:0");
        String nonUniqueReceiptIndex = readback.replace(
                "1:activity_id:0,2:actor_member_id:0,3:request_id:0",
                "1:activity_id:1,2:actor_member_id:1,3:request_id:1");
        String weakenedRoleGuard = readback.replace(
                "casewhenstatusin''assigned'',''confirmed''then1elsenullend",
                "casewhenstatus=''assigned''then1elsenullend");
        String nullableSessionSnapshot = readback.replace(
                "game_session.module_config_snapshot_json:json:no",
                "game_session.module_config_snapshot_json:json:yes");

        assertThrows(AssertionError.class, () -> assertSchemaReadback(reorderedReceiptIndex));
        assertThrows(AssertionError.class, () -> assertSchemaReadback(nonUniqueReceiptIndex));
        assertThrows(AssertionError.class, () -> assertSchemaReadback(weakenedRoleGuard));
        assertThrows(AssertionError.class, () -> assertSchemaReadback(nullableSessionSnapshot));
    }

    private void assertTeamProjection(String sql) {
        assertTrue(sql.contains("join game_session s on s.id=?"), sql);
        assertTrue(sql.contains("t.id=? and t.owner_type=2 and t.owner_id=s.activity_id"), sql);
        assertTrue(sql.contains("m.team_id=? and m.status=1"), sql);
        assertFalse(sql.matches(".*(?:phone|mobile|latitude|longitude|evidence_json).*"), sql);
    }

    private void assertActiveSubmissionLookup(String sql) {
        assertTrue(sql.contains("game_session_id=? and member_id=?"), sql);
        assertTrue(sql.contains("node_id=? and task_code=?"), sql);
        assertTrue(sql.contains("status in ('pending','approved','recorded')"), sql);
    }

    private void assertPaidActivityMemberScope(String sql) {
        assertTrue(sql.contains("reg.member_id=m.member_id"), sql);
        assertTrue(sql.contains("reg.owner_type=2 and reg.owner_id=t.owner_id"), sql);
        assertTrue(sql.contains("reg.payment_status=2 and reg.registration_status=2"), sql);
        assertFalse(sql.contains("reg.del_flag"), "cms_registration 没有 del_flag 列,不得再引用:" + sql);
    }

    private void assertSchemaReadback(String migration) {
        assertTrue(migration.contains("table_type='base table' and engine='innodb'"), migration);
        assertTrue(migration.contains("concat(seq_in_index, ':', column_name, ':', non_unique)"), migration);
        assertTrue(migration.contains("index_name='uk_game_session_activity'"), migration);
        assertTrue(migration.contains("index_name='uk_game_station_session_node'"), migration);
        assertTrue(migration.contains("index_name='uk_game_station_session_code'"), migration);
        assertTrue(migration.contains("index_name='uk_game_role_active'"), migration);
        assertTrue(migration.contains("index_name='uk_game_story_owner'"), migration);
        assertTrue(migration.contains("index_name='uk_game_receipt_actor_request'"), migration);
        assertTrue(migration.contains("index_name='uk_game_submission_active'"), migration);
        assertTrue(migration.contains("index_name='uk_game_outcome_player_node'"), migration);
        assertTrue(migration.contains("index_name='uk_game_delivery_member'"), migration);
        assertTrue(migration.contains("1:activity_id:0"), migration);
        assertTrue(migration.contains("1:game_session_id:0,2:node_id:0"), migration);
        assertTrue(migration.contains("1:game_session_id:0,2:station_code:0"), migration);
        assertTrue(migration.contains("1:game_session_id:0,2:member_id:0,3:active_guard:0"), migration);
        assertTrue(migration.contains("1:game_session_id:0,2:owner_type:0,3:owner_id:0"), migration);
        assertTrue(migration.contains("1:activity_id:0,2:actor_member_id:0,3:request_id:0"), migration);
        assertTrue(migration.contains(
                "1:game_session_id:0,2:member_id:0,3:node_id:0,4:task_code:0,5:active_guard:0"), migration);
        assertTrue(migration.contains("1:game_session_id:0,2:member_id:0,3:node_id:0"), migration);
        assertTrue(migration.contains("1:broadcast_id:0,2:member_id:0"), migration);
        assertTrue(migration.contains("generation_expression"), migration);
        assertTrue(migration.contains("extra"), migration);
        assertTrue(migration.contains("casewhenstatusin''assigned'',''confirmed''then1elsenullend"), migration);
        assertTrue(migration.contains("casewhenstatusin''pending'',''approved'',''recorded''then1elsenullend"), migration);
        assertTrue(migration.contains("cms_topic.game_module_config_json:json:yes"), migration);
        assertTrue(migration.contains("game_session.module_config_snapshot_json:json:no"), migration);
        assertTrue(migration.contains("game_story_state.applied_choices_json:json:no"), migration);
        assertTrue(migration.contains("game_story_state.variables_json:json:no"), migration);
        assertTrue(migration.contains("game_task_submission.evidence_json:json:no"), migration);
    }


    @Test
    void stationCasSupportsNullPublicOwnerWithoutDroppingSessionOwnerStatusOrRevision() throws Exception {
        Map<String, Object> values = params();
        values.put("merchantMemberId", null);
        String update = sql("updateStationStatusCas", values);
        assertTrue(update.contains("where id=? and game_session_id=?"), update);
        assertTrue(update.contains("merchant_member_id <=> ?"), update);
        assertTrue(update.contains("and status=? and revision=?"), update);
    }

    @Test
    void ordinaryRouteRecapUsesValidActivityProgressAndCountsClubPauseReceipts() throws Exception {
        String aggregate = sql("selectClubRecapAggregate", params());
        String stations = sql("selectClubStationRecaps", params());
        String teams = sql("selectClubRouteTeamProgressProjection", params());
        assertTrue(aggregate.contains("json_extract(s.module_config_snapshot_json, '$.playmode')"), aggregate);
        assertTrue(aggregate.contains("count(distinct p.member_id) from player_node_progress p"), aggregate);
        assertTrue(aggregate.contains("count(distinct p.node_id) from player_node_progress p"), aggregate);
        assertTrue(stations.contains("p.status=1 and p.del_flag=0"), stations);
        assertTrue(stations.contains("p.activity_id=r.activity_id and p.topic_id=s.topic_id and p.node_id=r.node_id"), stations);
        assertTrue(aggregate.contains("c.action in ('station_pause','club_station_pause')"), aggregate);
        assertTrue(stations.contains("c.action in ('station_pause','club_station_pause')"), stations);
        assertPaidActivityMemberScope(aggregate);
        assertPaidActivityMemberScope(stations);
        assertTrue(teams.contains("p.topic_id=s.topic_id and p.status=1 and p.del_flag=0"), teams);
        assertTrue(teams.contains("sr.game_session_id=s.id and sr.node_id=p.node_id"), teams);
    }

    @Test
    void sessionStartReadsOldStatusBeforeAssignmentAndEndedRecapUsesAppliedSameRevisionReceipt() throws Exception {
        String update = sql("updateSessionCas", params());
        assertTrue(update.indexOf("started_at=case") < update.indexOf("status=?"), update);
        String recap = sql("selectFinishedRecapJson", params());
        assertTrue(recap.contains("s.id=? and s.status='finished'"), recap);
        assertTrue(recap.contains("s.activity_id=c.activity_id"), recap);
        assertTrue(recap.contains("c.action='finish' and c.outcome='applied' and c.revision=s.revision"), recap);
        assertTrue(recap.contains("'$.recap.schemaversion'"), recap);
    }

    @Test
    void routeReceptionReadsOnlyEarlierEffectiveMerchantsAndExactGroupEvidenceScope() throws Exception {
        String sql = sql("countMissingRouteReception", params());
        assertTrue(sql.contains("s.id=? and s.club_id > 0"), sql);
        assertTrue(sql.contains("coalesce(previous.sort_id,0) < coalesce(target.sort_id,0)"), sql);
        assertTrue(sql.contains("target.topic_id=s.topic_id"), sql);
        assertTrue(sql.contains("receipt.activity_id=prior.activity_id"), sql);
        assertTrue(sql.contains("receipt.topic_id=prior.topic_id"), sql);
        assertTrue(sql.contains("receipt.merchant_member_id=prior.merchant_member_id"), sql);
        assertTrue(sql.contains("fallback.merchant_member_id"), sql);
        assertFalse(sql.contains("insert "), sql);
        assertFalse(sql.contains("update "), sql);
    }

    private Map<String, Object> params() {
        Map<String, Object> values = new HashMap<>();
        values.put("sessionId", 1L);
        values.put("teamId", 2L);
        values.put("memberId", 3L);
        values.put("activityId", 4L);
        values.put("actorMemberId", 3L);
        values.put("requestId", "req-1");
        values.put("nodeId", 5L);
        values.put("taskCode", "TASK-1");
        values.put("roleCode", "INVESTIGATOR");
        values.put("targetType", "ROLE");
        values.put("roleCode", "INVESTIGATOR");
        values.put("supersededBy", 3L);
        values.put("merchantMemberId", 9L);
        return values;
    }

    private String sql(String statement, Object parameter) throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream in = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(in, configuration, XML, configuration.getSqlFragments()).parse();
        }
        return configuration.getMappedStatement(NS + statement).getBoundSql(parameter).getSql()
                .replaceAll("\\s+", " ").replaceAll("\\s*=\\s*", "=").trim().toLowerCase();
    }

    private String readSql(String name) throws Exception {
        Path path = Paths.get("sql", name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private String readFixture(String name) throws Exception {
        Path path = Paths.get("src", "test", "resources", "it-schema", name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private String normalizeSql(String sql) {
        return sql.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private void assertAllOrNothingActivation(String sql) {
        assertTrue(sql.startsWith(
                "update game_station_run target left join game_station_run blocker"), sql);
        assertTrue(sql.contains("blocker.game_session_id=target.game_session_id"), sql);
        assertTrue(sql.contains("blocker.status<>'ready'"), sql);
        assertTrue(sql.contains("blocker.service_start_at is null"), sql);
        assertTrue(sql.contains("blocker.service_end_at is null"), sql);
        assertTrue(sql.contains("blocker.service_start_at>?"), sql);
        assertTrue(sql.contains("blocker.service_end_at<=?"), sql);
        assertTrue(sql.contains("target.status='ready'"), sql);
        assertTrue(sql.contains("target.service_start_at<=?"), sql);
        assertTrue(sql.contains("target.service_end_at>?"), sql);
        assertTrue(sql.contains("and blocker.id is null"), sql);
    }
}
