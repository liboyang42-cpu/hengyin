const test = require('node:test');
const assert = require('node:assert/strict');
const initial = require('../../pages/publish/utils/publish/story-initial-state');

test('新主题只按需选精力与幸运；旧检定配置和既有资源不被重置', () => {
  const fresh = initial.parse('');
  assert.equal(fresh.hp.enabled, false);
  assert.equal(fresh.luck.enabled, false);
  assert.deepEqual(initial.OPTIONS.map(item => item.type), ['hp', 'luck']);
  const legacy = initial.parse('{"schemaVersion":1,"checkEnabled":true,"modCap":4}');
  assert.equal(legacy.hp.enabled, true);
  assert.equal(legacy.luck.enabled, true);
  assert.equal(initial.parse(initial.serialize(legacy)).modCap, 4);
});

test('资源空值、小数与越界须报错；调整上限同时限制恢复值', () => {
  const rules = initial.parse('');
  for (const value of ['', 1.5, 11, 'oops']) {
    assert.throws(() => initial.serialize({ ...rules, hp: { ...rules.hp, init: value } }));
  }
  assert.throws(() => initial.parse('{bad'));
  rules.hp.max = 3;
  rules.hp.init = 3;
  rules.luck.max = 0;
  rules.luck.init = 0;
  const saved = JSON.parse(initial.serialize(rules));
  assert.equal(saved.recovery.exhaustRestHp, 3);
  assert.equal(saved.recovery.stageRestLuckFloor, 0);
});

// ==================== 伏笔清单 ====================
// ⚠️ 面上叫「伏笔」(2026-09-22 用户定名),内部键名仍是 thought.*。
// 2026-09-22 用户裁决「跟初始状态并排」——同一份 journeyRules,两个入口卡。
// 校验口径与服务端 JourneyRulesValidator 一致;前端先拦一道,免得存了才被打回。

test('伏笔清单跟着初始状态一起存,重开回填还在', () => {
  const raw = initial.serialize({
    schemaVersion: 1, stateEnabled: true,
    hp: { enabled: true, init: 10, max: 10 },
    luck: { enabled: true, init: 3, max: 3 },
    thoughts: [{ key: 'afternoon', name: '那个永远不会结束的下午', desc: '你总觉得…', need: 120 }],
  })
  const back = initial.parse(raw)
  assert.equal(back.thoughts.length, 1)
  assert.equal(back.thoughts[0].key, 'afternoon')
  assert.equal(back.thoughts[0].need, 120)
})

test('没配思维阁的主题不凭空多出一个空数组', () => {
  const back = initial.parse(initial.serialize({
    schemaVersion: 1, hp: { enabled: true, init: 10, max: 10 }, luck: { enabled: true, init: 3, max: 3 },
  }))
  assert.ok(!back.thoughts || back.thoughts.length === 0)
})

test('读取保留存量多个伏笔，新的配置提交只允许一个', () => {
  const list = [{ key: 'a', name: '甲', need: 10 }, { key: 'b', name: '乙', need: 20 }]
  const legacy = initial.parse(JSON.stringify({ thoughts: list }))
  assert.deepEqual(legacy.thoughts, list)
  assert.throws(() => initial.serialize(legacy), /只能配置一个伏笔/)
  assert.equal(JSON.parse(initial.serialize({ ...legacy, thoughts: [list[0]] })).thoughts.length, 1)
})

test('伏笔缺名字或阈值非法时当场拦住，不等服务端打回', () => {
  const base = { schemaVersion: 1, hp: { enabled: true, init: 10, max: 10 }, luck: { enabled: true, init: 3, max: 3 } }
  assert.throws(() => initial.serialize({ ...base, thoughts: [{ key: 'a', need: 10 }] }), /伏笔/)
  assert.throws(() => initial.serialize({ ...base, thoughts: [{ key: 'a', name: '甲', need: 0 }] }), /伏笔/)
  assert.throws(() => initial.serialize({ ...base, thoughts: [
    { key: 'a', name: '甲', need: 10 }, { key: 'a', name: '乙', need: 20 },
  ] }), /伏笔/)
})

// ==================== 伏笔由选项得到 / 应验(2026-09-22 用户裁决) ====================
// 「伏笔不能只是微信步数,回答出一些特定选项和条件的时候才有」:选项效果贴的标签(tag.xxx)
// 就是那个「特定选项」。口径与服务端 JourneyRulesValidator.normalizeThoughtConditions 一致。
const baseRules = () => ({ schemaVersion: 1, hp: { enabled: true, init: 10, max: 10 }, luck: { enabled: true, init: 3, max: 3 } })

