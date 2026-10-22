'use strict';
// cy-tag-sheet 的纯逻辑(图标表 + 分页 + 点选),组件与门店信息页共用,单测直接测。
// 图标表(owner 2026-10-22 · L11 Figma「Edit Interests」胶囊)。
//
// 图标是 Figma 原样导出的 SVG,放在本分包 images/tags/ 下(主包已过 3.00MB 棘轮,不进主包)。
// SHIPPED 只列「文件真的已经入库」的名字:没入库的名字一律解析成空串,胶囊退回无图标排版
// (文字起点不变),不会去请求一张不存在的图。资源入库时把名字补进 SHIPPED,
// tests/unit/merchant-tag-sheet.test.js 会逐个核对文件存在。
const ICON_DIR = '/pages/merchant/images/tags/';
const SHIPPED = [];

// 标签名 → 图标文件名(按关键字认领,先命中先得;都不命中 = 无图标)。
// 行业类型短名与经营类目词库共用一张表,owner 给的映射见 PR 说明。
const RULES = [
  { icon: 'noodles', match: /餐饮|美食/ },
  { icon: 'goblet-full', match: /咖啡|茶饮|饮品|酒吧/ },
  { icon: 'viencharts', match: /瑜伽|普拉提|健康生活/ },
  { icon: 'tennis', match: /球类|网球|羽毛球|篮球|足球/ },
  { icon: 'parachute', match: /极限/ },
  { icon: 'ripple', match: /游泳|水上/ },
  { icon: 'outdoor', match: /户外|露营|旅行|出行/ },
  { icon: 'sport', match: /运动|健身/ },
  { icon: 'voice', match: /KTV|唱歌/i },
  { icon: 'music', match: /音乐|演出|夜生活/ },
  { icon: 'platte', match: /艺术|手作|文化/ },
  { icon: 'game-handle', match: /娱乐|电玩|游乐|亲子/ },
  { icon: 'camera', match: /摄影/ },
  { icon: 'weixin-market', match: /零售|购物/ },
];

/** 标签名 → 图标文件名(未映射返回 '')。与是否已入库无关,纯映射。 */
function tagIconName(label) {
  const text = String(label == null ? '' : label);
  const hit = RULES.filter(r => r.match.test(text))[0];
  return hit ? hit.icon : '';
}

/** 图标文件名 → 可渲染的路径;未入库返回 ''。 */
function tagIconSrc(name) {
  return name && SHIPPED.indexOf(name) >= 0 ? ICON_DIR + name + '.svg' : '';
}

/** items + 选中 key → 分页后的胶囊数据。纯函数,单测直接测。 */
function buildPages(items, selected, rows, cols) {
  const per = Math.max(1, (Number(rows) || 4) * (Number(cols) || 2));
  const sel = (selected || []).map(String);
  const list = (Array.isArray(items) ? items : []).filter(it => it && it.key != null && it.key !== '').map(it => ({
    key: String(it.key),
    label: String(it.label == null ? it.key : it.label),
    iconSrc: tagIconSrc(it.icon),
    checked: sel.indexOf(String(it.key)) >= 0,
  }));
  const pages = [];
  for (let i = 0; i < list.length; i += per) pages.push(list.slice(i, i + per));
  return pages;
}

/** 点选一次后的新选中数组;到上限返回 null(调用方弹 toast)。 */
function toggleValue(selected, key, max) {
  const sel = (selected || []).map(String);
  const k = String(key);
  const at = sel.indexOf(k);
  if (at >= 0) return sel.slice(0, at).concat(sel.slice(at + 1));
  if (max > 0 && sel.length >= max) return null;
  return sel.concat(k);
}

module.exports = { ICON_DIR, SHIPPED, RULES, tagIconName, tagIconSrc, buildPages, toggleValue };
