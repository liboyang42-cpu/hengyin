#!/usr/bin/env python3
"""G 合成规格与源码证据检查；从不执行 Java/SQL/外部请求。"""
import datetime
import hashlib
import json
import re
from decimal import Decimal, ROUND_DOWN, ROUND_HALF_UP
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
BASE = "2ec68899099a1e250df0949a8383bf85b5d3ffe0"
PREFIX = "docs/delivery/architecture-day/G/"
IMPL = "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/"
SUPPORT = "chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/"
MAPPER = "chengyinhub-system/src/main/resources/mapper/business/"
API = "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/"
SQL = "chengyinhub-admin/sql/"
TEST = "chengyinhub-admin/src/test/java/com/chengyinhub/"
EXCLUSIVE_FILES = {
    SUPPORT + "CompletedRouteFactReader.java",
    "chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/CompletedRouteFactMapper.java",
    MAPPER + "CompletedRouteFactMapper.xml",
    TEST + "business/service/CompletedRouteFactReaderTest.java",
    TEST + "business/it/CompletedRouteFactMapperMySqlIT.java",
    TEST + "business/service/ArchitectureDayGSettlementGuardsTest.java",
    TEST + "web/controller/api/ArchitectureDayGCompletionFallbackTest.java",
}

# 字符串锚点只证明该基线源码形状，不能证明运行结果。
ANCHORS = {
    "E01": [(IMPL+"CmsTopicServiceImpl.java", ["public int executeTopicSettlement()", "tx.execute(status -> settleTopic", "NON_COOP_TOPIC_CASH_SETTLEMENT_UNPROVEN", "FREE_EXPLORE_LEGACY_SETTLEMENT_FORBIDDEN", "void settleCoopTopic(", "coopSettlementClaimService.prepare", "private Long resolveOrderPayee", "private void assertSettlementConservation", "private CoopSettlement payoutOneDueCoopSettlement", "pointsAwardService.awardPublisherSettlement"])],
    "E02": [(MAPPER+"CmsTopicMapper.xml", ["<select id=\"selectTopicsForSettlement\"", "r.refund_deadline", "<update id=\"claimTopicForSettlement\""])],
    "E03": [(IMPL+"CoopInviteServiceImpl.java", ["private void freezeOrderContractSnapshot", "setRoleSnapshot", "setScopeTypeSnapshot", "setEligiblePurchaseKindsSnapshot", "setFulfillmentEvidenceRuleSnapshot", "terms.put(\"compensationMode\""])],
    "E04": [(IMPL+"UnavailableCoopOrderSettlementSnapshotReader.java", ["public CoopSettlementSnapshot read(CoopOrder order) { return null; }"]), (IMPL+"CoopSettlementClaimServiceImpl.java", ["CoopSettlementSnapshot snapshot = snapshotReader.read", "public void persist", "public void appendExecutedAdjustment"])],
    "E05": [(MAPPER+"CoopSettlementEvidenceMapper.xml", ["<select id=\"selectTopicEvidenceCandidatesForUpdate\"", "#{snapshot.scopeType} = 'TOPIC_EDITION'", "sm.activity_id = #{snapshot.scopeId}", "r.owner_type = 2"]), (SUPPORT+"CoopSettlementContract.java", ["private static boolean eligible", "Long.valueOf(1L).equals(c.getOwnerType())", "c.isOrganizerScopeCovered()", "private static void validateSnapshot", "private static void allocateLargestRemainder"])],
    "E06": [(IMPL+"TopicSettlementEvidenceService.java", ["public Summary readTopic", "public List<CoopSettlementCandidate> lockCandidates", "selectTopicEvidenceCandidatesForUpdate"]), (MAPPER+"CmsRegistrationMapper.xml", ["<select id=\"selectTotalSalesByTopicId\"", "<select id=\"selectTotalSalesEvidenceByTopicIdIncludingActivityShell\"", "<select id=\"selectVerifiedSalesByTopicId\""])],
    "E07": [(IMPL+"TopicSaleFinalizationServiceImpl.java", ["public Date finalizeForSale", "assertFrozenCommercialFacts(topic", "clubPayeeService.snapshotForSale", "compensationService.freezeTermsForSale", "private void assertFrozenCommercialFacts"])],
    "E08": [(IMPL+"SettlementEntryServiceImpl.java", ["public int recordOnPayment", "private int recordPaidRegistration", "PurchaseKind.EXPLORE_PASS", "selectEffectiveOrdersByTopicId", "private int insertSplitDetails", "private SettlementClaim claimRegistration"])],
    "E09": [(SQL+"migration_settlement_claim_20260811.sql", ["CREATE TABLE settlement_claim", "UNIQUE KEY uk_settlement_claim_key", "UNIQUE KEY uk_coop_settlement_detail_claim", "UNIQUE KEY uk_coop_adjustment_event"]), (IMPL+"SettlementClaimServiceImpl.java", ["public SettlementClaim claim", "current.getSettlementRoute()", "economicClaimKey 已由"])],
    "E10": [(IMPL+"SnapshotFulfillmentWindowResolver.java", ["return snapshot == null ? null : snapshot.getFulfillmentEndExclusive();"]), (SUPPORT+"FulfillmentWindowResolver.java", ["public static Window resolve", "endDate.plusDays(1)"])],
    "E11": [(IMPL+"MerchantPayableServiceImpl.java", ["\"TOPIC_EDITION\", r.getTopicId(), \"REDEMPTION\""])],
    "E12": [(TEST+"business/service/CoopSettlementContractTest.java", ["void cutoffStatusScopeKindsAndDistinctAreFailClosed", "void salesPercentUsesOneHeaderRoundThenLargestRemainderByRegistrationId", "void missingFrozenSnapshotIsManualRepairNotGuessing"]), (TEST+"business/service/CoopSettlementClaimServiceImplTest.java", ["void threeRegistrationsProduceThreeClaimsThreeDetailsAndHeaderOnlyFromDetails", "void adjustmentAppendsByClaimEventWithoutChangingOriginDetailOrHead"])],
    "E13": [(IMPL+"PlayerNodeProgressServiceImpl.java", ["private void instance", "selectCurrentByOwner"]), (SQL+"migration_theme_runtime_20261004.sql", ["ADD UNIQUE KEY uk_progress", "play_instance_id"]), (MAPPER+"PlayerNodeProgressMapper.xml", ["and play_instance_id=coalesce", "<select id=\"selectRouteLeaderboard\""])],
    "E14": [(API+"ApiPlayProgressController.java", ["private Map<String, Object> advanceProgress", "p.setStatus(1)", "if (firstTime && (p.getPlayInstanceId()", "RewardPolicy.XP_BADGE_ONLY", "String serverReceiptId = p.getId()", "private boolean isTopicCompletedFor", "private boolean isCompletedCompletionFact", "public AjaxResult myCompleted()", "selfPlayNodesByTopic", "candidates.addAll(exploreByActivity.keySet())"])],
    "E15": [(IMPL+"TopicRouteRuntimeServiceImpl.java", ["private void recordNodeResult", "with(\"verifiedNodes\")", "\"TASK_FACT\"", "public Map<String,Object> gameplayAction", "action.setOutcomeCode(\"TASK_ARRIVAL\")", "private int statusOf", "public int countCompletedParticipants"])],
    "E16": [(MAPPER+"TopicRouteSessionMapper.xml", ["<update id=\"updateStateCas\"", "and version=#{version} and status=0", "<select id=\"selectActionByReceipt\"", "<select id=\"selectByOwners\""]), (SQL+"migration_20260823_topic_route_graph.sql", ["UNIQUE KEY uk_topic_route_owner"])],
    "E17": [(IMPL+"GameSessionRuntimeServiceImpl.java", ["private GamePlayerNodeOutcome outcomeForSubmission", "\"EVIDENCE_ONLY\".equals(upper(taskConfig", "private GamePlayerNodeOutcome ensureOutcome", "if (existing != null) return existing;", "GamePlayerNodeOutcome.FALLBACK_COMPLETED, \"ANSWER_REVEAL\"", "GamePlayerNodeOutcome.FALLBACK_COMPLETED, \"FALLBACK_PLAN\"", "story.setStatus(\"ENDING_RESOLVED\")"])],
    "E18": [(MAPPER+"GameSessionRuntimeMapper.xml", ["<update id=\"updateStoryStateCas\"", "status&lt;&gt;'ENDING_RESOLVED'", "insert ignore into game_player_node_outcome"]), (SQL+"migration_20260823_game_session_runtime.sql", ["UNIQUE KEY uk_game_story_owner", "UNIQUE KEY uk_game_outcome_player_node"])],
    "E19": [(IMPL+"PerkLedgerServiceImpl.java", ["entitlementMapper.markRedeemed", "entitlementMapper.restoreToPending", "entitlementMapper.invalidateByRegistration", "String idemKey = redeemKey"]), (MAPPER+"RegistrationEntitlementMapper.xml", ["<update id=\"markRedeemed\"", "<update id=\"invalidateByRegistration\"", "<update id=\"restoreToPending\"", "and r.payment_status = 2"])],
    "E20": [(MAPPER+"GameSessionRuntimeMapper.xml", ["as normalCompleters", "as fallbackCompleters", "as finishedTeams"]), (SUPPORT+"LeaderboardRankPolicy.java", ["public static boolean isRouteRanked", "personalGameplayTopic"]), (IMPL+"TopicOnsiteMetricsServiceImpl.java", ["boolean completed = fact.getRedeemedCount() >= required;", "result.setCompletionRate(rate(result.getCompleted(), result.getArrived()))"])],
    "E21": [(TEST+"web/controller/api/ApiPlayProgressCompletionTruthTest.java", ["void genericPlayerScanRecordsCurrentRunArrivalWithoutLegacyAwards", "void leaderboardProofScanCompletionWritesCompletedProgressRow", "void leaderboardProofPhotoCompletionWritesCompletedProgressRow", "void leaderboardProofAnswerCompletionWritesCompletedProgressRow", "void leaderboardProofAdvancedGameplayCompletionWritesCompletedProgressRow"])],
    "E22": [(API+"ApiPlayProgressController.java", ["if (explore == null)", "return topicCompletionRulePolicy.isCompleted", "if (p.getStatus() == null || p.getStatus() != 1) continue;", "new java.util.LinkedHashSet<>(doneByActivity.keySet())"])],
    "E23": [(IMPL+"PerkLedgerServiceImpl.java", ["public boolean isExploreCompleted", "pureContentExploreService.isPureContent", "CompletionRule rule = completionRule", "private CompletionRule completionRule"]), (SUPPORT+"PureContentExploreService.java", ["public boolean isPureContent", "public Set<Long> completedChapters", "Integer.valueOf(2).equals(registration.getPaymentStatus())"]), (MAPPER+"RedemptionMapper.xml", ["<select id=\"selectActiveCompletionCounts\"", "group by reg.member_id, r.registration_id"])],
    "E24": [(IMPL+"TopicCompletionAwardServiceImpl.java", ["public TopicCompletionAwardVO grantOnce", "countCompletionClawbackMarkers", "boolean pointsOpen = firstCompletion", "consume(reopened", "xpAwardMapper.selectOne"])],
    "E25": [("docs/superpowers/specs/2026-07-12-coop-multi-party-settlement-design.md", ["统一 T+7 + 客诉冻结【已定】", "真实 member_id", "核销人头"]), (SQL+"migration_settlement_claim_20260811.sql", ["直接 owner id", "UNIQUE KEY uk_settlement_claim_key"]), (IMPL+"SettlementEntryServiceImpl.java", ["private SettlementClaim claimRegistration", "? \"TOPIC_EDITION\" : \"ACTIVITY\""])],
    "E26": [("docs/theme-runtime/README.md", ["默认只在成功结束发放", "success=false，不计主题通关"]), (IMPL+"GameSessionRuntimeServiceImpl.java", ["private boolean isRouteSession", "requireFrozenTeam("])],
    "E27": [(SUPPORT+"CompletedRouteFactReader.java", ["@Transactional(readOnly = true)", "public List<TopicRouteSession> findCurrentCompletedRoutes"]), (MAPPER+"CompletedRouteFactMapper.xml", ["select max(id) as id", "group by activity_id, topic_id", "where r.member_id = #{memberId} and r.status = 1"])],
    "E28": [(IMPL+"CmsRegistrationServiceImpl.java", ["int qty = (count == null || count <= 0) ? 1 : count;", "registration.setOrderNum(Long.valueOf(qty))", "PurchaseKind.EXPLORE_PASS && qty > 1"]), (API+"ApiRegistrationController.java", ["@PostMapping(\"/create\")", "@PostMapping(\"/scan_dynamic_code\")", "@PostMapping(\"/scan_group_member_ticket\")"])],
}


