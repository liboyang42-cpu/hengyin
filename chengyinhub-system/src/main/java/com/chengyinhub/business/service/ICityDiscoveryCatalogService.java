package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.CityDiscoveryCatalogItemVO;
import com.chengyinhub.business.domain.vo.PublicCatalogFilterOptionsVO;

import java.util.List;

/**
 * M7 城市目录与筛选字典(只读)。所有值都从真实已公开数据聚合，不用城市/语言字典补齐。
 */
public interface ICityDiscoveryCatalogService {

    /** 调用方必须先 {@code startPage()}；本方法第一条 SQL 就是分页查询。 */
    List<CityDiscoveryCatalogItemVO> cities();

    PublicCatalogFilterOptionsVO filterOptions();
}
