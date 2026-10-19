function isR5(view) {
  return !!(view && view.config && view.config.outcomeRulesVersion === 1);
}

function feedbackBranch(view) {
  if (!isR5(view)) return null;
  const branch = view.branch;
  return branch && branch.currentStep && branch.currentStep.id ? branch : null;
}

function pendingFeedback(view) {
  const branch = feedbackBranch(view);
  return !!(branch && !branch.ended && !branch.currentStep.terminal);
}

function canContinue(view) {
  const branch = feedbackBranch(view);
  return !!(view && view.readyForBase && !pendingFeedback(view)
    && (!branch || branch.writable !== false));
}

function albumKit(seg) {
  const done = seg.done === true;
  return {
    title: '相册', completionEnabled: true, done, uploaded: false, uploadError: '', uploadRetryable: false,
    // 完成前的 images 是作者配图；完成回执才是服务器验证过的玩家证据。
    authorImages: done ? [] : (seg.images || []),
    images: done ? (seg.images || []).map(url => ({ url })) : [],
  };
}

function feedbackWritable(view, kit) {
  const branch = feedbackBranch(view);
  return !!(branch && branch.writable === true && !branch.ended
    && !branch.currentStep.terminal && branch.currentStep.id === kit.stepId);
}

// 上传失败只有网络中断、超时与服务端 5xx 可原样重试；4xx/业务拒绝须重选照片
function albumRetryable(failure) { return !!(failure && failure.retryable === true); }

function uploadAlbumPhoto(page, kit, detail) {
  page._albumUpload = { sessionId: kit.sessionId, version: kit.version, detail };
  // 新一次上传（含重选）先作废尚未提交的旧证据，避免失败后误交旧图
  page._albumSubmission = null;
  page.setData({ 'playKit.kit.uploaded': false, 'playKit.kit.uploadError': '', 'playKit.kit.uploadRetryable': false });
  page._submitKitPhoto(detail, {
    name: 'SUBMIT_ALBUM', payload: url => ({ imageUrls: [url] }),
    onUploaded(url) {
      // 重选替换尚未提交的图片；已提交（done）后只读，不会走到这里
      page._albumSubmission = { sessionId: kit.sessionId, version: kit.version, imageUrls: [url] };
      page.setData({ 'playKit.kit.uploaded': true, 'playKit.kit.uploadError': '', 'playKit.kit.uploadRetryable': false });
    },
    onFailed(failure) {
      const retryable = albumRetryable(failure);
      page.setData({ 'playKit.kit.uploadError': (failure && failure.msg || '照片没传上去') + (retryable ? '，可重试上传' : '，请重新选择照片'), 'playKit.kit.uploadRetryable': retryable });
    },
  });
}

function submitAlbum(page, action, detail, toast) {
  const kit = page._kit();
  if (!kit.completionEnabled || kit.done) return;
  const game = page.selectComponent('#advancedGame');
  const state = game && game.data.state;
  if (!state || state.sessionId !== kit.sessionId || state.version !== kit.version) {
    toast('玩法状态已变化，请重进节点'); return;
  }
  if (game.data.acting || game.data.unknown) { toast('结果正在核对，请稍后再试'); return; }
  if (action === 'shoot') {
    if (!detail || !detail.tempFilePath) { toast('请先拍摄或选择照片'); return; }
    uploadAlbumPhoto(page, kit, detail);
    return;
  }
  if (action === 'submit' && kit.uploadRetryable && !kit.uploaded) {
    const last = page._albumUpload;
    if (!last || last.sessionId !== state.sessionId || last.version !== state.version) { toast('请重新选择照片'); return; }
    uploadAlbumPhoto(page, kit, last.detail);
    return;
  }
  const evidence = page._albumSubmission;
  if (!evidence || evidence.sessionId !== state.sessionId || evidence.version !== state.version) {
    toast('请先拍摄或选择照片'); return;
  }
  game.action('SUBMIT_ALBUM', { imageUrls: evidence.imageUrls });
}

module.exports = { isR5, feedbackBranch, pendingFeedback, canContinue, albumKit, feedbackWritable, submitAlbum, albumRetryable };
