'use strict';
// G-8 / M-12(owner 2026-10-27 m687、p82):标签分库 + 每个标签配图标。
// 三套预设互不混用:club 俱乐部 / merchant 商家(经营类目 + 特色标签)/ theme 主题。
// 图标沿用 10-22 经营类目那批 Lucide 同风格线稿(cy-tag-sheet/model.js 的 SHIPPED 同名文件),
// 文件放在用到它的分包里,不进主包:商家页用 MERCHANT_ICON_DIR,我的/公开主页用 MEMBER_ICON_DIR。
// 预设外的自由文字(旧数据、自定义标签)按关键字认领图标,认不出就不带图标,不猜。
// 样式口径(owner):实底、无灰色描边、比原来小一号 —— 见各页 .cy-lib-tag 样式。

const MERCHANT_ICON_DIR = '/pages/merchant/images/tags/';
const MEMBER_ICON_DIR = '/subpackageMember/images/tags/';
const ICONS = ['baby', 'bike', 'book-open', 'brush', 'camera', 'coffee', 'croissant', 'cup-soda', 'dices',
  'drama', 'flower-2', 'footprints', 'frame', 'game-handle', 'heart-pulse', 'landmark', 'moon', 'music',
  'noodles', 'outdoor', 'parachute', 'paw-print', 'platte', 'ripple', 'scissors', 'sport', 'tennis', 'tent',
  'viencharts', 'voice', 'weixin-market', 'wine'];

// [标签, 图标]。merchant 的「经营类目」与品牌中心 PREFERENCE_OPTIONS 同一份 28 词。
const SETS = {
  merchant: {
    经营类目: [['城市运动', 'sport'], ['户外活动', 'outdoor'], ['健康生活', 'heart-pulse'], ['美食', 'noodles'],
      ['咖啡', 'coffee'], ['茶饮', 'cup-soda'], ['烘焙甜品', 'croissant'], ['酒吧小酌', 'wine'], ['手作', 'brush'],
      ['亲子', 'baby'], ['夜生活', 'moon'], ['文化艺术', 'platte'], ['展览', 'frame'], ['书店阅读', 'book-open'],
      ['音乐现场', 'music'], ['剧本密室', 'drama'], ['桌游', 'dices'], ['电玩', 'game-handle'], ['摄影', 'camera'],
      ['城市漫步', 'footprints'], ['骑行', 'bike'], ['露营', 'tent'], ['瑜伽', 'viencharts'], ['球类', 'tennis'],
      ['水上运动', 'ripple'], ['花艺', 'flower-2'], ['美容美发', 'scissors'], ['宠物友好', 'paw-print']],
    空间: [['老街', 'landmark'], ['天台', 'outdoor'], ['独立空间', 'frame'], ['宠物友好', 'paw-print'],
      ['夜间开放', 'moon'], ['适合拍照', 'camera']],
    体验: [['适合单人', 'footprints'], ['适合情侣', 'heart-pulse'], ['适合亲子', 'baby'], ['适合组队', 'dices'],
      ['雨天可去', 'tent'], ['安静', 'book-open']],
    人群: [['学生党', 'book-open'], ['上班族', 'coffee'], ['摄影爱好者', 'camera'], ['美食探店', 'noodles'],
      ['亲子家庭', 'baby'], ['潮流青年', 'music']],
  },
  club: {
    俱乐部: [['城市漫步', 'footprints'], ['骑行', 'bike'], ['徒步户外', 'outdoor'], ['露营', 'tent'], ['摄影', 'camera'],
      ['桌游', 'dices'], ['剧本推理', 'drama'], ['读书会', 'book-open'], ['音乐', 'music'], ['运动健身', 'sport'],
      ['球类', 'tennis'], ['水上运动', 'ripple'], ['极限运动', 'parachute'], ['亲子', 'baby'], ['宠物', 'paw-print'],
      ['手作', 'brush'], ['美食', 'noodles'], ['咖啡', 'coffee'], ['夜生活', 'moon'], ['艺术', 'platte']],
  },
  theme: {
    主题: [['城市探索', 'landmark'], ['美食探店', 'noodles'], ['咖啡巡游', 'coffee'], ['历史人文', 'landmark'],
      ['文艺展览', 'frame'], ['夜游', 'moon'], ['亲子', 'baby'], ['解谜', 'dices'], ['沉浸剧情', 'drama'],
      ['摄影打卡', 'camera'], ['骑行', 'bike'], ['徒步', 'footprints'], ['户外', 'outdoor'], ['运动', 'sport'],
      ['音乐', 'music'], ['宠物', 'paw-print'], ['手作', 'brush'], ['购物', 'weixin-market']],
  },
};

