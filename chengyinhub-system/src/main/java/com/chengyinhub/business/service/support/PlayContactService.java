package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文档二 L8 游玩菜单「联系主办 / 联系商家」(原型 B T-37;B6 / B7 / B9)。
 *
 * <p>号码只用内容归属方已有的电话:俱乐部主题和赛事 = 主办俱乐部 {@code Club.phone};
 * 自由探索 = 当前站商家 {@code MmsMerchant.phone}。不提供值班电话。没有可用号码返回 null(整行不出)。
 */
@Service
public class PlayContactService {

    public static final String KIND_CLUB = "club";
    public static final String KIND_MERCHANT = "merchant";

    private final CmsTopicMapper cmsTopicMapper;
    private final CmsTopicNodeMapper cmsTopicNodeMapper;
    private final CmsActivityMapper cmsActivityMapper;
    private final ClubMapper clubMapper;
    private final MmsMerchantMapper mmsMerchantMapper;

    public PlayContactService(CmsTopicMapper cmsTopicMapper, CmsTopicNodeMapper cmsTopicNodeMapper,
                              CmsActivityMapper cmsActivityMapper, ClubMapper clubMapper,
                              MmsMerchantMapper mmsMerchantMapper) {
        this.cmsTopicMapper = cmsTopicMapper;
        this.cmsTopicNodeMapper = cmsTopicNodeMapper;
        this.cmsActivityMapper = cmsActivityMapper;
        this.clubMapper = clubMapper;
        this.mmsMerchantMapper = mmsMerchantMapper;
    }

    /** 赛事:主办俱乐部。 */
    public Map<String, Object> forActivity(Long activityId) {
        if (activityId == null || activityId <= 0L) return null;
        CmsActivity activity = cmsActivityMapper.selectCmsActivityById(activityId);
        if (activity == null || Integer.valueOf(2).equals(activity.getDelFlag())) return null;
        return club(activity.getClubId());
    }

    /** 主题局:有俱乐部 → 主办俱乐部;否则当前站商家(nodeId 必须属于该主题)。 */
    public Map<String, Object> forTopic(Long topicId, Long nodeId) {
        if (topicId == null || topicId <= 0L) return null;
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(topicId);
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) return null;
        if (topic.getClubId() != null && topic.getClubId() > 0L) return club(topic.getClubId());
        if (nodeId == null || nodeId <= 0L) return null;
        CmsTopicNode node = cmsTopicNodeMapper.selectCmsTopicNodeById(nodeId);
        if (node == null || !topicId.equals(node.getTopicId()) || node.getMerchantMemberId() == null) return null;
        MmsMerchant merchant = mmsMerchantMapper.selectMmsMerchantByMemberId(node.getMerchantMemberId());
        if (merchant == null || !Long.valueOf(1L).equals(merchant.getStatus())) return null;
        return contact(KIND_MERCHANT, merchant.getName(), merchant.getPhone());
    }

    private Map<String, Object> club(Long clubId) {
        if (clubId == null || clubId <= 0L) return null;
        Club club = clubMapper.selectClubById(clubId);
        if (club == null || !Long.valueOf(1L).equals(club.getStatus()) || Integer.valueOf(2).equals(club.getDelFlag())) {
            return null;
        }
        return contact(KIND_CLUB, club.getName(), club.getPhone());
    }

    private static Map<String, Object> contact(String kind, String name, String phone) {
        String number = phone == null ? "" : phone.trim();
        String title = name == null ? "" : name.trim();
        if (number.isEmpty() || title.isEmpty()) return null;
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("kind", kind);
        view.put("name", title);
        view.put("phone", number);
        return view;
    }
}
