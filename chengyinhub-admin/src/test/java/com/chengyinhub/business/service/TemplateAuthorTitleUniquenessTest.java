package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.mapper.CmsTemplateLibraryMapper;
import com.chengyinhub.business.service.impl.CmsTemplateLibraryServiceImpl;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Author-scoped exact Java-trim title identity, including partial updates and restore. */
class TemplateAuthorTitleUniquenessTest {
    private CmsTemplateLibraryMapper mapper;
    private CmsTemplateLibraryServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(CmsTemplateLibraryMapper.class);
        service = new CmsTemplateLibraryServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTemplateLibraryMapper", mapper);
        when(mapper.insertCmsTemplateLibrary(any())).thenReturn(1);
        when(mapper.updateCmsTemplateLibrary(any())).thenReturn(1);
    }

    @Test
    void insertRejectsSameAuthorTrimmedExactTitleBeforeWriting() {
        when(mapper.selectAuthorLiveTitles(7L, null)).thenReturn(Arrays.asList(null, "\t暗号\r\n"));
        CmsTemplateLibrary row = row(null, 7L, "  暗号\t", 0);
        assertSameName(assertThrows(ServiceException.class, () -> service.insertCmsTemplateLibrary(row)));
        verify(mapper).selectAuthorLiveTitles(7L, null);
        verify(mapper, never()).insertCmsTemplateLibrary(any());
    }

    @Test
    void insertAllowsCaseVariantAndCannotSeedPopularity() {
        when(mapper.selectAuthorLiveTitles(7L, null)).thenReturn(Collections.singletonList("Night"));
        CmsTemplateLibrary row = row(null, 7L, " night ", 0);
        row.setUseNum(999);
        assertEquals(1, service.insertCmsTemplateLibrary(row));
        assertEquals(Integer.valueOf(0), row.getUseNum());
        verify(mapper).selectAuthorLiveTitles(7L, null);
        verify(mapper).insertCmsTemplateLibrary(row);
    }

    @Test
    void unicodeLineTerminatorsPreserveThePrecedingAsciiSpace() {
        for (String terminator : new String[]{"\u0085", "\u2028", "\u2029"}) {
            String title = "x " + terminator;
            when(mapper.selectAuthorLiveTitles(7L, null)).thenReturn(Collections.singletonList("x" + terminator));
            assertEquals(1, service.insertCmsTemplateLibrary(row(null, 7L, title, 0)),
                    "ASCII space before a Unicode terminator is not trailing Java-trim whitespace");
            when(mapper.selectAuthorLiveTitles(7L, null)).thenReturn(Collections.singletonList(title));
            assertSameName(assertThrows(ServiceException.class,
                    () -> service.insertCmsTemplateLibrary(row(null, 7L, "\t" + title + "\r", 0))));
        }
    }

    @Test
    void identicalTitleBelongingToAnotherAuthorDoesNotBlockInsert() {
        when(mapper.selectAuthorLiveTitles(8L, null)).thenReturn(Collections.emptyList());
        assertEquals(1, service.insertCmsTemplateLibrary(row(null, 8L, "暗号", 0)));
        verify(mapper).selectAuthorLiveTitles(8L, null);
        verify(mapper, never()).selectAuthorLiveTitles(7L, null);
    }

    @Test
    void updateExcludesLibraryIdNotSourcePersonalTemplateId() {
        CmsTemplateLibrary stored = row(91L, 7L, "暗号", 0);
        stored.setMemberTemplateId(501L);
        when(mapper.selectCmsTemplateLibraryById(91L)).thenReturn(stored);
        when(mapper.selectAuthorLiveTitles(7L, 91L)).thenReturn(Collections.emptyList());
        CmsTemplateLibrary patch = row(91L, null, " 暗号 ", null);
        assertEquals(1, service.updateCmsTemplateLibrary(patch));
        verify(mapper).selectAuthorLiveTitles(7L, 91L);
        verify(mapper, never()).selectAuthorLiveTitles(7L, 501L);
        verify(mapper).updateCmsTemplateLibrary(patch);
    }

    @Test
    void renameRejectsOtherLiveTitleUsingStoredOwnerAndDeletedFlag() {
        when(mapper.selectCmsTemplateLibraryById(91L)).thenReturn(row(91L, 7L, "旧名", 0));
        when(mapper.selectAuthorLiveTitles(7L, 91L)).thenReturn(Collections.singletonList("新名"));
        assertSameName(assertThrows(ServiceException.class,
                () -> service.updateCmsTemplateLibrary(row(91L, null, "\t新名 ", null))));
        verify(mapper, never()).updateCmsTemplateLibrary(any());
    }

    @Test
    void changingOwnerChecksEffectiveNewOwnerAndRetainsOriginalTitle() {
        when(mapper.selectCmsTemplateLibraryById(91L)).thenReturn(row(91L, 7L, "暗号", 0));
        when(mapper.selectAuthorLiveTitles(8L, 91L)).thenReturn(Collections.singletonList(" 暗号 "));
        assertSameName(assertThrows(ServiceException.class,
                () -> service.updateCmsTemplateLibrary(row(91L, 8L, null, null))));
        verify(mapper).selectAuthorLiveTitles(8L, 91L);
        verify(mapper, never()).updateCmsTemplateLibrary(any());
    }

    @Test
    void softDeleteDoesNotReserveTitleOrRunDuplicatePrecheck() {
        when(mapper.selectCmsTemplateLibraryById(91L)).thenReturn(row(91L, 7L, "暗号", 0));
        assertEquals(1, service.updateCmsTemplateLibrary(row(91L, null, null, 1)));
        verify(mapper, never()).selectAuthorLiveTitles(any(), any());
    }

    @Test
    void deletedRowRemainingDeletedDoesNotClaimTitleWhenEditing() {
        when(mapper.selectCmsTemplateLibraryById(91L)).thenReturn(row(91L, 7L, "暗号", 1));
        assertEquals(1, service.updateCmsTemplateLibrary(row(91L, null, "另一个标题", null)));
        verify(mapper, never()).selectAuthorLiveTitles(any(), any());
    }

    @Test
    void restoreWithoutTitleOrOwnerChecksStoredOriginalTitle() {
        when(mapper.selectCmsTemplateLibraryById(91L)).thenReturn(row(91L, 7L, "\t暗号 ", 1));
        when(mapper.selectAuthorLiveTitles(7L, 91L)).thenReturn(Collections.singletonList("暗号"));
        assertSameName(assertThrows(ServiceException.class,
                () -> service.updateCmsTemplateLibrary(row(91L, null, null, 0))));
        verify(mapper).selectAuthorLiveTitles(7L, 91L);
        verify(mapper, never()).updateCmsTemplateLibrary(any());
    }

    @Test
    void restoreToUnusedTitleSucceeds() {
        when(mapper.selectCmsTemplateLibraryById(91L)).thenReturn(row(91L, 7L, "暗号", 1));
        when(mapper.selectAuthorLiveTitles(7L, 91L)).thenReturn(Collections.emptyList());
        CmsTemplateLibrary patch = row(91L, null, null, 0);
        assertEquals(1, service.updateCmsTemplateLibrary(patch));
        verify(mapper).selectAuthorLiveTitles(7L, 91L);
        verify(mapper).updateCmsTemplateLibrary(patch);
    }

    @Test
    void insertRaceOnNamedUniqueKeyBecomesSameBusinessError() {
        when(mapper.selectAuthorLiveTitles(7L, null)).thenReturn(Collections.emptyList());
        when(mapper.insertCmsTemplateLibrary(any())).thenThrow(titleRace());
        assertSameName(assertThrows(ServiceException.class,
                () -> service.insertCmsTemplateLibrary(row(null, 7L, "暗号", 0))));
    }

    @Test
    void updateRaceOnNamedUniqueKeyBecomesSameBusinessError() {
        when(mapper.selectCmsTemplateLibraryById(91L)).thenReturn(row(91L, 7L, "旧名", 0));
        when(mapper.selectAuthorLiveTitles(7L, 91L)).thenReturn(Collections.emptyList());
        when(mapper.updateCmsTemplateLibrary(any())).thenThrow(titleRace());
        assertSameName(assertThrows(ServiceException.class,
                () -> service.updateCmsTemplateLibrary(row(91L, null, "新名", null))));
    }

    @Test
    void unrelatedDuplicateKeyIsNotMisreportedAsTitleConflict() {
        DuplicateKeyException unrelated = new DuplicateKeyException("Duplicate entry for key 'PRIMARY'");
        when(mapper.insertCmsTemplateLibrary(any())).thenThrow(unrelated);
        assertSame(unrelated, assertThrows(DuplicateKeyException.class,
                () -> service.insertCmsTemplateLibrary(row(null, 7L, "暗号", 0))));
        when(mapper.selectCmsTemplateLibraryById(91L)).thenReturn(row(91L, 7L, "旧名", 0));
        when(mapper.updateCmsTemplateLibrary(any())).thenThrow(unrelated);
        assertSame(unrelated, assertThrows(DuplicateKeyException.class,
                () -> service.updateCmsTemplateLibrary(row(91L, null, "新名", null))));
    }

    private static DuplicateKeyException titleRace() {
        return new DuplicateKeyException("Duplicate entry for key 'cms_template_library.uk_template_author_live_title'");
    }

    private static void assertSameName(ServiceException error) {
        assertEquals("你已经有同名模板了", error.getMessage());
        assertEquals(Integer.valueOf(HttpStatus.BAD_REQUEST), error.getCode());
    }

    private static CmsTemplateLibrary row(Long id, Long owner, String title, Integer deleted) {
        CmsTemplateLibrary row = new CmsTemplateLibrary();
        row.setId(id);
        row.setMemberId(owner);
        row.setTitle(title);
        row.setDelFlag(deleted);
        return row;
    }
}
