package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.common.utils.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * F-L6-17：把 ums_member 中仍为明文的存量口令一次性改写为 BCrypt。
 *
 * <p>口令语义不变（同一明文的哈希），所以不吊销会话、不改登录逻辑；登录路径对 {@code $2} 前缀
 * 本来就走 BCrypt 比对。每行独立 CAS（逐字节等于读到的旧值才写），并发改密或登录升级时本行跳过，
 * 绝不覆盖较新的口令。日志只记计数，不记 id 以外的任何口令信息。
 */
@Service
public class MemberLegacyPasswordHashService {

    private static final Logger log = LoggerFactory.getLogger(MemberLegacyPasswordHashService.class);
    private static final int PAGE_SIZE = 100;

    @Autowired
    private UmsMemberMapper memberMapper;

    /**
     * @param limit 本次最多处理的行数（读到的行，包括跳过的）
     * @return 扫描/改写/跳过计数
     */
    public Result hashLegacyPasswords(int limit) {
        int hashed = 0;
        int skipped = 0;
        int scanned = 0;
        long cursor = 0L;
        while (scanned < limit) {
            List<UmsMember> page = memberMapper.selectLegacyPlaintextPasswords(cursor,
                    Math.min(PAGE_SIZE, limit - scanned));
            if (page == null || page.isEmpty()) {
                break;
            }
            for (UmsMember row : page) {
                scanned++;
                cursor = Math.max(cursor, row.getId());
                String observed = row.getPassword();
                if (observed == null || observed.isEmpty() || observed.startsWith("$2")) {
                    skipped++;
                    continue;
                }
                String hash;
                try {
                    hash = SecurityUtils.encryptPassword(observed);
                } catch (IllegalArgumentException tooLong) {
                    // BCrypt 只接受 72 字节以内；这类账号保持原状并计入跳过，由运维单独处理。
                    skipped++;
                    log.warn("[LegacyPasswordHash] member {} skipped: password not BCrypt-compatible", row.getId());
                    continue;
                }
                if (memberMapper.hashLegacyPasswordIfUnchanged(row.getId(), observed, hash) == 1) {
                    hashed++;
                } else {
                    skipped++;
                }
            }
        }
        return new Result(scanned, hashed, skipped);
    }

    public static final class Result {
        private final int scanned;
        private final int hashed;
        private final int skipped;

        public Result(int scanned, int hashed, int skipped) {
            this.scanned = scanned;
            this.hashed = hashed;
            this.skipped = skipped;
        }

        public int getScanned() { return scanned; }
        public int getHashed() { return hashed; }
        public int getSkipped() { return skipped; }
    }
}
