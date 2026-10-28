package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.PuzzlePlayServiceImpl;
import com.chengyinhub.business.service.support.PlayNodeAccessService;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.mockito.Mockito.*;

/** Explicit real hint application wiring; no duplicate price/admission rules in fixtures. */
public final class NodeHintUnlockTestFixtures {
    private NodeHintUnlockTestFixtures() {}
    public static IPuzzlePlayService application(ApiPlayProgressController controller, ICmsPointsSettingService settings,
            IUmsMemberService members, IUmsMemberPointsDetailService details, PlatformTransactionManager transactions) {
        PlayNodeAccessTestFixtures.install(controller);
        PuzzlePlayServiceImpl target=new PuzzlePlayServiceImpl(
                (ICmsMemberTemplateService)com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller,"cmsMemberTemplateService"),
                mock(PlayAttemptLogMapper.class),mock(PlayerNodeProgressMapper.class),mock(UmsMemberMapper.class),
                (ICmsTopicNodeService)com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller,"cmsTopicNodeService"),
                (PlayNodeAccessService)com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller,"playNodeAccessService"),settings,members,details);
        if(transactions==null)return target; // Plain unit fixture, never claimed as transaction evidence.
        ProxyFactory proxy=new ProxyFactory(target);proxy.setInterfaces(IPuzzlePlayService.class);
        proxy.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        return (IPuzzlePlayService)proxy.getProxy();
    }
    public static void wire(ApiPlayProgressController controller, ICmsPointsSettingService settings,
            IUmsMemberService members, IUmsMemberPointsDetailService details, PlatformTransactionManager transactions) {
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller,"puzzlePlayService",application(controller,settings,members,details,transactions));
    }
    public static void pricing(ApiPlayProgressController controller,ICmsPointsSettingService settings) {
        IPuzzlePlayService price=application(controller,settings,mock(IUmsMemberService.class),mock(IUmsMemberPointsDetailService.class),null);
        IPuzzlePlayService existing=(IPuzzlePlayService)com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller,"puzzlePlayService");
        if(existing==null)throw new IllegalStateException("Pricing fixture must retain its explicit puzzle caller");
        IPuzzlePlayService observed=mockingDetails(existing).isMock()?existing:spy(existing);
        doAnswer(call -> price.hintUnlockCost()).when(observed).hintUnlockCost();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller,"puzzlePlayService",observed);
    }
}
