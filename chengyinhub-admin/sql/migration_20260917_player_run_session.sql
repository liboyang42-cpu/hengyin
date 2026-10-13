-- 第二轮拍板 22(2026-09-17):游戏进度跨设备续玩。
-- 节点完成本来就在服务端(player_node_progress);这里只补「进行中的会话 + 已用时 + 暂停态」。
-- game_session 是俱乐部活动级总局(一场一行),不是玩家个人计时,不能复用。
-- 作用域与本机快照一致:活动场次 = (activity_id, 该活动 topic_id);自玩 = (0, topic_id)。
-- 结束/通关/服务端判终态时客户端调 clear 把行标成 ENDED(留 saved_at 作墓碑):另一台设备重进时
-- 墓碑比它本机快照新就不再恢复。枚举接口只列 PAUSED,另按报名/通行证/活动结束日过滤。无数据回填。
-- 服务端按到达顺序后写覆盖(不比较客户端时钟,避免设备时钟偏差吞掉真实进度);同一页的写请求由客户端串行发出。
-- 回滚(确认无需跨设备续玩数据后):DROP TABLE player_run_session;
CREATE TABLE IF NOT EXISTS player_run_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL DEFAULT 0 COMMENT '0=自玩',
    topic_id BIGINT NOT NULL,
    run_state VARCHAR(16) NOT NULL COMMENT 'PAUSED=可继续(离页/暂停落的都是暂停态) ENDED=已结束墓碑',
    elapsed_seconds INT NOT NULL COMMENT '已用时(秒)',
    saved_at BIGINT NOT NULL COMMENT '客户端落盘/结束时刻(毫秒),客户端恢复时与本机快照比新旧',
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_player_run_scope (member_id, activity_id, topic_id),
    KEY idx_player_run_member_update (member_id, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家进行中的游戏会话(跨设备续玩)';
