package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;

import java.util.List;

/**
 * app 端主题列表的「来源」投影：只填 contentPublisherType / contentPublisherName。
 *
 * <p><b>与 {@link ITopicListProjectionService} 的分工（别混用）</b>：
 * 那个是<b>后台</b>列表投影，第一道闸是 {@code ITopicOperationsScopeAuthorizer.canViewTopic}
 * （全局超管，否则空集），并且会带出销售额、结算、客诉等三十余项运营指标。
 * 把它接到 app 端等于把整本运营账本下发给匿名访客。本服务是它的 app 安全子集：
 * 无授权闸（数据本身就是公开的来源标识），只出两个字段。
 *
 * @author chengyinhub
 * @date 2026-07-26
 */
public interface ITopicContentPublisherProjectionService {

    /**
     * 就地填充列表里每个主题的内容主体类型与公开显示名。
     *
     * <p>⚠️ <b>无生效 CONTENT 槽的主题两个字段都保持 null</b>（存量主题的正常状态）。
     * 绝不回退成「官方」——那会把一批未归属的历史主题冒充成平台自营。
     *
     * @param topics 待填充的主题列表；null / 空表直接原样返回
     * @return 传入的同一个 List 实例（保留 PageHelper 挂在容器上的 total）
     */
    List<CmsTopic> project(List<CmsTopic> topics);
}
