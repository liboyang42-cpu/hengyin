// 相机/相册被系统拒绝(微信不会再弹授权框)时给一张「去设置」。取消选图不算。写法同 play-location-once.js 的 GUIDE。
const cyModal = require('../../../utils/modal.js');

const GUIDE = {
  title: '打开相机和相册',
  content: '点右上角「···」→「设置」，打开「摄像头」和「相册」。',
  confirmText: '去设置',
  cancelText: '取消',
};

function deniedBySystem(err) {
  return /auth|deny|denied|authorize|permission/i.test(String((err && err.errMsg) || ''));
}

/** chooseMedia 的 fail 回调用:被拒就弹引导并返回 true;取消/其它返回 false */
function guideIfDenied(err) {
  if (!deniedBySystem(err)) return false;
  cyModal.show(Object.assign({}, GUIDE, {
    success(r) { if (r && r.confirm && typeof wx.openSetting === 'function') wx.openSetting({ fail() {} }); },
  }));
  return true;
}

module.exports = { GUIDE, deniedBySystem, guideIfDenied };
