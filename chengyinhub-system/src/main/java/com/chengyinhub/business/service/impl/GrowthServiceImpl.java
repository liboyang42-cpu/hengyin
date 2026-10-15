package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.GrowthMission;
import com.chengyinhub.business.domain.GrowthMissionProgress;
import com.chengyinhub.business.domain.MemberGrowth;
import com.chengyinhub.business.domain.dto.GrowthMissionCompleteDTO;
import com.chengyinhub.business.domain.dto.LeaderboardQueryDTO;
import com.chengyinhub.business.domain.vo.LeaderboardItemVO;
import com.chengyinhub.business.domain.vo.MemberGrowthCenterVO;
import com.chengyinhub.business.domain.vo.MemberLeaderboardVO;
import com.chengyinhub.business.mapper.GrowthMissionMapper;
import com.chengyinhub.business.mapper.GrowthMissionProgressMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.MemberGrowthMapper;
import com.chengyinhub.business.mapper.MemberLeaderboardMapper;
import com.chengyinhub.business.mapper.StoreVerifyGrowthDeliveryMapper;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * P3 玩家成长服务实现。
 */
@Service
public class GrowthServiceImpl implements IGrowthService {

    @Autowired
    private MemberGrowthMapper memberGrowthMapper;

    @Autowired
    private GrowthMissionMapper growthMissionMapper;

    @Autowired
    private GrowthMissionProgressMapper growthMissionProgressMapper;

    @Autowired
    private PlayerBadgeMapper playerBadgeMapper;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private MemberLeaderboardMapper memberLeaderboardMapper;

    @Autowired
    private StoreVerifyGrowthDeliveryMapper storeVerifyGrowthDeliveryMapper;

    @Override
    public MemberGrowthCenterVO getCenter(Long memberId) {
        if (memberId == null) {
            throw new IllegalArgumentException("memberId required");
        }
        MemberGrowth growth = memberGrowthMapper.selectByMemberId(memberId);
        if (growth == null) {
            // 只读：查不到不建号，返回内存里的默认账户。建号只发生在用户首次真正发奖时（见 ensureAccount），
            // 避免任何被动展示成长信息的页面（如「我的权益」）顺手给全体用户建出空账户。
            growth = new MemberGrowth();
            growth.setMemberId(memberId);
            growth.setLevelNo(1);
            growth.setExpValue(0);
        }
        MemberGrowthCenterVO vo = new MemberGrowthCenterVO();
        vo.setGrowth(growth);
        // 积分走统一账本 UmsMember.point（与资产中心同池），成长账户只保留经验/等级。
        UmsMember member = umsMemberService.selectUmsMemberById(memberId);
        vo.setPoints(member != null && member.getPoint() != null ? member.getPoint() : 0L);
        Map<String, Object> pointRank = memberLeaderboardMapper.selectPointTotalMyRank(memberId);
        vo.setRank(rankOrNull(pointRank));
        vo.setMissions(nullToEmpty(growthMissionMapper.selectActiveMissionList()));
        vo.setProgress(nullToEmpty(growthMissionProgressMapper.selectMemberProgress(memberId)));
        vo.setBadges(nullToEmpty(playerBadgeMapper.selectListByMember(memberId)));
        return vo;
    }

    @Override
    public MemberLeaderboardVO getLeaderboard(Long memberId, LeaderboardQueryDTO query) {
        if (memberId == null) {
            throw new IllegalArgumentException("memberId required");
        }
        String metric = "exp".equalsIgnoreCase(query == null ? null : query.getMetric()) ? "exp" : "point";
        String period = "week".equalsIgnoreCase(query == null ? null : query.getPeriod()) ? "week" : "total";
        int limit = clampLimit(query == null ? null : query.getLimit());
        Date weekStart = "week".equals(period) ? currentWeekStart() : null;
        Date weekEnd = weekStart == null ? null
                : Date.from(weekStart.toInstant().atZone(ZoneId.systemDefault()).plusWeeks(1).toInstant());

        boolean exp = "exp".equals(metric);
        boolean week = "week".equals(period);

        List<LeaderboardItemVO> list;
        Map<String, Object> myRank;
        if (exp && week) {
            list = memberLeaderboardMapper.selectExpWeekTop(weekStart, weekEnd, limit);
            myRank = memberLeaderboardMapper.selectExpWeekMyRank(memberId, weekStart, weekEnd);
        } else if (exp) {
            list = memberLeaderboardMapper.selectExpTotalTop(limit);
            myRank = memberLeaderboardMapper.selectExpTotalMyRank(memberId);
        } else if (week) {
            list = memberLeaderboardMapper.selectPointWeekTop(weekStart, limit);
            myRank = memberLeaderboardMapper.selectPointWeekMyRank(memberId, weekStart);
        } else {
            list = memberLeaderboardMapper.selectPointTotalTop(limit);
            myRank = memberLeaderboardMapper.selectPointTotalMyRank(memberId);
        }

        list = nullToEmpty(list);
        // 同分按 SQL 的稳定次序顺排(row_number)，我的名次查询使用同一 tie-break。
        for (int i = 0; i < list.size(); i++) {
            list.get(i).setRank(i + 1);
        }

        MemberLeaderboardVO vo = new MemberLeaderboardVO();
        vo.setMetric(metric);
        vo.setPeriod(period);
        vo.setList(list);
        vo.setMe(buildMe(memberId, myRank));
        return vo;
    }