def run(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def check_fixtures():
    f = json.loads((HERE / "fixtures.json").read_text())
    assert f["baseline"] == BASE
    facts = {}
    for row in f["facts"]:
        key = tuple(row[k] for k in ("source", "actorType", "actor", "scope", "instance", "version", "object", "type"))
        assert row["receipt"]
        if key in facts:
            assert facts[key] == row, "same business fact must preserve first receipt and payload"
        facts[key] = row
    predicates = {
        "arrival_current": lambda r: r["current"] and r["type"] == "ARRIVAL",
        "verified_current": lambda r: r["current"] and r["type"] == "TASK_VERIFIED",
        "nodes_current": lambda r: r["current"] and r["type"] == "NODE_COMPLETED",
        "game_normal": lambda r: r["source"] == "GAME" and r.get("mode") == "NORMAL",
        "game_fallback": lambda r: r["source"] == "GAME" and r.get("mode") == "FALLBACK",
        "theme_current": lambda r: r["current"] and r["type"] == "THEME_COMPLETED",
        "theme_history": lambda r: r["type"] == "THEME_COMPLETED",
        "team_endings": lambda r: r["actorType"] == "TEAM" and r["type"] == "ENDING_RESOLVED",
        "redeemed": lambda r: r["type"] == "ENTITLEMENT_REDEEMED",
        "full_node_reward_candidates": lambda r: r["type"] == "NODE_COMPLETED" and r.get("firstDefault") and r.get("reward") == "FULL",
        "capped_node_reward_candidates": lambda r: r["type"] == "NODE_COMPLETED" and r.get("firstDefault") and r.get("reward") == "XP_BADGE_ONLY",
    }
    for name, predicate in predicates.items():
        actual = {r["id"] for r in facts.values() if predicate(r)}
        expected = set(f["expected"][name])
        assert actual == expected, (name, "missing", expected - actual, "unexpected", actual - expected)
    normal = {r["actor"] for r in facts.values() if predicates["game_normal"](r)}
    fallback = {r["actor"] for r in facts.values() if predicates["game_fallback"](r)}
    assert normal == fallback == {4} and len(normal | fallback) == 1
    complete = {t["id"] for t in f["tickets"] if t["required"] and set(t["required"]) <= set(t["redeemed"])}
    assert complete == set(f["expected_complete_tickets"])
    cent = Decimal("0.01")
    s = f["money"]["sales_percent"]
    raw = {int(k): Decimal(v) * Decimal(s["rate_percent"]) / 100 for k, v in s["sales"].items()}
    amounts = {k: v.quantize(cent, rounding=ROUND_DOWN) for k, v in raw.items()}
    target = sum(raw.values()).quantize(cent, rounding=ROUND_HALF_UP)
    remainder_cents = int((target - sum(amounts.values())) / cent)
    for k in sorted(raw, key=lambda k: (-(raw[k] - amounts[k]), k))[:remainder_cents]:
        amounts[k] += cent
    assert target == Decimal(s["expected_header"]) == sum(amounts.values())
    assert amounts == {int(k): Decimal(v) for k, v in s["expected_lines"].items()}
    fixed = f["money"]["fixed"]
    assert len(set(fixed["registration_ids"])) * Decimal(fixed["fee"]) == Decimal(fixed["expected_header"])
    direct = f["money"]["direct"]
    gross = Decimal(direct["gross"])
    platform = (gross * Decimal(direct["platform_rate"])).quantize(cent, rounding=ROUND_HALF_UP)
    assert platform == Decimal(direct["expected_platform"])
    assert gross - platform == Decimal(direct["expected_merchant"])
    claims, replay, conflicts = {}, 0, 0
    for request in f["economic_claims"]:
        key, provenance = tuple(request["key"]), (request["route"], tuple(request["source"]))
        if key not in claims:
            claims[key] = provenance
        elif claims[key] == provenance:
            replay += 1
        else:
            conflicts += 1
    assert (len(claims), replay, conflicts) == (f["expected_claims"], f["expected_claim_replays"], f["expected_claim_conflicts"])
    print(f"PASS synthetic specification: {len(facts)} unique facts; {len(predicates)} exact expected sets; ticket isolation; overlapping person sets; 3 money cases; durable claim replay/conflict")


def collect_evidence():
    evidence = {}
    for eid, files in ANCHORS.items():
        evidence[eid] = []
        for relative, patterns in files:
            path = ROOT / relative
            lines = path.read_text().splitlines()
            matches = []
            for pattern in patterns:
                found = [{"line": n, "text": line.strip()} for n, line in enumerate(lines, 1) if pattern in line]
                assert found, (eid, relative, "missing anchor", pattern)
                matches.append({"anchor": pattern, "matches": found})
            evidence[eid].append({"path": relative, "revision": "candidate" if relative in EXCLUSIVE_FILES else BASE,
                                  "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "anchors": matches})
    (HERE / "source-evidence.json").write_text(json.dumps({"baseline": BASE, "evidence": evidence}, ensure_ascii=False, indent=2) + "\n")
    print(f"PASS static source anchors: {len(evidence)} evidence groups (not execution proof)")


def collect_callers():
    searches = [
        ("settlement production and tests", r"executeTopicSettlement\(|settleCoopTopic\(|snapshotReader\.read\(|coopSettlementClaimService\.(prepare|persist)|finalizeForSale\(|recordPaidRegistration\(|recordOnPayment\(|verifyAndSettle\(|appendExecutedAdjustment\(|settlementClaimService\.claim\(", ["chengyinhub-admin/src", "chengyinhub-system/src"]),
        ("completion writers/readers and tests", r"insertPlayerNodeProgress\(|markGameDone\(|markRedeemed\(|restoreToPending\(|invalidateByRegistration\(|isExploreCompleted\(|isCompletedCompletionFact\(|sessionStatuses\(|countCompletedParticipants\(|selectPlayerNodeOutcome\(|insertPlayerNodeOutcome\(|updateStoryStateCas\(|selectRouteLeaderboard\(|grantOnce\(|findCurrentCompletedRoutes\(|selectCurrentCompletedRoutes\(", ["chengyinhub-admin/src", "chengyinhub-system/src"]),
        ("fact tables and source separation", r"player_node_progress|topic_route_session|game_story_state|game_player_node_outcome|registration_entitlement|normalCompleters|fallbackCompleters", ["chengyinhub-system/src/main/resources/mapper/business"]),
        ("current frontend consumers", r"/api/play/(my-completed|checkin|arrive|leaderboard)|QUEST_RESOLVE|/gameplay/action", ["chengyinhub-xcx/pages", "chengyinhub-xcx/utils", "chengyinhub-ui/src"]),
    ]
    output = ["# G caller inventory; baseline " + BASE + " plus G candidate files", "# Static lexical search, includes definitions/tests/comments; reflection/runtime wiring not proved."]
    for title, pattern, paths in searches:
        args = ["rg", "-n", "--glob", "*.java", "--glob", "*.xml", "--glob", "*.js", "--glob", "*.vue", pattern, *paths]
        result = subprocess.run(args, cwd=ROOT, text=True, capture_output=True)
        assert result.returncode in (0, 1), result.stderr
        output += ["\n## " + title, "command arguments: " + json.dumps(args, ensure_ascii=False), result.stdout.rstrip()]
    (HERE / "caller-inventory.txt").write_text("\n".join(output) + "\n")
    print("PASS lexical caller inventory: 4 searches, full output saved; no network/database access")


def check_scope():
    head = run("git", "rev-parse", "HEAD")
    branch = run("git", "branch", "--show-current")
    assert branch == "codex/architecture-day-g-fact-contracts", (head, branch)
    assert head == BASE or run("git", "rev-parse", "HEAD^") == BASE, ("candidate must be a single commit from public baseline", head)
    changed = run("git", "diff", "--name-only", BASE).splitlines()
    untracked = run("git", "ls-files", "--others", "--exclude-standard").splitlines()
    assert all(path.startswith(PREFIX) or path in EXCLUSIVE_FILES for path in changed + untracked), changed + untracked
    for path in EXCLUSIVE_FILES:
        assert subprocess.run(["git", "cat-file", "-e", BASE + ":" + path], cwd=ROOT,
                              capture_output=True).returncode != 0, ("must only add new owned source files", path)
    subprocess.run(["git", "diff", "--check"], cwd=ROOT, check=True)
    subprocess.run(["git", "diff", "--cached", "--check"], cwd=ROOT, check=True)
    print(f"PASS isolation: HEAD={head}; branch={branch}; only G delivery directory and 7 explicitly owned new source files changed")
    print("PASS git diff --check and git diff --cached --check; all document source files also checked for trailing whitespace")
    for path in list(HERE.glob("*")) + [ROOT / p for p in EXCLUSIVE_FILES]:
        if path.suffix in (".md", ".py", ".json"):
            for n, line in enumerate(path.read_text().splitlines(), 1):
                assert line == line.rstrip(), (path.name, n, "trailing whitespace")


def check_candidate_sources():
    """仅静态形状/词法检查，不是 javac、JUnit 或数据库执行。"""
    xml = ET.parse(ROOT / MAPPER / "CompletedRouteFactMapper.xml").getroot()
    assert xml.attrib["namespace"] == "com.chengyinhub.business.mapper.CompletedRouteFactMapper"
    assert [child.tag for child in xml] == ["resultMap", "select"], "new mapper must stay read-only"
    cases = []
    for relative in sorted(EXCLUSIVE_FILES):
        if not relative.endswith(".java"):
            continue
        source = (ROOT / relative).read_text()
        # 删除 Java 注释及字面量后，仅检查括号平衡。不得称为 Java 编译通过。
        stripped = re.sub(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', '', source)
        stack = []
        for char in stripped:
            if char in "({[":
                stack.append(char)
            elif char in ")}]":
                assert stack and stack.pop() == dict(zip(")}]", "({["))[char], relative
        assert not stack, relative
        if relative.startswith(TEST):
            assert "@Disabled" not in source and "Assumptions." not in source, relative
            for method in re.findall(r'@Test\s+void\s+(\w+)\s*\(', source):
                cases.append({"file": relative, "method": method, "execution": "NOT_RUN",
                              "baseline_expected_failure_by_source_review": method ==
                              "completedGameNodeWithoutExploreTicketDoesNotBecomeACompletedExploreProduct"})
    assert len(cases) == 14, len(cases)
    (HERE / "java-execution-status.json").write_text(json.dumps({"baseline": BASE,
        "java_compiled": False, "java_tests_executed": 0, "mysql_tests_executed": 0,
        "cases": cases}, ensure_ascii=False, indent=2) + "\n")
    print("PASS static only: new mapper XML is select-only; Java delimiters balanced; 14 test methods inventoried without executing Java")
    print("EXPECTED_RED_BY_SOURCE_REVIEW_NOT_EXECUTION: completedGameNodeWithoutExploreTicketDoesNotBecomeACompletedExploreProduct (C controller fix pending)")


def manifest():
    hashes = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
              for p in sorted(HERE.iterdir()) if p.is_file() and p.name not in {"artifact-manifest.json", "validation-output.txt"}}
    hashes.update({p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in sorted(EXCLUSIVE_FILES)})
    identity = hashlib.sha256(json.dumps(hashes, sort_keys=True).encode()).hexdigest()
    (HERE / "artifact-manifest.json").write_text(json.dumps({"baseline": BASE, "candidate_sha256": identity,
        "exclusions": ["artifact-manifest.json (self)", "validation-output.txt (timestamped execution log)"],
        "files": hashes, "production_read_query_added": True, "funds_or_economic_eligibility_changed": False,
        "java_executed": False, "mysql_executed": False}, indent=2) + "\n")
    print("candidate_sha256=" + identity)


if __name__ == "__main__":
    print("UTC " + datetime.datetime.now(datetime.timezone.utc).isoformat())
    print("MODE: static evidence + synthetic contract specification; no production behavior claims")
    check_fixtures()
    collect_evidence()
    collect_callers()
    check_candidate_sources()
    check_scope()
    manifest()
    print("NOT_RUN: Maven/Java/MySQL/Redis/Spring transaction/approved VPS or CI/external payment or refund")
