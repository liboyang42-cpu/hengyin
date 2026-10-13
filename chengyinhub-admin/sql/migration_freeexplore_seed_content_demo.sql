-- ============================================================================
-- 自由探索(mode2)碎片叙事 · 真实文案种子(S8)
-- 用途:给一条真实主题的 7 个节点补齐 hook_text / card_hook_long / fragment_text 三列真实文案,
--       替代 automator 自证脚本里的随机词库(scripts/_seed_fragments.js),验证"碎片叙事内容层成立"。
-- 主题范式:一条「老城慢走 · 城的旧梦」城市定向路线,7 段 fragment 按完成顺序拼起来 = 一封连贯的结局信。
--
-- 部署方式(生产库):走 chengyin-db-migrate skill,只读预检后由用户确认执行。
--   1) 把 @topic_id 改成你要铺文案的真实主题 id;
--   2) 该主题需恰有(至少)7 个节点,且 sort_id 为 1..7(单章节;跨章节请按实际 sort_id 调整 WHERE);
--   3) 三列长度:hook_text VARCHAR(120) / card_hook_long VARCHAR(300) / fragment_text TEXT,以下文案均在限内。
-- 幂等:纯 UPDATE 覆盖指定列,可重复执行;不改结构、不动其它列。
-- ============================================================================

SET @topic_id = 0;  -- ← 改成真实主题 id 再执行(0 是占位,不会命中任何行,防误伤)

-- 节点 1 · 老茶馆(起点)
UPDATE cms_topic_node SET
  hook_text      = '推开吱呀的木门,茶香先到',
  card_hook_long = '别急着走。第一站,先坐下喝口茶。老板说这屋子比他爷爷还老。你要找的那座城市,就是从一盏茶的热气里开始的。',
  fragment_text  = '那天我决定重新认识这座城市,从一盏茶开始。热气模糊了窗,窗外的街还没醒。'
WHERE topic_id = @topic_id AND sort_id = 1;

-- 节点 2 · 青石巷
UPDATE cms_topic_node SET
  hook_text      = '青石板被磨得发亮,像谁走了一百年',
  card_hook_long = '低头看看脚下——这些青石板,踩过挑夫、学生、逃兵、恋人。你现在踩的这一块,也在悄悄把你记下来。',
  fragment_text  = '巷子把我带向更深的地方,脚步声在两堵老墙之间来回响。我忽然不确定,是我在走巷子,还是巷子在带我。'
WHERE topic_id = @topic_id AND sort_id = 2;

-- 节点 3 · 旧书店
UPDATE cms_topic_node SET
  hook_text      = '旧书店的猫,守着一屋子别人的故事',
  card_hook_long = '进去说话轻一点,猫在睡觉。这里的书都被人读旧过,随手翻开一本,可能掉出一张四十年前的车票,和一句谁写在页边的话。',
  fragment_text  = '在旧书店,我读到一句别人用铅笔划过的话:"要走很远,才认得出家门。"忽然就懂了自己为什么会来。'
WHERE topic_id = @topic_id AND sort_id = 3;

-- 节点 4 · 石桥
UPDATE cms_topic_node SET
  hook_text      = '桥下的水,记得比人还清楚',
  card_hook_long = '站到桥中间往下看。这条河穿城而过,涨过、枯过、结过冰,却从没离开。它把两岸的人和事,一遍遍抄写在水面上。',
  fragment_text  = '站在桥上,我看见水面倒着整座城——房檐、云、还有一个正在看它的我。原来这座城,一直有另一半藏在水里。'
WHERE topic_id = @topic_id AND sort_id = 4;

-- 节点 5 · 小庙
UPDATE cms_topic_node SET
  hook_text      = '香灰落下来的地方,时间慢半拍',
  card_hook_long = '不用许什么大愿。就站一会儿,听香在烧。这座小庙没什么名气,却替这条街上的人,收了几百年的心事。',
  fragment_text  = '庙里的香火提醒我:慢一点,再慢一点。我这才发现,自己已经很久没有好好走过一条街了。'
WHERE topic_id = @topic_id AND sort_id = 5;

-- 节点 6 · 夜市
UPDATE cms_topic_node SET
  hook_text      = '夜市亮起来,城市才开始说真话',
  card_hook_long = '天黑了才算真正到站。油锅、吆喝、塑料凳、陌生人的笑——白天板着脸的城市,到这里终于松了口气,露出它热腾腾的里子。',
  fragment_text  = '夜市的灯一盏盏亮起来,我终于听见这座城市在说话:它说的不是大事,是烟火、是热汤、是"再来一碗吗"。'
WHERE topic_id = @topic_id AND sort_id = 6;

-- 节点 7 · 天台(终点)
UPDATE cms_topic_node SET
  hook_text      = '爬到最高处,把走过的路一次看完',
  card_hook_long = '最后一站,往上走。到了顶,回头看——茶馆、巷子、书店、桥、庙、夜市,今天你走过的每一处,现在都在你脚下亮着。',
  fragment_text  = '爬到天台,风很大。我把今天走过的每一步都收进心里——茶、石板、旧书、桥上的倒影、香火和灯。原来这座城,一直在等我,慢慢地,把它重新走一遍。'
WHERE topic_id = @topic_id AND sort_id = 7;

-- 校验(执行后手动看一眼):以下应返回 7 行、三列均非空
-- SELECT sort_id, hook_text, card_hook_long, LEFT(fragment_text,20) AS frag FROM cms_topic_node
--   WHERE topic_id = @topic_id AND sort_id BETWEEN 1 AND 7 ORDER BY sort_id;
