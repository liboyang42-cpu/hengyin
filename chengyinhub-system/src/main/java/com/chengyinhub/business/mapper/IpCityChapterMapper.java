package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.IpCityChapter;

import java.util.List;

/**
 * P3 城市章节 Mapper。
 */
public interface IpCityChapterMapper {
    List<IpCityChapter> selectPublishedChapters(Long worldId);
}
