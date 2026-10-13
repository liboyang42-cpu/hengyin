const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8');
}

function loadHomeComponent() {
  const source = read('src/views/index.vue');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1];
  const body = script
    .replace(/^\s*import[^\n]*\n/gm, '')
    .replace(/export\s+default\s*/, 'return ');
  return new Function('CyPageHeader', 'CyTaskQueue', 'CyMetricCard', body)({}, {}, {});
}

test('Web 后台采用三层语义 Token，并由全局样式入口加载', () => {
  const tokens = read('src/assets/styles/tokens.scss');
  const tokenVariables = read('src/assets/styles/_token-variables.scss');
  const globalStyles = read('src/assets/styles/index.scss');
  const elementTheme = read('src/assets/styles/element-variables.scss');
  const settingsStore = read('src/store/modules/settings.js');
  const taskQueue = read('src/components/CyTaskQueue/index.vue');

  assert.match(tokens, /Primitive tokens/);
  assert.match(tokens, /Semantic tokens/);
  assert.match(tokens, /Component tokens/);
  assert.match(tokens, /@import ['"]\.\/token-variables\.scss['"]/);
  assert.match(tokenVariables, /\$cy-color-brand:\s*\$cy-ref-violet-700/i);
  assert.match(tokenVariables, /\$cy-ref-violet-700:\s*#7A5CFF/i);
  assert.match(tokenVariables, /\$cy-color-action-primary:\s*\$cy-ref-ink-900/i);
  assert.match(tokenVariables, /\$cy-control-height:\s*36px/i);
  assert.match(tokenVariables, /\$cy-table-row-height:\s*40px/i);
  assert.match(tokenVariables, /\$cy-radius-control:\s*6px/i);
  assert.match(tokenVariables, /\$cy-radius-card:\s*8px/i);
  assert.match(tokenVariables, /\$cy-radius-dialog:\s*12px/i);
  assert.match(tokenVariables, /\$cy-sidebar-width:\s*240px/i);
  assert.match(globalStyles, /@import ['"]\.\/tokens\.scss['"]/);
  assert.match(globalStyles, /\.el-button--primary,[\s\S]*background:\s*var\(--cy-color-action-primary\)/);
  assert.match(globalStyles, /\.el-table th\.el-table__cell,[\s\S]*height:\s*var\(--cy-table-row-height\)/);
  assert.match(taskQueue, /min-height:\s*var\(--cy-task-row-height\)/);
  assert.match(elementTheme, /@import ['"]\.\/token-variables\.scss['"]/);
  assert.match(elementTheme, /\$--color-primary:\s*\$cy-color-brand/i);
  assert.doesNotMatch(elementTheme, /#[0-9a-f]{3,8}/i);
  assert.match(settingsStore, /theme:\s*'#7A5CFF'/i);
  assert.match(settingsStore, /sideTheme:\s*sideTheme/);
});

test('首页把后端待审计数转换为可下钻任务，而不是观赏数字卡', () => {
  const component = loadHomeComponent();
  const vm = {
    ...component.data(),
    record: {
      merchantAuditNum: '3',
      creativeSquareNum: '0',
      topicNum: '2',
      activityNum: 1,
      memberTemplateNum: '4'
    }
  };

  const tasks = component.computed.reviewTasks.call(vm);
  assert.deepEqual(tasks.map(item => item.count), [3, 0, 2, 1, 4]);
  // 「只看待审」的参数名按目标实体字段断言，不共用一个写法：
  // mms_merchant 没有 check_status 列（MmsMerchantMapper.xml 只认 status，
  // 待审计数 merchantAuditNum 也是 del_flag=0 AND `status`=0），
  // 卡片带 check_status 会被列表页静默丢弃 = 「只看待审」从未传达。
  assert.deepEqual(tasks[0].route, { name: 'Merchant', query: { status: 0 } });
  assert.equal(tasks[1].route.query.check_status, 0);
  // 断言 name + query 这一组语义，而不是单个字符串字面量：
  // scope 缺失时 topic/index.vue 会落到「无来源」的全量视图（新增/批量被 isOfficialEntry() 藏掉），
  // 所以「跳对页」和「带对 query」是同一个契约的两半，必须一起断言。
  // ⚠️ 局限：Audit/Merchant/Square/OfficialTopic 都是 sys_menu 驱动的动态路由，
  //    router/index.js 里没有对应静态条目，静态测试无法验证该 name 运行时真被注册。
  assert.deepEqual(tasks[2].route, { name: 'OfficialTopic', query: { check_status: 0, scope: 'official' } });
  assert.equal(tasks[4].type, '模板审核');
  assert.ok(tasks.every(item => item.source === '实时待审队列'));
});

test('首页待审卡片按权限过滤：无权角色看不到点不动的卡片', () => {
  const component = loadHomeComponent();
  // permission 取值必须与生产库菜单 perms / 后端 @PreAuthorize 一致（不许猜）：
  // merchant=2013 / square=2044 / topic=2007 / activity=2062；
  // membertemplate 由 CmsMemberTemplateController @PreAuthorize('business:membertemplate:list') 坐实。
  const expectedPermissions = [
    'business:merchant:list',
    'business:square:list',
    'business:topic:list',
    'business:activity:list',
    'business:membertemplate:list'
  ];

  const makeVm = permissions => {
    const vm = {
      ...component.data(),
      $store: { state: { user: { permissions } } }
    };
    vm.hasPermission = component.methods.hasPermission.bind(vm);
    return vm;
  };

  const superVm = makeVm(['*:*:*']);
  assert.deepEqual(
    component.computed.reviewTasks.call(superVm).map(item => item.permission),
    expectedPermissions
  );
  // 超管通配：5 张全部可见
  assert.equal(component.computed.visibleReviewTasks.call({
    ...superVm,
    reviewTasks: component.computed.reviewTasks.call(superVm)
  }).length, 5);

  // 只有商家与帖文权限的账号：其余 3 张不出现在渲染列表里
  const limitedVm = makeVm(['business:merchant:list', 'business:square:list']);
  const visible = component.computed.visibleReviewTasks.call({
    ...limitedVm,
    reviewTasks: component.computed.reviewTasks.call(limitedVm)
  });
  assert.deepEqual(visible.map(item => item.key), ['merchant', 'square']);

  // 零权限账号：一张都不剩
  const noneVm = makeVm([]);
  assert.equal(component.computed.visibleReviewTasks.call({
    ...noneVm,
    reviewTasks: component.computed.reviewTasks.call(noneVm)
  }).length, 0);

  // 模板必须渲染过滤后的列表：还留一处 v-for reviewTasks 就等于过滤没接线
  const source = read('src/views/index.vue');
  assert.match(source, /v-for="item in visibleReviewTasks"/);
  assert.doesNotMatch(source, /v-for="(?:item|task) in reviewTasks"/);
  assert.match(source, /:items="visibleReviewTasks"/);
});

test('首页主题入口按服务端内容主体进入对应主题管理路由', () => {
  const component = loadHomeComponent();
  const vm = {
    ...component.data(),
    hasPermission: () => true
  };

  const topicManagement = component.computed.quickActions.call(vm)
    .find(action => action.label === '主题管理');
  const topicList = read('src/views/business/topic/index.vue');

  assert.ok(topicManagement);
  assert.deepEqual(topicManagement.route, { name: 'OfficialTopic', query: { scope: 'official' } });
  assert.equal(topicManagement.permission, 'business:topic:list');
  assert.match(topicList, /const scope = this\.workspaceScope\(row\)/);
  assert.match(topicList, /\$router\.push\(\{ path: '\/topic\/workspace', query: \{ id: row\.id, scope \} \}\)/);
  assert.match(topicList, /if \(!row \|\| !row\.contentPublisherType\) return 'HISTORICAL'/);
  assert.match(topicList, /if \(row\.contentPublisherType === 'CLUB' \|\| row\.contentPublisherType === 'MEMBER'\) return 'PLAZA'/);
});

test('首页使用公共工作台组件并明确未接通的数据，不保留 CountTo 卡片', () => {
  const source = read('src/views/index.vue');

  assert.match(source, /<cy-page-header/);
  assert.match(source, /<cy-task-queue/);
  assert.match(source, /尚未接通/);
  assert.doesNotMatch(source, /CountTo|card-panel/);
});

test('首页快捷入口在中等宽度先降为四列，再进入窄屏三列', () => {
  const source = read('src/views/index.vue');

  assert.match(source, /@media \(max-width:\s*1320px\)[\s\S]*?\.workbench__quick-grid\s*\{\s*grid-template-columns:\s*repeat\(4,/);
  assert.match(source, /@media \(max-width:\s*1100px\)[\s\S]*?\.workbench__quick-grid\s*\{\s*grid-template-columns:\s*repeat\(3,/);
});

test('顶栏移除 RuoYi 源码、文档与布局密度入口，并明确菜单搜索边界', () => {
  const source = read('src/layout/components/Navbar.vue');

  assert.doesNotMatch(source, /RuoYiGit|RuoYiDoc|SizeSelect|ruoyi-git|ruoyi-doc|size-select/);
  assert.match(source, /菜单搜索/);
  assert.doesNotMatch(source, /全局搜索/);
  assert.match(source, /待办/);
  assert.match(source, /当前角色/);
});

test('主题运营工作台使用只读聚合接口，并收敛为五个运营视图', () => {
  const api = read('src/api/business/topic.js');
  const router = read('src/router/index.js');
  const workspace = read('src/views/business/topic/workspace.vue');

  assert.match(api, /\/business\/topic\/['"]?\s*\+\s*id\s*\+\s*['"]\/workspace/);
  assert.match(router, /views\/business\/topic\/workspace/);
  for (const label of ['总览', '内容与售卖', '合作供给', '履约执行', '复盘结算']) {
    assert.match(workspace, new RegExp(label));
  }
  assert.match(workspace, /topic-workspace__relay/);
  assert.match(workspace, /runReadiness/);
  assert.match(workspace, /runBlockers/);
  assert.match(workspace, /nextAction/);
  assert.match(workspace, /editPolicy/);
  assert.doesNotMatch(workspace, /独立漫游|ROAM_RELATION_UNDEFINED|roam-note/, '漫游关联栏已按 2026-09-15 拍板移除，不得回潮');
  assert.doesNotMatch(workspace, /\.email\b|verifiedRegIds/);
});

test('主题360已按拍板移除漫游关联栏，且仍不把 NPC 路线彩蛋带进主题操作面', () => {
  const workspace = read('src/views/business/topic/workspace.vue');

  assert.doesNotMatch(workspace, /ROAM_RELATION_UNDEFINED|独立漫游|roam-note/);
  assert.doesNotMatch(workspace, /listNpcRouteEgg|auditNpcRouteEgg|updateNpcRouteEgg|removeNpcRouteEgg/);
  assert.doesNotMatch(workspace, /business:npcRouteEgg/);
});

test('俱乐部工作台展示完整申请和能力证据，并保持跨域权限边界', () => {
  const api = read('src/api/business/club.js');
  const club = read('src/views/business/club/index.vue');

  assert.match(api, /\/business\/club\/['"]?\s*\+\s*id\s*\+\s*['"]\/workspace/);
  for (const label of ['基础档案', '能力与资质', '承接主题', '加入理由', '过往经验', '资质与案例材料']) {
    assert.match(club, new RegExp(label));
  }
  assert.match(club, /business:topic:list/);
  assert.match(club, /getClubWorkspace/);
  assert.match(club, /auditClub\(\{ id: row\.id, status \}\)/);
  assert.doesNotMatch(club, /updateClub\([^)]*status/s);
});

test('俱乐部成员与内容治理使用专用端点，动态处置只进入统一审核链', () => {
  const api = read('src/api/business/club.js');
  const club = read('src/views/business/club/index.vue');
  const audit = read('src/views/business/audit/index.vue');

  assert.match(api, /\/governance/);
  assert.match(api, /\/members\/['"]?\s*\+\s*memberId\s*\+\s*['"]\/role/);
  assert.match(api, /\/posts\/['"]?\s*\+\s*postId\s*\+\s*['"]\/review/);
  for (const label of ['成员治理', '内容治理', '提交统一审核']) {
    assert.match(club, new RegExp(label));
  }
  assert.match(club, /business:club:audit/);
  assert.match(club, /submitClubPostForReview/);
  assert.doesNotMatch(club, /deleteClubPost|delClubPost/);
  assert.match(audit, /this\.\$route\.query\.bizType/);
});

test('俱乐部工作台把承接履约限定在同时拥有俱乐部与主题读取权限的只读视图', () => {
  const api = read('src/api/business/club.js');
  const club = read('src/views/business/club/index.vue');

  assert.match(api, /\/fulfillment/);
  for (const label of ['带队履约', '集合签到', '团核销']) {
    assert.match(club, new RegExp(label));
  }
  assert.match(club, /getClubFulfillment/);
  assert.match(club, /business:club:query/);
  assert.match(club, /business:topic:list/);
  assert.doesNotMatch(club, /attendee\.phone|verification\.phone/);
});

test('审核、客服、资金主链复用同一设计系统且保留真实下钻', () => {
  const audit = read('src/views/business/audit/index.vue');
  const member = read('src/views/business/member/index.vue');
  const refund = read('src/views/business/finance/refund/index.vue');
  const auditApi = read('src/api/business/auditWorkbench.js');
  const router = read('src/router/index.js');

  for (const source of [audit, member, refund]) {
    assert.match(source, /<cy-page-header/);
    assert.match(source, /<cy-status-tag/);
  }
  assert.match(audit, /审核证据链/);
  assert.match(audit, /getAuditTaskDetail/);
  assert.match(auditApi, /\/business\/audit\/['"]?\s*\+\s*id\s*\+\s*['"]\/detail/);
  assert.match(member, /用户与客服/);
  assert.match(refund, /资金与售后/);
  assert.match(refund, /人工打款队列/);
  assert.match(refund, /business:refund:review/);
  assert.match(router, /path:\s*['"]\/refund['"]/);
  assert.match(router, /views\/business\/refund\/manual/);
  assert.match(router, /path:\s*['"]\/finance\/refund['"]/);
  assert.match(router, /views\/business\/finance\/refund/);
});

test('用户调证台不保留隐藏账号 CRUD，状态更新只提交白名单字段', () => {
  const member = read('src/views/business/member/index.vue');

  assert.doesNotMatch(member, /账号密码|addMember|delMember/);
  assert.match(member, /updateMember\(\{\s*id:\s*this\.form\.id,\s*status:\s*this\.form\.status\s*\}\)/);
});

test('内容检索合并帖文与评论入口，并把审核写入留给统一审核台', () => {
  const contentSearch = read('src/views/business/contentsearch/index.vue');
  const square = read('src/views/business/square/index.vue');
  const comment = read('src/views/business/comment/index.vue');

  assert.match(square, /<content-search\s+default-type="SQUARE"/);
  assert.match(comment, /<content-search\s+default-type="COMMENT"/);
  assert.match(contentSearch, /listSquare/);
  assert.match(contentSearch, /listComment/);
  assert.match(contentSearch, /getSquare/);
  assert.match(contentSearch, /getComment/);
  assert.match(contentSearch, /<cy-page-header/);
  assert.match(contentSearch, /<cy-filter-bar/);
  assert.match(contentSearch, /<cy-data-table/);
  assert.match(contentSearch, /name:\s*['"]Audit['"]/);
  assert.match(contentSearch, /this\.queryParams\.checkStatus\s*=\s*this\.\$route\.query\.check_status/);
  assert.doesNotMatch(contentSearch, /submitSquareReview|submitCommentReview|提交审核/);
});
