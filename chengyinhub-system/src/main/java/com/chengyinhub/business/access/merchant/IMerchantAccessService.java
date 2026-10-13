package com.chengyinhub.business.access.merchant;

public interface IMerchantAccessService {
    /** 有有效 Owner/员工关系时返回上下文；完全没有商家关系时返回 null；脏/停用关系 fail closed。 */
    MerchantAccessContext resolve(Long principalMemberId);

    MerchantAccessContext require(Long principalMemberId, String permission);
}
