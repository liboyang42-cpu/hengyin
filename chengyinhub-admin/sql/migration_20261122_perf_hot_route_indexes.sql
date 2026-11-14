-- Performance baseline hot-route covering/lookup indexes identified by EXPLAIN audit on cy_e2e_r2.
-- Eliminates full table scans (type=ALL) and unnecessary Using filesort on comment/collaborator/registration/
-- activity/topic/club/product/search/deregistration/channel hot read paths.
-- Rerunnable & idempotent: every ALTER TABLE checks information_schema.statistics before adding KEY.

-- [1] ums_comment.idx_ums_comment_owner_status (owner_type, owner_id, status, del_flag, id)
SET @perf_idx_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ums_comment' AND index_name = 'idx_ums_comment_owner_status') = 0,
  'ALTER TABLE `ums_comment` ADD KEY `idx_ums_comment_owner_status` (owner_type, owner_id, status, del_flag, id)', 'SELECT 1');
PREPARE perf_idx_stmt FROM @perf_idx_ddl;
EXECUTE perf_idx_stmt;
DEALLOCATE PREPARE perf_idx_stmt;

-- [2] ums_comment_like.idx_ums_comment_like_comment_id (comment_id)
SET @perf_idx_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ums_comment_like' AND index_name = 'idx_ums_comment_like_comment_id') = 0,
  'ALTER TABLE `ums_comment_like` ADD KEY `idx_ums_comment_like_comment_id` (comment_id)', 'SELECT 1');
PREPARE perf_idx_stmt FROM @perf_idx_ddl;
EXECUTE perf_idx_stmt;
DEALLOCATE PREPARE perf_idx_stmt;

-- [3] cms_collaborators.idx_cms_collaborators_data (data_type, data_id, member_id)
SET @perf_idx_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_collaborators' AND index_name = 'idx_cms_collaborators_data') = 0,
  'ALTER TABLE `cms_collaborators` ADD KEY `idx_cms_collaborators_data` (data_type, data_id, member_id)', 'SELECT 1');
PREPARE perf_idx_stmt FROM @perf_idx_ddl;
EXECUTE perf_idx_stmt;
DEALLOCATE PREPARE perf_idx_stmt;

-- [4] cms_registration.idx_cms_reg_owner_member (owner_type, owner_id, member_id, registration_status, payment_status, id)
SET @perf_idx_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_registration' AND index_name = 'idx_cms_reg_owner_member') = 0,
  'ALTER TABLE `cms_registration` ADD KEY `idx_cms_reg_owner_member` (owner_type, owner_id, member_id, registration_status, payment_status, id)', 'SELECT 1');
PREPARE perf_idx_stmt FROM @perf_idx_ddl;
EXECUTE perf_idx_stmt;
DEALLOCATE PREPARE perf_idx_stmt;

-- [5] cms_registration.idx_cms_reg_ticket_status (ticket_id, registration_status, payment_status)
SET @perf_idx_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_registration' AND index_name = 'idx_cms_reg_ticket_status') = 0,
  'ALTER TABLE `cms_registration` ADD KEY `idx_cms_reg_ticket_status` (ticket_id, registration_status, payment_status)', 'SELECT 1');
PREPARE perf_idx_stmt FROM @perf_idx_ddl;
EXECUTE perf_idx_stmt;
DEALLOCATE PREPARE perf_idx_stmt;

-- [6] club.idx_club_del_status_ctime (del_flag, status, create_time)
SET @perf_idx_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'club' AND index_name = 'idx_club_del_status_ctime') = 0,
  'ALTER TABLE `club` ADD KEY `idx_club_del_status_ctime` (del_flag, status, create_time)', 'SELECT 1');
PREPARE perf_idx_stmt FROM @perf_idx_ddl;
EXECUTE perf_idx_stmt;
DEALLOCATE PREPARE perf_idx_stmt;

-- [7] club_post.idx_club_post_del_id (del_flag, id)
SET @perf_idx_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'club_post' AND index_name = 'idx_club_post_del_id') = 0,
  'ALTER TABLE `club_post` ADD KEY `idx_club_post_del_id` (del_flag, id)', 'SELECT 1');
PREPARE perf_idx_stmt FROM @perf_idx_ddl;
EXECUTE perf_idx_stmt;
DEALLOCATE PREPARE perf_idx_stmt;

-- [8] cms_topic_chapter.idx_cms_topic_chapter_topic_sort (topic_id, sort_id)
SET @perf_idx_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_topic_chapter' AND index_name = 'idx_cms_topic_chapter_topic_sort') = 0,
  'ALTER TABLE `cms_topic_chapter` ADD KEY `idx_cms_topic_chapter_topic_sort` (topic_id, sort_id)', 'SELECT 1');
