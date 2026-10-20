'use strict';

// F-L6-09 更新提示 + 失效页兜底;app.js 接线见 CY-HANDOFF(#328)。
const { installAppUpdate, promptUpdateIfAvailable } = require('./app-update.js');

// = app.json pages[0](单测对齐)
const HOME_PAGE = 'pages/index/index';
let pageNotFoundHandled = false;

function wxOrNull(wxApi) {
  return wxApi || (typeof wx !== 'undefined' ? wx : null);
}

// 与既有 installAppUpdate(wx) 共用管理器,重复安装幂等。
function installUpdateManager(app, wxApi) {
  if (app) app.promptUpdateIfAvailable = promptUpdateIfAvailable;
  return installAppUpdate(wxOrNull(wxApi));
}

// 旧分享卡/下线页回首页;每次启动一次、首页本身缺失不跳,防循环。
function handlePageNotFound(res, wxApi) {
  const api = wxOrNull(wxApi);
  const missing = String((res && res.path) || '').replace(/^\//, '');
  if (pageNotFoundHandled || missing === HOME_PAGE || !api || typeof api.reLaunch !== 'function') return false;
  pageNotFoundHandled = true;
  api.reLaunch({ url: '/' + HOME_PAGE });
  return true;
}

module.exports = { HOME_PAGE, installUpdateManager, promptUpdateIfAvailable, handlePageNotFound };
