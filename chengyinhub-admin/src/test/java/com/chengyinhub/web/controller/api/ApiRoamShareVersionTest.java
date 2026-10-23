package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.mapper.RoamShareSnapshotMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.support.RoamSharePrivacyService;
import com.chengyinhub.business.service.support.RoamShareSnapshotService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** #23:按令牌撤销单条 + 发布版本 CAS;/revoke(撤销全部)不变。 */
class ApiRoamShareVersionTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef"; // gitleaks:allow 固定的假分享令牌(32 位十六进制),不是密钥
    private static final String SNAPSHOT = "{\"routeName\":\"外滩足迹\",\"track\":[{\"lat\":31.23,\"lng\":121.47}],\"pois\":[]}";

    private static class AsMember extends ApiRoamShareController {
        private final long id;
        AsMember(long id) { this.id = id; }
        @Override Long uid() { return id; }
    }

    private static ApiRoamShareController controller(long memberId, RoamShareSnapshotMapper mapper) {
        ApiRoamShareController c = new AsMember(memberId);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        when(members.lockMemberById(memberId)).thenReturn(memberId);
        ReflectionTestUtils.setField(c, "snapshotMapper", mapper);
        ReflectionTestUtils.setField(c, "privacyService", new RoamSharePrivacyService());
        RoamShareSnapshotService service = new RoamShareSnapshotService(mapper, members);
        ReflectionTestUtils.setField(service, "locationConsent", mock(com.chengyinhub.business.service.impl.RoamLocationConsentService.class));
        com.chengyinhub.business.mapper.RoamMapper roam = mock(com.chengyinhub.business.mapper.RoamMapper.class);
        com.chengyinhub.business.domain.RoamSession session = new com.chengyinhub.business.domain.RoamSession();
        session.setId(12L); session.setMemberId(memberId); session.setStatus(1); session.setFinishedAt(new java.util.Date());
        when(roam.selectOwnedSessionForUpdate(12L, memberId)).thenReturn(session);
        ReflectionTestUtils.setField(service, "roamMapper", roam);
        ReflectionTestUtils.setField(c, "snapshotService", service);
        ReflectionTestUtils.setField(c, "wxContentSecurityService", mock(com.chengyinhub.business.service.IWxContentSecurityService.class));
        ReflectionTestUtils.setField(c, "umsMemberService", mock(com.chengyinhub.business.service.IUmsMemberService.class));
        return c;
    }

    @Test
    void revokeTokenDeletesOnlyThatShareOfTheCaller() {
        RoamShareSnapshotMapper mapper = mock(RoamShareSnapshotMapper.class);
        when(mapper.deleteByMemberToken(9L, TOKEN)).thenReturn(1);
        AjaxResult r = controller(9, mapper).revokeToken(TOKEN);
        assertEquals(200, r.get("code"));
        assertEquals(1, ((Map<?, ?>) r.get("data")).get("revoked"));
        verify(mapper).deleteByMemberToken(9L, TOKEN);
        verify(mapper, never()).deleteByMember(anyLong());
    }

    @Test
    void someoneElsesTokenReadsAsAlreadyGoneNotAsAnError() {
        RoamShareSnapshotMapper mapper = mock(RoamShareSnapshotMapper.class);
        when(mapper.deleteByMemberToken(10L, TOKEN)).thenReturn(0);
        AjaxResult r = controller(10, mapper).revokeToken(TOKEN);
        assertEquals(200, r.get("code"));
        assertEquals(0, ((Map<?, ?>) r.get("data")).get("revoked"));
    }

    @Test
    void malformedTokenAndAnonymousNeverTouchTheTable() {
        RoamShareSnapshotMapper mapper = mock(RoamShareSnapshotMapper.class);
        AjaxResult bad = controller(9, mapper).revokeToken("1 or 1=1");
        assertEquals("SHARE_TOKEN_INVALID", bad.get("errorCode"));
        assertEquals(500, controller(0, mapper).revokeToken(TOKEN).get("code"));
        verify(mapper, never()).deleteByMemberToken(anyLong(), anyString());
    }

    @Test
    void publishReturnsVersionAndStaleExpectedVersionIsConflictWithoutWriting() {
        RoamShareSnapshotMapper mapper = mock(RoamShareSnapshotMapper.class);
        when(mapper.selectTokenByMemberTs(9L, 5L)).thenReturn(TOKEN);
        when(mapper.selectVersionByMemberTs(9L, 5L)).thenReturn(3);
        ApiRoamShareController c = controller(9, mapper);

        AjaxResult stale = c.publish(5L, 12L, SNAPSHOT, 2);
        assertEquals(409, stale.get("code"));
        assertEquals("SHARE_VERSION_CONFLICT", stale.get("errorCode"));
        verify(mapper, never()).upsertOwnedSnapshot(anyString(), anyLong(), anyLong(), anyLong(), anyString());

        AjaxResult ok = c.publish(5L, 12L, SNAPSHOT, 3);
        assertEquals(200, ok.get("code"));
        assertEquals(TOKEN, ((Map<?, ?>) ok.get("data")).get("token"));
        assertEquals(3, ((Map<?, ?>) ok.get("data")).get("version"));
    }

    @Test
    void expectedVersionOnARevokedShareIsConflictNotSilentRecreate() {
        RoamShareSnapshotMapper mapper = mock(RoamShareSnapshotMapper.class);
        when(mapper.selectTokenByMemberTs(9L, 5L)).thenReturn(null);
        AjaxResult r = controller(9, mapper).publish(5L, 12L, SNAPSHOT, 1);
        assertEquals("SHARE_VERSION_CONFLICT", r.get("errorCode"));
        verify(mapper, never()).upsertOwnedSnapshot(anyString(), anyLong(), anyLong(), anyLong(), anyString());
        verify(mapper, never()).deleteOldestByMember(anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void legacyPublishWithoutExpectedVersionStillWorks() {
        RoamShareSnapshotMapper mapper = mock(RoamShareSnapshotMapper.class);
        when(mapper.selectTokenByMemberTs(9L, 5L)).thenReturn(null, TOKEN);
        when(mapper.selectVersionByMemberTs(9L, 5L)).thenReturn(1);
        AjaxResult r = controller(9, mapper).publish(5L, 12L, SNAPSHOT);
        assertEquals(200, r.get("code"));
        assertEquals(1, ((Map<?, ?>) r.get("data")).get("version"));
        verify(mapper).upsertOwnedSnapshot(anyString(), any(), any(), any(), anyString());
    }
}
