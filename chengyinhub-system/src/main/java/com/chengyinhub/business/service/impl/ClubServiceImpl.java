package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.ClubLeaderMapper;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.domain.MerchantOperator;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.ClubPendingSettlement;
import com.chengyinhub.business.domain.ClubPendingDeposit;
import com.chengyinhub.business.domain.ClubDissolutionBlockers;
import com.chengyinhub.business.service.ClubDissolutionDepositException;
import com.chengyinhub.business.service.ClubDissolutionSettlementException;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;

/**
 * 俱乐部 Service 业务层处理
 */
@Service
public class ClubServiceImpl implements IClubService
{
    private static final Logger log = LoggerFactory.getLogger(ClubServiceImpl.class);

    @Autowired
    private ClubMapper clubMapper;

    @Autowired
    private ClubMemberMapper clubMemberMapper;

    @Autowired
    private IImService imService;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private ClubLeaderMapper clubLeaderMapper;

    @Autowired
    private MmsMerchantMapper mmsMerchantMapper;

    @Autowired
    private MerchantOperatorMapper merchantOperatorMapper;

    @Autowired
    private ICapabilityService capabilityService;

    @Autowired
    private IRoleService roleService;

    @Override
    public Club selectClubById(Long id)
    {
        return clubMapper.selectClubById(id);
    }

    @Override
    public Club selectClubByIdForUpdate(Long id)
    {
        return clubMapper.selectClubByIdForUpdate(id);
    }

    @Override
    public Club selectClubByMemberId(Long memberId)
    {
        return clubMapper.selectClubByMemberId(memberId);
    }

    @Override
    public List<Club> selectClubsByMemberId(Long memberId)
    {
        return clubMapper.selectClubsByMemberId(memberId);
    }

    @Override
    public List<Club> selectClubList(Club club)
    {
        return clubMapper.selectClubList(club);
    }

