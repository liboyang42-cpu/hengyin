// BL-183:圈层城市实例「选择供给角色」。模板副本8个候选全关,唯一能重新开启的是
// POST /api/circle-theme/instance/configure —— 这里钉住:不预选、前端先按同一规则校验、
// 只有 200 才关抽屉并重载回读、失败原文展示、断网不说成功也不说失败。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const read = (p) => fs.readFileSync(path.resolve(__dirname, '../..', p), 'utf8');

let component;
let page;
const requests = [];
const tips = [];
global.wx = new Proxy({}, { get: () => () => ({}) });
global.getApp = () => ({
  globalData: {},
  sendRequest(opts) { requests.push(opts); },
  tips(msg) { tips.push(msg); },
  getRequestErrorMessage(res, fallback) { return (res && res.msg) || fallback; },
});
global.Component = (config) => { component = config; };
global.Behavior = (b) => b;
global.Page = (config) => { page = config; };
require('../../pages/topic/components/project-host/index.js');
require('../../pages/topic/merchantinfo/merchantinfo.js');

function hostInstance(themeCode, config) {
  const inst = {
    data: Object.assign({}, component.data, { hostTopic: { circleThemeCode: themeCode }, circleConfig: config || {} }),
    events: [],
    setData(patch) { Object.assign(this.data, patch); },
    triggerEvent(name, detail) { this.events.push(detail); },
  };
  Object.keys(component.methods).forEach((k) => { inst[k] = component.methods[k].bind(inst); });
  return inst;
}

function pageInstance() {
  const inst = {
    data: { topicId: '88', operationScope: 'MERCHANT', hostTopic: { circleThemeCode: 'FRIENDS' },
      circleConfig: { show: false, submitting: false, errorText: '' } },
    reloads: 0,
    setData(patch) {
      Object.keys(patch).forEach((key) => {
        const parts = key.split('.');
        if (parts.length === 2) this.data[parts[0]] = Object.assign({}, this.data[parts[0]], { [parts[1]]: patch[key] });
        else this.data[key] = patch[key];
      });
    },
    loadProjectHome() { this.reloads += 1; },
  };
  ['openCircleConfig', 'closeCircleConfig', 'submitCircleConfig'].forEach((k) => { inst[k] = page[k].bind(inst); });
  return inst;
}

const submitEvent = (codes) => ({ currentTarget: { dataset: { candidateCodes: codes } } });

test('打开抽屉列出本主题8个候选且一个都不预选', () => {
  const host = hostInstance('FRIENDS');
  component.observers['circleConfig.show'].call(host, true);
  assert.equal(host.data.circleCandidates.length, 8);
  assert.ok(host.data.circleCandidates.every((item) => item.picked === false));
  host.toggleCircleCandidate({ currentTarget: { dataset: { code: 'P1' } } });
  component.observers['circleConfig.show'].call(host, false);
  component.observers['circleConfig.show'].call(host, true);
  assert.ok(host.data.circleCandidates.every((item) => item.picked === false), '重新打开要清空上次勾选');
});

test('未知主题不编造候选，给出说明', () => {
  const host = hostInstance('UNKNOWN');
  component.observers['circleConfig.show'].call(host, true);
  assert.deepEqual(host.data.circleCandidates, []);
  assert.ok(host.data.circleConfigHint);
});

test('不满足3—4个/三类角色时不发事件，只给提示', () => {
  const host = hostInstance('FRIENDS');
  host.resetCircleCandidates();
  ['P1', 'P2'].forEach((code) => host.toggleCircleCandidate({ currentTarget: { dataset: { code } } }));
  host.emitCircleConfigSubmit();
  assert.equal(host.events.length, 0);
  assert.match(host.data.circleConfigHint, /3—4/);
});

test('满足规则时把勾选随事件带回页面', () => {
  const host = hostInstance('FRIENDS');
  host.resetCircleCandidates();
  ['P1', 'P3', 'P4'].forEach((code) => host.toggleCircleCandidate({ currentTarget: { dataset: { code } } }));
  host.emitCircleConfigSubmit();
  assert.deepEqual(host.events, [{ act: 'submitCircleConfig', dataset: { candidateCodes: ['P1', 'P3', 'P4'] } }]);
});

test('提交中不响应勾选和关闭', () => {
  const host = hostInstance('FRIENDS', { show: true, submitting: true });
  host.resetCircleCandidates();
  host.toggleCircleCandidate({ currentTarget: { dataset: { code: 'P1' } } });
  host.emitCloseCircleConfig();
  assert.ok(host.data.circleCandidates.every((item) => !item.picked));
  assert.equal(host.events.length, 0);
});

test('页面提交走 configure 接口，200 后关抽屉并重载回读', () => {
  requests.length = 0; tips.length = 0;
  const p = pageInstance();
  p.openCircleConfig();
  p.submitCircleConfig(submitEvent(['P1', 'P3', 'P4']));
  p.submitCircleConfig(submitEvent(['P1', 'P3', 'P4']));
  assert.equal(requests.length, 1, '提交中重复点击不重复请求');
  const req = requests[0];
  assert.equal(req.url, '/api/circle-theme/instance/configure');
  assert.equal(req.method, 'POST');
  assert.deepEqual(JSON.parse(req.data), { topicId: 88, candidateCodes: ['P1', 'P3', 'P4'], scope: 'MERCHANT' });
  req.success({ code: 200, data: 3 });
  req.complete();
  assert.equal(p.data.circleConfig.show, false);
  assert.equal(p.data.circleConfig.submitting, false);
  assert.equal(p.reloads, 1);
  assert.equal(tips.length, 1);
});

test('业务拒绝时抽屉不关、原文展示、不提示成功', () => {
  requests.length = 0; tips.length = 0;
  const p = pageInstance();
  p.openCircleConfig();
  p.submitCircleConfig(submitEvent(['P1', 'P3', 'P4']));
  requests[0].success({ code: 500, msg: '只有城市实例的内容主体本人可以配置供给角色' });
  requests[0].complete();
  assert.equal(p.data.circleConfig.show, true);
  assert.equal(p.data.circleConfig.errorText, '只有城市实例的内容主体本人可以配置供给角色');
  assert.equal(tips.length, 0);
  assert.equal(p.reloads, 0);
});

test('断网时结果未确认并重载一次，不提示成功', () => {
  requests.length = 0; tips.length = 0;
  const p = pageInstance();
  p.openCircleConfig();
  p.submitCircleConfig(submitEvent(['P1', 'P3', 'P4']));
  requests[0].fail({ errMsg: 'request:fail' });
  requests[0].complete();
  assert.match(p.data.circleConfig.errorText, /未确认/);
  assert.equal(p.reloads, 1);
  assert.equal(tips.length, 0);
});

test('入口只在「30天供给复核」区，页面派发白名单与宿主传参齐全', () => {
  const hostWxml = read('pages/topic/components/project-host/index.wxml');
  const sec = hostWxml.slice(hostWxml.indexOf('30天供给复核'), hostWxml.indexOf('page-pad'));
  assert.match(sec, /data-act="openCircleConfig"/);
  const js = read('pages/topic/merchantinfo/merchantinfo.js');
  assert.match(js, /openCircleConfig: 1, closeCircleConfig: 1, submitCircleConfig: 1/);
  assert.match(read('pages/topic/merchantinfo/merchantinfo.wxml'), /circle-config="\{\{circleConfig\}\}"/);
});
