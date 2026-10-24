// 由原生页面表单方法生成；Java 编译测试读取相同结果，不手写运行结构 JSON。
global.getApp = global.getApp || (() => ({ globalData: {} }));
global.wx = global.wx || { showToast() {} };
const runtime = require('../../../pages/publish/fabu/theme-runtime.js');
const structure = require('../../../pages/publish/fabu/theme-runtime-structure.js');
const matrix = require('./gameplay-support-matrix.json').data;
const tools = require('../../../pages/publish/utils/publish/template-tools.js');
const binding = require('../../../pages/publish/utils/publish/template-binding.js');
const copy = value => JSON.parse(JSON.stringify(value));
const event = (field, value, index = 0) => ({ currentTarget: { dataset: { field, index } }, detail: { value } });
function page() {
  const templates = [
    { id: 701, validationMethod: 4, advancedConfigJson: JSON.stringify(tools.apply('cost', { mode: 'FIXED', slotMode: true, slots: [], groups: [{ rows: [{ kind: 'VALUE', key: '', label: '支付的数值', amount: '1' }] }] }, { schemaVersion: 1 }).value) },
    { id: 702, validationMethod: 1, advancedConfigJson: JSON.stringify({ schemaVersion: 1, qa: { enabled: true, mode: 'TYPE', questions: [{ title: '请阅读现场线索后回答', answer: '共同前进' }], maxTries: 0 } }) },
  ];
  const p = { data: { ...copy(runtime.data), ...copy(structure.data), editingTopicId: 77, popChapterIndex: 0, popChapterAction: 1,
    supportMatrix: matrix, formData: { name: '末日寒冬', chapters: [{ id: 101, name: '第一幕', nodes: [{ id: 201, name: '出发站', templateId: 701, templateInfo: templates[0] }, { id: 202, name: '线索站', templateId: 702, templateInfo: templates[1] }, { id: 203, name: '决定站' }] }], journeyRules: '', themeRuntimeConfig: '' } },
    _templateDetailsById: Object.fromEntries(templates.map(row => [row.id, row])),
    setData(patch) { Object.entries(patch).forEach(([key, value]) => { const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.'); let at = this.data; for (const part of parts.slice(0, -1)) at = at[part]; at[parts.at(-1)] = value; }); },
  };
  Object.assign(p, runtime.methods, structure.methods);
  return p;
}
function open(p, kind, index = 0) { p.openStructure({ currentTarget: { dataset: { kind, index } } }); }
function build() {
  const p = page();
  p.onRuntimeStatToggle({ currentTarget: { dataset: { stat: 'hp' } }, detail: { value: true } });
  p.onRuntimeName(event('hpName', '保温值'));
  p.openChapterBackpack(); const now = Date.now, random = Math.random;
  Date.now = () => 1791504000000; Math.random = () => 0.125;
  try { p.addRuntimeContent({ currentTarget: { dataset: { kind: 'ITEM' } } }); } finally { Date.now = now; Math.random = random; }
  p.onRuntimeItem(event('name', '补给包')); p.onRuntimeItem(event('image', 'https://example.test/supply.png'));
  open(p, 'mode'); p.chooseStructureMode({ currentTarget: { dataset: { key: 'WORLD_SHARED' } } }); p.saveStructure();
  const first = p.data.formData.chapters[0].nodes[0], info = p._templateDetailsById[first.templateId];
  const slots = binding.slotsOf(info);
  const resourceBinding = binding.reconcile(slots, first.templateId, null, [], p.data.formData.journeyRules, p.data.formData.themeRuntimeConfig, info);
  const mapped = binding.bind(resourceBinding, slots, slots[0].key, 'sys.hp', p.data.formData.journeyRules, p.data.formData.themeRuntimeConfig, info);
  const config = p._runtimeConfig(); config.templateBindings = { [first.id]: mapped }; p._saveRuntime(config);
  p.onChapterHostUnlock({ detail: { value: true } }); // 章节卡行内开关（协调者界面）直接写幕
  p.data.nodesForm = p.data.formData.chapters[0].nodes[1];
  open(p, 'quests'); p.onStructureLocation({ detail: { value: 0 } }); p.onStructureGuide({ detail: { value: true } });
  p.toggleStructureFallback({ currentTarget: { dataset: { id: 201 } } }); p.saveStructure();
  open(p, 'endings');
  p.addStructureEnding(); p.onStructureEnding(event('title', '守住据点')); p.onStructureEnding(event('summary', '队伍完成共同目标。'));
  p.addStructureEnding(); p.onStructureEnding(event('title', '安全撤离', 1)); p.onStructureEnding(event('summary', '现场无法继续时的默认收尾。', 1));
  p.chooseStructureFallbackEnding({ currentTarget: { dataset: { index: 1 } } }); p.saveStructure();
  const endings = p._runtimeConfig().endings;
  const vote = tools.apply('vote', { votePolicy: 'MAJORITY_ACCEPTANCE', decidesEnding: true, plans: endings.map(row => ({ title: row.title, summary: row.summary, endingKey: row.id, materialCost: [] })) }, { schemaVersion: 1, scan: { enabled: true, kind: 'TEXT', reply: '核对现场并共同决定' } });
  if (vote.error) throw Error(vote.error);
  const voteTemplate = { id: 703, validationMethod: 4, advancedConfigJson: JSON.stringify(vote.value) };
  p._templateDetailsById[703] = voteTemplate;
  p.data.formData.chapters[0].nodes[2] = { id: 203, name: '决定站', templateId: 703, templateInfo: voteTemplate };
  open(p, 'acts'); p.saveStructure();
  if (p.data.structureError) throw Error(p.data.structureError);
  return { name: p.data.formData.name, runtime: JSON.parse(p._runtimePayload()), journeyRules: JSON.parse(p.data.formData.journeyRules), chapters: p.data.formData.chapters, templates: Object.values(p._templateDetailsById) };
}
module.exports = { page, event, open, build };
if (require.main === module) process.stdout.write(JSON.stringify(build(), null, 2) + '\n');
