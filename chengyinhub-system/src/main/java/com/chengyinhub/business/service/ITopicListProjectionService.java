package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;

import java.util.List;

/** 主题列表的跨服务只读投影入口。 */
public interface ITopicListProjectionService {

    List<CmsTopic> project(List<CmsTopic> topics);
}
