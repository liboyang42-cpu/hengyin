package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.vo.CityDiscoveryCatalogItemVO;
import com.chengyinhub.business.domain.vo.PublicCatalogFilterOptionsVO;
import com.chengyinhub.business.mapper.CityDiscoveryCatalogMapper;
import com.chengyinhub.business.service.ICityDiscoveryCatalogService;
import com.chengyinhub.business.service.support.PublicContentCatalogLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * M7 城市目录实现。
 *
 * <p>城市行只来自真实已公开主题；没有作品的城市不会出现，也不从城市字典造页。
 * 筛选项同样只列真实存在的值 —— 空集合就是空集合。
 */
@Service
public class CityDiscoveryCatalogServiceImpl implements ICityDiscoveryCatalogService {

    @Autowired
    private CityDiscoveryCatalogMapper cityMapper;

    @Override
    public List<CityDiscoveryCatalogItemVO> cities() {
        List<CityDiscoveryCatalogItemVO> rows = cityMapper.selectCityCatalog();
        if (rows == null) {
            return new ArrayList<CityDiscoveryCatalogItemVO>();
        }
        for (CityDiscoveryCatalogItemVO row : rows) {
            // cityCode 为空(主题未标注城市)时不造伪编码路由。
            row.setCityPath(row.getCityCode() == null || row.getCityCode().isEmpty()
                    ? null : "/city/" + row.getCityCode());
        }
        return rows;
    }

    @Override
    public PublicCatalogFilterOptionsVO filterOptions() {
        PublicCatalogFilterOptionsVO options = new PublicCatalogFilterOptionsVO();
        options.setCities(cities());
        List<Integer> productTypes = cityMapper.selectProductTypes();
        options.setProductTypes(productTypes == null ? new ArrayList<Integer>() : productTypes);
        options.setDeliveryModes(deliveryModes());
        List<String> approved = cityMapper.selectApprovedLocales();
        List<String> approvedDisplay = new ArrayList<String>();
        if (approved != null) {
            for (String locale : approved) {
                approvedDisplay.add(PublicContentCatalogLocale.display(locale));
            }
        }
        options.setApprovedLocales(approvedDisplay);

        List<String> sources = cityMapper.selectSourceLocales();
        List<String> sourceDisplay = new ArrayList<String>();
        if (sources != null) {
            for (String locale : sources) {
                sourceDisplay.add(PublicContentCatalogLocale.display(locale));
            }
        }
        if (!sourceDisplay.contains(PublicContentCatalogLocale.SOURCE_LOCALE_DISPLAY)) {
            // 原文语言恒可用：所有作品都有中文原文，这不是缺省填充数据，而是真实基线。
            sourceDisplay.add(PublicContentCatalogLocale.SOURCE_LOCALE_DISPLAY);
        }
        Collections.sort(sourceDisplay);
        options.setSourceLocales(sourceDisplay);

        Map<String, Object> eligibility = cityMapper.selectEligibilityAvailability();
        options.setGroupEligibleAvailable(count(eligibility, "groupEligibleCount") > 0);
        options.setSchoolEligibleAvailable(count(eligibility, "schoolEligibleCount") > 0);
        return options;
    }

    private List<String> deliveryModes() {
        List<String> modes = new ArrayList<String>();
        Map<String, Object> selfAndClub = cityMapper.selectSelfAndClubModeCounts();
        if (count(selfAndClub, "selfPlayCount") > 0) modes.add("SELF_PLAY");
        Long guided = cityMapper.countGuidedSessionTopics();
        if (guided != null && guided > 0) modes.add("GUIDED_SESSION");
        if (count(selfAndClub, "clubHostedCount") > 0) modes.add("CLUB_HOSTED");
        return modes;
    }

    private static long count(Map<String, Object> row, String key) {
        if (row == null) return 0L;
        Object value = row.get(key);
        if (value == null) return 0L;
        return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(String.valueOf(value));
    }
}
