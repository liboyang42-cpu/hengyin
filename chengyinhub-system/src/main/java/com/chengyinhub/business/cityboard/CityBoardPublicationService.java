package com.chengyinhub.business.cityboard;

import com.chengyinhub.business.cityboard.mapper.CityBoardMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 平台草稿、审核、冻结发布和玩家当前快照。
 * 不写积分、不建钱包、不把客户端 JSON 当成审批。
 */
@Service
public class CityBoardPublicationService {

    public static final String TRANSPORT = "NOT_APPROVED";
    public static final String CONTRACT = "CITY_PLAYER_READ_V1";
    private static final long MAX_SAFE = 9_007_199_254_740_991L;
    private static final Set<String> STAFF_READ = Set.of("DRAFT", "APPROVED", "REJECTED", "CANCELLED", "FROZEN");

    private final CityBoardMapper mapper;
    private final CityBoardSourceProbe probe;
    private final AtomicLong ids = new AtomicLong(1_000_000L);

    public CityBoardPublicationService(CityBoardMapper mapper, CityBoardSourceProbe probe) {
        this.mapper = mapper;
        this.probe = probe;
    }

    @Transactional
    public CityBoardOutcome save(long actorId, JsonNode body) {
        Command command = open("SAVE", actorId, body, Set.of("document", "draftId", "expectedVersion", "requestId"));
        if (command.early != null) {
            return command.early;
        }
        CityBoardDraftDocument document = CityBoardDraftDocument.parse(body.get("document"));
        if (!document.structural.isEmpty()) {
            return finish(command, fail(400, document.structural.get(0).code, "字段不在授权范围", violations(document.structural)));
        }
        Long draftId = optionalLong(body.get("draftId"));
        if (body.has("draftId") && draftId == null) {
            return finish(command, fail(400, "MISSING_PARAM", "缺少必要参数", null));
        }
        if (draftId == null) {
            if (command.expectedVersion != 0L) {
                return finish(command, fail(409, "VERSION_CONFLICT", "版本冲突", null));
            }
            CityBoardDraftRow created = newDraft(actorId, document, null);
            mapper.insertDraft(created);
            command.draftId = created.getId();
            return finish(command, CityBoardOutcome.ok(draftView(created)));
        }
        CityBoardDraftRow current = mapper.selectDraftById(draftId);
        if (current == null) {
            return finish(command, fail(404, "NOT_FOUND", "草稿不存在", null));
        }
        if (!current.getOwnerUserId().equals(actorId)) {
            return finish(command, fail(403, "NOT_OWNER", "不是草稿所有者", null));
        }
        if (!current.getVersion().equals(command.expectedVersion)) {
            return finish(command, conflict(current));
        }
        if (!"DRAFT".equals(current.getStatus()) && !"APPROVED".equals(current.getStatus())) {
            return finish(command, fail(409, "INVALID_STATE", "当前状态不能修改", state(current)));
        }
        if (document.contentHash.equals(current.getContentHash())) {
            command.draftId = current.getId();
            return finish(command, CityBoardOutcome.ok(draftView(current)));
        }
        current.setExpectedVersion(current.getVersion());
        current.setVersion(current.getVersion() + 1);
        current.setStatus("DRAFT");
        current.setRegionId(document.regionId());
        current.setContentJson(document.json);
        current.setContentHash(document.contentHash);
        current.setApprovalHash(null);
        current.setApproverUserId(null);
        current.setApprovalRequestId(null);
        current.setUpdateTime(new Date());
        if (mapper.updateDraft(current) != 1) {
            return finish(command, conflict(mapper.selectDraftById(draftId)));
        }
        command.draftId = current.getId();
        return finish(command, CityBoardOutcome.ok(draftView(current)));
    }

