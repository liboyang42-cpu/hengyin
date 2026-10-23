/* 主题徽章(2026-10-22 业主):数据条第一格,文字在上、徽章在下;点开放大。
   金属反光跟随手机倾斜(参考 aethermaker 登机牌的金属着色器):放大层打开时才听陀螺仪,
   关掉立刻停 —— 不常驻耗电。没有陀螺仪(模拟器)时退化为 CSS 缓慢流光。
   数据只用主题自带的通关勋章(finishMedalName / finishMedalImg),没配就只说「通关后获得」,不编名字。 */
Component({
  options: { styleIsolation: 'isolated' },
  properties: {
    name: { type: String, value: '' },
    img: { type: String, value: '' },
    productType: { type: Number, value: 1 },
    reducedMotion: { type: Boolean, value: false },
  },
  data: { open: false, tilt: '', shine: '' },
  lifetimes: {
    detached() { this._stopMotion(); },
  },
  methods: {
    onOpen() {
      if (this.data.open) return;
      this.setData({ open: true, tilt: '', shine: '' });
      if (!this.data.reducedMotion) this._startMotion();
    },
    onClose() {
      this._stopMotion();
      this.setData({ open: false });
    },
    noop() {},
    _startMotion() {
      if (this._listening || typeof wx.startDeviceMotionListening !== 'function') return;
      const self = this;
      this._onMotion = function (res) {
        // beta 前后倾、gamma 左右倾;夹到 ±30° 再映射成反光位置和小幅倾斜
        const b = Math.max(-30, Math.min(30, Number(res && res.beta) || 0));
        const g = Math.max(-30, Math.min(30, Number(res && res.gamma) || 0));
        const x = 50 + g * 1.5, y = 30 + b * 1.2;
        const now = Date.now();
        if (self._last && now - self._last < 50) return; // 20fps 足够,setData 不追帧
        self._last = now;
        self.setData({
          tilt: 'transform: rotateY(' + (g * 0.6).toFixed(1) + 'deg) rotateX(' + (-b * 0.5).toFixed(1) + 'deg);',
          shine: '--mx:' + x.toFixed(0) + '%;--my:' + y.toFixed(0) + '%;--ma:' + (g * 3).toFixed(0) + 'deg;',
        });
      };
      wx.startDeviceMotionListening({
        interval: 'ui',
        success: () => { self._listening = true; wx.onDeviceMotionChange(self._onMotion); },
        fail: () => { self._listening = false; },
      });
    },
    _stopMotion() {
      if (!this._listening) return;
      this._listening = false;
      try { if (wx.offDeviceMotionChange) wx.offDeviceMotionChange(this._onMotion); } catch (e) {}
      try { wx.stopDeviceMotionListening({}); } catch (e) {}
    },
  },
});
