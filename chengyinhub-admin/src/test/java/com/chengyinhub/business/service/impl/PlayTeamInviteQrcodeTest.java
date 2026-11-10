package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.PlayTeamMember;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.IPlayerWxacodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * l-misc 队伍小程序码:只给本队在队成员、邀请仍在招募窗口;scene 由邀请码编码并受 32 字符约束;
 * 微信接口不可用时降级为只给加入码,而无权 / 失效仍是拒绝。
 */
class PlayTeamInviteQrcodeTest {

    private static final Long TEAM_ID = 41L;
    private static final Long MEMBER_ID = 800L;
    private static final Long OUTSIDER_ID = 900L;
    private static final String NEW_CODE = "AbCdEfGhIjKlMnOpQr_-12";
    private static final String HEX_CODE = "00ff10a0b1c2d3e4f5061728394a5b6c";

    private PlayTeamMapper teamMapper;
    private IPlayerWxacodeService wxacode;
    private PlayTeamServiceImpl service;

    @BeforeEach
    void setUp() {
        teamMapper = mock(PlayTeamMapper.class);
        wxacode = mock(IPlayerWxacodeService.class);
        service = new PlayTeamServiceImpl();
        ReflectionTestUtils.setField(service, "teamMapper", teamMapper);
        ReflectionTestUtils.setField(service, "playerWxacodeService", wxacode);
    }

    @Test
    void memberGetsQrForNewStyleCode() {
        stubTeam(NEW_CODE, PlayTeam.STATUS_RECRUITING);
        when(teamMapper.selectActiveMember(TEAM_ID, MEMBER_ID)).thenReturn(new PlayTeamMember());
        when(wxacode.getOrCreateScene("tm." + NEW_CODE)).thenReturn("https://oss.example/qr.png");

        Map<String, Object> result = service.inviteQrcode(TEAM_ID, MEMBER_ID);

        assertEquals("READY", result.get("qrState"));
        assertEquals("https://oss.example/qr.png", result.get("qrcodeUrl"));
        assertEquals(NEW_CODE, result.get("inviteCode"));
        assertEquals(TEAM_ID, result.get("teamId"));
    }

    @Test
    void legacyHexCodeIsReencodedWithinSceneLimit() {
        String scene = PlayTeamServiceImpl.teamScene(HEX_CODE);
        assertEquals("th.AP8QoLHC0-T1BhcoOUpbbA", scene);
        assertTrue(scene.length() <= 32);
        assertTrue(PlayTeamServiceImpl.teamScene(NEW_CODE).length() <= 32);
        assertNull(PlayTeamServiceImpl.teamScene("not-a-code"));
        assertNull(PlayTeamServiceImpl.teamScene(null));
    }

    @Test
    void wechatFailureDegradesToJoinCodeOnly() {
        stubTeam(NEW_CODE, PlayTeam.STATUS_RECRUITING);
        when(teamMapper.selectActiveMember(TEAM_ID, MEMBER_ID)).thenReturn(new PlayTeamMember());
        when(wxacode.getOrCreateScene(anyString())).thenThrow(new IllegalStateException("微信小程序码签发失败(errcode=45009)"));

        Map<String, Object> result = service.inviteQrcode(TEAM_ID, MEMBER_ID);

        assertEquals("UNAVAILABLE", result.get("qrState"));
        assertFalse(result.containsKey("qrcodeUrl"));
        assertEquals(NEW_CODE, result.get("inviteCode"));
    }

    @Test
    void unknownCodeShapeDegradesWithoutCallingWechat() {
        stubTeam("weird code", PlayTeam.STATUS_RECRUITING);
        when(teamMapper.selectActiveMember(TEAM_ID, MEMBER_ID)).thenReturn(new PlayTeamMember());

        Map<String, Object> result = service.inviteQrcode(TEAM_ID, MEMBER_ID);

        assertEquals("UNAVAILABLE", result.get("qrState"));
        verify(wxacode, never()).getOrCreateScene(anyString());
    }

    @Test
    void outsiderIsRefusedAndNoCodeIsMinted() {
        stubTeam(NEW_CODE, PlayTeam.STATUS_RECRUITING);
        when(teamMapper.selectActiveMember(TEAM_ID, OUTSIDER_ID)).thenReturn(null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.inviteQrcode(TEAM_ID, OUTSIDER_ID));
        assertEquals("无权查看该队伍", e.getMessage());
        verify(wxacode, never()).getOrCreateScene(anyString());
    }

    @Test
    void endedTeamIsRefused() {
        stubTeam(NEW_CODE, PlayTeam.STATUS_LOCKED + 1);
        when(teamMapper.selectActiveMember(TEAM_ID, MEMBER_ID)).thenReturn(new PlayTeamMember());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.inviteQrcode(TEAM_ID, MEMBER_ID));
        assertEquals("邀请已失效", e.getMessage());
        verify(wxacode, never()).getOrCreateScene(anyString());
    }

    @Test
    void guestAndMissingTeamAreRefused() {
        assertThrows(IllegalStateException.class, () -> service.inviteQrcode(TEAM_ID, null));
        assertThrows(IllegalArgumentException.class, () -> service.inviteQrcode(0L, MEMBER_ID));
        verify(wxacode, never()).getOrCreateScene(anyString());
    }

    private void stubTeam(String code, int status) {
        PlayTeam team = new PlayTeam();
        team.setId(TEAM_ID);
        team.setInviteCode(code);
        team.setStatus(status);
        when(teamMapper.selectPlayTeamById(TEAM_ID)).thenReturn(team);
    }
}
