// 包 D 自动云草稿:两秒防抖云端保存接到 master 当前 saver / payload builder 上(Doc 1 §5.1 验收清单)。
const test = require('node:test');
const assert = require('node:assert/strict');
const proEditorDraft = require('../../pages/publish/utils/publish/pro-editor-draft.js');

const clone = value => JSON.parse(JSON.stringify(value));

// 回读:按提交的内容造一份服务端草稿(章节、站点拿到真实编号)
function detailFor(sent, options = {}) {
  const topicId = options.topicId || 501;
  let nodeId = 10;
  return {
    isDraft: true,
    topic: { id: topicId, status: 0, name: sent.name, configVersion: options.configVersion || 2, updateTime: options.updateTime || '2026-10-22 10:00:00' },
    chapters: (sent.chapters || []).map((chapter, ci) => ({
      id: 100 + ci, topicId, name: chapter.name,
      cmsTopicNodeList: (chapter.nodes || []).map(node => ({ id: ++nodeId, chapterId: 100 + ci, topicId, name: node.name, templateId: node.templateId || 0 })),
    })),
  };
}

function loadPage(options = {}) {
  const stored = options.stored || {};
  const sent = [], toasts = [], loading = [];
  const storage = { broken: false };
  let currentMember = 7;
  global.wx = {
    getStorageSync: k => stored[k],
    setStorageSync: (k, v) => { if (storage.broken) throw new Error('quota'); stored[k] = v; },
    removeStorageSync: k => { delete stored[k]; },
    showToast: o => toasts.push(o.title), showLoading: o => loading.push(o.title), hideLoading() {},
    getSystemInfoSync() { return {}; }, getWindowInfo() { return {}; },
  };
  global.getCurrentPages = () => [];
  global.getApp = () => ({ globalData: {}, getUserID: () => currentMember, getRequestErrorMessage: (r, d) => d, sendRequest: o => sent.push(o) });
  let page; global.Page = value => { page = value; };
  delete require.cache[require.resolve('../../pages/publish/fabu/index')];
  require('../../pages/publish/fabu/index');
  page.data = Object.assign(clone(page.data), {
    operationScope: '', editingTopicId: '', editLoaded: false, editIsDraft: false, submitting: false, draftSaving: false,
    draftUuid: 'u-1', draftMemberId: 7, baseRevision: '', pendingMaterials: [],
    formData: Object.assign(clone(page.data.formData), { name: '寒冬', configVersion: 1,
      chapters: [{ name: '第一章', nodes: [{ name: '甲', templateId: 3 }] }] }),
  });
  const deferred = [];
  page.deferCallbacks = false;
  page.flush = () => { while (deferred.length) deferred.shift()(); };
  page.setData = (patch, cb) => {
    Object.keys(patch).forEach((key) => {
      const path = key.split('.');
      let target = page.data;
      while (path.length > 1) { const k = path.shift(); target[k] = target[k] || {}; target = target[k]; }
      target[path[0]] = patch[key];
    });
    if (cb) { if (page.deferCallbacks) deferred.push(cb); else cb(); }
  };
  page._buildSubmitPayload = function () {
    const f = this.data.formData;
    return { name: f.name, publishMode: 'pro', productType: 1, configVersion: f.configVersion || '', scope: this.data.operationScope,
      chapters: (f.chapters || []).map(c => ({ name: c.name, nodes: (c.nodes || []).map(n => ({ name: n.name, templateId: n.templateId })) })) };
  };
  page.applied = [];
  page.applyEditingTopic = function (d) {
    this.applied.push(d);
    this.setData({ editIsDraft: d.isDraft === true, editLoaded: true, 'formData.name': d.topic.name, 'formData.configVersion': d.topic.configVersion,
      'formData.chapters': d.chapters.map(c => ({ id: c.id, name: c.name, nodes: c.cmsTopicNodeList.map(n => ({ id: n.id, name: n.name, templateId: n.templateId })) })) });
    this._finishEditingDraftBootstrap(d);
    return true;
  };
  page._installDraftAutosave();
  page._draftAutosaveReady = true;
  page._cloudAutosaveEnabled = true;
  page.type = name => page.setData({ 'formData.name': name });
  const requests = url => sent.filter(r => r.url === url);
  const writes = () => sent.filter(r => /\/api\/topic\/(v2\/)?(create|update)$/.test(r.url));
  // 完成一次保存:写入回包 + 回读回包
  const answer = (write, options2 = {}) => {
    const body = JSON.parse(write.data);
    write.success({ code: 200, data: { topicId: options2.topicId || 501 } });
    const back = sent[sent.length - 1];
    assert.equal(back.url, '/api/topic/author-edit-detail');
    if (options2.beforeReadback) options2.beforeReadback();
    back.success({ code: 200, data: options2.detail || detailFor(body, options2) });
    return body;
  };
  const envelope = (identity = { draftUuid: 'u-1' }) => proEditorDraft.loadDraft(wx, identity, { memberId: 7, operationScope: '' });
  return { page, sent, toasts, loading, stored, storage, requests, writes, answer, envelope, switchMember: id => { currentMember = id; } };
}

