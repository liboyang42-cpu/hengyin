const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8');
}

function loadEvidencePanel() {
  const source = read('src/components/CyEvidencePanel/index.vue');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1];
  return new Function(script.replace(/export\s+default\s*/, 'return '))();
}

test('CyEvidencePanel 只接收页面已准备好的文本和图片证据，不拥有领域请求', () => {
  const component = loadEvidencePanel();

  assert.ok(component.props.title);
  assert.ok(component.props.items);
  assert.ok(component.props.emptyText);
  assert.equal(component.methods && component.methods.getDetail, undefined, '组件不应请求详情');
  assert.equal(component.methods && component.methods.parseSnapshot, undefined, '组件不应解析领域快照');
});

test('CyEvidencePanel 将文本和图片分开呈现，忽略尚未定义的证据类型', () => {
  const component = loadEvidencePanel();
  const vm = {
    items: [
      { kind: 'text', content: '审核快照' },
      { kind: 'image', src: 'https://example.com/evidence.jpg' },
      { kind: 'image', src: 'https://example.com/evidence.jpg', alt: '同一材料的另一条事实' },
      { kind: 'trace', value: '暂不支持' },
      { kind: 'text', content: '' }
    ]
  };

  const textItems = component.computed.textItems.call(vm);
  const imageItems = component.computed.imageItems.call(vm);
  assert.deepEqual(textItems, [{ kind: 'text', content: '审核快照' }]);
  assert.deepEqual(imageItems, [
    { kind: 'image', src: 'https://example.com/evidence.jpg' },
    { kind: 'image', src: 'https://example.com/evidence.jpg', alt: '同一材料的另一条事实' }
  ]);
  assert.deepEqual(component.computed.previewImages.call({ imageItems }), ['https://example.com/evidence.jpg']);
  assert.match(read('src/components/CyEvidencePanel/index.vue'), /:key="`\$\{item\.src\}-\$\{index\}`"/);
});

test('审核快照与俱乐部图片材料接入同一证据面板，页面保留数据准备', () => {
  for (const [relativePath, itemsName] of [
    ['src/views/business/audit/index.vue', 'auditEvidenceItems'],
    ['src/views/business/club/index.vue', 'clubEvidenceItems']
  ]) {
    const source = read(relativePath);
    assert.ok(source.includes("import CyEvidencePanel from '@/components/CyEvidencePanel'"));
    assert.ok(source.includes('<cy-evidence-panel'));
    assert.ok(source.includes(`:items="${itemsName}"`));
  }

  const audit = read('src/views/business/audit/index.vue');
  const club = read('src/views/business/club/index.vue');
  assert.match(audit, /snapshotText\(value\)/, '快照格式化仍归审核页');
  assert.match(club, /splitValues\(value\)/, '图片路径拆分仍归俱乐部页');
  assert.doesNotMatch(club, /class="evidence-section"|class="evidence-grid"/);
});