    @Transactional
    public CityBoardOutcome reopen(long actorId, JsonNode body) {
        Command command = open("REOPEN", actorId, body, Set.of("draftId", "expectedVersion", "releaseId", "requestId"));
        if (command.early != null) {
            return command.early;
        }
        boolean hasDraft = body.has("draftId");
        boolean hasRelease = body.has("releaseId");
        if (hasDraft == hasRelease) {
            return finish(command, fail(400, "MISSING_PARAM", "缺少必要参数", null));
        }
        if (hasDraft) {
            Long draftId = optionalLong(body.get("draftId"));
            if (draftId == null) {
                return finish(command, fail(400, "MISSING_PARAM", "缺少必要参数", null));
            }
            CityBoardDraftRow current = mapper.selectDraftById(draftId);
            if (current == null) {
                return finish(command, fail(404, "NOT_FOUND", "草稿不存在", null));
            }
            if (!current.getOwnerUserId().equals(actorId)) {
                return finish(command, fail(403, "NOT_OWNER", "不是草稿所有者", null));
            }
            if (!current.getVersion().equals(command.expectedVersion)) {
                return finish(command, conflict(current));
            }
            if (!"REJECTED".equals(current.getStatus()) && !"CANCELLED".equals(current.getStatus())) {
                return finish(command, fail(409, "INVALID_STATE", "当前状态不能重开", state(current)));
            }
            current.setExpectedVersion(current.getVersion());
            current.setVersion(current.getVersion() + 1);
            current.setStatus("DRAFT");
            current.setApprovalHash(null);
            current.setApproverUserId(null);
            current.setApprovalRequestId(null);
            current.setUpdateTime(new Date());
            if (mapper.updateDraft(current) != 1) {
                return finish(command, conflict(mapper.selectDraftById(draftId)));
            }
            command.draftId = current.getId();
            return finish(command, CityBoardOutcome.ok(draftView(current)));
        }
        Long releaseId = optionalLong(body.get("releaseId"));
        if (releaseId == null) {
            return finish(command, fail(400, "MISSING_PARAM", "缺少必要参数", null));
        }
        CityBoardReleaseRow release = mapper.selectReleaseById(releaseId);
        if (release == null) {
            return finish(command, fail(404, "NOT_FOUND", "发布不存在", null));
        }
        if (!release.getRevision().equals(command.expectedVersion)) {
            return finish(command, fail(409, "VERSION_CONFLICT", "版本冲突", releaseVersion(release)));
        }
        CityBoardDraftDocument document = documentOf(release);
        if (!document.structural.isEmpty()) {
            return finish(command, fail(409, "INVALID_STATE", "冻结内容不能重开", null));
        }
        CityBoardDraftRow created = newDraft(actorId, document, release.getId());
        mapper.insertDraft(created);
        command.draftId = created.getId();
        command.releaseId = release.getId();
        Map<String, Object> view = draftView(created);
        view.put("sourceRevision", release.getRevision());
        view.put("sourceContentHash", release.getContentHash());
        return finish(command, CityBoardOutcome.ok(view));
    }

    @Transactional
    public CityBoardOutcome cancel(long actorId, JsonNode body) {
        Command command = open("CANCEL", actorId, body, Set.of("draftId", "expectedVersion", "requestId"));
        if (command.early != null) {
            return command.early;
        }
        CityBoardDraftRow current = requiredDraft(command, body);
        if (current == null) {
            return command.early;
        }
        if (!current.getOwnerUserId().equals(actorId)) {
            return finish(command, fail(403, "NOT_OWNER", "不是草稿所有者", null));
        }
        if (!current.getVersion().equals(command.expectedVersion)) {
            return finish(command, conflict(current));
        }
        if (!"DRAFT".equals(current.getStatus()) && !"APPROVED".equals(current.getStatus())) {
            return finish(command, fail(409, "INVALID_STATE", "当前状态不能取消", state(current)));
        }
        current.setExpectedVersion(current.getVersion());
        current.setVersion(current.getVersion() + 1);
        current.setStatus("CANCELLED");
        current.setApprovalHash(null);
        current.setApproverUserId(null);
        current.setApprovalRequestId(null);
        current.setUpdateTime(new Date());
        if (mapper.updateDraft(current) != 1) {
            return finish(command, conflict(mapper.selectDraftById(current.getId())));
        }
        command.draftId = current.getId();
        return finish(command, CityBoardOutcome.ok(draftView(current)));
    }

