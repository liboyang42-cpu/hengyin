const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/chapteroffer/index.vue'), 'utf8');
const ruoyiSource = fs.readFileSync(path.resolve(__dirname, '../../src/utils/ruoyi.js'), 'utf8');
const realToTimestamp = new Function(ruoyiSource.replace(/^export function /gm, 'function ') + '\nreturn toTimestamp;')();

function loadChapterOffer() {
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{[^}]+\} from "@\/api\/business\/chapterOffer";$/m, 'const { offerQueue, adjustOffer, deactivateOffer } = dependencies;')
    .replace(/^import \{ toTimestamp \} from '@\/utils\/ruoyi'$/m, '')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};');
  return new Function('dependencies', 'toTimestamp', script.replace(/export\s+default\s*/, 'return '))({
    offerQueue: () => Promise.resolve({ data: [] }),
    adjustOffer: () => Promise.resolve({}),
    deactivateOffer: () => Promise.resolve({})
  }, realToTimestamp);
}

test('商家承接资质抽屉复用对象头和证据面板，不再手写字段与营业执照图片', () => {
  assert.ok(source.includes("import CyEntityHeader from '@/components/CyEntityHeader'"));
  assert.ok(source.includes("import CyEvidencePanel from '@/components/CyEvidencePanel'"));
  assert.match(source, /<cy-entity-header[\s\S]*?:status="qualHeaderStatus"[\s\S]*?:facts="qualHeaderFacts"/);
  assert.match(source, /<cy-evidence-panel[\s\S]*?:items="qualEvidenceItems"[\s\S]*?empty-text="未上传营业执照，无法验证接待资质"/);
  assert.doesNotMatch(source, /<el-image\s+v-if="qual\.row\.businessLicense"/);
});

test('资质对象头只展示队列已提供的四项当前事实，并正确识别待审核与未建档', () => {
  const component = loadChapterOffer();
  const row = {
    merchantId: 18,
    mmsStatus: 0,
    mmsPhone: '13800000000',
    mmsAddress: '黄浦区外滩 1 号',
    mmsCapacity: 24,
    merchantPerkUsed: 3,
    merchantPerkTotal: 5,
    merchantRevshareCount: 2,
    businessLicense: 'https://example.com/license.jpg'
  };
  const vm = { qual: { row }, perkFulfillment: component.methods.perkFulfillment };

  assert.deepEqual(component.computed.qualHeaderStatus.call(vm), { label: '待审核', tone: 'warning' });
  assert.deepEqual(component.computed.qualHeaderFacts.call(vm), [
    { label: '商家 ID', value: '#18' },
    { label: '联系电话', value: '13800000000' },
    { label: '接待能力', value: '24 人' },
    { label: '历史履约', value: '权益 3/5(60%) · 计酬 2 笔' }
  ]);
  assert.deepEqual(component.computed.qualEvidenceItems.call(vm), [
    { kind: 'image', src: 'https://example.com/license.jpg', alt: '商家 #18 的营业执照' }
  ]);

  const unregistered = { qual: { row: { merchantId: 19, mmsStatus: null } }, perkFulfillment: component.methods.perkFulfillment };
  assert.deepEqual(component.computed.qualHeaderStatus.call(unregistered), { label: '未建档', tone: 'neutral' });
});
