package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.NicknameCheckVO;

public interface INicknameSuggestionService {
    NicknameCheckVO check(String nickname, Long excludeMemberId);
    NicknameCheckVO suggest(String baseName, Integer cursor, Long excludeMemberId);
}