    @Transactional
    public CityBoardOutcome validate(long actorId, JsonNode body) {
        return review(actorId, body, true);
    }

    @Transactional
    public CityBoardOutcome reject(long actorId, JsonNode body) {
        return review(actorId, body, false);
    }

    @Transactional
    public CityBoardOutcome publish(long actorId, JsonNode body) {
        Command command = open("PUBLISH", actorId, body, Set.of("draftId", "expectedVersion", "requestId"));
        if (command.early != null) {
            return command.early;
        }
        CityBoardDraftRow current = requiredDraft(command, body);
        if (current == null) {
            return command.early;
        }
        if (!current.getVersion().equals(command.expectedVersion)) {
            return finish(command, conflict(current));
        }
        if (!"APPROVED".equals(current.getStatus()) || current.getApprovalHash() == null || current.getApproverUserId() == null) {
            return finish(command, fail(409, "INVALID_STATE", "草稿还没有冻结所需的审批", state(current)));
        }
        String expectedApproval = CityBoardHashes.approval(current.getContentHash(), current.getId(), current.getVersion(),
                current.getApproverUserId(), current.getApprovalRequestId());
        if (!expectedApproval.equals(current.getApprovalHash())) {
            return finish(command, fail(409, "APPROVAL_MISMATCH", "审批哈希与内容哈希不一致", state(current)));
        }
        CityBoardDraftDocument document = CityBoardDraftDocument.parse(read(current.getContentJson()));
        List<CityBoardDraftDocument.Violation> blocked = document.publicationViolations(probe);
        if (!blocked.isEmpty()) {
            return finish(command, fail(400, "PUBLICATION_BLOCKED", "发布校验未通过", violations(blocked)));
        }
        current.setExpectedVersion(current.getVersion());
        current.setStatus("FROZEN");
        current.setUpdateTime(new Date());
        // ponytail: freeze CAS then insert; a crash between them needs the DB transaction, no second lock
        if (mapper.updateDraft(current) != 1) {
            return finish(command, conflict(mapper.selectDraftById(current.getId())));
        }
        Long max = mapper.selectMaxRevision(current.getRegionId());
        long revision = max == null ? 1L : max + 1L;
        CityBoardReleaseRow release = new CityBoardReleaseRow();
        release.setId(ids.incrementAndGet());
        release.setRegionId(document.regionId());
        release.setDraftId(current.getId());
        release.setRevision(revision);
        release.setRulesReleaseId("rel" + Long.toUnsignedString(release.getId(), 36));
        release.setGameId(document.gameId());
        release.setBoardId(document.boardId());
        release.setSeasonId(document.seasonId());
        release.setTitle(document.title());
        release.setLifecycle("FROZEN");
        release.setContentHash(document.contentHash);
        release.setApprovalHash(current.getApprovalHash());
        release.setApproverUserId(current.getApproverUserId());
        release.setSnapshotJson(snapshot(document, release));
        release.setCurrentFlag(1);
        release.setPublisherUserId(actorId);
        release.setCreateTime(new Date());
        mapper.clearCurrent(release.getRegionId());
        mapper.insertRelease(release);
        command.draftId = current.getId();
        command.releaseId = release.getId();
        return finish(command, CityBoardOutcome.ok(releaseView(release, current)));
    }

    public CityBoardOutcome readDraft(long actorId, long draftId, boolean staff) {
        CityBoardDraftRow current = mapper.selectDraftById(draftId);
        if (current == null || !STAFF_READ.contains(current.getStatus())) {
            return fail(404, "NOT_FOUND", "草稿不存在", null);
        }
        if (!current.getOwnerUserId().equals(actorId) && !staff) {
            return fail(403, "NOT_OWNER", "不是草稿所有者", null);
        }
        return CityBoardOutcome.ok(draftView(current));
    }

