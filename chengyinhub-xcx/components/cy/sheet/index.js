const reducedMotionBehavior = require('../../../behaviors/reduced-motion.js');
const exitMotion = require('../../../behaviors/exit-motion.js');
const morphEntrance = require('../../../behaviors/morph-entrance.js');
const { resolveMenuChrome } = require('../../../utils/nav-safe-area.js');
const { withNativeBackGuard, onBackGuardLeave } = require('../../../utils/native-back-guard.js');
const app = getApp();

/* ——— 2026-09-01:handle 从「装饰横条」补成真的可拖 ———
 * 手册 Motion·Overlay choreography 的 Sheet P1 一栏一直写着「handle 可拖」,
 * 但本组件此前 touch 零命中 —— 一根长得像「拖我」的横条,拖了没反应。
 * 这是「声称存在、实际不生效」那一类,不是缺功能。
 *
 * 三件事缺一不可(少一件就会「飘」):
 *   跟随 —— 位移 1:1 跟手。拖拽期间必须同时顶掉 animation 和 transition,
 *           否则面板会在手指后面追。
 *   阈值 —— 松手判定「位移 > 面板高 × 1/3」**或**「甩动速度 > .55 px/ms」,
 *           二选一满足即关。只看位移的话快速小甩关不掉,只看速度的话慢拖到底关不掉。
 *   吸附 —— 没过阈值弹回 0(standard 档 ease-out),过了交给既有关闭链路播 sh-down。
 *
 * 约束:只动 transform(手册 Motion 禁止动画 height/top);dirty 复用 _requestClose,
 * 和点遮罩走同一条闸 —— 有未保存输入时只发 requestclose、面板弹回,不直接关。 */
const DRAG_RATIO = 1 / 3;      // 位移阈值:面板高度的三分之一
const DRAG_VELOCITY = 0.55;    // 速度阈值:px/ms
const DRAG_UP_DAMP = 3;        // 往上拖给阻尼,不让面板被拽出屏幕
const SNAP_MS = 220;           // = --cy-motion-standard,和 wxss 里的吸附时长镜像
/* 2026-10-23 业主定:按档位定速。退场计时器是 wxss 里 --cy-motion-sheet-*-out 的 JS 镜像
 * (peek 200 / half 250 / full 280),sheet-motion-levels 契约从 tokens 反解比对。 */
const LEVEL_EXIT_MS = { peek: 200, half: 250, full: 280 };
const LEVEL_RATIO = { peek: 0.30, half: 0.60 };

/* G-2(2026-10-27):物理返回 / 侧滑先落在弹层上。full 有上一步就退一步;有脏输入只发 requestclose 交给宿主;
 * 其余与用户能点到的出口一致 —— 遮罩可关或有 ✕ 才关,两者都没有的弹层只吞掉返回键,不替宿主关。 */
function onNativeBack(vm) {
  if (vm.data._closing) return;
  if (vm.data.variant === 'full' && vm.data.canBack) { vm.onBack(); return; }
  if (vm.data.dirty) { vm.triggerEvent('requestclose', { reason: 'back' }); return; }
  const hasClose = vm.data.closable && (vm.data.title || vm.data.navTitle || vm.data.variant === 'full');
  if (vm.data.maskClosable || hasClose) vm._requestClose('back');
}

