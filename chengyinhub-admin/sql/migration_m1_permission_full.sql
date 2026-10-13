-- ============================================================
-- M1 权限地基：user_permission 扩展为「三端能力位全集」(专家报告 §3)
-- 单一事实源：/api/role/info.permission 下发这张表
-- 执行前：备份 user_permission；确认本脚本未执行过(列重复会报错)
-- 依赖：先跑过 migration_rbac_role.sql(已建表+player/club/merchant三行)
-- ============================================================

-- 1. 新增能力位列(在旧列 max_themes/max_events/max_coupons/can_create_* 之上扩展)
ALTER TABLE user_permission
    ADD COLUMN max_nodes_per_theme INT          NULL        COMMENT '单主题节点上限,NULL=不限' AFTER max_themes,
    ADD COLUMN can_redeem_coupon   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '到店核销权(仅商家)' AFTER can_publish_coupon,
    ADD COLUMN node_types          VARCHAR(128) NULL        COMMENT '可用节点类型,逗号分隔 checkin,scan,quiz,...' AFTER can_redeem_coupon,
    ADD COLUMN can_branch          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '分支/条件逻辑' AFTER node_types,
    ADD COLUMN can_game_mechanic   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '博弈机制 竞速/对抗/协作' AFTER can_branch,
    ADD COLUMN can_open_as_node    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '开放为城市节点(仅商家)' AFTER can_game_mechanic,
    ADD COLUMN can_design_medal    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '勋章设计权(仅俱乐部)' AFTER can_open_as_node,
    ADD COLUMN can_create_group    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '建群权' AFTER can_design_medal,
    ADD COLUMN data_scope          VARCHAR(32)  NOT NULL DEFAULT 'self' COMMENT '数据范围 self/store/theme+city' AFTER can_create_group,
    ADD COLUMN withdrawable        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '可提现(否=积分制)' AFTER data_scope;

-- 2. 按报告 §3 重置三行种子(含两处方向修正:玩家禁券、商家可发3主题)
-- 玩家 PLAYER：轻量行为生成者
UPDATE user_permission SET
    max_themes = 1, max_events = 0, max_nodes_per_theme = 2, max_coupons = 0,
    can_create_theme = 1, can_create_event = 0,
    can_publish_coupon = 0, can_redeem_coupon = 0,
    node_types = 'checkin',
    can_branch = 0, can_game_mechanic = 0, can_open_as_node = 0,
    can_design_medal = 0, can_create_group = 0,
    data_scope = 'self', withdrawable = 0
WHERE role = 'player' AND membership_level IS NULL;

-- 商家 MERCHANT：城市节点/基础设施(可发3主题、发3券、核销、开放为节点)
UPDATE user_permission SET
    max_themes = 3, max_nodes_per_theme = NULL, max_coupons = 3,
    can_create_theme = 1, can_create_event = 1,
    can_publish_coupon = 1, can_redeem_coupon = 1,
    node_types = 'checkin,scan',
    can_branch = 0, can_game_mechanic = 0, can_open_as_node = 1,
    can_design_medal = 0, can_create_group = 1,
    data_scope = 'store', withdrawable = 1
WHERE role = 'merchant' AND membership_level IS NULL;

-- 俱乐部 CLUB：城市创作者(全节点类型、分支、博弈、勋章设计)
UPDATE user_permission SET
    max_themes = 5, max_nodes_per_theme = NULL, max_coupons = 3,
    can_create_theme = 1, can_create_event = 1,
    can_publish_coupon = 1, can_redeem_coupon = 0,
    node_types = 'checkin,scan,quiz,puzzle,collect,occupy,minigame',
    can_branch = 1, can_game_mechanic = 1, can_open_as_node = 0,
    can_design_medal = 1, can_create_group = 1,
    data_scope = 'theme+city', withdrawable = 1
WHERE role = 'club' AND membership_level IS NULL;
