package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.mapper.MemberCapabilityMapper;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 能力服务实现(P1)。
 * grant/revoke 幂等(靠 uk_member_capability 复用同一行,激活/撤销切换 status)。
 */
@Service
public class CapabilityServiceImpl implements ICapabilityService {

    public static final String CAP_PLAYER = "player";

    @Autowired
    private MemberCapabilityMapper memberCapabilityMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void grant(Long memberId, String capability, Long grantedBy) {
        if (memberId == null || StringUtils.isEmpty(capability)) {
            return;
        }
        MemberCapability exist = memberCapabilityMapper.selectByMemberAndCapability(memberId, capability);
        if (exist == null) {
            MemberCapability cap = new MemberCapability();
            cap.setMemberId(memberId);
            cap.setCapability(capability);
            cap.setStatus(1);
            cap.setGrantedAt(new Date());
            cap.setGrantedBy(grantedBy == null ? 0L : grantedBy);
            if (memberCapabilityMapper.insertMemberCapability(cap) != 1) {
                throw new ServiceException("会员能力授予失败");
            }
        } else if (exist.getStatus() == null || exist.getStatus() != 1) {
            if (memberCapabilityMapper.updateStatus(memberId, capability, 1, grantedBy == null ? 0L : grantedBy) != 1) {
                throw new ServiceException("会员能力启用失败");
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long memberId, String capability) {
        if (memberId == null || StringUtils.isEmpty(capability)) {
            return;
        }
        MemberCapability exist = memberCapabilityMapper.selectByMemberAndCapability(memberId, capability);
        if (exist != null && (exist.getStatus() == null || exist.getStatus() != 0)) {
            if (memberCapabilityMapper.updateStatus(memberId, capability, 0, 0L) != 1) {
                throw new ServiceException("会员能力撤销失败");
            }
        }
    }

    @Override
    public boolean hasCapability(Long memberId, String capability) {
        if (memberId == null || StringUtils.isEmpty(capability)) {
            return false;
        }
        if (CAP_PLAYER.equals(capability)) {
            return true; // 玩家能力隐式默认
        }
        List<MemberCapability> caps = memberCapabilityMapper.selectByMember(memberId);
        if (caps == null) return false;
        for (MemberCapability c : caps) {
            if (capability.equals(c.getCapability())) return true;
        }
        return false;
    }

    @Override
    public List<String> getCapabilities(Long memberId) {
        List<String> result = new ArrayList<>();
        result.add(CAP_PLAYER); // 玩家能力始终在
        if (memberId == null) return result;
        List<MemberCapability> caps = memberCapabilityMapper.selectByMember(memberId);
        if (caps != null) {
            for (MemberCapability c : caps) {
                result.add(c.getCapability());
            }
        }
        return result;
    }

    @Override
    public List<MemberCapability> listValid(Long memberId) {
        if (memberId == null) return new ArrayList<>();
        List<MemberCapability> caps = memberCapabilityMapper.selectByMember(memberId);
        return caps == null ? new ArrayList<>() : caps;
    }
}
