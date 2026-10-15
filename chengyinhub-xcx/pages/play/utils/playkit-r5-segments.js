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
    title: '相册', completionEnabled: true, done, uploaded: false,
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
    page._submitKitPhoto(detail, {
      name: 'SUBMIT_ALBUM', payload: url => ({ imageUrls: [url] }),
      onUploaded(url) {
        page._albumSubmission = { sessionId: kit.sessionId, version: kit.version, imageUrls: [url] };
        page.setData({ 'playKit.kit.uploaded': true });
      },
    });
    return;
  }
  const evidence = page._albumSubmission;
  if (!evidence || evidence.sessionId !== state.sessionId || evidence.version !== state.version) {
    toast('请先拍摄或选择照片'); return;
  }
  game.action('SUBMIT_ALBUM', { imageUrls: evidence.imageUrls });
}

module.exports = { isR5, feedbackBranch, pendingFeedback, canContinue, albumKit, feedbackWritable, submitAlbum };
