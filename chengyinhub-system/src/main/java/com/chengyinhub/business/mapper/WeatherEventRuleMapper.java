package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.WeatherEventRule;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code weather_event_rule} Mapper.
 */
public interface WeatherEventRuleMapper {

    List<WeatherEventRule> selectActiveByTopicId(@Param("topicId") Long topicId);

    List<WeatherEventRule> selectAllByTopicId(@Param("topicId") Long topicId);

    int insertRule(WeatherEventRule rule);

    int softDeleteByTopicId(@Param("topicId") Long topicId);
}