    private LeaderboardItemVO buildMe(Long memberId, Map<String, Object> myRank) {
        LeaderboardItemVO me = new LeaderboardItemVO();
        me.setMemberId(memberId);
        UmsMember member = umsMemberService.selectUmsMemberById(memberId);
        if (member != null) {
            me.setNickname(member.getNickname());
            me.setAvatar(member.getAvatar());
        }
        long score = toLong(myRank == null ? null : myRank.get("score"));
        Integer rank = rankOrNull(myRank);
        int total = (int) toLong(myRank == null ? null : myRank.get("totalUsers"));
        me.setScore(score);
        me.setRank(rank);
        me.setRankPercentage(rankBucket(rank, total));
        return me;
    }

    private Integer rankOrNull(Map<String, Object> myRank) {
        if (myRank == null || toLong(myRank.get("totalUsers")) <= 0) return null;
        return toInteger(myRank.get("userRank"));
    }

    /** 复用现有「TOP X%」分桶口径(见 UmsMemberMapper.selectUserRankInfo)。 */
    private String rankBucket(Integer rank, int total) {
        if (rank == null || total <= 0) {
            return "TOP100%";
        }
        double pct = (double) rank / total;
        if (pct <= 0.2) return "TOP20%";
        if (pct <= 0.4) return "TOP40%";
        if (pct <= 0.6) return "TOP60%";
        if (pct <= 0.8) return "TOP80%";
        return "TOP100%";
    }

