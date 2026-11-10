const reducedMotionBehavior = require('../../../behaviors/reduced-motion.js');
const exitMotion = require('../../../behaviors/exit-motion.js');
const morphEntrance = require('../../../behaviors/morph-entrance.js');
const { resolveMenuChrome } = require('../../../utils/nav-safe-area.js');
const { withNativeBackGuard, onBackGuardLeave } = require('../../../utils/native-back-guard.js');
const app = getApp();

/* ——— 拖拽(qx-u1,2026-11-10 业主定参数) ———
 * 跟手在视图层跑:抓手和头部挂 utils/wxs/gesture.wxs 的 sheet* 处理器,位移只写面板行内 transform,
 * 不经 setData。阈值 / 阻尼 / 吸附的数学见 utils/gesture-physics.js(WXS 内有镜像,parity 单测比对):
 *   跟随 —— 1:1 跟手;越过最高档往上拽只走 25% 并弹回(减动效下顶住不动)
 *   阈值 —— 松手「速度 > 0.5 px/ms」或「位移 > 面板高 30%」任一满足即关
 *   吸附 —— 没过阈值落回最近的档(standard 档 ease-out;减动效直接到位)
 * 不许随手关(maskClosable=false)或有脏输入时,过了阈值也只弹回 —— 和点遮罩同一条闸,脏输入照样上报 requestclose。
 * 正文 scroll-view 不起拖,内容照常滚动。 */
const DISMISS_FALLBACK_MS = 400; // 宿主没接这次关闭时,多久把落下去的面板拉回来
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
    /* 拖拽:_dragOut 面板已被甩出屏外(退场不再从 0 播 sh-down)· _gestureCmd 变一次 = 让视图层撤掉行内位移
     * _winH 窗口高,给 WXS 算档位 */
    _dragOut: false,
    _gestureCmd: 0,
    _winH: 0,
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
    // 甩出去之后又被打开:退场途中重开(节点还在)⇒ 发 gcmd 把面板从屏外接回来;
    // 已卸载后重开 ⇒ 只清标记、gcmd 归零(新节点挂载时视图层不该再收到一条回拉指令)
    show(v) {
      if (!v || !this.data._dragOut) return;
      this.setData(this.data._render
        ? { _dragOut: false, _gestureCmd: this.data._gestureCmd + 1 }
        : { _dragOut: false, _gestureCmd: 0 });
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
        _winH: (w && (w.windowHeight || w.screenHeight)) || 0,
        closeLabel: (this.data.navTitle || this.data.title) ? '关闭' + (this.data.navTitle || this.data.title) : '关闭当前面板',
      });
    },
    detached() {
      if (this._dragOutTimer) { clearTimeout(this._dragOutTimer); this._dragOutTimer = null; }
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


    /* ——— 拖拽松手(WXS sheetEnd 回调) ———
     * dismiss:面板已在视图层往下落,这里走和 ✕ 同一条关闭出口;宿主没接(show 仍为 true)就拉回来。
     * blocked:过了阈值但不许关 —— 面板已在视图层弹回;脏输入照样上报 requestclose 让父页面决定。 */
    _onGestureRelease(detail) {
      const action = detail && detail.action;
      if (action === 'blocked') {
        if (this.data.dirty) this.triggerEvent('requestclose', { reason: 'drag' });
        return;
      }
      if (action !== 'dismiss' || this.data._closing) return;
      this.setData({ _dragOut: true });
      this._requestClose('drag');
      if (this._dragOutTimer) clearTimeout(this._dragOutTimer);
      this._dragOutTimer = setTimeout(() => {
        this._dragOutTimer = null;
        if (this.data.show && !this.data._closing) {
          this.setData({ _dragOut: false, _gestureCmd: this.data._gestureCmd + 1 });
        }
      }, DISMISS_FALLBACK_MS);
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
