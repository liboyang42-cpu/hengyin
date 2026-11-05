'use strict';
const MAX_BYTES = 10 * 1024 * 1024;
function videoError(file) {
  if (!file || !/\.mp4(?:$|\?)/i.test(file.tempFilePath || '')) return '请选择 mp4 视频';
  if (!Number.isFinite(file.duration) || file.duration <= 0 || file.duration > 30) return '视频不能超过 30 秒';
  if (!Number.isFinite(file.size) || file.size <= 0 || file.size > MAX_BYTES) return '视频不能超过 10 MB';
  if (!file.thumbTempFilePath) return '无法读取视频封面，请更换文件';
  return '';
}
const methods = {
  uploadChapterVideo() {
    const app = getApp(), chapter = this.data.chapterForm;
    if (!chapter || (!chapter.opening && !chapter.ending)) return;
    const owner = String(app.getUserID() || ''), scope = this.data.operationScope;
    const token = this._chapterVideoToken = (this._chapterVideoToken || 0) + 1;
    const current = () => !this._unloaded && this.data.popChapter && chapter === this.data.chapterForm
      && token === this._chapterVideoToken && owner === String(app.getUserID() || '') && scope === this.data.operationScope;
    this.setData({ chapterVideoError: '' });
    wx.chooseMedia({ count: 1, mediaType: ['video'], sourceType: ['album', 'camera'], maxDuration: 30,
      success: res => {
        if (!current()) return;
        const file = res.tempFiles && res.tempFiles[0], error = videoError(file);
        if (error) { this.setData({ chapterVideoError: error }); return; }
        // Commit the pair only after both uploads succeed; an unsuccessful replacement preserves the old clip.
        app.getUploadClient().uploadOne(file.thumbTempFilePath, { formData: { fileName: 'poster.jpg', fileType: 'jpg' }, onDone: poster => {
          if (!current()) return;
          if (!poster.ok || !poster.data.assetId) { this.setData({ chapterVideoError: poster.msg || '封面上传失败，请重试' }); return; }
          app.getUploadClient().uploadOne(file.tempFilePath, { fileSize: file.size, timeoutMs: 60000,
            formData: { fileName: 'chapter.mp4', fileType: 'mp4' }, onDone: video => {
              if (!current()) return;
              if (!video.ok || !video.data.assetId) { this.setData({ chapterVideoError: video.msg || '视频上传失败，请重试' }); return; }
              this.setData({ 'chapterForm.videoUrl': 'media-asset:' + video.data.assetId,
                'chapterForm.videoPosterUrl': 'media-asset:' + poster.data.assetId,
                chapterVideoError: video.data.reviewState === 'APPROVED' ? '' : '视频已提交审核，通过后可发布' });
            } });
        } });
      },
      fail: err => { if (current() && !/cancel/.test(err.errMsg || '')) this.setData({ chapterVideoError: '无法选择视频，请重试' }); }
    });
  },
  clearChapterVideo() {
    this._chapterVideoToken = (this._chapterVideoToken || 0) + 1;
    this.setData({ 'chapterForm.videoUrl': '', 'chapterForm.videoPosterUrl': '', chapterVideoError: '' });
  },
  uploadThemeAudio() {
    const app = getApp(), form = this.data.formData, owner = String(app.getUserID() || ''), scope = this.data.operationScope;
    const target = JSON.stringify([this.data.editingTopicId, this.data.draftUuid, this._editLoadToken]);
    const token = this._themeAudioToken = (this._themeAudioToken || 0) + 1;
    app.chooseDocument(files => {
      if (this._unloaded || this.data.formData !== form || token !== this._themeAudioToken
          || owner !== String(app.getUserID() || '') || scope !== this.data.operationScope
          || target !== JSON.stringify([this.data.editingTopicId, this.data.draftUuid, this._editLoadToken])) return;
      if (files && files[0] && files[0].url) this.setData({ 'formData.audioUrl': files[0].url });
    }, 1, ['mp3', 'm4a', 'aac']);
  },
  clearThemeAudio() {
    this._themeAudioToken = (this._themeAudioToken || 0) + 1;
    this.setData({ 'formData.audioUrl': '', 'formData.audioDuration': 0 });
  }
};
module.exports = { videoError, methods };
