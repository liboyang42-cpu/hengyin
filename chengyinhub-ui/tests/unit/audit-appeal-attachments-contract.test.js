const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

// S531-3:申诉单的附件(media-asset:{id})由后台详情解析成 5 分钟预览,审核台直接展示缩略图/链接;
// 没有附件时不渲染任何东西,与原来完全一致。
const viewPath = path.resolve(__dirname, '../../src/views/business/audit/index.vue');
const source = fs.readFileSync(viewPath, 'utf8');

function loadComponent() {
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1];
  const body = script
    .replace(/^\s*import[^\n]*\n/gm, '')
    .replace(/components:\s*\{[^}]+\},/, 'components: {},')
    .replace(/export\s+default\s*/, 'return ');
  return new Function(body)();
}

test('详情响应里的申诉附件按编号规整,预览地址只认 https,非法项丢弃', () => {
  const { normalizeAppealAttachments } = loadComponent().methods;
  assert.deepEqual(normalizeAppealAttachments(undefined), []);
  assert.deepEqual(normalizeAppealAttachments([
    { assetId: 31, mediaType: 'IMAGE', previewUrl: 'https://oss.example.invalid/31?sig=1' },
    { assetId: 32, mediaType: null, previewUrl: null },
    { assetId: 33, mediaType: 'AUDIO', previewUrl: 'javascript:alert(1)' },
    { assetId: '34', previewUrl: 'https://oss.example.invalid/34' },
    null
  ]), [
    { assetId: 31, mediaType: 'IMAGE', previewUrl: 'https://oss.example.invalid/31?sig=1' },
    { assetId: 32, mediaType: '', previewUrl: '' },
    { assetId: 33, mediaType: 'AUDIO', previewUrl: '' }
  ]);
});

test('图片附件以缩略图展示并可放大,其余附件给链接,无预览时只显示编号', () => {
  assert.match(source, /<div v-if="appealAttachments\.length" class="appeal-attachments">/);
  assert.match(source, /<el-image v-if="item\.mediaType === 'IMAGE' && item\.previewUrl" :src="item\.previewUrl"[^>]*:preview-src-list="appealAttachmentImageUrls"/);
  assert.match(source, /<el-link v-else-if="item\.previewUrl" :href="item\.previewUrl" target="_blank" rel="noopener noreferrer">/);
  const urls = loadComponent().computed.appealAttachmentImageUrls.call({
    appealAttachments: [
      { assetId: 1, mediaType: 'IMAGE', previewUrl: 'https://a.invalid/1' },
      { assetId: 2, mediaType: 'AUDIO', previewUrl: 'https://a.invalid/2' },
      { assetId: 3, mediaType: 'IMAGE', previewUrl: '' }
    ]
  });
  assert.deepEqual(urls, ['https://a.invalid/1']);
});

test('打开新详情先清空上一单的附件,防止串单', () => {
  const openDetail = source.match(/openDetail\(row\) \{[\s\S]*?return getAuditTaskDetail/)[0];
  assert.match(openDetail, /this\.appealAttachments = \[\]/);
  assert.match(openDetail, /this\.appealAttachmentsPreviewDenied = false/);
});
