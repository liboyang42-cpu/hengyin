/* R5 基础目录来自批准的 template-editor-v2-proto.html。
 * 旧形式 key/section 保持存储与试玩兼容；validationMethod 仅供旧配置识别，
 * 新作者的参与方式独立选择，不由玩法目录改写。
 */

const advancedGameConfig = require('./advanced-game-config.js');

const GROUPS = [
  {
    title: '内容展示',
    items: [
      { key: 'album', icon: 'image', label: '相册', sub: '上传照片，翻阅一段回忆', section: 'album', validationMethod: 0 },
    ],
  },
  {
    title: '问答类',
    items: [
      { key: 'qaText', icon: 'edit', label: '文字问答', sub: '出一道题，玩家打字作答', section: 'qa', mode: 'TYPE', validationMethod: 0 },
      { key: 'qaPick', icon: 'check', label: '选项问答', sub: '2-4 个选项里选一个', section: 'qa', mode: 'PICK', validationMethod: 0 },
      { key: 'qaShot', icon: 'camera', label: '拍照打卡', sub: '给一句提示词，玩家拍一张交上来', section: 'qa', mode: 'SHOT', validationMethod: 2 },
      { key: 'branch', icon: 'switch-chapter', label: '分支剧情', sub: '界面和问答一样，只是选项决定下一段', section: 'branch', validationMethod: 0 },
    ],
  },
  {
    /* 推理类(2026-09-16):三种都是「有唯一正确答案」的题面,答案一律不下发 ——
       排序的 answerOrder / 连线的 pairs / 分类的 answer 都只存在服务端。 */
    title: '推理类',
    items: [
      { key: 'sort', icon: 'sort', label: '排序', sub: '把几条按正确顺序排好', section: 'sort', validationMethod: 0 },
      { key: 'match', icon: 'connect', label: '连线', sub: '两列里一一配对', section: 'match', validationMethod: 0 },
      { key: 'classify', icon: 'filter', label: '分类', sub: '每条归到对的那一类', section: 'classify', validationMethod: 0 },
    ],
  },
  {
    title: '互动类',
    items: [
      { key: 'estimate', icon: 'finance', label: '猜数字', sub: '猜一个数，按接近程度分档给分', section: 'estimate', validationMethod: 8 },
      { key: 'pricePair', icon: 'image', label: '猜图', sub: '几张图里挑出正确的那张', section: 'pricePair', validationMethod: 9 },
      { key: 'hidden', icon: 'search', label: '找东西', sub: '在你上传的图上点中藏起来的目标', section: 'hiddenObject', validationMethod: 10 },
      { key: 'predict', icon: 'flag', label: '竞猜', sub: '今天押一个，到期商家给答案', section: 'predict', validationMethod: 0 },
      { key: 'random', icon: 'gift', label: '抽卡', sub: '一副卡池抽一张，翻开就是这一关的任务', section: 'random', validationMethod: 0 },
      /* 《预制人生》两段(契约 §2.1 / §2.4)。问答式交互,但结果都写进会话状态:
         建档写角色档案(后面的故事能引用),留言写给下一个来的人。 */
      { key: 'profile', icon: 'edit', label: '角色建档', sub: '设置问题，填写角色资料', section: 'profile', validationMethod: 0 },
      { key: 'note', icon: 'edit', label: '留言', sub: '写一句话，留给下一个来这里的人', section: 'note', validationMethod: 0 },
      /* 拍物成卡(立体藏品卡契约 §2.1):与「拍照审核」共用 photoCheck 段,靠 mode 区分。
         ★ 它必须排在「挑战类」那条 photoCheck **前面** —— detectGame 取第一个 section.enabled
         的条目,而 photoCheck 那条不带 mode(它要能匹配没有 mode 的老配置)。顺序一反,
         藏品卡会被抢先认成普通拍照审核,而且不报错。钉在 object-card-detect-contract。 */
      { key: 'objectCard', icon: 'camera', label: '拍物成卡', sub: '拍下一件东西，生成一张能转着看的藏品卡', section: 'photoCheck', mode: 'CARD', validationMethod: 0 },
      /* 九宫格是主题级的:格子和奖励在主题上配,节点这里零输入(见 b84ef6381)。
         原型把它也摆在这张单子里,所以保留入口,但配置面板只说明它在哪儿配。 */
      { key: 'bingo', icon: 'filter', label: '九宫格', sub: '走到一处点亮一格，连成线就换奖', section: '', badge: '主题级', validationMethod: 0 },
    ],
  },
  {
    title: '挑战类',
    items: [
      { key: 'steps', icon: 'walk', label: '计步挑战', sub: '以微信运动同步的步数为准', section: 'steps', validationMethod: 0 },
      { key: 'react', icon: 'star', label: '变色就点', sub: '屏幕一变色就点，比谁快', section: 'reaction', validationMethod: 0 },
      { key: 'shake', icon: 'play', label: '弹球', sub: '倾斜手机，让球撞够手机边缘的次数', section: 'ballShake', validationMethod: 0 },
      { key: 'quiet', icon: 'heart', label: '安静挑战', sub: '一直安静到时间到，响一下从头再来', section: 'quietHold', validationMethod: 0 },
      { key: 'compass', icon: 'route', label: '罗盘指向', sub: '把手机转向目标方位，对准保持几秒', section: 'compass', validationMethod: 0 },
      { key: 'shout', icon: 'bell', label: '喊一嗓子', sub: '让这儿的声音够大，连续喊满几秒', section: 'shout', validationMethod: 0 },
      { key: 'countdown', icon: 'clock', label: '倒计时', sub: '一钟油，时间走它就退', section: 'countdown', validationMethod: 0 },
      { key: 'stopwatch', icon: 'clock', label: '精准停表', sub: '盲停在目标秒数上，差多少算多少', section: 'stopwatch', validationMethod: 0 },
      /* 《预制人生》三段(契约 §2.2 / §2.5)+ R14 检定(契约 §2.3 更正:不新造段,
         接的是 master 上既有的 check)。判定一律在服务端。 */
      { key: 'photoCheck', icon: 'camera', label: '拍照审核', sub: '拍一张，写清要拍到什么，提交后等待判定', section: 'photoCheck', validationMethod: 0 },
      { key: 'check', icon: 'star', label: 'D20', sub: '掷一个二十面骰，点数够难度就成功', section: 'check', validationMethod: 0 },
      { key: 'd20', icon: 'star', label: 'D20 加值', sub: '二十面骰 + 加值，对照 DC 判定结果', section: 'diceRoll', mode: 'd20', validationMethod: 0 },
      { key: 'typeIn', icon: 'clock', label: '输入挑战', sub: '照着一行字打，超时或打错不算', section: 'typeIn', validationMethod: 0 },
    ],
  },
  {
    title: '决定类',
    items: [
      { key: 'coin', icon: 'coupon', label: '抛硬币', sub: '正反面各配一句，抛完照做', section: 'coinFlip', validationMethod: 0 },
      { key: 'dice', icon: 'more', label: '掷骰子', sub: '掷到几就做第几件事', section: 'diceRoll', mode: 'd6', validationMethod: 0 },
    ],
  },
  {
    title: '营销类',
    items: [
      { key: 'scan', icon: 'qr-scan', label: '扫码参与', sub: '扫完立刻回一条 —— 文字 / 语音 / 图片', section: 'scan', validationMethod: 4 },
    ],
  },
];

