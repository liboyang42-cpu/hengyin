const modal = require('../../../../utils/modal.js');

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
    // '' | 'retry'（重传同一张）| 'review_pending'（查看/重试原资产审核）| 'rejected'（审核未通过，重新选图）| 'reselect'（重新选图）
    uploadFailure: { type: String, value: '' },
  },
  data: { cur: 0 },
  methods: {
    onChange(e) { this.setData({ cur: Number(e.detail.cur) || 0 }); },
    close() { this.triggerEvent('close'); },
    onCta() {
      if (!this.data.completionEnabled || this.data.done) { this.close(); return; }
      if (this.data.uploadFailure === 'review_pending') {
        modal.show({ title: '查看/重试审核', content: '照片尚未提交相册。将查看原照片的审核结果；若已被拒绝或暂不可用，可能重新提交审核。审核批准后才会提交相册。', confirmText: '查看重试',
          success: res => {
            if (res.confirm && this.data.completionEnabled && !this.data.done
                && this.data.uploadFailure === 'review_pending') this.triggerEvent('submit');
          } });
        return;
      }
      if (this.data.uploaded || this.data.uploadFailure === 'retry') { this.triggerEvent('submit'); return; }
      this.choosePhoto();
    },
    choosePhoto() {
      if (!this.data.completionEnabled || this.data.done) return;
      if (this.data.uploadFailure === 'review_pending') { this.onCta(); return; }
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
