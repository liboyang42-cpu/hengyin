Component({
  properties: {
    show: { type: Boolean, value: false, observer(value) {
      if (value) this.setData({ cur: 0 });
    } },
    title: { type: String, value: '相册' },
    images: { type: Array, value: [] },
    completionEnabled: { type: Boolean, value: false },
    done: { type: Boolean, value: false },
    uploaded: { type: Boolean, value: false },
    uploadError: { type: String, value: '' },
    uploadRetryable: { type: Boolean, value: false },
  },
  data: { cur: 0 },
  methods: {
    onChange(e) { this.setData({ cur: Number(e.detail.cur) || 0 }); },
    close() { this.triggerEvent('close'); },
    onCta() {
      if (!this.data.completionEnabled || this.data.done) { this.close(); return; }
      // 已上传则提交；网络/5xx 失败则用同一张图重新提交上传（页面按可重试判定），4xx 只能重选
      if (this.data.uploaded || this.data.uploadRetryable) { this.triggerEvent('submit'); return; }
      this.choosePhoto();
    },
    choosePhoto() {
      if (!this.data.completionEnabled || this.data.done) return;
      // shortcut: 当前入口一次选择一张证据，需要多图相册时扩展现 uploadAll 批量参数。
      wx.chooseMedia({
        count: 1, mediaType: ['image'], sourceType: ['camera', 'album'],
        success: res => {
          const file = (res.tempFiles || [])[0];
          if (file && file.tempFilePath) this.triggerEvent('shoot', { tempFilePath: file.tempFilePath, size: file.size });
        },
      });
    },
  },
});
