package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.dto.MerchantSearchQuery;
import com.chengyinhub.business.domain.vo.PublicMerchantDiscoveryVO;
import com.chengyinhub.business.domain.vo.PublicMerchantVO;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E5 商家搜索 geo 距离排序:控制器路由/回退/脱敏(pure Mockito,不依赖 DB)。
 * SQL 层「按距离升序 + 无坐标排最后」由 MerchantGeoSortMyBatisIT 真库验证。
 */
class ApiMerchantSearchGeoTest {

    /** startPage() 依赖绑定的 Servlet 请求,单测里置空;搜索接口无需登录。 */
    private static class TestController extends ApiMerchantController {
        @Override
        protected void startPage() { /* no-op */ }
    }

    private MerchantSearchQuery merchant(long id, String phone, Double distance) {
        MerchantSearchQuery m = new MerchantSearchQuery();
        m.setId(id);
        m.setPhone(phone);
        m.setDistance(distance);
        return m;
    }

    // ② 不传定位 → 回退原排序:调 selectMmsMerchantList,绝不调距离排序
    @Test
    void noOrigin_fallsBackToPlainListAndNeverCallsGeo() {
        IMmsMerchantService svc = mock(IMmsMerchantService.class);
        ApiMerchantController controller = new TestController();
        ReflectionTestUtils.setField(controller, "mmsMerchantService", svc);

        MmsMerchant a = new MmsMerchant(); a.setId(1L);
        MmsMerchant b = new MmsMerchant(); b.setId(2L);
        when(svc.selectMmsMerchantList(any(MmsMerchant.class))).thenReturn(Arrays.asList(a, b));

        MerchantSearchQuery query = new MerchantSearchQuery();
        query.setName("咖啡");
        query.setMemberId(9527L);
        query.setPhone("13800000000");
        query.setWechat("private-wechat");
        query.setAddress("私密地址");
        query.setAccountStatus(0L);
        AjaxResult result = controller.merchantList(query);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        @SuppressWarnings("unchecked")
        List<PublicMerchantDiscoveryVO> data =
                (List<PublicMerchantDiscoveryVO>) result.get(AjaxResult.DATA_TAG);
        assertEquals(2, data.size());
        assertEquals(Long.valueOf(1L), data.get(0).getId(), "原排序保持不变");
        assertEquals(Long.valueOf(2L), data.get(1).getId());

        // 走原路径,且强制注入公开可见门禁,不允许用私密字段做存在性探测。
        ArgumentCaptor<MmsMerchant> captor = ArgumentCaptor.forClass(MmsMerchant.class);
        verify(svc).selectMmsMerchantList(captor.capture());
        assertEquals(Long.valueOf(1L), captor.getValue().getStatus());
        assertEquals(Long.valueOf(1L), captor.getValue().getAccountStatus());
        assertEquals(Integer.valueOf(0), captor.getValue().getDelFlag());
        assertNull(captor.getValue().getMemberId());
        assertNull(captor.getValue().getPhone());
        assertNull(captor.getValue().getWechat());
        assertNull(captor.getValue().getAddress());
        verify(svc, never()).selectMerchantListByDistance(any());
    }

    // ① 传定位 → 距离排序:调 selectMerchantListByDistance,透传 origin 坐标,顺序原样返回(升序)
    @Test
    void withOrigin_routesToDistanceSortAndPassesOriginThrough() {
        IMmsMerchantService svc = mock(IMmsMerchantService.class);
        ApiMerchantController controller = new TestController();
        ReflectionTestUtils.setField(controller, "mmsMerchantService", svc);

        // SQL 返回已按距离升序;控制器不得再打乱
        MerchantSearchQuery near = merchant(11L, "13800000000", 120.0);
        MerchantSearchQuery far = merchant(12L, "13900000000", 8000.0);
        when(svc.selectMerchantListByDistance(any(MerchantSearchQuery.class)))
                .thenReturn(Arrays.asList(near, far));

        MerchantSearchQuery query = new MerchantSearchQuery();
        query.setOriginLat(31.2304);
        query.setOriginLng(121.4737);
        AjaxResult result = controller.merchantList(query);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        @SuppressWarnings("unchecked")
        List<PublicMerchantDiscoveryVO> data =
                (List<PublicMerchantDiscoveryVO>) result.get(AjaxResult.DATA_TAG);
        assertEquals(Long.valueOf(11L), data.get(0).getId(), "近的在前");
        assertEquals(Long.valueOf(12L), data.get(1).getId(), "远的在后");
        assertEquals(120.0, data.get(0).getDistance(), 0.001);

        // origin 透传给距离查询
        ArgumentCaptor<MerchantSearchQuery> captor = ArgumentCaptor.forClass(MerchantSearchQuery.class);
        verify(svc).selectMerchantListByDistance(captor.capture());
        assertEquals(31.2304, captor.getValue().getOriginLat(), 0.0001);
        assertEquals(121.4737, captor.getValue().getOriginLng(), 0.0001);
        verify(svc, never()).selectMmsMerchantList(any());

        assertTrue(data.get(0) instanceof PublicMerchantVO, "匿名列表不得返回商家实体");
    }

    // ③ 传定位但存在无坐标商家(distance=null)→ 不崩、脱敏正常、无坐标排最后(顺序原样透传)
    @Test
    void withOrigin_coordlessMerchantIsLastAndDoesNotCrash() {
        IMmsMerchantService svc = mock(IMmsMerchantService.class);
        ApiMerchantController controller = new TestController();
        ReflectionTestUtils.setField(controller, "mmsMerchantService", svc);

        MerchantSearchQuery near = merchant(21L, "13811112222", 300.0);
        MerchantSearchQuery coordless = merchant(22L, "13833334444", null); // 无坐标 → distance null
        when(svc.selectMerchantListByDistance(any(MerchantSearchQuery.class)))
                .thenReturn(Arrays.asList(near, coordless));

        MerchantSearchQuery query = new MerchantSearchQuery();
        query.setOriginLat(30.0);
        query.setOriginLng(120.0);
        AjaxResult result = controller.merchantList(query);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        @SuppressWarnings("unchecked")
        List<PublicMerchantDiscoveryVO> data =
                (List<PublicMerchantDiscoveryVO>) result.get(AjaxResult.DATA_TAG);
        assertEquals(2, data.size());
        assertEquals(Long.valueOf(21L), data.get(0).getId(), "有坐标在前");
        assertEquals(Long.valueOf(22L), data.get(1).getId(), "无坐标排最后");
        assertNull(data.get(1).getDistance());
        assertTrue(data.get(1) instanceof PublicMerchantVO);
    }
}
