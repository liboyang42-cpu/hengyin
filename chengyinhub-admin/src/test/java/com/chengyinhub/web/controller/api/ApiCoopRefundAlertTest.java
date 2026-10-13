package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Controller只呈现业务服务已读回的事实；渠道/事务负控见CoopDepositRefundMybatisIntegrationTest。 */
class ApiCoopRefundAlertTest {
    private static class Fixture {
        final ICoopInviteService service=mock(ICoopInviteService.class);
        final IWechatPayService wx=mock(IWechatPayService.class);
        final ApiCoopController controller=spy(new ApiCoopController());
        final CoopInvite invite=new CoopInvite();
        Fixture(){
            invite.setId(8L);invite.setInviteType(0);invite.setDepositStatus(2);
            invite.setDepositOutTradeNo("DEP8T1");invite.setDepositRefundNo("RDEP8T1");
            when(service.selectCoopInviteById(8L)).thenReturn(invite);
            doReturn(200L).when(controller).getAppUserId();
            ReflectionTestUtils.setField(controller,"coopInviteService",service);
            ReflectionTestUtils.setField(controller,"wechatPayService",wx);
        }
        AjaxResult retry(){Map<String,Object> body=new HashMap<>();body.put("inviteId",8L);return controller.retryDepositRefund(body);}
    }
    @Test void unpaidRetryReportsUnpaidWithoutClaimingRefundAcceptance(){
        Fixture f=new Fixture();f.invite.setDepositStatus(5);AjaxResult result=f.retry();
        assertEquals(200,result.get("code"));assertEquals("保证金尚未缴纳，无需发起退款",result.get("msg"));assertState(result,"UNPAID",false);verifyNoInteractions(f.wx);
    }
    @Test void everyNonHeldStateReportsItsFactAndLegacyFourIsUnconfirmed(){
        int[] statuses={0,1,3,5};String[] states={"NOT_REQUIRED","PAYMENT_PENDING","DEDUCTED","UNPAID"};
        for(int i=0;i<statuses.length;i++){Fixture f=new Fixture();f.invite.setDepositStatus(statuses[i]);AjaxResult result=f.retry();assertEquals(200,result.get("code"));assertState(result,states[i],false);verifyNoInteractions(f.wx);}
        Fixture legacy=new Fixture();legacy.invite.setDepositStatus(4);AjaxResult result=legacy.retry();assertEquals(500,result.get("code"));assertState(result,"LEGACY_UNCONFIRMED",false);assertFalse(result.get("msg").toString().contains("已到账"));
    }
    @Test void processingPendingAndSuccessHaveDistinctTruthfulFeedback(){
        for(String phase:Arrays.asList("PENDING","DISPATCHING","PROCESSING","SUCCESS")){
            Fixture f=new Fixture();f.invite.setDepositRefundState(phase);
            if("SUCCESS".equals(phase)){f.invite.setDepositStatus(4);f.invite.setDepositRefundConfirmedAt(new Date());}
            AjaxResult result=f.retry();assertEquals(200,result.get("code"));assertState(result,"SUCCESS".equals(phase)?"SUCCESS":"PROCESSING".equals(phase)?"PROCESSING":"DISPATCH_PENDING","SUCCESS".equals(phase));
            verify(f.service).requestDepositRefund(8L,200L);verifyNoInteractions(f.wx);
        }
    }
    @Test void unknownManualMissingOrUnlinkedFactsNeverClaimSuccess(){
        for(String phase:Arrays.asList("UNKNOWN","MANUAL")){Fixture f=new Fixture();f.invite.setDepositRefundState(phase);AjaxResult result=f.retry();assertEquals(500,result.get("code"));assertState(result,phase,false);}
        for(Integer state:Arrays.asList(null,-1,6)){Fixture f=new Fixture();f.invite.setDepositStatus(state);assertEquals(500,f.retry().get("code"));}
        Fixture missing=new Fixture();when(missing.service.selectCoopInviteById(8L)).thenReturn(null);assertEquals(500,missing.retry().get("code"));
        Fixture wrong=new Fixture();wrong.invite.setDepositStatus(4);wrong.invite.setDepositRefundState("SUCCESS");wrong.invite.setDepositRefundConfirmedAt(new Date());wrong.invite.setDepositRefundNo("WRONG");assertState(wrong.retry(),"UNKNOWN",false);
    }
    @Test void legacyFourChannelFailureRetainsManualResultInsteadOfGenericHistory() {
        Fixture f=new Fixture(); f.invite.setDepositStatus(4); f.invite.setDepositRefundState("MANUAL");
        AjaxResult result=f.retry(); assertEquals(500,result.get("code"));assertState(result,"MANUAL",false);
        assertTrue(result.get("msg").toString().contains("人工核对"));
    }
    @Test void permissionFailureDoesNotReadMoneyOrCallWechat(){
        Fixture f=new Fixture();doThrow(new IllegalStateException("仅合作双方可重试保证金退款")).when(f.service).requestDepositRefund(8L,200L);
        AjaxResult result=f.retry();assertEquals(500,result.get("code"));assertTrue(result.get("msg").toString().contains("仅合作双方"));verify(f.service,never()).selectCoopInviteById(anyLong());verifyNoInteractions(f.wx);
    }
    @Test void persistenceFailureDoesNotBecomeSuccessfulFeedback(){
        Fixture f=new Fixture();doThrow(new IllegalStateException("保证金退款领取失败")).when(f.service).requestDepositRefund(8L,200L);
        assertEquals(500,f.retry().get("code"));verify(f.service,never()).selectCoopInviteById(anyLong());verifyNoInteractions(f.wx);
    }
    @Test void cancelledCooperationWithProcessingRefundStaysExplicitlyPending(){
        Fixture f=cancelFixture();f.invite.setDepositRefundState("PROCESSING");
        AjaxResult result=f.controller.handle(cancelCommand());assertEquals(500,result.get("code"));assertTrue(result.get("msg").toString().contains("合作已取消"));assertTrue(result.get("msg").toString().contains("退款处理中"));
        verify(f.service).cancelAcceptedInvite(8L,200L,"停止合作",2);verifyNoInteractions(f.wx);
    }
    @Test void failedCancellationDoesNotStartIndependentRefund(){
        Fixture f=cancelFixture();doThrow(new IllegalStateException("商家已停用")).when(f.service).cancelAcceptedInvite(anyLong(),anyLong(),anyString(),anyInt());
        assertEquals(500,f.controller.handle(cancelCommand()).get("code"));verify(f.service,never()).requestDepositRefund(anyLong(),anyLong());verifyNoInteractions(f.wx);
    }
    @Test void cancellationRetainsNonHeldAndLegacyNoOperationContract(){
        for(Integer status:Arrays.asList(null,0,1,3,4,5)){Fixture f=new Fixture();f.invite.setDepositStatus(status);assertTrue(ReflectionTestUtils.<Boolean>invokeMethod(f.controller,"refundDepositIfHeld",8L,"取消"));verifyNoInteractions(f.wx);}
        Fixture legacy=new Fixture();legacy.invite.setInviteType(2);assertTrue(ReflectionTestUtils.<Boolean>invokeMethod(legacy.controller,"refundDepositIfHeld",8L,"取消"));verifyNoInteractions(legacy.wx);
    }
    private Fixture cancelFixture(){Fixture f=new Fixture();f.invite.setStatus(1);f.invite.setFromType("talent");f.invite.setFromId(100L);f.invite.setToType("merchant");f.invite.setToId(200L);
        IMerchantAccessService access=mock(IMerchantAccessService.class);ReflectionTestUtils.setField(f.controller,"merchantAccessService",access);
        when(access.require(200L,MerchantPermission.COOP_MANAGE)).thenReturn(new MerchantAccessContext(200L,18L,200L,MerchantRoleCode.MERCHANT_OWNER));return f;}
    private CoopInvite cancelCommand(){CoopInvite c=new CoopInvite();c.setId(8L);c.setStatus(3);c.setMessage("停止合作");return c;}
    private static void assertState(AjaxResult r,String expected,boolean confirmed){Map<?,?> data=(Map<?,?>)r.get("data");assertEquals(expected,data.get("refundState"));assertEquals(confirmed,data.get("channelConfirmed"));}
}
