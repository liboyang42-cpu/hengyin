const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const drafts = {};
const draftStub = { read: k => drafts[k] || {}, write: (k, v) => { if (k) drafts[k] = v; }, clear: k => { delete drafts[k]; } };
function profile(questions, avatar = {}, draftKey = '') {
  let spec;
  vm.runInNewContext(fs.readFileSync(require.resolve('../../pages/play/components/playkit-profile/index.js'), 'utf8'), {
    Component: value => { spec = value; }, require: p => (/kit-draft/.test(p) ? draftStub : { haptic() {} }), wx: {},
    setTimeout: (fn, ms) => setTimeout(fn, ms), clearTimeout: id => clearTimeout(id),
  });
  const c = { data: JSON.parse(JSON.stringify(spec.data)), ...spec.methods, events: [],
    setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb(); },
    triggerEvent(name, detail) { this.events.push({ name, detail }); },
  };
  for (const [key, prop] of Object.entries(spec.properties)) c.data[key] = prop.value;
  const update = patch => {
    Object.assign(c.data, patch);
    for (const [fields, observer] of Object.entries(spec.observers)) {
      observer.apply(c, fields.split(',').map(key => c.data[key.trim()]));
    }
  };
  c.data.draftKey = draftKey;
  update({ show: true, questions, avatar });
  return { c, update };
}

test('建档逐题回答，最后才提交整张表；可返回修改，选项摘要显示文案', () => {
  const { c } = profile([
    { key: 'name', label: '姓名', kind: 'text', required: true, maxLength: 12 },
    { key: 'job', label: '梦想', kind: 'pick', required: true,
      options: [{ key: 'artist', label: '画家' }] },
  ]);
  assert.equal(c.data.currentQuestion.key, 'name');
  c.onSubmit();
  assert.equal(c.events.length, 0);
  c.onInput({ currentTarget: { dataset: { key: 'name' } }, detail: { value: '小满' } });
  c.onSubmit();
  assert.equal(c.events.length, 0, '中途不能结算');
  assert.equal(c.data.currentQuestion.key, 'job');
  c.onPick({ currentTarget: { dataset: { key: 'job', opt: 'artist' } } });
  assert.equal(c.data.summary[1].answer, '画家');
  c.onPrevious();
  assert.equal(c.data.form.name, '小满');
  c.onSubmit();
  c.onSubmit();
  assert.equal(c.events.length, 1);
  assert.equal(c.events[0].name, 'submit');
  assert.deepEqual(JSON.parse(JSON.stringify(c.events[0].detail.answers)), { name: '小满', job: 'artist' });
});

test('登记逐题必须作答，旧配置选填也不能空白跳过，空格不算答案', () => {
  const { c } = profile([
    { key: 'name', kind: 'text', required: false },
    { key: 'gender', kind: 'pick', options: [{ key: 'unsaid', label: '不想说' }] },
  ]);
  c.onSubmit();
  assert.equal(c.data.step, 0);
  c.onInput({ currentTarget: { dataset: { key: 'name' } }, detail: { value: '  ' } });
  c.onSubmit();
  assert.equal(c.data.step, 0);
  c.onInput({ currentTarget: { dataset: { key: 'name' } }, detail: { value: '小城' } });
  c.onSubmit();
  c.onSubmit();
  assert.equal(c.events.length, 0);
  c.onPick({ currentTarget: { dataset: { key: 'gender', opt: 'unsaid' } } });
  c.onSubmit();
  assert.equal(c.events.length, 1);
});

