package com.chengyinhub.business.service.impl;

import java.util.Collections;
import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import com.chengyinhub.business.mapper.UmsMemberAddressMapper;
import com.chengyinhub.business.domain.UmsMemberAddress;
import com.chengyinhub.business.service.IUmsMemberAddressService;

/**
 * 【请填写功能名称】Service业务层处理
 * 
 * @author ruoyi
 * @date 2025-08-26
 */
@Service
public class UmsMemberAddressServiceImpl implements IUmsMemberAddressService 
{
    @Autowired
    private UmsMemberAddressMapper umsMemberAddressMapper;

    /**
     * 查询【请填写功能名称】
     * 
     * @param id 【请填写功能名称】主键
     * @return 【请填写功能名称】
     */
    @Override
    public UmsMemberAddress selectUmsMemberAddressById(Long id)
    {
        return umsMemberAddressMapper.selectUmsMemberAddressById(id);
    }

    @Override
    public UmsMemberAddress selectOwnedAddressById(Long id, Long memberId)
    {
        if (!validId(id) || !validId(memberId)) {
            return null;
        }
        return umsMemberAddressMapper.selectOwnedAddressById(id, memberId);
    }

    @Override
    public List<UmsMemberAddress> selectOwnedAddressList(Long memberId)
    {
        if (!validId(memberId)) {
            return Collections.emptyList();
        }
        return umsMemberAddressMapper.selectOwnedAddressList(memberId);
    }

    /**
     * 查询【请填写功能名称】列表
     * 
     * @param umsMemberAddress 【请填写功能名称】
     * @return 【请填写功能名称】
     */
    @Override
    public List<UmsMemberAddress> selectUmsMemberAddressList(UmsMemberAddress umsMemberAddress)
    {
        return umsMemberAddressMapper.selectUmsMemberAddressList(umsMemberAddress);
    }

    /**
     * 新增【请填写功能名称】
     * 
     * @param umsMemberAddress 【请填写功能名称】
     * @return 结果
     */
    @Override
    public int insertUmsMemberAddress(UmsMemberAddress umsMemberAddress)
    {
        umsMemberAddress.setCreateTime(DateUtils.getNowDate());
        return umsMemberAddressMapper.insertUmsMemberAddress(umsMemberAddress);
    }

    @Override
    @Transactional
    public int insertOwnedAddress(UmsMemberAddress address, Long memberId)
    {
        if (address == null || !validId(memberId)) {
            return 0;
        }
        address.setId(null);
        address.setMemberId(memberId);
        address.setDelFlag(0);
        address.setCreateTime(DateUtils.getNowDate());
        if (Integer.valueOf(1).equals(address.getIsDefault())) {
            umsMemberAddressMapper.lockMemberAddressScope(memberId);
        }
        int rows = umsMemberAddressMapper.insertUmsMemberAddress(address);
        if (rows == 1 && Integer.valueOf(1).equals(address.getIsDefault())) {
            umsMemberAddressMapper.clearOtherOwnedDefaults(address.getId(), memberId);
        }
        return rows;
    }

    @Override
    @Transactional
    public UmsMemberAddress insertOwnedAddressIdempotent(UmsMemberAddress address, Long memberId, String requestId)
    {
        if (address == null || !validId(memberId)) {
            return null;
        }
        // 兼容尚未更新到 requestId 协议的旧小程序版本；新版本仍由唯一键提供强幂等。
        if (requestId == null || requestId.trim().isEmpty()) {
            return insertOwnedAddress(address, memberId) == 1 ? address : null;
        }
        if (!requestId.matches("[A-Za-z0-9_-]{16,64}")) return null;
        UmsMemberAddress existing = umsMemberAddressMapper.selectOwnedAddressByRequestId(memberId, requestId);
        if (existing != null) {
            return existing;
        }
        address.setRequestId(requestId);
        try {
            return insertOwnedAddress(address, memberId) == 1 ? address : null;
        } catch (DuplicateKeyException e) {
            return umsMemberAddressMapper.selectOwnedAddressByRequestId(memberId, requestId);
        }
    }

    /**
     * 修改【请填写功能名称】
     * 
     * @param umsMemberAddress 【请填写功能名称】
     * @return 结果
     */
    @Override
    public int updateUmsMemberAddress(UmsMemberAddress umsMemberAddress)
    {
        umsMemberAddress.setUpdateTime(DateUtils.getNowDate());
        return umsMemberAddressMapper.updateUmsMemberAddress(umsMemberAddress);
    }

    @Override
    @Transactional
    public int updateOwnedAddress(UmsMemberAddress address, Long memberId)
    {
        if (address == null || !validId(address.getId()) || !validId(memberId)) {
            return 0;
        }
        address.setUpdateTime(DateUtils.getNowDate());
        if (Integer.valueOf(1).equals(address.getIsDefault())) {
            umsMemberAddressMapper.lockMemberAddressScope(memberId);
        }
        int rows = umsMemberAddressMapper.updateOwnedAddress(address, memberId);
        if (rows == 1 && Integer.valueOf(1).equals(address.getIsDefault())) {
            umsMemberAddressMapper.clearOtherOwnedDefaults(address.getId(), memberId);
        }
        return rows;
    }

    /**
     * 批量删除【请填写功能名称】
     * 
     * @param ids 需要删除的【请填写功能名称】主键
     * @return 结果
     */
    @Override
    public int deleteUmsMemberAddressByIds(Long[] ids)
    {
        return umsMemberAddressMapper.deleteUmsMemberAddressByIds(ids);
    }

    /**
     * 删除【请填写功能名称】信息
     * 
     * @param id 【请填写功能名称】主键
     * @return 结果
     */
    @Override
    public int deleteUmsMemberAddressById(Long id)
    {
        return umsMemberAddressMapper.deleteUmsMemberAddressById(id);
    }

    @Override
    public int deleteOwnedAddress(Long id, Long memberId)
    {
        if (!validId(id) || !validId(memberId)) {
            return 0;
        }
        return umsMemberAddressMapper.deleteOwnedAddress(id, memberId);
    }


    /**
     * 更新用户地址是否默认
     *
     * @param umsMemberAddress 【请填写功能名称】
     * @return 结果
     */
    @Override
    public int updateUmsMemberAddressIsDefault(UmsMemberAddress umsMemberAddress)
    {
        umsMemberAddress.setUpdateTime(DateUtils.getNowDate());
        return umsMemberAddressMapper.updateUmsMemberAddressIsDefault(umsMemberAddress);
    }

    @Override
    @Transactional
    public int setOwnedAddressDefault(Long id, Long memberId)
    {
        if (!validId(id) || !validId(memberId)) {
            return 0;
        }
        umsMemberAddressMapper.lockMemberAddressScope(memberId);
        int rows = umsMemberAddressMapper.setOwnedAddressDefault(id, memberId);
        if (rows == 1) {
            umsMemberAddressMapper.clearOtherOwnedDefaults(id, memberId);
        }
        return rows;
    }

    private boolean validId(Long id)
    {
        return id != null && id > 0;
    }
}