test.beforeEach((t) => t.mock.timers.enable({ apis: ['setTimeout'] }));

test('连续输入：先逐次写本机，停手两秒后只发一次云端保存；回读通过才标已存云端，不弹面板不盖加载层', (t) => {
  const { page, writes, answer, loading, envelope } = loadPage();
  ['寒', '寒冬', '寒冬夜', '寒冬夜行', '寒冬夜行人'].forEach((name) => { page.type(name); t.mock.timers.tick(500); });
  assert.equal(page.data.themeSaveLabel, '已存本机');
  assert.equal(envelope().envelope.formData.name, '寒冬夜行人', '每次编辑先落本机');
  assert.equal(writes().length, 0, '还在输入时不发请求');
  t.mock.timers.tick(1500);
  assert.equal(writes().length, 1, '连续输入只发一次');
  assert.equal(writes()[0].url, '/api/topic/v2/create');
  assert.equal(JSON.parse(writes()[0].data).name, '寒冬夜行人');
  assert.equal(page.data.themeSaveLabel, '保存中');
  answer(writes()[0]);
  assert.equal(page.data.themeSaveState, 'saved');
  assert.equal(page.data.themeSaveLabel, '已存云端');
  assert.equal(page.data.resultSheet.show, false, '自动保存不弹结果面板');
  assert.deepEqual(loading, [], '自动保存不盖加载层');
  t.mock.timers.tick(5000);
  assert.equal(writes().length, 1, '内容没变不重复上传');
});

test('空稿：未命名时只存本机，不建空主题、不弹提示', (t) => {
  const { page, sent, toasts } = loadPage();
  page.type('');
  page.setData({ 'formData.chapters': [{ name: '第一章', nodes: [] }] });
  t.mock.timers.tick(10000);
  assert.equal(sent.length, 0);
  assert.deepEqual(toasts, []);
  page.saveServerDraft({ type: 'tap' });
  assert.match(toasts[0], /起个名字/, '用户明确点保存才提示');
});

test('已发布主题不进自动草稿；回填未完成也不存', (t) => {
  const { page, sent } = loadPage();
  Object.assign(page.data, { editingTopicId: '900', editLoaded: true, editIsDraft: false });
  page.type('已发布的主题改名');
  t.mock.timers.tick(5000);
  assert.equal(sent.length, 0);
  Object.assign(page.data, { editingTopicId: '901', editLoaded: false, editIsDraft: true });
  page.type('回填中');
  t.mock.timers.tick(5000);
  assert.equal(sent.length, 0);
});

test('本机存储写失败：状态如实显示「本机未存」，不伪造恢复稿', (t) => {
  const { page, storage, envelope, toasts } = loadPage();
  storage.broken = true;
  page.type('写不进本机');
  assert.equal(page.data.themeSaveLabel, '本机未存');
  assert.equal(envelope().status, 'missing');
  assert.equal(toasts.filter(x => /本地草稿保存失败/.test(x)).length, 1);
  page.type('写不进本机2');
  assert.equal(toasts.filter(x => /本地草稿保存失败/.test(x)).length, 1, '同一故障只提示一次');
});