    private int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 50;
        }
        return Math.min(limit, 100);
    }

    /** 本周一 00:00(ISO 周,周一为首日)。 */
    private Date currentWeekStart() {
        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
        return Date.from(monday.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private long toLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private Integer toInteger(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    @Override
    @Transactional
    public int completeMission(Long memberId, GrowthMissionCompleteDTO request) {
        if (memberId == null || request == null || StringUtils.isBlank(request.getMissionCode())) {
            return 0;
        }
        GrowthMission mission = growthMissionMapper.selectActiveByCode(request.getMissionCode());
        if (mission != null && "store_verify".equals(mission.getTriggerEvent())) {
            throw new ServiceException("该任务需完成到店核验后由系统发放");
        }
        // 自助上报通道不接受前端传入的 bizType/bizId，否则用户可通过变更 bizId 绕过幂等键反复刷奖励。
        // 需要按真实业务核销发奖的场景，应由服务端在动作完成后调用 applyEvent，bizId 由服务端生成。
        return completeMission(memberId, mission, null, null);
    }

    @Override
    @Transactional
    public int applyEvent(Long memberId, String eventName, String bizType, Long bizId) {
        if (memberId == null || StringUtils.isBlank(eventName)) {
            return 0;
        }
        List<GrowthMission> missions = growthMissionMapper.selectActiveByEvent(eventName);
        if (missions == null || missions.isEmpty()) {
            return 0;
        }
        // Mapper按事件列的排序规则归一到店语义，不能用传入名称的大小写绕过事实校验。
        boolean storeVerification = missions.stream().anyMatch(mission -> "store_verify".equals(mission.getTriggerEvent()));
        if (storeVerification && (!"registration".equals(bizType) || bizId == null
                || !memberId.equals(growthMissionMapper.selectVerifiedStoreMember(bizId)))) {
            return 0;
        }
        int accepted = 0;
        for (GrowthMission mission : missions) {
            // 同一核销事实跨日仍只发一次；报名行锁串行化查询与插入，保留历史进度。
            if (storeVerification && growthMissionProgressMapper.hasRegistrationAward(
                    memberId, mission.getMissionCode(), bizId)) continue;
            accepted += completeMission(memberId, mission, bizType, bizId);
        }
        return accepted;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueStoreVerification(Long registrationId) {
        Long memberId = growthMissionMapper.selectVerifiedStoreMember(registrationId);
        if (memberId == null) throw new IllegalStateException("核销成长来源事实缺失");
        List<GrowthMission> missions = growthMissionMapper.selectActiveByEvent("store_verify");
        // 空快照也保存，后续章节不能把新配置追授到此前核销。
        storeVerifyGrowthDeliveryMapper.enqueue(registrationId, memberId,
                JSON.toJSONString(nullToEmpty(missions)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deliverStoreVerification(Long registrationId) {
        Map<String, Object> event = storeVerifyGrowthDeliveryMapper.selectForUpdate(registrationId);
        if (event == null) return 0;
        Long memberId = ((Number) event.get("memberId")).longValue();
        List<GrowthMission> missions = JSON.parseArray((String) event.get("missionsJson"), GrowthMission.class);
        int accepted = 0;
        for (GrowthMission mission : missions) {
            if (!growthMissionProgressMapper.hasRegistrationAward(memberId, mission.getMissionCode(), registrationId)) {
                accepted += completeMission(memberId, mission, "registration", registrationId,
                        (Date) event.get("createTime"));
            }
        }
        if (storeVerifyGrowthDeliveryMapper.markProcessed(registrationId) != 1) {
            throw new IllegalStateException("核销成长待办状态更新失败");
        }
        return accepted;
    }

    @Override
    public List<MemberGrowth> selectAdminGrowthList(MemberGrowth growth) {
        return memberGrowthMapper.selectAdminGrowthList(growth);
    }

    @Override
    public List<GrowthMission> selectAdminMissionList(GrowthMission mission) {
        return growthMissionMapper.selectAdminMissionList(mission);
    }

    @Override
    public int saveMission(GrowthMission mission) {
        if (mission == null) {
            return 0;
        }
        if (mission.getId() == null) {
            // 旧表字段仍为 NOT NULL；当前任务配置不展示未实现的每日上限，新增记录沿用安全默认值。
            if (mission.getDailyLimit() == null) {
                mission.setDailyLimit(1);
            }
            mission.setCreateTime(DateUtils.getNowDate());
            return growthMissionMapper.insertGrowthMission(mission);
        }
        mission.setUpdateTime(DateUtils.getNowDate());
        return growthMissionMapper.updateGrowthMission(mission);
    }

    private int completeMission(Long memberId, GrowthMission mission, String bizType, Long bizId) {
        return completeMission(memberId, mission, bizType, bizId, new Date());
    }

    private int completeMission(Long memberId, GrowthMission mission, String bizType, Long bizId, Date progressDate) {
        if (mission == null || !"0".equals(mission.getStatus())) {
            return 0;
        }
        // 成长任务只发经验；积分统一由积分系统(IPointsAwardService 按 eventType)发放，不在此处发分。
        int exp = mission.getExpReward() == null ? 0 : mission.getExpReward();
        GrowthMissionProgress progress = new GrowthMissionProgress();
        progress.setMemberId(memberId);
        progress.setMissionCode(mission.getMissionCode());
        progress.setBizType(StringUtils.defaultIfBlank(bizType, ""));
        progress.setBizId(bizId == null ? 0L : bizId);
        progress.setProgressDate(progressDate);
        progress.setCompleteCount(1);
        progress.setRewardExp(exp);
        progress.setCreateTime(DateUtils.getNowDate());
        progress.setUpdateTime(DateUtils.getNowDate());
        int inserted = growthMissionProgressMapper.insertMissionProgress(progress);
        if (inserted <= 0) {
            return 0;
        }
        // 首次真正发奖时才建号，保证发奖路径有账户行可累加（ODKU：已存在则不覆盖既有经验/等级）。
        ensureAccount(memberId);
        MemberGrowth reward = new MemberGrowth();
        reward.setMemberId(memberId);
        reward.setExpValue(exp);
        reward.setLastActiveTime(DateUtils.getNowDate());
        reward.setUpdateTime(DateUtils.getNowDate());
        memberGrowthMapper.updateMemberGrowthReward(reward);
        if (exp > 0 && memberGrowthMapper.insertEarnedExp(memberId, exp, mission.getMissionCode(),
                reward.getUpdateTime(), progress.getId()) != 1) {
            throw new IllegalStateException("经验获得明细写入失败");
        }
        return 1;
    }

    /**
     * M7 变量额直发探索值。复用任务发奖同一路径(ensureAccount + updateMemberGrowthReward 累加)。
     * 经验余额与获得明细同事务写入，任何情况下不写 ums_member_points_detail(可抵现积分)。
     * 幂等由调用方场景保证(节点=player_node_progress uk 首次,通关=firstTime&&completed 恰一次)。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int addExp(Long memberId, int exp, String reason) {
        if (memberId == null || memberId <= 0 || exp <= 0) {
            return 0;
        }
        ensureAccount(memberId);
        MemberGrowth reward = new MemberGrowth();
        reward.setMemberId(memberId);
        reward.setExpValue(exp);
        reward.setLastActiveTime(DateUtils.getNowDate());
        reward.setUpdateTime(DateUtils.getNowDate());
        memberGrowthMapper.updateMemberGrowthReward(reward);
        if (memberGrowthMapper.insertEarnedExp(memberId, exp, reason, reward.getUpdateTime(), null) != 1) {
            throw new IllegalStateException("经验获得明细写入失败");
        }
        return exp;
    }

    private void ensureAccount(Long memberId) {
        MemberGrowth row = new MemberGrowth();
        row.setMemberId(memberId);
        row.setLevelNo(1);
        row.setExpValue(0);
        row.setLastActiveTime(DateUtils.getNowDate());
        row.setCreateTime(DateUtils.getNowDate());
        row.setUpdateTime(DateUtils.getNowDate());
        memberGrowthMapper.insertMemberGrowth(row);
    }

    private <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }
}
