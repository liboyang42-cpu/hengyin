package com.chengyinhub.business.service;

/** canonical live 权威写入口的统一版本失效 seam。 */
public interface ITopicSaleCanonicalMutationService {
    Long invalidateApprovedCanonical(Long topicId, String mutationSource);
}
