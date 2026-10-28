const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const read = file => fs.readFileSync(path.resolve(__dirname, '../../pages/publish/', file), 'utf8').replace(/<!--[\s\S]*?-->/g, '');
const topic = read('fabu/topic-detail-sheet.wxml');
const template = read('temp/index.wxml');
const station = read('fabu/index.wxml');

test('R3 主题导航仅提供主题规则，旧恢复/设施/运行结局只能显示只读摘要', () => {
  assert.doesNotMatch(topic, /data-kind="endings"|data-section="(?:recovery|facility)"/);
  assert.doesNotMatch(topic, />(?:恢复与配给|公共设施|运行结局|恢复\/救助)</);
  assert.match(topic, /data-kind="mode"[^>]*bindtap="openStructure"/);
  assert.match(topic, /data-section="card"[^>]*bindtap="openSurvivalEditor"/);
  assert.match(topic, /bindchange="toggleThemeReplay"/);
  const legacy = topic.match(/<block wx:if="\{\{legacyRuntimeSummary.hasLegacy\}\}">([\s\S]*?)<\/block>/);
  assert.ok(legacy, '只有存在旧配置时才显示兼容摘要');
  assert.match(legacy[1], /旧版配置仍按原样运行/);
  assert.match(legacy[1], /legacyRuntimeSummary.text/);
  assert.doesNotMatch(legacy[1], /bind(?:tap|change)|catchtap|<input|<cy-dropdown/);
});

test('R3 无效果消耗开关只在值域卡内，绑定缺省状态与可见禁用原因', () => {
  const values = topic.slice(topic.indexOf('id="fieldRuntimeState"'), topic.indexOf('id="fieldGameRules"'));
  assert.match(values, /没有实际效果时也消耗/);
  assert.match(values, /checked="\{\{runtimeConsumeOnNoEffect\}\}"[^>]*disabled="\{\{!!runtimeConsumeOnNoEffectDisabledReason\}\}"[^>]*bindchange="onThemeConsumeOnNoEffect"/);
  assert.match(values, /runtimeConsumeOnNoEffectDisabledReason \|\|/);
  assert.equal((topic.match(/bindchange="onThemeConsumeOnNoEffect"/g) || []).length, 1);
});

test('R3 原有效果三处支持数值选择/INC FLOOR/内联新建/逐行错误，旧变量行仍在', () => {
  for (const effects of ['o.effects', 'advanced.check.successEffects', 'advanced.check.failEffects']) {
    const begin = template.indexOf(`wx:for="{{${effects}}}"`);
    // 每处单独止于自己的添加按钮，防止通过行掩盖失败行缺失。
    // R5：检定的旧效果列表只保留已有行（新效果在「结果之后」里配），不再有添加按钮，止于该列表的行内错误。
    const end = effects === 'o.effects' ? template.indexOf('bindtap="addEffectRow"', begin)
      : template.indexOf('data-field="value" bindinput="updateEffectField"', template.indexOf('data-field="var" bindinput="updateEffectField"', begin)) + 60;
    assert.ok(begin >= 0 && end > begin, effects);
    const block = template.slice(begin, end);
    assert.match(block, /effectRows\[/);
    assert.match(block, /\.legacy/);
    assert.match(block, /range="\{\{effectValueSlots\}\}"[^>]*bind:change="pickEffectResource"/);
    assert.match(block, /range="\{\{resourceEffectOps\}\}"/);
    assert.match(block, /effectResourceDraft\.path/);
    assert.match(block, /bindtap="saveEffectResource"/);
    assert.match(block, /bindtap="closeEffectResource"/);
    assert.match(block, /effectResourceDisabledReason/);
    assert.match(block, /opt.rowErr\(effectErrors,/);
    assert.match(block, /value="\{\{eff.var\}\}"[\s\S]*?bindinput="updateEffectField"/);
    assert.match(block, /range="\{\{effectOps\}\}"/);
  }
  for (const path of ['check.successEffects', 'check.failEffects']) {
    assert.doesNotMatch(template, new RegExp('data-path="' + path.replace('.', '\\.') + '" bindtap="addEffectRow"'), path + ' 旧列表不再新增');
  }
});

test('R5 效果作用于在玩法设置「结果反馈」里，终局表决不在编辑页选结局', () => {
  const sheet = template.slice(template.indexOf('id="game-cfg"'), template.indexOf('wx:if="{{ gameSheetOpen }}"'));
  assert.ok(sheet.indexOf('结果反馈') >= 0 && sheet.indexOf('效果作用于') > sheet.indexOf('结果反馈'));
  assert.equal((template.match(/bind:change="pickEffectScope"/g) || []).length, 1);
  assert.match(sheet, /range="\{\{effectScopeOptions\}\}"/);
  assert.doesNotMatch(template, /bind:change="changeTargetScope"|range="\{\{effectTargets\}\}"/);
  assert.doesNotMatch(template, /chooseVoteEnding|runtimeEndingOptions|data-field="endingKey"|先在主题.*结局/);
  assert.doesNotMatch(template, /在把这个模板放到站点时，从最终章已写好的结局里选/);
});

test('R3 站点方案结局仅由终局行控制，资源绑定与缺结局提示保留', () => {
  assert.match(station, /wx:if="\{\{nodeBindingRows.length\}\}"/);
  assert.match(station, /对应主题资源/);
  assert.match(station, /wx:if="\{\{nodePlanEndingRows.length\}\}">\s*<text[^>]*>方案对应的结局/);
  assert.match(station, /range="\{\{plan.options\}\}"[^>]*bind:change="onNodePlanEnding"/);
  assert.match(station, /wx:else[^>]*>最终章</);
  assert.match(station, /nodePlanEndingError/);
  assert.match(station, /由工作人员开幕/);
  // 10-28 原型 A e-node：到达与引导是分组列表（L1 cy-list-row）里的一行，点开到达与引导半屏。
  assert.match(station, /title="到达与引导"[^>]*bind:press="openStructure"/);
  assert.match(topic, /id="fieldSubtitle"[\s\S]*?主题描述[\s\S]*?bindinput="onSubtitleChange"/);
});

test('R4 结局归章节：章节设置有「设为结局」开关与条件，处理函数存在且不在主题设置里另设结局编辑', () => {
  const settings = station.slice(station.indexOf('pop-chapter-settings'));
  assert.match(settings, /设为结局<\/text>[\s\S]*?bindchange="onChapterEndingToggle"/);
  for (const handler of ['onChapterEndingKind', 'onChapterEndingPick']) assert.match(settings, new RegExp('bind:change="' + handler + '"'));
  const js = fs.readFileSync(path.resolve(__dirname, '../../pages/publish/fabu/index.js'), 'utf8');
  for (const handler of ['onChapterEndingToggle', 'onChapterEndingKind', 'onChapterEndingPick']) assert.match(js, new RegExp('\\n  ' + handler + '\\(e\\) \\{'));
  assert.doesNotMatch(topic, /设为结局|onChapterEnding/);
});

test('R4 结局章回显：同步时写回「什么时候播」与条件下标，第二个结局不再显示成兜底', () => {
  const js = fs.readFileSync(path.resolve(__dirname, '../../pages/publish/fabu/index.js'), 'utf8');
  const body = js.slice(js.indexOf('  _syncChapterEndingOptions(ending) {'), js.indexOf('  onChapterEndingToggle(e) {'));
  for (const field of ['chapterEndingKindIndex', 'chapterEndingPickIndex', 'endingTagOptions', 'endingNodeOptions']) {
    assert.match(body, new RegExp(field + ':'), '回显必须写回 ' + field);
  }
});
