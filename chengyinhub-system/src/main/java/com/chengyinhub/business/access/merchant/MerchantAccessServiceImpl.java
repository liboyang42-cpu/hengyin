package com.chengyinhub.business.access.merchant;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.MerchantOperator;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MerchantAccessServiceImpl implements IMerchantAccessService {
    @Autowired
    private IMmsMerchantService merchantService;

    @Autowired
    private MerchantOperatorMapper merchantOperatorMapper;

    @Autowired
    private UmsMemberMapper umsMemberMapper;

    @Override
    public MerchantAccessContext require(Long principalMemberId, String permission) {
        if (principalMemberId == null || principalMemberId <= 0 || permission == null
                || !MerchantPermission.all().contains(permission)) {
            throw forbidden();
        }
        MerchantAccessContext context = resolve(principalMemberId);
        if (context == null || !context.hasPermission(permission)) throw forbidden();
        return context;
    }

    @Override
    public MerchantAccessContext resolve(Long principalMemberId) {
        if (principalMemberId == null || principalMemberId <= 0) throw forbidden();
        MmsMerchant ownerQuery = new MmsMerchant();
        ownerQuery.setMemberId(principalMemberId);
        ownerQuery.setStatus(1L);
        ownerQuery.setAccountStatus(1L);
        ownerQuery.setDelFlag(0);
        List<MmsMerchant> ownerRows = merchantService.selectMmsMerchantList(ownerQuery);
        if (ownerRows == null || ownerRows.size() > 1) throw forbidden();
        MmsMerchant merchant = ownerRows.isEmpty() ? null : ownerRows.get(0);
        if (merchant != null) {
            UmsMember owner = umsMemberMapper.selectUmsMemberById(principalMemberId);
            if (!isActiveOwner(merchant, principalMemberId)
                    || !isActiveMember(owner, principalMemberId)) throw forbidden();
            return new MerchantAccessContext(principalMemberId, merchant.getId(), merchant.getMemberId(),
                    MerchantRoleCode.MERCHANT_OWNER);
        }
        MerchantOperator operator = merchantOperatorMapper.selectActiveByMemberId(principalMemberId);
        if (operator == null) return null;
        if (operator.getId() == null || operator.getMerchantId() == null
                || !principalMemberId.equals(operator.getOperatorMemberId())
                || !MerchantOperator.STATUS_ACTIVE.equals(operator.getStatus())
                || !MerchantRoleCode.isOperatorRole(operator.getRoleCode())) throw forbidden();
        merchant = merchantService.selectMmsMerchantById(operator.getMerchantId());
        UmsMember member = umsMemberMapper.selectUmsMemberById(principalMemberId);
        if (!isActiveMerchant(merchant) || !isActiveMember(member, principalMemberId)) throw forbidden();
        MmsMerchant operatorOwnerQuery = new MmsMerchant();
        operatorOwnerQuery.setMemberId(merchant.getMemberId());
        operatorOwnerQuery.setStatus(1L);
        operatorOwnerQuery.setAccountStatus(1L);
        operatorOwnerQuery.setDelFlag(0);
        List<MmsMerchant> operatorOwnerRows = merchantService.selectMmsMerchantList(operatorOwnerQuery);
        if (operatorOwnerRows == null || operatorOwnerRows.size() != 1) throw forbidden();
        MmsMerchant ownerScopedMerchant = operatorOwnerRows.get(0);
        if (!operator.getMerchantId().equals(ownerScopedMerchant.getId())
                || !isActiveOwner(ownerScopedMerchant, merchant.getMemberId())) throw forbidden();
        return new MerchantAccessContext(principalMemberId, ownerScopedMerchant.getId(), ownerScopedMerchant.getMemberId(),
                operator.getRoleCode());
    }

    private boolean isActiveOwner(MmsMerchant merchant, Long principalMemberId) {
        return isActiveMerchant(merchant) && principalMemberId.equals(merchant.getMemberId());
    }

    private boolean isActiveMerchant(MmsMerchant merchant) {
        return merchant != null && merchant.getId() != null && merchant.getMemberId() != null
                && Long.valueOf(1L).equals(merchant.getStatus())
                && Long.valueOf(1L).equals(merchant.getAccountStatus())
                && Integer.valueOf(0).equals(merchant.getDelFlag());
    }

    private boolean isActiveMember(UmsMember member, Long principalMemberId) {
        return member != null && principalMemberId.equals(member.getId())
                && Long.valueOf(1L).equals(member.getStatus())
                && Integer.valueOf(0).equals(member.getDelFlag());
    }

    private ServiceException forbidden() {
        return new ServiceException("商家身份无效或权限不足", HttpStatus.FORBIDDEN);
    }
}
