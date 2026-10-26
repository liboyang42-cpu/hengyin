package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.dto.TemplateAdoptionCommand;
import com.chengyinhub.business.mapper.TemplateAdoptionReceiptMapper;
import com.chengyinhub.business.service.ITemplatePublishService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TemplateAdoptionServiceTest {
    @Test void canonicalLongAndSourceFingerprintCoverAuthorContentNotCounters() {
        var service = new TemplateAdoptionService(mock(TemplateAdoptionReceiptMapper.class),mock(ITemplatePublishService.class),mock(PlatformTransactionManager.class));
        assertEquals(Long.MAX_VALUE,TemplateAdoptionService.id("9223372036854775807"));
        for(String bad:List.of("0","01","1.0","9223372036854775808","-1")) assertThrows(ServiceException.class,()->TemplateAdoptionService.id(bad));
        CmsTemplateLibrary source=new CmsTemplateLibrary();source.setId(7L);source.setTitle("title");source.setQuestionAnswer("a");
        String first=service.sourceVersion(source);source.setQuestionAnswer("b");assertNotEquals(first,service.sourceVersion(source));
        source.setQuestionAnswer("a");source.setPublishStatus(0L);assertEquals(first,service.sourceVersion(source));
        assertEquals("9007199254740993",TemplateAdoptionWire.strings(Map.of("templateId",9007199254740993L),"templateId").path("templateId").asText());
    }
    @Test void completedReplayUsesOriginalActorOwnerFingerprintAndNeverRequiresSource() {
        var mapper=mock(TemplateAdoptionReceiptMapper.class);var drafts=mock(ITemplatePublishService.class);var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());
        var row=new HashMap<String,Object>();
        when(mapper.reserve(anyMap())).thenAnswer(i->{if(row.isEmpty()){row.putAll(i.getArgument(0));row.put("status","COMPLETED");row.put("draftId",9007199254740993L);}return 1;});
        when(mapper.lock(eq(1L),anyString())).thenAnswer(i->new HashMap<>(row));
        var service=new TemplateAdoptionService(mapper,drafts,manager);
        var command=new TemplateAdoptionCommand();command.setRequestId("adopt_original_request");command.setOriginalTemplateId("7");command.setScope("");command.setSourceVersion("a".repeat(64));
        assertEquals("9007199254740993",service.adopt(1L,2L,command,"actor").get("draftId"));
        assertEquals("9007199254740993",service.adopt(1L,2L,command,"actor").get("draftId"));
        command.setSourceVersion("b".repeat(64));assertThrows(ServiceException.class,()->service.adopt(1L,2L,command,"actor"));
        command.setSourceVersion("a".repeat(64));assertThrows(ServiceException.class,()->service.adopt(1L,3L,command,"actor"));
        verify(mapper,never()).lockSource(any());verifyNoInteractions(drafts);
    }
}
