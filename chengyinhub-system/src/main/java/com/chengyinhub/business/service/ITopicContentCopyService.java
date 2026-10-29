package com.chengyinhub.business.service;

import java.util.Map;

/** Catalog content-copy commands; existing callers keep their authorization and response contracts. */
public interface ITopicContentCopyService {
    Long copyCmsTopic(Long id, Map<String, Object> params);
    Long copyOfficialCmsTopic(Long id, Map<String, Object> params);
}