const MODIFIERS = [
  { key: 'timer', label: '限时挑战', icon: 'clock', modifier: true },
  { key: 'timeWindow', label: '时段限定', icon: 'clock', modifier: true },
  { key: 'leaderboard', label: '排行榜', icon: 'list', modifier: true },
  { key: 'multiplayer', label: '多人协作', icon: 'list', modifier: true, disabled: true, reason: '规则待定义' },
];
// 旧 key 仍供配置/试玩与存量绑定使用；选择器只列基础玩法。
const ALL = GROUPS.reduce((acc, group) => acc.concat(group.items), []).filter(item => item.key !== 'scan').concat([
  { key: 'blindTaste', section: 'blindTaste', label: '闭眼盲品', icon: 'check' },
  { key: 'dailySign', section: 'dailySign', label: '今日城市签', icon: 'edit' },
]);
const CATEGORIES = ['全部', '问答', '推理', '互动', '挑战'];
const form = (key, label, gameKey) => ({ key, label, gameKey });
const BASE_GAMES = [
  { key: 'choice', label: '选项问答', category: '问答', icon: 'check', forms: [form('text', '文字选项', 'qaPick'), form('image', '图片选项', 'pricePair'), form('taste', '闭眼盲品', 'blindTaste'), form('reveal', '到期揭晓', 'predict')] },
  { key: 'text', label: '文字问答', category: '问答', icon: 'edit', gameKey: 'qaText' },
  { key: 'photo', label: '拍照', category: '问答', icon: 'camera', forms: [form('checkin', '拍照打卡', 'qaShot'), form('review', '拍照审核', 'photoCheck'), form('card', '拍物成卡', 'objectCard')] },
  { key: 'random', label: '随机结果', category: '互动', icon: 'gift', forms: [form('coin', '抛硬币', 'coin'), form('dice', '掷骰子', 'dice'), form('cards', '抽卡', 'random')] },
  { key: 'check', label: 'D20', category: '挑战', icon: 'star', forms: [form('tier', '三档难度', 'check'), form('d20', 'D20 加值', 'd20')] },
  ...['sort', 'match', 'classify'].map(key => Object.assign({}, ALL.find(item => item.key === key), { category: '推理', gameKey: key })),
  ...['estimate', 'hidden', 'profile'].map(key => Object.assign({}, ALL.find(item => item.key === key), { category: '互动', gameKey: key })),
  { key: 'sign', label: '签名留存', category: '互动', icon: 'edit', forms: [form('note', '一句留言', 'note'), form('citySign', '今日城市签', 'dailySign')] },
  Object.assign({}, ALL.find(item => item.key === 'album'), { category: '互动', gameKey: 'album' }),
  { key: 'bingo', label: '九宫格', category: '互动', icon: 'filter', disabled: true, reason: '在主题里配置' },
  ...['steps', 'react', 'shake', 'quiet', 'compass', 'shout', 'countdown', 'stopwatch', 'typeIn'].map(key => Object.assign({}, ALL.find(item => item.key === key), { category: '挑战', gameKey: key })),
];
const PICKER_GROUPS = CATEGORIES.slice(1).map(title => ({ title, items: BASE_GAMES.filter(item => item.category === title) }));
function selection(key) {
  for (const base of BASE_GAMES) {
    const choice = (base.forms || []).find(item => item.gameKey === key);
    if (choice || base.gameKey === key) return { baseKey: base.key, formKey: choice ? choice.key : '', forms: base.forms || [] };
  }
  return { baseKey: '', formKey: '', forms: [] };
}

