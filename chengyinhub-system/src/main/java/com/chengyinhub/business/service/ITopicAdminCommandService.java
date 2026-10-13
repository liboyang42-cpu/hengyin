package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.CmsTopicEditDTO;

/** 后台主题编辑与广场治理的受控命令入口。 */
public interface ITopicAdminCommandService {

    /**
     * 更新当前账号受托范围内的主题字段。
     *
     * <p>官方主题可更新普通编辑白名单；俱乐部/会员主题只允许调整逐期漫游导流开关，
     * 历史待归属主题一律拒绝。</p>
     *
     * @throws IllegalArgumentException 当主题不存在、无操作范围、字段越权/为空或生命周期不允许时
     */
    int editTopicWithinScope(CmsTopicEditDTO request);

    /**
     * 仅调整俱乐部或会员主题在广场中的推荐状态。
     *
     * <p>这不是普通内容编辑，不接受任何用户剧情、路线、图片说明或票价字段。</p>
     *
     * @throws IllegalArgumentException 当主题不存在、无操作范围、不是主题广场内容主体或推荐值非法时
     */
    int updatePlazaRecommendation(Long topicId, Integer isRecommend);

    /**
     * 仅软删除当前账号受托范围内的官方主题；广场主题和历史待归属主题必须走各自治理流程。
     */
    int deleteOfficialTopics(Long[] topicIds);
}