test('必填头像作为最后一步，上传回填不清空答案，超长输入不能前进', () => {
  const { c, update } = profile([
    { key: 'name', label: '姓名', kind: 'text', required: true, maxLength: 2 },
  ], { enabled: true, required: true });
  c.onInput({ currentTarget: { dataset: { key: 'name' } }, detail: { value: '超长名字' } });
  c.onSubmit();
  assert.equal(c.data.step, 0);
  c.onInput({ currentTarget: { dataset: { key: 'name' } }, detail: { value: '小满' } });
  c.onSubmit();
  assert.equal(c.data.avatarStep, true);
  c.onSubmit();
  assert.equal(c.events.length, 0);
  update({ avatarUrl: '/profile/avatar.png' });
  assert.equal(c.data.form.name, '小满');
  assert.equal(c.data.avatarStep, true);
  c.onSubmit();
  assert.equal(c.events[0].detail.avatarUrl, '/profile/avatar.png');
  update({ done: true, answers: { name: '小满' } });
  c.onInput({ currentTarget: { dataset: { key: 'name' } }, detail: { value: '改名' } });
  c.onSubmit();
  assert.equal(c.data.form.name, '小满');
  assert.equal(c.events.length, 1, '已建档不能再提交');
});

test('登记问题读取、保存、预览统一必答，旧选填配置不能产生空白跳过路径', () => {
  const config = require('../../pages/publish/utils/publish/advanced-game-config.js');
  const questions = [
    { key: 'legacy', label: '旧选择题', kind: 'pick', options: [{ key: 'a', label: '甲' }, { key: 'b', label: '乙' }] },
    { key: 'optional', label: '选填', kind: 'pick', required: false, options: [{ key: 'c', label: '丙' }, { key: 'd', label: '丁' }] },
    { key: 'text', label: '文字', kind: 'text', maxLength: 12 },
  ];
  const parsed = config.parse({ schemaVersion: 1, profile: { enabled: true, questions } });
  assert.equal(parsed.error, '');
  assert.deepEqual(parsed.value.profile.questions.map(q => q.required), [true, true, true]);
  const saved = config.normalize(parsed.value);
  assert.deepEqual(saved.profile.questions.map(q => q.required), [true, true, true]);
  const { buildPreviewKit } = require('../../pages/publish/utils/publish/advanced-game-preview.js');
  assert.deepEqual(buildPreviewKit(saved).questions.map(q => q.required), [true, true, true]);
});

test('作者预览与正式登记同样拒绝缺题、空格、无效选项与超长答案', () => {
  const { applyPreviewAction } = require('../../utils/game-preview-action.js');
  const kit = { type: 'profile', done: false, answers: {}, questions: [
    { key: 'name', label: '姓名', kind: 'text', maxLength: 2, required: false },
    { key: 'job', label: '职业', kind: 'pick', required: false,
      options: [{ key: 'a', label: '甲' }, { key: 'b', label: '乙' }] },
  ] };
  for (const answers of [{ name: '小满' }, { name: '  ', job: 'a' },
    { name: '小满', job: 'unknown' }, { name: '超长名字', job: 'a' }]) {
    assert.equal(applyPreviewAction(kit, { action: 'submit', detail: { answers } }), null);
  }
  const valid = applyPreviewAction(kit, { action: 'submit', detail: { answers: { name: '小满', job: 'a' } } });
  assert.equal(valid.kit.done, true);
});

const cfg = require('../../pages/publish/utils/publish/advanced-game-config.js');

