const CONSENT_VERSION = 'roam-presence-v1';
const DISCLOSURE = '开启后，附近 3 公里内同样开启分享的漫游用户可看到你的街区模糊位置（约 1 公里精度）、昵称、头像和本次漫游信息，并可查看主页或发私信。默认关闭；关闭后立即停止对他人展示，不影响自己的定位、打卡和轨迹。';

function request(app, method, data) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('位置分享状态暂时无法确认，请重试')), 10000);
    app.sendRequest({
      url: '/api/roam/presence-visibility', method, data,
      hideLoading: true, autoErrorToast: false,
      success(res) {
        clearTimeout(timer);
        if (res && (res.code === 200 || res.code === '200') && typeof res.data === 'boolean') resolve(res.data);
        else reject(new Error((res && res.msg) || '位置分享状态暂时无法确认，请重试'));
      },
      fail() { clearTimeout(timer); reject(new Error('位置分享状态暂时无法确认，请重试')); },
    });
  });
}

function readSharing(app, sessionId) {
  return request(app, 'GET', { sessionId: String(sessionId || '') });
}

function setSharing(app, sessionId, visible) {
  if (visible && !sessionId) return Promise.reject(new Error('请先开始漫游并移动，建立本次会话'));
  return request(app, 'POST', {
    sessionId: String(sessionId || ''), visible: !!visible,
    consentVersion: visible ? CONSENT_VERSION : '',
  }).then(result => {
    if (result !== !!visible) throw new Error('位置分享状态尚未确认，请重试');
    return visible ? readSharing(app, sessionId).then(saved => {
      if (!saved) throw new Error('位置分享状态尚未确认，请重试');
      return true;
    }) : false;
  });
}

module.exports = { CONSENT_VERSION, DISCLOSURE, readSharing, setSharing };