// 限时统一作为附加项；A 批已接通类型与既有类型，旧数据始终保留。
const TIMED_GAMES = ['qaText', 'qaPick', 'qaShot', 'branch', 'random', 'estimate', 'pricePair', 'hidden',
  'react', 'shake', 'quiet', 'compass', 'countdown', 'stopwatch',
  'react', 'shake', 'quiet', 'shout', 'countdown', 'stopwatch',
  'sort', 'match', 'classify', 'check', 'd20', 'coin', 'dice', 'blindTaste', 'typeIn'];

/** 这个玩法支不支持限时。没选玩法时返回 true(旧模板那条老链路照旧)。 */
function supportsTimer(key) {
  if (!key) return true;
  return TIMED_GAMES.indexOf(key) >= 0;
}

/* ★ 不走 playKit 通道的段:配置得出来,但服务端不把它投影进 playKit。
   check(R14 技能检定)是唯一一个 —— 它走 Encounter:PlayEncounterServiceImpl.allowedActions()
   在节点旅程块 check.enabled 为真时单独把 'check' 追加进 enc.actions,题面在 enc.check,
   判定走 /api/play/check/roll|reroll|settle。所以它不在 PLAYABLE_SECTIONS 里,
   pickPlayKit 也永远还不出它的 kit —— 那是对的,不是漏接线。
   ⚠️ 这张表是「豁免名单」,只许放确实走别的通道的段。往里加一个不走别的通道的段,
   就等于把「玩法静默消失」那条门禁对它关掉了。 */