Component(withNativeBackGuard({
  options: { multipleSlots: true },
  behaviors: [reducedMotionBehavior, exitMotion(250), morphEntrance({ shellSelector: '.sh__panel' })],
  properties: {
    // G-2:页面已有自己的 page-container 覆盖这个弹层时传 false 让位(一页只能有一个容器)
    backGuard: { type: Boolean, value: true },
    stacked: { type: Boolean, value: false },
    closeIcon: {type:String,value:'close-sm'},
    closeIconSize: {type:Number,value:44},
    show:         { type: Boolean, value: false },
    title:        { type: String,  value: '' },   // 头部标题(可选)
    handle:       { type: Boolean, value: false }, // 顶部拖拽抓手(仅 bottom)
    closable:     { type: Boolean, value: true },  // 有 title 时头部 × 关闭
    maskClosable: { type: Boolean, value: true },  // 点遮罩关闭(sheet 默认可)

    /* ——— 2026-07-31 新增:全屏变体(弹窗规范类型 B) ———
     * variant='full' 用于"依附发起页的连续多步任务"(商家入驻、发起官方活动等)。
     * bottom 是默认值,既有 20 处调用不传 variant,行为完全不变。 */
    inset: { type: Boolean, value: false }, // 仅短提示/短操作菜单留左右和底部边距
    variant:  { type: String,  value: 'bottom' }, // bottom | full
    /* navTitle:full 变体的可选标题；未传时复用 title，避免标题层级重复。 */
    navTitle: { type: String,  value: '' },
    canBack: { type: Boolean, value: false },     // full:是否显示内部返回(首步不显示)
    steps:   { type: Number,  value: 0 },         // full:总步数(>1 才画进度)
    current: { type: Number,  value: 0 },         // full:当前步(0-based)
    footer:  { type: Boolean, value: false },     // 是否有底部固定 CTA(命名 slot="footer")
    /* dirty:有未保存输入。为 true 时遮罩不再直接关闭,只发 requestclose,
     * 由父页面决定"保存草稿/放弃"。组件自己不清业务状态(状态所有权留在发起页)。 */
    dirty:   { type: Boolean, value: false },
    /* fixed:打开后内容会变(搜索 / 加载 / 分页签 / 多步)时固定在某档高度,不随内容跳。
     * '' = 贴内容高(默认,上限 full 80%);'peek' | 'half' | 'full' = 30% / 60% / 80%。
     * 大编辑器与筛选弹窗一律 fixed="full"(2026-10-23 业主定)。 */
    fixed:   { type: String,  value: '' },

  },
  data: {
    stepList: [],
    closeLabel: '关闭当前面板',
    /* full 变体的面板顶:按胶囊实测几何下发(见 attached),拿不到时 wxss 退回安全区 token。
     * ⚠️ CU-M-163 改法:这里原来是 navTop + navRight —— 拿「把 ✕ 往左推约 100px」躲胶囊,
     *   结果右上角关闭停在面板中部偏右,和 bottom 档贴弹层右缘的 ✕ 长得不一样。
     *   顶边让开之后不再需要让位:用户统一规则是应用弹层不占微信导航区。 */
    fullMaxH: '',
    /* 拖拽三态:_dragging 关掉动效跟手 · _dragStyle 行内 transform · _snapping 弹回 */
    _dragging: false,
    _dragStyle: '',
    _snapping: false,
    /* 档位(peek | half | full):决定升起 / 落下用哪一档时长。full 变体与 fixed 档直接知道;
     * 贴内容高的弹窗挂载时先 _measuring(面板与遮罩不动、不可见),量完高度再起跑 —— 只多一帧。 */
    _lv: '',
    _measuring: false,
  },
  observers: {
    'title, navTitle'(title, navTitle) {
      const label = navTitle || title;
      this.setData({ closeLabel: label ? '关闭' + label : '关闭当前面板' });
    },
    steps(n) {
      this.setData({ stepList: Array.from({ length: Math.max(0, n) }, (_, i) => i) });
    },
  },
  lifetimes: {
    attached() {
      const g = (app && app.globalData) || {};
      const w = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
      // 胶囊底 +5 是全站弹窗的统一顶线(用户 2026-08-10 定);拿不到胶囊信息时
      // resolver 自己退化成「状态栏 + 导航行 + 5」,不会退回压胶囊的满屏。
      const sheetTop = resolveMenuChrome(w, g.menuButtonInfo).sheetTop;
      this.setData({
        // 值是字符串拼出来的:wxml 侧只做一次三元拼接,不在模板里算几何。
        fullMaxH: `--cy-comp-sheet-max-h: calc(100vh - ${sheetTop}px);`,
        closeLabel: (this.data.navTitle || this.data.title) ? '关闭' + (this.data.navTitle || this.data.title) : '关闭当前面板',
      });
    },
    detached() {
      if (this._snapTimer) { clearTimeout(this._snapTimer); this._snapTimer = null; }
      this._drag = null;
    },
  },
  methods: {
    // G-2 返回守卫 page-container 的 afterleave(判定逻辑在 utils/native-back-guard.js)
    _onBackGuardLeave() { onBackGuardLeave(this); },
    /* 内部步骤返回 —— 只退一步,不是退出任务,所以单独发 back,不发 requestclose */
    onBack() {
      if (this.data._closing) return;
      this.triggerEvent('back');
    },

    onClose() {
      if (this.data._closing) return;
      this._requestClose('close');
    },
    onMask() {
      if (this.data._closing) return;
      // 有脏输入时遮罩不直接关(防误触丢数据),但仍上报让父页面决定
      if (this.data.dirty) { this.triggerEvent('requestclose', { reason: 'mask' }); return; }
      if (!this.data.maskClosable) return;
      this._requestClose('mask');
    },

    _requestClose(reason) {
      if (this.data._closing) return;
      this.triggerEvent('requestclose', { reason });
      /* 向后兼容:既有 20 处调用监听的是 bind:close。干净状态下照常发 close,
       * 旧页面零改动;脏状态只发 requestclose,强制父页面显式处理。 */
      if (!this.data.dirty) this.triggerEvent('close', { reason });
    },


    /* ——— handle 拖拽:跟随 / 阈值 / 吸附 ——— */
    onDragStart(e) {
      if (this.data._closing) return;
      const t = e.touches && e.touches[0];
      if (!t) return;
      if (this._snapTimer) { clearTimeout(this._snapTimer); this._snapTimer = null; }
      const now = Date.now();
      // prevY/prevT 留空,首帧没有前一采样点 ⇒ onDragEnd 里速度按 0 算(只走位移闸)
      this._drag = { y0: t.clientY, y: t.clientY, t: now, prevY: null, prevT: null, h: 0 };
      /* 面板高度只在按下时量一次:阈值是「面板高的 1/3」,不是屏幕高的 —— 半屏弹层和
       * 只有两行的小面板要用各自的 1/3,写死一个像素值会让小面板几乎关不掉。
       * 量测是异步的,松手时若还没回来则 h=0,此时退化成只看速度(见 onDragEnd)。 */
      this.createSelectorQuery().select('.sh__panel').boundingClientRect((r) => {
        if (this._drag && r && r.height) this._drag.h = r.height;
      }).exec();
      this.setData({ _dragging: true, _snapping: false });
    },

    onDragMove(e) {
      const d = this._drag;
      if (!d) return;
      const t = e.touches && e.touches[0];
      if (!t) return;
      d.prevY = d.y; d.prevT = d.t;
      d.y = t.clientY; d.t = Date.now();
      let dy = d.y - d.y0;
      if (dy < 0) dy /= DRAG_UP_DAMP;   // 向上是阻尼,不是 1:1
      /* 位移走 CSS 变量挂在根节点上,不写进 .sh__panel 的 style ——
         panel 那个 style 表达式被 sheet-morph-contract 逐字锁着(morph 起点变换靠它),
         在上面再叠一层拖拽会把 morph 的进出场顶掉。变量从根节点继承下去,两者互不打架。 */
      this.setData({ _dragStyle: `--sh-drag-y: ${dy.toFixed(1)}px;` });
    },

    onDragEnd() {
      const d = this._drag;
      this._drag = null;
      if (!d) return;
      const dy = d.y - d.y0;
      const dt = d.prevT == null ? 0 : Math.max(1, d.t - d.prevT);
      const v = dt ? (d.y - d.prevY) / dt : 0;          // 只算末段速度,不用全程均速
      const passed = (d.h > 0 && dy > d.h * DRAG_RATIO) || v > DRAG_VELOCITY;

      /* 两种「过了阈值也不许关」的情形,必须和点遮罩走同一套判断:
           · maskClosable=false —— 调用方显式表态不许随手关(如 club/detail 的编辑动态)。
             onMask 在这种情形什么都不发,拖拽同样不发。
           · dirty —— 有未保存输入。onMask 会发 requestclose 让父页面决定,拖拽同样要发。
         ⚠️ 这两种情形下面板都得**弹回**,不能瞬移:2026-09-01 自审实测,dirty 时原本走的是
            下面那条关闭分支,_snapping 被置 false ⇒ .sh--snap 的 transform 规则不再命中,
            面板直接跳回 0。当时的单测只断言了事件、没断言吸附态,所以绿着放过去了。 */
      const blocked = !this.data.maskClosable || this.data.dirty;
      if (passed && blocked) {
        if (this.data.dirty) this.triggerEvent('requestclose', { reason: 'drag' });
        this._snapBack();
        return;
      }
      if (passed) {
        // 关闭动画由既有链路(_closing → sh-down)播,这里必须先把行内 transform 撤掉,
        // 否则它会盖住 sh-down 的关键帧,面板停在手指松开的位置不动。
        this.setData({ _dragging: false, _dragStyle: '', _snapping: false });
        this._requestClose('drag');
        return;
      }
      this._snapBack();
    },

    /* 吸附回 0:_dragging 摘掉(恢复 transition),_snapping 挂上给一档 standard 的过渡,
       到时清掉 —— 留着的话下次进场会被这条 transition 拖慢。
       三个调用点(没过阈值 / 不许关 / 有脏输入)共用,别再各写一份。 */
    _snapBack() {
      if (this._snapTimer) clearTimeout(this._snapTimer);
      this.setData({ _dragging: false, _snapping: true, _dragStyle: '' });
      this._snapTimer = setTimeout(() => {
        this._snapTimer = null;
        this.setData({ _snapping: false });
      }, SNAP_MS);
    },

    /* ——— 按档位定速(exit-motion 的两个钩子) ——— */
    _knownLevel() {
      if (this.data.variant === 'full') return 'full';
      if (this.data.fixed) return this.data.fixed;
      // morph 进场有自己的量测与起点,不再插一帧量高;按 half 档定速
      if (this.data.anchorSelector) return 'half';
      return '';
    },
    _enterData() {
      const lv = this._knownLevel();
      this._exitMs = LEVEL_EXIT_MS[lv || 'half'];
      return lv ? { _lv: lv, _measuring: false } : { _lv: '', _measuring: !this.data.reducedMotion };
    },
    _afterEnter() {
      if (!this.data._measuring) return;
      const done = (lv) => {
        this._exitMs = LEVEL_EXIT_MS[lv];
        this.setData({ _lv: lv, _measuring: false });
      };
      const w = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
      const H = (w && (w.windowHeight || w.screenHeight)) || 0;
      this.createSelectorQuery().select('.sh__panel').boundingClientRect((r) => {
        if (!r || !r.height || !H) { done('half'); return; }
        // +1px 容差:贴着档位线的面板算进这一档
        done(r.height <= H * LEVEL_RATIO.peek + 1 ? 'peek' : (r.height <= H * LEVEL_RATIO.half + 1 ? 'half' : 'full'));
      }).exec();
    },

    noop() {},
  },
}, onNativeBack));