test('断网：原意图冻结且本机新编辑保留，不自动重发；手动核对沿用原输入', (t) => {
  const { page, writes, envelope } = loadPage();
  page.type('断网时写');
  t.mock.timers.tick(2000);
  const firstKey = JSON.parse(writes()[0].data).requestId;
  writes()[0].fail({});
  assert.equal(page.data.themeSaveLabel, '待确认', '首建丢回包:结果不明');
  assert.equal(page.data.resultSheet.show, false);
  assert.equal(envelope().envelope.formData.name, '断网时写');
  t.mock.timers.tick(10000);
  assert.equal(writes().length, 1, '结果不明时不自动盲重试');
  page.type('断网后继续写');
  t.mock.timers.tick(2000);
  assert.equal(writes().length, 1, '新编辑不能自动重发未知创建');
  page.saveServerDraft();
  assert.equal(writes().length, 2);
  assert.equal(JSON.parse(writes()[1].data).name, '断网时写', '保留原意图输入');
  assert.equal(JSON.parse(writes()[1].data).requestId, firstKey, '同一份本机草稿沿用原意图键，服务端可认出已建的那份');
  assert.equal(writes()[1].url, '/api/topic/v2/create');
});

test('A 号的定时器与迟到回包在切到 B 后不发请求、不改页面、不写 B 的存储', (t) => {
  const { page, writes, sent, stored, switchMember } = loadPage();
  page.type('A 的稿');
  switchMember(8);
  t.mock.timers.tick(2000);
  assert.equal(sent.length, 0, '换号后过时调度作废');

  switchMember(7);
  page.type('A 的稿 2');
  t.mock.timers.tick(2000);
  assert.equal(writes().length, 1);
  switchMember(8);
  const keys = Object.keys(stored).sort();
  writes()[0].success({ code: 200, data: { topicId: 501 } });
  assert.equal(sent.length, 1, '迟到回包不再刷新');
  assert.equal(page.data.editingTopicId, '');
  assert.notEqual(page.data.themeSaveState, 'saved');
  assert.deepEqual(Object.keys(stored).sort(), keys);
  assert.ok(!Object.keys(stored).some(k => k.includes(':8')), '不写 B 的存储');
  page.type('B 打开后的编辑');
  t.mock.timers.tick(5000);
  assert.equal(writes().length, 1, '草稿归属 A 时 B 不自动保存');
});

test('保存中继续编辑：不换上回读版本、不标已存云端，接上服务端版本号后只再存一次', (t) => {
  const { page, writes, answer, requests } = loadPage();
  page.type('第一版');
  t.mock.timers.tick(2000);
  answer(writes()[0], { configVersion: 5, beforeReadback: () => page.type('第二版') });
  assert.equal(page.applied.length, 0, '旧响应不覆盖新编辑');
  assert.equal(page.data.formData.name, '第二版');
  assert.equal(page.data.themeSaveLabel, '已存本机');
  assert.equal(Number(page.data.formData.configVersion), 5, '接上自己这次写入推进的版本');
  assert.equal(page.data.editingTopicId, '501');
  t.mock.timers.tick(2000);
  assert.equal(writes().length, 2, '只再存一次');
  const body = JSON.parse(writes()[1].data);
  assert.equal(writes()[1].url, '/api/topic/v2/update');
  assert.equal(body.id, '501'); assert.equal(body.name, '第二版'); assert.equal(Number(body.configVersion), 5);
  answer(writes()[1], { configVersion: 6 });
  assert.equal(page.data.themeSaveLabel, '已存云端');
  assert.equal(requests('/api/topic/author-edit-detail').length, 2);
});

test('保存与发布竞争：自动保存在途时发布不并发写；发布在途时定时器不起保存', (t) => {
  const { page, writes, toasts, sent } = loadPage();
  page.type('竞争');
  t.mock.timers.tick(2000);
  assert.equal(writes().length, 1);
  page._doSubmit();
  assert.equal(writes().length, 1, '同一草稿不并发写');
  assert.match(toasts.at(-1), /保存完成后再发布/);
  page.submitForm();
  assert.equal(writes().length, 1);

  const other = loadPage();
  Object.assign(other.page.data, { editingTopicId: '501', editLoaded: true, editIsDraft: true });
  other.page._doSubmit();
  assert.equal(other.writes().length, 1, '发布先存草稿');
  other.page.type('发布在途时的编辑');
  t.mock.timers.tick(5000);
  assert.equal(other.writes().length, 1, '发布在途不起自动保存');
  assert.equal(sent.length, 1);
});

