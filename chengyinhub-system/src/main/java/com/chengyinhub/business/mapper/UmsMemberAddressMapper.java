package com.chengyinhub.business.mapper;


import java.util.List;
import com.chengyinhub.business.domain.UmsMemberAddress;
import org.apache.ibatis.annotations.Param;

/**
 * 【请填写功能名称】Mapper接口
 * 
 * @author ruoyi
 * @date 2025-08-26
 */
public interface UmsMemberAddressMapper 
{
    /**
     * 查询【请填写功能名称】
     * 
     * @param id 【请填写功能名称】主键
     * @return 【请填写功能名称】
     */
    public UmsMemberAddress selectUmsMemberAddressById(Long id);

    UmsMemberAddress selectOwnedAddressById(@Param("id") Long id, @Param("memberId") Long memberId);

    List<UmsMemberAddress> selectOwnedAddressList(@Param("memberId") Long memberId);

    UmsMemberAddress selectOwnedAddressByRequestId(@Param("memberId") Long memberId,
                                                   @Param("requestId") String requestId);

    Long lockMemberAddressScope(@Param("memberId") Long memberId);

    /**
     * 锁定当前会员仍有效的收货地址，结算期间防止地址被并发删除或越权替换。
     */
    UmsMemberAddress lockActiveUmsMemberAddressByIdAndMemberId(@Param("id") Long id,
                                                               @Param("memberId") Long memberId);

    /**
     * 查询【请填写功能名称】列表
     * 
     * @param umsMemberAddress 【请填写功能名称】
     * @return 【请填写功能名称】集合
     */
    public List<UmsMemberAddress> selectUmsMemberAddressList(UmsMemberAddress umsMemberAddress);

    /**
     * 新增【请填写功能名称】
     * 
     * @param umsMemberAddress 【请填写功能名称】
     * @return 结果
     */
    public int insertUmsMemberAddress(UmsMemberAddress umsMemberAddress);

    /**
     * 修改【请填写功能名称】
     * 
     * @param umsMemberAddress 【请填写功能名称】
     * @return 结果
     */
    public int updateUmsMemberAddress(UmsMemberAddress umsMemberAddress);

    int updateOwnedAddress(@Param("address") UmsMemberAddress address,
                           @Param("memberId") Long memberId);

    /**
     * 删除【请填写功能名称】
     * 
     * @param id 【请填写功能名称】主键
     * @return 结果
     */
    public int deleteUmsMemberAddressById(Long id);

    int deleteOwnedAddress(@Param("id") Long id, @Param("memberId") Long memberId);

    /**
     * 批量删除【请填写功能名称】
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteUmsMemberAddressByIds(Long[] ids);


    /**
     * 修改【请填写功能名称】
     *
     * @param umsMemberAddress 【请填写功能名称】
     * @return 结果
     */
    public int updateUmsMemberAddressIsDefault(UmsMemberAddress umsMemberAddress);

    int setOwnedAddressDefault(@Param("id") Long id, @Param("memberId") Long memberId);

    int clearOtherOwnedDefaults(@Param("id") Long id, @Param("memberId") Long memberId);

}
