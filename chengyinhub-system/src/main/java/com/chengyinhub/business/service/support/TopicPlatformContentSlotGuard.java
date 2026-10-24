package com.chengyinhub.business.service.support;

import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.common.constant.RoleEntityType;
import org.springframework.stereotype.Component;

/** Catalog adapter retaining the existing PLATFORM declaration/readback contract. */
@Component
public class TopicPlatformContentSlotGuard {
    private final IProductRoleService productRoleService;
    public TopicPlatformContentSlotGuard(IProductRoleService productRoleService) {
        this.productRoleService = productRoleService;
    }

    public void declareRequiredPlatformContentSlot(Long officialTopicId) {
        productRoleService.declareContentSlot(officialTopicId, RoleEntityType.PLATFORM, null);
        com.chengyinhub.business.domain.ProductRole persisted = productRoleService.activeContentSlot(officialTopicId);
        if (persisted == null
                || !RoleEntityType.PLATFORM.equals(persisted.getEntityType())
                || persisted.getEntityId() != null) {
            throw new IllegalStateException("平台主题内容主体落库失败，已取消本次创建或复制");
        }
    }
}