test('伏笔可以不配步数、只靠选项应验;得到条件也能挂选项', () => {
  const saved = JSON.parse(initial.serialize({ ...baseRules(), thoughts: [{
    key: 'window', name: '那扇窗', need: '',
    when: [{ op: 'HAS_TAG', value: 'tag.saw_window' }],
    doneWhen: [{ op: 'HAS_TAG', value: 'tag.opened_window' }],
  }] }))
  const t = saved.thoughts[0]
  assert.equal(t.need, undefined, '空步数不写成 0(服务端会判「阈值为 0」)')
  assert.deepEqual(t.when, [{ op: 'HAS_TAG', value: 'tag.saw_window' }])
  assert.deepEqual(t.doneWhen, [{ op: 'HAS_TAG', value: 'tag.opened_window' }])
})

test('既没步数也没应验条件的伏笔当场拦住;坏标签也拦', () => {
  assert.throws(() => initial.serialize({ ...baseRules(), thoughts: [{ key: 'a', name: '甲', need: '' }] }), /应验/)
  assert.throws(() => initial.serialize({ ...baseRules(), thoughts: [{ key: 'a', name: '甲', need: '',
    doneWhen: [{ op: 'HAS_TAG', value: 'hurried' }] }] }), /tag\./)
})

test('从玩法选项里收集可选的「特定选项」:只收挂了贴标签效果的,带上题目与站点名', () => {
  const chapters = [{ nodes: [{ name: '新生儿登记', templateInfo: { advancedConfigJson: JSON.stringify({
    profile: { questions: [{ key: 'gender', label: '男孩还是女孩？', kind: 'pick', options: [
      { key: 'A', label: '男孩', effects: [{ op: 'ADD_TAG', value: 'tag.boy' }] },
      { key: 'B', label: '女孩', effects: [{ op: 'INC', var: 'luck', value: 1 }] },
    ] }] },
  }) } }, { name: '坏配置', templateInfo: { advancedConfigJson: '{坏' } }] }]
  assert.deepEqual(initial.optionTagsOf(chapters), [{ tag: 'tag.boy', label: '新生儿登记 · 男孩还是女孩？ · 男孩' }])
  assert.deepEqual(initial.optionTagsOf([]), [])
})

