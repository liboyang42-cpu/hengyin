/**
 * behaviors/exit-motion.js —— 浮层退场动画(2026-08-21)
 *
 * 解决的问题:`wx:if="{{show}}"` 直连外部 show,关闭时组件瞬间卸载,退场动画根本没机会播。
 * 做法:组件内部另立 `_render` 控卸载 —— show 置 false 时先挂 `_closing`(调用方在 wxml 里
 * 拼成自己的 --closing 类,由 wxss 接退场 keyframes),定时器到点才把 _render 置 false。
 *
 * ⚠️ exitMs 是 WXSS 里退场动画时长的 JS 镜像(WXSS 变量 JS 读不到,只能两处各留一份)。
 * 两处漂移会让「动画没播完就卸载」或「播完还多等一拍」,由 sheet-modal-exit-motion-contract
 * 从 tokens.wxss 反解数值来判红 —— 改时长务必两处同步。
 * 按档位定速的组件(cy-sheet / cy-scene-sheet)在实例上写 `this._exitMs` 覆盖本次退场时长。
 *
 * ⚠️ 退场必须比进场快:DS 文档3 §B.1「上限 ~300ms;进场慢、退场快(exit 比 enter 快 ~20%)」。
 *
 * —— 2026-10-23 业主定:同一时刻只有一个弹层在动(交接) ——
 * 以前「关 A 开 B」写在同一帧里(帖文操作 → 删除确认、删除确认 → 结果卡、14 处 setData 一关一开),
 * 两层同时进出、两层遮罩叠在一起。现在由本 behavior 统一交接:
 *   · 有别的弹层正在退场时,新弹层先排队,等它退完才挂载(最多等 HANDOFF_CAP_MS);
 *   · 正在退场的那一层收到 `_keepMask`,遮罩保持不透明不淡出;新弹层带 `_handoffIn` 挂载,
 *     遮罩直接是暗的不再淡入 —— 页面不会「暗 → 亮 → 暗」闪一下。
 *   · 叠在一个**仍开着**的弹层上(如脏数据确认盖在编辑弹窗上)不排队,照常进场。
 * 调用方一行不用改:凡挂了本 behavior 的弹层(cy-sheet / cy-modal / cy-date-sheet /
 * cy-scene-sheet / cy-publish-sheet / cy-dropdown)自动参与。
 *
 * 依赖:宿主组件必须也挂 behaviors/reduced-motion.js —— 减动效下直接卸载不空等,也不排队。
 *
 * @param {number} exitMs 退场动画时长(毫秒),必须等于 wxss 里那条 --cy-motion-* 的值
 */
const HANDOFF_CAP_MS = 320;   // 最长退场(full 280)+ 一帧余量;再长就不等了,宁可叠也不让用户干等

// 全小程序同一时刻只有栈顶页在交互,交接表放模块级即可。
const closing = new Set();    // 正在播退场的实例
const waiting = [];           // 排队等进场的实例

function releaseWaiting() {
  if (closing.size) return;
  const list = waiting.splice(0);
  list.forEach((vm) => {
    if (vm._handoffTimer) { clearTimeout(vm._handoffTimer); vm._handoffTimer = null; }
    if (vm._handoffPending) vm._enterNow(true);
  });
}

