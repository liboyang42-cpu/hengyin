// P-60(2026-10-27):故事流正文与对白用中文手写体。
//
// 只换故事流正文 / 对白(样式类 .cy-story-hand,写在 pages/play 与编辑器预览各自的 wxss 里),
// 标题、按钮、数字不换。字体不进包:官方完整字库约 5.15 MB,首次下载较大。
// 这里按官方 CDN URL 用 wx.loadFontFace 在线加载,失败保留系统字体。
//
// STORY_FONT_URL 为空 = 不加载;加载失败也不报错 —— 样式类的 font-family 后面跟着系统字体,
// 认不出 STORY_FONT_FAMILY 时自动回落,玩家看到的就是原来的字。
// 官方 Google Fonts 文件公开可读,CORS=*;保持 Long Cang 字族与 SIL OFL 1.1 署名。
//
// 主包已满(3 MiB 发版棘轮),所以不放 utils/:pages/play/utils 与 pages/publish/utils/publish
// 两个分包各留一份逐字相同的副本(分包之间不能互相 require),tests/unit/story-font-loader.test.js 钉住一致。

var STORY_FONT_FAMILY = 'CyStoryHand';
var STORY_FONT_URL = 'https://fonts.gstatic.com/s/longcang/v21/LYjAdGP8kkgoTec8zkRgrQ.ttf'; // 官方完整 Long Cang TTF(SIL OFL 1.1);首次下载较子集大,失败保持系统字体回落

var state = 'idle'; // idle | loading | loaded | failed
var waiters = [];

function settle(next) {
  state = next;
  var list = waiters;
  waiters = [];
  list.forEach(function (cb) { try { cb(next === 'loaded'); } catch (e) {} });
}

/**
 * 加载故事流手写体(整个小程序只加载一次,global:true 让分包页面也能用)。
 * @param {function(boolean)=} done 加载结束回调,true = 手写体可用
 */
function loadStoryFont(done) {
  if (typeof done === 'function') {
    if (state === 'loaded' || state === 'failed') { done(state === 'loaded'); return; }
    waiters.push(done);
  }
  if (state !== 'idle') return;
  if (!/^https:\/\//.test(STORY_FONT_URL) || typeof wx === 'undefined' || typeof wx.loadFontFace !== 'function') {
    settle('failed');
    return;
  }
  state = 'loading';
  try {
    wx.loadFontFace({
      global: true,
      family: STORY_FONT_FAMILY,
      source: 'url("' + STORY_FONT_URL + '")',
      desc: { style: 'normal', weight: 'normal' },
      success: function () { settle('loaded'); },
      fail: function () { settle('failed'); },
    });
  } catch (e) {
    settle('failed');
  }
}

module.exports = {
  STORY_FONT_FAMILY: STORY_FONT_FAMILY,
  STORY_FONT_URL: STORY_FONT_URL,
  loadStoryFont: loadStoryFont,
};
