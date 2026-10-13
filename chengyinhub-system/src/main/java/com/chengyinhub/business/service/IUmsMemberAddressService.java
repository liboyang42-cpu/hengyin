package com.chengyinhub.business.service;
import java.util.List;
import com.chengyinhub.business.domain.UmsMemberAddress;

/**
 * 【请填写功能名称】Service接口
 * 
 * @author ruoyi
 * @date 2025-08-26
 */
public interface IUmsMemberAddressService 
{
    /**
     * 查询【请填写功能名称】
     * 
     * @param id 【请填写功能名称】主键
     * @return 【请填写功能名称】
     */
    public UmsMemberAddress selectUmsMemberAddressById(Long id);

    UmsMemberAddress selectOwnedAddressById(Long id, Long memberId);

    List<UmsMemberAddress> selectOwnedAddressList(Long memberId);

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

    int insertOwnedAddress(UmsMemberAddress umsMemberAddress, Long memberId);

    UmsMemberAddress insertOwnedAddressIdempotent(UmsMemberAddress umsMemberAddress, Long memberId, String requestId);

    /**
     * 修改【请填写功能名称】
     * 
     * @param umsMemberAddress 【请填写功能名称】
     * @return 结果
     */
    public int updateUmsMemberAddress(UmsMemberAddress umsMemberAddress);

    int updateOwnedAddress(UmsMemberAddress umsMemberAddress, Long memberId);

    /**
     * 批量删除【请填写功能名称】
     * 
     * @param ids 需要删除的【请填写功能名称】主键集合
     * @return 结果
     */
    public int deleteUmsMemberAddressByIds(Long[] ids);

    /**
     * 删除【请填写功能名称】信息
     * 
     * @param id 【请填写功能名称】主键
     * @return 结果
     */
    public int deleteUmsMemberAddressById(Long id);

    int deleteOwnedAddress(Long id, Long memberId);

    /**
     * 更新用户地址是否默认
     *
     * @param umsMemberAddress 【请填写功能名称】
     * @return 结果
     */
    public int updateUmsMemberAddressIsDefault(UmsMemberAddress umsMemberAddress);

    int setOwnedAddressDefault(Long id, Long memberId);
}
