package com.chengyinhub.business.service.merchantauthoring;

import java.util.function.Function;

import com.chengyinhub.business.mapper.merchantauthoring.MerchantAuthoringMapper;

/** One database transaction around the authoring mapper. */
public interface AuthoringTx {
    <T> T call(Function<MerchantAuthoringMapper, T> work);
}