module.exports = function exitMotion(exitMs) {
  // 单测沙箱里没有全局 Behavior(只 mock 了 Component):退回原样对象,组件定义照常可加载
  const define = typeof Behavior === 'function' ? Behavior : (o) => o;
  return define({
    data: {
      _render: false,
      _closing: false,
      _keepMask: false,
      _handoffIn: false,
    },
    observers: {
      show(value) {
        if (value) {
          // 退场播到一半又被打开:撤掉待卸载的定时器,否则它会把刚开的面板关掉
          if (this._exitTimer) {
            clearTimeout(this._exitTimer); this._exitTimer = null;
            closing.delete(this);
            this.setData({ _render: true, _closing: false, _keepMask: false });
            return;
          }
          if (this.data._render || this._handoffPending) return;
          // morph 进场(传了 anchor-selector)在 show 的同一拍里量锚点,挂载不能往后推 —— 直接进场不排队
          if (this.data.anchorSelector) { this._enterNow(false); return; }
          // 推到下一拍再判断:同一次 setData 里「关 A 开 B」的观察者顺序不定(取决于节点先后),
          // 等这一拍里所有关闭都登记进 closing,再决定 B 要不要排队。
          this._handoffPending = true;
          this._openTick = setTimeout(() => {
            this._openTick = null;
            if (!this._handoffPending) return;
            const others = [...closing].filter((vm) => vm !== this);
            if (!others.length || this.data.reducedMotion) { this._enterNow(false); return; }
            // 别的弹层正在退场:排队,等它退完再进(交接),遮罩不闪
            others.forEach((vm) => { if (!vm.data._keepMask) vm.setData({ _keepMask: true }); });
            waiting.push(this);
            this._handoffTimer = setTimeout(() => {
              this._handoffTimer = null;
              const i = waiting.indexOf(this);
              if (i >= 0) waiting.splice(i, 1);
              if (this._handoffPending) this._enterNow(true);
            }, HANDOFF_CAP_MS);
          }, 0);
          return;
        }
        // 还在排队就被关掉:直接出队,什么都不渲染
        if (this._handoffPending) {
          this._handoffPending = false;
          const i = waiting.indexOf(this);
          if (i >= 0) waiting.splice(i, 1);
          if (this._handoffTimer) { clearTimeout(this._handoffTimer); this._handoffTimer = null; }
          if (this._openTick) { clearTimeout(this._openTick); this._openTick = null; }
          return;
        }
        if (!this.data._render) return;   // 本来就没渲染,不必播退场
        if (this.data.reducedMotion) { this.setData({ _render: false, _closing: false }); return; }
        this.setData({ _closing: true });
        closing.add(this);
        this._exitTimer = setTimeout(() => {
          this._exitTimer = null;
          closing.delete(this);
          this.setData({ _render: false, _closing: false, _keepMask: false });
          releaseWaiting();
        }, this._exitMs || exitMs);
      },
    },
    methods: {
      // handoff=true:从上一个弹层手里接过遮罩 —— 遮罩直接是暗的,只有面板进场
      // 宿主可选两个钩子:_enterData() 返回与挂载同一次 setData 的字段(如档位),
      // _afterEnter() 在挂载渲染完成后调用(如量面板高度)。
      _enterNow(handoff) {
        this._handoffPending = false;
        const extra = typeof this._enterData === 'function' ? this._enterData() : null;
        this.setData(Object.assign({ _render: true, _closing: false, _handoffIn: !!handoff }, extra), () => {
          if (typeof this._afterEnter === 'function') this._afterEnter();
        });
        if (handoff) {
          if (this._handoffInTimer) clearTimeout(this._handoffInTimer);
          // 进场播完就摘掉,免得下次自己退场时遮罩不淡出
          this._handoffInTimer = setTimeout(() => {
            this._handoffInTimer = null;
            this.setData({ _handoffIn: false });
          }, HANDOFF_CAP_MS + 80);
        }
      },
    },
    lifetimes: {
      detached() {
        if (this._exitTimer) { clearTimeout(this._exitTimer); this._exitTimer = null; }
        if (this._handoffTimer) { clearTimeout(this._handoffTimer); this._handoffTimer = null; }
        if (this._handoffInTimer) { clearTimeout(this._handoffInTimer); this._handoffInTimer = null; }
        if (this._openTick) { clearTimeout(this._openTick); this._openTick = null; }
        this._handoffPending = false;
        closing.delete(this);
        const i = waiting.indexOf(this);
        if (i >= 0) waiting.splice(i, 1);
        releaseWaiting();
      },
    },
  });
};

// 单测用:清空模块级交接表,免得用例之间互相串
module.exports._resetHandoff = function () { closing.clear(); waiting.splice(0); };
