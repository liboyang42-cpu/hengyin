package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.mapper.ConsentRecordMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.impl.RoamLocationConsentService;
import com.chengyinhub.common.config.ComplianceProperties;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F-L5C-01/12: 撤回漫游定位必须在服务端立刻停止对他人展示位置。 */
class RoamLocationRevokeHidesPresenceTest {

    private RoamLocationConsentService service(RoamMapper roam, ConsentRecordMapper records) {
        ComplianceProperties properties = new ComplianceProperties();
        properties.setDocVersions(Collections.singletonMap("privacy_policy", "v-test"));
        when(roam.lockClaimOwner(9L)).thenReturn(9L);
        when(records.insertConsentRecord(any())).thenReturn(1);
        return new RoamLocationConsentService(roam, records, properties);
    }

    @Test
    void revokeClosesNearbySharingOnEveryOpenSessionInTheSameTransaction() {
        RoamMapper roam = mock(RoamMapper.class);
        ConsentRecordMapper records = mock(ConsentRecordMapper.class);
        RoamLocationConsentService consent = service(roam, records);
        ConsentRecord receipt = new ConsentRecord();
        receipt.setEventType("REVOKE");
        // 第一次查(幂等判定)为空,写入后回读得到刚写的行
        when(records.selectByRequestId(9L, "revoke-1")).thenReturn(null, receipt);

        assertEquals("REVOKE", consent.record(9L, "REVOKE", "revoke-1", null, "").getEventType());
        verify(roam).updatePresenceVisibility(isNull(), eq(9L), eq(false));
    }

    @Test
    void agreeNeverOpensSharingByItself() {
        RoamMapper roam = mock(RoamMapper.class);
        ConsentRecordMapper records = mock(ConsentRecordMapper.class);
        RoamLocationConsentService consent = service(roam, records);
        ConsentRecord receipt = new ConsentRecord();
        receipt.setEventType("AGREE");
        when(records.selectByRequestId(9L, "agree-1")).thenReturn(null, receipt);

        consent.record(9L, "AGREE", "agree-1", "", "");
        verify(roam, never()).updatePresenceVisibility(any(), anyLong(), anyBoolean());
    }
}
