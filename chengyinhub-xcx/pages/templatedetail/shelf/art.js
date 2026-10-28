// 模板页货架的包内图与编排(2026-10-28 业主定稿原型 v21)。
// 图全部放在本分包 art/ 下：主包已超棘轮,服务端 profile 静态目录要人工上传,
// 包内图打开就能显示，不依赖接口回来的 imgUrl。
// 来源与许可：
//   · props/:Polyfork 低多边形模型的商店渲染图抠图(免费、可商用、免署名，不得转售模型文件);
//   · covers/game/ 有照片的 8 张：Openverse CC0 照片;其余为同一套道具 + 纯色底的合成封面;
//   · covers/topic/:仓库 assets/covers/topic-templates 既有封面 + 2 张 CC0/PDM 照片
//     (城市藏宝猎人 = Flickr「Shanghai Bund」CC0,生活不掉线 = Flickr「Spin City Laundromat」PDM)。
// 主题按名称、玩法按标题绑定，与 pages/template 既有 INTERACTION_ICON_BY_TITLE 同口径(种子重跑 id 会漂)。

const BASE = '/pages/templatedetail/art/';
const propSrc = (slug) => BASE + 'props/' + slug + '.png';
const gameCoverSrc = (slug) => BASE + 'covers/game/' + slug + '.jpg';
const topicCoverSrc = (slug) => BASE + 'covers/topic/' + slug + '.jpg';

const GROUPS = [
  { key: 'shop', name: '到店互动', color: '#FF9F0A' } /* ds-ok 原型分组色 */,
  { key: 'team', name: '小队协作', color: '#0A84FF' } /* ds-ok 原型分组色 */,
  { key: 'city', name: '城市探索', color: '#30D158' } /* ds-ok 原型分组色 */
];

// 标题 → [道具/封面文件名, 分组, 一行短介绍]。只有这 25 个上货架(业主 2026-10-27 定的保留清单)。
const GAMES = {
  '今晚的暗号': ['secret-code', 'shop', '说出暗号拿奖励'],
  '老板的秘密题': ['secret-question', 'shop', '答对店铺小问题'],
  '拍下这一刻': ['photo-moment', 'city', '一张照片过关'],
  '收集这枚风味印章': ['flavor-stamp', 'shop', '到店集印章'],
  '新品盲测局': ['blind-test', 'shop', '抢先试新品'],
  '今日搭配任务': ['style-mission', 'shop', '搭出今日风格'],
  '集合点亮': ['gather', 'team', '到点领身份'],
  '小队竞速点亮': ['team-race', 'team', '抢先点亮目标'],
  '街角谜题': ['street-puzzle', 'city', '找线索解暗号'],
  '城市取景任务': ['city-camera', 'city', '按主题拍一张'],
  '城市接力棒': ['relay', 'team', '解题传给下一队'],
  '今日角色任务': ['role-mission', 'team', '抽身份做任务'],
  '招牌菜限时盲猜': ['signature-dish', 'shop', '限时猜菜名'],
  '咖啡风味人格': ['coffee-personality', 'shop', '两题测风味'],
  '分支故事接力': ['branch-story', 'team', '轮流讲故事'],
  '细节观察挑战': ['detail-observation', 'city', '90 秒找细节'],
  '一桌任务宾果': ['table-bingo', 'team', '同桌破冰'],
  '小队能量接力': ['team-energy', 'team', '小队答题接力'],
  '闭眼味觉师': ['blind-taste', 'shop', '闭眼猜味道'],
  '沉默点单': ['silent-order', 'shop', '不说话点单'],
  '便利店调酒师': ['diy-drink', 'shop', '调一杯起个名'],
  '治愈音乐角': ['music-corner', 'shop', '听完一首歌'],
  '午夜电台': ['midnight-radio', 'shop', '只在深夜开放'],
  '低碳行动': ['low-carbon', 'city', '步数换爪印'],
  '今日城市签': ['daily-city-sign', 'city', '每天一张签']
};

// 玩法 tab 顶部大卡：推荐语(白色首句 + 灰色后半，最多两行)、封面底部渐变取色。
const FEATURED_GAMES = [
  ['一桌任务宾果', '三项任务，一桌人。', '等餐时抽三张卡，同桌一起完成。', '#2f3636'],
  ['分支故事接力', '轮到你讲下一段。', '2–4 人接力，故事走向由你们决定。', '#423c23'],
  ['招牌菜限时盲猜', '60 秒猜出招牌。', '看三条线索，抢先说出店里的招牌菜。', '#4e4a4a'],
  ['闭眼味觉师', '闭上眼，尝一口。', '从四个选项里猜出杯里是什么。', '#764a22'],
  ['城市取景任务', '拍下今天的城市。', '按主题找画面，一张照片就过关。', '#483933'],
  ['小队竞速点亮', '谁先到，谁点亮。', '和队友分头出发，抢先点亮目标点。', '#262f3f'],
  ['拍下这一刻', '把这一刻拍下来。', '一张照片，完成今天的城市任务。', '#392b3a'],
  ['咖啡风味人格', '测测你的咖啡人格。', '两道选择题，抽一张专属风味卡。', '#373d41']
];

