package com.chengyinhub.business.mapper.opsconsole;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/** R10 BE-24 主题数值规则后台编辑：读与 CAS 写 cms_topic.journey_rules_json。 */
public interface JourneyRulesAdminMapper
{
    List<Map<String, Object>> searchTopics(@Param("keyword") String keyword, @Param("topicId") Long topicId);

    /** 以读到的旧值做 CAS（&lt;=&gt; 允许 NULL）；返回 0 = 已被别人改过。 */
    int updateJourneyRules(@Param("id") long id, @Param("expected") String expected, @Param("json") String json);
}