// Custom initial attributes: canonical JSON values match JourneyInitialState.normalize.
const attribute = extra => ({ key: 'counter.focus', label: '专注', ...extra });
test('属性四类标识与缺省值遵循服务端，返回新对象且不修改高级字段', () => {
  const input = ['counter', 'relation', 'clue', 'tag'].map(key => attribute({ key: key + '.focus', advanced: { keep: true } }));
  const before = JSON.stringify(input), rows = initial.normalizeAttributes(input);
  assert.equal(JSON.stringify(input), before);
  assert.deepEqual(rows.map(x => [x.initial, x.min, x.max, x.visible]), [[0, -10000, 10000, true], [0, -10000, 10000, true], [0, 0, 1, true], [0, 0, 1, true]]);
  assert.equal(rows[0].advanced, undefined);
  assert.deepEqual(initial.normalizeAttributes(undefined), []);
});
test('属性数量 32/33、空数组、空值、稀疏数组及重复标识严格区分', () => {
  const rows = Array.from({ length: 32 }, (_, i) => attribute({ key: 'counter.a' + i }));
  assert.equal(initial.normalizeAttributes(rows).length, 32);
  assert.deepEqual(initial.normalizeAttributes([]), []);
  for (const bad of [null, {}, Array(1), rows.concat(attribute()), [attribute(), attribute()]]) assert.throws(() => initial.normalizeAttributes(bad));
});
test('标识后缀 48/49、大小写、系统键、末尾换行均匹配 Java 全串规则', () => {
  assert.equal(initial.normalizeAttributes([attribute({ key: 'relation.' + 'a'.repeat(48) })]).length, 1);
  for (const key of ['counter.' + 'a'.repeat(49), 'counter.A', 'counter.1a', 'sys.hp', 'tag.a\n', 'clue.a ', 'counter.a.b']) assert.throws(() => initial.normalizeAttributes([attribute({ key })]), key);
});
test('名称使用 Java trim 和 UTF-16 长度，NUL 控制符/NBSP/emoji 边界准确', () => {
  for (const label of ['', '\0\t\r\n ', 'a'.repeat(25), '😀'.repeat(13), null, 3]) assert.throws(() => initial.normalizeAttributes([attribute({ label })]));
  assert.equal(initial.normalizeAttributes([attribute({ label: '\0\t 标题 \x1f' })])[0].label, '标题');
  assert.equal(initial.normalizeAttributes([attribute({ label: '\u00a0' })])[0].label, '\u00a0');
  assert.equal(initial.normalizeAttributes([attribute({ label: '😀'.repeat(12) })])[0].label.length, 24);
});
test('整数字段拒绝字符串/空值/浮点/非有限值，数值上下限精确', () => {
  for (const field of ['initial', 'min', 'max']) for (const value of [null, '', '0', false, 1.5, Infinity, NaN, 2147483648, -2147483649]) {
    assert.throws(() => initial.normalizeAttributes([attribute({ [field]: value })]), field + ':' + value);
  }
  assert.equal(initial.normalizeAttributes([attribute({ min: -10000, max: 10000, initial: -10000 })])[0].initial, -10000);
  for (const extra of [{ min: -10001 }, { max: 10001 }, { min: 2, max: 1 }, { min: 1 }, { initial: 10001 }]) assert.throws(() => initial.normalizeAttributes([attribute(extra)]));
});
test('clue/tag 仅 0/1，visible 只能布尔值', () => {
  for (const kind of ['clue', 'tag']) {
    assert.equal(initial.normalizeAttributes([attribute({ key: kind + '.a', initial: 1 })])[0].initial, 1);
    for (const extra of [{ min: -1 }, { max: 2 }, { initial: 2 }]) assert.throws(() => initial.normalizeAttributes([attribute({ key: kind + '.a', ...extra })]));
  }
  for (const visible of [null, '', 1, 'false']) assert.throws(() => initial.normalizeAttributes([attribute({ visible })]));
  assert.equal(initial.normalizeAttributes([attribute({ visible: false })])[0].visible, false);
});
test('字段输入只转换完整十进制整数，空值和小数不变成零', () => {
  assert.equal(initial.attributeNumber(' -12\n'), -12);
  for (const value of ['', '1.5', '1e2', '0x10', null, false, '1.0']) assert.equal(initial.attributeNumber(value), value);
});
test('属性专用暂存保留未知根/行字段以及未声明默认值，不启用状态或改资源', () => {
  const root = { hp: { custom: [1], max: 7 }, thoughts: null, advanced: { future: [2] }, attributes: [attribute({ future: { nested: [3] } })] };
  const list = root.attributes.map(x => ({ ...x, label: '新名' }));
  const saved = JSON.parse(initial.serializeAttributes(JSON.stringify(root), list));
  assert.deepEqual(saved, { ...root, attributes: list });
  assert.equal(saved.stateEnabled, undefined);
  assert.equal(saved.attributes[0].min, undefined);
  assert.deepEqual(JSON.parse(initial.serializeAttributes('{}', undefined, false)), { schemaVersion: 1, stateEnabled: false });
  assert.deepEqual(JSON.parse(initial.serializeAttributes('{"stateEnabled":true}', [])), { stateEnabled: true, attributes: [] });
});
test('属性暂存拒绝坏启用值、坏属性与超出 16 KiB UTF-8 的配置', () => {
  assert.throws(() => initial.serializeAttributes('{"stateEnabled":null}', []));
  assert.throws(() => initial.serializeAttributes('{}', [], 'true'));
  assert.throws(() => initial.serializeAttributes('{}', null));
  assert.throws(() => initial.serializeAttributes(JSON.stringify({ future: '中'.repeat(5500) }), []), /16 KiB/);
  assert.throws(() => initial.serialize({ ...baseRules(), attributes: [attribute({ initial: '0' })] }));
});
test('全新空配置仅补服务端必需版本；已有显式版本及未声明 attributes 均保留', () => {
  for (const raw of [undefined, null, '', ' ', '{}', {}]) {
    const saved = JSON.parse(initial.serializeAttributes(raw, [attribute()]));
    assert.equal(saved.schemaVersion, 1); assert.equal(saved.stateEnabled, undefined);
    const enabledOnly = JSON.parse(initial.serializeAttributes(raw, undefined, true));
    assert.deepEqual(enabledOnly, { schemaVersion: 1, stateEnabled: true });
  }
  assert.equal(JSON.parse(initial.serializeAttributes('{"schemaVersion":2}', [])).schemaVersion, 2, 'Explicit unsupported versions stay unsupported at the authoritative server boundary');
});
