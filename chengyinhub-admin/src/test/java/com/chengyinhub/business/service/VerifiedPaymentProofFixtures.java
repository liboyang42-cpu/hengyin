package com.chengyinhub.business.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference;
import com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Explicit immutable facts for unit fixtures only. Unknown refs reject; never a real SDK/DB verification claim. */
public final class VerifiedPaymentProofFixtures {
    private final Map<Reference,WechatVerifiedPaymentFactService.Stored> issued = new HashMap<>();
    private final Map<String,Reference> byHash = new HashMap<>();
    public final WechatVerifiedPaymentFactService reader = mock(WechatVerifiedPaymentFactService.class);
    public VerifiedPaymentProofFixtures() {
        when(reader.requireVerified(any())).thenAnswer(call -> {
            Reference reference=call.getArgument(0);var stored=issued.get(reference);
            if(stored==null)throw new IllegalStateException("unissued fixture Reference");return stored;
        });
        // Explicit issued/open unit fixture only. Actual current SQL/fencing is exercised by the MySQL suite.
        when(reader.requireConsumablePayment(any())).thenAnswer(call -> reader.requireVerified(call.getArgument(0)));
    }
    public Reference issue(String businessNo,long fen,String transactionId) {
        String wxNo=businessNo.length()<=32?businessNo:"WXP"+WechatVerifiedPaymentFact.sha256(businessNo).substring(0,29);
        var fact=new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,"fixture-merchant","fixture-app",
                businessNo,wxNo,wxNo,transactionId,transactionId,"JSAPI","CNY",fen,fen,
                Instant.parse("2026-10-24T00:00:00Z"),WechatVerifiedPaymentFact.sha256("original-fixture:"+businessNo),"SUCCESS");
        fact.requireValid();Reference reference=byHash.computeIfAbsent(fact.factHash(),hash->new Reference(issued.size()+1L,hash));
        issued.putIfAbsent(reference,new WechatVerifiedPaymentFactService.Stored(reference,fact));return reference;
    }
    public Map<String,String> successMap(String businessNo,long fen,String transactionId) {
        Reference reference=issue(businessNo,fen,transactionId);var fact=issued.get(reference).facts();
        Map<String,String> result=new HashMap<>();result.put("out_trade_no",businessNo);result.put("wx_out_trade_no",fact.wxOutTradeNo());
        result.put("trade_state","SUCCESS");result.put("transaction_id",transactionId);result.put("total",Long.toString(fen));
        result.put("currency","CNY");result.put("mchid",fact.mchid());result.put("appid",fact.appid());result.put("trade_type",fact.tradeType());
        result.put("verified_fact_id",Long.toString(reference.id()));result.put("verified_fact_hash",reference.hash());return result;
    }
}