test('UNKNOWN：保存回读网络失败标待确认且不自动重试；发布 UNKNOWN 后编辑不再自动上云', (t) => {
  const { page, writes, sent } = loadPage();
  Object.assign(page.data, { editingTopicId: '501', editLoaded: true, editIsDraft: true });
  page.type('更新');
  t.mock.timers.tick(2000);
  writes()[0].success({ code: 200, data: { topicId: 501 } });
  sent.at(-1).fail({});
  assert.equal(page.data.themeSaveLabel, '待确认');
  t.mock.timers.tick(10000);
  assert.equal(writes().length, 1, '结果不明不盲重试');

  page._publishAttempt = { status: 'UNKNOWN', kind: 'SERVER_DRAFT', topicId: '501' };
  page.type('发布结果不明后又改');
  t.mock.timers.tick(10000);
  assert.equal(sent.length, 2, '先由用户明确操作去确认原发布，不自动保存');
  page._publishAttempt = { status: 'UNKNOWN', kind: 'CREATE' };
  page.type('普通发布结果不明');
  t.mock.timers.tick(10000);
  assert.equal(sent.length, 2, '新建发布结果不明时自动建草稿会造成重复主题');
});

test('旧CAS接口仅给500文案：保留未知与原输入，不靠文案推断事务已回滚', (t) => {
  const { page, writes, toasts } = loadPage();
  Object.assign(page.data, { editingTopicId: '501', editLoaded: true, editIsDraft: true });
  page.type('另一端也在改');
  t.mock.timers.tick(2000);
  writes()[0].success({ code: 500, msg: '路线配置已更新，请刷新后重试' });
  assert.equal(page.data.themeSaveLabel, '待确认');
  assert.equal(page.data.formData.name, '另一端也在改');
  assert.equal(page.data.resultSheet.show, false);
  page.type('继续改'); t.mock.timers.tick(10000);
  assert.equal(writes().length, 1, '不反复撞同一个冲突');
  page.saveServerDraft({ type: 'tap' });
  assert.equal(writes().length, 1, '手动核对只读，不重放未知写');
  assert.equal(page._serverDraftAttempt.payload.name, '另一端也在改');
});

test('回读章节/站点身份不符：不清本机恢复稿、不标已存云端、暂停自动保存', (t) => {
  const { page, writes, sent, envelope } = loadPage();
  assert.equal(page._persistDraftEnvelope(), true);
  page.type('身份确认');
  t.mock.timers.tick(2000);
  const body = JSON.parse(writes()[0].data);
  const bad = detailFor(body);
  bad.chapters[0].cmsTopicNodeList[0].chapterId = 999;
  writes()[0].success({ code: 200, data: { topicId: 501 } });
  sent.at(-1).success({ code: 200, data: bad });
  assert.equal(page.applied.length, 0);
  assert.notEqual(page.data.themeSaveState, 'saved');
  assert.equal(page.data.themeSaveLabel, '待确认');
  assert.equal(envelope().status, 'ready', '新建时那份本机恢复稿仍在');
  assert.equal(envelope({ topicId: '501' }).envelope.formData.name, '身份确认', '按主题号的恢复稿也在');
  page.type('继续');
  t.mock.timers.tick(10000);
  assert.equal(writes().length, 1);
});

test('保存在途时改了模板绑定：回读版本不回退绑定', (t) => {
  const { page, writes, answer } = loadPage();
  page.type('绑定');
  t.mock.timers.tick(2000);
  answer(writes()[0], { beforeReadback: () => page.setData({ 'formData.chapters': [{ name: '第一章', nodes: [{ name: '甲', templateId: 4 }] }] }) });
  assert.equal(page.applied.length, 0);
  assert.equal(page.data.formData.chapters[0].nodes[0].templateId, 4, '新绑定保留');
  t.mock.timers.tick(2000);
  assert.equal(JSON.parse(writes()[1].data).chapters[0].nodes[0].templateId, 4, '下一次保存带上新绑定');
});