PREPARE perf_idx_stmt FROM @perf_idx_ddl;
EXECUTE perf_idx_stmt;
DEALLOCATE PREPARE perf_idx_stmt;

-- [9] cms_topic_node.idx_cms_topic_node_topic_status_sort (topic_id, status, sort_id)
SET @perf_idx_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_topic_node' AND index_name = 'idx_cms_topic_node_topic_status_sort') = 0,
  'ALTER TABLE `cms_topic_node` ADD KEY `idx_cms_topic_node_topic_status_sort` (topic_id, status, sort_id)', 'SELECT 1');
PREPARE perf_idx_stmt FROM @perf_idx_ddl;
EXECUTE perf_idx_stmt;
DEALLOCATE PREPARE perf_idx_stmt;

-- [10] cms_topic_node.idx_cms_topic_node_chapter_status_sort (chapter_id, status, sort_id)
SET @perf_idx_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_topic_node' AND index_name = 'idx_cms_topic_node_chapter_status_sort') = 0,
  'ALTER TABLE `cms_topic_node` ADD KEY `idx_cms_topic_node_chapter_status_sort` (chapter_id, status, sort_id)', 'SELECT 1');
PREPARE perf_idx_stmt FROM @perf_idx_ddl;
EXECUTE perf_idx_stmt;
DEALLOCATE PREPARE perf_idx_stmt;

-- [11] cms_activity.idx_cms_activity_pub_del_status_end (del_flag, status, publish_status, cancel_time, end_date, id)
SET @perf_idx_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_activity' AND index_name = 'idx_cms_activity_pub_del_status_end') = 0,
  'ALTER TABLE `cms_activity` ADD KEY `idx_cms_activity_pub_del_status_end` (del_flag, status, publish_status, cancel_time, end_date, id)', 'SELECT 1');
PREPARE perf_idx_stmt FROM @perf_idx_ddl;
EXECUTE perf_idx_stmt;
DEALLOCATE PREPARE perf_idx_stmt;

-- [12] cms_topic.idx_cms_topic_pub_del_ctime (status, user_status, del_flag, create_time, id)
SET @perf_idx_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND index_name = 'idx_cms_topic_pub_del_ctime') = 0,
  'ALTER TABLE `cms_topic` ADD KEY `idx_cms_topic_pub_del_ctime` (status, user_status, del_flag, create_time, id)', 'SELECT 1');
PREPARE perf_idx_stmt FROM @perf_idx_ddl;
EXECUTE perf_idx_stmt;
DEALLOCATE PREPARE perf_idx_stmt;

-- [13] content_search_document.idx_content_search_vis_type_time (visibility_state, document_type, source_time, id)
SET @perf_idx_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'content_search_document' AND index_name = 'idx_content_search_vis_type_time') = 0,
  'ALTER TABLE `content_search_document` ADD KEY `idx_content_search_vis_type_time` (visibility_state, document_type, source_time, id)', 'SELECT 1');
PREPARE perf_idx_stmt FROM @perf_idx_ddl;
EXECUTE perf_idx_stmt;
DEALLOCATE PREPARE perf_idx_stmt;

-- [14] pms_product.idx_pms_product_pub_del_sort (publish_status, del_flag, sort_id, id)
SET @perf_idx_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'pms_product' AND index_name = 'idx_pms_product_pub_del_sort') = 0,
  'ALTER TABLE `pms_product` ADD KEY `idx_pms_product_pub_del_sort` (publish_status, del_flag, sort_id, id)', 'SELECT 1');
PREPARE perf_idx_stmt FROM @perf_idx_ddl;
EXECUTE perf_idx_stmt;
DEALLOCATE PREPARE perf_idx_stmt;

-- [15] account_deregistration.idx_deregister_member_apply (member_id, apply_time, id)
SET @perf_idx_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'account_deregistration' AND index_name = 'idx_deregister_member_apply') = 0,
  'ALTER TABLE `account_deregistration` ADD KEY `idx_deregister_member_apply` (member_id, apply_time, id)', 'SELECT 1');
PREPARE perf_idx_stmt FROM @perf_idx_ddl;
EXECUTE perf_idx_stmt;
DEALLOCATE PREPARE perf_idx_stmt;

-- [16] im_channel.idx_channel_tree_parent_pos (guild_kind, guild_id, del_flag, status, parent_id, position, id)
SET @perf_idx_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'im_channel' AND index_name = 'idx_channel_tree_parent_pos') = 0,
  'ALTER TABLE `im_channel` ADD KEY `idx_channel_tree_parent_pos` (guild_kind, guild_id, del_flag, status, parent_id, position, id)', 'SELECT 1');
PREPARE perf_idx_stmt FROM @perf_idx_ddl;
EXECUTE perf_idx_stmt;
DEALLOCATE PREPARE perf_idx_stmt;
