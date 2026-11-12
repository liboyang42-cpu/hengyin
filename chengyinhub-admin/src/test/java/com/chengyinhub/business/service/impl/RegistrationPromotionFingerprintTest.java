package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.domain.dto.RegistrationOrderResult;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IRegistrationCreationTransaction;
import com.chengyinhub.business.service.IRegistrationOrderCreateGate;
import com.chengyinhub.business.service.RegistrationRequestConflictException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 幂等指纹必须接住促销意图:同一 requestId 换优惠码/换促销报价 = 不同内容 ⇒ 409。
 *
 * <p>反面同样重要:两个促销字段都为空时(旧客户端、无促销配置),指纹与历史方案逐字相同,
 * 升级瞬间在途订单的重放不能被误判成"内容已变"。
 */
class RegistrationPromotionFingerprintTest {

    private static final Long MEMBER_ID = 88L;

    @Test
    void changingOfferCodeUnderSameRequestIdChangesTheFingerprint() {
        assertNotEquals(fingerprintOf(request("COMBO-A", "SIGN-A")),
                fingerprintOf(request("COMBO-B", "SIGN-A")),
                "换优惠码是内容变更,必须换指纹");
    }

    @Test
    void changingPromotionSignUnderSameRequestIdChangesTheFingerprint() {
        assertNotEquals(fingerprintOf(request(null, "SIGN-A")),
                fingerprintOf(request(null, "SIGN-B")),
                "同一优惠码但规则版本变了(促销指纹变了),同样不能当重放");
    }

    @Test
    void emptyMarketingIntentKeepsTheLegacyFingerprint() {
        // 两个促销字段都为空 ⇒ 与历史算法同串;否则升级瞬间全部在途订单重放都会 409。
        assertEquals(fingerprintOf(request(null, null)), fingerprintOf(request(null, null)));
    }

    @Test
    void replayWithChangedOfferCodeIsRejectedNotSilentlyReused() {
        String stored = fingerprintOf(request("COMBO-A", "SIGN-A"));
        ICmsRegistrationService registrationService = registrationServiceReturning(persisted(stored));

        RegistrationOrderServiceImpl service = newService(registrationService, mockTransaction());
        assertThrows(RegistrationRequestConflictException.class,
                () -> service.createOrReplay(MEMBER_ID, request("COMBO-B", "SIGN-A")),
                "不同优惠不能借同一 requestId 静默复用原单");
        verify(registrationService, never()).bindLegacyRequestFingerprint(any(), anyString(), anyString());
    }

    @Test
    void replayOfIdenticalRequestStaysIdempotent() {
        String stored = fingerprintOf(request("COMBO-A", "SIGN-A"));
        ICmsRegistrationService registrationService = registrationServiceReturning(persisted(stored));

        RegistrationOrderServiceImpl service = newService(registrationService, mockTransaction());
        RegistrationOrderResult result = service.createOrReplay(MEMBER_ID, request("COMBO-A", "SIGN-A"));
        assertTrue(result.isReplayed());
    }

    @Test
    void legacyOrderWithoutFingerprintCannotBeBackfilledWithMarketingIntent() {
        ICmsRegistrationService registrationService = registrationServiceReturning(persisted(null));

        RegistrationOrderServiceImpl service = newService(registrationService, mockTransaction());
        assertThrows(RegistrationRequestConflictException.class,
                () -> service.createOrReplay(MEMBER_ID, request("COMBO-A", "SIGN-A")),
                "旧单没有促销快照,不能给它补绑一个「看起来有优惠」的指纹");
        verify(registrationService, never()).bindLegacyRequestFingerprint(any(), anyString(), anyString());
    }

    // ------------------------------------------------------------------ fixture

    /** 走一次真实 createOrReplay,截获服务端算出的指纹(每次用独立的 transaction mock)。 */
    private String fingerprintOf(RegistrationRequest request) {
        IRegistrationCreationTransaction transaction = mockTransaction();
        RegistrationOrderServiceImpl service =
                newService(registrationServiceReturning(null), transaction);
        service.createOrReplay(MEMBER_ID, request);

        ArgumentCaptor<String> fingerprint = ArgumentCaptor.forClass(String.class);
        verify(transaction).create(eq(MEMBER_ID), any(RegistrationRequest.class), anyString(),
                fingerprint.capture());
        return fingerprint.getValue();
    }

    /** replay 查询返回空列表(首次下单)或指定行(重放)。 */
    private ICmsRegistrationService registrationServiceReturning(CmsRegistration persisted) {
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(persisted == null
                ? Collections.emptyList()
                : Collections.singletonList(persisted));
        return registrationService;
    }

    private IRegistrationCreationTransaction mockTransaction() {
        IRegistrationCreationTransaction transaction = mock(IRegistrationCreationTransaction.class);
        when(transaction.create(any(), any(), anyString(), anyString())).thenAnswer(invocation -> {
            CmsRegistration created = new CmsRegistration();
            created.setId(1000L);
            created.setMemberId(MEMBER_ID);
            created.setRequestId(invocation.getArgument(2));
            created.setRegistrationStatus(1);
            List<CmsRegistration> list = new ArrayList<>();
            list.add(created);
            return list;
        });
        return transaction;
    }

    private RegistrationOrderServiceImpl newService(ICmsRegistrationService registrationService,
                                                    IRegistrationCreationTransaction transaction) {
        RegistrationOrderServiceImpl service = new RegistrationOrderServiceImpl();
        ReflectionTestUtils.setField(service, "registrationService", registrationService);
        ReflectionTestUtils.setField(service, "registrationCreationTransaction", transaction);
        ReflectionTestUtils.setField(service, "registrationOrderCreateGate",
                mock(IRegistrationOrderCreateGate.class));
        return service;
    }

    private CmsRegistration persisted(String requestFingerprint) {
        CmsRegistration row = new CmsRegistration();
        row.setId(1000L);
        row.setMemberId(MEMBER_ID);
        row.setRequestId("req-1");
        row.setRequestFingerprint(requestFingerprint);
        row.setRegistrationStatus(1);
        row.setOwnerType(2L);
        row.setOwnerId(200L);
        row.setTicketId(301L);
        row.setOrderNum(1L);
        row.setRealName("张三");
        row.setPhone("18800000000");
        return row;
    }

    private RegistrationRequest request(String offerCode, String marketingSign) {
        RegistrationRequest request = new RegistrationRequest();
        request.setOwnerType(2);
        request.setOwnerId(200L);
        request.setTicketId(301L);
        request.setIsUsePoint(0);
        request.setRealName("张三");
        request.setPhone("18800000000");
        request.setRequestId("req-1");
        request.setOfferCode(offerCode);
        request.setMarketingSign(marketingSign);
        return request;
    }
}
