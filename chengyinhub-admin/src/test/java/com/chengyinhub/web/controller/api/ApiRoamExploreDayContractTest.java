package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.vo.RoamExploreDayCandidateVO;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ApiRoamExploreDayContractTest {

    @Test
    void endpoint只接受经纬度客户端伪造状态审核owner产品参数不能扩大集合() throws Exception {
        Method method = ApiRoamController.class.getMethod("nearbyExploreDay", Double.class, Double.class);
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertNotNull(mapping);
        assertArrayEquals(new String[]{"/nearby-exploreday"}, mapping.value());
        assertEquals(2, method.getParameterCount());
        assertArrayEquals(new Class<?>[]{Double.class, Double.class}, method.getParameterTypes());
        assertEquals("lat", method.getParameters()[0].getAnnotation(RequestParam.class).value());
        assertEquals("lng", method.getParameters()[1].getAnnotation(RequestParam.class).value());
    }

    @Test
    void 响应类型只能是窄活动壳VO() throws Exception {
        Method method = Class.forName("com.chengyinhub.business.service.IRoamExploreDayCandidateService")
                .getMethod("findNearestEligible", double.class, double.class);
        assertEquals(RoamExploreDayCandidateVO.class, method.getReturnType());
    }
}
