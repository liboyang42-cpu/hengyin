package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.PlayNodeAccessService;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;

/** Test setup adapter: each explicit owner replacement reconstructs the real shared service.
 * No production callback reads Controller fields during a command. */
public final class PlayNodeAccessTestFixtures {
    private PlayNodeAccessTestFixtures() {}
    public static void setField(Object target, String field, Object value) {
        if (!(target instanceof ApiPlayProgressController controller)) {
            ReflectionTestUtils.setField(target, field, value); return;
        }
        PlayNodeAccessService previous = (PlayNodeAccessService) ReflectionTestUtils.getField(controller,"playNodeAccessService");
        if (!field.equals("clubLeadMapper") && !field.equals("omsTicketService"))
            ReflectionTestUtils.setField(target,field,value);
        install(controller, previous, field, value);
    }
    public static Object getField(Object target, String field) {
        if (target instanceof ApiPlayProgressController controller
                && (field.equals("clubLeadMapper") || field.equals("omsTicketService"))) {
            PlayNodeAccessService service=(PlayNodeAccessService)ReflectionTestUtils.getField(controller,"playNodeAccessService");
            return service==null?null:ReflectionTestUtils.getField(service,field);
        }
        return ReflectionTestUtils.getField(target,field);
    }
    public static void install(ApiPlayProgressController controller) {
        install(controller,(PlayNodeAccessService)ReflectionTestUtils.getField(controller,"playNodeAccessService"),null,null);
    }
    private static void install(ApiPlayProgressController controller,PlayNodeAccessService previous,String field,Object value) {
        // These defaults explicitly describe an ordinary no-lead/unused-ticket fixture, never production fallback.
        ClubLeadMapper leads;
        if(previous==null) {
            leads=mock(ClubLeadMapper.class); when(leads.selectByActivity(anyLong())).thenReturn(null);
        } else leads=(ClubLeadMapper)ReflectionTestUtils.getField(previous,"clubLeadMapper");
        IOmsTicketService tickets=previous==null?mock(IOmsTicketService.class):(IOmsTicketService)ReflectionTestUtils.getField(previous,"omsTicketService");
        if("clubLeadMapper".equals(field))leads=(ClubLeadMapper)value;
        if("omsTicketService".equals(field))tickets=(IOmsTicketService)value;
        PlayNodeAccessService service=new PlayNodeAccessService(
                (ICmsActivityService)ReflectionTestUtils.getField(controller,"cmsActivityService"),
                (CmsActivityMapper)ReflectionTestUtils.getField(controller,"currentPlayActivityMapper"),
                (CmsRegistrationMapper)ReflectionTestUtils.getField(controller,"cmsRegistrationMapper"),
                (ICmsTopicNodeService)ReflectionTestUtils.getField(controller,"cmsTopicNodeService"),
                (IPlayerNodeProgressService)ReflectionTestUtils.getField(controller,"playerNodeProgressService"),
                (ICmsTopicService)ReflectionTestUtils.getField(controller,"cmsTopicService"),tickets,leads,
                (IGameSessionRuntimeService)ReflectionTestUtils.getField(controller,"gameSessionRuntimeService"),
                (ICmsTopicChapterService)ReflectionTestUtils.getField(controller,"cmsTopicChapterService"),
                (ITopicRouteRuntimeService)ReflectionTestUtils.getField(controller,"topicRouteRuntimeService"));
        ReflectionTestUtils.setField(controller,"playNodeAccessService",service);
        Object puzzle=ReflectionTestUtils.getField(controller,"puzzlePlayService");
        if(puzzle instanceof org.springframework.aop.framework.Advised advised) {
            try { puzzle=advised.getTargetSource().getTarget(); }
            catch(Exception failed) { throw new IllegalStateException("Cannot rewire hint application fixture",failed); }
        }
        if(puzzle instanceof com.chengyinhub.business.service.impl.PuzzlePlayServiceImpl)
            ReflectionTestUtils.setField(puzzle,"accessService",service);
    }
}
