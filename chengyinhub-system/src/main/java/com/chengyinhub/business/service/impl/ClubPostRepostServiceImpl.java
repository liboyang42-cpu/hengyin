package com.chengyinhub.business.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ClubPostRepost;
import com.chengyinhub.business.mapper.ClubPostMapper;
import com.chengyinhub.business.mapper.ClubPostRepostMapper;
import com.chengyinhub.business.service.IClubPostRepostService;

/**
 * 俱乐部帖文转发 Service 业务层处理(dw29 L8)
 */
@Service
public class ClubPostRepostServiceImpl implements IClubPostRepostService
{
    /** 动态流单次取转发条目上限,与帖文页上限同口径 */
    private static final int FEED_REPOST_MAX = 50;

    @Autowired
    private ClubPostRepostMapper clubPostRepostMapper;

    @Autowired
    private ClubPostMapper clubPostMapper;

    @Override
    public boolean isReposted(Long postId, Long memberId)
    {
        ClubPostRepost row = clubPostRepostMapper.selectByPostAndMember(postId, memberId);
        return row != null && Integer.valueOf(0).equals(row.getDelFlag());
    }

    @Override
    public boolean setReposted(Long postId, Long memberId, boolean reposted)
    {
        ClubPostRepost row = clubPostRepostMapper.selectByPostAndMember(postId, memberId);
        boolean current = row != null && Integer.valueOf(0).equals(row.getDelFlag());
        if (current == reposted) return false;
        if (!reposted)
        {
            // 只有真撤掉了一行才减数:并发下另一请求先撤了,这里就不能再减一次。
            if (clubPostRepostMapper.cancelByPostAndMember(postId, memberId) > 0)
            {
                clubPostMapper.incrementRepostCount(postId, -1);
                return true;
            }
            return false;
        }
        Date now = DateUtils.getNowDate();
        int changed;
        if (row == null)
        {
            ClubPostRepost repost = new ClubPostRepost();
            repost.setPostId(postId);
            repost.setMemberId(memberId);
            repost.setCreateTime(now);
            changed = clubPostRepostMapper.insertClubPostRepost(repost);
        }
        else
        {
            changed = clubPostRepostMapper.reactivateById(row.getId(), now);
        }
        if (changed > 0)
        {
            clubPostMapper.incrementRepostCount(postId, 1);
            return true;
        }
        return false;
    }

    @Override
    public Set<Long> selectRepostedPostIds(Long memberId, List<Long> postIds)
    {
        Set<Long> out = new HashSet<>();
        if (memberId == null || postIds == null || postIds.isEmpty()) return out;
        List<Long> rows = clubPostRepostMapper.selectRepostedPostIds(memberId, postIds);
        if (rows != null) out.addAll(rows);
        return out;
    }

    @Override
    public List<ClubPostRepost> selectFeedReposts(List<Long> clubIds, Date since, Date until, int limit)
    {
        // 空集合不进 SQL:foreach 会生成 `in ()`。
        if (clubIds == null || clubIds.isEmpty()) return new ArrayList<>();
        int bounded = limit <= 0 ? 20 : Math.min(limit, FEED_REPOST_MAX);
        return clubPostRepostMapper.selectFeedReposts(clubIds, since, until, bounded);
    }

    @Override
    public List<ClubPost> selectOriginalPosts(List<Long> postIds)
    {
        if (postIds == null || postIds.isEmpty()) return new ArrayList<>();
        return clubPostMapper.selectFeedPostsByIds(postIds);
    }
}
