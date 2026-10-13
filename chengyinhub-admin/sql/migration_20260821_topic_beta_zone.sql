-- 主题 Beta 试玩区(学 Questo):pro 主题过审上架后先进 Beta 期,对玩家可见可玩但带 Beta 标识,
-- 作者自决转正式(POST /api/topic/beta/graduate 置 0)。simple 免审直发不进 Beta。
-- 执行前必须走 chengyin-db-migrate;本文件只生成,不直接操作生产库。

ALTER TABLE cms_topic
    ADD COLUMN beta_flag tinyint NOT NULL DEFAULT 0
        COMMENT '1=Beta试玩期(pro主题过审先进Beta,作者转正归0)' AFTER is_template;
