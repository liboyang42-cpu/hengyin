package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.vo.NearbyNodeVO;
import com.chengyinhub.business.mapper.ApiMapMapper;
import com.chengyinhub.business.service.IApiMapService;
import com.chengyinhub.common.utils.PinyinUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 地图 Service 实现 —— 周边节点(只读)。
 *
 */
@Service
public class ApiMapServiceImpl implements IApiMapService {

    @Autowired
    private ApiMapMapper apiMapMapper;

    @Override
    public List<NearbyNodeVO> nearbyNodes(double lng, double lat, double radius, int limit,
                                          Integer status, Integer auditStatus) {
        List<NearbyNodeVO> rows = apiMapMapper.selectNearbyNodes(lng, lat, radius, limit, status, auditStatus);
        attachPinyin(rows);
        return rows;
    }

    /**
     * 给每条地址补上全拼与首字母,地图顶部那个输入框据此支持敲字母搜(「xhbj」→「徐汇滨江」)。
     *
     * 在读的时候算而不是落库:地名改了拼音要跟着改,存列就得配回填与触发器,
     * 而这里一次最多 200 条、字典在内存、结果还带 LRU 缓存 —— 地名高度重复,几乎都命中缓存。
     * 前端不做这件事:那要把一整本字典塞进小程序主包,而主包资源棘轮是只准降的。
     */
    private void attachPinyin(List<NearbyNodeVO> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (NearbyNodeVO row : rows) {
            if (row == null) {
                continue;
            }
            String name = row.getAddressName();
            if (name == null || name.trim().isEmpty()) {
                continue;   // 无名商家没什么可拼的,留 null 让前端只按中文匹配
            }
            row.setNamePinyin(PinyinUtils.toPinyin(name));
            row.setNameInitials(PinyinUtils.toInitials(name));
        }
    }
}
