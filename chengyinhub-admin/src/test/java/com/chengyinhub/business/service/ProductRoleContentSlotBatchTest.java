package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.mapper.ProductRoleMapper;
import com.chengyinhub.business.service.impl.ProductRoleServiceImpl;
import com.chengyinhub.common.constant.ProductSlot;
import com.chengyinhub.common.constant.RoleEntityType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 批量读取列表页 CONTENT 槽时，异常主体必须保留给界面标红，不能伪装成“无槽”。 */
class ProductRoleContentSlotBatchTest {

    @Test
    void batchProjection_preservesAbnormalContentEntityForFailClosedDisplay() {
        ProductRoleMapper mapper = mock(ProductRoleMapper.class);
        ProductRoleServiceImpl service = new ProductRoleServiceImpl();
        ReflectionTestUtils.setField(service, "productRoleMapper", mapper);

        ProductRole platform = role(1L, RoleEntityType.PLATFORM, null);
        ProductRole club = role(2L, RoleEntityType.CLUB, 9L);
        ProductRole merchant = role(3L, RoleEntityType.MERCHANT, 8L);
        when(mapper.selectActiveContentByTopicIds(Arrays.asList(1L, 2L, 3L)))
                .thenReturn(Arrays.asList(platform, club, merchant));

        Map<Long, ProductRole> slots = service.activeContentSlots(Arrays.asList(1L, 2L, 3L));

        assertEquals(RoleEntityType.PLATFORM, slots.get(1L).getEntityType());
        assertEquals(RoleEntityType.CLUB, slots.get(2L).getEntityType());
        assertEquals(RoleEntityType.MERCHANT, slots.get(3L).getEntityType(),
                "异常 CONTENT 槽必须让列表显示“主体异常”，不能被伪装成历史待归属");
    }

    private ProductRole role(Long topicId, String entityType, Long entityId) {
        ProductRole role = new ProductRole();
        role.setTopicId(topicId);
        role.setSlot(ProductSlot.CONTENT);
        role.setEntityType(entityType);
        role.setEntityId(entityId);
        role.setStatus(1);
        return role;
    }
}
