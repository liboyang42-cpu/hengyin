package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.domain.UmsComment;
import com.chengyinhub.business.mapper.UmsCommentMapper;
import java.util.HashSet;
import java.util.Set;
import com.chengyinhub.business.mapper.UmsCreativeSquareMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 互动入口锁定当前可见内容，锁保持到调用者完成写入或回滚。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ContentInteractionGuard {
    private final UmsCreativeSquareMapper squares;
    private final UmsCommentMapper comments;

    public ContentInteractionGuard(UmsCreativeSquareMapper squares, UmsCommentMapper comments) {
        this.squares = squares;
        this.comments = comments;
    }

    public String lockSquare(Long id) {
        UmsCreativeSquare square = squares.selectUmsCreativeSquareByIdForUpdate(id);
        return square == null || !Integer.valueOf(0).equals(square.getDelFlag())
                || !Integer.valueOf(1).equals(square.getStatus()) ? "内容不存在或已不可见" : null;
    }

/** 评论链由子到父锁定，最后锁帖文；所有入口使用相同顺序。 */
    public String lockTarget(Long ownerType, Long ownerId, Long replyId) {
        if (ownerType == null || ownerType < 1 || ownerType > 4 || ownerId == null || ownerId <= 0
                || (replyId != null && replyId < 0)) return "评论对象参数不合规";
        Set<Long> visited = new HashSet<>();
        Long current = replyId;
        while (current != null && current > 0) {
            if (!visited.add(current)) return "评论回复关系异常";
            UmsComment comment = comments.selectUmsCommentByIdForUpdate(current);
            if (comment == null || !Integer.valueOf(0).equals(comment.getDelFlag())
                    || !Integer.valueOf(1).equals(comment.getStatus())) return "评论不存在或已不可见";
            if (!ownerType.equals(comment.getOwnerType()) || !ownerId.equals(comment.getOwnerId()))
                return "回复评论不属于当前内容";
            current = comment.getReplyId();
        }
        return ownerType == 3 ? lockSquare(ownerId) : null;
    }

    public String lockComment(Long id) {
        if (id == null || id <= 0) return "评论参数不合规";
        UmsComment comment = comments.selectUmsCommentByIdForUpdate(id);
        if (comment == null) return "评论不存在或已不可见";
        return lockTarget(comment.getOwnerType(), comment.getOwnerId(), id);
    }
}
