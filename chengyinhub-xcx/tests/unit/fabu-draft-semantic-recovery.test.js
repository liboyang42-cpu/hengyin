const test = require('node:test');
const assert = require('node:assert/strict');
const { readbackIssue, createServerDraftSaver } = require('../../pages/publish/utils/publish/server-draft.js');

function fixture() {
  const sent = { name: '草稿', productType: 1, collaboratorIds: [10, '20', 10],
    tickets: [{ name: '标准票', price: '12.00', totalStock: 20, mode: 1, teamSize: 2,
      startTime: '2026-12-01 09:00:00', endTime: '2026-12-01 18:00:00',
      refundSupported: false, syncWithTheme: true, gatherLng: '121.5000' }],
    chapters: [{ id: 1, name: '第一章', nodes: [{ id: 2, name: '书店', templateId: 0 }],
      blocks: [{ type: 'text', key: 'intro', content: '原剧情', who: ' 店主 ' },
        { type: 'node', key: 'place', nodeIndex: 0, locationRequired: true }] }] };
  const detail = { topic: { id: 501, status: 0, name: '草稿' }, isDraft: true,
    collaboratorIds: ['20', 10, '10'],
    tickets: [{ id: 601, ownerId: '501', ownerType: 1, name: '标准票', price: 12,
      totalInventory: '20', mode: 1, teamSize: 2, startTime: '2026-12-01 09:00:00',
      endTime: '2026-12-01 18:00:00', refundSupported: false, syncWithTheme: true, gatherLng: 121.5 }],
    chapters: [{ id: 101, topicId: 501, name: '第一章', cmsTopicNodeList: [
      { id: 201, topicId: 501, chapterId: 101, name: '书店', templateId: 0 }],
      blocks: [{ type: 'text', key: 'intro', content: '原剧情', who: '店主' }, { type: 'node', key: 'place', nodeId: 201 }] }] };
  return { sent, detail };
}

test('正规化：数字、协作者顺序、服务端换号及nodeIndex物化不误报', () => {
  const { sent, detail } = fixture();
  assert.equal(readbackIssue(sent, detail, true), '');
});

const losses = [
  ['票价', d => { d.tickets[0].price = 0; }],
  ['库存', d => { d.tickets[0].totalInventory = 1; }],
  ['票种归属', d => { d.tickets[0].ownerId = 999; }],
  ['票种缺失', d => { delete d.tickets; }],
  ['协作者', d => { d.collaboratorIds = [10, 20]; }],
  ['协作者缺失', d => { delete d.collaboratorIds; }],
  ['剧情正文', d => { d.chapters[0].blocks[0].content = '旧剧情'; }],
  ['剧情块缺失', d => { d.chapters[0].blocks = []; }],
  ['节点块引用', d => { d.chapters[0].blocks[1].nodeId = 999; }],
  ['站点归属', d => { d.chapters[0].cmsTopicNodeList[0].chapterId = 999; }],
  ['章节归属', d => { d.chapters[0].topicId = 999; }],
];
for (const [label, mutate] of losses) test(label + '不一致：不清稿、不继续发布', () => {
  const { sent, detail } = fixture(); mutate(detail);
  assert.notEqual(readbackIssue(sent, detail, true), '');
  let successes = 0, publishes = 0, failure;
  const saver = createServerDraftSaver({ save: (p, cb) => cb({ ok: true, data: 501 }),
    readback: (id, cb) => cb({ ok: true, data: detail }),
    publish: () => { publishes++; } });
  saver.saveAndPublish(sent, { onSuccess: () => successes++, onFail: r => { failure = r; } });
  assert.equal(successes, 0); assert.equal(publishes, 0); assert.equal(failure.stage, 'readback');
});

test('票种按内容多重集匹配，允许重排和新编号，不丢重复票', () => {
  const { sent, detail } = fixture();
  sent.tickets.push({ ...sent.tickets[0], price: 25 });
  detail.tickets.unshift({ ...detail.tickets[0], id: 602, price: 25 });
  assert.equal(readbackIssue(sent, detail, true), '');
  detail.tickets[0].price = 12;
  assert.notEqual(readbackIssue(sent, detail, true), '');
});

test('blocksJson与服务端生成key、配色别名、空可选文案正规化', () => {
  const { sent, detail } = fixture();
  sent.chapters[0].blocks = [{ type: 'voice', who: '心', content: ' 听见了 ' },
    { type: 'mood', mood: 'NIGHT' }, { type: 'thought', thoughtKey: ' clue ' },
    { type: 'dream', title: ' ', images: [{ url: 'image.jpg', line: ' ' }] }];
  delete detail.chapters[0].blocks;
  detail.chapters[0].blocksJson = JSON.stringify([{ type: 'voice', key: 'generated1', who: '心', content: '听见了' },
    { type: 'mood', key: 'generated2', mood: 'BLUE' }, { type: 'thought', key: 'generated3', thoughtKey: 'clue' },
    { type: 'dream', key: 'generated4', images: [{ url: 'image.jpg' }] }]);
  assert.equal(readbackIssue(sent, detail, true), '');
  detail.chapters[0].blocksJson = '{';
  assert.notEqual(readbackIssue(sent, detail, true), '');
});

