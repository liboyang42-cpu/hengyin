'use strict';
// U04-03 / §27.2(BE-07):开局时声明本端真正会渲染的内容能力。
//
// ★ 只登记本小程序已有渲染与提交路径的能力。键名与服务端 ContentCapabilityRequirements 推导的需求逐字对齐;
//   服务端从冻结内容推导需求,声明里缺了必需项就在开局前 409 CONTENT_CAPABILITY_UNSUPPORTED,
//   不会让玩家进到半截才发现某块内容看不见。
// ★ 玩法段(game.*)不手抄:直接取玩家端分发表 playkit-view TYPE_OF 的键 —— 渲染器加一个段,声明就跟着多一个;
//   没有渲染器的段(如 roleViews、团队共解)不在表里,也就不声明。
// ★ 未声明 ≠ 声明为空:本模块总是给出非空声明;旧入口不调用本模块时请求里根本不带这三个字段。
const { TYPE_OF } = require('./playkit-view.js');

const VERSION = 1;
const PLATFORM = 'wechat-miniprogram';

/** 故事流块:pages/play/index.js buildChapterParas 逐类渲染(node/text/image/audio/dream/mood/reveal/odd/voice/thought/divider)。 */
const STORY_BLOCKS = ['node', 'text', 'image', 'audio', 'dream', 'mood', 'reveal', 'odd', 'voice', 'thought', 'divider'];

/** 完成方式:normNode 认 0–6;8/9/10 由同名玩法段渲染、到达收尾;7(App 传感器)本端没有提交入口,不声明。 */
const ANSWER_METHODS = ['answer.arrive', 'answer.text', 'answer.photo', 'answer.choice', 'answer.scan', 'answer.gps',
  'answer.preference'];
const SECTION_ANSWERS = { estimate: 'answer.estimate', pricePair: 'answer.pricePair', hiddenObject: 'answer.hiddenObject' };

const BASE = ['chapter.blocks', 'station.list', 'media.image', 'media.audio', 'media.photo', 'location.gps',
  'route.branch', 'theme.runtime'];

function keys() {
  const out = new Set(BASE);
  STORY_BLOCKS.forEach(type => out.add('chapter.blocks.' + type));
  ANSWER_METHODS.forEach(key => out.add(key));
  Object.keys(TYPE_OF).forEach(section => {
    out.add('game.' + section.toLowerCase());
    if (SECTION_ANSWERS[section]) out.add(SECTION_ANSWERS[section]);
  });
  return Array.from(out).sort();
}

function clientVersion() {
  try {
    const info = typeof wx !== 'undefined' && wx.getAccountInfoSync ? wx.getAccountInfoSync() : null;
    const version = info && info.miniProgram && info.miniProgram.version;
    if (typeof version === 'string' && /^[0-9A-Za-z._-]{1,32}$/.test(version)) return version;
    return info && info.miniProgram && info.miniProgram.envVersion ? String(info.miniProgram.envVersion) : '';
  } catch (error) { return ''; }
}

/** orient prepare/start 的表单字段与 advanced/start 的 JSON 字段同形:key@version 逗号串。 */
function declaration() {
  return {
    clientPlatform: PLATFORM,
    clientVersion: clientVersion(),
    supportedCapabilities: keys().map(key => key + '@' + VERSION).join(','),
  };
}

const UNSUPPORTED = 'CONTENT_CAPABILITY_UNSUPPORTED';
/** 服务端判定本端缺必需能力:原样给服务端说明,补一句可执行的下一步;不跳关、不删内容、不本地降级。 */
function unsupportedNotice(body) {
  if (!body || (body.err !== UNSUPPORTED && body.errorCode !== UNSUPPORTED
      && !(body.data && body.data.errorCode === UNSUPPORTED))) return '';
  return '当前版本无法完整呈现本局内容，请更新微信后重新进入';
}

module.exports = { declaration, keys, unsupportedNotice, STORY_BLOCKS, PLATFORM, VERSION, UNSUPPORTED };
