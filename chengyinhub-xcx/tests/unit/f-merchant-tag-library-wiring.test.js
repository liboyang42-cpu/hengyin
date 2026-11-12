'use strict';
// f-merchant(G-8 / M-12 收尾,2026-11-11):俱乐部「活动倾向」与快速配置的主题兴趣接入标签库。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const lib = require(path.join(ROOT, 'utils/tag-library.js'));
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');

test('俱乐部活动倾向:库里一份,创建与编辑两处都从库取,不再各抄一份', () => {
  assert.deepEqual(lib.tagLabels('club', '活动倾向'),
    ['轻社交', '深度社交', 'RPG体验', '城市定向', '解谜路线', '沉浸式剧情', '运动路线', '艺术体验', '美食体验', '主题聚会'],
    '词与顺序沿用原两页(后端按原词存 activityPrefs)');
  for (const rel of ['pages/club/create/index.js', 'pages/club/components/cy/scene-club-edit/index.js']) {
    const js = read(rel);
    assert.match(js, /tagLibrary\.tagLabels\('club', '活动倾向'\)/, rel);
    assert.doesNotMatch(js, /\['轻社交', '深度社交'/, rel + ' 不得再写死选项');
  }
  assert.match(read('pages/club/create/index.wxml'), /<image wx:if="\{\{item\.icon\}\}" class="cc-chip-ico"/);
  assert.match(read('pages/club/components/cy/scene-club-edit/index.wxml'), /<image wx:if="\{\{dirIcons\[item\]\}\}" class="chip-ico"/);
});

test('图标文件放在使用它的分包里,且每个库标签都有图', () => {
  const check = (kind, group, dir) => lib.tagSet(kind).filter((g) => !group || g.group === group).forEach((g) => g.tags.forEach((t) => {
    const deco = lib.decorateTags([t.label], kind, dir)[0];
    assert.ok(deco.iconSrc, t.label + ' 没有图标');
    assert.ok(fs.existsSync(path.join(ROOT, deco.iconSrc)), deco.iconSrc + ' 不存在');
  }));
  check('club', '活动倾向', lib.CLUB_ICON_DIR);
  check('theme', null, lib.PUBLISH_ICON_DIR);
  assert.ok(lib.CLUB_ICON_DIR.startsWith('/pages/club/'));
  assert.ok(lib.PUBLISH_ICON_DIR.startsWith('/pages/publish/'));
});

test('快速配置:兴趣即库里的主题标签,选中仍拼进发给 AI 的描述', () => {
  const js = read('pages/publish/simple/index.js');
  assert.match(js, /tagLibrary\.tagSet\('theme'\)/);
  const wxml = read('pages/publish/simple/index.wxml');
  assert.match(wxml, /class="simple-ai__tag \{\{item\.on \? 'is-on' : ''\}\}"[\s\S]*?bindtap="toggleInterest"/);
  assert.doesNotMatch(wxml, /simple-ai__bubble|simple-ai__glow|simple-ai__kicker|选了 \{\{/, '漂浮气泡、柔光、引导句和「选了 N 个」已撤');
  assert.match(wxml, /<cy-cell title="历史起草记录"[^>]*bind:tap="openAiHistory"/);
  assert.match(wxml, /<cy-cell title="本机保留记录"[^>]*bind:tap="openLocalAiHistory"/);
  const labels = lib.tagSet('theme').reduce((all, g) => all.concat(g.tags.map((t) => t.label)), []);
  const page = { data: { aiIdea: '老城', interests: labels.map((label, i) => ({ key: 't' + i, label, on: label === '夜游' || label === '解谜' })) } };
  const src = js.match(/_composeIdea\(\) \{[\s\S]*?\n  \},/)[0];
  const compose = new Function('return {' + src + '}')()._composeIdea;
  assert.equal(compose.call(page), '老城，喜欢夜游、解谜');
});
