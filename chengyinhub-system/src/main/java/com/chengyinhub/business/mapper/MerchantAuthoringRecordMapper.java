package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantAuthoringCandidate;
import com.chengyinhub.business.domain.MerchantAuthoringSource;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.PmsProduct;
import com.chengyinhub.business.domain.PmsSku;
import org.apache.ibatis.annotations.Param;

public interface MerchantAuthoringRecordMapper {
    int insertCandidate(MerchantAuthoringCandidate candidate);
    MerchantAuthoringCandidate lockCandidate(@Param("id") Long id);
    MerchantAuthoringSource sourceForCandidate(@Param("candidateId") Long candidateId);
    int insertSource(MerchantAuthoringSource source);
    java.util.List<Long> lockMerchantOwnerRows(@Param("ownerMemberId") Long ownerMemberId);
    Long lockMerchant(@Param("merchantRowId") Long merchantRowId);
    Long lockProduct(@Param("id") Long id, @Param("merchantRowId") Long merchantRowId);
    Long lockSku(@Param("id") Long id, @Param("productId") Long productId);
    MmsMerchant lockMerchantSnapshot(@Param("merchantRowId") Long merchantRowId);
    java.util.List<MmsMerchant> lockAuthorityMerchants(@Param("principalMemberId") Long principalMemberId,
            @Param("ownerMemberId") Long ownerMemberId);
    PmsProduct lockProductSnapshot(@Param("id") Long id, @Param("merchantRowId") Long merchantRowId);
    PmsSku lockSkuSnapshot(@Param("id") Long id, @Param("productId") Long productId);
}
