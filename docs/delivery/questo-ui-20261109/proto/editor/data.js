// 原型数据:四种画面风格(与 pages/publish/utils/publish/theme-look.js 同口径)、配色、玩法目录、示例章节。
(function () {
  var STYLES = [
    { value: 'STARDUST', label: '星尘', skin: 'core', sw: 'look-sw--stardust', params: [
      { key: 'density', label: '浮尘', options: [['SPARSE', '稀'], ['NORMAL', '中'], ['DENSE', '密']], def: 'NORMAL' },
      { key: 'drift', label: '飘动', options: [['SLOW', '慢'], ['NORMAL', '中'], ['FAST', '快']], def: 'SLOW' },
      { key: 'tone', label: '光色', options: [['WHITE', '白'], ['WARM', '暖'], ['COOL', '冷']], def: 'WHITE' }] },
    { value: 'PAPER', label: '纸本', skin: 'paper', sw: 'look-sw--paper', params: [
      { key: 'paper', label: '纸张', options: [['KRAFT', '牛皮纸'], ['XUAN', '宣纸'], ['CARD', '深色卡纸']], def: 'KRAFT' },
      { key: 'grain', label: '纸纹', options: [['LIGHT', '轻'], ['MEDIUM', '中'], ['HEAVY', '重']], def: 'MEDIUM' },
      { key: 'stamp', label: '印章', options: [['VERIFIED', '已核实'], ['VISITED', '到此一游'], ['SEAL', '朱文方印']], def: 'VERIFIED' }] },
    { value: 'SURVIVAL', label: '末世', skin: 'frost', sw: 'look-sw--survival', params: [
      { key: 'climate', label: '天气', options: [['CLEAR', '晴'], ['RAIN', '雨'], ['STORM', '雷暴'], ['SAND', '沙尘暴']], def: 'RAIN' },
      { key: 'volatility', label: '变化', options: [['CALM', '平稳'], ['SHIFTING', '多变'], ['VIOLENT', '剧烈']], def: 'SHIFTING' },
      { key: 'edge', label: '屏幕边缘', options: [['HP', '跟随体力'], ['FIXED', '固定'], ['OFF', '关闭']], def: 'HP' }] },
    { value: 'CYBER', label: '夜城', skin: 'noir', sw: 'look-sw--cyber', params: [
      { key: 'neon', label: '霓虹', options: [['YELLOW_CYAN', '黄青'], ['CYAN_PINK', '青粉'], ['RED_CYAN', '红青']], def: 'YELLOW_CYAN' },
      { key: 'current', label: '电流', options: [['LOW', '弱'], ['MID', '中'], ['HIGH', '强']], def: 'MID' },
      { key: 'glitch', label: '故障', options: [['TRANSITION', '只在转场'], ['OFF', '关闭']], def: 'TRANSITION' }] }
  ];
  var PALETTES = [['DEFAULT', '黑'], ['BLUE', '蓝'], ['RED', '红'], ['YELLOW', '黄'], ['WHITE', '白']];
  var TIMES = [['DAWN', '清晨'], ['NOON', '正午'], ['DUSK', '日落'], ['NIGHT', '夜晚']];
  var FONTS = [['HAND', '手写'], ['SONG', '宋体'], ['KAI', '楷体'], ['HEI', '黑体'], ['BOLD', '粗斜']];
  var TEXT_FX = [['AUTO', '跟随风格'], ['rise', '浮现'], ['type', '打字机', 1], ['ink', '墨晕', 1], ['gather', '聚星', 1], ['glow', '呼吸光'], ['wipe', '擦出'],
    ['drop', '落定', 1], ['wind', '风吹', 1], ['signal', '信号', 1], ['fog', '雾散'], ['neon', '霓虹点亮'], ['scan', '扫描'], ['decode', '解码', 1]];
  var STYLE_TEXT = { STARDUST: 'gather', PAPER: 'ink', SURVIVAL: 'wind', CYBER: 'neon' };
  // 玩家端配色(原型 v7 PAL,与 look-sheet.wxss 的色板同值)
  var PAL = {
    core: { DEFAULT: { bg: '#0A0A0A', ink: '#F2F2F0', mute: '#9AA0AA', acc: '#E9E2D0', acc2: '#CFE0FF' }, BLUE: { bg: '#0E1A33', ink: '#F5F8FF', mute: '#A9BBE0', acc: '#CFE0FF', acc2: '#F2D58A' },
      RED: { bg: '#2A0E12', ink: '#FFF4F5', mute: '#E0AAB1', acc: '#FFB8AC', acc2: '#F2D58A' }, YELLOW: { bg: '#2A2208', ink: '#FFFBEF', mute: '#DFCD9A', acc: '#F2D58A', acc2: '#CFE0FF' }, WHITE: { bg: '#F5F6F8', ink: '#111318', mute: '#5B6270', acc: '#111318', acc2: '#5B6270' } },
    paper: { DEFAULT: { bg: '#1D1B18', ink: '#ECE6D8', mute: '#9A927F', acc: '#D0503C', acc2: '#E8C66A', card: '#F6F1E4', cink: '#2B241A' }, BLUE: { bg: '#1C2A44', ink: '#EEF0EA', mute: '#A2ADBF', acc: '#E8C66A', acc2: '#D0503C', card: '#F3F0E8', cink: '#1E2538' },
      RED: { bg: '#3A1A17', ink: '#F4E6DC', mute: '#C9A596', acc: '#E8C66A', acc2: '#D0503C', card: '#F6EEE4', cink: '#2B1A17' }, YELLOW: { bg: '#E6DCC4', ink: '#2B241A', mute: '#7D715C', acc: '#A3281D', acc2: '#7D715C', card: '#F6F1E4', cink: '#2B241A' }, WHITE: { bg: '#F4F1EA', ink: '#1E1C19', mute: '#7A756C', acc: '#B0302A', acc2: '#7A756C', card: '#FFFDF8', cink: '#1E1C19' } },
    frost: { DEFAULT: { bg: '#0E1012', ink: '#E4E7E9', mute: '#8A9196', acc: '#FF8A3D', acc2: '#8A9196' }, BLUE: { bg: '#0B1626', ink: '#EAF3FB', mute: '#8FA6BD', acc: '#FFB35C', acc2: '#8FA6BD' },
      RED: { bg: '#1C0A0A', ink: '#F3E3E0', mute: '#B08A86', acc: '#7FE6C4', acc2: '#B08A86' }, YELLOW: { bg: '#2A1D0C', ink: '#FFF2DC', mute: '#CDB38A', acc: '#8FD3FF', acc2: '#CDB38A' }, WHITE: { bg: '#E3E9EE', ink: '#14202B', mute: '#5D6D7B', acc: '#D4561E', acc2: '#5D6D7B' } },
    noir: { DEFAULT: { bg: '#0B0B0F', ink: '#ECEBE4', mute: '#8A8C96', acc: '#F3E600', acc2: '#24E0E8' }, BLUE: { bg: '#06101C', ink: '#E2F1FF', mute: '#7E93AC', acc: '#2DE2E6', acc2: '#FF3B6B' },
      RED: { bg: '#12060A', ink: '#FFE8EA', mute: '#A88088', acc: '#FF2E4C', acc2: '#2DE2E6' }, YELLOW: { bg: '#100E04', ink: '#FFF6CC', mute: '#A99E6E', acc: '#FCEE0A', acc2: '#FF2E4C' }, WHITE: { bg: '#E6E7EA', ink: '#101216', mute: '#5E636E', acc: '#E0002B', acc2: '#008C99' } }
  };
  var NEON = { YELLOW_CYAN: ['#F3E600', '#24E0E8'], CYAN_PINK: ['#2DE2E6', '#FF3B6B'], RED_CYAN: ['#FF2E4C', '#2DE2E6'] };

  // 画面风格状态(全局唯一真源:画面风格面板改它,所有预览读它)
  var LOOK = { style: 'STARDUST', params: { density: 'NORMAL', drift: 'SLOW', tone: 'WHITE' }, palette: 'DEFAULT', font: 'HAND', textFx: 'AUTO', times: ['', 'DUSK', ''] };
  function styleOf(v) { for (var i = 0; i < STYLES.length; i++) if (STYLES[i].value === v) return STYLES[i]; return STYLES[0]; }
  function setStyle(v) {
    var s = styleOf(v); LOOK.style = s.value; LOOK.params = {};
    s.params.forEach(function (p) { LOOK.params[p.key] = p.def; });
  }
  function colors() {
    var s = styleOf(LOOK.style), c = Object.assign({}, PAL[s.skin][LOOK.palette] || PAL[s.skin].DEFAULT);
    if (s.value === 'CYBER' && LOOK.palette === 'DEFAULT') { var n = NEON[LOOK.params.neon] || NEON.YELLOW_CYAN; c.acc = n[0]; c.acc2 = n[1]; }
    c.skin = s.skin; c.dark = s.skin === 'paper' ? (LOOK.palette !== 'YELLOW' && LOOK.palette !== 'WHITE') : (LOOK.palette !== 'WHITE');
    return c;
  }
  function textFx() { return LOOK.textFx === 'AUTO' ? STYLE_TEXT[LOOK.style] : LOOK.textFx; }
  function fxChars(fx) { for (var i = 0; i < TEXT_FX.length; i++) if (TEXT_FX[i][0] === fx) return !!TEXT_FX[i][2]; return false; }

  // 玩法目录(公共玩法库)+ 我的模板
  var CATS = ['全部', '问答', '互动', '推理', '挑战', '决定'];
  var GAMES = [
    { key: 'qaPick', label: '选项问答', sub: '2 至 4 个选项里选一个', cat: '问答', color: '#FC3F3F', icon: 'check-circle', mode: '嵌入' },
    { key: 'qaText', label: '文字问答', sub: '出一道题,玩家打字作答', cat: '问答', color: '#FF7A45', icon: 'pencil', mode: '嵌入' },
    { key: 'hangman', label: '逐字猜词', sub: '一格一格把词猜出来', cat: '问答', color: '#3FA9D2', icon: 'grid', mode: '嵌入' },
    { key: 'qaShot', label: '拍照打卡', sub: '给一句提示词,玩家拍一张交上来', cat: '问答', color: '#F06292', icon: 'camera', mode: '整屏' },
    { key: 'estimate', label: '猜数字', sub: '猜一个数,按接近程度给分', cat: '互动', color: '#F5A200', icon: 'hash', mode: '嵌入' },
    { key: 'hidden', label: '找东西', sub: '在图上点中藏起来的目标', cat: '互动', color: '#12B886', icon: 'search', mode: '整屏' },
    { key: 'draw', label: '盲盒抽卡', sub: '一副卡池抽一张,翻开就是任务', cat: '互动', color: '#00BDFC', icon: 'card', mode: '整屏' },
    { key: 'bingo', label: '九宫格', sub: '连成一条线就算完成', cat: '互动', color: '#3FD2D2', icon: 'grid', mode: '整屏' },
    { key: 'sort', label: '排序', sub: '把几条按正确顺序排好', cat: '推理', color: '#5B8CFF', icon: 'sort', mode: '嵌入' },
    { key: 'match', label: '连线', sub: '两列里一一配对', cat: '推理', color: '#3FA9D2', icon: 'link', mode: '嵌入' },
    { key: 'classify', label: '分类', sub: '每条归到对的那一类', cat: '推理', color: '#4C6EF5', icon: 'grid', mode: '嵌入' },
    { key: 'stop', label: '精准停表', sub: '看准秒数按停,越准越好', cat: '挑战', color: '#22C55E', icon: 'clock', mode: '整屏' },
    { key: 'd20', label: 'D20', sub: '掷一个二十面骰,点数够难度就成功', cat: '挑战', color: '#7C5CFF', icon: 'd20', mode: '整屏' },
    { key: 'shake', label: '摇够数', sub: '规定时间里摇够次数', cat: '挑战', color: '#A78BFA', icon: 'wave', mode: '整屏' },
    { key: 'coin', label: '抛硬币', sub: '正反面各配一句,抛完照做', cat: '决定', color: '#D8A05A', icon: 'coin', mode: '整屏' },
    { key: 'dice', label: '掷骰子', sub: '掷到几就做第几件事', cat: '决定', color: '#8B5CF6', icon: 'dots', mode: '嵌入' }
  ];
  // 玩法 = 配置好的模板(公共模板 / 我的模板),里面的具体小游戏不单独列
  var TPL_PUB = [
    { key: 't1', title: '老城茶馆的最后一道题', game: 'qaPick', cat: '问答', place: '茶馆 · 1 个地点', photos: 2, by: '城瘾官方', status: '公共' },
    { key: 't2', title: '邮筒上的暗号', game: 'hangman', cat: '问答', place: '邮局 · 1 个地点', photos: 1, by: '城瘾官方', status: '公共' },
    { key: 't3', title: '广场合影打卡', game: 'qaShot', cat: '问答', place: '广场 · 1 个地点', photos: 1, by: '城瘾官方', status: '公共' },
    { key: 't4', title: '站台上的数字', game: 'estimate', cat: '互动', place: '地铁站 · 1 个地点', photos: 1, by: '城瘾官方', status: '公共' },
    { key: 't5', title: '橱窗里藏着什么', game: 'hidden', cat: '互动', place: '书店 · 1 个地点', photos: 3, by: '城瘾官方', status: '公共' },
    { key: 't6', title: '石库门年代排序', game: 'sort', cat: '推理', place: '弄堂 · 1 个地点', photos: 2, by: '城瘾官方', status: '公共' },
    { key: 't7', title: '钟楼下按停三十秒', game: 'stop', cat: '挑战', place: '钟楼 · 1 个地点', photos: 1, by: '城瘾官方', status: '公共' },
    { key: 't8', title: '十字路口抛一枚硬币', game: 'coin', cat: '决定', place: '路口 · 1 个地点', photos: 0, by: '城瘾官方', status: '公共' }
  ];
  var TPL_MINE = [
    { key: 'm1', title: '雨后咖啡馆 · 吧台问答', game: 'qaPick', cat: '问答', place: '咖啡馆 · 1 个地点', photos: 2, by: '我', status: '已发布' },
    { key: 'm2', title: '旧书店的书脊', game: 'match', cat: '推理', place: '书店 · 1 个地点', photos: 1, by: '我', status: '草稿' }
  ];
  function gameOf(k) { for (var i = 0; i < GAMES.length; i++) if (GAMES[i].key === k) return GAMES[i]; return GAMES[0]; }
  function tplOf(k) { var a = TPL_PUB.concat(TPL_MINE); for (var i = 0; i < a.length; i++) if (a[i].key === k) return a[i]; return null; }
  // 图片组版式:一组图作为一个整体移动
  var IMG_LAYOUTS = [[1, '单图'], [2, '双图'], [3, '三图'], [4, '四图']];
  var POIS = [['商务印书馆旧址', '福州路 390 号'], ['上海邮政博物馆', '北苏州路 250 号'], ['人民广场', '人民大道 120 号'], ['外滩源', '中山东一路 33 号']];

  var P = {
    'check-circle': '<circle cx="12" cy="12" r="8.5"/><path d="M8.5 12.2l2.4 2.4 4.8-4.8"/>', pencil: '<path d="M5 19l1-4L16 5l3 3L9 18z"/>',
    camera: '<rect x="4" y="7" width="16" height="12" rx="2.5"/><circle cx="12" cy="13" r="3.2"/><path d="M9 7l1.5-2h3L15 7"/>', hash: '<path d="M9 4L7 20M17 4l-2 16M4 9h16M3 15h16"/>',
    search: '<circle cx="11" cy="11" r="6"/><path d="M16 16l4 4"/>', card: '<rect x="5" y="3.5" width="14" height="17" rx="2.5"/><path d="M9 9h6M9 13h4"/>',
    grid: '<rect x="4" y="4" width="7" height="7" rx="1.5"/><rect x="13" y="4" width="7" height="7" rx="1.5"/><rect x="4" y="13" width="7" height="7" rx="1.5"/><rect x="13" y="13" width="7" height="7" rx="1.5"/>',
    sort: '<path d="M6 6v12M3.5 15.5L6 18l2.5-2.5M11 7h9M11 12h7M11 17h5"/>', link: '<path d="M10 14a4 4 0 005.7 0l2.5-2.5a4 4 0 00-5.7-5.7L11 7.3M14 10a4 4 0 00-5.7 0l-2.5 2.5a4 4 0 005.7 5.7l1.5-1.5"/>',
    clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>', d20: '<path d="M12 3l8 4.5v9L12 21l-8-4.5v-9z"/><path d="M12 3l-4 7.5h8zM8 10.5L4 16.5M16 10.5l4 6M8 10.5L12 21l4-10.5"/>',
    wave: '<path d="M3 12c2-4 4-4 6 0s4 4 6 0 4-4 6 0"/>', coin: '<circle cx="12" cy="12" r="8"/><path d="M9.5 9.5h4a1.5 1.5 0 010 3h-3a1.5 1.5 0 000 3h4M12 7.5v1.8M12 15.2V17"/>',
    dots: '<rect x="4.5" y="4.5" width="15" height="15" rx="3"/><circle cx="9" cy="9" r="1.1" fill="currentColor"/><circle cx="15" cy="15" r="1.1" fill="currentColor"/><circle cx="12" cy="12" r="1.1" fill="currentColor"/>',
    plus: '<path d="M12 5v14M5 12h14"/>', image: '<rect x="4" y="5" width="16" height="14" rx="2.5"/><circle cx="9" cy="10" r="1.6"/><path d="M5 17l4.5-4.5 3.5 3.5 2.5-2.5L19 17"/>',
    audio: '<path d="M9 18V6l10-2v12"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="16.5" cy="16" r="2.5"/>', play: '<path d="M8 5.5v13l10.5-6.5z"/>',
    text: '<path d="M5 6h14M5 12h14M5 18h9"/>', hr: '<path d="M4 12h16"/>', pin: '<path d="M12 21s6-5.5 6-11a6 6 0 10-12 0c0 5.5 6 11 6 11z"/><circle cx="12" cy="10" r="2.2"/>',
    branch: '<path d="M6 4v8a4 4 0 004 4h8M18 12l3 4-3 4M6 4a2 2 0 110 .01"/>', game: '<rect x="3" y="7" width="18" height="11" rx="4"/><path d="M8 11v3M6.5 12.5h3M15 11.5h.01M17.5 13.5h.01"/>',
    grip: '<circle cx="9" cy="6" r="1.4" fill="currentColor"/><circle cx="15" cy="6" r="1.4" fill="currentColor"/><circle cx="9" cy="12" r="1.4" fill="currentColor"/><circle cx="15" cy="12" r="1.4" fill="currentColor"/><circle cx="9" cy="18" r="1.4" fill="currentColor"/><circle cx="15" cy="18" r="1.4" fill="currentColor"/>',
    close: '<path d="M6 6l12 12M18 6L6 18"/>', back: '<path d="M15 5l-7 7 7 7"/>', check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>', down: '<path d="M8 10l4 4 4-4"/>', right: '<path d="M10 7l5 5-5 5"/>',
    undo: '<path d="M9 14L4 9l5-5"/><path d="M4 9h11a5 5 0 010 10h-3"/>', medal: '<circle cx="12" cy="14" r="5"/><path d="M9 9.5L6.5 4h3.5L12 8l2-4h3.5L15 9.5"/>', more: '<circle cx="5.5" cy="12" r="1.6" fill="currentColor"/><circle cx="12" cy="12" r="1.6" fill="currentColor"/><circle cx="18.5" cy="12" r="1.6" fill="currentColor"/>',
    eye: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/>', flag: '<path d="M5 21V4h11l-2 4 2 4H5"/>'
  };
  function ic(n, s, w) { return '<svg class="ik" width="' + (s || 18) + '" height="' + (s || 18) + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="' + (w || 1.8) + '" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + (P[n] || '') + '</svg>'; }

  // 示例主题 / 章节(虚构内容)
  var THEME = { title: '雨后的咖啡馆', sub: '一段关于旧书店与一封没寄出的信的城市漫游', chapters: 3, minutes: 40 };
  var CHAPTERS = [
    { id: 'c1', name: '雨停之后', cover: 'assets/covers/old-movie.jpg', open: '雨刚停,石板路上还有积水。你收到一条没有署名的消息:「咖啡馆见,带上那本书。」', end: '门铃响了一声,她没有回头。桌上多了一张写着地址的纸条。', reward: '线索 · 纸条上的地址', next: '下一章 · 旧书店', blocks: [
      { id: 'b1', type: 'p', text: '雨停了,石板路上还有积水。她推开咖啡馆的门,铃铛响了一声。吧台后面没有人,只有一杯还冒着热气的咖啡。' },
      { id: 'b2', type: 'img', layout: 1, imgs: ['assets/covers/midlife-day.jpg'], cap: '吧台上的那杯咖啡' },
      { id: 'b3', type: 'p', text: '杯底压着一张纸条。字迹很潦草,像是匆忙写下的。你认得这个笔迹。纸条背面还有半枚邮戳。' },
      { id: 'b4', type: 'node', tpl: 'm1', name: '吧台问答', place: ['雨后咖啡馆', '福州路 388 号'], photos: ['assets/covers/flavor-tour.jpg'] },
      { id: 'b5', type: 'p', text: '窗外又开始下雨。她把纸条折好放进口袋,决定去邮局看看。' },
      { id: 'b6', type: 'img', layout: 2, imgs: ['assets/covers/heat-escape.jpg', 'assets/covers/wildlife-observation.jpg'], cap: '' },
      { id: 'b7', type: 'branch', q: '纸条上的地址,先去哪一处?', opts: [{ t: '直接去邮局', to: '继续本章' }, { t: '先回旧书店问老板', to: '跳到第 2 章' }] },
      { id: 'b8', type: 'audio', name: '雨夜街巷.m4a', dur: '0:42' }
    ] },
    { id: 'c2', name: '旧书店', cover: 'assets/covers/prebuilt-life.jpg', open: '', end: '', reward: '', next: '', blocks: [] },
    { id: 'c3', name: '没寄出的信', cover: 'assets/covers/time-pollution.jpg', open: '', end: '', reward: '', next: '', blocks: [] }
  ];

  window.D = { STYLES: STYLES, PALETTES: PALETTES, TIMES: TIMES, FONTS: FONTS, TEXT_FX: TEXT_FX, PAL: PAL, LOOK: LOOK, styleOf: styleOf, setStyle: setStyle, colors: colors, textFx: textFx, fxChars: fxChars,
    CATS: CATS, GAMES: GAMES, TPL_PUB: TPL_PUB, TPL_MINE: TPL_MINE, gameOf: gameOf, tplOf: tplOf, IMG_LAYOUTS: IMG_LAYOUTS, POIS: POIS, ic: ic, THEME: THEME, CHAPTERS: CHAPTERS };
})();
