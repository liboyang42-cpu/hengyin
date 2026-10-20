package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.PlayTeamMember;
import com.chengyinhub.business.domain.ThemeTeamRun;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.mapper.ThemeTeamRunMapper;
import com.chengyinhub.common.exception.ServiceException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Team-row lock precedes membership, group, route and game locks. Audit links never grant admission. */
@Component
public class ThemeTeamRunCoordinator {
    private final PlayTeamMapper teams;
    private final ThemeTeamRunMapper runs;
    public ThemeTeamRunCoordinator(PlayTeamMapper teams, ThemeTeamRunMapper runs) { this.teams=teams; this.runs=runs; }

    public static final class LockedTeam {
        private final PlayTeam team;
        private final List<PlayTeamMember> members;
        private final ThemeTeamRun current;
        private LockedTeam(PlayTeam team,List<PlayTeamMember> members,ThemeTeamRun current) {
            this.team=team;this.members=Collections.unmodifiableList(members);this.current=current;
        }
        public PlayTeam getTeam() { return team; }
        public List<PlayTeamMember> getMembers() { return members; }
        public ThemeTeamRun getCurrent() { return current; }
    }

    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public LockedTeam lock(Long actor,Long teamId,Long activityId,Long topicId,boolean leaderOnly) {
        validIds(actor,activityId,topicId);
        if(teamId==null || teamId<=0)throw new IllegalArgumentException("队伍参数有误");
        PlayTeam team=teams.selectPlayTeamByIdForUpdate(teamId);
        validateTeam(team,teamId,activityId);
        if(leaderOnly && !actor.equals(team.getLeaderMemberId()))throw new ServiceException("只有当前队长可以整队重开",403);
        List<PlayTeamMember> found=teams.selectActiveMembersForUpdate(teamId);
        if(found==null)throw new IllegalStateException("队伍成员读取失败");
        List<PlayTeamMember> members=new ArrayList<>(found);Set<Long> ids=new HashSet<>();
        for(PlayTeamMember member:members) {
            if(member==null || !teamId.equals(member.getTeamId()) || member.getMemberId()==null || member.getMemberId()<=0
                    || !Integer.valueOf(PlayTeamMember.STATUS_JOINED).equals(member.getStatus()) || !ids.add(member.getMemberId()))
                throw new IllegalStateException("队伍成员范围异常");
        }
        if(!ids.contains(actor))throw new ServiceException("你已不在该队伍中",403);
        members.sort(Comparator.comparing(PlayTeamMember::getMemberId));
        ThemeTeamRun current=runs.selectCurrentByTeamForUpdate(teamId,activityId,topicId);
        validateRun(current,teamId,activityId,topicId);
        return new LockedTeam(team,members,current);
    }

    /** Only a lock-order hint. The following locking read, not this ordinary read, proves currentness. */
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public ThemeTeamRun guardCurrent(Long actor,Long activityId,Long topicId) {
        ThemeTeamRun hint=runs.selectByCurrentMember(actor,activityId,topicId);
        if(hint==null)return null;
        SelectionPin pin=selectionPin(false);String key=actor+":"+activityId+":"+topicId;
        if(pin!=null && pin.selected.containsKey(key) && !java.util.Objects.equals(pin.selected.get(key),hint.getTeamId()))throw changed();
        // This is a personal-route barrier, not team-game admission. A departed member's own
        // still-current personal route is not reset or disabled when the remaining team replays.
        PlayTeam team=teams.selectPlayTeamByIdForUpdate(hint.getTeamId());
        if(team==null || !Integer.valueOf(2).equals(team.getOwnerType()) || !activityId.equals(team.getOwnerId()))throw new ServiceException("队伍本局范围异常",403);
        teams.selectActiveMembersForUpdate(team.getId());
        ThemeTeamRun locked=runs.selectByIdForUpdate(hint.getId());
        validateRun(locked,team.getId(),activityId,topicId);
        if(locked==null)throw changed();
        return locked;
    }

    /** Existing active-member admission is retained, including members who joined after a replay. */
    public Long inferTeam(Long actor,Long activityId,Long topicId) {
        validIds(actor,activityId,topicId);Long matched=null;List<PlayTeam> candidates=teams.selectMyTeams(actor);
        if(candidates==null)return null;
        for(PlayTeam team:candidates) {
            if(team==null || !Integer.valueOf(2).equals(team.getOwnerType()) || !activityId.equals(team.getOwnerId())
                    || team.getStatus()==null || team.getStatus()<0 || team.getStatus()>PlayTeam.STATUS_STARTED)continue;
            ThemeTeamRun run=runs.selectCurrentByTeam(team.getId(),activityId,topicId);
            if(run==null)continue;
            if(matched!=null && !matched.equals(team.getId()))throw new ServiceException("当前场次存在多个队伍，请明确选择队伍",409);
            matched=team.getId();
        }
        return matched;
    }

