// 发布菜单：复用已确认的 3D 素材与错峰上浮动效。
const toast = require('../../../utils/toast.js');
const reducedMotionBehavior = require('../../../behaviors/reduced-motion.js');
const exitMotion = require('../../../behaviors/exit-motion.js');
const { withNativeBackGuard, onBackGuardLeave } = require('../../../utils/native-back-guard.js');

/* G-2(2026-10-27):物理返回先退到首屏卡片,首屏再按 = 底部「返回」关闭菜单。 */
function onNativeBack(vm) {
  if (vm.data.level && vm.data.level !== 'cards') vm.backToCards();
  else vm.onClose();
}

Component(withNativeBackGuard({
  // 与 --cy-motion-standard 的退场时长保持一致。
  behaviors: [reducedMotionBehavior, exitMotion(220)],
  options: { styleIsolation: 'apply-shared' },
  properties: {
    // G-2:页面已有自己的 page-container 覆盖这个弹层时传 false 让位(一页只能有一个容器)
    backGuard: { type: Boolean, value: true },
    show: {
      type: Boolean, value: false,
      observer(v) {
        if (v) this.setData({ level: 'cards', selectedMode: 1 });
      }
    },
    pub: { type: Object, value: null },
    scope: { type: String, value: '' },
    merchant: { type: Boolean, value: false }
  },
  data: {
    level: 'cards', selectedMode: 1, quickOpen: false
  },
  lifetimes: {
    detached() {
      if (this._openTimer) { clearTimeout(this._openTimer); this._openTimer = null; }
    }
  },
  observers: {
    '_render, level': function (render, level) {
      if (this._openTimer) { clearTimeout(this._openTimer); this._openTimer = null; }
      this.setData({ quickOpen: false });
      if (!render) return;
      // P-12(2026-10-27):遮罩原来是「瞬间出现」—— nextTick 的 setData 会和挂载并进同一帧渲染,
      // opacity 0→1 的过渡根本没起点。隔一帧(~16ms)再置 quickOpen,先让透明态画出来。
      this._openTimer = setTimeout(() => {
        this._openTimer = null;
        if (this.data.show && this.data.level === level) this.setData({ quickOpen: true });
      }, 16);
    }
  },
  methods: {
    // G-2 返回守卫 page-container 的 afterleave(判定逻辑在 utils/native-back-guard.js)
    _onBackGuardLeave() { onBackGuardLeave(this); },
    onClose() { this.triggerEvent('close'); },
    onMask() { this.triggerEvent('close'); },
    noop() {},
    backToCards() { this.setData({ level: 'cards' }); },
    chooseTopicMode(e) {
      if (!this.data.pub) return toast('发布信息尚未就绪，请稍后重试');
      const mode = Number(e.currentTarget.dataset.mode) === 2 ? 2 : 1;
      this.setData({
        selectedMode: mode,
        level: 'topicAbility'
      });
    },
    chooseSimple() {
      const perm = (this.data.pub && this.data.pub.permission) || {};
      if (perm.canSimplePublish === false) {
        return toast('当前身份不可发布主题');
      }
      this._closeAndGo('/pages/publish/simple/index?mode=' + this.data.selectedMode + this._scopeSuffix());
    },
    choosePro() {
      const pub = this.data.pub || {};
      const perm = pub.permission || {};
      if (perm.canProPublish === false) {
        return toast('当前身份不可发布主题');
      }
      const q = pub.quota || {};
      if (q.maxThemes != null && q.themesRemaining === 0) {
        toast('在架已满，可先存草稿或下架旧项目');
      }
      this._closeAndGo('/pages/publish/fabu/index?mode=' + this.data.selectedMode + this._scopeSuffix());
    },

    // ===== 卡2 模板:先进第0页(价值主张),再逐步进入命名与编辑器 =====
    // 三步均保留页面栈,编辑器返回时能按原路逐级退出;商家作用域随 query 透传。
    goTemplate() {
      if (!this.data.pub) return toast('发布信息尚未就绪，请稍后重试');
      this._closeAndGo('/pages/publish/template-intro/index'
        + (this.properties.scope === 'MERCHANT' ? '?scope=MERCHANT' : ''));
    },

    _scopeSuffix() {
      return this.properties.scope === 'MERCHANT' ? '&scope=MERCHANT' : '';
    },

    // 先跳转、跳成功后再收菜单:原来先 close 再 navigateTo,玻璃层先退场、新页还没画出来,
    // 中间露出原页/白底闪一下。现在模糊菜单一直盖着,直到下一页上屏;页面栈行为不变。
    // 跳转失败则菜单保持打开并提示,用户可重试或点「返回」。
    _closeAndGo(url) {
      if (this._navigating) return;
      this._navigating = true;
      wx.navigateTo({
        url,
        success: () => this.triggerEvent('close'),
        fail: () => toast('跳转失败，请重试'),
        complete: () => { this._navigating = false; }
      });
    }
  }
}, onNativeBack));
