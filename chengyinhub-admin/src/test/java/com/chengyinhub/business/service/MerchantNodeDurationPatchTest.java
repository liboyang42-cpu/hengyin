package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.domain.vo.MemberTemplateVO;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTemplateLibraryMapper;
import com.chengyinhub.business.service.impl.TemplatePublishServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanUtils;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 商家时长映射回归：真实 Spring/MyBatis，依赖用内存代理；无 Spring 容器、DB 或 HTTP。 */
class MerchantNodeDurationPatchTest {
    private static final Long OWNER = 7L;
    private static final Long TEMPLATE_ID = 91L;
    private static final String MAPPER = "com.chengyinhub.business.mapper.CmsMemberTemplateMapper.";
    private static final Pattern DURATION_ASSIGNMENT = Pattern.compile("(?i)\\bduration\\s*=");
    private static Configuration mapperConfiguration;

    @BeforeAll
    static void parseActualMapperWithoutDatabase() throws Exception {
        mapperConfiguration = new Configuration();
        mapperConfiguration.getTypeAliasRegistry().registerAlias("CmsMemberTemplate", CmsMemberTemplate.class);
        mapperConfiguration.getTypeAliasRegistry().registerAlias("MemberTemplateVO", MemberTemplateVO.class);
        String resource = "mapper/business/CmsMemberTemplateMapper.xml";
        try (InputStream input = MerchantNodeDurationPatchTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, "必须读取仓库真实 mapper，不以复制 SQL 代替");
            new XMLMapperBuilder(input, mapperConfiguration, resource,
                    mapperConfiguration.getSqlFragments()).parse();
        }
        assertNull(mapperConfiguration.getEnvironment(), "离线 BoundSql 测试不配置数据源");
    }

    @Test
    void springBeanUtilsDoesNotConvertLongDurationToInteger() {
        CmsMemberTemplate input = input(12L);
        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        BeanUtils.copyProperties(input, request);
        assertEquals(input.getTitle(), request.getTitle(), "兼容类型仍正常复制");
        assertNull(request.getDuration(), "Long 与 Integer 不可直接赋值，必须显式映射");
    }

    @ParameterizedTest
    @ValueSource(longs = {12L, 0L, -1L, -2147483648L, 2147483647L})
    void createPreservesExactSigned32Minutes(long minutes) throws Exception {
        Fixture fixture = new Fixture(null);
        assertEquals(TEMPLATE_ID, fixture.service.saveMerchantNodeTemplate(input(minutes), OWNER, "merchant7"));
        assertEquals(Long.valueOf(minutes), fixture.saved.getDuration(), "单位仍为分钟，不乘除 60");
        assertEquals(Collections.singletonList("insertCmsMemberTemplate"), fixture.calls);
        assertDurationBinding("insertCmsMemberTemplate", fixture.saved, minutes);
        assertServerOwnedFields(fixture.saved);
    }

    @ParameterizedTest
    @ValueSource(longs = {12L, 0L, -1L, -2147483648L, 2147483647L})
    void updatePreservesExactSigned32Minutes(long minutes) throws Exception {
        Fixture fixture = new Fixture(existing(45L));
        CmsMemberTemplate input = input(minutes);
        input.setId(TEMPLATE_ID);
        assertEquals(TEMPLATE_ID, fixture.service.saveMerchantNodeTemplate(input, OWNER, "merchant7"));
        assertEquals(Long.valueOf(minutes), fixture.saved.getDuration());
        assertDurationBinding("updateCmsMemberTemplate", fixture.saved, minutes);
        assertTrue(DURATION_ASSIGNMENT.matcher(sql("updateCmsMemberTemplate", fixture.saved).getSql()).find());
        assertEquals("selectCmsMemberTemplateByIdsForUpdate", fixture.calls.get(0));
        assertEquals("updateCmsMemberTemplate", fixture.calls.get(fixture.calls.size() - 1));
        assertServerOwnedFields(fixture.saved);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"duration\":null}"})
    void createMissingOrNullDurationKeepsExistingZeroDefault(String json) throws Exception {
        Fixture fixture = new Fixture(null);
        CmsMemberTemplate input = new ObjectMapper().readValue(json, CmsMemberTemplate.class);
        fixture.service.saveMerchantNodeTemplate(input, OWNER, "merchant7");
        assertEquals(Long.valueOf(0L), fixture.saved.getDuration());
        assertDurationBinding("insertCmsMemberTemplate", fixture.saved, 0L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"duration\":null}"})
    void updateMissingOrNullDurationOmitsColumnForBothExistingValueAndNull(String json) throws Exception {
        for (Long oldMinutes : new Long[] {45L, null}) {
            CmsMemberTemplate existing = existing(oldMinutes);
            Fixture fixture = new Fixture(existing);
            CmsMemberTemplate input = new ObjectMapper().readValue(json, CmsMemberTemplate.class);
            input.setId(TEMPLATE_ID);
            fixture.service.saveMerchantNodeTemplate(input, OWNER, "merchant7");
            assertNull(fixture.saved.getDuration(), "更新缺失值不能经过公共 DTO 默认值写成 0");
            BoundSql bound = sql("updateCmsMemberTemplate", fixture.saved);
            assertFalse(DURATION_ASSIGNMENT.matcher(bound.getSql()).find(), bound.getSql());
            assertEquals(0L, bound.getParameterMappings().stream()
                    .filter(mapping -> "duration".equals(mapping.getProperty())).count());
            assertEquals(oldMinutes, existing.getDuration(), "锁定原对象也不得被改写");
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {-2147483649L, 2147483648L, Long.MIN_VALUE, Long.MAX_VALUE})
    void createRejectsOverflowBeforeAnyCollaboratorCall(long minutes) throws Exception {
        Fixture fixture = new Fixture(null);
        ServiceException failure = assertThrows(ServiceException.class,
                () -> fixture.service.saveMerchantNodeTemplate(input(minutes), OWNER, "merchant7"));
        assertEquals("时长超出支持范围", failure.getMessage());
        assertNull(fixture.saved);
        assertTrue(fixture.calls.isEmpty(), fixture.calls.toString());
    }

    @ParameterizedTest
    @ValueSource(longs = {-2147483649L, 2147483648L, Long.MIN_VALUE, Long.MAX_VALUE})
    void updateRejectsOverflowAfterOwnerCheckBeforeProjectionAuditOrTemplateWrites(long minutes) throws Exception {
        CmsMemberTemplate existing = existing(45L);
        Fixture fixture = new Fixture(existing);
        CmsMemberTemplate input = input(minutes);
        input.setId(TEMPLATE_ID);
        ServiceException failure = assertThrows(ServiceException.class,
                () -> fixture.service.saveMerchantNodeTemplate(input, OWNER, "merchant7"));
        assertEquals("时长超出支持范围", failure.getMessage());
        assertEquals(Collections.singletonList("selectCmsMemberTemplateByIdsForUpdate"), fixture.calls);
        assertNull(fixture.saved);
        assertEquals(Long.valueOf(45L), existing.getDuration());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "deleted", "otherOwner", "notMerchantNode"})
    void ownerAndStateRejectionStillPrecedesDurationValidation(String reason) throws Exception {
        CmsMemberTemplate existing = existing(45L);
        if ("missing".equals(reason)) existing = null;
        else if ("deleted".equals(reason)) existing.setDelFlag(2);
        else if ("otherOwner".equals(reason)) existing.setMemberId(99L);
        else existing.setMerchantNodeEnabled(0);
        Fixture fixture = new Fixture(existing);
        CmsMemberTemplate input = input(Long.MAX_VALUE);
        input.setId(TEMPLATE_ID);
        ServiceException failure = assertThrows(ServiceException.class,
                () -> fixture.service.saveMerchantNodeTemplate(input, OWNER, "merchant7"));
        assertEquals("据点模板不存在或无权修改", failure.getMessage());
        assertEquals(Collections.singletonList("selectCmsMemberTemplateByIdsForUpdate"), fixture.calls);
        assertNull(fixture.saved);
    }

    private static CmsMemberTemplate input(Long minutes) {
        CmsMemberTemplate input = new CmsMemberTemplate();
        input.setTitle("商家节点分钟回归");
        input.setDuration(minutes);
        input.setMemberId(99L);
        input.setDelFlag(2);
        input.setStatus(9L);
        input.setCheckStatus(9);
        input.setPublishStatus(9L);
        input.setDraftStatus(9);
        input.setMerchantNodeEnabled(0);
        input.setNodeSubmitStatus(9);
        input.setIsSync(1);
        return input;
    }

    private static CmsMemberTemplate existing(Long minutes) {
        CmsMemberTemplate row = new CmsMemberTemplate();
        row.setId(TEMPLATE_ID);
        row.setMemberId(OWNER);
        row.setDelFlag(0);
        row.setMerchantNodeEnabled(1);
        row.setDuration(minutes);
        return row;
    }

    private static void assertServerOwnedFields(CmsMemberTemplate row) {
        assertEquals(OWNER, row.getMemberId());
        assertEquals(Integer.valueOf(0), row.getDelFlag());
        assertEquals(Long.valueOf(1L), row.getStatus());
        assertEquals(Integer.valueOf(1), row.getCheckStatus());
        assertEquals(Long.valueOf(1L), row.getPublishStatus());
        assertEquals(Integer.valueOf(1), row.getDraftStatus());
        assertEquals(Integer.valueOf(1), row.getMerchantNodeEnabled());
        assertEquals(Integer.valueOf(2), row.getNodeSubmitStatus());
        assertEquals(Integer.valueOf(0), row.getIsSync());
    }

    private static BoundSql sql(String statement, CmsMemberTemplate row) {
        return mapperConfiguration.getMappedStatement(MAPPER + statement).getBoundSql(row);
    }

    private static void assertDurationBinding(String statement, CmsMemberTemplate row, long minutes) {
        BoundSql bound = sql(statement, row);
        List<ParameterMapping> duration = new ArrayList<>();
        for (ParameterMapping mapping : bound.getParameterMappings()) {
            if ("duration".equals(mapping.getProperty())) duration.add(mapping);
        }
        assertEquals(1, duration.size(), bound.getSql());
        assertEquals(Long.class, duration.get(0).getJavaType());
        assertEquals(Long.valueOf(minutes), mapperConfiguration.newMetaObject(bound.getParameterObject())
                .getValue("duration"));
    }

    /** 仅代理持久化边界，实际 service、BeanUtils、DTO 和 mapper XML 都参与运行。 */
    private static final class Fixture implements InvocationHandler {
        final TemplatePublishServiceImpl service = new TemplatePublishServiceImpl();
        final List<String> calls = new ArrayList<>();
        final CmsMemberTemplate existing;
        CmsMemberTemplate saved;

        Fixture(CmsMemberTemplate existing) throws Exception {
            this.existing = existing;
            wire("memberTemplateService", ICmsMemberTemplateService.class);
            wire("memberTemplateMapper", CmsMemberTemplateMapper.class);
            wire("libraryMapper", CmsTemplateLibraryMapper.class);
            wire("auditTaskMapper", AuditTaskMapper.class);
        }

        private void wire(String name, Class<?> type) throws Exception {
            Field field = TemplatePublishServiceImpl.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(service, Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, this));
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            calls.add(name);
            if ("selectCmsMemberTemplateByIdsForUpdate".equals(name)) {
                assertEquals(Collections.singletonList(TEMPLATE_ID), args[0]);
                return existing == null ? Collections.emptyList() : Collections.singletonList(existing);
            }
            if ("selectByMemberTemplateForUpdate".equals(name)) return Collections.emptyList();
            if ("cancelOpenTemplateTasks".equals(name)) return 0;
            if ("insertCmsMemberTemplate".equals(name) || "updateCmsMemberTemplate".equals(name)) {
                saved = (CmsMemberTemplate) args[0];
                if ("insertCmsMemberTemplate".equals(name)) saved.setId(TEMPLATE_ID);
                return 1;
            }
            throw new AssertionError("意外的持久化调用: " + name);
        }
    }
}
