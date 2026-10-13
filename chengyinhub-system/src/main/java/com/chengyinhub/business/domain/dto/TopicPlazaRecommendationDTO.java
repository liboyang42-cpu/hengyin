package com.chengyinhub.business.domain.dto;

/**
 * 主题广场的发现治理请求。
 *
 * <p>只允许调整推荐位，不能夹带剧情、路线、图片说明或票价等用户内容字段。</p>
 */
public class TopicPlazaRecommendationDTO {

    private Integer isRecommend;

    public Integer getIsRecommend() {
        return isRecommend;
    }

    public void setIsRecommend(Integer isRecommend) {
        this.isRecommend = isRecommend;
    }
}
