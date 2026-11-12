package com.chengyinhub.business.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.domain.VideoBountyEvidence;

/**
 * 视频播放证据 Mapper。insert-only:{@code uk_evidence_hash} 做录入幂等。
 *
 * @author chengyinhub
 */
public interface VideoBountyEvidenceMapper
{
    List<VideoBountyEvidence> selectBySubmission(@Param("submissionId") Long submissionId);

    /** 同组全部证据(封顶计算用)。 */
    List<VideoBountyEvidence> selectByGroup(@Param("campaignId") Long campaignId,
                                            @Param("originGroupKey") String originGroupKey);

    VideoBountyEvidence selectByHash(@Param("evidenceHash") String evidenceHash);

    /** 同组证据水位(最大 id),作为应计幂等键的一部分。 */
    /**
     * 组内证据水位（MAX(evidence.id)），按<b>运营核验组</b>统计。
     *
     * <p>必须用 {@code verified_group_key}：核验组可能由作者自报不同的 origin 键合并而成，
     * 按 origin 过滤会让"并组后新证据"不推进水位 ⇒ 重算命中旧幂等键 ⇒ 永久返回旧额。
     */
    Long selectMaxIdByVerifiedGroup(@Param("campaignId") Long campaignId,
                                    @Param("verifiedGroupKey") String verifiedGroupKey);

    int insertEvidence(VideoBountyEvidence evidence);
}