    public CityBoardOutcome readOperation(String requestId) {
        if (!CityBoardDraftDocument.isId(requestId)) {
            return fail(400, "INVALID_ID", "请求标识不合法", null);
        }
        CityBoardOperationRow row = mapper.selectOperation(requestId);
        if (row == null) {
            return fail(404, "NOT_FOUND", "操作不存在", null);
        }
        return restore(row);
    }

    public CityBoardOutcome playerCurrent(long memberId, Map<String, String> query) {
        if (memberId <= 0) {
            return fail(401, "UNAUTHORIZED", "请先登录", null);
        }
        if (query == null || !query.keySet().equals(Set.of("regionId"))) {
            return fail(400, "MISSING_PARAM", "缺少必要参数", null);
        }
        String regionId = query.get("regionId");
        if (!CityBoardDraftDocument.isId(regionId)) {
            return fail(400, "INVALID_ID", "区域标识不合法", null);
        }
        CityBoardReleaseRow release = mapper.selectCurrentRelease(regionId);
        Map<String, Object> data = playerEnvelope(regionId);
        if (release == null) {
            data.put("status", "NO_CURRENT_BOARD");
            return CityBoardOutcome.ok(data);
        }
        if (!matrixAllowsRead(release)) {
            return fail(403, "PERMISSION_MATRIX", "发布权限矩阵不允许玩家读取", null);
        }
        data.remove("regionId");
        data.put("status", "AVAILABLE");
        data.put("board", board(release));
        data.put("participationStatus", "UNAVAILABLE");
        data.put("pointsStatus", "UNAVAILABLE");
        return CityBoardOutcome.ok(data);
    }

    public CityBoardOutcome playerParticipation(long memberId, Map<String, String> query) {
        return playerProjection(memberId, query, "CITY_PARTICIPATION_UNAVAILABLE", "参与真源不可用");
    }

    public CityBoardOutcome playerPoints(long memberId, Map<String, String> query) {
        return playerProjection(memberId, query, "CITY_POINTS_UNAVAILABLE", "点位真源不可用");
    }

    private CityBoardOutcome review(long actorId, JsonNode body, boolean approve) {
        Set<String> keys = approve
                ? Set.of("draftId", "expectedVersion", "requestId")
                : Set.of("draftId", "expectedVersion", "reason", "requestId");
        Command command = open(approve ? "VALIDATE" : "REJECT", actorId, body, keys);
        if (command.early != null) {
            return command.early;
        }
        CityBoardDraftRow current = requiredDraft(command, body);
        if (current == null) {
            return command.early;
        }
        if (!current.getVersion().equals(command.expectedVersion)) {
            return finish(command, conflict(current));
        }
        if (current.getOwnerUserId().equals(actorId)) {
            return finish(command, fail(403, "SELF_APPROVAL", "不能审批自己的草稿", null));
        }
        if ("FROZEN".equals(current.getStatus()) || "CANCELLED".equals(current.getStatus()) || "REJECTED".equals(current.getStatus())) {
            return finish(command, fail(409, "INVALID_STATE", "当前状态不能审核", state(current)));
        }
        if (!approve) {
            JsonNode reasonNode = body.get("reason");
            if (reasonNode == null || !reasonNode.isTextual() || reasonNode.asText().trim().isEmpty()
                    || reasonNode.asText().length() > 160) {
                return finish(command, fail(400, "MISSING_PARAM", "缺少驳回原因", null));
            }
            current.setExpectedVersion(current.getVersion());
            current.setVersion(current.getVersion() + 1);
            current.setStatus("REJECTED");
            current.setApprovalHash(null);
            current.setApproverUserId(null);
            current.setApprovalRequestId(null);
            current.setUpdateTime(new Date());
            if (mapper.updateDraft(current) != 1) {
                return finish(command, conflict(mapper.selectDraftById(current.getId())));
            }
            command.draftId = current.getId();
            Map<String, Object> view = draftView(current);
            view.put("reason", reasonNode.asText());
            return finish(command, CityBoardOutcome.ok(view));
        }
        if (!"DRAFT".equals(current.getStatus())) {
            return finish(command, fail(409, "INVALID_STATE", "当前状态不能审核", state(current)));
        }
        CityBoardDraftDocument document = CityBoardDraftDocument.parse(read(current.getContentJson()));
        List<CityBoardDraftDocument.Violation> blocked = document.publicationViolations(probe);
        if (!blocked.isEmpty()) {
            command.draftId = current.getId();
            return finish(command, fail(400, "PUBLICATION_BLOCKED", "发布校验未通过", violations(blocked)));
        }
        current.setExpectedVersion(current.getVersion());
        current.setStatus("APPROVED");
        current.setApproverUserId(actorId);
        current.setApprovalRequestId(command.requestId);
        current.setApprovalHash(CityBoardHashes.approval(current.getContentHash(), current.getId(), current.getVersion(),
                actorId, command.requestId));
        current.setUpdateTime(new Date());
        if (mapper.updateDraft(current) != 1) {
            return finish(command, conflict(mapper.selectDraftById(current.getId())));
        }
        command.draftId = current.getId();
        return finish(command, CityBoardOutcome.ok(draftView(current)));
    }

