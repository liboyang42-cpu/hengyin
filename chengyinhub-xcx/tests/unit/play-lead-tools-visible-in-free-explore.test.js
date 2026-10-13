const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const WXML = fs.readFileSync(
  path.join(__dirname, '../../pages/play/index.wxml'), 'utf8');

const LEAD_ROWS = [
  ['leadBroadcastFromTools', '队伍位置'],
  ['leadVerifyMemberTicketFromTools', '扫成员票核销'],
  ['leadShowGroupCodeFromTools', '展示整团码'],
  ['leadUnlockFromTools', '解锁下一章节'],
  ['leadSettleFromTools', '整团结算'],
];

/** 抽出游玩工具菜单(scroll-view.tools)的整段内容。2026-09-22 起它从底栏抽屉挪到故事流右上角。 */
function toolsDrawer() {
  const start = WXML.indexOf('<scroll-view class="tools');
  assert.notEqual(start, -1, '找不到上滑工具抽屉');
  const end = WXML.indexOf('</scroll-view>', start);
  assert.notEqual(end, -1, '工具抽屉没有闭合');
  return WXML.slice(start, end);
}

/** 抽出 mode==2(自由探索)那个分支,用来证明领队行不在它里面。 */
function freeExploreBranch(drawer) {
  const start = drawer.indexOf('<block wx:if="{{mode==2}}">');
  assert.notEqual(start, -1, '找不到自由探索分支');
  const end = drawer.indexOf('</block>', start);
  return drawer.slice(start, end);
}

// 2026-09-22 用户裁决:底栏三键与工具抽屉整体挪到故事流右上角(底栏 .pbar 已删)。
// F-57 要钉的事不变 —— 领队在任何模式下都够得着工具:入口那颗按钮不许挂模式条件。
test('FREE_EXPLORE 领队也够得着工具:右上角菜单按钮不按模式判', () => {
  const btn = WXML.match(/<view class="fx-story__act"([^>]*)bindtap="toggleSessionTools"/);
  assert.ok(btn, '找不到故事流右上角的游玩工具按钮');
  assert.doesNotMatch(btn[1], /wx:if=/, '菜单按钮挂了条件 ⇒ 某个模式的领队发车后没有任何入口(F-57)');
  assert.doesNotMatch(WXML, /<view class="pbar[ "]/, '底栏已挪走,不许两套入口并存');
});

test('五行领队工具挂在模式分支之外,只由 lead 判', () => {
  const drawer = toolsDrawer();
  const free = freeExploreBranch(drawer);
  for (const [handler, label] of LEAD_ROWS) {
    const row = drawer.match(
      new RegExp(`<view class="drow"[^>]*wx:if="\\{\\{([^"]+)\\}\\}"[^>]*bindtap="${handler}"`));
    assert.ok(row, `领队行 ${label} 不见了`);
    assert.match(row[1], /lead\.exists/, `${label} 必须自己守 lead.exists`);
    assert.ok(!free.includes(handler),
      `${label} 还关在 mode==2 分支里 ⇒ 另一模式够不着(F-57)`);
  }
});
