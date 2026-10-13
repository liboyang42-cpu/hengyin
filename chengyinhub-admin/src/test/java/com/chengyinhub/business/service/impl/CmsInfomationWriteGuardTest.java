package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.CmsInfomationMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 物理删除要等待路由、前端、权限和数据写入方的四重退役检查；当前服务层必须拒绝直调。
 */
class CmsInfomationWriteGuardTest {

    private CmsInfomationServiceImpl service;
    private CmsInfomationMapper mapper;

    @BeforeEach
    void setUp() {
        service = new CmsInfomationServiceImpl();
        mapper = mock(CmsInfomationMapper.class);
        ReflectionTestUtils.setField(service, "cmsInfomationMapper", mapper);
    }

    @Test
    void physicalDeleteIsDisabledWhileMiniProgramStillReadsGuides() {
        assertThrows(ServiceException.class, () -> service.deleteCmsInfomationById(7L));
        assertThrows(ServiceException.class, () -> service.deleteCmsInfomationByIds(new Long[] { 7L, 8L }));

        verify(mapper, never()).deleteCmsInfomationById(any(Long.class));
        verify(mapper, never()).deleteCmsInfomationByIds(any(Long[].class));
    }
}
