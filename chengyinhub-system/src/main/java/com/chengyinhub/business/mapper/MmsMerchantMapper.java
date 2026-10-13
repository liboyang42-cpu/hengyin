package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.dto.MerchantSearchQuery;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 商家Mapper接口
 * 
 * @author ruoyi
 * @date 2025-08-22
 */
public interface MmsMerchantMapper 
{
    /**
     * 查询商家
     * 
     * @param id 商家主键
     * @return 商家
     */
    public MmsMerchant selectMmsMerchantById(Long id);

    public MmsMerchant selectMmsMerchantByMemberId(Long id);

    /** 调配/合作写入的事务内状态门禁，与停用更新共用商家行锁。 */
    public MmsMerchant selectMmsMerchantByMemberIdForUpdate(Long id);

    /** 员工邀请接受只需判定是否存在商家记录，避免同一会员多行时触发单行映射异常。 */
    public int countMmsMerchantRowsByMemberIdForUpdate(Long id);

    /** 商家写事务按行主键锁定主体状态；Owner 变更与团队写入共用该线性化点。 */
    public MmsMerchant selectMmsMerchantByIdForUpdate(Long id);

    /** 认领审核的所有权闸：仅未认领(NULL)且正常启用商家可被一次性写入。 */
    int claimOwnerCas(@Param("merchantId") Long merchantId,
                      @Param("claimantMemberId") Long claimantMemberId,
                      @Param("auditorId") Long auditorId);

    /** 主题工作台批量读取候选商家显示名，避免按报名逐条查商家。 */
    public List<MmsMerchant> selectMerchantNamesByMemberIds(@Param("memberIds") List<Long> memberIds);


    /**
     * 查询商家列表
     * 
     * @param mmsMerchant 商家
     * @return 商家集合
     */
    public List<MmsMerchant> selectMmsMerchantList(MmsMerchant mmsMerchant);

    /**
     * 商家搜索/发现列表(E5)按距离升序:与 selectMmsMerchantList 同筛选,
     * 追加 Haversine 直线距离并升序;无坐标商家 distance 为 null 排最后。
     * 仅在 originLat/originLng 都非空时调用。
     *
     * @param query 含搜索者定位(originLat/originLng)的商家查询
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

    /**
     * 修改商家
     * 
     * @param mmsMerchant 商家
     * @return 结果
     */
    public int updateMmsMerchant(MmsMerchant mmsMerchant);

    /** 承接设置显式清空接待人数(共用 update 里 null=不改) */
    public int clearMerchantCapacity(MmsMerchant mmsMerchant);

    /** 招牌主推显式解除(共用 update 里 featured_id 的 null=不改,清不掉旧值) */
    public int clearMerchantFeatured(MmsMerchant mmsMerchant);

    /** Owner 自助入驻资料更新：只更新白名单字段，并在 WHERE 中再校验归属。 */
    public int updateOwnerRegistrationProfile(@Param("merchant") MmsMerchant merchant,
                                              @Param("expectedOwnerMemberId") Long expectedOwnerMemberId);

    /**
     * 平台停用/启用商家账号：只写 account_status 与 disable_reason 两列白名单，
     * 并在 WHERE 复核未删除。审核通过/驳回走 updateMmsMerchant(reson)，不经本语句。
     */
    public int updatePlatformAccountStatus(@Param("id") Long id,
                                           @Param("accountStatus") Long accountStatus,
                                           @Param("disableReason") String disableReason,
                                           @Param("updateBy") String updateBy,
                                           @Param("updateTime") Date updateTime);

    /**
     * 删除商家
     * 
     * @param id 商家主键
     * @return 结果
     */
    public int deleteMmsMerchantById(Long id);

    /**
     * 批量删除商家
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteMmsMerchantByIds(Long[] ids);

    /** 内容安全:清空商家资料图片(logo/封面/相册),违规图回调下架用 */
    public int clearProfileMedia(@Param("id") Long id);
}
