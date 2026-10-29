package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.vo.CoopSettlementManualCaseView;
import com.chengyinhub.business.mapper.CoopSettlementManualCaseReadMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Operational visibility only. No raw payment evidence or mutation/recovery capability is exposed. */
@Service
@Transactional(readOnly=true)
public class CoopSettlementManualCaseReadService {
    private final CoopSettlementManualCaseReadMapper rows;
    private final ObjectMapper json=new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    public CoopSettlementManualCaseReadService(CoopSettlementManualCaseReadMapper rows){this.rows=rows;}
    public List<CoopSettlementManualCaseView> list(Long topicId,String state) {
        if(topicId!=null && topicId<=0) throw new IllegalArgumentException("主题ID无效");
        if(state!=null && state.isBlank()) state=null;
        if(state!=null && !"MANUAL_REQUIRED".equals(state)) throw new IllegalArgumentException("人工记录状态无效");
        return rows.selectList(topicId,state);
    }
    public CoopSettlementManualCaseView detail(Long id) {
        if(id==null || id<=0) throw new IllegalArgumentException("记录ID无效");
        var stored=rows.selectById(id);if(stored==null)return null;
        CoopSettlementManualCaseView view=new CoopSettlementManualCaseView();
        view.setId(stored.getId());view.setTopicId(stored.getTopicId());view.setTopicName(stored.getTopicName());
        view.setReason(stored.getReason());view.setState(stored.getState());view.setNegativeAmount(stored.getNegativeAmount());view.setCreatedAt(stored.getCreatedAt());
        // Copy whitelisted fields into a separate DTO. The persistence record is never returned.
        view.setSource(source(stored.getSourceSnapshot()));return view;
    }
    private CoopSettlementManualCaseView.Source source(String raw) {
        var result=new CoopSettlementManualCaseView.Source();result.setLines(List.of());
        try {
            JsonNode node=json.readTree(raw);if(node==null || !node.isObject())return result;
            result.setAvailable(true);
            if(CoopSettlementOriginalProducer.VERSION.equals(node.path("version").asText()))result.setCalculationVersion(CoopSettlementOriginalProducer.VERSION);
            result.setPayerRegistrationId(id(node.get("payerRegistrationId")));
            result.setOriginalSales(amount(node.get("sales")));result.setOriginalCash(amount(node.get("originalCash")));
            result.setAllocatedCost(amount(node.get("allocatedCost")));result.setPlatformAmount(amount(node.get("platform")));
            result.setGuaranteeAmount(amount(node.get("guarantee")));result.setMerchantAmount(amount(node.get("merchant")));
            List<CoopSettlementManualCaseView.Line> lines=new ArrayList<>();
            JsonNode heads=node.get("originalMerchantHeads");
            if(heads!=null && heads.isArray())for(JsonNode head:heads) {
                var line=line(head,"amount");line.setCoopOrderId(id(head.get("coopOrderId")));
                line.setShareMode(smallInt(head.get("shareMode")));line.setShareRate(amount(head.get("shareRate")));line.setFixedFee(amount(head.get("fixedFee")));lines.add(line);
            }
            JsonNode components=node.get("originalComponents");
            if(components!=null && components.isArray())for(JsonNode component:components) {
                var line=line(component,"originalAmount");line.setSettlementId(id(component.get("settlementId")));
                line.setSourceRegistrationId(id(component.get("sourceRegistrationId")));line.setSourceClaimId(id(component.get("sourceClaimId")));lines.add(line);
            }
            result.setLines(lines);
        } catch(Exception unavailable) {
            // Preserve visibility of topic/reason/state even if historical JSON cannot be projected.
            // Never put source content or parser messages in an API response/log.
            result=new CoopSettlementManualCaseView.Source();result.setLines(List.of());
        }
        return result;
    }
    private CoopSettlementManualCaseView.Line line(JsonNode node,String amountField) {
        var line=new CoopSettlementManualCaseView.Line();String kind=node.path("lineType").asText();
        if(Set.of("PLATFORM","INITIATOR_GUARANTEE","INITIATOR_MARGIN","MERCHANT_VERIFY","LEAD_FEE","DEPOSIT_DEDUCTION").contains(kind))line.setLineType(kind);
        line.setOriginalAmount(amount(node.get(amountField)));return line;
    }
    private static Long id(JsonNode node){return node!=null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue()>0?node.longValue():null;}
    private static Integer smallInt(JsonNode node){return node!=null && node.isIntegralNumber() && node.canConvertToInt()?node.intValue():null;}
    private static BigDecimal amount(JsonNode node){return node!=null && node.isNumber()?node.decimalValue():null;}
}
