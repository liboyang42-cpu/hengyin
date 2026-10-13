package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.UmsCreativeSquareMapper;
import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.service.IUmsCreativeSquareService;

/**
 * 创意广场-用户发布Service业务层处理
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
@Service
public class UmsCreativeSquareServiceImpl implements IUmsCreativeSquareService 
{
    @Autowired
    private UmsCreativeSquareMapper umsCreativeSquareMapper;

    /**
     * 查询创意广场-用户发布
     * 
     * @param id 创意广场-用户发布主键
     * @return 创意广场-用户发布
     */
    @Override
    public UmsCreativeSquare selectUmsCreativeSquareById(Long id)
    {
        return umsCreativeSquareMapper.selectUmsCreativeSquareById(id);
    }

    /**
     * 查询创意广场-用户发布列表
     * 
     * @param umsCreativeSquare 创意广场-用户发布
     * @return 创意广场-用户发布
     */
    @Override
    public List<UmsCreativeSquare> selectUmsCreativeSquareList(UmsCreativeSquare umsCreativeSquare)
    {
        return umsCreativeSquareMapper.selectUmsCreativeSquareList(umsCreativeSquare);
    }

    /**
     * 新增创意广场-用户发布
     * 
     * @param umsCreativeSquare 创意广场-用户发布
     * @return 结果
     */
    @Override
    public int insertUmsCreativeSquare(UmsCreativeSquare umsCreativeSquare)
    {
        umsCreativeSquare.setCreateTime(DateUtils.getNowDate());
        return umsCreativeSquareMapper.insertUmsCreativeSquare(umsCreativeSquare);
    }

    /**
     * R10-03:稳定发布意图键幂等插入。先按键回读,未命中才插;并发由唯一键兜底并回读。
     * 不删不改历史帖子;同键不同载荷一律冲突,不允许静默复用意图。
     */
    @Override
    public IdempotentPublishResult insertUmsCreativeSquareIdempotent(UmsCreativeSquare square,
                                                                     String requestId,
                                                                     String signature)
    {
        if (square == null || square.getMemberId() == null || square.getMemberId() <= 0
                || requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}")) {
            return new IdempotentPublishResult(IdempotentPublishResult.Kind.INVALID, null);
        }
        UmsCreativeSquare existing = umsCreativeSquareMapper
                .selectUmsCreativeSquareByMemberAndRequestId(square.getMemberId(), requestId);
        if (existing == null) {
            square.setRequestId(requestId);
            square.setRequestSignature(signature);
            try {
                if (insertUmsCreativeSquare(square) != 1) {
                    return new IdempotentPublishResult(IdempotentPublishResult.Kind.FAILED, null);
                }
                return new IdempotentPublishResult(IdempotentPublishResult.Kind.NEW, square);
            } catch (org.springframework.dao.DuplicateKeyException raced) {
                existing = umsCreativeSquareMapper
                        .selectUmsCreativeSquareByMemberAndRequestId(square.getMemberId(), requestId);
            }
        }
        return classifyIdempotent(existing, signature);
    }

    private IdempotentPublishResult classifyIdempotent(UmsCreativeSquare existing, String signature)
    {
        if (existing == null) {
            // 极端竞态下唯一键刚被释放/不可见:按待确认处理,由调用方幂等重试
            return new IdempotentPublishResult(IdempotentPublishResult.Kind.PENDING, null);
        }
        // R10-03 收尾:软删(del_flag=2)是独立终态。必须在 status/签名判定之前收口:
        // 已被删除的帖子既不能当「可见既有帖」回成功(假回执),也不能按新意图自动复活。
        if (IUmsCreativeSquareService.isSoftDeleted(existing)) {
            return new IdempotentPublishResult(IdempotentPublishResult.Kind.EXISTING_DELETED, existing);
        }
        if (signature != null && existing.getRequestSignature() != null
                && !signature.equals(existing.getRequestSignature())) {
            return new IdempotentPublishResult(IdempotentPublishResult.Kind.CONFLICT, existing);
        }
        Integer status = existing.getStatus();
        if (status != null && status == 2) {
            return new IdempotentPublishResult(IdempotentPublishResult.Kind.EXISTING_REJECTED, existing);
        }
        if (status != null && status == 1) {
            return new IdempotentPublishResult(IdempotentPublishResult.Kind.EXISTING_VISIBLE, existing);
        }
        return new IdempotentPublishResult(IdempotentPublishResult.Kind.PENDING, existing);
    }

    /**
     * 修改创意广场-用户发布
     * 
     * @param umsCreativeSquare 创意广场-用户发布
     * @return 结果
     */
    @Override
    public int updateUmsCreativeSquare(UmsCreativeSquare umsCreativeSquare)
    {
        umsCreativeSquare.setUpdateTime(DateUtils.getNowDate());
        return umsCreativeSquareMapper.updateUmsCreativeSquare(umsCreativeSquare);
    }

    /**
     * 批量删除创意广场-用户发布
     * 
     * @param ids 需要删除的创意广场-用户发布主键
     * @return 结果
     */
    @Override
    public int deleteUmsCreativeSquareByIds(Long[] ids)
    {
        return umsCreativeSquareMapper.deleteUmsCreativeSquareByIds(ids);
    }

    /**
     * 删除创意广场-用户发布信息
     * 
     * @param id 创意广场-用户发布主键
     * @return 结果
     */
    @Override
    public int deleteUmsCreativeSquareById(Long id)
    {
        return umsCreativeSquareMapper.deleteUmsCreativeSquareById(id);
    }

    @Override
    public int syncTopicPostVisibility(Long topicId, boolean visible)
    {
        if (topicId == null || topicId <= 0) {
            return 0;
        }
        return visible
                ? umsCreativeSquareMapper.restoreTopicSyncPosts(topicId)
                : umsCreativeSquareMapper.hideTopicSyncPosts(topicId);
    }
}