for (const field of ['name', 'startTime', 'endTime', 'saleStartTime', 'saleEndTime',
  'meetingPoint', 'meetingPointAddress', 'refundRule', 'refundSupported', 'syncWithTheme',
  'teamSize', 'gatherLng', 'gatherLat', 'needAudit', 'mode']) test('票种字段未保存：' + field, () => {
  const { sent, detail } = fixture();
  if (!Object.hasOwn(sent.tickets[0], field)) {
    const value = ['gatherLat', 'needAudit'].includes(field) ? 1 : '已填写';
    sent.tickets[0][field] = value; detail.tickets[0][field] = value;
  }
  delete detail.tickets[0][field];
  assert.notEqual(readbackIssue(sent, detail, true), '');
});

for (const [label, mutate] of [
  ['重复票号', (s, d) => { s.tickets.push({ ...s.tickets[0] }); d.tickets.push({ ...d.tickets[0] }); }],
  ['重复章节号', (s, d) => { s.chapters.push(structuredClone(s.chapters[0])); d.chapters.push(structuredClone(d.chapters[0])); }],
  ['重复站点号', (s, d) => { s.chapters[0].nodes.push({ ...s.chapters[0].nodes[0] }); d.chapters[0].cmsTopicNodeList.push({ ...d.chapters[0].cmsTopicNodeList[0] }); }],
  ['缺章节号', (s, d) => { delete d.chapters[0].id; }],
  ['非法站点号', (s, d) => { d.chapters[0].cmsTopicNodeList[0].id = true; }],
  ['缺章节列表', (s, d) => { delete d.chapters; }],
  ['缺站点列表', (s, d) => { delete d.chapters[0].cmsTopicNodeList; }],
]) test(label + '必须失败且不抛异常', () => {
  const { sent, detail } = fixture(); mutate(sent, detail);
  assert.notEqual(readbackIssue(sent, detail, true), '');
});

test('叙事beat的归属与条件不丢失；后端trim规则只应用于对应字段', () => {
  const { sent, detail } = fixture();
  sent.chapters[0].blocks[0] = { type: 'text', key: 'intro', content: ' 原剧情 ',
    beat: ' enter ', field: ' q ', qid: ' clue ', nodeIndex: 0 };
  detail.chapters[0].blocks[0] = { type: 'text', key: 'intro', content: ' 原剧情 ',
    beat: 'enter', field: 'q', qid: 'clue', nodeId: 201 };
  assert.equal(readbackIssue(sent, detail, true), '');
  detail.chapters[0].blocks[0].content = '原剧情';
  assert.notEqual(readbackIssue(sent, detail, true), '');
});

test('已经发布的未知结果回读：确认发布事实，但票种变化仍标记localChanges保留本机稿', () => {
  const { sent, detail } = fixture(); detail.isDraft = false; detail.topic.status = 1;
  detail.tickets[0].price = 0;
  let result, saves = 0, publishes = 0;
  const saver = createServerDraftSaver({ pendingPublish: () => 501,
    save: () => saves++, publish: () => publishes++, readback: (id, cb) => cb({ ok: true, data: detail }) });
  saver.saveAndPublish(sent, { onSuccess: value => { result = value; } });
  assert.equal(result.published.recovered, true); assert.equal(result.localChanges, true);
  assert.equal(saves, 0); assert.equal(publishes, 0);
});

// ActivityTicketRequest 接受 description，但 OmsTicket/Mapper 不持久化、不回显；本修复不伪造该合同。
test('只核对真实持久字段：DTO票描述不作为已存字段；地图坐标允许六位小数正规化', () => {
  const { sent, detail } = fixture();
  sent.tickets[0].description = '现有接口未持久化';
  sent.tickets[0].gatherLng = 121.12345678; detail.tickets[0].gatherLng = '121.123457';
  assert.equal(readbackIssue(sent, detail, true), '');
  detail.tickets[0].gatherLng = 121.123458;
  assert.notEqual(readbackIssue(sent, detail, true), '');
});

test('半填同名票匹配不抢占完整票的唯一回读行', () => {
  const { sent, detail } = fixture();
  sent.tickets = [{ name: '标准票' }, { name: '标准票', price: 12 }];
  detail.tickets.push({ ...detail.tickets[0], id: 602, price: 25 });
  assert.equal(readbackIssue(sent, detail, true), '');
});
