package com.chengyinhub.business.mapper;


import java.util.List;
import com.chengyinhub.business.domain.UmsMemberFollow;
import org.apache.ibatis.annotations.Param;

/**
 * 用户之间关注Mapper接口
 * 
 * @author ruoyi
 * @date 2025-08-26
 */
public interface UmsMemberFollowMapper 
{
    /**
     * 查询用户之间关注
     * 
     * @param id 用户之间关注主键
     * @return 用户之间关注
     */
    public UmsMemberFollow selectUmsMemberFollowById(Long id);

    /** 关系行锁:同一关系的并发编辑在此串行化,保证重校验拿到的 old owner 稳定。 */
    public UmsMemberFollow selectUmsMemberFollowByIdForUpdate(Long id);

    /** 建立关注关系前的目标存在性(轻量、非锁读;取消/维护遗留关系不调用)。 */
    public Long selectExistingMemberId(@org.apache.ibatis.annotations.Param("memberId") Long memberId);

    /**
     * 查询用户之间关注列表
     * 
     * @param umsMemberFollow 用户之间关注
     * @return 用户之间关注集合
     */
    public List<UmsMemberFollow> selectUmsMemberFollowList(UmsMemberFollow umsMemberFollow);

    public List<UmsMemberFollow> selectUmsMemberFollowsByMemberAndFollowMemberIds(@Param("memberId") Long memberId,
                                                                                  @Param("followMemberIds") List<Long> followMemberIds);

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
     * 删除用户之间关注
     * 
     * @param id 用户之间关注主键
     * @return 结果
     */
    public int deleteUmsMemberFollowById(Long id);

    /**
     * 批量删除用户之间关注
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteUmsMemberFollowByIds(Long[] ids);


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
