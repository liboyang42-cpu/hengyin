// P-60(2026-10-27):故事流正文与对白用中文手写体。
//
// 只换故事流正文 / 对白(样式类 .cy-story-hand,写在 pages/play 与编辑器预览各自的 wxss 里),
// 标题、按钮、数字不换。字体不进包:完整字库约 5.15 MB,首次下载较大。
// 第十一轮 g4(j3 F19):谷歌字体 CDN 在国内不可靠,改由调用方传入 utils/config.js 的 storyFontUrl
// (我们自己的静态地址,上传与 CORS 见 docs/发版checklist.md「故事手写体」)。
//
// 地址为空或不是 https = 不加载;加载失败也不报错 —— 样式类的 font-family 后面跟着系统字体,
// 认不出 STORY_FONT_FAMILY 时自动回落,玩家看到的就是原来的字。保持 Long Cang 字族与 SIL OFL 1.1 署名。
//
// 主包已满(3 MiB 发版棘轮),所以不放 utils/:pages/play/utils 与 pages/publish/utils/publish
// 两个分包各留一份逐字相同的副本(分包之间不能互相 require),tests/unit/story-font-loader.test.js 钉住一致。

var STORY_FONT_FAMILY = 'CyStoryHand';

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
 * @param {string} url 字体地址(utils/config.js storyFontUrl);空 = 用系统字体
 * @param {function(boolean)=} done 加载结束回调,true = 手写体可用
 */
function loadStoryFont(url, done) {
  url = String(url || '').trim();
  if (typeof done === 'function') {
    if (state === 'loaded' || state === 'failed') { done(state === 'loaded'); return; }
    waiters.push(done);
  }
  if (state !== 'idle') return;
  if (!/^https:\/\//.test(url) || typeof wx === 'undefined' || typeof wx.loadFontFace !== 'function') {
    settle('failed');
    return;
  }
  state = 'loading';
  try {
    wx.loadFontFace({
      global: true,
      family: STORY_FONT_FAMILY,
      source: 'url("' + url + '")',
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
  loadStoryFont: loadStoryFont,
};
