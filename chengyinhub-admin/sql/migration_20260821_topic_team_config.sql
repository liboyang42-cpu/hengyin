ALTER TABLE cms_topic
    ADD COLUMN team_mode TINYINT NOT NULL DEFAULT 0 COMMENT '组队入口:0关闭 1报名时组队 2报名后邀请',
    ADD COLUMN team_max_members INT NOT NULL DEFAULT 4 COMMENT '玩家可选队伍人数上限';