// 预设外的自由文字:更具体的词放前面(与 cy-tag-sheet/model.js RULES 同口径)。
const RULES = [[/烘焙|甜品|面包/, 'croissant'], [/咖啡/, 'coffee'], [/茶饮|奶茶|饮品/, 'cup-soda'],
  [/酒吧|小酌|酒馆/, 'wine'], [/餐饮|美食|小吃/, 'noodles'], [/健康|养生/, 'heart-pulse'], [/瑜伽|普拉提/, 'viencharts'],
  [/球类|网球|羽毛球|篮球|足球/, 'tennis'], [/极限/, 'parachute'], [/游泳|水上/, 'ripple'], [/露营/, 'tent'],
  [/骑行|单车/, 'bike'], [/漫步|徒步|citywalk/i, 'footprints'], [/户外|旅行|出行/, 'outdoor'], [/运动|健身/, 'sport'],
  [/KTV|唱歌/i, 'voice'], [/音乐|演出|livehouse/i, 'music'], [/夜生活|夜游|夜间/, 'moon'], [/剧本|密室|沉浸|剧情/, 'drama'],
  [/桌游|解谜/, 'dices'], [/展览|画廊|美术馆/, 'frame'], [/书店|阅读|图书|读书/, 'book-open'], [/手作|DIY/i, 'brush'],
  [/艺术|文化/, 'platte'], [/亲子|儿童/, 'baby'], [/娱乐|电玩|游乐/, 'game-handle'], [/摄影|写真|拍照/, 'camera'],
  [/宠物/, 'paw-print'], [/花艺|花店/, 'flower-2'], [/美容|美发|美甲/, 'scissors'], [/景点|地标|博物馆|老街|历史/, 'landmark'],
  [/零售|购物|买手/, 'weixin-market']];

function kindOf(kind) { return SETS[kind] ? kind : 'merchant'; }

/** 某一类的预设:[{ group, tags: [{ label, icon }] }]。 */
function tagSet(kind) {
  const set = SETS[kindOf(kind)];
  return Object.keys(set).map(group => ({ group, tags: set[group].map(([label, icon]) => ({ label, icon })) }));
}

/** 标签名 → 图标名:先查本类预设,再按关键字认领;都不中返回 ''。 */
function tagIcon(label, kind) {
  const text = String(label == null ? '' : label).trim();
  if (!text) return '';
  const set = SETS[kindOf(kind)];
  for (const group of Object.keys(set)) {
    const hit = set[group].find(pair => pair[0] === text);
    if (hit) return hit[1];
  }
  const rule = RULES.find(pair => pair[0].test(text));
  return rule ? rule[1] : '';
}

/** 渲染用:标签名数组 → [{ label, iconSrc }];iconDir 由调用方按所在分包传入。 */
function decorateTags(labels, kind, iconDir) {
  const dir = iconDir || MERCHANT_ICON_DIR;
  return (Array.isArray(labels) ? labels : []).map(item => String(item == null ? '' : item).trim()).filter(Boolean)
    .map(label => {
      const icon = tagIcon(label, kind);
      return { label, iconSrc: icon && ICONS.indexOf(icon) >= 0 ? dir + icon + '.svg' : '' };
    });
}

module.exports = { MERCHANT_ICON_DIR, MEMBER_ICON_DIR, ICONS, tagSet, tagIcon, decorateTags };