    @Override
    public int insertClub(Club club)
    {
        requireNameFree(club.getName() == null ? null : club.getName().trim(), null, null);
        club.setCreateTime(DateUtils.getNowDate());
        return holdingName(() -> clubMapper.insertClub(club));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertClubWithinOwnerQuota(Club club, int maxOwnedClubs)
    {
        validateClubCreate(club, maxOwnedClubs);
        Long memberId = club.getMemberId();
        lockActiveMember(memberId);
        requireNoActiveMerchantIdentity(memberId);
        requireActiveLeader(memberId);
        return insertClubWithinOwnerQuotaLocked(club, maxOwnedClubs);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubLeader saveLeaderProfileWithinIdentityMutex(Long memberId, ClubLeader profile)
    {
        if (memberId == null || memberId <= 0 || profile == null)
        {
            throw new ServiceException("主理人资料不完整", HttpStatus.BAD_REQUEST);
        }
        UmsMember account = lockActiveMember(memberId);
        requireNoActiveMerchantIdentity(memberId);
        ClubLeader stored = saveLeaderProfileLocked(memberId, profile);
        ensureLeaderIdentity(account, memberId);
        return stored;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int onboardLeaderAndInsertClubWithinIdentityMutex(
            Long memberId, ClubLeader profile, Club club, int maxOwnedClubs)
    {
        validateClubCreate(club, maxOwnedClubs);
        if (memberId == null || !memberId.equals(club.getMemberId()) || profile == null)
        {
            throw new ServiceException("主理人入驻参数不完整", HttpStatus.BAD_REQUEST);
        }
        UmsMember account = lockActiveMember(memberId);
        requireNoActiveMerchantIdentity(memberId);
        ClubLeader current = clubLeaderMapper.selectClubLeaderByMemberId(memberId);
        if (current == null)
        {
            saveNewLeaderProfile(memberId, profile);
        }
        else
        {
            requireActiveLeaderRecord(current, memberId);
        }
        ensureLeaderIdentity(account, memberId);
        return insertClubWithinOwnerQuotaLocked(club, maxOwnedClubs);
    }

    private UmsMember lockActiveMember(Long memberId)
    {
        if (!memberId.equals(umsMemberService.lockMemberById(memberId)))
        {
            throw new ServiceException("主理人账号不可用", HttpStatus.FORBIDDEN);
        }
        UmsMember account = umsMemberService.selectUmsMemberById(memberId);
        if (account == null || !memberId.equals(account.getId())
                || !Long.valueOf(1L).equals(account.getStatus())
                || !Integer.valueOf(0).equals(account.getDelFlag()))
        {
            throw new ServiceException("主理人账号不可用", HttpStatus.FORBIDDEN);
        }
        return account;
    }

    private void requireNoActiveMerchantIdentity(Long memberId)
    {
        if (mmsMerchantMapper.countMmsMerchantRowsByMemberIdForUpdate(memberId) > 0)
        {
            throw new ServiceException("您已注册为商户,一个账户不能同时是商户与俱乐部主理人",
                    HttpStatus.CONFLICT);
        }
        MerchantOperator operator = merchantOperatorMapper.selectActiveByMemberId(memberId);
        if (operator != null)
        {
            throw new ServiceException("您已加入商家团队,一个账户不能同时是商家员工与俱乐部主理人",
                    HttpStatus.CONFLICT);
        }
    }

    private ClubLeader saveLeaderProfileLocked(Long memberId, ClubLeader profile)
    {
        ClubLeader current = clubLeaderMapper.selectClubLeaderByMemberId(memberId);
        if (current == null)
        {
            return saveNewLeaderProfile(memberId, profile);
        }
        requireActiveLeaderRecord(current, memberId);
        profile.setId(current.getId());
        profile.setMemberId(null);
        profile.setUpdateTime(DateUtils.getNowDate());
        if (clubLeaderMapper.updateClubLeader(profile) != 1)
        {
            throw new ServiceException("主理人资料保存失败，请重试", HttpStatus.CONFLICT);
        }
        ClubLeader stored = clubLeaderMapper.selectClubLeaderByMemberId(memberId);
        requireActiveLeaderRecord(stored, memberId);
        return stored;
    }

    private ClubLeader saveNewLeaderProfile(Long memberId, ClubLeader profile)
    {
        profile.setId(null);
        profile.setMemberId(memberId);
        profile.setStatus(1);
        profile.setLevel(0);
        profile.setTags(null);
        profile.setDelFlag(0);
        profile.setCreateTime(DateUtils.getNowDate());
        if (clubLeaderMapper.insertClubLeader(profile) != 1)
        {
            throw new ServiceException("主理人身份建立失败，请重试", HttpStatus.CONFLICT);
        }
        return profile;
    }

    private ClubLeader requireActiveLeader(Long memberId)
    {
        ClubLeader leader = clubLeaderMapper.selectClubLeaderByMemberId(memberId);
        requireActiveLeaderRecord(leader, memberId);
        return leader;
    }

    private void requireActiveLeaderRecord(ClubLeader leader, Long memberId)
    {
        if (leader == null || leader.getId() == null || !memberId.equals(leader.getMemberId())
                || !Integer.valueOf(1).equals(leader.getStatus())
                || !Integer.valueOf(0).equals(leader.getDelFlag()))
        {
            throw new ServiceException("请先成为有效的俱乐部主理人", HttpStatus.CONFLICT);
        }
    }

    private void ensureLeaderIdentity(UmsMember account, Long memberId)
    {
        roleService.changeRole(memberId, IRoleService.ROLE_CLUB, 0L);
    }

    private void validateClubCreate(Club club, int maxOwnedClubs)
    {
        if (club == null || club.getMemberId() == null || club.getMemberId() <= 0 || maxOwnedClubs <= 0)
        {
            throw new ServiceException("俱乐部创建参数不完整", HttpStatus.BAD_REQUEST);
        }
    }

    private int insertClubWithinOwnerQuotaLocked(Club club, int maxOwnedClubs)
    {
        Long memberId = club.getMemberId();
        List<Long> ownedClubIds = clubMapper.selectOwnedClubIdsForUpdate(memberId);
        if (ownedClubIds == null)
        {
            throw new ServiceException("俱乐部配额状态暂不可确认", HttpStatus.CONFLICT);
        }
        // 行已在上面 FOR UPDATE 锁住,这里只读它们的状态/名称
        List<Club> ownedRows = ownedClubIds.isEmpty() ? java.util.Collections.emptyList()
                : clubMapper.selectClubsByIds(ownedClubIds);
        Club pending = null, sameName = null, rejected = null;
        int rejectedCount = 0;
        String name = club.getName() == null ? null : club.getName().trim();
        for (Club row : ownedRows == null ? java.util.Collections.<Club>emptyList() : ownedRows)
        {
            if (row == null || row.getId() == null || !ownedClubIds.contains(row.getId())) continue;
            long status = row.getStatus() == null ? -1L : row.getStatus();
            if (status == 0L) pending = newer(pending, row);
            else if (status == 2L) { rejected = newer(rejected, row); rejectedCount++; }
            else if (status == 1L && name != null && row.getName() != null && name.equals(row.getName().trim()))
                sameName = newer(sameName, row);
        }
        // 幂等重放:已有待审申请,或同名俱乐部刚建好(网络重试),原样返回那一条,不再新建
        Club replay = pending != null ? pending : sameName;
        int counted = ownedClubIds.size() - rejectedCount;   // 被驳回的不占配额
        if (replay != null)
        {
            club.setId(replay.getId());
            club.setStatus(replay.getStatus());
            club.setLogo(replay.getLogo());
            club.setCover(replay.getCover());
            club.setCaseImages(replay.getCaseImages());
            return counted;
        }
        // 俱乐部名称全站唯一(用户 2026-10-20 决定):别的主理人待审或在营的俱乐部占着同名就拒绝;本人同名已在上面按重放处理
        requireNameFree(name, memberId, null);
        if (counted >= maxOwnedClubs)
        {
            throw new ServiceException("每位主理人最多创建" + maxOwnedClubs + "个俱乐部", HttpStatus.CONFLICT);
        }
        if (rejected != null)
        {
            // 驳回后重新提交:改回同一行为待审并清掉驳回原因,不复制第二个俱乐部
            club.setId(rejected.getId());
            club.setStatus(0L);
            club.setRejectReason(null);
            club.setMemberCount(null);
            club.setUpdateTime(DateUtils.getNowDate());
            if (holdingName(() -> clubMapper.updateClub(club)) != 1)
            {
                throw new ServiceException("俱乐部重新提交失败，请重试", HttpStatus.CONFLICT);
            }
            return counted + 1;
        }
        club.setCreateTime(DateUtils.getNowDate());
        if (holdingName(() -> clubMapper.insertClub(club)) != 1)
        {
            throw new ServiceException("俱乐部创建失败，请重试", HttpStatus.CONFLICT);
        }
        return counted + 1;
    }

    private static Club newer(Club current, Club candidate)
    {
        return current == null || candidate.getId() > current.getId() ? candidate : current;
    }

    /** 俱乐部名称全站唯一(用户 2026-10-20 决定):待审或在营、未删除的俱乐部占用名称,被驳回和已解散的不占。 */
    private void requireNameFree(String name, Long ownerToSkip, Long clubToSkip)
    {
        if (name == null || name.isEmpty()) return;
        if (clubMapper.countClubsHoldingName(name, ownerToSkip, clubToSkip) > 0)
        {
            throw new ServiceException(NAME_TAKEN, HttpStatus.CONFLICT);
        }
    }

    private static final String NAME_TAKEN = "俱乐部名称已被使用，请换一个";
    private static final String HOLDING_NAME_INDEX = "uk_club_holding_name";

    /**
     * 两位主理人同一时刻抢同一个新名称时,上面的 count 都看不到对方,由库里的 uk_club_holding_name 兜底。
     * 只把这个索引的冲突翻译成同一句 409;别的唯一键冲突照常抛出。
     */
    private static int holdingName(java.util.function.IntSupplier write)
    {
        try
        {
            return write.getAsInt();
        }
        catch (DuplicateKeyException e)
        {
            if (isHoldingNameConflict(e)) throw new ServiceException(NAME_TAKEN, HttpStatus.CONFLICT);
            throw e;
        }
    }

    static boolean isHoldingNameConflict(DuplicateKeyException e)
    {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause())
        {
            if (t.getMessage() != null && t.getMessage().contains(HOLDING_NAME_INDEX)) return true;
        }
        return false;
    }

    /** 改名才查重:名称没变(包括历史上已经重名的俱乐部)照常保存。 */
    private void requireRenameFree(Club club)
    {
        if (club == null || club.getId() == null || club.getName() == null) return;
        String name = club.getName().trim();
        Club current = clubMapper.selectClubById(club.getId());
        if (current != null && current.getName() != null && name.equals(current.getName().trim())) return;
        requireNameFree(name, null, club.getId());
    }

    @Override
    public int updateClub(Club club)
    {
        requireRenameFree(club);
        club.setUpdateTime(DateUtils.getNowDate());
        return holdingName(() -> clubMapper.updateClub(club));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteClubByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        int deleted = 0;
        for (Long id : ids)
        {
            Club club = clubMapper.selectClubByIdForUpdate(id);
            if (club == null)
            {
                continue;
            }
            dissolveLockedClub(club, true);
            deleted++;
        }
        return deleted;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteClubById(Long id)
    {
        return deleteClubByIds(new Long[] { id });
    }

    @Override
    public int updateMemberCount(Long clubId, int delta)
    {
        return clubMapper.updateMemberCount(clubId, delta);
    }

    @Override
    public boolean isJoinPolicySupported()
    {
        try
        {
            return clubMapper.countJoinPolicyColumn() > 0;
        }
        catch (RuntimeException e)
        {
            log.warn("join_policy 能力探针失败，按公开直进降级", e);
            return false;
        }
    }

    @Override
    public int selectJoinPolicy(Long clubId)
    {
        if (!isJoinPolicySupported())
        {
            return 0;
        }
        try
        {
            Integer policy = clubMapper.selectJoinPolicy(clubId);
            return Integer.valueOf(1).equals(policy) ? 1 : 0;
        }
        catch (RuntimeException e)
        {
            // 探针与真实查询之间若正好发生迁移回滚，也必须保持旧版公开行为而不是 500。
            log.warn("读取 join_policy 失败，按公开直进降级，clubId={}", clubId, e);
            return 0;
        }
    }

    @Override
    public void updateMemberPostAllowed(Long clubId, Long ownerId, boolean enabled)
    {
        Club club = requireOwnedForOpenSettings(clubId, ownerId);
        if (clubMapper.updateMemberPostAllowed(club.getId(), enabled ? 1 : 0) < 1) {
            throw new IllegalStateException("开放设置没有保存成功，请重试");
        }
    }

    @Override
    public void updatePublicVisible(Long clubId, Long ownerId, boolean enabled)
    {
        Club club = requireOwnedForOpenSettings(clubId, ownerId);
        if (clubMapper.updatePublicVisible(club.getId(), enabled ? 1 : 0) < 1) {
            throw new IllegalStateException("开放设置没有保存成功，请重试");
        }
    }

    @Override
    public void updateMerchantUndertakeOpen(Long clubId, Long ownerId, boolean enabled)
    {
        Club club = requireOwnedForOpenSettings(clubId, ownerId);
        if (clubMapper.updateMerchantUndertakeOpen(club.getId(), enabled ? 1 : 0) < 1) {
            throw new IllegalStateException("开放设置没有保存成功，请重试");
        }
    }

    /** 开放设置改的是「这个俱乐部对外怎么开」,归主理人本人。管理员管人,不管规则。 */
    private Club requireOwnedForOpenSettings(Long clubId, Long ownerId)
    {
        if (clubId == null || ownerId == null) throw new IllegalArgumentException("缺少俱乐部或操作人");
        Club club = clubMapper.selectClubById(clubId);
        if (club == null) throw new IllegalArgumentException("俱乐部不存在");
        if (!ownerId.equals(club.getMemberId())) throw new IllegalArgumentException("只有主理人可以修改开放设置");
        return club;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateOwnedClubProfile(Club club, Long ownerId, Integer joinPolicy)
    {
        if (club == null || club.getId() == null)
        {
            throw new IllegalArgumentException("俱乐部不存在");
        }
        Club locked = clubMapper.selectClubByIdForUpdate(club.getId());
        if (locked == null)
        {
            throw new IllegalArgumentException("俱乐部不存在");
        }
        // 2026-08-26:展示资料放开给管理员。★ 判据在锁内重算 —— 期间可能被降级或俱乐部被解散,
        // 拿调用方算好的布尔值进来就等于把闸挪到锁外,那是假闸。
        boolean isOwner = locked.getMemberId() != null && locked.getMemberId().equals(ownerId);
        boolean isAdmin = false;
        if (!isOwner)
        {
            ClubMember actor = clubMemberMapper.selectByClubAndMember(club.getId(), ownerId);
            isAdmin = actor != null && Integer.valueOf(1).equals(actor.getRole())
                    && Integer.valueOf(1).equals(actor.getStatus());
        }
        if (!isOwner && !isAdmin)
        {
            throw new IllegalArgumentException("无权修改该俱乐部");
        }
        // 加入方式属于「经营配置」,只有主理人能改。管理员传了必须**报错**,
        // 不能静默丢弃 —— 静默丢弃会让他以为改成功了。
        if (!isOwner && joinPolicy != null)
        {
            throw new IllegalArgumentException("只有主理人可以修改加入方式");
        }
        if (joinPolicy != null && joinPolicy != 0 && joinPolicy != 1)
        {
            throw new IllegalArgumentException("加入方式仅支持公开或需审批");
        }
        boolean supported;
        try
        {
            supported = clubMapper.countJoinPolicyColumn() > 0;
        }
        catch (RuntimeException e)
        {
            log.warn("保存俱乐部时 join_policy 能力探针失败，拒绝可能的私密策略降级，clubId={}", club.getId(), e);
            if (joinPolicy != null)
            {
                throw new IllegalStateException("加入方式状态暂不可确认，未保存任何修改，请稍后重试");
            }
            supported = false;
        }
        if (joinPolicy != null && supported)
        {
            try
            {
                if (clubMapper.updateJoinPolicy(club.getId(), joinPolicy) != 1)
                {
                    throw new IllegalStateException("加入方式保存失败，请重试");
                }
            }
            catch (IllegalStateException e)
            {
                throw e;
            }
            catch (RuntimeException e)
            {
                log.warn("保存 join_policy 失败，整笔资料修改回滚，clubId={}", club.getId(), e);
                throw new IllegalStateException("加入方式保存失败，请重试");
            }
        }
        club.setUpdateTime(DateUtils.getNowDate());
        if (holdingName(() -> clubMapper.updateClub(club)) != 1)
        {
            throw new IllegalStateException("俱乐部资料保存失败，请重试");
        }
        return supported;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int dissolveClub(Long clubId, Long ownerId, boolean confirmed, boolean memberConsequencesConfirmed)
    {
        if (!confirmed)
        {
            throw new IllegalStateException("请先确认解散俱乐部及其后果");
        }
        Club club = clubMapper.selectClubByIdForUpdate(clubId);
        if (club == null)
        {
            throw new IllegalArgumentException("俱乐部不存在");
        }
        if (club.getMemberId() == null || !club.getMemberId().equals(ownerId))
        {
            throw new IllegalArgumentException("无权解散该俱乐部");
        }
        return dissolveLockedClub(club, memberConsequencesConfirmed);
    }

    /** 调用方已持有 club 行锁；平台删除与主理人解散必须共用全部安全闸和 IM 收尾。 */
    private int dissolveLockedClub(Club club, boolean memberConsequencesConfirmed)
    {
        Long clubId = club.getId();
        int unsettledRevenueCount = clubMapper.countUnsettledRevenueByClub(clubId);
        if (unsettledRevenueCount > 0)
        {
            throw new IllegalStateException("俱乐部还有 " + unsettledRevenueCount
                    + " 个主题未结算，请先到俱乐部项目完成结算");
        }
        List<ClubPendingSettlement> unpaidSettlements = clubMapper.selectUnpaidSettlementsByClub(clubId);
        if (unpaidSettlements != null && !unpaidSettlements.isEmpty())
        {
            int incoming = 0;
            int outgoing = 0;
            int unknown = 0;
            for (ClubPendingSettlement settlement : unpaidSettlements)
            {
                if (settlement.getAmount() == null)
                {
                    log.error("俱乐部待打款结算金额为 NULL，按未了结阻塞解散，clubId={}, settlementId={}",
                            clubId, settlement.getId());
                }
                if ("incoming".equals(settlement.getDirection())) incoming++;
                else if ("outgoing".equals(settlement.getDirection())) outgoing++;
                else unknown++;
            }
            String detail = "待收 " + incoming + " 笔、待付 " + outgoing + " 笔";
            if (unknown > 0) detail += "、待确认 " + unknown + " 笔";
            throw new ClubDissolutionSettlementException("俱乐部还有 " + unpaidSettlements.size()
                    + " 笔未打款结算（" + detail + "），请先逐笔处理", unpaidSettlements);
        }
        int missingTopicCooperationCount = clubMapper.countActiveCooperationsWithMissingTopicByClub(clubId);
        if (missingTopicCooperationCount > 0)
        {
            log.error("俱乐部生效合作缺少父主题，按进行中阻塞解散，clubId={}, count={}",
                    clubId, missingTopicCooperationCount);
        }
        int activeCooperationCount = clubMapper.countActiveCooperationsByClub(clubId);
        if (activeCooperationCount > 0)
        {
            throw new IllegalStateException("俱乐部还有 " + activeCooperationCount
                    + " 个合作生效中，请先到我的合作取消或完成结算");
        }
        List<ClubPendingDeposit> pendingDeposits = clubMapper.selectPendingDepositsByClub(clubId);
        if (pendingDeposits != null && !pendingDeposits.isEmpty())
        {
            throw new ClubDissolutionDepositException("俱乐部还有 " + pendingDeposits.size()
                    + " 笔合作保证金待处理，请先到合作管理逐笔处理", pendingDeposits);
        }
        int ongoingSessionCount = clubMapper.countOngoingSessionsByClub(clubId);
        if (ongoingSessionCount > 0)
        {
            throw new IllegalStateException("俱乐部还有 " + ongoingSessionCount
                    + " 个未结束场次，请先完成或取消场次");
        }
        int incompleteRegistrationCount = clubMapper.countIncompleteRegistrationsByClub(clubId);
        if (incompleteRegistrationCount > 0)
        {
            throw new IllegalStateException("俱乐部还有 " + incompleteRegistrationCount
                    + " 笔未完成报名，请先处理报名或退款");
        }
        int memberCount = clubMemberMapper.countByClub(clubId);
        com.chengyinhub.business.domain.ClubMember ownerMembership =
                clubMemberMapper.selectAnyByClubAndMember(clubId, club.getMemberId());
        if (ownerMembership != null && Integer.valueOf(1).equals(ownerMembership.getStatus()))
        {
            memberCount = Math.max(0, memberCount - 1);
        }
        if (memberCount > 0 && !memberConsequencesConfirmed)
        {
            throw new IllegalStateException("俱乐部仍有成员，请再次确认成员将失去俱乐部与群聊入口");
        }
        if (clubMapper.dissolveClubById(clubId) != 1)
        {
            throw new IllegalStateException("俱乐部解散失败，请稍后重试");
        }
        imService.closeClubGroup(clubId);
        return memberCount;
    }

    @Override
    public ClubDissolutionBlockers selectDissolutionBlockers(Long clubId, Long ownerId)
    {
        Club club = clubMapper.selectClubById(clubId);
        if (club == null) throw new IllegalArgumentException("俱乐部不存在");
        if (club.getMemberId() == null || !club.getMemberId().equals(ownerId))
        {
            throw new IllegalArgumentException("无权查看该俱乐部的解散阻断明细");
        }
        ClubDissolutionBlockers result = new ClubDissolutionBlockers();
        result.setDeposits(clubMapper.selectPendingDepositsByClub(clubId));
        result.setSettlements(clubMapper.selectUnpaidSettlementsByClub(clubId));
        return result;
    }
}
