package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.MemberCollectible;
import com.chengyinhub.business.mapper.MemberCollectibleMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiCityNodeControllerStatusTest {

    private ApiCityNodeController controller(RoamMapper roamMapper, MemberCollectibleMapper collectibleMapper) {
        ApiCityNodeController controller = new ApiCityNodeController() {
            @Override
            public Long getAppUserId() {
                return 1001L;
            }
        };
        ReflectionTestUtils.setField(controller, "roamMapper", roamMapper);
        ReflectionTestUtils.setField(controller, "collectibleMapper", collectibleMapper);
        return controller;
    }

    @Test
    void unavailableCityNodeReturnsGoneForFavoriteAndComplete() {
        RoamMapper roamMapper = mock(RoamMapper.class);
        MemberCollectibleMapper collectibleMapper = mock(MemberCollectibleMapper.class);
        when(collectibleMapper.countOne(1001L, MemberCollectible.TYPE_NODE_FAV, 992021L)).thenReturn(0);
        when(roamMapper.selectPlayerMerchantNodeDetail(992021L, 0L)).thenReturn(null);

        ApiCityNodeController controller = controller(roamMapper, collectibleMapper);

        AjaxResult favResult = controller.favorite(992021L);
        assertEquals(410, favResult.get(AjaxResult.CODE_TAG));

        AjaxResult completeResult = controller.complete(992021L, 31.23, 121.47, null, null, null);
        assertEquals(410, completeResult.get(AjaxResult.CODE_TAG));
    }
}
