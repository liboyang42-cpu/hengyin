-- ============================================================================
-- 节点名次奖牌(帖文卡 MedalPill 的数据源)。2026-09-02。
--
-- 【为什么要这张表】
-- 规则 = 名次:某个 node 全平台【头三个完成的人】分别拿金/银/铜,第四位起无牌。
-- 名次是「事件」,不是「投影」—— player_node_progress.checkin_time 是 DATETIME
-- (秒精度,见 migration_play_progress_points.sql),同一秒完成的两行在任何 ORDER BY
-- 下都等价,事后从数据上答不了「谁是第一」。所以名次必须在【发生的那一刻】物化,
-- 并由 PRIMARY KEY (node_id, medal_rank) 让 InnoDB 当唯一裁判:
--   两个事务同时插 (X, 1) -> 后到者在重复键上阻塞 -> 前者提交 -> 后者拿 1062 -> 退到 rank=2;
--   前者回滚 -> 后者插入成功夺金。两种分支都只有一个金牌。
-- 不需要 SELECT FOR UPDATE,不需要分布式锁,应用层不参与裁决。
--
-- 【为什么不是往 player_node_progress 加列】
-- 加列要对全平台最热写表做全表 UPDATE(算错只能再写一次全表 UPDATE 复原),
-- 且夺牌者持有的重复键 gap 锁正好落在普通打卡 INSERT(medal_rank=NULL)要写入的那段 ——
-- 夺牌拥堵会反噬普通打卡。独立表:算错就清表重跑,回滚就是 DROP TABLE。
-- ⚠️ 这句只对【上线后的实时抢占】成立(它只写 node_medal_claim,一个字都不碰源表)。
--    【回填那一刻是唯一的例外】,见下面【回填的锁面】——
--    别把上面那句读成「本文件全程不碰源表」,原先的文件头就是这么写错的。
--
-- 【回填的锁面 —— 2026-09-02 修正,原论证是反的】
-- 回填是 INSERT ... SELECT。服务器默认隔离级别是 REPEATABLE READ,该级别下 MySQL 对
-- SELECT 端读到的每一行加【共享 next-key 锁】,一直持到语句所在事务结束。
-- 而 WHERE p.status = 1 AND p.del_flag = 0 【没有任何可用索引】(现有索引全部以
-- member_id / topic_id / id 打头)⇒ 全表扫 ⇒ 整张 player_node_progress 连同间隙被锁住,
-- 这期间任何人的普通打卡 INSERT 都要排队。反噬就发生在这里,不在上线之后。
-- 修法(本文件采用):回填前把【会话】切到 READ COMMITTED —— 该级别下 INSERT ... SELECT
-- 的 SELECT 端是一致性非锁读,对源表不加任何锁,只剩一次全表扫的 I/O。
--   · runner 允许这条语句:--transaction 白名单管不到本文件(CREATE TABLE 本来就进不去
--     那个白名单,本文件只能走普通路径),普通路径不做形状白名单;
--     ci/audit_migrations_applied.py 的只读重放白名单也认 SET SESSION,日后对账不会断。
--   · 不分块:分块要为每块各扛一次守卫与定序,而 RC 已把锁面降到零。真到了必须分块的
--     规模,下面的【行数预检】会先把人拦下来,而不是让他一头扎进去。
--
-- ⚠️【残留:会话依赖没被消掉,只是从守卫搬到了隔离级别】—— 别把上面读成「已解决」。
--    下面【重跑安全性】里判 @claim_rows 死刑的三个场景,对这条 SET SESSION 一字不改地成立:
--    人工把文件拆开粘进两个窗口 / 分块时中途重连 / 未来执行器改成逐句新连接
--    ⇒ 回填那条 INSERT ... SELECT 落在一条【没跑过这条 SET】的新连接上,也就是回到了
--    服务器默认的 REPEATABLE READ。
--    而且新坑比旧坑安静:
--      旧坑(@claim_rows 是 NULL)= 静默插 0 行且 exit 0 —— 回填回读① 能把它抓红。
--      新坑(隔离级别掉回 RR)  = 语句本身照常成功、结果完全正确、所有断言全绿,
--        代价是【回填期间整张 player_node_progress 连同间隙被共享 next-key 锁锁住】,
--        所有人的打卡 INSERT 一起排队。没有任何一条回读会因此变红 —— 它不是正确性问题,
--        是可用性问题,而本文件里没有任何东西在观测可用性。
--    隔离断言(本文件 `SELECT @@SESSION.transaction_isolation = 'READ-COMMITTED'` 那条)
--    【只证明它自己那条连接】,证明不了下一条语句还在同一条连接上。
--    (别按行号找 —— 行号会随编辑漂移,按语句内容找。)
--    真正的防线只有一条【操作纪律】:本文件必须整份、按顺序、在同一条连接里跑完
--    (run_migration.py 就是这么跑的)。手工执行 = 自己扛这个前提。
--
-- 【各键的职责,一个都不能省】
--   PRIMARY KEY (node_id, medal_rank)   抢占闸本体:同一 node 的 rank=1 全平台只能一行。
--   uk_node_member (node_id, member_id) 同一人同一 node 只能持一枚。必需 ——
--       player_node_progress 的唯一键含 activity_id,同一人换一局或自玩(activity_id=0)
--       可以对同一 node 再次「首次完成」;没有这道键他会先拿金再拿银,把低流量节点的
--       领奖台包圆。
--   idx_claim_member (member_id)        feed 侧按 (member_id, activity_id) /
--       (member_id, topic_id) 聚合;一个人的持牌数极少,回表过滤成本可忽略。
--   没有 del_flag:名次不可撤销,软删只会造出「占着档位的幽灵」。
--       撤销是人工修复,不是常态状态位。退款/注销后名次仍在,这是设计不是 bug。
--
-- 【回填口径】
--   来源  player_node_progress,只取 status = 1 且 del_flag = 0
--         (mode2 扫码进店建的 status = 0 行不算完成)。
--   ★与线上同源,这是核过代码的结论不是推测(2026-09-02):status = 1 的行全平台只有两个
--     产生点 —— ①② advanceProgress 里 p.setStatus(1) 后直插
--     (ApiPlayProgressController.java 的 insertPlayerNodeProgress 调用点);
--     ③ 探店日由 shopDayProofService.markGameDone 把 0→1(同文件 shopDay 分支)。
--     两条都汇进同一个 `boolean firstTime = (rows > 0)`,而该分支最后【无条件】调 claimNodeMedal,
--     没有 shopDay 判断。所以 ③ 的牌照发,「历史能得、未来永不能得」这个不一致【不存在】,
--     不需要消除,也不该为它把回填收窄 —— 收窄反而会让 ③ 的历史行凭空少一档。
--     反向也一致:status = 0 的行(进店没答题 / 没配游戏)两侧都不发牌。
--   topic_id  取 cms_topic_node.topic_id —— 与线上抢占的 node.getTopicId() 同源。
--     【不能】用 player_node_progress.topic_id:那是 M2 之后才加的列(NOT NULL DEFAULT 0,
--     '0=旧活动会话'),M2 之前的历史行一律是 0;而 feed 侧按 (member_id, topic_id) 聚合
--     ⇒ 照抄 0 会让历史奖牌在主题维度上永远匹配不上,页面上就是「牌不见了」且零告警。
--     节点被硬删时 LEFT JOIN 取不到,回落 p.topic_id 再回落 0:宁可这一枚在主题 feed 里
--     看不见,也不能把候选行整条丢掉 —— 丢一行会让排在它后面的人整体前移一个名次。
--   去重  每人每 node 只取【最早的那一次】完成 —— 规则是「头三个人」,不是「头三行」。
--   排序  checkin_time 升序;NULL 排最后但不丢弃(不许拿金牌,但仍可捡剩下的档位);
--         checkin_time 只有秒精度,同秒用 player_node_progress.id 做确定性 tie-break。
--   取数  每 node 前三名 -> medal_rank 1 / 2 / 3。
--   已知偏差(接受,不修):
--     · 历史与未来是两套定序规则 —— 回填按 checkin_time + id,线上按提交顺序;
--       id 是语句执行时分配的、不是提交时分配的,回滚还会留空洞,所以回填的
--       「第一名」未必真是当时第一个。数据里没有毫秒级完成时刻,消不掉。
--     · 探店日(mode2)行的 checkin_time 是「到店时刻」而非「完成时刻」
--       (翻 status 时不改时间),回填会把它排在真实完成时间之前。上线后的实时抢占不受影响。
--
-- 【重跑安全性】
--   建表  CREATE TABLE IF NOT EXISTS,重复执行不报错。
--   回填  INSERT IGNORE + 【该 node 一枚牌都没有】守卫,两层各管一件事:
--     · 守卫写在同一条 INSERT 的 FROM 里(LEFT JOIN 一张按 node_id 聚合的派生表),
--       【不再用 @claim_rows 会话变量】。原因:SET @x := ... 与 WHERE @x = 0 是两条语句,
--       只在同一连接成立;人工拆开粘进两个窗口 / 分块时重连 / 未来执行器改成逐句新连接
--       ⇒ @x 是 NULL,`NULL = 0` 求值为 NULL ⇒ 回填【静默插 0 行且 exit 0】。
--       同一个坑 ci/audit_migrations_applied.py 头部已经记过一次。
--       派生表在【本语句内】求值,跨不跨连接都一样。
--       ⚠️ 派生表里的 GROUP BY 保留着,但它是【第二层保险】,不是唯一那道闸。
--          ⛔ 原稿在这里写过「少了它就会边插边读自己 ⇒ 每个 node 只发一枚金牌」——
--             那个具体因果【在 MySQL 8.0.45 上实测没能复现】,已作废,别再照它推理。
--          实测(一次性 docker MySQL 8.0.45,与生产零接触):结构性删掉 GROUP BY 后
--          EXPLAIN FORMAT=TREE 确认派生表【确实被 merge】(Materialize → Nested loop),
--          但两版计划顶上都有 `Insert into ... -> Table scan on <temporary> -> Temporary table`
--          —— INSERT ... SELECT 读到自己的目标表时,MySQL 先把整个 SELECT 灌进临时表再开始插,
--          【读全部发生在写之前】,所以退化没发生,回填结果仍是 1,2,3。
--          缓冲这个行为不受任何书面保证,保险照留;但把它当【已验证机制】去推理是错的。
--          真正盯这类退化的是下面的回读③,它盯【结果少发】这个状态本身,不管起因。
--     · 落库前重跑(表空):结果逐行一致;IGNORE 只兜住重入。
--     · 落库后重跑:已有牌的 node 整体跳过。挡的是这个洞 —— jar 上线后线上抢占的定序
--       与回填不同源,若用【整表为空】那种粗守卫,一旦被绕过就可能「rank1 候选被
--       uk_node_member 挡下、rank3 却插了进去」⇒ 名次出洞;按 node 判则该 node 要么
--       一枚未发(没人会被 uk_node_member 挡)、要么整体跳过,构造不出这个洞。
--     · 反过来,这让原地重跑成为【修复手段】:下面「有完成就得有牌」的断言红了,
--       重跑本文件即可补齐缺牌的 node,不必 TRUNCATE。
--   要整份重做(比如定序算错):先 TRUNCATE node_medal_claim 再重跑。
--   分块  真要按 node_id 分块,在最内层 WHERE 上加 node_id 区间即可 —— 守卫按 node 判,
--         块与块之间不会互相熄火(旧的 @claim_rows 版本做不到:第一块插完表就非空了)。
--         ⚠️【硬要求:每一块都必须先跑一遍 SET SESSION TRANSACTION ISOLATION LEVEL
--            READ COMMITTED + 紧跟其后的隔离断言,再跑该块的 INSERT】。
--            分块最常见的做法就是一块一条连接(或中途断线重连),而隔离级别是【会话】属性,
--            不跟着文件走。漏掉这一步的那一块会退回 REPEATABLE READ,悄悄把整张
--            player_node_progress 锁住 —— 结果正确、断言全绿、线上打卡全排队。
--            见文件头【回填的锁面】末尾那条【残留】。
--            这两句必须和该块的 INSERT 在【同一条连接】里,顺序不能换、不能省。
--
-- 【回滚办法】
--   DROP TABLE IF EXISTS node_medal_claim;
--   ⚠️ 必须【先回滚 jar,再 DROP 表】。顺序反了 = 每次节点完成 Table doesn't exist -> 500。
--   ⚠️ 回滚不是无代价的:回滚期间照常有人完成节点、没人写 claim,重新部署后档位已被
--      后来者占走,补跑回填也拿不回来 —— 名次是一次性棘轮。
--
-- 【落库顺序 —— 反了是 P0】
--   本文件必须【先于】携带抢占代码的 jar 落库。反了 = 每次节点完成 500,整条游玩链断。
--   依赖:无(只读已存在的 player_node_progress)。
--
-- 【环境要求】MySQL 8(回填用窗口函数);CHECK 约束真强制需 8.0.16+。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `node_medal_claim` (
  `node_id`     BIGINT           NOT NULL COMMENT 'cms_topic_node.id(全局唯一,不必再带 topic_id)',
  `medal_rank`  TINYINT UNSIGNED NOT NULL COMMENT '1金 2银 3铜',
  `member_id`   BIGINT           NOT NULL COMMENT '持牌人',
  `activity_id` BIGINT           NOT NULL DEFAULT 0 COMMENT '夺牌所在会话(0=自玩),feed 按会话归属',
  `topic_id`    BIGINT           NOT NULL DEFAULT 0 COMMENT '夺牌所在主题,feed 按主题归属',
  `progress_id` BIGINT           NOT NULL DEFAULT 0 COMMENT 'player_node_progress.id,溯源用',
  `claim_time`  DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '抢占时刻(毫秒)',
  PRIMARY KEY (`node_id`, `medal_rank`),
  UNIQUE KEY `uk_node_member` (`node_id`, `member_id`),
  KEY `idx_claim_member` (`member_id`),
  CONSTRAINT `ck_node_medal_rank` CHECK (`medal_rank` BETWEEN 1 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='节点名次奖牌占位:INSERT 撞主键即原子抢占';

-- 形状回读:问 information_schema,不是把刚写进去的值读回来。
-- 同名错形(比如表早就以别的键存在,IF NOT EXISTS 会静默跳过建表)必须在这里 fail-closed:
-- 主键错了 = 抢占闸根本不存在,而代码照跑、零告警。
-- runner: assert=scalar-true
SELECT
  (SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')
     FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'node_medal_claim'
      AND INDEX_NAME = 'PRIMARY') = 'node_id,medal_rank'
  AND
  (SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')
     FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'node_medal_claim'
      AND INDEX_NAME = 'uk_node_member') = 'node_id,member_id';

-- 回填前把【本会话】切到 READ COMMITTED。RR 下 INSERT ... SELECT 会给源表读到的每一行
-- 加共享 next-key 锁,而 status/del_flag 无索引 ⇒ 全表扫 ⇒ 锁死整张 player_node_progress。
-- 理由与代价见文件头【回填的锁面】。只影响本会话,不改全局。
SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;

-- 前置回读:上面那条 SET 真生效了吗?顺带把 RC 的硬前提问清楚 —— binlog 开着且是
-- STATEMENT 时,RC 下写 InnoDB 会直接 1665。宁可在动源表之前红,也不要跑到 INSERT 才
-- 炸一个看不懂的错。
-- 负控:把上面那条 SET 改成 ISOLATION LEVEL REPEATABLE READ 再跑,本断言必须变红。
-- runner: assert=scalar-true
SELECT @@SESSION.transaction_isolation = 'READ-COMMITTED'
   AND (@@GLOBAL.log_bin = 0 OR @@SESSION.binlog_format IN ('ROW', 'MIXED'));

-- 行数预检:回填要把 player_node_progress 全表扫一遍。RC 之后锁面没了,扫描时长还在。
-- 超过 50 万行就【红】,逼人先决定要不要按 node_id 分块,而不是一头扎进去。
-- ⚠️ runner 只打印断言通过/失败,从不打印查询结果,所以「把行数打出来」只能长成
--    「超阈值即红」这个形状;要看确切行数,单独只读跑 SELECT COUNT(*) FROM player_node_progress。
-- 第一个分支让本断言在【已回填过】之后恒真:它判的是「这一次还要不要扫」,不是表有多大,
-- 否则日后表长大会把 ci/audit_migrations_applied.py 的只读重放判成「本迁移未落库」。
-- 负控:必须在【回填前(node_medal_claim 还空)】做 —— 把 500000 改成 0 再跑,本断言必须变红
--       (生产至少有 1 行进度)。回填后做无效:第一个分支已恒真,那是设计如此,不是断言坏了。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM node_medal_claim) > 0
    OR (SELECT COUNT(*) FROM player_node_progress) <= 500000;

-- 历史回填:按「每人每节点的最早一次完成」排全平台前三。
-- 守卫 = 末尾那个 LEFT JOIN ... IS NULL(只回填「一枚牌都没有」的 node),写在同一条语句里,
-- 不依赖会话变量;派生表里的 GROUP BY 承重(阻止 merge,必须物化)。见文件头【重跑安全性】。
INSERT IGNORE INTO node_medal_claim
    (node_id, medal_rank, member_id, activity_id, topic_id, progress_id, claim_time)
SELECT r.node_id, r.rn, r.member_id, r.activity_id, r.topic_id, r.progress_id,
       COALESCE(r.checkin_time, r.create_time, NOW(3))
FROM (
    SELECT f.*,
           ROW_NUMBER() OVER (PARTITION BY f.node_id
                              ORDER BY f.checkin_time IS NULL, f.checkin_time, f.progress_id) AS rn
    FROM (
        SELECT p.node_id, p.member_id,
               COALESCE(p.activity_id, 0)             AS activity_id,
               -- 与线上 node.getTopicId() 同源;p.topic_id 对 M2 之前的历史行恒是 0,
               -- 照抄会让历史奖牌在 (member_id, topic_id) 的 feed 聚合里永远匹配不上。
               COALESCE(n.topic_id, p.topic_id, 0)    AS topic_id,
               p.id AS progress_id, p.checkin_time, p.create_time,
               ROW_NUMBER() OVER (PARTITION BY p.node_id, p.member_id
                                  ORDER BY p.checkin_time IS NULL, p.checkin_time, p.id) AS mrn
        FROM player_node_progress p
        -- LEFT:节点被硬删也不许丢候选行(丢一行 = 后面的人整体前移一个名次)。
        LEFT JOIN cms_topic_node n ON n.id = p.node_id
        WHERE p.status = 1 AND p.del_flag = 0
    ) f
    WHERE f.mrn = 1
) r
LEFT JOIN (SELECT node_id FROM node_medal_claim GROUP BY node_id) claimed
       ON claimed.node_id = r.node_id
WHERE r.rn <= 3
  AND claimed.node_id IS NULL;

-- 回填回读①:【做实事的那一步的断言】—— 原先这一步一条断言都没有,于是「一行没插」
-- 被上面的形状断言和下面的无洞断言(空表恒真)一起判成通过,fail-open。
-- 判据:有资格的历史完成 ⇒ 该 node 至少有一枚牌。它比对【源表】与【目标表】两侧的事实,
-- 不是把刚写进去的值再读一遍 ⇒ 回填整条没跑 / 守卫写反 / 定序漏掉某些 node,这里都红。
-- 而「表已回填过所以这次插 0 行」时牌本来就在,断言仍绿 —— 这就是
-- 「该插却没插(红)」与「不该插所以没插(绿)」的分界线。
-- ⚠️ 为什么不给 INSERT 挂 rowcount-positive:它判的是「上一句改了几行」,合法的零行重跑
--    会被它判红;而且 ci/audit_migrations_applied.py 只读重放复现不了这类断言,对账用不上。
-- ⚠️【本断言不是恒真不变式,健康系统下也会红】—— 原先这里写的是「上线后本断言仍成立」,
--    那句话是错的,别照它排查。它当时的推理是「status=1 只由 firstTime 分支产生,该分支
--    末尾无条件调 claimNodeMedal」;推理的前半段没错,错在把【调了 claimNodeMedal】
--    当成了【写进了一行 claim】。实际有三条「调了但一行没写」的路径:
--      · ApiPlayProgressController#claimNodeMedal 捕获 TransientDataAccessException
--        (1205 锁等待超时这类)后只 logger.warn 然后 return null,不写行,也不抛;
--      · 同方法开头 nodeMedalClaimMapper == null 时直接 return null(纯单测形态,生产不该有);
--      · 本文件落库到 jar 上线之间的空窗:有人完成节点时抢占代码还不在,那一行没有对应的牌。
--      · (2026-09-02 新增的第四条)claimNodeMedal 捕获 BadSqlGrammarException /
--        UncategorizedDataAccessException 后同样 logger.warn + return null —— 这支是给
--        「jar 先于本文件上线」兜底的,日志关键词 `node_medal_claim not ready`。
--    四条都会让「有 status=1 却该 node 一枚牌都没有」成立 ⇒ 本断言红,而系统没坏。
-- 【红了怎么判 —— 先分清是真缺陷还是正常抖动,别反射性改代码】
--    ① 先看范围:红的是【全表所有 node】还是【零星几个】。
--       全红 = 回填整条没跑 / 守卫写反 / 表被 TRUNCATE 过 ⇒ 真缺陷,按下面「怎么补」补。
--    ② 零星红:去应用日志搜 `claimNodeMedal transient failure`,按 nodeId 对得上
--       ⇒ 就是上面第一条路径,属于设计接受的抖动(夺牌是锦上添花,不该有把玩家打卡
--       一起回滚的权力),不是缺陷。留意频次:偶发才算抖动,持续刷 = 库在压力下出事了,
--       那是另一个问题,去查锁等待,别来改这里。
--    ③ 日志里【没有】对应的 warn,而 node 确实缺牌:那就不是抖动。看这批 node 的完成时刻
--       是否全部落在本文件落库与 jar 上线之间(第三条空窗路径) —— 是则等下一个人完成
--       该 node 自然消,也可主动补;都不是,才当真缺陷排查。
-- 【怎么补】原地重跑本文件即可补齐【一枚牌都没有】的 node(守卫按 node 判)。
--    ⚠️ 只补得了「零枚」的 node;「有牌但少了几枚」重跑跳过,那种缺口由回填回读③抓,
--       补法也不同,见③自己的说明。
-- 负控:回填前(表空、而生产有 status=1 的历史行)单跑本断言必须红;或把上面 INSERT 的
--       `WHERE r.rn <= 3` 改成 `r.rn <= 0` 后整份重跑,也必须红。
-- runner: assert=scalar-true
SELECT NOT EXISTS (
  SELECT 1 FROM player_node_progress p
   WHERE p.status = 1 AND p.del_flag = 0
     AND NOT EXISTS (SELECT 1 FROM node_medal_claim c WHERE c.node_id = p.node_id)
);

-- 回填回读②:名次必须是 1..k 的无洞前缀。去重或排序写错就会露洞。
-- ⚠️ 单独看它是 fail-open 的(空表上 NOT EXISTS 恒真),必须与①配着看:①管「有没有」,
--    ②管「发得对不对」。这也是原先只有②时能把「一行没插」判成通过的原因。
-- 负控(落库前在影子库上做一次):把上面回填里的 `WHERE f.mrn = 1` 删掉再跑 ——
-- 同一人会被同时排成 rank1/rank2,第二条被 uk_node_member 挡下变成 IGNORE,rank2 出洞,
-- 本断言必须【变红】。变不红说明断言写废了,先修断言,再跑真的。
-- runner: assert=scalar-true
SELECT NOT EXISTS (
  SELECT 1 FROM node_medal_claim GROUP BY node_id HAVING MAX(medal_rank) <> COUNT(*)
);

-- 回填回读③:【数量闸 —— ①②合起来都看不见的那个退化,只有它能抓住】。
-- ①问「有没有」、②问「发得对不对(无洞)」、③问「够不够」。
-- 守的失败模式:【每个 node 只发一枚金牌】这一类「发了,但少发」的结果。
-- 文件头【重跑安全性】担心的守卫退化(派生表被 merge、边插边读自己)只是它的【一种】起因,
-- 定序写错、守卫写反、将来某次改写都能落到同一个结果上,所以本断言盯【结果】不盯起因。
-- 为什么非它不可:这个结果对 ① 是瞎的(有牌,只是少)、对 ② 也是瞎的(MAX=1、COUNT=1,
-- 无洞),两条都绿,而领奖台上永远只有第一名 —— 零告警。
-- 判据:比对【源表的合格人数】与【目标表的牌数】两侧事实,不回读刚写进去的值。
--   某 node 在源表有 k 个不同的人合格完成 ⇒ 该 node 该有 min(k, 3) 枚牌。
--   正确回填下恒等:候选行已按 (node_id, member_id) 去重(mrn = 1),rn 取 1..min(k,3),
--   每行 member 互不相同 ⇒ 既撞不上 PK 也撞不上 uk_node_member ⇒ 一枚不少。
-- ⚠️ 与 ① 共享同一条「健康系统下也会红」的路径,判法完全一致,见 ① 的【红了怎么判】。
-- ⚠️ ③ 红了【原地重跑本文件补不回来】—— 这点和 ① 不同,别照 ① 的办法处理:
--    守卫是「该 node 一枚牌都没有」,而 ③ 红的 node 手上至少有一枚 ⇒ 重跑整体跳过它。
--    要么 TRUNCATE node_medal_claim 后整份重做(见文件头【重跑安全性】),
--    要么只对这些 node 人工补档。上线后重做还会丢掉线上已抢占的名次,先想清楚再动。
-- ⚠️ 覆盖前提:本断言只在【存在某个 node 有 ≥2 个不同的人合格完成】时才有分辨力。
--    2026-09-02 生产 player_node_progress 只有 1 行(1 个玩家完成 1 个节点)⇒
--    每个 node 的 due = LEAST(1,3) = 1 = got ⇒ 【落库当天 ③ 对「少发」这类退化
--    一点分辨力都没有】,它是绿的,但那个绿不承载任何信息。
--    说满:它是一张【日后才通电】的网,不是现在守着的网。别把它读成
--    「GROUP BY 承重这件事已经被生产验证了」。它是个棘轮:
--    数据一长出第二个完成者就自动开始承重(ci/audit_migrations_applied.py 每次对账都重放它)。
--    落库前的真负控在【影子库】上做,见下面那行。
-- 负控(影子库 MySQL 8.0.45,2026-09-02 实跑,造一个 node 有 4 个不同的人合格完成):
--   把回填的 `WHERE r.rn <= 3` 改成 `r.rn <= 1`,直接造出「每个 node 只发一枚金牌」这个
--   结果集 —— 实测 ① 绿(有牌)、② 绿(MAX=1=COUNT=1,无洞)、本断言【红】。
--   ⇒ ③ 确实是这三条里唯一看得见「少发」的。
-- ⚠️【同一次实跑推翻了一个上游说法,记在这里免得日后有人照着它排查】:
--    上面【重跑安全性】说「少了守卫派生表的 GROUP BY ⇒ 边插边读自己 ⇒ 每个 node 只发一枚金牌」。
--    实测:删掉 GROUP BY 后派生表【确实】被 merge 进外层(EXPLAIN FORMAT=TREE 里守卫从
--    `Materialize` 变成直接对 node_medal_claim 做 `Nested loop left join`),
--    但发牌结果【没有】退化,仍是 1,2,3 —— 因为 INSERT ... SELECT 读到了自己的目标表时,
--    MySQL 会把整个 SELECT 结果先灌进一张 `Temporary table` 再开始插(两版计划顶上都有
--    `Insert into node_medal_claim -> Table scan on <temporary>`),读全部发生在写之前。
--    ⇒ 在 8.0.45 的这个语句形状下,GROUP BY 是【第二层保险】,不是唯一那道闸;
--      「少了它就只发金牌」这个具体因果没能复现。保险照留(换个 MySQL 版本 / 换个语句
--      形状,缓冲行为不受任何书面保证),但【别把它当成已验证的机制去推理】。
--    ⇒ 也正因为它没能复现,本断言不能只盯着 GROUP BY:它守的是【结果少发】这个状态,
--      不管起因是 merge、定序写错、守卫写反、还是将来某次改写。
-- runner: assert=scalar-true
SELECT NOT EXISTS (
  SELECT 1
    FROM (SELECT p.node_id, LEAST(COUNT(DISTINCT p.member_id), 3) AS due
            FROM player_node_progress p
           WHERE p.status = 1 AND p.del_flag = 0
           GROUP BY p.node_id) src
    LEFT JOIN (SELECT node_id, COUNT(*) AS got
                 FROM node_medal_claim
                GROUP BY node_id) held ON held.node_id = src.node_id
   WHERE COALESCE(held.got, 0) < src.due
);

-- ---------------------------------------------------------------------------
-- 【落库记录】2026-09-02 已在生产执行完毕(先于 jar,顺序正确)。
--   9 条语句 / 6 条 scalar-true 断言全部通过;换新连接回读坐实(不是提交回执):
--     表存在 · InnoDB · PRIMARY=(node_id,medal_rank) · uk_node_member=(node_id,member_id)
--     · ck_node_medal_rank 存在 · 实际发牌 node=1 rank=1 · topic_id 与 cms_topic_node 一致
--     · 新连接隔离级别仍是 REPEATABLE-READ(READ COMMITTED 没泄漏到别的连接)
--   ⇒ 本 PR 合并前不需要再落库。回滚顺序:先回滚 jar,再 DROP TABLE(反了会 500)。
-- ---------------------------------------------------------------------------
