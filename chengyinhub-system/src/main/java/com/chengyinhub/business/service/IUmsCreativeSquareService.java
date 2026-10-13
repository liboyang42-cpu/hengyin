package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.UmsCreativeSquare;

/**
 * 创意广场-用户发布Service接口
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
public interface IUmsCreativeSquareService 
{
    /**
     * 查询创意广场-用户发布
     * 
     * @param id 创意广场-用户发布主键
     * @return 创意广场-用户发布
     */
    public UmsCreativeSquare selectUmsCreativeSquareById(Long id);

    /**
     * 查询创意广场-用户发布列表
     * 
     * @param umsCreativeSquare 创意广场-用户发布
     * @return 创意广场-用户发布集合
     */
    public List<UmsCreativeSquare> selectUmsCreativeSquareList(UmsCreativeSquare umsCreativeSquare);

    /** R10-03 幂等发布:同 (memberId, requestId) 命中既有帖子,不再 insert。 */
    class IdempotentPublishResult {
        public enum Kind {
            NEW,
            EXISTING_VISIBLE,
            EXISTING_REJECTED,
            /** R10-03 收尾:同键命中的帖子已被软删(del_flag=2)——不得当成功回执,也不自动复活。 */
            EXISTING_DELETED,
            PENDING,
            CONFLICT,
            INVALID,
            FAILED
        }

        private final Kind kind;
        private final UmsCreativeSquare square;

        public IdempotentPublishResult(Kind kind, UmsCreativeSquare square) {
            this.kind = kind;
            this.square = square;
        }

        public Kind getKind() { return kind; }

        public UmsCreativeSquare getSquare() { return square; }
    }

    /** 软删标记 2 = 已删除;null/0 视为存在。幂等服务与控制器回读共用同一判据。 */
    static boolean isSoftDeleted(UmsCreativeSquare square) {
        return square != null && square.getDelFlag() != null && square.getDelFlag() == 2;
    }

    /**
     * R10-03:稳定发布意图键幂等插入。
     * 同键同载荷重试 → 返回既有行(可见/被拒/待审);同键不同载荷 → CONFLICT;
     * 并发首写由 (member_id, request_id) 唯一键兜底,catch DuplicateKey 后回读。
     */
    IdempotentPublishResult insertUmsCreativeSquareIdempotent(UmsCreativeSquare square, String requestId, String signature);

    /**
     * 新增创意广场-用户发布
     * 
     * @param umsCreativeSquare 创意广场-用户发布
     * @return 结果
     */
    public int insertUmsCreativeSquare(UmsCreativeSquare umsCreativeSquare);

    /**
     * 修改创意广场-用户发布
     * 
     * @param umsCreativeSquare 创意广场-用户发布
     * @return 结果
     */
    public int updateUmsCreativeSquare(UmsCreativeSquare umsCreativeSquare);

    /**
     * 批量删除创意广场-用户发布
     * 
     * @param ids 需要删除的创意广场-用户发布主键集合
     * @return 结果
     */
    public int deleteUmsCreativeSquareByIds(Long[] ids);

    /**
     * 删除创意广场-用户发布信息
     * 
     * @param id 创意广场-用户发布主键
     * @return 结果
     */
    public int deleteUmsCreativeSquareById(Long id);

    /**
     * [6-09] 主题同步帖(data_type=2)随主题可见性联动。
     *
     * <p>主题发布时自动同步的那条广场帖,在主题被删除、下架、审核驳回或内容安全自动下架后
     * 必须一起不公开;主题恢复公开时按原规则恢复。
     *
     * <ul>
     *   <li>visible=false:把该主题下仍公开的同步帖置为隐藏(status=2);</li>
     *   <li>visible=true:只把**此前被联动隐藏**的同步帖放回(status=1)。
     *       帖子自身被审核驳回(check_status=2)或作者已删(del_flag=2)的一律不复活。</li>
     * </ul>
     *
     * <p>调用点覆盖所有改主题可见性的入口(上下架 CAS、删除、审核驳回/过审、内容安全自动下架、
     * 发布草稿、移交下架、后台删除),读取端另有一道兜底过滤。
     *
     * @return 实际改动的行数
     */
    int syncTopicPostVisibility(Long topicId, boolean visible);
}
