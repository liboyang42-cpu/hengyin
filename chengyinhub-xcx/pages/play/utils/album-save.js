'use strict';
// E-E2:存相册被拒时先说明、去设置、回来自动重存。纯判定在这里,弹窗与 wx 调用在页面。

/** saveImageToPhotosAlbum 的 fail → 'cancel' 用户取消 / 'denied' 无相册权限 / 'fail' 其它 */
function saveFailKind(error) {
  const msg = String((error && error.errMsg) || '');
  if (msg.indexOf('cancel') >= 0) return 'cancel';
  if (msg.indexOf('auth') >= 0 || msg.indexOf('deny') >= 0) return 'denied';
  return 'fail';
}

/** wx.openSetting 回来后是否已开了「保存到相册」 */
function albumGranted(setting) {
  return !!(setting && setting.authSetting && setting.authSetting['scope.writePhotosAlbum'] === true);
}

module.exports = { saveFailKind, albumGranted };