const ENCOUNTER_SECTIONS = ['check']

/** 所有被玩法目录占用的段名(去重)。选一个玩法 = 关掉其余这些段。 */
const GAME_SECTIONS = ALL.map((item) => item.section).filter(Boolean)
  .filter((name, index, list) => list.indexOf(name) === index);

/** 同一段里靠 mode 分玩法的那些段(qa 三条 / photoCheck 两条)。
 *  applyToConfig 按这张表决定「换玩法时要不要动 mode」。 */
const MODE_SECTIONS = ALL.filter((item) => item.mode).map((item) => item.section)
  .filter((name, index, list) => list.indexOf(name) === index);

/** 必须能被 pickPlayKit 还出 kit 的段 = 目录里除掉走别的通道的那些。 */
// 相册插入为 dream 展示块，不创建待通关节点；预览仍复用 cy-playkit。
const PLAYKIT_SECTIONS = GAME_SECTIONS.filter((name) => name !== 'album' && ENCOUNTER_SECTIONS.indexOf(name) < 0);

function findGame(key) {
  if (!key) return null;
  for (let i = 0; i < ALL.length; i += 1) {
    if (ALL[i].key === key) return ALL[i];
  }
  return key === 'scan' ? GROUPS.flatMap(group => group.items).find(item => item.key === 'scan') : null;
}

/**
 * 从已有的 advanced 配置反推当前选中的玩法。
 * 编辑一份旧模板时要靠它把槽位填回去 —— 认不出来就会显示成「没选玩法」,
 * 而下面的配置还在,人会以为东西丢了。
 */
function detectGame(advanced) {
  if (!advanced || typeof advanced !== 'object') return '';
  for (let i = 0; i < ALL.length; i += 1) {
    const item = ALL[i];
    if (!item.section) continue;
    // R5 branch 是本站反馈，不能抢占主玩法回填；旧独立分支仍按原目录识别。
    if (item.section === 'branch' && advanced.outcomeRules?.version === 1 && ALL.some(row => row.section !== 'branch' && advanced[row.section]?.enabled)) continue;
    const section = advanced[item.section];
    if (!section || !section.enabled) continue;
    if (item.mode && (section.mode || (item.section === 'diceRoll' ? 'd6' : '')) !== item.mode) continue;
    return item.key;
  }
  return '';
}


/* 先看再选用的样例内容。
 * 商家还没配任何东西就点「预览」时,拿默认配置直接造屏会是一片空白 —— 玩法本身
 * 没问题,是没东西可显示,但看的人只会以为这个玩法是坏的。这里给每个玩法一句样例,
 * 只在试玩那一瞬间用,不写回配置。 */
