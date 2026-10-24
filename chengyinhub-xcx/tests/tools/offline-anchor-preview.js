'use strict';

const fs = require('node:fs');
const path = require('node:path');

function createOfflineAnchorPreview(directory) {
  const root = path.resolve(__dirname, '../..');
  const source = fs.readFileSync(path.join(root, 'pages/club/topic-detail/index.wxml'), 'utf8');
  const start = source.indexOf('    <view class="ctd-generic-ops"');
  const end = source.indexOf('    <cy-scene-sheet', start);
  if (start < 0 || end < start) throw new Error('Director panel boundaries not found');
  const write = (file, data) => {
    fs.mkdirSync(path.dirname(path.join(directory, file)), { recursive: true });
    fs.writeFileSync(path.join(directory, file), data);
  };
  write('project.config.json', JSON.stringify({ appid: 'touristappid', projectname: 'offline-anchor-isolated-preview', compileType: 'miniprogram', setting: { urlCheck: true, es6: true } }));
  write('app.json', JSON.stringify({ pages: ['pages/index/index'], window: { navigationBarTitleText: '隔离渲染 · 非端到端', navigationBarBackgroundColor: '#101014', navigationBarTextStyle: 'white' } }));
  write('app.js', 'App({});');
  write('app.wxss', '@import "./style/tokens.wxss";\n@import "./style/components.wxss";\npage { background: var(--cy-bg-page); color: var(--cy-text-body); }');
  for (const file of ['tokens.wxss', 'components.wxss', 'fade-in.wxss', 'motion-entry.wxss']) {
    write('style/' + file, fs.readFileSync(path.join(root, 'style', file), 'utf8'));
  }
  write('pages/index/index.wxml', '<view class="ctd-page"><text>隔离渲染，非真实页面端到端；所有网络写入禁用。</text>' + source.slice(start, end) + '</view>');
  write('pages/index/index.wxss', fs.readFileSync(path.join(root, 'pages/club/topic-detail/index.wxss'), 'utf8'));
  write('pages/index/index.json', JSON.stringify({ usingComponents: { 'cy-icon': '../../components/cy/icon/index' } }));
  write('pages/index/index.js', `const { genericOpsActions } = require('../../generic-director-ops');
const control = { run: { operationStatus: 'RUNNING', authorityMode: 'ONLINE', authorityEpoch: 2, eventSeq: 3 }, revision: 9,
  availableActions: ['RUN_PAUSE', 'ACT_UNLOCK', 'ARRIVAL_CONFIRM'], offlineAnchorAvailable: false, offlineAnchor: null };
Page({ data: { directorActive: true, loadState: 'ready', genericOpsKnown: true, genericOpsActions: genericOpsActions(control),
  genericOpsStatus: '现场运营中', genericOpsTeams: '2 支队伍', writeLocked: false },
  setAvailable(available) { control.offlineAnchorAvailable = available; this.setData({ genericOpsActions: genericOpsActions(control) }); },
  openGenericOps(event) { if (event.currentTarget.dataset.action === 'OFFLINE_ANCHOR') wx.showModal({ title: '开始离线记录',
    content: '隔离渲染：仅展示确认框，不发送请求。', confirmText: '开始记录', cancelText: '暂不开始' }); }
});`);
  write('generic-director-ops.js', fs.readFileSync(path.join(root, 'pages/club/utils/generic-director-ops.js'), 'utf8'));
  for (const file of ['index.js', 'index.json', 'index.wxml', 'index.wxss', 'icons.wxss']) {
    write('components/cy/icon/' + file, fs.readFileSync(path.join(root, 'components/cy/icon', file), 'utf8'));
  }
  return directory;
}

if (require.main === module) {
  if (!process.argv[2]) throw new Error('Pass an owned temporary directory');
  console.log(createOfflineAnchorPreview(path.resolve(process.argv[2])));
}

module.exports = { createOfflineAnchorPreview };
