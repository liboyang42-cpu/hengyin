package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.UmsMemberFollowMapper;
import com.chengyinhub.business.domain.UmsMemberFollow;
import com.chengyinhub.business.service.IUmsMemberFollowService;

/**
 * 用户之间关注Service业务层处理
 * 
 * @author ruoyi
 * @date 2025-08-26
 */
@Service
public class UmsMemberFollowServiceImpl implements IUmsMemberFollowService 
{
    @Autowired
    private UmsMemberFollowMapper umsMemberFollowMapper;

    /** 复用既有的会员行锁(select ... for update),不新增表/列。 */
    @Autowired
    private com.chengyinhub.business.mapper.UmsMemberMapper umsMemberMapper;

    /**
     * R10-07:先锁 follower 的会员行,再在同一事务内查重 + 写。
     * 同一 follower 的并发关注/取消在此串行化:并发首次关注只可能有一个事务走到「查无→插入」;
     * 失败回滚后锁释放,重试可正常完成。绝不删除别人或历史关系。
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public ApplyResult applyFollowState(Long memberId, Long followMemberId, Boolean desired)
    {
        if (memberId == null || memberId <= 0 || followMemberId == null || followMemberId <= 0) {
            return ApplyResult.INVALID_MEMBER;
        }
        Long locked = umsMemberMapper.lockMemberById(memberId);
        if (locked == null) {
            return ApplyResult.INVALID_MEMBER;
        }
        UmsMemberFollow probe = new UmsMemberFollow();
        probe.setMemberId(memberId);
        probe.setFollowMemberId(followMemberId);
        List<UmsMemberFollow> list = umsMemberFollowMapper.selectUmsMemberFollowList(probe);
        boolean followed = list != null && !list.isEmpty();
        if (desired == null) {
            //旧调用者:有记录取消(目标注销也要能取消),没记录新增(需建关系校验)
            return followed ? removeFollow(list) : establishFollow(probe, memberId, followMemberId);
        }
        if (desired) {
            return followed ? ApplyResult.ALREADY_FOLLOWED : establishFollow(probe, memberId, followMemberId);
        }
        return followed ? removeFollow(list) : ApplyResult.ALREADY_UNFOLLOWED;
    }

    /**
     * 建立新关注关系才做的校验:禁止自关注、目标必须真实存在(非锁读,避免锁序反转)。
     * 取消/解除遗留关系不走这里 —— 目标注销/不存在时旧关注仍必须能取消。
     */
    private ApplyResult establishFollow(UmsMemberFollow row, Long memberId, Long followMemberId)
    {
        if (memberId.equals(followMemberId)) {
            return ApplyResult.SELF_FOLLOW;
        }
        if (umsMemberFollowMapper.selectExistingMemberId(followMemberId) == null) {
            return ApplyResult.INVALID_MEMBER;
        }
        return addFollow(row);
    }

    /**
     * R10-07 收尾:后台编辑与新增/取消共用同一把 follower 行锁,消除 check-then-update 竞态。
     * old/new follower 按 id 升序加锁(防 A←→B 并发编辑互锁);锁内重查撞键;不依赖唯一索引已落库。
     */
    @Override
    // READ_COMMITTED:锁后重查要看最新已提交(RR 旧快照会漏同键);同时避免 FOR UPDATE 在无索引
    // 关系表上做全表/间隙锁导致并发死锁 —— 串行化交给 follower 行锁,不靠间隙锁。
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class,
            isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public EditResult editUmsMemberFollowLocked(UmsMemberFollow update)
    {
        if (update == null || update.getId() == null || update.getId() <= 0) {
            return new EditResult(EditResult.Kind.NOT_FOUND);
        }
        // 非锁读仅用于确定锁集。与新增/取消统一先锁会员、后锁关系，避免 DELETE 反向等待。
        UmsMemberFollow snapshot = umsMemberFollowMapper.selectUmsMemberFollowById(update.getId());
        if (snapshot == null) {
            return new EditResult(EditResult.Kind.NOT_FOUND);
        }
        Long newMemberId = update.getMemberId() != null ? update.getMemberId() : snapshot.getMemberId();
        if (newMemberId == null || newMemberId <= 0 || !lockFollowersAscending(snapshot.getMemberId(), newMemberId)) {
            return new EditResult(EditResult.Kind.INVALID_MEMBER);
        }
        UmsMemberFollow current = umsMemberFollowMapper.selectUmsMemberFollowByIdForUpdate(update.getId());
        if (current == null) {
            return new EditResult(EditResult.Kind.NOT_FOUND);
        }
        // 等待期间关系可能移交给另一会员。不能持着旧锁集继续改，也不能补锁破坏升序。
        if (!snapshot.getMemberId().equals(current.getMemberId())) {
            return new EditResult(EditResult.Kind.CONCURRENT_CHANGE);
        }
        Long newTargetId = update.getFollowMemberId() != null ? update.getFollowMemberId() : current.getFollowMemberId();
        if (newMemberId == null || newMemberId <= 0 || newTargetId == null || newTargetId <= 0) {
            return new EditResult(EditResult.Kind.INVALID_MEMBER);
        }
        boolean pairChanging = !newMemberId.equals(current.getMemberId())
                || !newTargetId.equals(current.getFollowMemberId());
        // 存在性/自关注校验只拦「建立新关系」;原样编辑(含目标已注销)不受阻,遗留关系仍可取消/维护。
        if (pairChanging) {
            if (newMemberId.equals(newTargetId)) {
                return new EditResult(EditResult.Kind.SELF_FOLLOW);
            }
            if (umsMemberFollowMapper.selectExistingMemberId(newTargetId) == null) {
                return new EditResult(EditResult.Kind.INVALID_MEMBER);
            }
        }
        UmsMemberFollow probe = new UmsMemberFollow();
        probe.setMemberId(newMemberId);
        probe.setFollowMemberId(newTargetId);
        List<UmsMemberFollow> same = umsMemberFollowMapper.selectUmsMemberFollowList(probe);
        if (same != null) {
            for (UmsMemberFollow row : same) {
                if (row != null && row.getId() != null && !row.getId().equals(update.getId())) {
                    return new EditResult(EditResult.Kind.CONFLICT);
                }
            }
        }
        update.setMemberId(newMemberId);
        update.setFollowMemberId(newTargetId);
        update.setUpdateTime(DateUtils.getNowDate());
        return umsMemberFollowMapper.updateUmsMemberFollow(update) > 0
                ? new EditResult(EditResult.Kind.UPDATED)
                : new EditResult(EditResult.Kind.FAILED);
    }

