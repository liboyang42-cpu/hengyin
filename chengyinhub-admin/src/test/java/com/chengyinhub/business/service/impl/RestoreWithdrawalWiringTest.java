package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
import com.chengyinhub.business.mapper.MemberCashWithdrawMapper;
import com.chengyinhub.business.service.ICashWithdrawService;
import com.chengyinhub.business.service.IWechatPayService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Wiring/identity controls only; no database, channel acceptance or restore evidence is manufactured here. */
class RestoreWithdrawalWiringTest {
    private CashWithdrawServiceImpl target() {
        var target=new CashWithdrawServiceImpl();
        var mapper=mock(MemberCashWithdrawMapper.class);
        when(mapper.countPendingByMember(61L)).thenReturn(1);
        ReflectionTestUtils.setField(target,"mapper",mapper);
        return target;
    }

    private ICashWithdrawService proxy(Object target,boolean classProxy,AtomicInteger invoked) {
        var factory=new ProxyFactory(target);
        factory.setInterfaces(ICashWithdrawService.class);factory.setProxyTargetClass(classProxy);
        factory.addAdvice((MethodInterceptor) invocation->{invoked.incrementAndGet();return invocation.proceed();});
        return (ICashWithdrawService)factory.getProxy();
    }

    @Test
    void classProxyBindsTheActualExistingFieldAndReturnsTheOriginalAdvisedOwner() {
        var target=target();var invoked=new AtomicInteger();
        var service=proxy(target,true,invoked);var provider=mock(IWechatPayService.class);
        assertSame(service,RestoreWithdrawalReconciliationFixture.bindProvider(service,provider));
        Object actual=AopTestUtils.getUltimateTargetObject(service);
        assertSame(target,actual);assertSame(provider,ReflectionTestUtils.getField(actual,"wechatPayService"));
        assertTrue(service.hasPendingWithdraw(61L));assertEquals(1,invoked.get());
        verifyNoInteractions(provider);
    }

    @Test
    void nestedInterfaceAndClassAdviceRemainInPlaceInsteadOfReturningAnUnwrappedService() {
        var target=target();var innerCalls=new AtomicInteger();var outerCalls=new AtomicInteger();
        var inner=proxy(target,true,innerCalls);var outer=proxy(inner,false,outerCalls);
        var provider=mock(IWechatPayService.class);
        assertSame(outer,RestoreWithdrawalReconciliationFixture.bindProvider(outer,provider));
        assertSame(provider,ReflectionTestUtils.getField(target,"wechatPayService"));
        assertTrue(outer.hasPendingWithdraw(61L));
        assertEquals(1,innerCalls.get());assertEquals(1,outerCalls.get());verifyNoInteractions(provider);
    }

    @Test
    void missingAdviceMissingProviderAndForeignOwnerRejectBeforeAnyChannelCall() {
        var provider=mock(IWechatPayService.class);var target=target();
        assertThrows(IllegalStateException.class,()->RestoreWithdrawalReconciliationFixture.bindProvider(target,provider));
        var realProxy=proxy(target,true,new AtomicInteger());
        assertThrows(IllegalStateException.class,()->RestoreWithdrawalReconciliationFixture.bindProvider(realProxy,null));
        var foreign=proxy(mock(ICashWithdrawService.class),false,new AtomicInteger());
        assertThrows(IllegalStateException.class,()->RestoreWithdrawalReconciliationFixture.bindProvider(foreign,provider));
        assertNull(ReflectionTestUtils.getField(target,"wechatPayService"));verifyNoInteractions(provider);
    }

    @Test
    void boundProviderRetainsTheTypedOriginalQueryAndUnknownResultWithoutAnySubmitOrLegacyCall() {
        var target=target();var provider=mock(IWechatPayService.class);
        var service=proxy(target,true,new AtomicInteger());
        RestoreWithdrawalReconciliationFixture.bindProvider(service,provider);
        var original=CashWithdrawTransferIntent.fromRequest(1L,"original-mch","original-app","CW1","CD1",
                new BigDecimal("30.00"),"original-openid","original remark").withSubmissionIntentId(9L);
        original.requireValid();var unknown=Map.of("result","UNKNOWN");
        when(provider.queryTransferResult(original)).thenReturn(unknown);
        var installed=(IWechatPayService)ReflectionTestUtils.getField(target,"wechatPayService");
        assertSame(unknown,installed.queryTransferResult(original));
        verify(provider).queryTransferResult(original);verifyNoMoreInteractions(provider);
        // This is the installed-port contract, not a claim that a mocked query proves financial recovery.
    }

    @Test
    void journalComparisonBindsBothV1SourceAndSnapshotHashAsWellAsTheFullOriginalWire() {
        var expected=new LinkedHashMap<String,Object>();
        expected.put("withdrawalId",1L);expected.put("source","FUND_FACT");expected.put("memberId",61L);
        expected.put("submissionIntentId",9L);expected.put("requestVersion",1);expected.put("requestHash","a".repeat(64));
        expected.put("wireHash","b".repeat(64));expected.put("masterRequestHash","c".repeat(64));
        expected.put("mchid","original-mch");expected.put("appid","original-app");
        expected.put("outBatchNo","CW1");expected.put("outDetailNo","CD1");expected.put("amountFen",3000L);
        expected.put("openid","original-openid");expected.put("remark","original remark");
        expected.put("origin","REAL_ADMISSION_AND_SUBMIT");
        assertDoesNotThrow(()->RestoreWithdrawalReconciliationFixture.requireSameProviderIdentity(expected,new LinkedHashMap<>(expected)));
        for(String key:expected.keySet()) {
            var wrong=new LinkedHashMap<>(expected);wrong.put(key,"MISMATCH");
            assertThrows(IllegalStateException.class,
                ()->RestoreWithdrawalReconciliationFixture.requireSameProviderIdentity(expected,wrong),key);
        }
        var master=new LinkedHashMap<>(expected);master.put("source","TRANSFER_INTENT");
        master.put("submissionIntentId",null);master.put("requestHash",master.get("masterRequestHash"));
        master.put("origin","HISTORICAL_MASTER_ONLY:original-TransferIntent9/B-history-fixture");
        assertDoesNotThrow(()->RestoreWithdrawalReconciliationFixture.requireSameProviderIdentity(master,new LinkedHashMap<>(master)));
        assertThrows(IllegalStateException.class,()->RestoreWithdrawalReconciliationFixture.requireSameProviderIdentity(expected,master));
        assertThrows(IllegalStateException.class,()->RestoreWithdrawalReconciliationFixture.requireSameProviderIdentity(master,expected));
    }
}
