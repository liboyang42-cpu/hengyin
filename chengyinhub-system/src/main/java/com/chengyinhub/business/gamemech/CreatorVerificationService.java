package com.chengyinhub.business.gamemech;

import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.mapper.MemberCapabilityMapper;
import com.chengyinhub.business.service.impl.RoleServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * G2 / GAM-12: operators mark a player creator as verified ({@code member_capability.verified_creator}).
 * The mark only changes permissions when {@code creator.player.verified_unlock=true} (default false).
 */
@Service
public class CreatorVerificationService {
    private final MemberCapabilityMapper capabilities;

    @Autowired
    public CreatorVerificationService(MemberCapabilityMapper capabilities) {
        this.capabilities = capabilities;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> set(Long memberId, boolean verified, Long actorId) {
        if (memberId == null || memberId <= 0) throw new ServiceException("参数有误", 400);
        String cap = RoleServiceImpl.CAP_VERIFIED_CREATOR;
        MemberCapability existing = capabilities.selectByMemberAndCapability(memberId, cap);
        if (existing == null) {
            if (verified) {
                MemberCapability row = new MemberCapability();
                row.setMemberId(memberId);
                row.setCapability(cap);
                row.setStatus(1);
                row.setGrantedAt(new Date());
                row.setGrantedBy(actorId);
                if (capabilities.insertMemberCapability(row) != 1) throw new ServiceException("保存失败");
            }
        } else if (!Integer.valueOf(verified ? 1 : 0).equals(existing.getStatus())) {
            if (capabilities.updateStatus(memberId, cap, verified ? 1 : 0, actorId) != 1) throw new ServiceException("保存失败");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("memberId", memberId);
        out.put("verifiedCreator", verified);
        return out;
    }
}
