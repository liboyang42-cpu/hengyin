const avatar = require('../../../utils/pixel-avatar.js');
Component({
  properties: { src: { type: String, value: '' } },
  data: { preset: false, failed: false, renderedSrc: '' },
  observers: {
    src: function (src) {
      this.setData({ preset: avatar.isPixelAvatar(src), failed: false, renderedSrc: '' }, () => {
        if (this._ready) this.draw();
      });
    },
  },
  lifetimes: {
    ready() {
      this._ready = true;
      this.draw();
    },
    detached() {
      this._ready = false;
    },
  },
  methods: {
    fail() {
      this.setData({ failed: true });
    },
    draw() {
      if (!this.data.preset) return;
      const src = this.data.src;
      this.createSelectorQuery()
        .select('#avatar')
        .fields({ node: true })
        .exec((result) => {
          if (!this._ready || src !== this.data.src) return;
          const cv = result && result[0] && result[0].node;
          if (!cv) return this.fail();
          cv.width = 192;
          cv.height = 192;
          avatar.drawAvatarInBox(cv.getContext('2d'), src, 0, 0, 192, 192);
          // 普通图片随滚动容器移动，避免原生画布停留在初始位置。
          wx.canvasToTempFilePath({
            canvas: cv,
            fileType: 'png',
            success: (result) => {
              if (this._ready && src === this.data.src) this.setData({ renderedSrc: result.tempFilePath });
            },
            fail: () => {
              if (this._ready && src === this.data.src) this.fail();
            },
          });
        });
    },
  },
});