    public ThemeTeamRun read(Long actor,Long teamId,Long activityId,Long topicId) {
        validIds(actor,activityId,topicId);PlayTeam team=teams.selectPlayTeamById(teamId);validateTeam(team,teamId,activityId);
        if(teams.selectActiveMember(teamId,actor)==null)throw new ServiceException("你已不在该队伍中",403);
        ThemeTeamRun current=runs.selectCurrentByTeam(teamId,activityId,topicId);validateRun(current,teamId,activityId,topicId);return current;
    }

    /** FIRST has no group row. Infer only a currently active activity team, never an audit link. */
    public Long inferFirstTeam(Long actor,Long activityId,Long topicId) {
        validIds(actor,activityId,topicId);Long matched=null;List<PlayTeam> candidates=teams.selectMyTeams(actor);
        if(candidates==null)return null;
        for(PlayTeam team:candidates) {
            if(team==null || team.getId()==null || team.getId()<=0 || !Integer.valueOf(2).equals(team.getOwnerType())
                    || !activityId.equals(team.getOwnerId()) || team.getStatus()==null
                    || team.getStatus()<0 || team.getStatus()>PlayTeam.STATUS_STARTED)continue;
            PlayTeamMember member=teams.selectActiveMember(team.getId(),actor);
            if(member==null || !team.getId().equals(member.getTeamId()) || !actor.equals(member.getMemberId())
                    || !Integer.valueOf(PlayTeamMember.STATUS_JOINED).equals(member.getStatus()))continue;
            if(matched!=null && !matched.equals(team.getId()))throw new ServiceException("当前场次存在多个队伍，请明确选择队伍",409);
            matched=team.getId();
        }
        return matched;
    }

    /** A second opening-phase resolution cannot acquire a different team after the route lock. */
    public void guardSelection(Long actor,Long activityId,Long topicId,Long selected) {
        SelectionPin pin=selectionPin(true);if(pin==null)return;
        String key=actor+":"+activityId+":"+topicId;
        if(pin.selected.containsKey(key) && !java.util.Objects.equals(pin.selected.get(key),selected))throw changed();
        pin.selected.put(key,selected);
    }

    /** Route readers can take the FIRST team barrier without calling back into the route resolver. */
    public void guardFirstCurrent(Long actor,Long activityId,Long topicId) {
        SelectionPin pin=selectionPin(false);String key=actor+":"+activityId+":"+topicId;
        Long selected=pin==null?null:pin.selected.get(key);
        if(selected==null) {
            selected=inferTeam(actor,activityId,topicId);
            if(selected==null)selected=inferFirstTeam(actor,activityId,topicId);
        }
        // A previous null selection must be checked again, not silently treated as permission to join.
        guardSelection(actor,activityId,topicId,selected);
        if(selected!=null)lock(actor,selected,activityId,topicId,false);
    }

    private SelectionPin selectionPin(boolean create) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())return null;
        if(!TransactionSynchronizationManager.isSynchronizationActive())throw new IllegalStateException("队伍事务锁序不可确认");
        for(TransactionSynchronization sync:TransactionSynchronizationManager.getSynchronizations())
            if(sync instanceof SelectionPin && ((SelectionPin)sync).owner==this)return (SelectionPin)sync;
        if(!create)return null;
        SelectionPin pin=new SelectionPin(this);TransactionSynchronizationManager.registerSynchronization(pin);return pin;
    }

    /** Spring owns this transaction-local lifetime, including suspension/resumption and cleanup. */
    private static final class SelectionPin implements TransactionSynchronization {
        private final ThemeTeamRunCoordinator owner;
        private final Map<String,Long> selected=new HashMap<>();
        private SelectionPin(ThemeTeamRunCoordinator owner) { this.owner=owner; }
        @Override public void afterCompletion(int status) { selected.clear(); }
    }

    private void validateTeam(PlayTeam team,Long teamId,Long activityId) {
        if(team==null || !teamId.equals(team.getId()) || !Integer.valueOf(2).equals(team.getOwnerType())
                || !activityId.equals(team.getOwnerId()) || team.getStatus()==null || team.getStatus()<0 || team.getStatus()>PlayTeam.STATUS_STARTED)
            throw new ServiceException("队伍不可用于本场玩法",403);
    }
    private void validateRun(ThemeTeamRun run,Long teamId,Long activityId,Long topicId) {
        if(run!=null && (!teamId.equals(run.getTeamId()) || !activityId.equals(run.getActivityId()) || !topicId.equals(run.getTopicId())
                || run.getId()==null || run.getGeneration()==null || run.getGeneration()<1 || !instance(run.getId()).equals(run.getPlayInstanceId())))
            throw new IllegalStateException("队伍本局范围异常");
    }
    private void validIds(Long actor,Long activity,Long topic) {
        if(actor==null || actor<=0 || activity==null || activity<=0 || topic==null || topic<=0)throw new IllegalArgumentException("整队玩法仅支持有效活动场次");
    }
    public static String instance(Long id) { return "team-replay:"+id; }
    public static boolean isTeamInstance(String instance) { return instance!=null && instance.startsWith("team-replay:"); }
    private ServiceException changed() { return new ServiceException("队伍本局已更新，请刷新",409); }
}
