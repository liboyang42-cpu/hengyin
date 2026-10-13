package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.vo.NicknameCheckVO;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.INicknameSuggestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class NicknameSuggestionServiceImpl implements INicknameSuggestionService {
    public static final String ERROR_NICKNAME_INVALID = "NICKNAME_INVALID";
    public static final String ERROR_NICKNAME_TAKEN = "NICKNAME_TAKEN";
    public static final String ERROR_NICKNAME_EXHAUSTED = "NICKNAME_SUFFIX_EXHAUSTED";
    private static final int MAX_LENGTH = 24;
    private static final int SUFFIX_WIDTH = 4;
    private static final int SUFFIX_SPACE = 36 * 36 * 36 * 36;
    private static final int MAX_ATTEMPTS = 128;

    @Autowired private UmsMemberMapper umsMemberMapper;

    @Override
    public NicknameCheckVO check(String nickname, Long excludeMemberId) {
        String normalized = normalize(nickname);
        NicknameCheckVO result = new NicknameCheckVO();
        result.setNickname(normalized);
        if (!valid(normalized)) {
            result.setErrorCode(ERROR_NICKNAME_INVALID);
            return result;
        }
        boolean available = umsMemberMapper.countActiveNickname(normalized, excludeMemberId) == 0;
        result.setAvailable(available);
        result.setErrorCode(available ? null : ERROR_NICKNAME_TAKEN);
        return result;
    }

    @Override
    public NicknameCheckVO suggest(String baseName, Integer cursor, Long excludeMemberId) {
        String normalized = normalize(baseName);
        if (!valid(normalized)) {
            NicknameCheckVO invalid = new NicknameCheckVO();
            invalid.setNickname(normalized);
            invalid.setErrorCode(ERROR_NICKNAME_INVALID);
            return invalid;
        }
        int start = cursor == null ? 0 : Math.floorMod(cursor, SUFFIX_SPACE);
        String base = truncate(stripGeneratedSuffix(normalized), MAX_LENGTH - SUFFIX_WIDTH - 1);
        for (int offset = 1; offset <= MAX_ATTEMPTS; offset++) {
            int suffix = (start + offset) % SUFFIX_SPACE;
            if (suffix == 0) continue;
            String candidate = base + "#" + leftPad(Integer.toString(suffix, 36).toUpperCase(Locale.ROOT));
            if (umsMemberMapper.countActiveNickname(candidate, excludeMemberId) == 0) {
                NicknameCheckVO result = new NicknameCheckVO();
                result.setNickname(candidate);
                result.setAvailable(true);
                result.setCursor(suffix);
                return result;
            }
        }
        NicknameCheckVO exhausted = new NicknameCheckVO();
        exhausted.setNickname(base);
        exhausted.setErrorCode(ERROR_NICKNAME_EXHAUSTED);
        return exhausted;
    }

    private String normalize(String value) { return value == null ? null : value.trim(); }

    private String stripGeneratedSuffix(String value) {
        return value != null && value.matches(".*#[0-9A-Z]{4}$")
                ? value.substring(0, value.length() - SUFFIX_WIDTH - 1) : value;
    }

    private boolean valid(String value) {
        if (value == null) return false;
        int length = value.codePointCount(0, value.length());
        return length >= 2 && length <= MAX_LENGTH && value.matches("[\\p{L}\\p{N}_#\\-.· ]+");
    }

    private String truncate(String value, int limit) {
        int length = value.codePointCount(0, value.length());
        return length <= limit ? value : value.substring(0, value.offsetByCodePoints(0, limit));
    }

    private String leftPad(String suffix) {
        StringBuilder out = new StringBuilder(SUFFIX_WIDTH);
        for (int i = suffix.length(); i < SUFFIX_WIDTH; i++) out.append('0');
        return out.append(suffix).toString();
    }
}