    private CityBoardOutcome playerProjection(long memberId, Map<String, String> query, String errorCode, String message) {
        if (memberId <= 0) {
            return fail(401, "UNAUTHORIZED", "请先登录", null);
        }
        Set<String> keys = Set.of("boardId", "regionId", "revision", "rulesReleaseId", "seasonId");
        if (query == null || !query.keySet().equals(keys)) {
            return fail(400, "MISSING_PARAM", "缺少必要参数", null);
        }
        if (!CityBoardDraftDocument.isId(query.get("regionId")) || !CityBoardDraftDocument.isId(query.get("boardId"))
                || !CityBoardDraftDocument.isId(query.get("seasonId"))
                || !CityBoardDraftDocument.isId(query.get("rulesReleaseId"))) {
            return fail(400, "INVALID_ID", "快照标识不合法", null);
        }
        Long revision = revision(query.get("revision"));
        if (revision == null) {
            return fail(400, "MISSING_PARAM", "缺少必要参数", null);
        }
        CityBoardReleaseRow release = mapper.selectCurrentRelease(query.get("regionId"));
        if (release == null || !sameSnapshot(release, query, revision)) {
            return fail(409, "CITY_SNAPSHOT_CHANGED", "快照已变化", release == null ? null : Map.of("board", board(release)));
        }
        if (!matrixAllowsRead(release)) {
            return fail(403, "PERMISSION_MATRIX", "发布权限矩阵不允许玩家读取", null);
        }
        return fail(503, errorCode, message, null);
    }

    private static boolean sameSnapshot(CityBoardReleaseRow release, Map<String, String> query, long revision) {
        return release.getRegionId().equals(query.get("regionId"))
                && release.getBoardId().equals(query.get("boardId"))
                && release.getSeasonId().equals(query.get("seasonId"))
                && release.getRulesReleaseId().equals(query.get("rulesReleaseId"))
                && release.getRevision().equals(revision);
    }

