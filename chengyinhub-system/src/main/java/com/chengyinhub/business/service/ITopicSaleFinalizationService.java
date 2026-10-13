package com.chengyinhub.business.service;

import java.util.Date;

/** 合同二唯一开售终检入口。 */
public interface ITopicSaleFinalizationService {

    /** 使用注入 Clock 的当前时刻终检并开售；已 SELLING 的幂等重试返回既有实际开售时刻。 */
    Date finalizeForSale(Long topicId);
}
