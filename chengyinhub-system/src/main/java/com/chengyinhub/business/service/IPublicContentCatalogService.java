package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.PublicCatalogQuery;
import com.chengyinhub.business.domain.vo.PublicContentCatalogDetailVO;
import com.chengyinhub.business.domain.vo.PublicContentCatalogItemVO;
import com.chengyinhub.business.domain.vo.PublicContentCatalogMapPointVO;

import java.util.List;

/**
 * M7 公开内容目录读面(只读，无需登录)。
 *
 * <p>三种投影分开，避免首页为了卡片拉全量正文：
 * <ul>
 *   <li>{@link #list} —— 完整卡(含摘要/价格/落地配置)，可 PageHelper 分页；</li>
 *   <li>{@link #mapPoints} —— 地图轻投影(只到首个公开站点坐标)；</li>
 *   <li>{@link #byIds} —— ID 批量补取(按调用方给定顺序返回)。</li>
 * </ul>
 *
 * <p>所有方法只返回已公开内容；下架、内容安全打回、软删、模板本体、会员专属主题
 * 由 SQL 公开闸排除，不靠调用方过滤。
 */
public interface IPublicContentCatalogService {

    /** 调用方必须先 {@code startPage()}；本方法第一条 SQL 就是分页查询。 */
    List<PublicContentCatalogItemVO> list(PublicCatalogQuery query);

    PublicContentCatalogDetailVO detail(Long topicId, String locale);

    List<PublicContentCatalogMapPointVO> mapPoints(PublicCatalogQuery query);

    /** 只做参数校验(不触库、不分页)，失败抛 400 ServiceException；校验通过后调用方才 startPage()。 */
    void validate(PublicCatalogQuery query);

    List<PublicContentCatalogItemVO> byIds(List<Long> topicIds, String locale);
}