const DEMO = {
  profile: { title: '旅人登记', lead: '留下你的名字，准备出发', avatar: { enabled: true, required: false },
    questions: [{ key: 'name', label: '怎么称呼你', kind: 'text', maxLength: 12, required: true },
      { key: 'destination', label: '想去哪里', kind: 'pick', required: true, options: [
        { key: 'sea', label: '海边', effects: [] }, { key: 'mountain', label: '山间', effects: [] }] }] },
  typeIn: { title: '输入通行口令', target: '今天也要出发', seconds: 20 },
  photoCheck: { title: '拍下沿途的风景', shotNote: '让街景完整出现在画面里', requirement: '画面包含街景' },
  note: { title: '留给下一位旅人', prompt: '写下此刻想说的话', presets: ['慢慢走', '前面有好风景'] },
  qaText: { title: '这座桥建于哪一年?', answerText: '1937' },
  qaPick: { title: '石碑上的图案象征什么?', options: [
    { id: 'opt_1', label: '丰收', fb: '对了，是麦穗。', correct: true },
    { id: 'opt_2', label: '远航', fb: '再看看穗子。', correct: false },
  ] },
  /* 拍照这一屏:题干在上,大字是 lead(见 advanced-game-preview 的 shotLead)。
     两个给的是不同的话 —— 一句说要干什么,一句说怎么拍。 */
  qaShot: { title: '在这棵树下拍一张', lead: '和这棵古树合个影，把整棵树都框进去' },
  branch: { steps: [{
    id: 'start', title: '门口', body: '木门虚掩着，里面飘出咖啡味。推门的话铃会响，绕到后巷还有一扇小门。',
    terminal: false, outcomeCode: 'COMPLETED', outcomeLabel: '完成节点',
    options: [
      { key: 'A', label: '推门进去', feedback: '铃响了一声，老板抬头看你。', next: 'in' },
      { key: 'B', label: '绕到后巷', feedback: '后巷堆着刚烘好的豆子。', next: 'alley' },
    ],
  }] },
  estimate: { title: '这罐豆子有多少颗?', unit: '颗', answer: 480, min: 0, max: 1000, tolerance: 50, reveal: '一共 480 颗，老板数了两遍。' },
  pricePair: { title: '哪一张是这家店的老招牌?', items: [
    { id: 'pic_1', name: '木牌', imageUrl: '', correct: true },
    { id: 'pic_2', name: '霓虹灯', imageUrl: '', correct: false },
    { id: 'pic_3', name: '手写纸板', imageUrl: '', correct: false },
  ] },
  hidden: { title: '找到那只猫', hint: '它在窗台附近' },
  predict: { question: '明天中午哪种豆子卖得更多?', options: [{ key: 'A', label: '耶加雪菲' }, { key: 'B', label: '曼特宁' }] },
  random: { deckName: '今日线索卡', items: [
    { id: 'item_1', label: '线索卡', weight: 1, content: '去吧台问一句今天的手冲是什么。' },
    { id: 'item_2', label: '任务卡', weight: 1, content: '找到店里最旧的那样东西，拍下来。' },
    { id: 'item_3', label: '彩蛋卡', weight: 1, content: '跟店员说「今天的天气」,看他回你什么。' },
  ] },
  steps: { eyebrow: '低碳行动 · 今日步数', goal: 6000 },
  // 2026-10-23 补:这几个玩法试玩时一直是空行,看不出长什么样
  sort: { prompt: '按开业年份从早到晚排', items: [
    { id: 'item_1', label: '老城茶馆 · 1912' }, { id: 'item_2', label: '巷口书店 · 1958' }, { id: 'item_3', label: '街角花店 · 2003' }] },
  match: { prompt: '把店和它的招牌连起来',
    left: [{ id: 'left_1', label: '老张咖啡' }, { id: 'left_2', label: '深夜面馆' }],
    right: [{ id: 'right_1', label: '手冲耶加' }, { id: 'right_2', label: '红油抄手' }] },
  classify: { prompt: '这些东西在哪家店能买到?',
    bins: [{ id: 'bin_1', label: '茶馆' }, { id: 'bin_2', label: '书店' }],
    items: [{ id: 'item_1', label: '冻顶乌龙' }, { id: 'item_2', label: '旧版地图' }] },
  blindTaste: { title: '闭上眼，尝一口，猜猜是哪一种', answerKey: 'B',
    options: [{ key: 'A', label: '桂花乌龙' }, { key: 'B', label: '冻顶乌龙' }, { key: 'C', label: '茉莉绿茶' }] },
  dailySign: { poems: [['今日宜慢行', '巷口有风，茶正好']] },
  check: { skill: '观察', successText: '锁舌“咔”地弹开，地窖里有股陈年茶香。', failText: '锁纹丝不动。去问问掌柜，钥匙也许在柜台下。' },
  react: { kicker: '屏幕一变色就点' },
  shake: { kicker: '倾斜手机，让球撞够边缘' },
  quiet: { kicker: '安静挑战', sub: '一直安静到时间到' },
  compass: { kicker: '把手机转向钟楼', bearing: 135, hint: '它比你想的更靠右' },
  shout: { kicker: '一起喊,让这儿听见' },
  countdown: { kicker: '一钟油', doneText: '时间到。' },
  stopwatch: { kicker: '盲停在 10 秒' },
  coin: { kicker: '抛完照做', heads: { label: '正面', action: '这杯店家请' }, tails: { label: '反面', action: '你请下一杯' } },
  dice: { kicker: '掷到几就做第几件事', faces: ['和店员说句今天的天气', '换个位置坐', '点一杯没喝过的', '拍一张门口', '把杯子摆正', '写一句留言'] },
  d20: { kicker: '观察暗门', successText: '你发现了门后的线索', failText: '暂时没有发现，沿街继续寻找' },
  scan: { reply: '欢迎来到这家店，今天的手冲是耶加雪菲。' },
};

