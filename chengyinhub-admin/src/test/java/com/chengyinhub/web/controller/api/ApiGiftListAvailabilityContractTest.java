package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.PmsGift;
import com.chengyinhub.business.service.IPmsGiftService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C 端只可见可赠送且未删除的礼物；Controller 传参和 Mapper SQL 缺一不可。
 */
class ApiGiftListAvailabilityContractTest {

    private ApiGiftController controller;
    private IPmsGiftService giftService;

    @BeforeEach
    void setUp() {
        controller = new ApiGiftController();
        giftService = mock(IPmsGiftService.class);
        ReflectionTestUtils.setField(controller, "pmsGiftService", giftService);
    }

    @Test
    void allGiftListUsesTheDedicatedAvailableCatalog() {
        when(giftService.selectAvailablePmsGiftList()).thenReturn(Collections.<PmsGift>emptyList());

        AjaxResult result = controller.giftList("1");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(giftService).selectAvailablePmsGiftList();
    }

    @Test
    void giftMapperActuallyAppliesTheLogicalDeleteFilter() throws Exception {
        Path mapper = findRepositoryRoot().resolve("chengyinhub-system/src/main/resources/mapper/business/PmsGiftMapper.xml");
        String xml = new String(Files.readAllBytes(mapper), StandardCharsets.UTF_8);

        assertTrue(xml.contains("<if test=\"delFlag != null \"> and del_flag = #{delFlag}</if>"));
        assertTrue(xml.contains("<select id=\"selectAvailablePmsGiftList\""));
        assertTrue(xml.contains("and point &gt; 0"));
        assertTrue(xml.contains("and point &lt;= 2147483647"));
    }

    private Path findRepositoryRoot() {
        Path cursor = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            if (Files.exists(cursor.resolve("chengyinhub-system/src/main/resources/mapper/business/PmsGiftMapper.xml"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new AssertionError("未找到仓库根目录");
    }
}
