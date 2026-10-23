package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.mapper.OrientMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOrientTeamBoardService;
import com.chengyinhub.business.service.support.OrientRejection;
import com.chengyinhub.business.service.support.OrientSwitches;
import com.chengyinhub.business.service.support.OrientTeamProgressCalculator;
import com.chengyinhub.business.service.support.OrientTeamProgressCalculator.TaskCell;
import com.chengyinhub.business.service.support.OrientTeamProgressCalculator.TeamProgress;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OrientTeamBoardServiceImpl implements IOrientTeamBoardService {

    static final int LOBBY_LIMIT = 50;
    static final int BOARD_LIMIT = 200;

    @Autowired
    private OrientMapper orientMapper;
    @Autowired
    private PlayTeamMapper playTeamMapper;
    @Autowired
    private ICmsTopicService topicService;
    @Autowired
    private ICmsActivityService activityService;
    @Autowired
    private ICmsTopicNodeService topicNodeService;
    @Autowired
    private OrientSwitches switches;

    @Override
    public List<Map<String, Object>> lobby(Long viewerId, Integer ownerType, Long ownerId) {
        switches.require(switches.teamBoardOn());
        OrientOwnership.requireLogin(viewerId);
        resolveOwner(ownerType, ownerId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : orientMapper.selectLobbyTeams(ownerType, ownerId, LOBBY_LIMIT)) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("teamId", toLong(r.get("team_id")));
            v.put("title", r.get("title"));
            v.put("maxMembers", r.get("max_members"));
            v.put("joinedCount", r.get("joined_count"));
            v.put("status", r.get("status"));
            v.put("expireTime", toDate(r.get("expire_time")));
            v.put("leaderMemberId", toLong(r.get("leader_member_id")));
            v.put("leaderName", r.get("leader_name"));
            v.put("leaderAvatar", r.get("leader_avatar"));
            rows.add(v);
        }
        return rows;
    }

    @Override
    public Map<String, Object> detail(Long viewerId, Long teamId) {
        switches.require(switches.teamBoardOn());
        OrientOwnership.requireLogin(viewerId);
        PlayTeam team = teamId == null || teamId <= 0 ? null : playTeamMapper.selectPlayTeamById(teamId);
        if (team == null) throw new OrientRejection(OrientRejection.NOT_FOUND, "队伍不存在");
        if (playTeamMapper.selectActiveMember(teamId, viewerId) == null) {
            throw new OrientRejection(OrientRejection.TEAM_NOT_MEMBER, "只有队伍成员可以查看队伍进度");
        }
        Owner owner = resolveOwner(team.getOwnerType(), team.getOwnerId());
        List<CmsTopicNode> tasks = listedTasks(owner.topicId);
        List<Long> taskIds = new ArrayList<>();
        for (CmsTopicNode n : tasks) taskIds.add(n.getId());
        List<Map<String, Object>> members = orientMapper.selectActiveMembersOfTeams(Collections.singletonList(teamId));
        List<Long> memberIds = new ArrayList<>();
        for (Map<String, Object> m : members) memberIds.add(toLong(m.get("member_id")));
        double ratio = switches.teamCompletionRatio();
        TeamProgress progress = OrientTeamProgressCalculator.compute(teamId, memberIds, taskIds,
                facts(owner, memberIds), ratio);

        Map<String, Object> teamView = new LinkedHashMap<>();
        teamView.put("teamId", team.getId());
        teamView.put("title", team.getTitle());
        teamView.put("status", team.getStatus());
        teamView.put("maxMembers", team.getMaxMembers());
        teamView.put("leaderMemberId", team.getLeaderMemberId());
        teamView.put("ownerType", team.getOwnerType());
        teamView.put("ownerId", team.getOwnerId());

        Map<String, Object> threshold = new LinkedHashMap<>();
        threshold.put("ratio", ratio);
        threshold.put("activeCount", progress.activeCount);
        threshold.put("required", progress.required);

        Map<Long, TaskCell> cellByTask = new HashMap<>();
        for (TaskCell c : progress.tasks) cellByTask.put(c.taskId, c);
        List<Map<String, Object>> taskViews = new ArrayList<>();
        for (CmsTopicNode n : tasks) {
            TaskCell c = cellByTask.get(n.getId());
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("taskId", n.getId());
            t.put("name", n.getName());
            t.put("completedCount", c.completedCount);
            t.put("teamDone", c.teamDone);
            t.put("teamDoneTime", c.teamDoneTime);
            taskViews.add(t);
        }
        List<Map<String, Object>> memberViews = new ArrayList<>();
        for (Map<String, Object> m : members) {
            Map<String, Object> v = new LinkedHashMap<>();
            Long memberId = toLong(m.get("member_id"));
            v.put("memberId", memberId);
            v.put("name", m.get("member_name"));
            v.put("avatar", m.get("member_avatar"));
            v.put("role", m.get("role"));
            v.put("doneTaskIds", progress.memberDone.getOrDefault(memberId, Collections.emptyList()));
            memberViews.add(v);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("teamDoneCount", progress.teamDoneCount);
        summary.put("totalTasks", taskIds.size());
        summary.put("lastTeamDoneTime", progress.lastTeamDoneTime);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("team", teamView);
        data.put("threshold", threshold);
        data.put("tasks", taskViews);
        data.put("members", memberViews);
        data.put("summary", summary);
        return data;
    }

    @Override
    public List<Map<String, Object>> ranking(Long viewerId, Integer ownerType, Long ownerId) {
        switches.require(switches.teamBoardOn());
        OrientOwnership.requireLogin(viewerId);
        Owner owner = resolveOwner(ownerType, ownerId);
        List<PlayTeam> teams = orientMapper.selectBoardTeams(ownerType, ownerId, BOARD_LIMIT);
        if (teams.isEmpty()) return Collections.emptyList();
        List<Long> teamIds = new ArrayList<>();
        Map<Long, PlayTeam> teamById = new HashMap<>();
        for (PlayTeam t : teams) {
            teamIds.add(t.getId());
            teamById.put(t.getId(), t);
        }
        Map<Long, List<Long>> membersByTeam = new LinkedHashMap<>();
        List<Long> allMembers = new ArrayList<>();
        for (Map<String, Object> m : orientMapper.selectActiveMembersOfTeams(teamIds)) {
            Long tid = toLong(m.get("team_id"));
            Long mid = toLong(m.get("member_id"));
            membersByTeam.computeIfAbsent(tid, k -> new ArrayList<>()).add(mid);
            allMembers.add(mid);
        }
        List<Long> taskIds = new ArrayList<>();
        for (CmsTopicNode n : listedTasks(owner.topicId)) taskIds.add(n.getId());
        Map<Long, Map<Long, Date>> facts = facts(owner, allMembers);
        double ratio = switches.teamCompletionRatio();
        List<TeamProgress> progress = new ArrayList<>();
        for (Long tid : teamIds) {
            List<Long> mids = membersByTeam.getOrDefault(tid, Collections.emptyList());
            progress.add(OrientTeamProgressCalculator.compute(tid, mids, taskIds, facts, ratio));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int rank = 0;
        for (TeamProgress p : OrientTeamProgressCalculator.rank(progress)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", ++rank);
            row.put("teamId", p.teamId);
            row.put("title", teamById.get(p.teamId).getTitle());
            row.put("activeCount", p.activeCount);
            row.put("teamDoneCount", p.teamDoneCount);
            row.put("totalTasks", taskIds.size());
            row.put("lastTeamDoneTime", p.lastTeamDoneTime);
            row.put("mine", membersByTeam.getOrDefault(p.teamId, Collections.emptyList()).contains(viewerId));
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------------

    private static final class Owner {
        final long activityId;
        final long topicId;

        Owner(long activityId, long topicId) {
            this.activityId = activityId;
            this.topicId = topicId;
        }
    }

    /** ownerType 1=主题(自玩,activity_id=0)2=场次;只对城市定向主题开放。 */
    private Owner resolveOwner(Integer ownerType, Long ownerId) {
        if (ownerId == null || ownerId <= 0 || ownerType == null || (ownerType != 1 && ownerType != 2)) {
            throw new OrientRejection(OrientRejection.BAD_REQUEST, "参数有误");
        }
        long topicId = ownerId;
        long activityId = 0L;
        if (ownerType == 2) {
            CmsActivity activity = activityService.selectCmsActivityById(ownerId);
            if (activity == null || activity.getTopicId() == null) {
                throw new OrientRejection(OrientRejection.NOT_FOUND, "场次不存在");
            }
            topicId = activity.getTopicId();
            activityId = ownerId;
        }
        CmsTopic topic = OrientOwnership.requireTopic(topicService, topicId);
        OrientOwnership.requireOrienteering(topic);
        return new Owner(activityId, topicId);
    }

    /** 玩家可见、未下架的任务(节点),按既有节点列表顺序。 */
    private List<CmsTopicNode> listedTasks(long topicId) {
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topicId);
        List<CmsTopicNode> listed = new ArrayList<>();
        for (CmsTopicNode n : topicNodeService.selectCmsTopicNodeList(probe)) {
            if (n.getStatus() == null || Long.valueOf(1L).equals(n.getStatus())) listed.add(n);
        }
        return listed;
    }

    private Map<Long, Map<Long, Date>> facts(Owner owner, List<Long> memberIds) {
        Map<Long, Map<Long, Date>> facts = new HashMap<>();
        if (memberIds.isEmpty()) return facts;
        for (Map<String, Object> row : orientMapper.selectCompletionFacts(owner.activityId, owner.topicId, memberIds)) {
            Object t = row.get("done_time");
            facts.computeIfAbsent(toLong(row.get("member_id")), k -> new HashMap<>())
                    .put(toLong(row.get("task_id")), toDate(t));
        }
        return facts;
    }

    /** DATETIME 经 Connector/J 8 可能是 LocalDateTime,经 H2/旧驱动是 Timestamp;统一成 Date。 */
    static Date toDate(Object v) {
        if (v instanceof Date) return (Date) v;
        if (v instanceof LocalDateTime) return Timestamp.valueOf((LocalDateTime) v);
        return null;
    }

    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }
}