    private boolean lockFollowersAscending(Long first, Long second)
    {
        if (first == null) {
            return false;
        }
        if (second == null || first.equals(second)) {
            return umsMemberMapper.lockMemberById(first) != null;
        }
        Long low = first < second ? first : second;
        Long high = first < second ? second : first;
        if (umsMemberMapper.lockMemberById(low) == null) {
            return false;
        }
        return umsMemberMapper.lockMemberById(high) != null;
    }

    private ApplyResult addFollow(UmsMemberFollow row)
    {
        row.setCreateTime(DateUtils.getNowDate());
        return umsMemberFollowMapper.insertUmsMemberFollow(row) > 0 ? ApplyResult.FOLLOWED : ApplyResult.FAILED;
    }

    private ApplyResult removeFollow(List<UmsMemberFollow> list)
    {
        Long[] ids = list.stream()
                .filter(item -> item != null && item.getId() != null)
                .map(UmsMemberFollow::getId)
                .toArray(Long[]::new);
        if (ids.length == 0) {
            return ApplyResult.FAILED;
        }
        return umsMemberFollowMapper.deleteUmsMemberFollowByIds(ids) > 0
                ? ApplyResult.UNFOLLOWED : ApplyResult.FAILED;
    }

    /**
     * 查询用户之间关注
     * 
     * @param id 用户之间关注主键
     * @return 用户之间关注
     */
    @Override
    public UmsMemberFollow selectUmsMemberFollowById(Long id)
    {
        return umsMemberFollowMapper.selectUmsMemberFollowById(id);
    }

    /**
     * 查询用户之间关注列表
     * 
     * @param umsMemberFollow 用户之间关注
     * @return 用户之间关注
     */
    @Override
    public List<UmsMemberFollow> selectUmsMemberFollowList(UmsMemberFollow umsMemberFollow)
    {
        return umsMemberFollowMapper.selectUmsMemberFollowList(umsMemberFollow);
    }

    @Override
    public List<UmsMemberFollow> selectUmsMemberFollowsByMemberAndFollowMemberIds(Long memberId, List<Long> followMemberIds) {
        return umsMemberFollowMapper.selectUmsMemberFollowsByMemberAndFollowMemberIds(memberId, followMemberIds);
    }

    /**
     * 新增用户之间关注
     * 
     * @param umsMemberFollow 用户之间关注
     * @return 结果
     */
    @Override
    public int insertUmsMemberFollow(UmsMemberFollow umsMemberFollow)
    {
        umsMemberFollow.setCreateTime(DateUtils.getNowDate());
        return umsMemberFollowMapper.insertUmsMemberFollow(umsMemberFollow);
    }

    /**
     * 修改用户之间关注
     * 
     * @param umsMemberFollow 用户之间关注
     * @return 结果
     */
    @Override
    public int updateUmsMemberFollow(UmsMemberFollow umsMemberFollow)
    {
        umsMemberFollow.setUpdateTime(DateUtils.getNowDate());
        return umsMemberFollowMapper.updateUmsMemberFollow(umsMemberFollow);
    }

    /**
     * 批量删除用户之间关注
     * 
     * @param ids 需要删除的用户之间关注主键
     * @return 结果
     */
    @Override
    public int deleteUmsMemberFollowByIds(Long[] ids)
    {
        return umsMemberFollowMapper.deleteUmsMemberFollowByIds(ids);
    }

    /**
     * 删除用户之间关注信息
     * 
     * @param id 用户之间关注主键
     * @return 结果
     */
    @Override
    public int deleteUmsMemberFollowById(Long id)
    {
        return umsMemberFollowMapper.deleteUmsMemberFollowById(id);
    }

    /**
     * 查询用户关注数
     *
     * @param member_id 用户ID
     * @return 结果
     */
    @Override
    public int selectUmsMemberFollowNumByMemberId(Long member_id){
        return umsMemberFollowMapper.selectUmsMemberFollowNumByMemberId(member_id);
    }

    @Override
    public int selectMutualFollowCount(Long memberId) {
        return umsMemberFollowMapper.selectMutualFollowCount(memberId);
    }

    /**
     * 查询用户粉丝数
     *
     * @param member_id 用户ID
     * @return 结果
     */
    @Override
    public int selectUmsMemberFansNumByMemberId(Long member_id){
        return umsMemberFollowMapper.selectUmsMemberFansNumByMemberId(member_id);
    }
}
