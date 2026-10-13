-- ============================================================
-- 建议版发布:路线预设 CMS 建表 + cms_topic 加 publish_mode
-- 配套:docs/建议版发布-专家方案.md §6.1 / 实施计划 Phase 0
-- 执行方式:MySQL 连生产库后 source 本文件;或用 /chengyin-db-migrate skill
-- 注意:执行前备份 cms_topic 表
-- ============================================================

-- 1) 主题风格(运营预置,建议版"选皮肤"用)
CREATE TABLE IF NOT EXISTS preset_theme_style (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '风格ID',
    name                VARCHAR(64)  NOT NULL                COMMENT '风格名称',
    cover_img           VARCHAR(500) NULL                    COMMENT '封面图URL',
    tone_tokens         TEXT         NULL                    COMMENT '一组 --cy- token 色值(JSON)',
    default_title       VARCHAR(128) NULL                    COMMENT '默认主题标题文案',
    default_subtitle    VARCHAR(255) NULL                    COMMENT '默认副标题文案',
    default_desc        TEXT         NULL                    COMMENT '默认简介/规则文案',
    default_category_ids VARCHAR(128) NULL                   COMMENT '默认分类ID(逗号分隔)',
    sort                INT          NOT NULL DEFAULT 0       COMMENT '排序(小在前)',
    status              CHAR(1)      NOT NULL DEFAULT '1'     COMMENT '上下架 1上架 0下架',
    del_flag            INT          NOT NULL DEFAULT 0       COMMENT '删除标志 0存在 2删除',
    create_by           VARCHAR(64)  NULL                    COMMENT '创建者',
    create_time         DATETIME     NULL                    COMMENT '创建时间',
    update_by           VARCHAR(64)  NULL                    COMMENT '更新者',
    update_time         DATETIME     NULL                    COMMENT '更新时间',
    remark              VARCHAR(500) NULL                    COMMENT '备注',
    PRIMARY KEY (id),
    KEY idx_preset_style_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='建议版主题风格预设';

-- 2) 城市路线(运营录入,建议版"选路线"用)
CREATE TABLE IF NOT EXISTS preset_route (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '路线ID',
    city                VARCHAR(64)  NULL                    COMMENT '城市',
    name                VARCHAR(128) NOT NULL                COMMENT '路线名称',
    preview_img         VARCHAR(500) NULL                    COMMENT '路线静态预览图URL',
    route_geometry      TEXT         NULL                    COMMENT '沿路坐标(JSON)',
    total_distance      DECIMAL(10,2) NULL DEFAULT 0         COMMENT '总距离(km)',
    total_time          BIGINT       NULL DEFAULT 0          COMMENT '总时长(分钟)',
    default_category_ids VARCHAR(128) NULL                   COMMENT '默认分类ID(逗号分隔)',
    sort                INT          NOT NULL DEFAULT 0       COMMENT '排序(小在前)',
    status              CHAR(1)      NOT NULL DEFAULT '1'     COMMENT '上下架 1上架 0下架',
    del_flag            INT          NOT NULL DEFAULT 0       COMMENT '删除标志 0存在 2删除',
    create_by           VARCHAR(64)  NULL                    COMMENT '创建者',
    create_time         DATETIME     NULL                    COMMENT '创建时间',
    update_by           VARCHAR(64)  NULL                    COMMENT '更新者',
    update_time         DATETIME     NULL                    COMMENT '更新时间',
    remark              VARCHAR(500) NULL                    COMMENT '备注',
    PRIMARY KEY (id),
    KEY idx_preset_route_status (status),
    KEY idx_preset_route_city (city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='建议版城市路线预设';

-- 3) 路线站点(隶属 preset_route)
CREATE TABLE IF NOT EXISTS preset_route_node (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '站点ID',
    route_id            BIGINT       NOT NULL                COMMENT '所属路线ID',
    name                VARCHAR(128) NOT NULL                COMMENT '站点名称',
    address             VARCHAR(255) NULL                    COMMENT '站点地址',
    longitude           VARCHAR(32)  NULL                    COMMENT '经度',
    latitude            VARCHAR(32)  NULL                    COMMENT '纬度',
    img_url             VARCHAR(500) NULL                    COMMENT '站点配图URL',
    node_time           BIGINT       NULL DEFAULT 0          COMMENT '建议停留时长(分钟)',
    sort                INT          NOT NULL DEFAULT 0       COMMENT '站点顺序(小在前)',
    play_template_id    BIGINT       NULL                    COMMENT '绑定玩法模板ID(cms_template_library,可空)',
    del_flag            INT          NOT NULL DEFAULT 0       COMMENT '删除标志 0存在 2删除',
    create_by           VARCHAR(64)  NULL                    COMMENT '创建者',
    create_time         DATETIME     NULL                    COMMENT '创建时间',
    update_by           VARCHAR(64)  NULL                    COMMENT '更新者',
    update_time         DATETIME     NULL                    COMMENT '更新时间',
    remark              VARCHAR(500) NULL                    COMMENT '备注',
    PRIMARY KEY (id),
    KEY idx_preset_node_route (route_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='建议版路线站点预设';

-- 4) cms_topic 加 publish_mode(区分建议版/专业版来源,供免审分支与数据分析)
ALTER TABLE cms_topic
    ADD COLUMN publish_mode VARCHAR(16) NULL DEFAULT 'pro' COMMENT '发布来源 simple建议版 pro专业版';
