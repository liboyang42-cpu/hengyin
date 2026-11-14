package com.chengyinhub.business.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.MerchantAuthoringCandidate;
import com.chengyinhub.business.domain.MerchantAuthoringSource;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.PmsProduct;
import com.chengyinhub.business.domain.PmsSku;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.domain.dto.ai.MerchantAuthoringConfirmation;
import com.chengyinhub.business.domain.vo.ai.AiTemplateContent;
import com.chengyinhub.business.domain.vo.ai.AiTemplateFillResp;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.MerchantAuthoringRecordMapper;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.merchantgap.MerchantGapH2;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IPmsProductService;
import com.chengyinhub.business.service.IPmsSkuService;
import com.chengyinhub.business.service.ITemplatePublishService;
import com.chengyinhub.business.service.merchantauthoring.MerchantDraftSelectionService;
import com.chengyinhub.common.exception.ServiceException;

/**
 * The W05 source producer (record → confirm) writes the rows draft-selection lists: a real candidate, a real
 * MERCHANT draft and an immutable source whose template hash the chooser recomputes. H2 holds the two tables.
 */
class MerchantAuthoringRecordServiceTest {
    static final long OWNER = 9002L, STRANGER = 9005L, STORE = 8001L, PRODUCT = 992951L, SKU = 992952L, TEMPLATE = 6101L;

    MerchantGapH2 db;
    TransactionTemplate tx;
    MerchantAuthoringFactsService facts;
    MerchantAuthoringRecordService records;
    MerchantDraftSelectionService selection;
    ITemplatePublishService templates;
    PmsProduct product;
    CmsMemberTemplate saved;

    @BeforeEach
    void setUp() throws Exception {
        db = MerchantGapH2.create();
        tx = new TransactionTemplate(db.transactions);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(eq(OWNER), anyString())).thenReturn(new MerchantAccessContext(OWNER, STORE, OWNER, MerchantRoleCode.MERCHANT_OWNER));
        when(access.require(eq(STRANGER), anyString())).thenReturn(new MerchantAccessContext(STRANGER, 8005L, STRANGER, MerchantRoleCode.MERCHANT_OWNER));

        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(STORE); merchant.setMemberId(OWNER); merchant.setStatus(1L); merchant.setAccountStatus(1L); merchant.setDelFlag(0);
        merchant.setName("城瘾咖啡"); merchant.setUpdateTime(new Date(1_000L));
        product = new PmsProduct();
        product.setId(PRODUCT); product.setMerchantId(STORE); product.setDelFlag(0); product.setPublishStatus(1);
        product.setProductName("手冲"); product.setUnit("杯"); product.setUpdateTime(new Date(2_000L));
        PmsSku sku = new PmsSku();
        sku.setId(SKU); sku.setProductId(PRODUCT); sku.setSkuName("大杯"); sku.setUpdateTime(new Date(3_000L));
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        when(merchants.selectMmsMerchantById(STORE)).thenReturn(merchant);
        IPmsProductService products = mock(IPmsProductService.class);
        when(products.selectPmsProductById(PRODUCT)).thenAnswer(i -> product);
        IPmsSkuService skus = mock(IPmsSkuService.class);
        when(skus.selectPmsSkuById(SKU)).thenReturn(sku);
        facts = new MerchantAuthoringFactsService(access, merchants, products, skus);

        MerchantAuthoringRecordMapper mapper = h2Mapper();
        when(mapper.lockMerchant(STORE)).thenReturn(STORE);
        when(mapper.lockMerchantSnapshot(STORE)).thenReturn(merchant);
        when(mapper.lockAuthorityMerchants(OWNER, OWNER)).thenReturn(List.of(merchant));
        when(mapper.lockProductSnapshot(PRODUCT, STORE)).thenAnswer(i -> product);
        when(mapper.lockSkuSnapshot(SKU, PRODUCT)).thenReturn(sku);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        when(members.lockActiveMemberById(anyLong())).thenAnswer(i -> i.getArgument(0));

        saved = new CmsMemberTemplate();
        saved.setId(TEMPLATE); saved.setMemberId(OWNER); saved.setTitle("店内寻宝"); saved.setDescription("找到隐藏菜单");
        saved.setDraftStatus(0); saved.setStatus(0L); saved.setPublishStatus(0L); saved.setDelFlag(0);
        templates = mock(ITemplatePublishService.class);
        when(templates.saveOrUpdateDraft(any(CmsTemplatePublishRequest.class), eq(OWNER), eq(String.valueOf(OWNER)))).thenReturn(TEMPLATE);
        CmsMemberTemplateMapper templateRows = mock(CmsMemberTemplateMapper.class);
        when(templateRows.selectCmsMemberTemplateById(TEMPLATE)).thenAnswer(i -> saved);