    private boolean matrixAllowsRead(CityBoardReleaseRow release) {
        CityBoardDraftDocument document = documentOf(release);
        if (!document.structural.isEmpty() || document.canonical == null) {
            return false;
        }
        String scope = "region:" + release.getRegionId();
        JsonNode matrix = document.canonical.get("permissionMatrix");
        if (matrix == null || !matrix.isArray()) {
            return false;
        }
        for (JsonNode entry : matrix) {
            if (CityBoardPermissions.PLAYER_READ.equals(entry.path("permission").asText())
                    && scope.equals(entry.path("scope").asText())) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> playerEnvelope(String regionId) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("contract", CONTRACT);
        data.put("scope", "CITY");
        data.put("regionId", regionId);
        stamp(data);
        return data;
    }

    private static Map<String, Object> board(CityBoardReleaseRow release) {
        Map<String, Object> board = new LinkedHashMap<String, Object>();
        board.put("gameId", release.getGameId());
        board.put("boardId", release.getBoardId());
        board.put("regionId", release.getRegionId());
        board.put("seasonId", release.getSeasonId());
        board.put("rulesReleaseId", release.getRulesReleaseId());
        board.put("rulesHash", release.getContentHash());
        board.put("lifecycle", "FROZEN");
        board.put("title", release.getTitle());
        board.put("revision", release.getRevision());
        return board;
    }

    private Command open(String action, long actorId, JsonNode body, Set<String> allowed) {
        Command command = new Command();
        command.action = action;
        command.actorId = actorId;
        if (body == null || !body.isObject()) {
            command.early = fail(400, "MISSING_PARAM", "缺少必要参数", null);
            return command;
        }
        if (body.has("approvalHash") || body.has("approved") || body.has("decision")) {
            command.early = fail(400, "CLIENT_APPROVAL_REJECTED", "客户端不能提交审批", null);
            remember(command, body);
            return command.requestId == null ? command : finishEarly(command);
        }
        if (body.has("owner") || body.has("role") || body.has("contentHash") || body.has("rulesReleaseId")) {
            command.early = fail(400, "PROTECTED_FIELD", "该字段受保护", null);
            remember(command, body);
            return command.requestId == null ? command : finishEarly(command);
        }
        List<String> unknown = new ArrayList<String>();
        Iterator<String> names = body.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            command.early = fail(400, "UNKNOWN_FIELD", "字段不在授权范围", Map.of("fields", unknown));
            remember(command, body);
            return command.requestId == null ? command : finishEarly(command);
        }
        remember(command, body);
        if (command.requestId == null) {
            command.early = fail(400, "MISSING_PARAM", "缺少必要参数", null);
            return command;
        }
        command.requestHash = CityBoardHashes.sha256(CityBoardDraftDocument.canonicalTree(body));
        CityBoardOperationRow existing = mapper.selectOperation(command.requestId);
        if (existing != null) {
            command.early = existing.getRequestHash().equals(command.requestHash)
                    ? restore(existing) : duplicate(existing);
            return command;
        }
        JsonNode versionNode = body.get("expectedVersion");
        Long version = optionalLong(versionNode);
        if (version == null || version < 0 || version > MAX_SAFE) {
            command.early = finish(command, fail(400, "MISSING_PARAM", "缺少必要参数", null));
            return command;
        }
        command.expectedVersion = version;
        return command;
    }

    private Command finishEarly(Command command) {
        if (command.requestHash == null && command.requestId != null && command.body != null) {
            command.requestHash = CityBoardHashes.sha256(CityBoardDraftDocument.canonicalTree(command.body));
        }
        CityBoardOperationRow existing = command.requestId == null ? null : mapper.selectOperation(command.requestId);
        if (existing != null) {
            command.early = existing.getRequestHash().equals(command.requestHash) ? restore(existing) : duplicate(existing);
            return command;
        }
        finish(command, command.early);
        return command;
    }

    private void remember(Command command, JsonNode body) {
        command.body = body;
        JsonNode request = body.get("requestId");
        if (request != null && request.isTextual() && CityBoardDraftDocument.isId(request.asText())) {
            command.requestId = request.asText();
        }
    }

