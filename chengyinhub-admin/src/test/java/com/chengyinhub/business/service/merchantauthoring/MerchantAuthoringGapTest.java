package com.chengyinhub.business.service.merchantauthoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.PmsProduct;
import com.chengyinhub.business.domain.PmsSku;
import com.chengyinhub.business.domain.merchantauthoring.MerchantAuthoringRows.Release;
import com.chengyinhub.business.mapper.merchantauthoring.MerchantAuthoringMapper;
import com.chengyinhub.business.mapper.merchantauthoring.MerchantAuthoringMapperTest;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IPmsProductService;
import com.chengyinhub.business.service.IPmsSkuService;
import com.chengyinhub.business.service.ai.AiGatewayService;
import com.chengyinhub.business.service.merchantauthoring.MerchantAuthoringService.Request;
import com.chengyinhub.common.exception.ServiceException;

/** The app-facing changes on top of W05: createDraft (drafts POST) from skuId without a fact version, idempotent by
 *  request id; and the player game read (authoring/player/{merchantId}/game). */
class MerchantAuthoringGapTest {
    static final long OWNER = 9002L, STORE = 8001L;
    AuthoringTx tx;
    MerchantAuthoringService service;

    @BeforeEach
    void setUp() throws Exception {
        SqlSessionFactory factory = MerchantAuthoringMapperTest.factory();
        tx = new AuthoringTx() {
            public <T> T call(Function<MerchantAuthoringMapper, T> work) {
                try (SqlSession session = factory.openSession(false)) {
                    T value = work.apply(session.getMapper(MerchantAuthoringMapper.class));
                    session.commit();
                    return value;
                }
            }
        };
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(eq(OWNER), eq(MerchantPermission.PROJECT_MANAGE)))
                .thenReturn(new MerchantAccessContext(OWNER, STORE, OWNER, MerchantRoleCode.MERCHANT_OWNER));
        IPmsProductService products = mock(IPmsProductService.class);
        PmsProduct product = new PmsProduct();
        product.setId(5L);
        product.setMerchantId(STORE);
        product.setDelFlag(0);
        when(products.selectPmsProductById(5L)).thenReturn(product);
        IPmsSkuService skus = mock(IPmsSkuService.class);
        PmsSku sku = new PmsSku();
        sku.setId(51L);
        sku.setProductId(5L);
        when(skus.selectPmsSkuById(51L)).thenReturn(sku);
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(STORE);
        merchant.setDelFlag(0);
        when(merchants.selectMmsMerchantById(STORE)).thenReturn(merchant);
        MerchantAuthoringPolicy policy = new MerchantAuthoringPolicy("m", "p", 1, Set.of("tpl"), Set.of(), true, true,
                "PUBLIC", "Asia/Shanghai", Set.of("MORNING"), null, null);
        service = new MerchantAuthoringService(tx, mock(AiGatewayService.class), mock(IAuditTaskService.class), products, skus,
                access, mock(ICmsTopicService.class), merchants, policy, null);
    }

    @Test
    void createDraftFromSkuWithoutFactVersionIsIdempotentByRequestId() {
        Request request = new Request();
        request.requestId = "6E0B4F0C-8A51-4B8E-9F4B-0D7A3C2B1A00";
        request.templateId = "tpl";
        request.skuId = 51L;
        request.scene = "PRODUCT";
        Map<String, Object> first = service.createDraft(OWNER, request);
        Map<String, Object> again = service.createDraft(OWNER, request);
        assertEquals(first.get("draftId"), again.get("draftId"));
        assertTrue(((String) first.get("draftId")).matches("[A-Za-z0-9_-]{1,64}"));
        assertEquals(0, first.get("currentVersion"));
        assertEquals(STORE, first.get("merchantId"));
        assertEquals(1, service.listDrafts(OWNER).size());

        Request noProduct = new Request();
        noProduct.requestId = "6E0B4F0C-8A51-4B8E-9F4B-0D7A3C2B1A01";
        noProduct.scene = "PRODUCT";
        ServiceException refused = assertThrows(ServiceException.class, () -> service.createDraft(OWNER, noProduct));
        assertEquals("PRODUCT_REQUIRED", refused.getMessage());
    }

    @Test
    void playerGameIsNoneThenPublishedFactsThenWithdrawn() {
        assertEquals("NONE", service.playerGame(STORE).get("outcome"));
        Release release = new Release();
        release.ownerId = OWNER;
        release.requestId = "pub-1";
        release.payloadFingerprint = "f".repeat(64);
        release.draftId = "d1";
        release.revisionId = 1L;
        release.revisionVersion = 1;
        release.merchantId = STORE;
        release.bindingKey = "STORE";
        release.snapshotJson = "{\"business_segment\":\"MORNING\",\"duration_minutes\":20,\"reward_rule_ref\":\"secret\"}";
        release.snapshotHash = "a".repeat(64);
        release.factHash = "b".repeat(64);
        release.contentHash = "c".repeat(64);
        release.status = "PUBLISHED";
        release.visibility = "PUBLIC";
        release.publishedAtMs = 1L;
        tx.call(mapper -> mapper.insertRelease(release));
        Map<String, Object> published = service.playerGame(STORE);
        assertEquals("PUBLISHED", published.get("outcome"));
        assertEquals(1, published.get("revisionVersion"));
        Map<?, ?> facts = (Map<?, ?>) published.get("facts");
        assertEquals("MORNING", facts.get("business_segment"));
        assertFalse(facts.containsKey("reward_rule_ref"));
        tx.call(mapper -> mapper.withdrawRelease(OWNER, release.id, 2L, "w-1"));
        Map<String, Object> withdrawn = service.playerGame(STORE);
        assertEquals("WITHDRAWN", withdrawn.get("outcome"));
        assertFalse(withdrawn.containsKey("facts"));
        assertEquals("NONE", service.playerGame(424242L).get("outcome"));
    }
}
