package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.Map;

/** P1b SEC-14：会员「已满 18 周岁」确认的留痕（ums_member_age_attestation）。每个会员至多一行，首次确认时间不被覆盖。 */
public interface MemberAgeAttestationMapper {

    @Select("select member_id as memberId, age_policy as agePolicy, confirmed_at as confirmedAt, source as source "
            + "from ums_member_age_attestation where member_id = #{memberId}")
    Map<String, Object> selectByMember(@Param("memberId") long memberId);

    /** 已有行时不改写（insert ignore），保住首次确认的时间与口径。 */
    @Insert("insert ignore into ums_member_age_attestation (member_id, age_policy, confirmed_at, source, client_ip) "
            + "values (#{memberId}, #{agePolicy}, #{confirmedAt}, #{source}, #{clientIp})")
    int insertIfAbsent(@Param("memberId") long memberId, @Param("agePolicy") String agePolicy,
                       @Param("confirmedAt") Date confirmedAt, @Param("source") String source,
                       @Param("clientIp") String clientIp);
}
