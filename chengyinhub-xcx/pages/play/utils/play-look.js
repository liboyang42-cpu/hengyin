// 主题画面风格在玩家页的两个入口(theme-look.js 读 / look-transition 转场)。从 index.js 抽出:
// 那个文件的 UI 门禁基线按行号登记,新方法放这里,index.js 只留单行挂钩,行号不动。
const themeLook = require('./theme-look.js');

function methods() {
  // 写成 const 对象再返回:台账 / 处理器门禁(xcx-scan)只扫模块里 `const X = {` 的键
  const api = {
    /** 读不懂的值回落默认(星尘 + 光圈 + 手写),老主题外观不变。 */
    _applyLook(raw) {
      const look = themeLook.normalizeLook(raw);
      this._look = look;
      this._lookKey = JSON.stringify(look);
      this.setData({ lookClass: themeLook.lookClass(look), lookTransition: themeLook.resolveTransition(look) });
    },
    /** 用风格的转场盖住画面,在完全盖住时执行 swap(换内容),再揭开;转场层不在 / 减动效时直接执行。 */
    _withLookTransition(swap) {
      const tx = this.data.reducedMotion || typeof this.selectComponent !== 'function' ? null : this.selectComponent('#lookTx');
      if (!tx) { swap(); return; }
      tx.run(swap);
    },
  };
  return api;
}

module.exports = { methods };
