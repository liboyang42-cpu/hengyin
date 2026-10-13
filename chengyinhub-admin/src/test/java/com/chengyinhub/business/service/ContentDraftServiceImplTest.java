package com.chengyinhub.business.service;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.ContentDraft;
import com.chengyinhub.business.domain.dto.ContentDraftCommand;
import com.chengyinhub.business.mapper.ContentDraftMapper;
import com.chengyinhub.business.service.impl.ContentDraftServiceImpl;
import com.chengyinhub.business.service.support.CanonicalDigest;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentDraftServiceImplTest {
    @Test
    void staleVersionIsRejectedByTheVersionedDraftWriteSeam() {
        ContentDraftMapper mapper = mock(ContentDraftMapper.class);
        ContentDraftServiceImpl service = service(mapper);
        ContentDraftCommand command = saveCommand();
        command.setId(18L);
        command.setExpectedVersion(4L);
        ContentDraft current = draft(18L, 6L, "different", ContentDraft.STATUS_DRAFT);
        when(mapper.updateDraftCas(any(ContentDraft.class), org.mockito.ArgumentMatchers.eq(4L))).thenReturn(0);
        when(mapper.selectOwnedById(18L, 7L)).thenReturn(current);

        ServiceException conflict = assertThrows(ServiceException.class, () -> service.save(7L, command));

        assertEquals(Integer.valueOf(409), conflict.getCode(), "旧版本写入必须返回可辨别的冲突码");
        assertEquals("草稿版本冲突，请先恢复云端最新版本", conflict.getMessage(),
                "冲突必须指示客户端恢复云端版本，不能静默覆盖");
        verify(mapper).updateDraftCas(any(ContentDraft.class), org.mockito.ArgumentMatchers.eq(4L));
    }

    @Test
    void repeatedCreateWithTheSameStableKeyAndPayloadReturnsTheStoredDraft() {
        ContentDraftMapper mapper = mock(ContentDraftMapper.class);
        ContentDraftServiceImpl service = service(mapper);
        ContentDraftCommand command = saveCommand();
        String canonical = JSON.toJSONString(JSON.parse(command.getPayloadJson()));
        ContentDraft existing = draft(21L, 1L, CanonicalDigest.sha256Hex(canonical), ContentDraft.STATUS_DRAFT);
        when(mapper.insertDraft(any(ContentDraft.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(mapper.selectByBusinessKey(7L, ContentDraft.TYPE_TOPIC, "editor-42")).thenReturn(existing);

        ContentDraft replay = service.save(7L, command);

        assertSame(existing, replay, "同一稳定业务键的同内容重放必须返回权威存量，不得新造草稿");
    }

    private ContentDraftServiceImpl service(ContentDraftMapper mapper) {
        ContentDraftServiceImpl service = new ContentDraftServiceImpl();
        ReflectionTestUtils.setField(service, "draftMapper", mapper);
        return service;
    }

    private ContentDraftCommand saveCommand() {
        ContentDraftCommand command = new ContentDraftCommand();
        command.setBusinessType(ContentDraft.TYPE_TOPIC);
        command.setClientDraftKey("editor-42");
        command.setPayloadJson("{\"name\":\"夜游\"}");
        command.setExpectedVersion(0L);
        command.setDeviceId("ios-device-1");
        return command;
    }

    private ContentDraft draft(Long id, Long version, String hash, String status) {
        ContentDraft draft = new ContentDraft();
        draft.setId(id);
        draft.setVersion(version);
        draft.setPayloadHash(hash);
        draft.setStatus(status);
        return draft;
    }
}
