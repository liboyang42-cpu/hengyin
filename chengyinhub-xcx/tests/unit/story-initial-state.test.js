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

test('伏笔节点引用保留精确Long字符串并拒绝隐式转换及溢出', () => {
  const save = nodeId => JSON.parse(initial.serialize({ ...initial.parse(''),
    thoughts: [{ key: 'arrival', name: '抵达', doneWhen: [{ op: 'NODE_COMPLETED', nodeId }] }],
  })).thoughts[0].doneWhen[0].nodeId;
  assert.equal(save(42), 42);
  assert.equal(save('9007199254740993'), '9007199254740993');
  assert.equal(save('9223372036854775807'), '9223372036854775807');
  for (const value of [true, 1.5, Number.MAX_SAFE_INTEGER + 1, '01', '1e3', '1.5', '-1', '0', '9223372036854775808']) {
    assert.throws(() => save(value), undefined, String(value));
  }
});