        records = new MerchantAuthoringRecordService(mapper, facts, access, members, mock(MerchantOperatorMapper.class), templates, templateRows);
        selection = new MerchantDraftSelectionService(access, templateRows, db.jdbc);
    }

    /** The four evidence statements of MerchantAuthoringRecordMapper.xml, run on H2. */
    MerchantAuthoringRecordMapper h2Mapper() {
        MerchantAuthoringRecordMapper mapper = mock(MerchantAuthoringRecordMapper.class);
        when(mapper.insertCandidate(any())).thenAnswer(i -> {
            MerchantAuthoringCandidate c = i.getArgument(0);
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            int n = db.jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("INSERT INTO merchant_authoring_candidate (principal_member_id, merchant_row_id, owner_member_id, facts_json, facts_hash, constraints_json, constraints_hash, ai_trace_id, template_json, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                Object[] v = {c.getPrincipalMemberId(), c.getMerchantRowId(), c.getOwnerMemberId(), c.getFactsJson(), c.getFactsHash(), c.getConstraintsJson(), c.getConstraintsHash(), c.getAiTraceId(), c.getTemplateJson(), new Timestamp(c.getCreatedAt().getTime())};
                for (int k = 0; k < v.length; k++) ps.setObject(k + 1, v[k]);
                return ps;
            }, key);
            c.setId(key.getKey().longValue());
            return n;
        });
        when(mapper.lockCandidate(anyLong())).thenAnswer(i -> db.jdbc.query("SELECT * FROM merchant_authoring_candidate WHERE id = ?", rs -> {
            if (!rs.next()) return null;
            MerchantAuthoringCandidate c = new MerchantAuthoringCandidate();
            c.setId(rs.getLong("id")); c.setPrincipalMemberId(rs.getLong("principal_member_id")); c.setMerchantRowId(rs.getLong("merchant_row_id"));
            c.setOwnerMemberId(rs.getLong("owner_member_id")); c.setFactsJson(rs.getString("facts_json")); c.setFactsHash(rs.getString("facts_hash"));
            c.setConstraintsJson(rs.getString("constraints_json")); c.setConstraintsHash(rs.getString("constraints_hash"));
            c.setAiTraceId(rs.getString("ai_trace_id")); c.setTemplateJson(rs.getString("template_json"));
            return c;
        }, (Long) i.getArgument(0)));
        when(mapper.sourceForCandidate(anyLong())).thenAnswer(i -> db.jdbc.query("SELECT * FROM merchant_authoring_source WHERE candidate_id = ?", rs -> {
            if (!rs.next()) return null;
            MerchantAuthoringSource s = new MerchantAuthoringSource();
            s.setId(rs.getLong("id")); s.setCandidateId(rs.getLong("candidate_id")); s.setActualTemplateId(rs.getLong("actual_template_id"));
            s.setTemplateContentHash(rs.getString("template_content_hash")); s.setConfirmationHash(rs.getString("confirmation_hash"));
            s.setContentHash(rs.getString("content_hash"));
            return s;
        }, (Long) i.getArgument(0)));
        when(mapper.insertSource(any())).thenAnswer(i -> {
            MerchantAuthoringSource s = i.getArgument(0);
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            int n = db.jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("INSERT INTO merchant_authoring_source (candidate_id, owner_member_id, merchant_row_id, actual_template_id, template_snapshot_json, template_content_hash, confirmation_hash, content_hash, source_json, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                Object[] v = {s.getCandidateId(), s.getOwnerMemberId(), s.getMerchantRowId(), s.getActualTemplateId(), s.getTemplateSnapshotJson(), s.getTemplateContentHash(), s.getConfirmationHash(), s.getContentHash(), s.getSourceJson(), new Timestamp(s.getCreatedAt().getTime())};
                for (int k = 0; k < v.length; k++) ps.setObject(k + 1, v[k]);
                return ps;
            }, key);
            s.setId(key.getKey().longValue());
            return n;
        });
        return mapper;
    }

    <T> T inTx(Supplier<T> call) {
        return tx.execute(status -> call.get());
    }

    static Map<String, Object> constraints() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("durationMinutes", 20); c.put("participantLimit", 4); c.put("openingHours", "10:00-20:00");
        c.put("staffRequired", false); c.put("filmingAllowed", false); c.put("noisePolicy", "安静");
        c.put("accessibleAlternative", "前台口述"); c.put("offlineAlternative", "纸质卡片"); c.put("failureExit", "直接结束");
        c.put("rewardAuthority", "UNCONFIGURED");
        return c;
    }

    long recordCandidate() {
        Map<String, Object> verified = facts.facts(OWNER, PRODUCT, SKU);
        AiTemplateFillResp generated = new AiTemplateFillResp();
        AiTemplateContent template = new AiTemplateContent();
        template.setValidationMethod(1);
        generated.setTemplate(template);
        generated.setTraceId("trace-w05-1");
        return inTx(() -> records.record(OWNER, verified, constraints(), generated)).getId();
    }

    static MerchantAuthoringConfirmation confirmation(long candidate, String title) {
        Map<String, String> fields = new HashMap<>();
        fields.put("title", title); fields.put("description", "找到隐藏菜单"); fields.put("ruleInstructions", "问店员要线索卡");
        MerchantAuthoringConfirmation c = new MerchantAuthoringConfirmation();
        c.setCandidateId(candidate); c.setAcceptedFields(fields);
        return c;
    }

    @Test
    @SuppressWarnings("unchecked")
    void confirmedCandidateIsWhatDraftSelectionListsAndResolves() {
        long candidate = recordCandidate();
        Map<String, Object> receipt = inTx(() -> records.confirm(OWNER, confirmation(candidate, "店内寻宝")));
        assertEquals(TEMPLATE, receipt.get("templateId"));
        assertEquals(true, receipt.get("draftOnly"));
        assertEquals(false, receipt.get("approved"));
        Map<String, Object> source = (Map<String, Object>) receipt.get("source");
        assertEquals(MerchantDraftSelectionService.SOURCE_KIND, source.get("kind"));

        Map<String, Object> body = new HashMap<>();
        body.put("beforeSourceId", null);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) selection.list(OWNER, body).get("rows");
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("READY", row.get("state"));
        assertEquals(source.get("sourceId"), row.get("sourceId"));
        assertEquals(TEMPLATE, row.get("memberTemplateId"));
        assertEquals(receipt.get("templateContentHash"), row.get("templateContentHash"));
        assertEquals(source, row.get("source"));

        Map<String, Object> resolve = new LinkedHashMap<>();
        resolve.put("source", source);
        resolve.put("memberTemplateId", TEMPLATE);
        resolve.put("templateContentHash", receipt.get("templateContentHash"));
        assertEquals("店内寻宝", ((Map<String, Object>) selection.resolve(OWNER, resolve).get("row")).get("templateTitle"));

        // The draft was saved through the ordinary MERCHANT draft service, never published.
        verify(templates, times(1)).saveOrUpdateDraft(any(CmsTemplatePublishRequest.class), eq(OWNER), eq(String.valueOf(OWNER)));
    }

    @Test
    void sameConfirmationReplaysOtherTextOrOtherStoreIsRefused() {
        long candidate = recordCandidate();
        Map<String, Object> first = inTx(() -> records.confirm(OWNER, confirmation(candidate, "店内寻宝")));
        Map<String, Object> replay = inTx(() -> records.confirm(OWNER, confirmation(candidate, "店内寻宝")));
        assertEquals(first, replay);
        verify(templates, times(1)).saveOrUpdateDraft(any(CmsTemplatePublishRequest.class), anyLong(), anyString());
        assertEquals(409, assertThrows(ServiceException.class, () -> inTx(() -> records.confirm(OWNER, confirmation(candidate, "别的标题")))).getCode());
        assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM merchant_authoring_source", Integer.class));
        assertEquals(403, assertThrows(ServiceException.class, () -> inTx(() -> records.confirm(STRANGER, confirmation(candidate, "店内寻宝")))).getCode());
    }

    @Test
    void changedStoreFactsOrNoTransactionAreRefused() {
        long candidate = recordCandidate();
        product.setUpdateTime(new Date(9_000L));
        assertEquals(409, assertThrows(ServiceException.class, () -> inTx(() -> records.confirm(OWNER, confirmation(candidate, "店内寻宝")))).getCode());
        assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM merchant_authoring_source", Integer.class));
        assertEquals(403, assertThrows(ServiceException.class, () -> records.confirm(OWNER, confirmation(candidate, "店内寻宝"))).getCode());
        assertTrue(db.jdbc.queryForObject("SELECT COUNT(*) FROM merchant_authoring_candidate", Integer.class) == 1);
    }
}
