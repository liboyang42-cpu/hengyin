package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.vo.NicknameCheckVO;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NicknameSuggestionServiceTest {
    private static final String BASE_NAME = "城市玩家";
    private static final String TAKEN_CANDIDATE = "城市玩家#0001";
    private static final String AVAILABLE_CANDIDATE = "城市玩家#0002";
    private static final Long EXCLUDED_MEMBER_ID = 91L;

    @Test
    void suggestionSkipsOccupiedSuffixAndReturnsAJustCheckedCandidate() {
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        when(members.countActiveNickname(TAKEN_CANDIDATE, EXCLUDED_MEMBER_ID)).thenReturn(1);
        when(members.countActiveNickname(AVAILABLE_CANDIDATE, EXCLUDED_MEMBER_ID)).thenReturn(0);
        NicknameSuggestionServiceImpl service = new NicknameSuggestionServiceImpl();
        ReflectionTestUtils.setField(service, "umsMemberMapper", members);

        NicknameCheckVO result = service.suggest(BASE_NAME, 0, EXCLUDED_MEMBER_ID);

        assertEquals(AVAILABLE_CANDIDATE, result.getNickname());
        assertTrue(result.isAvailable());
        assertEquals(Integer.valueOf(2), result.getCursor());
    }

    @Test
    void occupiedCheckReturnsStableMachineReadableCode() {
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        when(members.countActiveNickname(BASE_NAME, null)).thenReturn(1);
        NicknameSuggestionServiceImpl service = new NicknameSuggestionServiceImpl();
        ReflectionTestUtils.setField(service, "umsMemberMapper", members);

        NicknameCheckVO result = service.check(BASE_NAME, null);

        assertFalse(result.isAvailable());
        assertEquals(NicknameSuggestionServiceImpl.ERROR_NICKNAME_TAKEN, result.getErrorCode());
    }

    @Test
    void rerollReplacesTheGeneratedSuffixInsteadOfStackingAnotherOne() {
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        when(members.countActiveNickname(AVAILABLE_CANDIDATE, EXCLUDED_MEMBER_ID)).thenReturn(0);
        NicknameSuggestionServiceImpl service = new NicknameSuggestionServiceImpl();
        ReflectionTestUtils.setField(service, "umsMemberMapper", members);

        NicknameCheckVO result = service.suggest(TAKEN_CANDIDATE, 1, EXCLUDED_MEMBER_ID);

        assertEquals(AVAILABLE_CANDIDATE, result.getNickname());
    }
}
