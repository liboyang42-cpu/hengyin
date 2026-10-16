-- 历史评论 LIKE 关系已回填，但初始 like_count 仍为 0；只补可逐项确认来源与归属的历史评论。
-- runner: requires=transaction
UPDATE community_post_comment c
SET like_count=(SELECT COUNT(*) FROM community_post_comment_action a
                WHERE a.comment_id=c.id AND a.action_type='LIKE')
WHERE BINARY c.client_request_id=BINARY CONCAT('legacy-square-comment-',c.id)
  AND EXISTS (
    SELECT 1 FROM ums_comment old_comment
    JOIN community_post p ON p.id=old_comment.owner_id
    JOIN ums_creative_square s ON s.id=p.id AND s.member_id=p.author_id
    WHERE old_comment.id=c.id AND old_comment.owner_type=3
      AND old_comment.member_id=c.author_id AND old_comment.owner_id=c.post_id
      AND BINARY p.client_request_id=BINARY CONCAT('legacy-square-',p.id)
  )
  AND NOT (c.like_count<=>(SELECT COUNT(*) FROM community_post_comment_action a
                          WHERE a.comment_id=c.id AND a.action_type='LIKE'));

CREATE TEMPORARY TABLE assert_community_legacy_comment_likes (
  mismatches BIGINT NOT NULL CHECK (mismatches=0)
);
INSERT INTO assert_community_legacy_comment_likes (mismatches)
SELECT COUNT(*) FROM community_post_comment c
WHERE BINARY c.client_request_id=BINARY CONCAT('legacy-square-comment-',c.id)
  AND EXISTS (
    SELECT 1 FROM ums_comment old_comment
    JOIN community_post p ON p.id=old_comment.owner_id
    JOIN ums_creative_square s ON s.id=p.id AND s.member_id=p.author_id
    WHERE old_comment.id=c.id AND old_comment.owner_type=3
      AND old_comment.member_id=c.author_id AND old_comment.owner_id=c.post_id
      AND BINARY p.client_request_id=BINARY CONCAT('legacy-square-',p.id)
  )
  AND NOT (c.like_count<=>(SELECT COUNT(*) FROM community_post_comment_action a
                          WHERE a.comment_id=c.id AND a.action_type='LIKE'));
DROP TEMPORARY TABLE assert_community_legacy_comment_likes;