/**
 * 返回「假如选了它」的那份配置(新对象),用于先看再选的试玩。
 * 不改传进来的配置 —— 看完不该在真配置上留下痕迹。
 */
function dropStaleTimer(next) {
  // 旧 timer / variants 只读保留，打开与切换玩法不能静默删配置。
  return next;
}

function applyToConfig(advanced, key, options) {
  const game = findGame(key);
  if (!game || !advanced || typeof advanced !== 'object') return null;
  const next = JSON.parse(JSON.stringify(advanced));
  if (key === 'scan') { next.scan = Object.assign({}, next.scan, { enabled: true }); return next; }
  GAME_SECTIONS.forEach((name) => {
    if (!next[name] || typeof next[name] !== 'object') return;
    if (name === 'branch' && game.section !== 'branch' && next.outcomeRules?.version === 1) return;
    next[name].enabled = Boolean(game.section && name === game.section);
  });
  /* mode 判别(qa 三条 / photoCheck 两条)必须整段重来:选中带 mode 的玩法就写进去,
     选中同段里**不带** mode 的那条(拍照审核)就把上一手留下的 mode 删掉。
     留着 'CARD' 的话,detectGame 会把人刚改的选择又认回成藏品卡 —— 改完保存,玩法没换。
     qa 那批三条都带 mode,行为与今天一致(永远重写)。 */
  if (MODE_SECTIONS.indexOf(game.section) >= 0 && next[game.section]) {
    if (game.mode) next[game.section].mode = game.mode;
    else delete next[game.section].mode;
  }
  /* 呈现方式跟着作者走:2026-10-30 起任何玩法都能内嵌,换玩法时作者选的 inline / fullscreen 原样保留。 */
  /* 一个玩法段都没启用(如选了主题级九宫格)时 present 没有意义,与 normalize 同口径清掉。 */
  if (!GAME_SECTIONS.some((name) => next[name] && next[name].enabled)) delete next.present;
  dropStaleTimer(next, key);
  /* 只有试玩用的那份才铺样例:空配置造出来是一片空白屏,看的人分不清「没配」和「坏了」。
     ⚠️ 真的选中一个玩法时不能铺 —— 那会把样例当成商家自己填的存进去。
     判据是「这个字段还是默认值吗」,商家动过的一个字都不覆盖。 */
  if (!options || !options.demo) return next;
  const demo = DEMO[key];
  const target = next[game.section];
  const pristine = advancedGameConfig.defaultConfig()[game.section] || {};
  if (demo && target) {
    Object.keys(demo).forEach((field) => {
      if (JSON.stringify(target[field]) !== JSON.stringify(pristine[field])) return;
      target[field] = JSON.parse(JSON.stringify(demo[field]));
    });
  }
  return next;
}

module.exports = { BASE_GAMES, CATEGORIES, selection, GROUPS, MODIFIERS, PICKER_GROUPS, ALL, GAME_SECTIONS, ENCOUNTER_SECTIONS, PLAYKIT_SECTIONS, TIMED_GAMES, supportsTimer, findGame, detectGame, applyToConfig, dropStaleTimer };
