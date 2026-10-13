package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.dto.MerchantSearchQuery;

import java.util.List;

/**
 * 商家Service接口
 * 
 * @author ruoyi
 * @date 2025-08-22
 */
public interface IMmsMerchantService 
{
    /**
     * 查询商家
     * 
     * @param id 商家主键
     * @return 商家
     */
    public MmsMerchant selectMmsMerchantById(Long id);

    public MmsMerchant selectMmsMerchantByMemberId(Long id);


    /**
     * 查询商家列表
     * 
     * @param mmsMerchant 商家
     * @return 商家集合
     */
    public List<MmsMerchant> selectMmsMerchantList(MmsMerchant mmsMerchant);

    /**
     * 商家搜索/发现列表(E5)按距离升序:同筛选口径,追加直线距离升序,无坐标商家排最后。
     * 仅在搜索者定位(originLat/originLng)都非空时使用。
     *
     * @param query 含搜索者定位的商家查询
     * @return 商家集合(含 distance,米)
     */
    public List<MerchantSearchQuery> selectMerchantListByDistance(MerchantSearchQuery query);

    /**
     * 新增商家
     * 
     * @param mmsMerchant 商家
     * @return 结果
     */
    public int insertMmsMerchant(MmsMerchant mmsMerchant);

    /** 商家生效事务内完成身份互斥校验与切换；不得脱离商家写入单独调用。 */
    void activateMerchantOwner(Long memberId);

    /**
     * 修改商家
     * 
     * @param mmsMerchant 商家
     * @return 结果
     */
    public int updateMmsMerchant(MmsMerchant mmsMerchant);

    /**
     * 小程序商家经营资料写。实现先锁当前会员再锁商家行，按当前 Owner/operator、会员状态和权限
     * 重新授权，不能信任 Controller 事务外取得的旧访问上下文。
     */
    public int updateAuthorizedProfile(MmsMerchant mmsMerchant, Long principalMemberId, String requiredPermission);

    /**
     * 平台停用/启用商家账号(R9-35)。
     * <p>停用(accountStatus=2)必须携带非空停用原因，写入 disable_reason;
     * 启用(accountStatus=1)清空 disable_reason。审核通过/驳回只写 reson，不经本方法，
     * 两者不复用。实现先锁商家行再按固定列白名单更新。
     *
     * @param id 商家主键
     * @param accountStatus 目标账号状态，仅接受 1(启用)/2(停用)
     * @param disableReason 停用原因(仅停用时有意义，最长 500 字)
     * @param updateBy 操作人
     * @return 更新行数
     */
    public int updatePlatformAccountStatus(Long id, Long accountStatus, String disableReason, String updateBy);

    /**
     * 当前 Owner 自助重提入驻资料。先锁会员再锁商家，锁后重新校验 Owner，
     * 且只允许更新入驻资料字段，不得修改 memberId/accountStatus 等后台字段。
     *
     * @param mmsMerchant 自助资料 patch
     * @param expectedOwnerMemberId 发起请求的 Owner 会员 ID
     * @return 更新行数
     */
    public int updateOwnerRegistrationProfile(MmsMerchant mmsMerchant, Long expectedOwnerMemberId);

    /**
     * 批量删除商家
     * 
     * @param ids 需要删除的商家主键集合
     * @return 结果
     */
    public int deleteMmsMerchantByIds(Long[] ids);

    /**
     * 删除商家信息
     * 
     * @param id 商家主键
     * @return 结果
     */
    public int deleteMmsMerchantById(Long id);
}
