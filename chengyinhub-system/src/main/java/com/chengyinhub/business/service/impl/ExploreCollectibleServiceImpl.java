package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.MemberCollectible;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.mapper.MemberCollectibleMapper;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.IExploreCollectibleService;
import com.chengyinhub.business.service.IMmsMerchantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class ExploreCollectibleServiceImpl implements IExploreCollectibleService {
    @Autowired
    private MemberCollectibleMapper memberCollectibleMapper;
    @Autowired
    private ICmsTopicChapterService topicChapterService;
    @Autowired
    private IMmsMerchantService merchantService;
    @Autowired(required = false)
    private com.chengyinhub.business.service.ICmsTopicNodeService topicNodeService;

    @Override
    public void record(Long memberId, Long topicId, Long chapterId, Long merchantMemberId) {
        if (memberCollectibleMapper.countOne(memberId, MemberCollectible.TYPE_EXPLORE_STAMP, chapterId) > 0) return;
        CmsTopicChapter chapter = topicChapterService.selectCmsTopicChapterById(chapterId);
        MmsMerchant merchant = merchantMemberId == null ? null
                : merchantService.selectMmsMerchantByMemberId(merchantMemberId);
        MemberCollectible stamp = new MemberCollectible();
        stamp.setMemberId(memberId);
        stamp.setCollType(MemberCollectible.TYPE_EXPLORE_STAMP);
        stamp.setTopicId(topicId);
        stamp.setRefId(chapterId);
        String chapterName = chapter == null ? "探店章节" : chapter.getName();
        // P-39:图鉴写玩家看到的店名(本章该商家的节点名),不写商家账号名;找不到节点才回落商家名。
        String shopName = shopNodeName(topicId, chapterId, merchantMemberId);
        if (shopName == null && merchant != null) shopName = merchant.getName();
        stamp.setContent(shopName == null || shopName.trim().isEmpty()
                ? chapterName : chapterName + " · " + shopName.trim());
        stamp.setCreateTime(new Date());
        try {
            if (memberCollectibleMapper.insertMemberCollectible(stamp) != 1) {
                throw new IllegalStateException("探店收藏凭证写入失败");
            }
        } catch (DuplicateKeyException ignored) {
            // 唯一键是最终幂等闸；并发核销不能发出两枚同章凭证。
        }
    }

    private String shopNodeName(Long topicId, Long chapterId, Long merchantMemberId) {
        if (topicNodeService == null || merchantMemberId == null || chapterId == null) return null;
        com.chengyinhub.business.domain.CmsTopicNode probe = new com.chengyinhub.business.domain.CmsTopicNode();
        probe.setTopicId(topicId);
        probe.setChapterId(chapterId);
        probe.setMerchantMemberId(merchantMemberId);
        java.util.List<com.chengyinhub.business.domain.CmsTopicNode> nodes = topicNodeService.selectCmsTopicNodeList(probe);
        if (nodes == null) return null;
        for (com.chengyinhub.business.domain.CmsTopicNode node : nodes) {
            if (node != null && merchantMemberId.equals(node.getMerchantMemberId())
                    && chapterId.equals(node.getChapterId()) && node.getName() != null && !node.getName().trim().isEmpty()) {
                return node.getName();
            }
        }
        return null;
    }
}
