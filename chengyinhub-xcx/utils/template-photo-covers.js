// 摄影来源、许可与文件校验值见 docs/image-sources/template-photos-20261023.json。
const PHOTO_GROUPS = {
  coffee: ['今晚的暗号', '老板的秘密题', '收集这枚风味印章', '新品盲测局', '闭眼味觉师',
    '隐藏菜单挑战', '隐藏菜单线索链', '咖啡风味人格', '招牌菜限时盲猜', '同桌口味接力',
    '菜单共创接力', '隐藏菜单', '听音辨物', '菜单顺口溜', '沉默点单', '风味巡游',
    '上海本帮菜食材寻味之旅', '成都宽窄巷子川味探索'],
  camera: ['拍下这一刻', '城市取景任务', '拍照挑战', '今天拍点好看的'],
  street: ['街角谜题', '今日城市签', '城市支线观察局', '细节观察挑战', '时间错格',
    '神秘店员的暗号', '三步解谜', '三连快答', '集章巡店', '岔路口的选择', '逆向NPC',
    '上海像一部旧电影', '不惑之年', '预制人生', '城市藏宝猎人', '异常事务局：不存在的坐标',
    '时间污染：被删除的今天', '上海科技馆AR探索之旅', '西安大唐不夜城汉服体验'],
  night: ['午夜电台', '夜猫子城市生活', '下班后切换频道', '便利店调酒师',
    '末日广播：最后一座中继站', '上海沉浸式戏剧《不眠之夜》'],
  music: ['治愈音乐角', '点歌情绪接力', '节奏盲猜夜'],
  travel: ['低碳行动', '一个人也出发', '高温末日逃生', '安全观察宾果', '旅伴决策接力',
    '路线纪念章故事局', '生活不掉线', '杭州西湖群山徒步指南', '上海滨江10KM骑行攻略',
    '三亚蜈支洲岛潜水体验', 'G0｜30秒生活掉线测试', 'G1｜现在出状况,你先选什么',
    'G2｜我的底线三选一', 'G3｜营业时间拼图', 'G4｜去之前准备什么',
    'G5｜值不值得现在处理', 'G6｜首选失效怎么办'],
  style: ['今日搭配任务', '穿搭盲配', '很时尚', '风格人格实验室', '空间搭配挑战',
    '色彩共创接力', '换季收纳挑战', '衣物护理宾果', '污渍侦探', '日常护理宾果'],
  gifts: ['三个盲盒', '今天值得庆祝', '纪念物线索寻宝', '礼物偏好猜想局', '节日故事拼图',
    '双人纪念故事接力', 'G0｜30秒今天值得什么', 'G1｜每人一个不能', 'G2｜气氛卡碰撞',
    'G3｜三小时时间方块', 'G4｜每人预算筹码', 'G5｜十分钟步行上限',
    'G6｜主选突然掉线', 'G7｜60秒共同落款'],
  tools: ['店内寻宝', '维修工具盲猜', '家庭安全检查接力', '家居安全排雷'],
  fitness: ['小队竞速点亮', '小队能量接力', '搭子训练接力', '动作宾果',
    '全馆公益热身接力', '60秒健身知识反应赛'],
  cat: ['爪印巡街', '野生邻居观察局'],
  friends: ['集合点亮', '城市接力棒', '今日角色任务', '一桌任务宾果', '表演任务宾果',
    '原创角色协作局', '分支故事接力', '亲子各自开心']
};

const PHOTO_BY_TITLE = Object.create(null);
const PHOTO_BASE_URL = 'https://www.chengyinhub.com/prod-api/profile/template-photos-20261023/';
Object.keys(PHOTO_GROUPS).forEach(function (key) {
  PHOTO_GROUPS[key].forEach(function (title) {
    PHOTO_BY_TITLE[title] = PHOTO_BASE_URL + key + '.webp';
  });
});

function templatePhotoCover(template) {
  const source = template || {};
  const original = source.imgUrl || '';
  // 作者自传的封面保留；仅替换旧公共封面和缺图，不把本地资源写回业务数据。
  if (original && !/^(?:https:\/\/www\.chengyinhub\.com\/prod-api\/profile\/(?:template-covers|merchant-game-covers)\/|\/images\/interaction-templates\/)/.test(original)) return original;
  return PHOTO_BY_TITLE[source.title || source.name || ''] || original;
}

module.exports = { templatePhotoCover };
