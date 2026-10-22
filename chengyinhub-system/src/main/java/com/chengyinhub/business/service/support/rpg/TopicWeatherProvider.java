package com.chengyinhub.business.service.support.rpg;

/**
 * F6 T06 天气源接口。
 *
 * <p>只按<strong>主题配置的城市</strong>查询 —— 入参里没有、也不许加玩家坐标 / GPS;
 * 主题城市是作者公开写在主题上的地址,不是玩家的位置隐私。
 *
 * <p>实现约定:返回 {@link TopicEnvironmentService#WEATHER_VALUES} 之一,查不到返回 null;
 * 可以抛异常 —— 调用方一律 fail-open(按 unknown 处理,玩法照常)。实现不得引入付费源密钥到代码里,
 * 需要外部源时另写实现类、密钥走配置中心,并替换默认 {@link FixedTopicWeatherProvider}。
 */
public interface TopicWeatherProvider {

    /**
     * @param topicCity 主题自身的城市 / 地址文本(cms_topic.address_name),可能为空
     * @return 天气代码(clear / cloudy / rain / snow / fog / wind / storm),未知返回 null
     */
    String currentWeather(String topicCity);
}