test('咔哒:编辑页的平字段存成服务端 override,读回来还原;条件与选项不对判红', () => {
  const model = cfg.defaultConfig()
  model.profile = { enabled: true, title: '入职登记', lead: '', xp: 0, avatar: { enabled: false, required: false },
    questions: [{ key: 'job', label: '你想做什么', kind: 'pick', required: true,
      options: [{ key: 'painter', label: '画师', effects: [] }, { key: 'clerk', label: '会计', effects: [] }],
      kaccha: { enabled: true, var: 'counter.instability', gte: 4, option: 'clerk', voice: '表格上写的是{assigned}。' } }] }
  const saved = JSON.parse(cfg.serialize(model))
  assert.deepEqual(saved.profile.questions[0].override, {
    requires: [{ op: 'GTE', var: 'counter.instability', value: 4 }], option: 'clerk', voice: '表格上写的是{assigned}。' })
  assert.equal(saved.profile.questions[0].kaccha, undefined)
  const back = cfg.parse(JSON.stringify(saved)).value.profile.questions[0].kaccha
  assert.deepEqual(back, { enabled: true, var: 'counter.instability', gte: 4, option: 'clerk', voice: '表格上写的是{assigned}。' })

  const bad = JSON.parse(JSON.stringify(model))
  bad.profile.questions[0].kaccha.var = 'instability'
  assert.throws(() => cfg.serialize(bad), /前缀/)
  bad.profile.questions[0].kaccha.var = 'counter.instability'
  bad.profile.questions[0].kaccha.option = 'astronaut'
  assert.throws(() => cfg.serialize(bad), /改写成的选项/)
  bad.profile.questions[0].kaccha.enabled = false
  assert.equal(JSON.parse(cfg.serialize(bad)).profile.questions[0].override, undefined)
})

test('咔哒:作者试玩按条件已满足演,答案改写成定好的那一项', () => {
  const { applyPreviewAction } = require('../../utils/game-preview-action.js')
  const kit = { type: 'profile', done: false, answers: {}, questions: [{ key: 'job', kind: 'pick',
    options: [{ key: 'painter', label: '画师' }, { key: 'clerk', label: '会计' }], kaccha: { option: 'clerk', voice: '写的是{assigned}' } }] }
  const r = applyPreviewAction(kit, { action: 'submit', detail: { answers: { job: 'painter' } } })
  assert.equal(r.kit.answers.job, 'clerk')
  assert.deepEqual(r.kit.overrides, [{ key: 'job', picked: '画师', assigned: '会计', voice: '写的是{assigned}' }])
  const same = applyPreviewAction(kit, { action: 'submit', detail: { answers: { job: 'clerk' } } })
  assert.deepEqual(same.kit.overrides, [])
})

test('填了一半的建档:退出重进接着填(同一题、已填的还在);提交后草稿清掉', () => {
  const qs = [{ key: 'name', label: '姓名', kind: 'text', required: true, maxLength: 12 },
    { key: 'city', label: '出生地', kind: 'text', required: true, maxLength: 12 }]
  const first = profile(qs, {}, 'k1').c
  first.onInput({ currentTarget: { dataset: { key: 'name' } }, detail: { value: '阿猫' } })
  first.onSubmit()                     // 下一题
  assert.equal(JSON.stringify(drafts.k1), JSON.stringify({ form: { name: '阿猫', city: '' }, step: 1 }))
  const again = profile(qs, {}, 'k1')
  assert.equal(again.c.data.form.name, '阿猫')
  assert.equal(again.c.data.step, 1)
  again.update({ done: true, answers: { name: '阿猫', city: '上海' } })
  assert.equal(drafts.k1, undefined)
})

test('刚交上去:答案像墨迹写进档案,写完盖章;重进看到的已登记档案直接是盖好的', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const questions = [{ key: 'name', label: '姓名', kind: 'text', required: true, maxLength: 12 }];
  const { c, update } = profile(questions);
  c.onInput({ currentTarget: { dataset: { key: 'name' } }, detail: { value: '小满' } });
  c.onSubmit();
  update({ done: true, answers: { name: '小满' } });
  assert.equal(c.data.inked, false, '先是空白');
  assert.equal(c.data.sealed, false);
  t.mock.timers.tick(50);
  assert.equal(c.data.inked, true, '一行行写进去');
  assert.equal(c.data.sealed, false, '写完才盖章');
  t.mock.timers.tick(1100);
  assert.equal(c.data.sealed, true);
  const again = profile(questions);
  again.update({ done: true, answers: { name: '小满' } });
  assert.equal(again.c.data.inked, true);
  assert.equal(again.c.data.sealed, true, '不是刚交的，不重放');
});
