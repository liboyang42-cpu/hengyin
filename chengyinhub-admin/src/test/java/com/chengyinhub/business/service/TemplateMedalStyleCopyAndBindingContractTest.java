package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.service.impl.TemplatePublishServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** App 模板编辑器的勋章样式请求复制与 MyBatis 持久化绑定合同。 */
class TemplateMedalStyleCopyAndBindingContractTest
{
    private final AtomicReference<CmsMemberTemplate> memberRow = new AtomicReference<>();
    private TemplatePublishServiceImpl service;
    private ICmsMemberTemplateService memberService;
    private ICmsTemplateLibraryService libraryService;
    private ICapabilityGuard capabilityGuard;

    @BeforeEach
    void setUp()
    {
        memberService = mock(ICmsMemberTemplateService.class);
        libraryService = mock(ICmsTemplateLibraryService.class);
        IAuditTaskService auditService = mock(IAuditTaskService.class);
        capabilityGuard = mock(ICapabilityGuard.class);
        service = new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(service, "memberTemplateService", memberService);
        com.chengyinhub.business.mapper.CmsMemberTemplateMapper lockedMembers = mock(com.chengyinhub.business.mapper.CmsMemberTemplateMapper.class);
        ReflectionTestUtils.setField(service, "memberTemplateMapper", lockedMembers);
        ReflectionTestUtils.setField(service, "libraryMapper", mock(com.chengyinhub.business.mapper.CmsTemplateLibraryMapper.class));
        ReflectionTestUtils.setField(service, "auditTaskMapper", mock(com.chengyinhub.business.mapper.AuditTaskMapper.class));
        when(lockedMembers.selectCmsMemberTemplateByIdsForUpdate(any())).thenAnswer(call -> {
            java.util.List<Long> ids = call.getArgument(0);
            CmsMemberTemplate row = memberService.selectCmsMemberTemplateById(ids.get(0));
            return row == null ? java.util.Collections.emptyList() : java.util.Collections.singletonList(row);
        });
        ReflectionTestUtils.setField(service, "templateLibraryService", libraryService);
        ReflectionTestUtils.setField(service, "auditTaskService", auditService);
        ReflectionTestUtils.setField(service, "capabilityGuard", capabilityGuard);
        when(memberService.insertCmsMemberTemplate(any())).thenAnswer(invocation -> {
            CmsMemberTemplate row = invocation.getArgument(0);
            row.setId(501L);
            when(memberService.selectCmsMemberTemplateById(row.getId())).thenReturn(row);
            memberRow.set(row);
            return 1;
        });
        when(memberService.updateCmsMemberTemplate(any())).thenAnswer(invocation -> {
            memberRow.set(invocation.getArgument(0));
            return 1;
        });
    }

    @Test
    void appJsonEnamelChoiceReachesPublishedAndDraftCanonicalRows() throws Exception
    {
        CmsTemplatePublishRequest publish = new ObjectMapper().readValue(
                "{\"title\":\"街角徽章\",\"isSync\":0,\"medalStyle\":\"enamel\"}",
                CmsTemplatePublishRequest.class);

        service.publishTemplateWithTransaction(publish, 7L, "member7");
        assertEquals("enamel", memberRow.get().getMedalStyle());

        memberRow.set(null);
        CmsTemplatePublishRequest draft = new CmsTemplatePublishRequest();
        draft.setTitle("街角徽章草稿");
        draft.setMedalStyle("glow");
        service.saveOrUpdateDraft(draft, 7L, "member7");
        assertEquals("glow", memberRow.get().getMedalStyle());
    }

    @Test
    void omittedStyleKeepsBackwardCompatibleGlowDefault()
    {
        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setTitle("旧客户端模板");

        service.saveOrUpdateDraft(request, 7L, "member7");

        assertEquals("glow", memberRow.get().getMedalStyle());
    }

    @Test
    void unsupportedStyleIsRejectedBeforeAnyTemplateRowIsWritten()
    {
        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setTitle("非法样式");
        request.setMedalStyle("glass");

        assertThrows(IllegalArgumentException.class,
                () -> service.publishTemplateWithTransaction(request, 7L, "member7"));
        assertNull(memberRow.get());
    }

    @Test
    void styleOnlyChangeStillRequiresDesignMedalCapability()
    {
        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setTitle("只改样式");
        request.setMedalStyle("enamel");
        when(capabilityGuard.checkDesignMedal(7L)).thenReturn("当前身份未解锁「勋章设计」");

        assertThrows(IllegalArgumentException.class,
                () -> service.saveOrUpdateDraft(request, 7L, "member7"));
        assertNull(memberRow.get());
    }

    @Test
    void defaultGlowWithoutMedalDoesNotRequireDesignMedalCapability()
    {
        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setTitle("没碰勋章");
        request.setMedalStyle("glow");
        when(capabilityGuard.checkDesignMedal(7L)).thenReturn("当前身份未解锁「勋章设计」");

        service.saveOrUpdateDraft(request, 7L, "member7");

        assertEquals("glow", memberRow.get().getMedalStyle());
        verify(capabilityGuard, never()).checkDesignMedal(7L);
    }

