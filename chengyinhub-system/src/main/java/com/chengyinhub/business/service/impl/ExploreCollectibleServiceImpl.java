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
        stamp.setContent(merchant == null || merchant.getName() == null
                ? chapterName : chapterName + " · " + merchant.getName());
        stamp.setCreateTime(new Date());
        try {
            if (memberCollectibleMapper.insertMemberCollectible(stamp) != 1) {
                throw new IllegalStateException("探店收藏凭证写入失败");
            }
        } catch (DuplicateKeyException ignored) {
            // 唯一键是最终幂等闸；并发核销不能发出两枚同章凭证。
        }
    }
}
