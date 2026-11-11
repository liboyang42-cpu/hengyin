// 全部屏:A 图标三风格 · B 章节开场(波点)· C 通行证钥匙 · D 玻璃研究 · E 落地说明
(function () {
  var add = H.add, notes = H.notes, top = H.top, gb = H.gb, foot = H.foot, pop = H.pop, SVG = H.SVG, esc = H.esc;
  var LIST = IB.list, NM = IB.names;
  function g3(name, sz, cls) { return '<span class="g3' + (cls ? ' ' + cls : '') + '" data-icon="' + name + '" style="width:' + sz + 'px;height:' + sz + 'px"></span>'; }
  function cell(style, name, sz, i) { return style === 'A' ? g3(name, sz) : style === 'B' ? IB.img(name, { size: sz, d: i * 60 }) : DOTS.img(name, { size: sz, d: i * 60, cell: sz < 40 ? 10 : 8 }); }
  var STYLE_T = { A: '3D 玻璃', B: '精致插画', C: '波点' };

  // ===== A-1 · 参考 =====
  var REFS = [
    ['r1-kita-medal.jpg', 'Kita Studio · Gold Medal Animation', 'https://dribbble.com/shots/22893339-Gold-Medal-Animation', '金章 + 紫绶带 + 星点:两三种颜色、同色系深浅做体积,星点只在落定时出现。'],
    ['r2-kita-crown.jpg', 'Kita Studio · Crown Gold Medal Animation', 'https://dribbble.com/shots/22908463-Crown-Gold-Medal-Animation', '同一套里换主体不换语言:描边粗细、圆角、阴影方向全部一致。'],
    ['r3-kita-school.jpg', 'Kita Studio · Back To School Icon Exploration', 'https://dribbble.com/shots/20823183-Back-To-School-Icon-Exploration', '一套十二枚的配色纪律:共用调色盘,每枚只取两三色,黑描边做骨架。'],
    ['r9-kita-camera.jpg', 'Kita Studio · Camera Icon Illustrations', 'https://dribbble.com/shots/20815608-Camera-Icon-Illustrations', '同一物件三种变体仍像一家人:靠统一的高光、阴影和描边。'],
    ['r4-lordicon-lineal.jpg', 'Lordicon · Badge Ribbon Star(lineal)', 'https://lordicon.com/icons/wired/lineal/434-prize-padge-ribbon', '动作拆成主 + 次级:章体先弹,绶带慢半拍甩;整段不到一秒。'],
    ['r5-shield-achievement.jpg', 'Dribbble · 成就盾(Ian Costello W. 的 Motion 收藏)', 'https://dribbble.com/iancw/collections/1894458-Motion', '奖励时刻的节奏:盾翻面一次、星芒只闪一下,其余时间安静。'],
    ['r6-sticker-set.jpg', 'Dribbble · 贴纸风图标组(同一收藏)', 'https://dribbble.com/iancw/collections/1894458-Motion', '粗黑描边 + 高饱和两三色 + 轻微蹦跳,和你空态插图最接近的一路。'],
    ['r7-rank-ribbons.jpg', 'Dribbble · Rank Ribbons(Inspiration Rewards 收藏)', 'https://dribbble.com/mynicename/collections/4489871-Inspiration-Rewards', '等级勋章系列:同一结构换色相区分等级,深色底上也站得住。'],
    ['r8-duolingo-campduo.jpg', 'Duolingo · Camp Duo Logo', 'https://dribbble.com/shots/14516014-Duolingo-Camp-Duo-Logo', '圆章 + 内环文字 + 中心物件:章节印、勋章可借这个骨架。'],
    ['r10-morphon.jpg', 'JAVIER @DSpot · Morphon Icons(Rive)', 'https://contra.com/p/RFOe7eE2-morphon-icons', '三色软体 + 网格构图:每枚由 2–3 个色块构成,动起来是色块各自位移再合体。'],
  ];
  var refH = top('参考', { back: false, cls: 'gm-top--bare' }) +
    '<div class="gm-body gm-body--nohud refv"><div class="sec">取用的四条</div><div class="gcard refrule" data-mk="1"><div><b>1</b>共用一个小调色盘,每枚只取 2–3 色,黑白做骨架。</div><div><b>2</b>体积靠同色系深一档 + 一道白高光 + 脚下软影,不用灰。</div><div><b>3</b>动作分主 + 次级:主体先动,挂件慢半拍跟;闪光只在「完成」那一下。</div><div><b>4</b>同一套的描边、圆角、高光方向、阴影方向全部一致。</div></div>' +
    '<div class="sec">参考帧 · 本地保存于 proto-icons-work/refs/</div>' + REFS.map(function (r, i) { return '<div class="refi"' + (i === 0 ? ' data-mk="2"' : '') + '><img src="assets/refs/' + r[0] + '"><div class="refi__t">' + r[1] + '</div><div class="refi__d">' + r[3] + '</div><a class="refi__l" href="' + r[2] + '" target="_blank" rel="noopener">' + r[2].replace(/^https?:\/\//, '') + '</a></div>'; }).join('') + '<div style="height:60px"></div></div>';
  add('ref', '参考 · 插画与动效', 'A · 图标三风格', 'cyp gm icx', refH, '', notes([
    [1, '从参考里提炼出来、这一轮 B 风格照做的四条。'],
    [2, '十组参考帧(GIF 取四帧并排),每组下面写了取用的点和来源链接;只借结构与节奏,不借任何形象、配色原样或文案。']]));

  // ===== A0 · 三风格对照 =====
  var cmp = top('图标 · 三种风格', { back: false, cls: 'gm-top--bare', right: SVG.repeat, rightAttr: 'data-act="replay"' }) +
    '<div class="gm-body gm-body--nohud"><div class="cmp" data-mk="1"><div class="cmp__h"><span></span><b>A · 3D 玻璃</b><b>B · 精致插画</b><b>C · 波点</b></div>' +
    LIST.map(function (k, i) { return '<div class="cmp__r"><span>' + NM[k] + '</span>' + cell('A', k, 56, i) + cell('B', k, 56, i) + cell('C', k, 56, i) + '</div>'; }).join('') + '</div>' +
    '<div class="sec">列表尺寸 · 24px</div><div class="lrow" data-mk="2">' + ['A', 'B', 'C'].map(function (s) { return '<div class="lrow__g"><b>' + STYLE_T[s] + '</b>' + ['clue', 'key', 'points', 'team'].map(function (k) { return '<div class="li">' + cell(s, k, 24) + '<span>' + NM[k] + '</span><i>' + (k === 'points' ? '+120' : k === 'team' ? '4 人' : '') + '</i></div>'; }).join('') + '</div>'; }).join('') + '</div>' +
    '<div class="sec">同一枚放进主题弹窗</div><div class="row3" data-mk="3">' + ['A', 'B', 'C'].map(function (s) { return gb(STYLE_T[s], { kind: 'ghost', pop: 'pop-' + s, data: 'style="flex:1;height:44px;font-size:14px"' }); }).join('') + '</div><div style="height:60px"></div></div>' +
    ['A', 'B', 'C'].map(function (s) { return pop('pop-' + s, { title: '找到一条线索', center: true, body: '<div class="popic">' + cell(s, 'clue', 112) + '</div><div class="gp">邮政大楼门口的信箱里,有一封没贴邮票的信。</div>', foot: gb('收进背包', { kind: 'primary', close: true }) }); }).join('');
  add('ic-compare', '三种风格对照', 'A · 图标三风格', 'cyp gm icx', cmp, 'icons-b.js · dots.js · glass3d.js(全部原创)', notes([
    [1, '同十枚图标(线索 / 勋章 / 钥匙 / 成功 / 失败 / 调查中 / 队伍 / 通行证 / 积分 / 章节印)三种风格并排:A 是 Three.js 实时 3D 玻璃(白玻璃外壳 + 柠檬绿 / 深灰内芯,程序影棚反光),B 是在你空态插图语言上加体积阴影和高光的插画,C 是按外观主色拼出来的点阵。点右上角重播进场。'],
    [2, '列表 24px:A 是同一场景缩小渲染(落地会换成序列帧或静态 WebP),B 直接缩,C 换大一点的点距。'],
    [3, '三个按钮分别把同一枚「线索」放进当前外观的主题弹窗;切顶栏外观、配色再看一遍。']]));

  // ===== A1 · 3D 玻璃 =====
  var a1 = top('A · 3D 玻璃', { back: false, cls: 'gm-top--bare', right: SVG.repeat, rightAttr: 'data-act="replay"' }) +
    '<div class="gm-body gm-body--nohud"><div class="igrid" data-mk="1">' + LIST.map(function (k) { return '<div>' + g3(k, 84) + '<b>' + NM[k] + '</b></div>'; }).join('') + '</div>' +
    '<div class="sec">预渲染序列帧(小程序落地方式)</div><div class="sprow" id="sprow" data-mk="2"><div class="gp">点下面按钮,用同一场景在浏览器里烤出 24 帧 WebP 雪碧图,右侧是它的体积。</div>' + gb('生成 3 枚序列帧', { kind: 'ghost', act: 'bakeSprites', data: 'style="height:44px;font-size:14px;margin-top:10px"' }) + '<div class="sprites" id="sprites"></div></div>' +
    '<div class="sec">弹窗 · 列表</div><div class="row3" data-mk="3">' + gb('主题弹窗', { kind: 'ghost', pop: 'pop-A1', data: 'style="flex:1;height:44px;font-size:14px"' }) + '</div><div class="lrow" style="margin-top:10px"><div class="lrow__g">' + ['badge', 'pass', 'seal'].map(function (k) { return '<div class="li">' + g3(k, 28) + '<span>' + NM[k] + '</span><i>' + (k === 'badge' ? '已获得' : k === 'pass' ? '有效' : '第 2 章') + '</i></div>'; }).join('') + '</div></div><div style="height:60px"></div></div>' +
    pop('pop-A1', { title: '获得勋章', center: true, body: '<div class="popic">' + g3('badge', 128) + '</div><div class="gp">邮政大楼 · 第一个找齐三封信的人。</div>', foot: gb('收下', { kind: 'primary', close: true }) });
  add('ic-3d', '3D 玻璃 · 整套', 'A · 图标三风格', 'cyp gm icx', a1, 'glass3d.js(Three.js r128,cdnjs 本地副本)', notes([
    [1, '十枚实时 3D:每枚是「白色清漆玻璃外壳 + 实体内芯」两层,反光来自一座程序生成的影棚(白 / 暖 / 柠檬绿三块柔光板),所以整套的高光位置、色温一致。进场弹出带一点过冲,常驻缓慢左右转、轻微起伏。'],
    [2, '落地方式 1:预渲染序列帧。这里当场烤出来,给出每枚 24 帧 112px WebP 的实际体积,供你估子包预算(实测每枚 20–40 KB,十枚约 0.3 MB;若要 2 倍清晰度 224px 约 0.9 MB,建议放主题分包)。落地方式 2:threejs-miniprogram + WebGL canvas 实时渲染,见「落地说明」屏。'],
    [3, '放进主题弹窗与列表行;切外观看它与玻璃 / 纸本 / 锈蚀 / 霓虹四种面的关系。']]));

  // ===== A2 · 精致插画 v2 =====
  var a2 = top('B · 精致插画 v2', { back: false, cls: 'gm-top--bare', right: SVG.repeat, rightAttr: 'data-act="replay"' }) +
    '<div class="gm-body gm-body--nohud"><div class="igrid" data-mk="1">' + LIST.map(function (k, i) { return '<div data-act="replayOne">' + IB.img(k, { size: 84, d: i * 70 }) + '<b>' + NM[k] + '</b></div>'; }).join('') + '</div>' +
    '<div class="sec">调色盘 · 每枚只取 2–3 色</div><div class="palr" data-mk="2">' + [['lime', '柠檬绿'], ['sky', '天蓝'], ['coral', '珊瑚'], ['gold', '金'], ['vio', '紫'], ['paper', '纸']].map(function (c) { return '<div><i style="background:' + IB.palette[c[0]] + '"><b style="background:' + IB.palette[c[0] + 'D'] + '"></b></i><span>' + c[1] + '</span></div>'; }).join('') + '</div>' +
    '<div class="sec">与空态插图并排</div><div class="gcard cmpes" data-mk="3"><span class="es"><img src="assets/img/empty_state_bg.svg"><img class="fg" src="assets/img/empty_state_figure.svg"></span>' + IB.img('clue', { size: 84 }) + IB.img('pass', { size: 84 }) + '</div>' +
    '<div class="sec">弹窗 · 列表</div><div class="row3" data-mk="4">' + gb('主题弹窗', { kind: 'ghost', pop: 'pop-B1', data: 'style="flex:1;height:44px;font-size:14px"' }) + gb('旧 vs 新', { kind: 'ghost', go: 'ic-illu-cmp', data: 'style="flex:1;height:44px;font-size:14px"' }) + '</div><div class="lrow" style="margin-top:10px"><div class="lrow__g">' + ['badge', 'pass', 'seal'].map(function (k) { return '<div class="li">' + IB.img(k, { size: 28 }) + '<span>' + NM[k] + '</span><i>' + (k === 'badge' ? '已获得' : k === 'pass' ? '有效' : '第 2 章') + '</i></div>'; }).join('') + '</div></div><div style="height:60px"></div></div>' +
    pop('pop-B1', { title: '获得勋章', center: true, body: '<div class="popic">' + IB.img('badge', { size: 128 }) + '</div><div class="gp">邮政大楼 · 第一个找齐三封信的人。</div>', foot: gb('收下', { kind: 'primary', close: true }) });
  add('ic-illu', '精致插画 v2 · 整套', 'A · 图标三风格', 'cyp gm icx', a2, 'icons-b.js(原创 SVG,v2)· 画法来源 assets/img/empty_state_*.svg · 参考见「参考」屏', notes([
    [1, '十枚重画。根基不变(白底形、4.5 粗黑描边、圆角端点),补上三样:每枚自己的 2–3 色、同色系深一档做下半阴影 + 左上白高光 + 脚下软影、主动作加次级动作。线索:放大镜左右找、纸条探头、找到才闪;勋章:落下压扁、绶带慢半拍摆、落定爆一次星;钥匙:在锁里转四分之一圈弹回、宝石随之闪;成功:钩画完三色纸屑弹出再落下;失败:落地抖一下裂成两半、小云飘来滴一滴;调查中:金沙一粒粒落、底堆慢慢涨、到点翻转;队伍:两侧靠拢、中间蹦一下冒颗心;通行证:挂在绳上来回荡;积分:三枚依次落下压实、顶上那枚翻面、冒个 +;章节印:举起落下压实,印记那一刻才显出来。点任一枚单独重播。'],
    [2, '共用调色盘六色各两档(浅 / 深),柠檬绿只是其中一色,不再满屏绿;黑白做骨架。'],
    [3, '与你的空态插图并排:同一家人,多了颜色和厚度。'],
    [4, '放进主题弹窗、列表行;「旧 vs 新」屏逐枚对照 v1。SVG 合计约 22 KB,直接进主包。']]));
  var cmpb = top('B · 旧 vs 新', { back: true, cls: 'gm-top--bare', right: SVG.repeat, rightAttr: 'data-act="replay"' }) +
    '<div class="gm-body gm-body--nohud"><div class="ovn" data-mk="1"><div class="ovn__h"><span></span><b>v1(上一轮)</b><b>v2(这一轮)</b></div>' + LIST.map(function (k, i) { return '<div class="ovn__r"><span>' + NM[k] + '</span>' + IB1.img(k, { size: 64, d: i * 50 }) + IB.img(k, { size: 64, d: i * 50 }) + '</div>'; }).join('') + '</div><div style="height:60px"></div></div>';
  add('ic-illu-cmp', '精致插画 · 旧 vs 新', 'A · 图标三风格', 'cyp gm icx', cmpb, 'icons-b1.js(v1)· icons-b.js(v2)', notes([
    [1, '左 v1(单色柠檬绿 + 灰阴影,动作单一),右 v2(每枚 2–3 色、同色系阴影、主 + 次级动作)。']]));

  // ===== A3 · 波点 =====
  var a3 = top('C · 波点', { back: false, cls: 'gm-top--bare', right: SVG.repeat, rightAttr: 'data-act="replay"' }) +
    '<div class="gm-body gm-body--nohud"><div class="igrid" data-mk="1">' + LIST.map(function (k, i) { return '<div data-act="replayOne">' + DOTS.img(k, { size: 84, d: i * 70 }) + '<b>' + NM[k] + '</b></div>'; }).join('') + '</div>' +
    '<div class="sec">点距</div><div class="row3 dotsz" data-mk="2">' + [6, 8, 10, 12].map(function (c) { return '<div>' + DOTS.img('key', { size: 72, cell: c }) + '<i>' + c + '</i></div>'; }).join('') + '</div>' +
    '<div class="sec">弹窗 · 列表</div><div class="row3" data-mk="3">' + gb('主题弹窗', { kind: 'ghost', pop: 'pop-C1', data: 'style="flex:1;height:44px;font-size:14px"' }) + '</div><div class="lrow" style="margin-top:10px"><div class="lrow__g">' + ['badge', 'pass', 'seal'].map(function (k) { return '<div class="li">' + DOTS.img(k, { size: 28, cell: 10 }) + '<span>' + NM[k] + '</span><i>' + (k === 'badge' ? '已获得' : k === 'pass' ? '有效' : '第 2 章') + '</i></div>'; }).join('') + '</div></div><div style="height:60px"></div></div>' +
    pop('pop-C1', { title: '获得勋章', center: true, body: '<div class="popic">' + DOTS.img('badge', { size: 128, cell: 7 }) + '</div><div class="gp">邮政大楼 · 第一个找齐三封信的人。</div>', foot: gb('收下', { kind: 'primary', close: true }) });
  add('ic-dot', '波点 · 整套', 'A · 图标三风格', 'cyp gm icx', a3, 'dots.js(剪影采样 → 圆点网格,原创)', notes([
    [1, '每枚图标只留一个实心剪影,采样到圆点网格上;进场时圆点从四周飞入落位(和章节数字的像素块同一套运动),常驻时一道亮波从左上扫到右下。颜色只读外观主色 --lk-a 和墨色,所以四种外观 × 五种配色自动成套,这是三种里唯一天然跟主题走的。'],
    [2, '点距 6 / 8 / 10 / 12:越密越像图,越疏越像装饰。建议大图 8,列表小图 10。'],
    [3, '放进主题弹窗与列表行。体积:运行时生成,零素材。']]));

  // ===== B · 章节开场(波点)=====
  var chs = '<div class="gm-tex"></div><canvas class="tiles-cv" id="dotStart" width="780" height="1688"></canvas>' + top('', { right: SVG.more, rightAttr: 'data-toast="菜单"' }) +
    '<div class="chs"><div class="chs__num" data-mk="1"></div><div class="chs__seal" data-mk="2"></div><div class="chs__t" data-mk="3">' + G.fxLine('第二章 · 邮政大楼', 'gh1', 'title') + G.fxLine('白天和夜里,它看起来不像同一栋楼。', 'line', 'body') + '</div></div>' +
    foot(gb('开始本章', { kind: 'primary', toast: '进入故事流', mk: 4 }));
  add('ch-dot', '章节开场 · 波点', 'B · 章节开场', 'cyp gm chdot', chs, 'components/cy/chapter-flip(替换像素块为圆点)', notes([
    [1, '章节数字改成圆点点阵:圆点从星野里飞入落位,带一点弹性;顶部到底部有明暗递减,一道亮波周期性扫过。颜色 = 当前外观主色。切顶栏四种外观 × 五种配色看整套。'],
    [2, '章节印:下方一枚圆章 —— 外环 44 点顺时针慢转、内环 28 点逆时针,中间一个「章」字点阵。印记在章末会盖到收集册里(同一枚点阵缩小)。'],
    [3, '章名与引子沿用定稿的文字动效(星尘 gather / 纸本 ink / 末世 wind / 夜城 neon)。'],
    [4, '按钮沿用外观按钮。']]));
  G.on['ch-dot'] = function (s) { DOTS.chapter(s.querySelector('#dotStart'), { num: '02', seal: '章' }); G.playFx(s); };

  // ===== C · 通行证钥匙 =====
  var ky = '<div class="ky"><canvas id="kyCv" width="780" height="1688"></canvas><div class="ky-grad" data-mk="3"></div>' +
    '<div class="ky-skip" data-go="ps-pass">跳过</div>' +
    '<div class="ky-txt" data-mk="4"><div class="ky-eb">通行证</div><div class="ky-h">赢取你的钥匙</div><div class="ky-p">持证期间,主题库里的城市定向随时开玩。</div><div class="ky-dots"><i class="on"></i><i></i><i></i><i></i><i></i><i></i></div><div class="ky-btn" data-go="ps-pass">开始</div></div>' +
    '<div class="ky-key" data-mk="1"></div><div class="ky-orb" data-mk="2"></div><div class="ky-turn" data-mk="5"></div>' +
    '<div class="ky-tl">' + [[0, '0s'], [1500, '1.5s'], [2400, '2.4s'], [3300, '3.3s'], [4400, '4.4s'], [7600, '侧立'], [9800, '待机']].map(function (t) { return '<span data-act="kySeek" data-t="' + t[0] + '">' + t[1] + '</span>'; }).join('') + '<span data-act="kyReplay">重播</span></div></div>';
  add('ps-key', '通行证 · 钥匙开场', 'C · 通行证钥匙', 'cyp ky-scr', ky, 'cy-pass-reveal(替换为 3D 钥匙开场)· 对照 xref/satya2.mp4', notes([
    [1, '钥匙:圆角方章头 + 吊环 + 杆 + 两齿,正面一圈「城瘾 · 通行证 · CHENGYIN PASS」环形字和一圈点,中间钥匙孔镂空;金属金,反光来自暖色影棚。0.5s 金色碎片从四面翻滚飞入,1.1s 四个部件也在其中,2.4s 合体(带回弹),碎片穿过钥匙后消散,一枚发光方块掉向右下。'],
    [2, '五枚发光小方块(带钥匙孔)4.2s 起绕钥匙公转,轨道半径、速度、倾角各不同,每枚带一圈暖光晕。'],
    [3, '3.0s 钥匙身后一团金尘炸开,同时底部暖橙渐变升起,场景里一盏橙色补光同步点亮,钥匙下半被照暖。'],
    [4, '3.4s 起:眉题 → 标题 → 一行正文 → 分页点 → 白色药丸「开始」,每项相隔约 150–200ms,从 8px 模糊 + 下移 8px 浮入;4.3s 右上角「跳过」。点「开始」进标准样式的通行证页。'],
    [5, '6.5–9.3s 钥匙绕竖轴整转一圈:侧立时正面光退掉,只剩两道背光勾出的轮廓;之后缓慢摆动、起伏待机。底部时间轴只在原型里有,逐镜对照用;减少动态效果时直接停在 4.6s 的终态。']]));
  G.on['ps-key'] = function (s) { KEY.start(s); };
  ACT.kySeek = function (el) { KEY.seek(+el.getAttribute('data-t')); };
  ACT.kyReplay = function (el) { KEY.start(el.closest('.scr')); };

  // ===== C2 · 通行证页(标准玩家 UI,不用玻璃;仅玩家侧,商家端没有通行证入口)=====
  var psp = '<div class="pl-top"><span class="bk" data-back>' + SVG.back + '</span><span class="tt">通行证</span></div>' +
    '<div class="sv psv"><div class="pcard" data-mk="1"><div class="pcard__top"><div><div class="gl-eb">城瘾通行证</div><div class="gl-h">年卡</div><div class="gl-k">有效期至 2027-11-10</div></div>' + g3('key', 92, 'gl-key') + '</div><div class="gl-bar"><i style="width:62%"></i></div><div class="gl-k2"><span>已用 138 天</span><span>剩 227 天</span></div></div>' +
    '<div class="plist" data-mk="2">' + [['seal', '可玩主题库', '12 个主题 · 持证 ¥0'], ['team', '邀请队友', '同队持证,队友门票 ¥0'], ['badge', '通行证勋章', '3 / 12']].map(function (r) { return '<div class="pitem"><span class="gl-ic">' + IB.img(r[0], { size: 36 }) + '</span><div><b>' + r[1] + '</b><span>' + r[2] + '</span></div>' + SVG.chev + '</div>'; }).join('') + '</div>' +
    '<div class="sec" style="padding:0 4px">本页只在玩家「我的」里;商家端没有通行证</div><div style="height:130px"></div></div>' +
    '<div class="gl-foot" data-mk="3"><div class="gl-pill" data-toast="去主题库">去主题库</div></div>';
  add('ps-pass', '通行证页 · 标准样式', 'C · 通行证钥匙', 'cyp pl psx', psp, 'pages/mine/pass(玩家侧平台页,黑底 token)', notes([
    [1, '按你的意见改回标准玩家 UI:实色卡片(cy 黑底 token),不用玻璃;卡内保留一枚 3D 钥匙,和开场是同一把。'],
    [2, '标准列表行,图标用 B 风格;通行证只在玩家侧,商家端没有入口、没有这一页。'],
    [3, '底部固定药丸,与开场的「开始」同一个。']]));

  // ===== D · 玻璃研究(只保留星尘主题内)=====
  var gls = '<div class="photo" style="background-image:url(assets/covers/treasure-hunter.jpg)"></div><div class="gm-tex"></div>' + top('邮政大楼') +
    '<div class="gls"><div class="glz glz--st gls__card" data-mk="1"><div class="gl-eb">第 2 站</div><div class="gl-h">邮政大楼</div><div class="gl-k">1924 年落成 · 钟楼仍在报时</div><div class="row" style="margin-top:12px">' + gb('开始玩法', { kind: 'primary', pop: 'pop-gls', data: 'style="flex:1;height:40px;font-size:14px"' }) + gb('线索', { kind: 'ghost', act: 'glsStack', data: 'style="flex:1;height:40px;font-size:14px"' }) + '</div></div>' +
    '<div class="gls__stack" id="glsStack" data-mk="2"><div class="glz glz--st gls__note"><span>' + g3('clue', 40) + '</span><div><b>线索 1</b><span>信箱里的信,没贴邮票。</span></div></div><div class="glz glz--st gls__note"><span>' + g3('clue', 40) + '</span><div><b>线索 2</b><span>钟楼的钟慢了七分钟。</span></div></div><div class="glz glz--st gls__note"><span>' + g3('seal', 40) + '</span><div><b>章节印</b><span>第一章 · 已盖。</span></div></div></div></div>' +
    pop('pop-gls', { title: '找到一条线索', center: true, cls: 'gm-pop--glz', mk: 3, body: '<div class="popic">' + g3('clue', 112) + '</div><div class="gp">邮政大楼门口的信箱里,有一封没贴邮票的信。</div>', foot: gb('收进背包', { kind: 'primary', close: true }) });
  add('gl-stardust', '玻璃 · 星尘主题内', 'D · 玻璃研究', 'cyp gm gm--photo glsx', gls, 'pages/play(地点卡 + 弹窗,星尘外观)', notes([
    [1, '星尘外观下的地点卡改成同一配方的玻璃(照片透过来被模糊),描边、反光、噪点与平台页一致;其他三种外观不受影响,仍用各自的面。'],
    [2, '点「线索」展开一叠玻璃便签:三张从卡片后面依次抽出、错落堆叠,每张里一枚 3D 图标;再点收回。这是 jay2 参考里的「纵深堆叠」在主题内的用法。'],
    [3, '弹窗也是玻璃:背后的地点卡被压暗、缩小 4%、轻微模糊,弹窗浮在上面,里面同一枚 3D 线索。切配色看玻璃的色温跟着变。']]));
  ACT.glsStack = function (el) { el.closest('.scr').classList.toggle('stack-open'); requestAnimationFrame(PROTO.placeMarks); };

  // ===== E · 落地说明 =====
  var ship = '<div class="pl-top"><span class="tt">落地说明</span></div><div class="sv shipv">' +
    '<div class="ship" data-mk="1"><div class="ship__h">3D 图标 / 钥匙开场怎么进小程序</div>' +
    '<div class="ship__opt"><b>方式 1 · 预渲染序列帧(推荐先用)</b><p>在构建机用同一份 Three.js 场景烤成 WebP 序列帧或雪碧图(本原型实测每枚 24 帧 × 112px 为 20–40 KB,十枚约 0.3 MB,224px 约 0.9 MB;钥匙开场 10s × 24fps 按 390×844 约 3–5 MB,需裁成 390×520 的上半区并降到 18fps,约 1.5–2.5 MB)。小程序端用 image + 定时切帧或 CSS steps(),零运行时成本、所有机型一致,代价是不能跟手、不能随配色实时变色。</p></div>' +
    '<div class="ship__opt"><b>方式 2 · threejs-miniprogram + WebGL canvas</b><p>微信官方适配层(npm threejs-miniprogram,three r0.11x 级别 API),用 canvas type="webgl" 实时渲染。能跟手、能随外观变色、能做侧立轮廓光这种依赖实时光照的效果;代价是 three 本体约 600 KB(需放分包,主包 2 MB 上限)、低端安卓发热与掉帧、首帧要预热 300–600ms,且 transmission 这类高级材质在 WebGL1 机型上不可用,要备降级材质。</p></div>' +
    '<div class="ship__opt"><b>子包预算</b><p>主包 2 MB、总包 20 MB(含分包)。建议:图标用方式 1 进「主题分包」;钥匙开场只在通行证页出现,放「我的分包」,帧序列不超过 2.5 MB;three 本体与 WebGL 场景只在决定上方式 2 时再进独立分包 ≤ 1 MB。SVG 插画(B)和点阵(C)都 < 20 KB,直接进主包。</p></div>' +
    '<div class="ship__opt"><b>通行证只在玩家侧</b><p>通行证、钥匙开场、通行证页都只出现在玩家「我的」里;商家端没有通行证,不加入口、不加页面、不发通知。</p></div><div class="ship__opt"><b>减少动态效果</b><p>系统开启减弱动态时:图标只显示终态静帧,钥匙开场直接停在 4.6s 的完整画面(钥匙正面 + 文字 + 按钮),不播碎片与整转。</p></div></div>' +
    '<div class="ship" data-mk="2"><div class="ship__h">三种风格各自的代价</div><div class="ship__tbl"><div><b>A · 3D 玻璃</b><span>最贵:要烤帧或跑 WebGL;最像 Apple 奖章;不随主题变色(烤帧版)。</span></div><div><b>B · 精致插画</b><span>最省:SVG 进主包;和空态插图一家;动画用 transform 就够;不随主题变色,但黑白绿在四种外观上都站得住。</span></div><div><b>C · 波点</b><span>零素材,运行时生成;唯一天然跟外观主色走的一种;表意弱,小尺寸需要配文字。</span></div></div></div><div style="height:40px"></div></div>';
  add('ship', '落地说明', 'E · 落地说明', 'cyp pl shipx', ship, '', notes([
    [1, '两条落地路径与子包预算,数字按本原型实测(序列帧由 A1 屏当场烤出)与微信公开限制(主包 2 MB / 总包 20 MB)估算。'],
    [2, '三种风格各自的代价,供你选。']]));

  // 公共动作
  ACT.replay = function (el) { var root = el.closest('.scr'); [].forEach.call(root.querySelectorAll('.ib,.idot'), function (ic) { var c = ic.cloneNode(true); ic.parentNode.replaceChild(c, ic); }); [].forEach.call(root.querySelectorAll('.g3'), function (g) { delete g.dataset.born; }); };
  ACT.replayOne = function (el) { var ic = el.querySelector('.ib,.idot'); if (!ic) return; var c = ic.cloneNode(true); ic.parentNode.replaceChild(c, ic); };
  ACT.bakeSprites = function (el) {
    var box = document.getElementById('sprites'); box.innerHTML = ''; var total = 0;
    ['key', 'badge', 'points'].forEach(function (k) { var s = G3.sprite(k, 24, 112); total += s.bytes; box.insertAdjacentHTML('beforeend', '<div class="spr"><span style="background-image:url(' + s.url + ');--n:' + s.n + ';--px:' + s.px + 'px"></span><b>' + NM[k] + '</b><i>' + (s.bytes / 1024).toFixed(0) + ' KB · ' + s.n + ' 帧</i></div>'); });
    box.insertAdjacentHTML('beforeend', '<div class="spr__sum">三枚合计 ' + (total / 1024).toFixed(0) + ' KB → 十枚约 ' + (total / 1024 / 3 * 10 / 1024).toFixed(1) + ' MB(WebP q82,112px)</div>');
    requestAnimationFrame(PROTO.placeMarks);
  };
  H.flush();
})();