    @Test
    void defaultGlowWithMedalNameStillRequiresDesignMedalCapability()
    {
        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setTitle("设计了勋章");
        request.setMedalStyle("glow");
        request.setMedalName("街角徽章");
        when(capabilityGuard.checkDesignMedal(7L)).thenReturn("当前身份未解锁「勋章设计」");

        assertThrows(IllegalArgumentException.class,
                () -> service.saveOrUpdateDraft(request, 7L, "member7"));
        assertNull(memberRow.get());
    }

    @Test
    void updatingDraftWithoutStyleKeepsExistingEnamelWithoutRecheckingCapability()
    {
        CmsMemberTemplate existing = new CmsMemberTemplate();
        existing.setId(501L);
        existing.setDelFlag(0);
        existing.setMemberId(7L);
        existing.setMedalStyle("enamel");
        when(memberService.selectCmsMemberTemplateById(501L)).thenReturn(existing);
        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setId(501L);
        request.setTitle("旧客户端只改标题");

        service.saveOrUpdateDraft(request, 7L, "member7");

        assertEquals("enamel", memberRow.get().getMedalStyle());
        verify(capabilityGuard, never()).checkDesignMedal(7L);
    }

    @Test
    void adoptingPublicTemplateUsesCanonicalRewardWithoutRequiringDesignCapability()
    {
        CmsTemplateLibrary source = new CmsTemplateLibrary();
        source.setId(52L);
        source.setMemberTemplateId(601L);
        source.setDelFlag(0);
        source.setStatus(1L);
        source.setPublishStatus(1L);
        CmsMemberTemplate canonical = new CmsMemberTemplate();
        canonical.setId(601L);
        canonical.setDelFlag(0);
        canonical.setStatus(1L);
        canonical.setPublishStatus(1L);
        canonical.setMedalName("街角发现者");
        canonical.setMedalImg("https://cdn.example/medal.png");
        canonical.setMedalStyle("enamel");
        when(libraryService.selectCmsTemplateLibraryById(52L)).thenReturn(source);
        when(memberService.selectCmsMemberTemplateById(601L)).thenReturn(canonical);
        when(capabilityGuard.checkDesignMedal(7L)).thenReturn("当前身份未解锁「勋章设计」");
        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setTitle("采用公共模板");
        request.setOriginalTemplateId(52L);
        request.setMedalName("客户端篡改");
        request.setMedalImg("https://evil.example/medal.png");
        request.setMedalStyle("glow");

        service.publishTemplateWithTransaction(request, 7L, "member7");

        assertEquals("街角发现者", memberRow.get().getMedalName());
        assertEquals("https://cdn.example/medal.png", memberRow.get().getMedalImg());
        assertEquals("enamel", memberRow.get().getMedalStyle());
        verify(capabilityGuard, never()).checkDesignMedal(7L);
    }

    @Test
    void adoptingProjectionWithoutCanonicalCannotSmuggleClientReward()
    {
        CmsTemplateLibrary source = new CmsTemplateLibrary();
        source.setId(52L);
        source.setDelFlag(0);
        source.setStatus(1L);
        source.setPublishStatus(1L);
        when(libraryService.selectCmsTemplateLibraryById(52L)).thenReturn(source);
        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setTitle("采用旧公共模板");
        request.setOriginalTemplateId(52L);
        request.setMedalName("客户端夹带");
        request.setMedalImg("https://evil.example/medal.png");
        request.setMedalStyle("enamel");

        service.publishTemplateWithTransaction(request, 7L, "member7");

        assertNull(memberRow.get().getMedalName());
        assertNull(memberRow.get().getMedalImg());
        assertEquals("glow", memberRow.get().getMedalStyle());
        verify(capabilityGuard, never()).checkDesignMedal(7L);
    }

    @Test
    void memberMapperAndExistingMigrationCoverReadInsertAndUpdateBindings() throws Exception
    {
        String mapper = resource("mapper/business/CmsMemberTemplateMapper.xml");
        assertEquals(true, mapper.contains("property=\"medalStyle\"   column=\"medal_style\""));
        assertEquals(true, mapper.contains("medal_style,"));
        assertEquals(true, mapper.contains("<if test=\"medalStyle != null\">#{medalStyle},</if>"));
        assertEquals(true, mapper.contains("medal_style = #{medalStyle}"));

        String migration = new String(Files.readAllBytes(
                Paths.get("sql/migration_20260807_medal_style.sql")), StandardCharsets.UTF_8);
        assertEquals(true, migration.contains("ALTER TABLE cms_member_template"));
        assertEquals(true, migration.contains("ADD COLUMN medal_style varchar(16) NOT NULL DEFAULT 'glow'"));
        assertEquals(true, migration.contains("glow=二维发光/enamel=3D珐琅"));
    }

    private String resource(String path) throws Exception
    {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(org.springframework.util.StreamUtils.copyToByteArray(in), StandardCharsets.UTF_8);
        }
    }
}
