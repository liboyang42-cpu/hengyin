// 2026-10-22 owner:搜索结果里的模板要和模板页长得一样。
// utils/template-art.js 的玩法图标表必须与模板页 INTERACTION_ICON_BY_TITLE 逐条一致,结果行用模板页同一套 .cml-* 样式。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');

// 2026-10-28:模板页货架改用分包内的 Polyfork 道具图(pages/templatedetail/shelf/art.js),
// 主包模板页不再有 INTERACTION_ICON_BY_TITLE。搜索结果所在分包取不到那批图，继续用这张 SVG 表;
// 这里守住它自身成立：每个图标文件都真实存在、且都对应货架上保留的玩法。
test('搜索用的玩法图标表：文件都存在，标题都是货架保留的玩法', () => {
  const { INTERACTION_ICON_BY_TITLE } = require('../../utils/template-art.js');
  const shelfArt = require('../../pages/templatedetail/shelf/art.js');
  Object.entries(INTERACTION_ICON_BY_TITLE).forEach(([title, icon]) => {
    assert.ok(fs.existsSync(path.join(ROOT, icon)), icon + ' 不存在');
    if (!/^G\d+｜/.test(title)) assert.ok(shelfArt.GAMES[title], title + ' 不在货架保留清单里');
  });
});

test('模板结果按模板页行样式画:具象图标优先,meta 为人数 · 时长', () => {
  const discover = require('../../utils/discover-search.js');
  const game = discover.decorateItem('gameTemplate', { id: 9, title: '今晚的暗号', players: '1-4 人', duration: 15 });
  assert.equal(game.tplRow, true);
  assert.equal(game.art, '/images/interaction-templates/merchant-secret-code.svg');
  assert.equal(game.detail, '1-4 人 · 15分钟');
  const theme = discover.decorateItem('topicTemplate', { id: 3, name: '咖啡探店月', subtitle: '自由探索' });
  assert.equal(theme.tplRow, true);
  assert.equal(theme.art, '');
  assert.equal(discover.decorateItem('club', { id: 1, name: '城市乐居人' }).tplRow, false);
  // owner 2026-10-22:搜索里的模板是大卡,和「我的订单」卡同一套
  const wxml = read('pages/search2/result/index.wxml');
  assert.match(wxml, /wx:elif="\{\{item\.tplRow\}\}" class="tpl-big"/);
  assert.match(wxml, /<image wx:if="\{\{item\.cover\}\}" class="tpl-big__img"/, '有封面优先铺封面');
  const wxss = read('pages/search2/result/index.wxss');
  const order = read('subpackageMember/components/cy/profile/index.wxss');
  const decl = (src, sel, prop) => {
    const start = src.indexOf(sel + ' {');
    const body = src.slice(start, src.indexOf('}', start));
    const m = body.match(new RegExp('(?:^|[;{\\s])' + prop + ':\\s*([^;]+);'));
    return m && m[1].trim();
  };
  [['.tpl-big', '.pc-ord-card', 'height'], ['.tpl-big', '.pc-ord-card', 'border-radius'], ['.tpl-big', '.pc-ord-card', 'background'],
    ['.tpl-big__shade', '.pc-ord-shade', 'background'], ['.tpl-big__shade', '.pc-ord-shade', 'height'],
    // 2026-10-27 owner 真机走查 P-48:「我的订单」卡去掉了「出示核销码/查看」按钮(.pc-ord-btn 已删),按钮一项不再比对
    ['.tpl-big__title', '.pc-ord-title', 'font-size']].forEach(([mine, theirs, prop]) => {
    assert.equal(decl(wxss, mine, prop), decl(order, theirs, prop), `${mine} ${prop} 与订单卡一致`);
  });
});
