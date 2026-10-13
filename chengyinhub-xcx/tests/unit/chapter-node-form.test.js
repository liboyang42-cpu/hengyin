const { beforeEach, test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const COMPONENT = '../../pages/topic/components/cy/chapter-node-form/index.js';
const WXML = fs.readFileSync(path.resolve(__dirname, '../../pages/topic/components/cy/chapter-node-form/index.wxml'), 'utf8');

let componentConfig;
let requests;
let tips;
let navigations;
let modals;
let locationPicks;
let memberId;

global.getApp = () => ({
  getUserID: () => memberId,
  sendRequest: (request) => requests.push(request),
  tips: (message) => tips.push(message),
});
global.Component = (config) => { componentConfig = config; };
global.wx = {
  navigateTo: (options) => navigations.push(options),
  showModal: (options) => modals.push(options),
  chooseLocation: (options) => locationPicks.push(options),
  showToast: (options) => tips.push(options.title),
};

beforeEach(() => {
  componentConfig = null;
  requests = [];
  tips = [];
  navigations = [];
  modals = [];
  locationPicks = [];
  memberId = 1;
  delete require.cache[require.resolve(COMPONENT)];
  require(COMPONENT);
});

function makeComponent(form, formMode = 'application') {
  const events = [];
  const component = Object.assign({}, componentConfig.methods);
  component.data = {
    show: true,
    chapterId: 33,
    termsMode: 'PERK',
    formMode,
    submitting: false,
    perkTemplates: [{ id: 7, name: '夜航限定饮品' }],
    form: Object.assign({
      name: '', address: '', longitude: '', latitude: '', templateId: 0, templateName: '', xpValue: '', message: '',
      perkTemplateId: 0, perkName: '', quotaTotal: '', perHeadFee: '',
    }, form),
  };
  component.setData = (patch, callback) => {
    Object.entries(patch).forEach(([key, value]) => {
      if (key.startsWith('form.')) component.data.form[key.slice(5)] = value;
      else component.data[key] = value;
    });
    if (callback) callback();
  };
  component.triggerEvent = (name, detail) => events.push({ name, detail });
  return { component, events };
}

function visibleInputsByKey(wxml, key, termsMode, formMode = 'offer') {
  const stack = [];
  let count = 0;
  const tags = wxml.match(/<\/?[\w-]+(?:\s[^<>]*?)?\/?>/g) || [];
  tags.forEach((tag) => {
    const closing = /^<\//.test(tag);
    const name = (tag.match(/^<\/?([\w-]+)/) || [])[1];
    if (!name) return;
    if (closing) {
      for (let i = stack.length - 1; i >= 0; i -= 1) {
        if (stack[i].name === name) {
          stack.splice(i, 1);
          break;
        }
      }
      return;
    }
    const ownIf = (tag.match(/wx:if="\{\{([\s\S]*?)\}\}"/) || [])[1];
    const conditions = stack.map(item => item.condition).filter(Boolean);
    if (ownIf) conditions.push(ownIf);
    if (new RegExp(`data-key="${key}"`).test(tag)) {
      const visible = conditions.every((condition) => Function(
        'termsMode', 'formMode', `return !!(${condition})`,
      )(termsMode, formMode));
      if (visible) count += 1;
    }
    if (!/\/>$/.test(tag) && !['input', 'image'].includes(name)) {
      stack.push({ name, condition: ownIf });
    }
  });
  return count;
}

test('申请点位阶段同时守住 name 和 templateId 两道必填闸，且不夹带供给', () => {
  const missingName = makeComponent({ templateId: 88 });
  missingName.component.onSubmit();
  assert.equal(missingName.events.length, 0, '缺 name 时不得发出 submit 事件');

  const missingTemplate = makeComponent({ name: '夜航咖啡' });
  missingTemplate.component.onSubmit();
  assert.equal(missingTemplate.events.length, 0, '缺 templateId 时不得发出 submit 事件');

  const complete = makeComponent({
    name: '  夜航咖啡  ', templateId: 88, xpValue: '', address: '星海街', message: '可承接',
    longitude: '120.70', latitude: '31.30',
    perkTemplateId: 7, perkName: '夜航限定饮品', quotaTotal: '12',
  });
  complete.component.onSubmit();
  assert.equal(complete.events.length, 1);
  assert.equal(complete.events[0].name, 'submit');
  assert.deepEqual(complete.events[0].detail, {
    chapterId: 33,
    templateId: 88,
    name: '夜航咖啡',
    address: '星海街',
    longitude: '120.70',
    latitude: '31.30',
    xpValue: null,
    message: '可承接',
  });
});

test('地图入口先说明用途，原生选点确认后保存真实坐标并保留自定名称', () => {
  const state = makeComponent({ name: '玩家看到的名称', templateId: 88 });
  state.component.chooseNodeLocation();
  assert.equal(locationPicks.length, 0, '用途确认前不唤起原生位置能力');
  assert.match(modals[0].content, /导航和现场位置校验/);
  modals[0].success({ confirm: true });
  assert.equal(locationPicks.length, 1);
  locationPicks[0].success({ name: '地图地点', address: '星海街 8 号', longitude: 120.7, latitude: 31.3 });
  assert.equal(state.component.data.form.name, '玩家看到的名称');
  assert.equal(state.component.data.form.address, '星海街 8 号');
  assert.equal(state.component.data.locationReady, true);
  assert.equal(state.component.data.canSubmit, true);
  state.component.onSubmit();
  assert.equal(state.events[0].detail.longitude, '120.7');
  assert.equal(state.events[0].detail.latitude, '31.3');
  assert.match(WXML, /bindtap="chooseNodeLocation"/);
});

test('空白地址即使有坐标也不能提交，地图空地址不能确认位置', () => {
  for (const address of ['', '   ']) {
    const state = makeComponent({ name: '咖啡点', templateId: 88, address,
      longitude: '-77', latitude: '38.8833' });
    state.component.refreshSubmitState();
    assert.equal(state.component.data.canSubmit, false);
    state.component.onSubmit();
    assert.equal(state.events.length, 0);
  }
  const state = makeComponent({ name: '咖啡点', templateId: 88, address: '门店资料地址' });
  state.component.refreshSubmitState();
  const original = Object.assign({}, state.component.data.form);
  state.component.chooseNodeLocation();
  modals.at(-1).success({ confirm: true });
  locationPicks.at(-1).success({ name: '', address: '   ', longitude: -77, latitude: 38.8833 });
  assert.deepEqual(state.component.data.form, original);
  assert.notEqual(state.component.data.locationReady, true);
  assert.equal(state.component.data.canSubmit, false);
});

test('已有空地址点位不拿门店地址与旧坐标拼成已确认位置', () => {
  const state = makeComponent({});
  state.component.data.presetNode = { id: 277, name: '咖啡点', templateId: 88,
    address: '', longitude: '-77', latitude: '38.8833' };
  state.component.openForm();
  assert.equal(requests.some(request => request.url === '/api/merchant/info'), false);
  assert.equal(state.component.data.form.address, '');
  assert.equal(state.component.data.locationReady, false);
  assert.equal(state.component.data.canSubmit, false);
  state.component.onSubmit();
  assert.equal(state.events.length, 0);
});

test('地图取消、拒绝授权和无效坐标都保留原表单且不发点位提交', () => {
  for (const result of ['cancel', 'denied', 'invalid']) {
    const state = makeComponent({ name: '原名称', address: '原地址', templateId: 88 });
    const original = Object.assign({}, state.component.data.form);
    state.component.chooseNodeLocation();
    modals.at(-1).success({ confirm: true });
    const picker = locationPicks.at(-1);
    if (result === 'invalid') picker.success({ address: '错误点', longitude: 0, latitude: 31.3 });
    else picker.fail({ errMsg: 'chooseLocation:fail ' + (result === 'cancel' ? 'cancel' : 'auth deny') });
    assert.deepEqual(state.component.data.form, original, result);
    state.component.onSubmit();
    assert.equal(state.events.length, 0, result + ' 不得拿地址文本代替真实坐标');
  }
  const canceledPurpose = makeComponent({});
  const count = locationPicks.length;
  canceledPurpose.component.chooseNodeLocation();
  modals.at(-1).success({ confirm: false });
  assert.equal(locationPicks.length, count);
});

test('旧点位坐标回填，手动改变地址后清空坐标并阻止错配保存', () => {
  const state = makeComponent({});
  state.component.data.presetNode = { id: 80, name: '咖啡点', templateId: 88,
    address: '星海街', longitude: '120.7', latitude: '31.3' };
  state.component.openForm();
  assert.equal(state.component.data.locationReady, true);
  assert.equal(state.component.data.canSubmit, true);
  assert.equal(state.component.data.form.longitude, '120.7');
  state.component.onInput({ currentTarget: { dataset: { key: 'address' } }, detail: { value: '星海街' } });
  assert.equal(state.component.data.canSubmit, true, '同值回填不丢位置');
  state.component.onInput({ currentTarget: { dataset: { key: 'address' } }, detail: { value: '新地址' } });
  assert.equal(state.component.data.form.longitude, '');
  assert.equal(state.component.data.form.latitude, '');
  assert.equal(state.component.data.locationReady, false);
  assert.equal(state.component.data.canSubmit, false);
  state.component.onSubmit();
  assert.equal(state.events.length, 0);
});

test('关闭重开、切换章节或身份后，旧地图回包不能写入当前表单', () => {
  for (const change of ['reopen', 'chapter', 'member']) {
    const state = makeComponent({ name: '原名称', address: '原地址', templateId: 88 });
    state.component.chooseNodeLocation();
    modals.at(-1).success({ confirm: true });
    const picker = locationPicks.at(-1);
    if (change === 'reopen') { state.component.onClose(); state.component.openForm(); }
    if (change === 'chapter') state.component.data.chapterId = 34;
    if (change === 'member') memberId += 1;
    const original = Object.assign({}, state.component.data.form);
    picker.success({ name: '旧地点', address: '旧地图地址', longitude: 120.7, latitude: 31.3 });
    assert.deepEqual(state.component.data.form, original, change);
  }
});

test('审核通过后的实际供给阶段不再要求点位字段', () => {
  const state = makeComponent({ perkTemplateId: 7, quotaTotal: '12' }, 'offer');

  state.component.onSubmit();

  assert.deepEqual(state.events[0], {
    name: 'submit',
    detail: {
      chapterId: 33,
      termsMode: 'PERK',
      perkTemplateId: 7,
      quotaTotal: 12,
    },
  });
});

test('quotaTotal 输入与必填闸只属于 PERK 档', () => {
  assert.equal(visibleInputsByKey(WXML, 'quotaTotal', 'PERK'), 1, 'PERK 必须展示一次接待额度输入');
  assert.equal(visibleInputsByKey(WXML, 'quotaTotal', 'TRAFFIC'), 0, 'TRAFFIC 不走容量链，不得展示额度输入');
  assert.equal(visibleInputsByKey(WXML, 'quotaTotal', 'REVSHARE'), 0, 'REVSHARE 不走容量链，不得展示额度输入');

  const missingPerkQuota = makeComponent({ perkTemplateId: 7, quotaTotal: '' }, 'offer');
  missingPerkQuota.component.onSubmit();
  assert.equal(missingPerkQuota.events.length, 0, 'PERK 缺 quotaTotal 时不得越过核销额度闸');

  const traffic = makeComponent({ quotaTotal: '30' }, 'offer');
  traffic.component.data.termsMode = 'TRAFFIC';
  traffic.component.openForm();
  assert.equal(traffic.component.data.canSubmit, true, '引流供给无额外字段，正常打开后即可确认');
  traffic.component.onSubmit();
  assert.deepEqual(traffic.events[0].detail, {
    chapterId: 33, termsMode: 'TRAFFIC',
  });

  const revshare = makeComponent({ perHeadFee: '8.5', quotaTotal: '20' }, 'offer');
  revshare.component.data.termsMode = 'REVSHARE';
  revshare.component.onSubmit();
  assert.deepEqual(revshare.events[0].detail, {
    chapterId: 33, termsMode: 'REVSHARE', perHeadFee: 8.5,
  });
});

test('PERK 档 perkTemplateId 必选闸不会被点位玩法模板替代', () => {
  const state = makeComponent({ name: '夜航咖啡', templateId: 88, quotaTotal: '12' }, 'offer');

  state.component.onSubmit();

  assert.equal(state.events.length, 0, '有玩法模板但无权益模板时不得提交 PERK 供给');
});

test('REVSHARE 档 perHeadFee 必填且不得为负数', () => {
  const emittedCounts = [];
  for (const perHeadFee of ['', '-1']) {
    const state = makeComponent({ perHeadFee }, 'offer');
    state.component.data.termsMode = 'REVSHARE';
    state.component.onSubmit();
    emittedCounts.push(state.events.length);
  }
  assert.deepEqual(emittedCounts, [0, 0], '空值和负数都不得越过采购金额闸');

  const zero = makeComponent({ perHeadFee: '0' }, 'offer');
  zero.component.data.termsMode = 'REVSHARE';
  zero.component.onSubmit();
  assert.equal(zero.events.length, 1, '服务端现行契约允许零元计酬，但仍要求显式填写');
});

test('供给提交失败只受 submitting 闸约束，不在组件内伪造持久冻结态', () => {
  const state = makeComponent({ perkTemplateId: 7, quotaTotal: '12' }, 'offer');
  state.component.onSubmit();
  state.component.onInput({ currentTarget: { dataset: { key: 'quotaTotal' } }, detail: { value: '13' } });
  assert.equal(state.component.data.form.quotaTotal, '13',
    '服务端未确认 offer 存在前，组件不得用页面内存冻结用户输入');
});

test('表单打开时从真实商家资料回填门店地址，不覆盖已输入内容', () => {
  const state = makeComponent({});
  state.component.loadMerchantAddress();
  assert.equal(requests[0].url, '/api/merchant/info');
  requests[0].success({ code: '200', data: { address: '苏州市星海街 8 号' } });
  assert.equal(state.component.data.form.address, '苏州市星海街 8 号');

  state.component.data.form.address = '玩家手动改过的地址';
  requests[0].success({ code: '200', data: { address: '旧地址' } });
  assert.equal(state.component.data.form.address, '玩家手动改过的地址');
});

test('PERK 无常备权益时提供可执行的创建入口', () => {
  const state = makeComponent({});
  state.component.data.perkTemplatesLoading = false;

  state.component.loadMyPerkTemplates();
  assert.equal(requests[0].url, '/api/coop/perk-template/list');
  assert.equal(requests[0].header['Content-Type'], 'application/json');
  assert.deepEqual(JSON.parse(requests[0].data), {});
  requests[0].success({ code: '200', data: [] });
  assert.deepEqual(state.component.data.perkTemplates, []);

  state.component.goCreatePerkTemplate();
  assert.equal(navigations[0].url, '/pages/merchant/decor/perks/index');
});

test('申请点位无玩法时列出商家全部模板并提供去创建', () => {
  const state = makeComponent({});
  state.component.data.templatesLoading = false;

  state.component.loadMyTemplates();
  assert.equal(requests[0].url, '/api/template/my-list');
  assert.deepEqual(requests[0].data, { scope: 'MERCHANT' });
  requests[0].success({ code: '200', data: { rows: [] } });
  assert.deepEqual(state.component.data.templates, []);

  state.component.goCreateTemplate();
  assert.equal(navigations[0].url, '/pages/publish/temp/index?scope=MERCHANT');
});

test('PERK 选择器过滤历史缺零售价或份数的无效模板', () => {
  const state = makeComponent({});
  state.component.data.perkTemplatesLoading = false;
  state.component.loadMyPerkTemplates();
  requests[0].success({ code: 200, data: [
    { id: 1, name: '旧权益', retailValue: null, quota: null },
    { id: 2, name: '可用权益', retailValue: 68, quota: 10 },
  ] });
  assert.deepEqual(state.component.data.perkTemplates, [
    { id: 2, name: '可用权益', retailValue: 68, quota: 10 },
  ]);
});
