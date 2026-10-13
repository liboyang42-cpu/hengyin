-- migration_player_object_card_20260922.sql
-- 走 chengyin-db-migrate:先只读预检 `--describe player_object_card` 确认表不存在,再经人工确认后执行。
-- 必须先于引用它的 jar 上线 —— 没有这张表,拍物成卡判过的那一刻铸卡就抛异常
-- (铸卡是静默失败的:玩家照样通关,只是藏品库里永远等不到那张卡)。
--
-- 【这张表回答什么】「这个玩家收藏了哪几张立体藏品卡」。
-- 一行 = 一张卡。它是拍物成卡(契约 §4)唯一的落库点:玩法会话 state_json 只记「这一关过了」,
-- 卡面要活过单局、要能被回看,就必须有自己的表 —— 塞进 state_json 会随重开一局而消失。
--
-- 【为什么 (member_id, origin_type, origin_id) 唯一】同一节点只留一张:玩家重拍、
-- 甚至把这一局推倒重玩,拿到的都是同一行被覆盖,而不是攒出一排同款卡。
-- 这同时是防刷闸:反复提交同一节点不会把藏品库灌满。
-- origin_id 存的是节点 id 的字符串形态 —— 它在自玩(activityId=0)与商家局里都非空且稳定,
-- 用 activity_id 会在自玩时为空、用 session_id 会在重开时换值,两者都会让「同款只留一张」失效。
--
-- 【为什么没有 del_flag】这一版不做删除(契约 §10 明确不做),不给一张没人写的列。
-- 真要做删除时,加列的迁移与读路径的 WHERE 同一个 PR 落,不要提前留坑。
--
-- 【生成字段】这张卡先是一张照片卡(gen_status=NONE、frame_count=0),3D 转台由后台任务补:
-- QUEUED → GENERATING → READY(frames_prefix 下 00.webp 到 23.webp 共 24 帧)或 FAILED。
-- 任何一步失败都停在照片卡,source_url 永远是这张卡的本体。
-- 同一节点重拍覆盖这一行时,生成结果一并清空(见 mapper 的 upsert):照片换了,旧 3D 就不是它的了。
--
-- 【抠图与分类】两列都是「铸卡当场尽力而为」的产物:抠不出、认不出都照常铸卡,所以都可空,
-- 空是常态而不是异常。重拍覆盖时它们跟新照片一起更新 —— 不跟着换,卡面就是新照片配着上一件东西的贴纸。
-- 施工文档:物品拍照生成3D_施工与验收_2026-09-22.md(第四版)§2.1 §2.4 §5。
--
-- 【512 的字宽】沿用 photoCheck 对 imageUrl 的既有上限(512),两侧同一口径才不会
-- 一边放行一边在 MySQL 静默截断。cutout_url 同宽:它是我们自己的 OSS 链,与 source_url 同构。
--
-- 【没有 FK】本仓一贯不建外键,靠应用层归属校验(每条读写都带 member_id)。
--
-- 【注释与字面量禁 ASCII 分号】半角分号会被按分号拆句的执行器劈开,一律用中文逗号。
--
-- 回滚(只在功能整体撤下时):DROP TABLE player_object_card
--
-- 上线后校验:
-- SELECT COUNT(*) FROM information_schema.tables
--  WHERE table_schema=DATABASE() AND table_name='player_object_card'

CREATE TABLE IF NOT EXISTS player_object_card (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  member_id    BIGINT       NOT NULL COMMENT '卡的主人 ums_member.id,只从登录态取,接口不接受客户端传归属',
  source_url   VARCHAR(512) NOT NULL COMMENT '玩家那张照片,本站上传链产出的地址,照片卡的卡面,也是 3D 生成的输入',
  cutout_url   VARCHAR(512) NULL     COMMENT '抠图结果转存到我们 OSS 的透明 PNG,空 = 没抠出来,卡面退回照片档',
  cutout_box   VARCHAR(32)  NULL     COMMENT '物品在原照片里的位置 x,y,w,h 四个比例,与 cutout_url 同生同灭,当场揭晓靠它让物品留在原位',
  title        VARCHAR(64)  NOT NULL COMMENT '卡名,发布侧上限 64 字,这里同宽',
  category     VARCHAR(16)  NULL     COMMENT 'AI 认物的分类,取值见固定清单,空 = 没认出来',
  caption      VARCHAR(128) NULL     COMMENT '卡面一句话,来自节点配置里的 shotNote',
  card_style   VARCHAR(16)  NOT NULL DEFAULT 'foil' COMMENT '卡面样式:foil 镭射 / plain 哑光,未知值一律按 foil',
  origin_type  VARCHAR(16)  NOT NULL COMMENT '来源:PLAY 玩法节点,商家侧 L1 尚未开工故只此一种',
  origin_id    VARCHAR(64)  NOT NULL COMMENT '来源标识,PLAY 时是节点 id 字符串,同一来源重拍覆盖同一行',
  create_time  DATETIME     NOT NULL,
  update_time  DATETIME     NULL COMMENT '重拍覆盖同一行时只动这一列',
  gen_status    VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT '3D 生成状态:NONE 照片卡 / QUEUED / GENERATING / READY / FAILED',
  gen_provider  VARCHAR(16)  NULL     COMMENT '3D 生成服务商,只接一家',
  gen_task_id   VARCHAR(128) NULL     COMMENT '服务商任务号,与 gen_provider 组成唯一键',
  gen_error     VARCHAR(64)  NULL     COMMENT '内部错误码,不给玩家看',
  gen_queued_at DATETIME     NULL     COMMENT '排队时间,额度计数与超时判断都用它',
  model_url     VARCHAR(512) NULL     COMMENT 'GLB 转存地址,留着以后升级多层或 AR 不用重新生成',
  frames_prefix VARCHAR(512) NULL     COMMENT '转台帧目录,帧地址 = 前缀 + 00.webp 到 23.webp',
  frame_count   TINYINT      NOT NULL DEFAULT 0 COMMENT 'READY 时为 24,其余为 0',
  PRIMARY KEY (id),
  -- 同一人同一来源只留一张:覆盖语义全靠这一条键
  UNIQUE KEY uk_member_card_origin (member_id, origin_type, origin_id),
  -- 藏品库按人倒序翻页
  KEY idx_card_member_time (member_id, create_time),
  -- 生成任务按状态取队列、额度按排队时间计数,走同一条索引
  KEY idx_card_gen (gen_status, gen_queued_at),
  -- 同一个服务商任务只能落到一张卡上
  UNIQUE KEY uk_card_gen_task (gen_provider, gen_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家立体藏品卡 拍物成卡的落库点';
