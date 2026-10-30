// 主题画面风格在玩家页的两个入口(theme-look.js 读 / look-transition 转场)。从 index.js 抽出:
// 那个文件的 UI 门禁基线按行号登记,新方法放这里,index.js 只留单行挂钩,行号不动。
const themeLook = require('./theme-look.js');

function methods() {
  // 写成 const 对象再返回:台账 / 处理器门禁(xcx-scan)只扫模块里 `const X = {` 的键
  const api = {
    /** 读不懂的值回落默认(星尘 + 光圈 + 手写),老主题外观不变。 */
    _applyLook(raw) {
      // 没配过风格的老主题:标 legacy,星尘的新银河不套上去,原来的浮尘与场景光照旧
      const look = Object.assign(themeLook.normalizeLook(raw), raw ? {} : { legacy: true });
      this._look = look;
      this._lookKey = JSON.stringify(look);
      this.setData({ lookClass: themeLook.lookClass(look), lookTransition: themeLook.resolveTransition(look) });
    },
    /** 主题配色设了就统一用它,没设(老主题)各章沿用自己的章节配色。 */
    _themePalette(chapterPreset) {
      return (this._look && this._look.palette) || chapterPreset;
    },
    /** 末世每章的时间段(清晨 / 正午 / 日落 / 夜晚):按章节顺序取,只在末世里生效;没设 = 不加。 */
    _chapterTime(i) {
      const look = this._look, t = look && look.style === 'SURVIVAL' && look.times && look.times[i];
      return t ? ' look-time--' + t.toLowerCase() : '';
    },
    /** 雷暴打闪:字跟着亮一下(远景画布已经整屏闪过);同一道闪只照一次。 */
    _lookLightUp(flashAt) {
      if (!flashAt || flashAt === this._litAt) return;
      this._litAt = flashAt;
      this.setData({ lookLit: true });
      clearTimeout(this._litTimer);
      this._litTimer = setTimeout(() => this.setData({ lookLit: false }), 420);
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
