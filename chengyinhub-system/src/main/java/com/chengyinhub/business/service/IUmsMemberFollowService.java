package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.UmsMemberFollow;

/**
 * 用户之间关注Service接口
 * 
 * @author ruoyi
 * @date 2025-08-26
 */
public interface IUmsMemberFollowService 
{
    /** R10-07:按「期望状态」幂等应用的结果。 */
    enum ApplyResult {
        FOLLOWED,
        UNFOLLOWED,
        ALREADY_FOLLOWED,
        ALREADY_UNFOLLOWED,
        SELF_FOLLOW,
        INVALID_MEMBER,
        FAILED
    }

    /** R10-07 后台编辑的锁内结果。 */
    class EditResult {
        public enum Kind {
            UPDATED,
            CONFLICT,
            CONCURRENT_CHANGE,
            NOT_FOUND,
            INVALID_MEMBER,
            SELF_FOLLOW,
            FAILED
        }

        private final Kind kind;

        public EditResult(Kind kind) {
            this.kind = kind;
        }

        public Kind getKind() { return kind; }
    }

    /**
     * R10-07:在同一事务内对 follower 行加锁后查重 + 写,按期望状态幂等应用关注关系。
     * desired=true 确保关注、false 确保取消(已是目标态则空操作),null=旧调用者 toggle。
     * 并发首次关注只会有一个事务插入成功,其余读到已提交结果;丢响应重试不反转意图。
     */
    public ApplyResult applyFollowState(Long memberId, Long followMemberId, Boolean desired);

    /**
     * 后台编辑关注关系:与玩家/后台新增同一把 follower 行锁(同一事务内)。
     * 受影响 follower(old+new)按 id 升序加锁防死锁,锁内重查 (member_id, follow_member_id) 撞键拒绝;
     * 不校验唯一索引是否已落库,不依赖生产迁移;不锁 target(避免与关注写路径锁序反转)。
     */
    public EditResult editUmsMemberFollowLocked(UmsMemberFollow update);

    /**
     * 查询用户之间关注
     * 
     * @param id 用户之间关注主键
     * @return 用户之间关注
     */
    public UmsMemberFollow selectUmsMemberFollowById(Long id);

    /**
     * 查询用户之间关注列表
     * 
     * @param umsMemberFollow 用户之间关注
     * @return 用户之间关注集合
     */
    public List<UmsMemberFollow> selectUmsMemberFollowList(UmsMemberFollow umsMemberFollow);

    public List<UmsMemberFollow> selectUmsMemberFollowsByMemberAndFollowMemberIds(Long memberId, List<Long> followMemberIds);

    /**
     * 新增用户之间关注
     * 
     * @param umsMemberFollow 用户之间关注
     * @return 结果
     */
    public int insertUmsMemberFollow(UmsMemberFollow umsMemberFollow);

    /**
     * 修改用户之间关注
     * 
     * @param umsMemberFollow 用户之间关注
     * @return 结果
     */
    public int updateUmsMemberFollow(UmsMemberFollow umsMemberFollow);

    /**
     * 批量删除用户之间关注
     * 
     * @param ids 需要删除的用户之间关注主键集合
     * @return 结果
     */
    public int deleteUmsMemberFollowByIds(Long[] ids);

    /**
     * 删除用户之间关注信息
     * 
     * @param id 用户之间关注主键
     * @return 结果
     */
    public int deleteUmsMemberFollowById(Long id);

    /**
     * 查询用户关注数
     *
     * @param member_id 用户ID
     * @return 结果
     */
    public int selectUmsMemberFollowNumByMemberId(Long member_id);

    /**
     * 查询用户粉丝数
     *
     * @param member_id 用户ID
     * @return 结果
     */
    public int selectUmsMemberFansNumByMemberId(Long member_id);
}