    private CityBoardOutcome finish(Command command, CityBoardOutcome outcome) {
        if (command.requestId == null || command.requestHash == null) {
            return outcome;
        }
        CityBoardOperationRow row = new CityBoardOperationRow();
        row.setId(ids.incrementAndGet());
        row.setRequestId(command.requestId);
        row.setAction(command.action);
        row.setActorUserId(command.actorId);
        row.setRequestHash(command.requestHash);
        row.setHttpStatus(outcome.httpStatus);
        row.setResultJson(write(stored(outcome)));
        row.setDraftId(command.draftId);
        row.setReleaseId(command.releaseId);
        row.setCreateTime(new Date());
        if (mapper.insertOperation(row) != 1) {
            CityBoardOperationRow existing = mapper.selectOperation(command.requestId);
            if (existing != null && existing.getRequestHash().equals(command.requestHash)) {
                return restore(existing);
            }
            return existing == null ? outcome : duplicate(existing);
        }
        return outcome;
    }

    private CityBoardDraftRow requiredDraft(Command command, JsonNode body) {
        Long draftId = optionalLong(body.get("draftId"));
        if (draftId == null) {
            command.early = finish(command, fail(400, "MISSING_PARAM", "缺少必要参数", null));
            return null;
        }
        CityBoardDraftRow current = mapper.selectDraftById(draftId);
        if (current == null) {
            command.early = finish(command, fail(404, "NOT_FOUND", "草稿不存在", null));
            return null;
        }
        command.draftId = current.getId();
        return current;
    }

    private CityBoardDraftRow newDraft(long actorId, CityBoardDraftDocument document, Long parentReleaseId) {
        Date now = new Date();
        CityBoardDraftRow row = new CityBoardDraftRow();
        row.setId(ids.incrementAndGet());
        row.setOwnerUserId(actorId);
        row.setRegionId(document.regionId());
        row.setVersion(1L);
        row.setStatus("DRAFT");
        row.setContentJson(document.json);
        row.setContentHash(document.contentHash);
        row.setParentReleaseId(parentReleaseId);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        return row;
    }

    private CityBoardDraftDocument documentOf(CityBoardReleaseRow release) {
        JsonNode snapshot = read(release.getSnapshotJson());
        return CityBoardDraftDocument.parse(snapshot.get("document"));
    }

    private String snapshot(CityBoardDraftDocument document, CityBoardReleaseRow release) {
        com.fasterxml.jackson.databind.node.ObjectNode root = CityBoardDraftDocument.mapper().createObjectNode();
        root.set("document", read(document.json));
        com.fasterxml.jackson.databind.node.ObjectNode meta = CityBoardDraftDocument.mapper().createObjectNode();
        meta.put("approvalHash", release.getApprovalHash());
        meta.put("contentHash", release.getContentHash());
        meta.put("lifecycle", "FROZEN");
        meta.put("revision", release.getRevision());
        meta.put("rulesReleaseId", release.getRulesReleaseId());
        root.set("release", meta);
        return write(root);
    }

