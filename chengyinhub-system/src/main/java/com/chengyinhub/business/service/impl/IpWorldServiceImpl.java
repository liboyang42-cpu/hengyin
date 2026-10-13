package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.IpQuest;
import com.chengyinhub.business.domain.IpWorld;
import com.chengyinhub.business.domain.dto.IpWorldQueryDTO;
import com.chengyinhub.business.domain.vo.IpWorldHomeVO;
import com.chengyinhub.business.mapper.IpCharacterMapper;
import com.chengyinhub.business.mapper.IpCityChapterMapper;
import com.chengyinhub.business.mapper.IpQuestMapper;
import com.chengyinhub.business.mapper.IpWorldMapper;
import com.chengyinhub.business.service.IIpWorldService;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * P3 IP世界服务实现。
 */
@Service
public class IpWorldServiceImpl implements IIpWorldService {

    @Autowired
    private IpWorldMapper ipWorldMapper;

    @Autowired
    private IpCityChapterMapper ipCityChapterMapper;

    @Autowired
    private IpCharacterMapper ipCharacterMapper;

    @Autowired
    private IpQuestMapper ipQuestMapper;

    @Override
    public IpWorldHomeVO getHome(IpWorldQueryDTO query) {
        String cityCode = query == null ? null : query.getCityCode();
        List<IpWorld> worlds = nullToEmpty(ipWorldMapper.selectPublishedWorlds(cityCode));
        IpWorldHomeVO vo = new IpWorldHomeVO();
        vo.setWorlds(worlds);
        if (worlds.isEmpty()) {
            return vo;
        }
        IpWorld selected = worlds.get(0);
        if (query != null && query.getWorldId() != null) {
            for (IpWorld world : worlds) {
                if (query.getWorldId().equals(world.getId())) {
                    selected = world;
                    break;
                }
            }
        }
        vo.setWorld(selected);
        vo.setChapters(nullToEmpty(ipCityChapterMapper.selectPublishedChapters(selected.getId())));
        vo.setCharacters(nullToEmpty(ipCharacterMapper.selectPublishedCharacters(selected.getId())));
        vo.setQuests(nullToEmpty(ipQuestMapper.selectPublishedQuests(selected.getId())));
        return vo;
    }

    @Override
    public List<IpWorld> listPublishedWorlds(String cityCode) {
        return nullToEmpty(ipWorldMapper.selectPublishedWorlds(cityCode));
    }

    @Override
    public List<IpQuest> listPublishedQuests(Long worldId) {
        if (worldId == null) {
            return Collections.emptyList();
        }
        return nullToEmpty(ipQuestMapper.selectPublishedQuests(worldId));
    }

    @Override
    public List<IpWorld> selectAdminWorldList(IpWorld query) {
        return ipWorldMapper.selectAdminWorldList(query);
    }

    @Override
    public int saveWorld(IpWorld world) {
        if (world == null) {
            return 0;
        }
        if (world.getId() == null) {
            world.setCreateTime(DateUtils.getNowDate());
            return ipWorldMapper.insertIpWorld(world);
        }
        world.setUpdateTime(DateUtils.getNowDate());
        return ipWorldMapper.updateIpWorld(world);
    }

    private <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }
}
