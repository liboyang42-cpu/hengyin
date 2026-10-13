const { fitModelOf } = require('../../../pages/play/components/playkit-scan/ar/ar-math.js');
Component({
  data: { angle: 0 },
  properties: { src: { type: String, value: '' } },
  lifetimes: {
    attached() {
      this._alive = true;
      this._loadTimer = setTimeout(() => this.handleError(), 15000);
    },
    detached() { this._alive = false; clearTimeout(this._loadTimer); this.scene = null; },
  },
  methods: {
    handleReady(event) { if (this._alive) this.scene = event.detail.value; },
    handleLoaded() {
      if (!this._alive) return;
      try {
        const sys = wx.getXrFrameSystem && wx.getXrFrameSystem();
        const node = sys && this.scene && this.scene.getNodeById('author-model-node');
        const model = node && node.getComponent(sys.GLTF);
        if (!model) throw new Error('model unavailable');
        const box = model.calcTotalBoundBox();
        if (!box || !['x', 'y', 'z'].every(key => Number.isFinite(box.center[key]) && Number.isFinite(box.size[key]) && box.size[key] >= 0)
            || !(Math.max(box.size.x, box.size.y, box.size.z) > 0)) throw new Error('invalid model bounds');
        const fitted = fitModelOf(box.center, box.size, 1.5);
        const transform = node.getComponent(sys.Transform);
        transform.scale.setValue(fitted.scale, fitted.scale, fitted.scale);
        transform.position.setValue(fitted.x, fitted.y, fitted.z);
        clearTimeout(this._loadTimer);
      } catch (error) { this.handleError(); }
    },
    rotate() { if (this._alive) this.setData({ angle: (this.data.angle + 45) % 360 }); },
    handleError() {
      if (!this._alive) return;
      clearTimeout(this._loadTimer);
      this.triggerEvent('error');
    },
  },
});