    private Map<String, Object> draftView(CityBoardDraftRow row) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("draftId", row.getId());
        view.put("ownerUserId", row.getOwnerUserId());
        view.put("regionId", row.getRegionId());
        view.put("version", row.getVersion());
        view.put("status", row.getStatus());
        view.put("contentHash", row.getContentHash());
        view.put("approvalHash", row.getApprovalHash());
        view.put("approverUserId", row.getApproverUserId());
        view.put("parentReleaseId", row.getParentReleaseId());
        view.put("document", convert(read(row.getContentJson())));
        stamp(view);
        return view;
    }

    private Map<String, Object> releaseView(CityBoardReleaseRow release, CityBoardDraftRow draft) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("action", "PUBLISH");
        view.put("draftId", draft.getId());
        view.put("releaseId", release.getId());
        view.put("regionId", release.getRegionId());
        view.put("version", draft.getVersion());
        view.put("revision", release.getRevision());
        view.put("rulesReleaseId", release.getRulesReleaseId());
        view.put("contentHash", release.getContentHash());
        view.put("approvalHash", release.getApprovalHash());
        view.put("status", "FROZEN");
        view.put("board", board(release));
        stamp(view);
        return view;
    }

    private static void stamp(Map<String, Object> view) {
        view.put("candidate", Boolean.TRUE);
        view.put("transport", TRANSPORT);
    }

    private static CityBoardOutcome conflict(CityBoardDraftRow current) {
        return fail(409, "VERSION_CONFLICT", "版本冲突", current == null ? null : state(current));
    }

    private static Map<String, Object> state(CityBoardDraftRow current) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("draftId", current.getId());
        data.put("version", current.getVersion());
        data.put("status", current.getStatus());
        data.put("contentHash", current.getContentHash());
        return data;
    }

    private static Map<String, Object> releaseVersion(CityBoardReleaseRow release) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("releaseId", release.getId());
        data.put("revision", release.getRevision());
        data.put("contentHash", release.getContentHash());
        return data;
    }

    private static Map<String, Object> violations(List<CityBoardDraftDocument.Violation> items) {
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
        for (CityBoardDraftDocument.Violation item : items) {
            Map<String, String> row = new LinkedHashMap<String, String>();
            row.put("code", item.code);
            row.put("path", item.path);
            row.put("fieldClass", item.fieldClass);
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("violations", rows);
        return data;
    }

    private static CityBoardOutcome duplicate(CityBoardOperationRow existing) {
        CityBoardOutcome original = restore(existing);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("requestId", existing.getRequestId());
        data.put("original", original.data);
        data.put("originalStatus", original.httpStatus);
        data.put("originalErrorCode", original.errorCode);
        return fail(409, "DUPLICATE_REQUEST", "重复请求", data);
    }

    private static CityBoardOutcome restore(CityBoardOperationRow row) {
        JsonNode stored = read(row.getResultJson());
        int status = stored.path("httpStatus").asInt();
        String errorCode = stored.get("errorCode") == null || stored.get("errorCode").isNull()
                ? null : stored.get("errorCode").asText();
        String message = stored.path("message").asText("操作成功");
        JsonNode dataNode = stored.get("data");
        Map<String, Object> data = dataNode == null || dataNode.isNull() ? null : convert(dataNode);
        if (status == 200) {
            return CityBoardOutcome.ok(data);
        }
        return fail(status, errorCode, message, data);
    }

    private static Map<String, Object> stored(CityBoardOutcome outcome) {
        Map<String, Object> stored = new LinkedHashMap<String, Object>();
        stored.put("httpStatus", outcome.httpStatus);
        stored.put("errorCode", outcome.errorCode);
        stored.put("message", outcome.message);
        stored.put("data", outcome.data);
        return stored;
    }

    private static Map<String, Object> convert(JsonNode node) {
        return CityBoardDraftDocument.mapper().convertValue(node, new TypeReference<Map<String, Object>>() {
        });
    }

    private static JsonNode read(String json) {
        try {
            return CityBoardDraftDocument.mapper().readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("stored json");
        }
    }

    private static String write(Object value) {
        try {
            return CityBoardDraftDocument.mapper().writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("stored json");
        }
    }

    private static Long optionalLong(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            return null;
        }
        long value = node.longValue();
        if (value < 0 || value > MAX_SAFE) {
            return null;
        }
        return value;
    }

    private static Long revision(String raw) {
        if (raw == null || !raw.matches("\\A[0-9]{1,16}\\z")) {
            return null;
        }
        long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
        if (!Long.toString(value).equals(raw) || value > MAX_SAFE) {
            return null;
        }
        return value;
    }

    private static CityBoardOutcome fail(int status, String code, String message, Map<String, Object> data) {
        return CityBoardOutcome.fail(status, code, message, data);
    }

    private static final class Command {
        private String action;
        private long actorId;
        private String requestId;
        private String requestHash;
        private long expectedVersion;
        private Long draftId;
        private Long releaseId;
        private JsonNode body;
        private CityBoardOutcome early;
    }
}