test('编辑弹窗打开时不上云，弹窗内未提交内容不被回读覆盖；关闭后接力保存一次', (t) => {
  const { page, writes, answer } = loadPage();
  page.type('弹窗');
  page.setData({ popChapterNodes: true, nodesForm: { name: '还没点完成的站点', templateId: 9 } });
  t.mock.timers.tick(10000);
  assert.equal(writes().length, 0, '弹窗开着不保存');
  page.setData({ 'storyEditor.show': true, popChapterNodes: false });
  t.mock.timers.tick(10000);
  assert.equal(writes().length, 0, '故事流编辑器同样等关闭');
  page.setData({ 'storyEditor.show': false });
  t.mock.timers.tick(2000);
  assert.equal(writes().length, 1, '关闭后接力保存');
  assert.deepEqual(page.data.nodesForm, { name: '还没点完成的站点', templateId: 9 });
  answer(writes()[0]);
  assert.deepEqual(page.data.nodesForm, { name: '还没点完成的站点', templateId: 9 });
});

test('隐藏取消调度、回来接力；卸载后不再发请求；重开从本机恢复稿取回最新内容', (t) => {
  const { page, writes, envelope } = loadPage();
  page.type('隐藏前');
  page.onHide();
  t.mock.timers.tick(10000);
  assert.equal(writes().length, 0, '隐藏期间不发');
  assert.equal(envelope().envelope.formData.name, '隐藏前', '本机已可恢复');
  page.onShow();
  t.mock.timers.tick(2000);
  assert.equal(writes().length, 1, '回来后接力保存');

  const second = loadPage();
  second.page.type('卸载前');
  second.page.onUnload();
  t.mock.timers.tick(10000);
  assert.equal(second.writes().length, 0, '卸载后不发');
  const reopened = proEditorDraft.loadDraft(wx, { draftUuid: 'u-1' }, { memberId: 7, operationScope: '' });
  assert.equal(reopened.status, 'ready');
  assert.equal(reopened.envelope.formData.name, '卸载前', '重开时可从本机恢复');
});

test('手动保存与自动保存同一 saver：手动成功给面板并标已存云端，之后不重复上传；恢复稿首次打开会排队上云', (t) => {
  const { page, writes, answer, loading, envelope } = loadPage();
  page.type('手动');
  page.saveServerDraft({ type: 'tap' });
  assert.equal(writes().length, 1);
  assert.deepEqual(loading, ['正在保存草稿']);
  page.saveServerDraft({ type: 'tap' });
  t.mock.timers.tick(2000);
  assert.equal(writes().length, 1, '手动保存在途，自动保存与重复点击都不并发');
  answer(writes()[0]);
  assert.equal(page.data.resultSheet.title, '草稿已保存到云端');
  assert.equal(page.data.themeSaveLabel, '已存云端');
  assert.equal(envelope().status, 'missing', '刷新确认后才清新建恢复稿');
  t.mock.timers.tick(10000);
  assert.equal(writes().length, 1);

  // 恢复稿:onLoad 恢复本机稿后按一次编辑处理
  const restored = loadPage();
  restored.page._onDraftEdited(true);
  assert.equal(restored.page.data.themeSaveLabel, '已存本机');
  t.mock.timers.tick(2000);
  assert.equal(restored.writes().length, 1);
});

test('真机 setData 回调异步：换上回读版本后的回写不把已存云端打回去，也不形成保存循环', (t) => {
  const { page, writes, answer } = loadPage();
  page.type('异步');
  t.mock.timers.tick(2000);
  page.deferCallbacks = true;
  answer(writes()[0], { configVersion: 7 });
  page.flush();
  assert.equal(page.data.themeSaveLabel, '已存云端');
  page.deferCallbacks = false;
  t.mock.timers.tick(10000);
  assert.equal(writes().length, 1, '刷新版本回写不触发新保存');
});

test('saver：hasChanges 只作用于保存草稿；发布路径仍按 master 的 localChanges 收口', () => {
  const { createServerDraftSaver } = require('../../pages/publish/utils/publish/server-draft.js');
  const calls = [];
  const saver = createServerDraftSaver({
    save: (p, cb) => calls.push(cb), readback: (id, cb) => calls.push(cb), publish: (id, cb) => calls.push(cb),
  });
  const sent = { name: '寒冬', chapters: [{ name: '第一章', nodes: [{ name: '甲', templateId: 3 }] }] };
  const events = [];
  const h = { hasChanges: () => true, onSuccess: r => events.push(['ok', r]), onFail: r => events.push(['fail', r]) };
  saver.save(sent, h);
  calls[0]({ ok: true, data: { topicId: 501 } });
  calls[1]({ ok: true, data: detailFor(sent) });
  assert.equal(events[0][0], 'fail');
  assert.equal(events[0][1].localChanges, true);
  assert.equal(events[0][1].stage, 'readback');
  assert.equal(events[0][1].detail.topic.id, 501, '带刷新结果供页面接版本号');
  saver.saveAndPublish(sent, h);
  calls[2]({ ok: true, data: { topicId: 501 } });
  calls[3]({ ok: true, data: detailFor(sent) });
  assert.equal(calls.length, 5, '发布不受 hasChanges 拦截');
});

