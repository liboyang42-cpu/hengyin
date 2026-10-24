'use strict';

const installed = new WeakSet();
// 426「需更新」可借此补弹同一重启提示(每次启动至多一次)。
let promptCurrent = () => false;

function installAppUpdate(wxApi) {
  if (!wxApi || typeof wxApi.getUpdateManager !== 'function') return false;
  if (typeof wxApi.canIUse === 'function' && !wxApi.canIUse('getUpdateManager')) return false;
  let manager;
  try { manager = wxApi.getUpdateManager(); } catch (error) { return false; }
  if (!manager || typeof manager.onUpdateReady !== 'function'
      || typeof manager.onUpdateFailed !== 'function' || typeof manager.applyUpdate !== 'function') return false;
  if (installed.has(manager)) return true;
  installed.add(manager);
  let readyHandled = false;
  let failureHandled = false;
  let modalHandled = false;
  let modalShown = false;
  function promptRestart() {
    if (modalShown || typeof wxApi.showModal !== 'function') return false;
    try {
      wxApi.showModal({
        title: '新版本已就绪',
        content: '请先保存未保存的内容。立即重启小程序后，新版本才会生效。',
        confirmText: '立即重启', cancelText: '稍后',
        success(result) {
          if (modalHandled) return;
          modalHandled = true;
          if (!result || result.confirm !== true || result.cancel === true) return;
          try { manager.applyUpdate(); } catch (error) {
            showFailure('新版本未能启用，请稍后重新打开小程序');
          }
        },
      });
    } catch (error) { return false; /* 下载就绪不能替代用户明确确认。 */ }
    modalShown = true;
    return true;
  }
  promptCurrent = () => readyHandled && promptRestart();
  try {
    manager.onUpdateReady(() => {
      if (readyHandled) return;
      readyHandled = true;
      promptRestart();
    });
    manager.onUpdateFailed(() => showFailure('新版本下载失败，请稍后重新打开小程序'));
  } catch (error) { return false; }
  function showFailure(title) {
    if (failureHandled) return;
    failureHandled = true;
    if (typeof wxApi.showToast !== 'function') return;
    try { wxApi.showToast({ title, icon: 'none' }); } catch (error) {}
  }
  return true;
}

// 已就绪且未弹过才弹;true = 已弹出。
function promptUpdateIfAvailable() { return promptCurrent(); }

module.exports = { installAppUpdate, promptUpdateIfAvailable };
