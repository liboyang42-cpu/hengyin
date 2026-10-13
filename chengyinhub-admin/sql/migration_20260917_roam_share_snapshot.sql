-- 第二轮拍板 17(2026-09-17):朋友发来的足迹卡点开直接看那条足迹。
-- 轨迹只存在玩家本机,服务端 roam_session 没有轨迹;分享时上传一份「分享快照」供接收方只读。
-- 快照内容 = 分享卡上已过位置隐私闸(首尾裁剪)的轨迹 + 成绩,不含原始轨迹/起点;坐标整体平移到整数度锚点
-- (只保留路线形状,不暴露真实位置),时间只到小时。
-- share_token 为随机 32 位十六进制,接收页只凭它读取,不暴露自增 id,防枚举。
-- 失效 = 删行(分享者清空本机足迹时客户端调 revoke,删本人全部);单人最多 50 条,超出删最旧。无数据回填。
-- 回滚(确认无需保留分享链接后):DROP TABLE roam_share_snapshot;
CREATE TABLE IF NOT EXISTS roam_share_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    share_token CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '分享路径携带的随机令牌',
    member_id BIGINT NOT NULL COMMENT '分享者',
    client_ts BIGINT NOT NULL COMMENT '本机足迹记录 ts;同一条足迹重复分享复用同一令牌',
    snapshot_json MEDIUMTEXT NOT NULL COMMENT '服务端归一后的快照(裁剪后轨迹+成绩)',
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_roam_share_token (share_token),
    UNIQUE KEY uk_roam_share_member_ts (member_id, client_ts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='漫游足迹分享快照(只读落地页)';