test('F-2：真机 setData 回调异步时，保存换版与重开都不把草稿标脏；重开不编辑零写入，之后一次编辑只存一次', (t) => {
  const first = loadPage();
  first.page.deferCallbacks = true;
  first.page.type('重开确认');
  first.page.flush();
  t.mock.timers.tick(2000);
  first.answer(first.writes()[0], { configVersion: 2 });
  first.page.flush();
  t.mock.timers.tick(10000);
  assert.equal(first.writes().length, 1);
  assert.equal(first.envelope({ topicId: '501' }).status, 'missing', '换上刷新版本后不再回写一份与云端相同的本机恢复稿');
  assert.equal(first.envelope().status, 'missing');

  // 重开同一份已存云端的草稿(共享同一份本机存储)
  const second = loadPage({ stored: first.stored });
  const { page, sent, writes } = second;
  page.deferCallbacks = true;
  Object.assign(page.data, { editingTopicId: '501', draftUuid: '', draftMemberId: '' });
  page._draftAutosaveReady = false;
  page.loadEditingTopic('501');
  const load = sent.find(r => r.url === '/api/topic/author-edit-detail');
  load.success({ code: 200, data: detailFor({ name: '重开确认', chapters: [{ name: '第一章', nodes: [{ name: '甲', templateId: 3 }] }] }, { configVersion: 2 }) });
  page.flush();
  t.mock.timers.tick(10000);
  page.flush();
  assert.equal(writes().length, 0, '重开不编辑不发任何写请求');
  assert.equal(page.data.themeSaveLabel, '已存云端');
  assert.equal(second.envelope({ topicId: '501' }).status, 'missing', '重开本身不制造本机恢复稿');

  page.type('重开后改名');
  page.flush();
  assert.equal(page.data.themeSaveLabel, '已存本机');
  t.mock.timers.tick(2000);
  assert.equal(writes().length, 1, '真实编辑后 2 秒只存一次');
  assert.equal(writes()[0].url, '/api/topic/v2/update');
  assert.equal(JSON.parse(writes()[0].data).name, '重开后改名');
});

test('F-2 配套：重开时本机恢复稿与云端不同才上传一次；与云端相同只标已存云端不发请求', (t) => {
  const cloud = { name: '恢复稿确认', chapters: [{ name: '第一章', nodes: [{ name: '甲', templateId: 3 }] }] };
  const run = (localName) => {
    const ctx = loadPage();
    proEditorDraft.saveDraft(wx, { memberId: 7, operationScope: '', topicId: '501', baseRevision: '2026-10-22 10:00:00',
      formData: Object.assign({}, ctx.page.data.formData, { name: localName, configVersion: 2 }), pendingMaterials: [], editorMeta: {} });
    ctx.page.deferCallbacks = true;
    Object.assign(ctx.page.data, { editingTopicId: '501', draftUuid: '', draftMemberId: '' });
    ctx.page._draftAutosaveReady = false;
    ctx.page.loadEditingTopic('501');
    ctx.sent.find(r => r.url === '/api/topic/author-edit-detail').success({ code: 200, data: detailFor(cloud, { configVersion: 2 }) });
    ctx.page.flush();
    t.mock.timers.tick(2000);
    ctx.page.flush();
    t.mock.timers.tick(8000);
    return ctx;
  };
  const differs = run('本机改过的名字');
  assert.equal(differs.page.data.formData.name, '本机改过的名字');
  assert.equal(differs.writes().length, 1, '恢复稿与云端不同:上传一次');
  assert.equal(JSON.parse(differs.writes()[0].data).name, '本机改过的名字');
  const same = run('恢复稿确认');
  assert.equal(same.writes().length, 0, '恢复稿与云端相同:不发请求');
  assert.equal(same.page.data.themeSaveLabel, '已存云端');
});
