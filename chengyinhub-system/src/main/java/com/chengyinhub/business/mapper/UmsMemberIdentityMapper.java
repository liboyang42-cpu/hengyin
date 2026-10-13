package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.UmsMemberIdentity;

/**
 * 发布者实名登记。加解密全部在 XML 里靠 typeHandler 接线(与 CmsRegistrationMapper 同一套),
 * Java 侧不出现密文,也不出现「忘了带 typeHandler 就把明文写进去」的第二条写路径。
 */
public interface UmsMemberIdentityMapper {
    int insertIdentity(UmsMemberIdentity identity);

    UmsMemberIdentity selectByMemberId(Long memberId);

    boolean existsByMemberId(Long memberId);

    int deleteByMemberId(Long memberId);
}