const GAME_TAGS = [
  { key: 'shop', name: '到店互动', test: (g) => g._grp === 'shop' },
  { key: 'team', name: '小队协作', test: (g) => g._grp === 'team' },
  { key: 'city', name: '城市探索', test: (g) => g._grp === 'city' },
  { key: 'solo', name: '单人可玩', test: (g) => /单人/.test(g.players || '') },
  { key: 'multi', name: '多人一起', test: (g) => /小队|2-4|双人/.test(g.players || '') },
  { key: 'quick', name: '5 分钟内', test: (g) => Number(g.duration) > 0 && Number(g.duration) <= 5 }
];

// 主题名 → 封面文件名 + 卡片取色(封面主色,与原型同一算法预先算好)。
const THEMES = {
  '城市藏宝猎人': ['treasure-hunter', 'hsla(19,35%,34%,.85)'],
  '野生邻居观察局': ['wildlife-observation', 'hsla(34,35%,34%,.85)'],
  '异常事务局：不存在的坐标': ['anomaly-coordinate', 'hsla(216,62%,34%,.85)'],
  '末日广播：最后一座中继站': ['apocalypse-broadcast', 'hsla(264,35%,34%,.85)'],
  '时间污染：被删除的今天': ['time-pollution', 'hsla(36,35%,34%,.85)'],
  '风味巡游': ['flavor-tour', 'hsla(355,35%,34%,.85)'],
  '不惑之年': ['midlife-day', 'hsla(97,35%,34%,.85)'],
  '预制人生': ['prebuilt-life', 'hsla(26,35%,34%,.85)'],
  '高温末日逃生': ['heat-escape', 'hsla(209,35%,34%,.85)'],
  '上海像一部旧电影': ['old-movie', 'hsla(211,35%,34%,.85)'],
  '生活不掉线': ['life-backup', 'hsla(199,35%,34%,.85)']
};

// 主题标签(可多选归属)。quick 按时长现算,不列名单。
const TOPIC_TAGS = [
  { key: 'story', name: '剧情解谜', members: ['异常事务局：不存在的坐标', '末日广播：最后一座中继站', '时间污染：被删除的今天', '城市藏宝猎人'] },
  { key: 'walk', name: '城市漫步', members: ['上海像一部旧电影', '野生邻居观察局', '不惑之年', '预制人生', '高温末日逃生'] },
  { key: 'food', name: '美食探店', members: ['风味巡游'] },
  { key: 'life', name: '生活日常', members: ['生活不掉线'] },
  { key: 'history', name: '历史人文', members: ['城市藏宝猎人', '上海像一部旧电影', '时间污染：被删除的今天'] },
  { key: 'scifi', name: '科幻异常', members: ['异常事务局：不存在的坐标', '末日广播：最后一座中继站', '时间污染：被删除的今天'] },
  { key: 'photo', name: '拍照出片', members: ['上海像一部旧电影', '风味巡游', '野生邻居观察局'] },
  { key: 'nature', name: '户外自然', members: ['野生邻居观察局', '异常事务局：不存在的坐标'] },
  { key: 'indoor', name: '雨天室内', members: ['高温末日逃生', '不惑之年', '生活不掉线'] },
  { key: 'quick', name: '两小时内', members: null }
];

const FEATURED_TOPICS = ['异常事务局：不存在的坐标', '风味巡游', '末日广播：最后一座中继站', '上海像一部旧电影', '时间污染：被删除的今天', '城市藏宝猎人', '高温末日逃生'];

const TOPIC_SHELVES = [
  ['两小时内玩完', null],
  ['科幻异常', ['异常事务局：不存在的坐标', '末日广播：最后一座中继站', '时间污染：被删除的今天']],
  ['城市记忆', ['城市藏宝猎人', '上海像一部旧电影', '时间污染：被删除的今天']],
  ['雨天也能玩', ['高温末日逃生', '不惑之年', '生活不掉线']]
];

// 主题名可能带版本后缀(如「预制人生 · 四站试玩版」),先精确再按前缀认。
function themeKey(name) {
  const text = String(name || '').trim();
  if (THEMES[text]) return text;
  return Object.keys(THEMES).find((k) => text.indexOf(k) === 0) || '';
}

function topicArt(name) {
  const key = themeKey(name);
  return key ? { key: key, cover: topicCoverSrc(THEMES[key][0]), glow: THEMES[key][1] } : null;
}

function gameArt(title) {
  const row = GAMES[String(title || '').trim()];
  if (!row) return null;
  const feat = FEATURED_GAMES.find((f) => f[0] === title);
  return {
    slug: row[0], grp: row[1], short: row[2],
    prop: propSrc(row[0]), cover: gameCoverSrc(row[0]),
    lead: feat ? feat[1] : '', rest: feat ? feat[2] : '', tint: feat ? feat[3] : ''
  };
}

module.exports = {
  GROUPS, GAMES, FEATURED_GAMES, GAME_TAGS, THEMES, TOPIC_TAGS, FEATURED_TOPICS, TOPIC_SHELVES,
  propSrc, gameCoverSrc, topicCoverSrc, themeKey, topicArt, gameArt
};
