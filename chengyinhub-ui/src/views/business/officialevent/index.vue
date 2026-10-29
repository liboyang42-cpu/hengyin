<template>
  <div class="official-event-console">
    <section v-if="view === 'list'" class="console-list app-container">
      <header class="console-hero">
        <div>
          <p class="eyebrow">LIVE OPS / 官方活动</p>
          <h1>把活动从“发布一条内容”变成可运行的合同</h1>
          <p class="hero-copy">内容绑定、任务证据、主办承接和奖励资格各自有真源。后台只读聚合 V2 合同；草稿、排期与暂停须由小程序白名单官方会员执行。</p>
        </div>
        <div class="hero-actions">
          <el-button v-hasPermi="['business:officialevent:notify']" icon="el-icon-bell" plain @click="openNotify(null)">官方通知</el-button>
          <el-button v-hasPermi="['business:officialevent:authorize']" plain icon="el-icon-key" @click="openOperatorAuthorization">主办授权</el-button>
          <el-button type="primary" icon="el-icon-plus" :disabled="!canWriteV2" @click="createV2">{{ canWriteV2 ? '创建 V2 活动' : '先绑定主办身份' }}</el-button>
        </div>
      </header>

      <div class="status-rail" aria-label="V2 生命周期">
        <span v-for="item in lifecycle" :key="item.value" class="rail-item">
          <i></i>{{ item.label }}
        </span>
        <span class="operator-state" :class="canWriteV2 ? 'bound' : 'unbound'">{{ operatorIdentityLabel }}</span>
        <span class="rail-note">暂停是独立控制层，不会修改证据历史</span>
      </div>

      <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" class="search-row">
        <el-form-item label="标题" prop="title"><el-input v-model="queryParams.title" placeholder="活动标题" clearable @keyup.enter.native="handleQuery" /></el-form-item>
        <el-form-item label="城市" prop="city"><el-input v-model="queryParams.city" placeholder="城市" clearable @keyup.enter.native="handleQuery" /></el-form-item>
        <el-form-item label="状态" prop="status">
          <el-select v-model="queryParams.status" placeholder="全部状态" clearable style="width:130px">
            <el-option v-for="item in lifecycle" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item><el-button type="primary" icon="el-icon-search" @click="handleQuery">筛选</el-button><el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button></el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="list" class="event-table">
        <el-table-column label="活动" min-width="220">
          <template slot-scope="scope">
            <div class="event-title">{{ scope.row.title }}</div>
            <div class="event-meta">{{ scope.row.city || '未设城市' }} · {{ scope.row.category || '未分类' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="合同" width="92" align="center">
          <template slot-scope="scope"><el-tag :type="isV2(scope.row) ? 'success' : 'info'" size="mini">{{ isV2(scope.row) ? 'V2 证据' : 'V1 历史' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="生命周期" width="110" align="center">
          <template slot-scope="scope"><el-tag :type="statusType(scope.row.status)" size="mini">{{ statusText(scope.row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="运行控制" width="100" align="center">
          <template slot-scope="scope"><span :class="controlStateClass(scope.row.controlState)">{{ controlStateText(scope.row.controlState) }}</span></template>
        </el-table-column>
        <el-table-column label="活动窗口" min-width="190">
          <template slot-scope="scope"><span class="time-line">{{ fmt(scope.row.activityStart) }} — {{ fmt(scope.row.activityEnd) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right" align="right">
          <template slot-scope="scope">
            <el-button v-if="isV2(scope.row)" type="text" @click="openDraft(scope.row.id)">合同查看</el-button>
            <el-button v-if="isV2(scope.row)" type="text" @click="open360(scope.row.id)">活动 360</el-button>
            <el-button v-if="!isV2(scope.row)" type="text" @click="openLegacy(scope.row)">历史详情</el-button>
            <el-button v-hasPermi="['business:officialevent:notify']" type="text" @click="openNotify(scope.row)">通知</el-button>
            <el-button v-if="isV2(scope.row) && scope.row.status === 4" v-hasPermi="['business:officialevent:settle']" type="text" @click="settle(scope.row)">结算</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />
    </section>

    <section v-else-if="view === 'editor'" class="editor-shell">
      <header class="editor-header">
        <div>
          <el-button type="text" icon="el-icon-arrow-left" @click="backToList">活动列表</el-button>
          <p class="eyebrow">V2 DRAFT / {{ draft.event.id ? '编辑草稿 #' + draft.event.id : '新活动' }}</p>
          <h1>{{ draft.event.title || '未命名官方活动' }}</h1>
        </div>
        <div class="editor-header-actions">
          <el-tag :type="canWriteV2 ? 'success' : 'warning'">{{ canWriteV2 ? '证据合同 V2 · 已绑定' : '证据合同 V2 · 未绑定' }}</el-tag>
          <el-button plain :disabled="!canWriteV2" @click="saveDraft">保存草稿</el-button>
          <el-button type="primary" :disabled="!canWriteV2" @click="runPublishCheck">运行检查</el-button>
        </div>
        <el-alert v-if="!canWriteV2" title="当前后台账号未绑定有效官方会员。为防止同号 ID 冒用，写入已被服务端拒绝；请由具备“主办授权”权限的管理员建立可审计映射。" type="warning" :closable="false" show-icon class="v2-readonly-alert" />
      </header>

      <main class="editor-main">
        <aside class="editor-nav">
          <button v-for="(item, index) in editorSteps" :key="item.title" :class="['step-nav', { active: editorStep === index }]" @click="editorStep = index">
            <span>{{ String(index + 1).padStart(2, '0') }}</span>
            <strong>{{ item.title }}</strong>
            <small>{{ item.hint }}</small>
          </button>
        </aside>

        <section class="editor-panel">
          <template v-if="editorStep === 0">
            <div class="panel-heading"><p class="eyebrow">STEP 01</p><h2>活动意图与时间</h2><p>这里定义活动壳与唯一责任主体的运行窗口；状态由服务端时间推进。</p></div>
            <el-form :model="draft.event" label-position="top" class="form-grid">
              <el-form-item label="活动标题" required><el-input v-model="draft.event.title" maxlength="80" show-word-limit placeholder="例如：周末城市微光行动" /></el-form-item>
              <el-form-item label="城市" required><el-input v-model="draft.event.city" placeholder="例如：成都" /></el-form-item>
              <el-form-item label="分类"><el-select v-model="draft.event.category" style="width:100%"><el-option v-for="item in categoryOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
              <el-form-item label="承接策略">
                <el-select v-model="draft.event.fulfillmentPolicy" style="width:100%">
                  <el-option label="官方自行运行" value="SELF_RUN" /><el-option label="合作可选，缺口提示风险" value="OPTIONAL_PARTNERS" /><el-option label="外部履约必需，缺口阻断发布" value="REQUIRED_FULFILLMENT" />
                </el-select>
              </el-form-item>
              <el-form-item label="报名开始" required><el-date-picker v-model="draft.event.signupStart" type="datetime" value-format="timestamp" style="width:100%" /></el-form-item>
              <el-form-item label="活动开始" required><el-date-picker v-model="draft.event.activityStart" type="datetime" value-format="timestamp" style="width:100%" /></el-form-item>
              <el-form-item label="活动结束" required><el-date-picker v-model="draft.event.activityEnd" type="datetime" value-format="timestamp" style="width:100%" /></el-form-item>
              <el-form-item label="结算完成" required><el-date-picker v-model="draft.event.settleTime" type="datetime" value-format="timestamp" style="width:100%" /></el-form-item>
              <el-form-item label="报名上限"><el-input-number v-model="draft.event.signupLimit" :min="0" /><span class="field-hint">0 表示不限</span></el-form-item>
              <el-form-item label="首页 Banner"><el-switch v-model="draft.event.bannerEnabled" :active-value="1" :inactive-value="0" /></el-form-item>
              <el-form-item label="副标题" class="full"><el-input v-model="draft.event.subtitle" placeholder="让玩家知道为什么现在参与" /></el-form-item>
              <el-form-item label="故事与说明" class="full"><el-input v-model="draft.event.story" type="textarea" :rows="5" placeholder="叙事可以自由；完成条件必须配置在任务规则中。" /></el-form-item>
            </el-form>
          </template>

          <template v-else-if="editorStep === 1">
            <div class="panel-heading row-heading"><div><p class="eyebrow">STEP 02</p><h2>内容绑定与玩家任务</h2><p>先引用内容或配置活动自有点，再将任务明确绑定到一个目标。DISPLAY_ONLY 与 ROAM_AREA 不能作为 P0 任务。</p></div><el-button plain icon="el-icon-plus" @click="addBinding">添加绑定</el-button></div>
            <div v-if="!draft.bindings.length" class="empty-stage">还没有绑定。添加主题、漫游 POI 或活动自有点后，才能配置任务。</div>
            <article v-for="(binding, index) in draft.bindings" :key="binding._key" class="contract-card">
              <div class="card-header"><strong>绑定 {{ index + 1 }}</strong><el-button type="text" class="danger-text" @click="removeBinding(index)">移除</el-button></div>
              <el-form label-position="top" class="form-grid compact-grid">
                <el-form-item label="稳定编码" required><el-input v-model="binding.bindingCode" placeholder="例如 poi-ifs" /></el-form-item>
                <el-form-item label="绑定类型" required><el-select v-model="binding.bindingType" style="width:100%" @change="syncBindingSource(binding)"><el-option v-for="item in bindingTypes" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
                <el-form-item label="来源模式"><el-select v-model="binding.sourceMode" style="width:100%" @change="syncBindingSource(binding)"><el-option v-for="item in sourceOptions(binding)" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
                <el-form-item v-if="binding.sourceMode === 'REFERENCE'" label="来源对象 ID" required><el-input-number v-model="binding.sourceRefId" :min="1" style="width:100%" /></el-form-item>
                <el-form-item v-if="binding.sourceMode === 'EVENT_OWNED'" label="纬度" required><el-input-number v-model="binding.latitude" :precision="6" :step="0.000001" style="width:100%" /></el-form-item>
                <el-form-item v-if="binding.sourceMode === 'EVENT_OWNED'" label="经度" required><el-input-number v-model="binding.longitude" :precision="6" :step="0.000001" style="width:100%" /></el-form-item>
                <el-form-item v-if="binding.sourceMode === 'EVENT_OWNED'" label="验证半径（米）" required><el-input-number v-model="binding.radiusM" :min="1" style="width:100%" /></el-form-item>
                <el-form-item label="展示城市"><el-input v-model="binding.city" :placeholder="draft.event.city || '与活动城市一致'" /></el-form-item>
              </el-form>
            </article>

            <div class="subsection-heading"><div><h3>任务合同</h3><p>每个 P0 任务只认一个服务端事实；数量固定为 1，需要多点请创建多个任务并写显式前置。</p></div><el-button type="primary" plain icon="el-icon-plus" @click="addMission">添加任务</el-button></div>
            <div v-if="!draft.missions.length" class="empty-stage">还没有任务。参与型活动无法通过发布检查。</div>
            <article v-for="(mission, index) in draft.missions" :key="mission._key" class="contract-card mission-card">
              <div class="card-header"><strong>任务 {{ index + 1 }}</strong><el-button type="text" class="danger-text" @click="removeMission(index)">移除</el-button></div>
              <el-form label-position="top" class="form-grid compact-grid">
                <el-form-item label="任务编码" required><el-input v-model="mission.missionCode" placeholder="例如 arrive-ifs" /></el-form-item>
                <el-form-item label="目标绑定" required><el-select v-model="mission.bindingCode" style="width:100%"><el-option v-for="binding in draft.bindings" :key="binding._key" :label="binding.bindingCode || '未命名绑定'" :value="binding.bindingCode" /></el-select></el-form-item>
                <el-form-item label="任务类型" required><el-select v-model="mission.missionType" style="width:100%"><el-option v-for="item in missionTypes" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
                <el-form-item label="规则版本"><el-input v-model="mission.ruleVersion" placeholder="1" /></el-form-item>
                <el-form-item label="任务标题" required><el-input v-model="mission.title" /></el-form-item>
                <el-form-item label="短说明"><el-input v-model="mission.shortDescription" placeholder="玩家端将直接显示" /></el-form-item>
                <el-form-item label="任务可用开始"><el-date-picker v-model="mission.availableStart" type="datetime" value-format="timestamp" style="width:100%" /></el-form-item>
                <el-form-item label="任务可用结束"><el-date-picker v-model="mission.availableEnd" type="datetime" value-format="timestamp" style="width:100%" /></el-form-item>
                <el-form-item label="任务规则 JSON" class="full"><el-input v-model="mission.ruleJson" type="textarea" :rows="3" placeholder='例如 {"maxAccuracyM":80}' /></el-form-item>
              </el-form>
            </article>
          </template>

          <template v-else-if="editorStep === 2">
            <div class="panel-heading row-heading"><div><p class="eyebrow">STEP 03</p><h2>主办与承接</h2><p>内容里出现商家不代表它已承接。每场参与型活动必须有且只有一位已接受的有效主办方。</p></div><el-button plain icon="el-icon-plus" @click="addParty">添加主体</el-button></div>
            <article v-for="(party, index) in draft.parties" :key="party._key" class="contract-card">
              <div class="card-header"><strong>{{ party.role === 'ORGANIZER' ? '唯一主办方' : '承接或协办' }} {{ index + 1 }}</strong><el-button type="text" class="danger-text" @click="removeParty(index)">移除</el-button></div>
              <el-form label-position="top" class="form-grid compact-grid">
                <el-form-item label="主体类型" required><el-select v-model="party.partyType" style="width:100%"><el-option label="官方主体" value="OFFICIAL" /><el-option label="商家" value="MERCHANT" /><el-option label="俱乐部" value="CLUB" /></el-select></el-form-item>
                <el-form-item label="主体 ID" required><el-input-number v-model="party.partyId" :min="1" style="width:100%" /></el-form-item>
                <el-form-item label="本场角色" required><el-select v-model="party.role" style="width:100%"><el-option v-for="item in partyRoles" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
                <el-form-item label="确认状态"><span>OFFICIAL 主办方 ID 仅表示白名单 UmsMember ID。后台不会写入主办授权；候任主办须在小程序收件箱接受，随后由当前主办受控转交。</span></el-form-item>
                <el-form-item label="责任目标类型"><el-input v-model="party.targetType" placeholder="例如 ROAM_POI" /></el-form-item>
                <el-form-item label="责任目标 ID"><el-input-number v-model="party.targetId" :min="1" style="width:100%" /></el-form-item>
                <el-form-item label="服务开始"><el-date-picker v-model="party.windowStart" type="datetime" value-format="timestamp" style="width:100%" /></el-form-item>
                <el-form-item label="服务结束"><el-date-picker v-model="party.windowEnd" type="datetime" value-format="timestamp" style="width:100%" /></el-form-item>
                <el-form-item label="承接容量"><el-input-number v-model="party.capacity" :min="0" style="width:100%" /></el-form-item>
                <el-form-item label="责任说明" class="full"><el-input v-model="party.responsibilitySummary" placeholder="只描述本场职责，不复制联系方式或长期主体资料" /></el-form-item>
              </el-form>
            </article>

            <div class="subsection-heading"><div><h3>关键履约覆盖</h3><p>REQUIRED_FULFILLMENT 要逐条精确匹配目标、角色、时段和容量；P0 不支持拼容量。</p></div><el-button v-if="draft.event.fulfillmentPolicy !== 'SELF_RUN'" type="primary" plain icon="el-icon-plus" @click="addRequirement">添加覆盖需求</el-button></div>
            <div v-if="draft.event.fulfillmentPolicy === 'SELF_RUN'" class="policy-note">当前是 SELF_RUN：不能配置外部履约需求。</div>
            <article v-for="(requirement, index) in draft.fulfillmentRequirements" :key="requirement._key" class="contract-card requirement-card">
              <div class="card-header"><strong>覆盖需求 {{ index + 1 }}</strong><el-button type="text" class="danger-text" @click="removeRequirement(index)">移除</el-button></div>
              <el-form label-position="top" class="form-grid compact-grid">
                <el-form-item label="对应任务"><el-select v-model="requirement.missionCode" clearable style="width:100%"><el-option v-for="mission in draft.missions" :key="mission._key" :label="mission.missionCode || '未命名任务'" :value="mission.missionCode" /></el-select></el-form-item>
                <el-form-item label="目标类型" required><el-input v-model="requirement.targetType" placeholder="例如 ROAM_POI" /></el-form-item>
                <el-form-item label="目标 ID" required><el-input-number v-model="requirement.targetId" :min="1" style="width:100%" /></el-form-item>
                <el-form-item label="要求角色" required><el-select v-model="requirement.requiredRole" style="width:100%"><el-option label="商家履约" value="FULFILLMENT_MERCHANT" /><el-option label="俱乐部履约" value="FULFILLMENT_CLUB" /></el-select></el-form-item>
                <el-form-item label="需求容量"><el-input-number v-model="requirement.requiredCapacity" :min="0" style="width:100%" /></el-form-item>
                <el-form-item label="关键需求"><el-switch v-model="requirement.critical" :active-value="1" :inactive-value="0" /></el-form-item>
                <el-form-item label="需求开始"><el-date-picker v-model="requirement.windowStart" type="datetime" value-format="timestamp" style="width:100%" /></el-form-item>
                <el-form-item label="需求结束"><el-date-picker v-model="requirement.windowEnd" type="datetime" value-format="timestamp" style="width:100%" /></el-form-item>
              </el-form>
            </article>
          </template>

          <template v-else-if="editorStep === 3">
            <div class="panel-heading"><p class="eyebrow">STEP 04</p><h2>目标、奖励与触达</h2><p>资格由有效报名与全部有效证据聚合。这里只定义奖励壳，不能提前把报名记录标记为已完成或已领奖。</p></div>
            <el-form :model="draft.event" label-position="top" class="form-grid">
              <el-form-item label="开启集体目标"><el-switch v-model="draft.event.collectiveEnabled" :active-value="1" :inactive-value="0" /></el-form-item>
              <el-form-item v-if="draft.event.collectiveEnabled === 1" label="集体指标"><el-select v-model="draft.event.collectiveMetric" style="width:100%"><el-option label="有效完成玩家" value="complete_count" /><el-option label="有效证据数" value="light_count" /><el-option label="有效报名人数" value="signup_count" /></el-select></el-form-item>
              <el-form-item v-if="draft.event.collectiveEnabled === 1" label="达标阈值"><el-input-number v-model="draft.event.collectiveThreshold" :min="1" style="width:100%" /></el-form-item>
              <el-form-item label="榜单"><el-switch v-model="draft.event.leaderboardEnabled" :active-value="1" :inactive-value="0" /></el-form-item>
              <el-form-item label="结算 XP"><el-input-number v-model="reward.settleXp" :min="0" style="width:100%" /></el-form-item>
              <el-form-item label="结算券 ID"><el-input-number v-model="reward.settleCouponId" :min="0" style="width:100%" /></el-form-item>
              <el-form-item label="集体达标券 ID"><el-input-number v-model="reward.collectiveCouponId" :min="0" style="width:100%" /></el-form-item>
              <el-form-item label="限定徽章"><el-switch v-model="reward.settleBadge" /></el-form-item>
              <el-form-item label="奖励规则版本"><el-input v-model="reward.rewardRuleVersion" placeholder="1" /></el-form-item>
            </el-form>
            <div class="notice-callout"><i class="el-icon-info"></i><div><strong>通知以活动归属广播为真源</strong><p>保存草稿后可以从活动列表发通知；活动 360 只回读本活动通知及其待发、已发状态和站内触达，点击及订阅消息总触达等未接入指标不会被估算。</p></div></div>
          </template>

          <template v-else>
            <div class="panel-heading"><p class="eyebrow">STEP 05</p><h2>发布检查与玩家预览</h2><p>保存并运行检查后才能排期。任何阻塞项都不能用页面“看起来正常”来覆盖。</p></div>
            <div class="preview-grid">
              <article class="preview-card player-preview"><span class="preview-label">玩家任务卡预览</span><h3>{{ draft.event.title || '未命名活动' }}</h3><p>{{ draft.event.subtitle || '活动说明将在这里展示。' }}</p><div v-for="mission in draft.missions" :key="mission._key" class="preview-mission"><span class="mission-dot"></span><div><strong>{{ mission.title || '未命名任务' }}</strong><small>{{ mission.shortDescription || missionTypeText(mission.missionType) }}</small></div><em>等待有效证据</em></div><div v-if="!draft.missions.length" class="preview-empty">尚未配置任务</div></article>
              <article class="preview-card"><span class="preview-label">责任与发布门槛</span><dl><dt>主办方</dt><dd>{{ organizerSummary }}</dd><dt>承接策略</dt><dd>{{ policyText(draft.event.fulfillmentPolicy) }}</dd><dt>任务数</dt><dd>{{ draft.missions.length }} 项</dd><dt>关键覆盖</dt><dd>{{ draft.fulfillmentRequirements.length }} 项</dd></dl></article>
            </div>
            <div v-if="publishCheck" :class="['publish-result', publishCheck.pass ? 'pass' : 'blocked']"><div><strong>{{ publishCheck.pass ? '可以排期' : '发布被阻断' }}</strong><p v-if="publishCheck.pass">所有硬性合同检查通过。排期后任务、绑定与承接关系将冻结为活动版本。</p><p v-else>请逐条修复后再次检查；风险项不会被假装成通过。</p></div><ul v-if="publishCheck.blockers && publishCheck.blockers.length"><li v-for="item in publishCheck.blockers" :key="item">{{ item }}</li></ul><ul v-if="publishCheck.risks && publishCheck.risks.length" class="risks"><li v-for="item in publishCheck.risks" :key="item">风险：{{ item }}</li></ul></div>
            <div class="publish-actions"><el-button plain :disabled="!canWriteV2" @click="saveDraft">保存草稿</el-button><el-button type="primary" :disabled="!canWriteV2" @click="runPublishCheck">运行检查</el-button><el-button type="success" :disabled="!canWriteV2" @click="schedule">提交排期</el-button></div>
          </template>
        </section>
      </main>
      <footer class="editor-footer"><el-button :disabled="editorStep === 0" @click="editorStep--">上一步</el-button><span>第 {{ editorStep + 1 }} / {{ editorSteps.length }} 步</span><el-button v-if="editorStep < editorSteps.length - 1" type="primary" @click="editorStep++">下一步</el-button></footer>
    </section>

    <section v-else class="cockpit-shell">
      <header class="editor-header cockpit-header"><div><el-button type="text" icon="el-icon-arrow-left" @click="backToList">活动列表</el-button><p class="eyebrow">ACTIVITY 360 / 只读聚合 + 受控命令</p><h1>{{ activity360.title || '活动 360' }}</h1></div><div class="editor-header-actions"><el-tag :type="controlStateTone(activity360.controlState)">{{ controlStateText(activity360.controlState) }}</el-tag><el-button v-if="activity360.controlState === 'PAUSED'" type="primary" :disabled="!canWriteV2" @click="resume">恢复活动</el-button><el-button v-else-if="activity360.controlState === 'NORMAL'" type="danger" plain :disabled="!canWriteV2" @click="pause">暂停活动</el-button><small v-else class="control-unknown">控制状态待确认，已停用暂停/恢复</small></div></header>
      <main class="cockpit-main">
        <section class="cockpit-overview"><article><span>有效参与</span><strong>{{ activity360.participants || 0 }}</strong><small>只读有效参与</small></article><article><span>有效证据</span><strong>{{ activity360.validEvidence || 0 }}</strong><small>只计有效证据</small></article><article><span>完成玩家</span><strong>{{ activity360.completedParticipants || 0 }}</strong><small>全部必做任务完成</small></article><article><span>运行状态</span><strong class="status-word">{{ statusText(activity360.status) }}</strong><small v-if="activity360.controlState === 'PAUSED'">{{ fmt(activity360.pausedAt) }} · {{ activity360.pausedReason || '暂停原因未记录' }}</small><small v-else-if="activity360.controlState === 'NORMAL'">{{ activity360.resumeNote ? '最近恢复：' + activity360.resumeNote : '无暂停记录' }}</small><small v-else>控制状态待确认</small></article></section>
        <section class="cockpit-grid">
          <article class="cockpit-card wide"><header><div><p class="eyebrow">任务与内容</p><h2>以证据为准，而非进度</h2></div></header><el-table :data="activity360.missions || []" size="small"><el-table-column label="任务" min-width="160"><template slot-scope="scope">{{ scope.row.title || '未命名任务' }}</template></el-table-column><el-table-column label="任务编号" min-width="120"><template slot-scope="scope">{{ scope.row.missionCode }}</template></el-table-column><el-table-column label="类型" min-width="150"><template slot-scope="scope">{{ missionTypeText(scope.row.missionType) }}</template></el-table-column><el-table-column label="绑定类型" min-width="130"><template slot-scope="scope">{{ bindingTypeText(scope.row.bindingType) }}</template></el-table-column><el-table-column label="绑定编号" min-width="120"><template slot-scope="scope">{{ scope.row.bindingCode }}</template></el-table-column><el-table-column prop="validMembers" label="有效完成玩家" width="130" /><el-table-column prop="revokedEvidence" label="已撤销" width="90" /></el-table></article>
          <article class="cockpit-card"><header><div><p class="eyebrow">主办与承接</p><h2>责任关系</h2></div></header><div v-if="!(activity360.parties || []).length" class="inline-empty">未接入主体关系</div><div v-for="party in activity360.parties || []" :key="party.id" class="party-row"><div><strong>{{ partyRoleText(party.role) }}</strong><small>{{ partyTypeText(party.partyType) }} #{{ party.partyId }}</small></div><el-tag size="mini" :type="party.status === 'ACTIVE' || party.status === 'ACCEPTED' ? 'success' : 'warning'">{{ partyStatusText(party.status) }}</el-tag></div></article>
          <article class="cockpit-card"><header><div><p class="eyebrow">关键覆盖</p><h2>缺口不能假绿</h2></div></header><div v-if="!(activity360.coverage || []).length" class="inline-empty">此活动没有外部履约需求</div><div v-for="item in activity360.coverage || []" :key="item.requirementId" class="coverage-row"><span>{{ coverageTargetText(item) }}</span><el-tag size="mini" :type="Number(item.coveredBy) > 0 ? 'success' : 'danger'">{{ Number(item.coveredBy) > 0 ? '已覆盖' : '有缺口' }}</el-tag></div></article>
          <article class="cockpit-card wide"><header><div><p class="eyebrow">名单与奖励</p><h2>资格与投递状态</h2></div><el-button v-if="activity360.status === 4" v-hasPermi="['business:officialevent:settle']" type="text" @click="settle(activity360)">重试结算</el-button></header><el-table :data="activity360.participantsAndRewards || []" size="small"><el-table-column prop="memberId" label="成员" width="110" /><el-table-column label="参与状态" width="130"><template slot-scope="scope">{{ participationStateText(scope.row.state) }}</template></el-table-column><el-table-column prop="completedMissions" label="有效完成任务" width="140" /><el-table-column label="奖励投递" min-width="130"><template slot-scope="scope">{{ rewardStatusText(scope.row.rewardStatus) }}</template></el-table-column><el-table-column prop="failureReason" label="异常说明" min-width="180" /></el-table></article>
          <article class="cockpit-card wide"><header><div><p class="eyebrow">暂停控制</p><h2>影响、替代与通知必须可回读</h2></div></header><div v-if="activity360.pausedAt || activity360.resumeNote"><p class="risk-line"><strong>暂停开始：</strong>{{ fmt(activity360.pausedAt) }} · {{ activity360.pausedReason || '未记录原因' }}</p><p class="risk-line"><strong>影响范围：</strong>{{ activity360.pauseImpactScope || '未接入' }}</p><p class="risk-line"><strong>替代方案：</strong>{{ activity360.pauseAlternativePlan || '未接入' }}</p><p class="risk-line"><strong>通知策略 / 状态：</strong>{{ activity360.pauseNotificationPlan || '未接入' }} / {{ pauseNotificationText(activity360.pauseNotificationStatus) }}</p><p class="risk-line"><strong>恢复条件：</strong>{{ activity360.resumeConditions || '未接入' }}<span v-if="activity360.resumeNote">；最近处理：{{ activity360.resumeNote }}</span></p></div><div v-else class="inline-empty">尚无暂停记录。</div></article>
          <article class="cockpit-card wide risk-card"><header><div><p class="eyebrow">通知与风险</p><h2>待发公告、阻塞与运行风险</h2></div></header><div v-if="(activity360.notifications || []).length"><p v-for="notice in activity360.notifications" :key="notice.id" class="risk-line"><i class="el-icon-bell"></i><strong>{{ notice.title || '未命名通知' }}</strong> · {{ broadcastStatusText(notice.status) }} · {{ fmt(notice.scheduledTime || notice.createTime) }} · 历史汇总 {{ notice.reachedCount || 0 }}<el-button type="text" @click="readDelivery(notice.id)">逐渠道状态</el-button></p></div><div v-else class="inline-empty">本活动尚未建立通知，首条通知不可预览。</div><div v-if="activity360.publishCheck && activity360.publishCheck.blockers && activity360.publishCheck.blockers.length"><p v-for="risk in activity360.publishCheck.blockers" :key="risk" class="risk-line"><i class="el-icon-warning-outline"></i>{{ risk }}</p></div><div v-else class="inline-empty">当前无发布合同阻塞项。点击和订阅消息总触达等未接入指标不会在这里估算。</div><div v-for="risk in activity360.sourceRisks || []" :key="risk" class="risk-line"><i class="el-icon-warning-outline"></i>{{ risk }}</div></article>
        </section>
      </main>
    </section>

    <el-card v-if="broadcastRecovery.record || broadcastRecovery.error" class="risk-card">
      <p>{{ broadcastRecovery.error || (broadcastRecovery.record.result ? '原创建请求已有回执，不代表通知送达' : '创建结果待确认，原输入已冻结') }}</p>
      <p v-if="broadcastRecovery.record">原请求号 {{ broadcastRecovery.record.payload.clientRequestId }}</p>
      <template v-if="broadcastRecovery.record &amp;&amp; !broadcastRecovery.record.result">
        <el-button :disabled="broadcastRecovery.busy" @click="broadcastRecoveryAction('query')">查询原创建结果</el-button>
        <el-button :disabled="broadcastRecovery.busy" @click="broadcastRecoveryAction('retry')">原请求重试</el-button>
        <el-button :disabled="broadcastRecovery.busy" @click="broadcastRecoveryAction('cancel')">终止未执行创建</el-button>
      </template>
      <template v-if="broadcastRecovery.record &amp;&amp; broadcastRecovery.record.result">
        <p>{{ broadcastRecovery.record.result.state }} · 广播 {{ broadcastRecovery.record.result.broadcastId }}</p>
        <el-button v-if="broadcastRecovery.record.result.broadcastId" @click="readDelivery(broadcastRecovery.record.result.broadcastId)">读取投递状态</el-button>
        <el-button @click="broadcastRecoveryAction('acknowledge')">确认归档创建回执</el-button>
      </template>
      <el-button v-if="broadcastRecovery.error" @click="openBroadcastRecovery">重新读取记录</el-button>
    </el-card>
    <el-card v-if="deliveryView || deliveryError" class="risk-card">
      <p>{{ deliveryError }}</p>
      <template v-if="deliveryView">
        <p>{{ deliveryView.deliveryNote }}</p>
        <p v-for="(count,state) in deliveryView.deliveryCounts" :key="state">{{ deliveryStateText(state) }}：{{ count }}</p>
        <el-button @click="readDelivery(deliveryView.id)">读取原广播结果</el-button>
        <el-button v-if="deliveryView.canRecoverInapp" v-hasPermi="['business:officialevent:notify']" @click="readDelivery(deliveryView.id,true)">安全恢复原投递</el-button>
        <p>本轮仅人工恢复，自动规则及新增到期策略已暂缓；UNKNOWN需核对渠道事实，不会盲目重发。</p>
      </template>
    </el-card>
    <el-dialog title="官方通知" :visible.sync="notifyOpen" width="620px" append-to-body :close-on-click-modal="false">
      <el-form :disabled="!!broadcastRecovery.record || broadcastRecovery.busy || !!broadcastRecovery.error" label-position="top"><el-form-item label="通知对象"><el-checkbox-group v-model="notify.audience"><el-checkbox label="player">玩家</el-checkbox><el-checkbox label="club">俱乐部</el-checkbox><el-checkbox label="merchant">商家</el-checkbox></el-checkbox-group></el-form-item><el-form-item label="标题"><el-input v-model="notify.title" /></el-form-item><el-form-item label="内容"><el-input v-model="notify.content" type="textarea" :rows="4" /></el-form-item><el-form-item label="渠道"><el-checkbox-group v-model="notify.channels"><el-checkbox label="inapp">站内信</el-checkbox><el-checkbox label="subscribe">订阅消息</el-checkbox></el-checkbox-group></el-form-item></el-form>
      <div slot="footer"><el-button @click="notifyOpen = false">取消</el-button><el-button v-hasPermi="['business:officialevent:notify']" type="primary" @click="submitNotify">发送通知</el-button></div>
    </el-dialog>

    <el-dialog title="暂停活动" :visible.sync="pauseOpen" width="620px" append-to-body :close-on-click-modal="false">
      <p class="dialog-tip">暂停会立即关闭新报名、证据提交和外部履约动作；通知状态会按实际投递进度如实回显，不能把计划当作已送达。</p>
      <el-form :model="pauseForm" label-position="top"><el-form-item label="暂停原因" required><el-input v-model="pauseForm.reason" type="textarea" :rows="2" /></el-form-item><el-form-item label="影响范围 / 人数" required><el-input v-model="pauseForm.impactScope" placeholder="例如：已报名 42 人、2 家承接商家" /></el-form-item><el-form-item label="替代方案" required><el-input v-model="pauseForm.alternativePlan" type="textarea" :rows="2" /></el-form-item><el-form-item label="通知策略" required><el-input v-model="pauseForm.notificationPlan" type="textarea" :rows="2" placeholder="例如：站内信 + 订阅消息，负责人和发送时点" /></el-form-item></el-form>
      <div slot="footer"><el-button @click="pauseOpen = false">取消</el-button><el-button type="danger" @click="submitPause">确认暂停</el-button></div>
    </el-dialog>

    <el-dialog title="后台主办授权" :visible.sync="operatorAuthOpen" width="560px" append-to-body :close-on-click-modal="false">
      <p class="dialog-tip">建立的是可撤销、可审计的 <code>sys_user → ums_member</code> 映射。不会凭相同数字 ID 自动授权，也不会自动授予角色权限。</p>
      <el-form :model="operatorAuthForm" label-position="top">
        <el-form-item label="后台账号 ID" required><el-input-number v-model="operatorAuthForm.sysUserId" :min="1" style="width:100%" /></el-form-item>
        <el-form-item label="官方会员 ID" required><el-input-number v-model="operatorAuthForm.memberId" :min="1" style="width:100%" /></el-form-item>
        <el-form-item label="授权原因" required><el-input v-model="operatorAuthForm.reason" type="textarea" :rows="3" placeholder="例如：2026 夏季城市活动主办代办" /></el-form-item>
      </el-form>
      <div slot="footer"><el-button v-if="operatorIdentity.authorized" type="danger" plain @click="revokeCurrentOperatorAuthorization">撤销当前映射</el-button><el-button @click="operatorAuthOpen = false">取消</el-button><el-button type="primary" @click="submitOperatorAuthorization">保存授权</el-button></div>
    </el-dialog>

    <el-dialog title="历史 V1 活动" :visible.sync="legacyOpenVisible" width="560px" append-to-body><el-descriptions v-if="legacyEvent" :column="1" border><el-descriptions-item label="标题">{{ legacyEvent.title }}</el-descriptions-item><el-descriptions-item label="状态">{{ statusText(legacyEvent.status) }}</el-descriptions-item><el-descriptions-item label="说明">历史活动继续按旧版进度兼容读取；不能冒充新版证据口径。新活动请使用新版制作台。</el-descriptions-item></el-descriptions><div slot="footer"><el-button type="primary" @click="legacyOpenVisible = false">知道了</el-button></div></el-dialog>
  </div>
</template>

<script>
import {
  listOfficialEvent, getOfficialEvent, settleOfficialEvent, broadcastOfficial,
  queryBroadcastCreation, cancelBroadcastCreation, readBroadcastDelivery, recoverBroadcastDelivery,
  saveOfficialEventV2Draft, getOfficialEventV2Draft, checkOfficialEventV2Publish,
  scheduleOfficialEventV2, getOfficialEvent360, pauseOfficialEventV2, resumeOfficialEventV2,
  getOfficialEventV2OperatorIdentity, authorizeOfficialEventV2Operator, revokeOfficialEventV2Operator
} from '@/api/business/officialevent'
import auth from '@/plugins/auth'
import { createBroadcastRecovery } from '@/utils/official-broadcast-recovery'
import { toTimestamp } from '@/utils/ruoyi'

const STATUS = { 0: '草稿', 1: '已排期', 2: '报名中', 3: '进行中', 4: '待结算', 5: '已结束', 6: '已复盘', 9: '已下线' }
// 控制状态只认后端 control_state 的正式值 NORMAL/PAUSED；其余一律「状态待确认」。
const CONTROL_STATE_TEXT = { NORMAL: '运行正常', PAUSED: '已暂停' }
const CONTROL_STATE_TONE = { NORMAL: 'success', PAUSED: 'danger' }
// 任务类型的【只读展示】合同: 含后端全部正式值(含 P1 的 ROAM_SESSION_AREA)。
const MISSION_TYPE_TEXT = {
  ROAM_POI_ARRIVAL: '漫游 POI 到达',
  EVENT_POINT_ARRIVAL: '活动自有点到达（需定位验证）',
  THEME_VERIFIED_FINISH: '主题完成',
  ROAM_SESSION_AREA: '漫游区域覆盖'
}
const POLICY_TEXT = { SELF_RUN: '官方自行运行', OPTIONAL_PARTNERS: '合作可选', REQUIRED_FULFILLMENT: '外部履约必需' }
const BROADCAST_STATUS_TEXT = { 0: '草稿', 1: '投递未全部确认', 2: '投递处理结束（不代表已读）', 3: '已停止（已送达内容不回收）' }
const PARTY_ROLE_TEXT = { ORGANIZER: '主办方', CO_ORGANIZER: '协办方', FULFILLMENT_MERCHANT: '商家履约', FULFILLMENT_CLUB: '俱乐部履约', SPONSOR: '支持方', PROMOTION_PARTNER: '推广伙伴' }
const PARTY_TYPE_TEXT = { OFFICIAL: '官方', MERCHANT: '商家', CLUB: '俱乐部' }
const PARTY_STATUS_TEXT = { INVITED: '已邀请待确认', ACCEPTED: '已接受', ACTIVE: '已生效', DECLINED: '已拒绝', WITHDRAWN: '已撤回', SUSPENDED: '已暂停', COMPLETED: '已完成', ARCHIVED: '已归档' }
const COVERAGE_TARGET_TEXT = { THEME: '主题', ROAM_POI: '漫游据点', ROAM_AREA: '漫游范围', MERCHANT_NODE: '商家节点', CLUB_SESSION: '俱乐部场次', EVENT_OWNED_POINT: '活动自有点', DISPLAY_ONLY: '纯展示' }
// 360 只读面的枚举展示合同: 参与状态 / 奖励投递 / 暂停通知状态 / 绑定类型。
const PARTICIPATION_STATE_TEXT = { ACTIVE: '参与有效', LEFT: '已退出', REFUNDED: '已退款', BANNED: '已封禁' }
const REWARD_STATUS_TEXT = {
  NOT_ELIGIBLE: '未达资格', ELIGIBLE: '已具备资格', PENDING_ISSUE: '待发放',
  ISSUED: '已发放', CLAIMED: '已领取', FAILED: '发放失败', MANUAL_REVIEW: '人工复核中'
}
const PAUSE_NOTIFICATION_TEXT = { PENDING: '待发送' }
const BINDING_TYPE_TEXT = { THEME: '主题', ROAM_POI: '漫游据点', EVENT_OWNED_POINT: '活动自有点', ROAM_AREA: '漫游范围', DISPLAY_ONLY: '纯展示' }
// 只认自有键，避免 'constructor'/'__proto__' 命中 Object 原型把 native function 渲染到页面。
function enumText(map, value, fallback) {
  if (value === null || value === undefined || value === '') return fallback
  const key = String(value)
  return Object.prototype.hasOwnProperty.call(map, key) ? map[key] : fallback
}
let keySeed = 0

// 官方活动日期控件用 value-format="timestamp"(epoch ms)，但 OfficialEvent 的 Date 字段
// 无 @JsonFormat，接口回的是带偏移的 ISO 串；控件 new Date(Number(iso)) 得到 Invalid → 空白。
// 载入/提交边界统一复用后台同一严格入口 @/utils/ruoyi.toTimestamp（不再维护第三套规则）。
const DRAFT_DATE_FIELDS = {
  event: ['signupStart', 'activityStart', 'activityEnd', 'settleTime'],
  missions: ['availableStart', 'availableEnd'],
  parties: ['windowStart', 'windowEnd'],
  fulfillmentRequirements: ['windowStart', 'windowEnd']
}
function toEpochMs(value) {
  const ts = toTimestamp(value)
  return Number.isNaN(ts) ? null : ts
}
function normalizeDraftDates(draft) {
  if (!draft) return draft
  if (draft.event) DRAFT_DATE_FIELDS.event.forEach(field => { draft.event[field] = toEpochMs(draft.event[field]) })
  for (const key of ['missions', 'parties', 'fulfillmentRequirements']) {
    for (const item of draft[key] || []) {
      DRAFT_DATE_FIELDS[key].forEach(field => { item[field] = toEpochMs(item[field]) })
    }
  }
  return draft
}

function localKey(prefix) { keySeed += 1; return prefix + '-' + Date.now() + '-' + keySeed }

export default {
  name: 'OfficialEvent',
  data() {
    return {
      view: 'list', loading: false, total: 0, list: [], editorStep: 0, publishCheck: null,
      queryParams: { pageNum: 1, pageSize: 10, title: null, city: null, status: null },
      lifecycle: Object.keys(STATUS).map(value => ({ value: Number(value), label: STATUS[value] })),
      editorSteps: [
        { title: '活动意图', hint: '时间、城市、策略' }, { title: '内容与任务', hint: '绑定、规则、证据' },
        { title: '主办与承接', hint: '责任、覆盖、确认' }, { title: '目标与奖励', hint: '资格、投递、触达' },
        { title: '发布检查', hint: '预览、阻塞、排期' }
      ],
      categoryOptions: [{ value: 'city_light', label: '城市点亮' }, { value: 'challenge', label: '城市挑战' }, { value: 'festival', label: '节日活动' }, { value: 'brand', label: '品牌联动' }, { value: 'community', label: '社群共创' }],
      bindingTypes: [{ value: 'THEME', label: '主题' }, { value: 'ROAM_POI', label: '漫游 POI' }, { value: 'EVENT_OWNED_POINT', label: '活动自有点' }, { value: 'ROAM_AREA', label: '漫游范围（仅展示）' }, { value: 'DISPLAY_ONLY', label: '纯展示' }],
      missionTypes: [{ value: 'ROAM_POI_ARRIVAL', label: '漫游 POI 到达' }, { value: 'EVENT_POINT_ARRIVAL', label: '活动自有点到达（需定位验证）' }, { value: 'THEME_VERIFIED_FINISH', label: '主题完成' }],
      partyRoles: [{ value: 'ORGANIZER', label: '主办方' }, { value: 'CO_ORGANIZER', label: '协办方' }, { value: 'FULFILLMENT_MERCHANT', label: '商家履约' }, { value: 'FULFILLMENT_CLUB', label: '俱乐部履约' }, { value: 'SPONSOR', label: '支持方' }, { value: 'PROMOTION_PARTNER', label: '推广伙伴' }],
      draft: this.emptyDraft(), reward: this.emptyReward(), activity360: {},
      operatorIdentity: { sysUserId: null, memberId: null, authorized: false, authorizationStatus: 'UNBOUND' },
      operatorAuthOpen: false, operatorAuthForm: { sysUserId: null, memberId: null, reason: '' },
      broadcastRecovery: {record:null,error:'',busy:false}, deliveryView:null, deliveryError:'',
      notifyOpen: false, notifyEventId: null, notify: this.emptyNotify(), pauseOpen: false, pauseForm: this.emptyPause(), legacyOpenVisible: false, legacyEvent: null
    }
  },
  computed: {
    organizerSummary() {
      const organizers = this.draft.parties.filter(item => item.role === 'ORGANIZER' && item.status === 'ACCEPTED')
      return organizers.length === 1 ? this.partyTypeText(organizers[0].partyType) + ' #' + organizers[0].partyId + ' 已接受' : '需要恰有一位已接受主办方'
    },
    canWriteV2() { return Boolean(this.operatorIdentity && this.operatorIdentity.authorized) },
    operatorIdentityLabel() {
      return this.canWriteV2 ? '当前主办会员 #' + this.operatorIdentity.memberId : '当前后台账号未绑定主办会员'
    }
  },
  created() { this.getList(); this.loadOperatorIdentity(); this.openBroadcastRecovery() },
  watch: {
    '$store.state.user.id'() {this.deliveryView=null;this.deliveryError='';this.openBroadcastRecovery()},
    '$store.state.user.token'() {this.deliveryView=null;this.deliveryError='';this.openBroadcastRecovery()}
  },
  beforeDestroy() { if(this._broadcastRecovery)this._broadcastRecovery.dispose() },
  methods: {
    openBroadcastRecovery() {
      if(this._broadcastRecovery)this._broadcastRecovery.dispose()
      let firstEmission = true
      this._broadcastRecovery=createBroadcastRecovery({storage:window.localStorage,
        identity:()=>({id:String(this.$store.state.user.id||''),token:this.$store.state.user.token}),
        random:bytes=>window.crypto.getRandomValues(bytes),publish:broadcastOfficial,query:queryBroadcastCreation,cancel:cancelBroadcastCreation,
        changed:value=>{
          const previous = this.broadcastRecovery.record
          const current = value.record
          this.broadcastRecovery=value
          // Hydration is not a new submission. Only the matching original creation receipt
          // acknowledges this form; no channel delivery or read status is inferred here.
          if(!firstEmission && previous && !previous.result && current && current.result
              && current.result.state === 'APPLIED'
              && previous.payload.clientRequestId === current.payload.clientRequestId
              && this.notifyOpen && this.notifyEventId === current.payload.eventId) {
            this.$modal.msgSuccess('通知已提交')
            this.notifyOpen = false
          }
          firstEmission = false
        }})
    },
    broadcastRecoveryAction(action) {if(['query','retry','cancel','acknowledge'].includes(action))return this._broadcastRecovery[action]()},
    readDelivery(id, recover=false) {
      const actor=String(this.$store.state.user.id),token=this.$store.state.user.token
      this.deliveryError=''
      const read=()=>readBroadcastDelivery(id)
      const task=recover?this.$modal.confirm('只恢复原收件人的可安全恢复投递；UNKNOWN不会重发。').then(()=>recoverBroadcastDelivery(id)).then(read):read()
      task.then(res=>{if(actor===String(this.$store.state.user.id)&&token===this.$store.state.user.token)this.deliveryView={id,...res.data}}).catch(()=>{if(actor===String(this.$store.state.user.id)&&token===this.$store.state.user.token)this.deliveryError='结果暂未确认，请读取原广播状态；不要新建广播重发'})
    },
    emptyDraft() {
      return { event: { contractVersion: 2, category: 'city_light', fulfillmentPolicy: 'SELF_RUN', bannerEnabled: 0, collectiveEnabled: 0, collectiveMetric: 'complete_count', collectiveThreshold: 0, leaderboardEnabled: 0 }, bindings: [], missions: [], parties: [], fulfillmentRequirements: [] }
    },
    emptyReward() { return { settleXp: 0, settleCouponId: null, collectiveCouponId: null, settleBadge: false, rewardRuleVersion: '1' } },
    emptyNotify() { return { audience: [], title: '', content: '', channels: ['inapp'], city: '' } },
    emptyPause() { return { reason: '', impactScope: '', alternativePlan: '', notificationPlan: '' } },
    loadOperatorIdentity() {
      return getOfficialEventV2OperatorIdentity().then(res => {
        this.operatorIdentity = Object.assign({ sysUserId: null, memberId: null, authorized: false, authorizationStatus: 'UNBOUND' }, res.data || {})
        return this.operatorIdentity
      }).catch(() => {
        this.operatorIdentity = { sysUserId: null, memberId: null, authorized: false, authorizationStatus: 'UNBOUND' }
        return this.operatorIdentity
      })
    },
    openOperatorAuthorization() {
      this.operatorAuthForm = { sysUserId: this.operatorIdentity.sysUserId || null, memberId: this.operatorIdentity.memberId || null, reason: '' }
      this.operatorAuthOpen = true
    },
    submitOperatorAuthorization() {
      if (!this.operatorAuthForm.sysUserId || !this.operatorAuthForm.memberId || !this.operatorAuthForm.reason) {
        this.$modal.msgWarning('请填写后台账号、官方会员和授权原因')
        return
      }
      authorizeOfficialEventV2Operator(this.operatorAuthForm).then(res => {
        this.$modal.msgSuccess(res.msg || '后台主办授权已生效')
        this.operatorAuthOpen = false
        return this.loadOperatorIdentity()
      })
    },
    revokeCurrentOperatorAuthorization() {
      const sysUserId = this.operatorIdentity.sysUserId
      if (!sysUserId) return
      this.$prompt('撤销原因会进入活动授权审计。', '撤销后台主办授权', {
        confirmButtonText: '确认撤销', cancelButtonText: '取消', inputPattern: /\S+/, inputErrorMessage: '请填写撤销原因'
      }).then(({ value }) => revokeOfficialEventV2Operator(sysUserId, value)).then(res => {
        this.$modal.msgSuccess(res.msg || '后台主办授权已撤销')
        this.operatorAuthOpen = false
        return this.loadOperatorIdentity()
      }).catch(() => {})
    },
    isV2(row) { return Number(row.contractVersion || 1) >= 2 },
    statusText(status) { return enumText(STATUS, status, '未知') },
    controlStateText(state) { return enumText(CONTROL_STATE_TEXT, state, '状态待确认') },
    controlStateTone(state) { return enumText(CONTROL_STATE_TONE, state, 'warning') },
    controlStateClass(state) { return state === 'PAUSED' ? 'state-paused' : (state === 'NORMAL' ? 'state-normal' : 'state-unknown') },
    partyRoleText(role) {
      return enumText(PARTY_ROLE_TEXT, role, '未记录职责')
    },
    partyTypeText(type) {
      return enumText(PARTY_TYPE_TEXT, type, '未记录主体')
    },
    partyStatusText(status) {
      return enumText(PARTY_STATUS_TEXT, status, '状态待确认')
    },
    missionTypeText(type) {
      return enumText(MISSION_TYPE_TEXT, type, '未记录任务类型')
    },
    coverageTargetText(item) {
      const role = this.partyRoleText(item.requiredRole)
      const target = enumText(COVERAGE_TARGET_TEXT, item.targetType, '未记录对象')
      const targetId = item.targetId === null || item.targetId === undefined || item.targetId === '' ? '未记录' : item.targetId
      return role + ' · ' + target + ' #' + targetId
    },
    participationStateText(state) { return enumText(PARTICIPATION_STATE_TEXT, state, '状态待确认') },
    rewardStatusText(status) { return enumText(REWARD_STATUS_TEXT, status, '未生成发放记录') },
    pauseNotificationText(status) {
      if (status === null || status === undefined || status === '') return '未接入'
      return enumText(PAUSE_NOTIFICATION_TEXT, status, '状态待确认')
    },
    bindingTypeText(type) { return enumText(BINDING_TYPE_TEXT, type, '未记录绑定类型') },
    statusType(status) { return status === 3 ? 'success' : (status === 4 ? 'warning' : (status === 9 ? 'info' : '')) },
    deliveryStateText(state) { return ({INAPP_STORED:'站内已入库',CHANNEL_ACCEPTED:'订阅渠道已受理（非已读）',REJECTED:'订阅明确拒绝，不自动重发',NOT_SUBMITTED:'订阅未提交，修复前置后人工恢复',RETRYABLE:'可沿原站内意图恢复',UNKNOWN:'渠道结果待核，不重发',READY:'已冻结待处理',CAPPED:'原频控阻止，未投递',LEGACY_UNCONFIRMED:'历史结果待核，不重发'})[state] || '未知状态' },
    broadcastStatusText(status) { return enumText(BROADCAST_STATUS_TEXT, status, '状态未知') },
    policyText(policy) { return enumText(POLICY_TEXT, policy, '未设置') },
    fmt(value) { if (!value) return '未设置'; const date = new Date(Number(value)); return isNaN(date.getTime()) ? String(value) : date.getFullYear() + '/' + (date.getMonth() + 1) + '/' + date.getDate() + ' ' + String(date.getHours()).padStart(2, '0') + ':' + String(date.getMinutes()).padStart(2, '0') },
    getList() {
      this.loading = true
      listOfficialEvent(this.queryParams).then(res => { this.list = res.rows || []; this.total = res.total || 0 }).finally(() => { this.loading = false })
    },
    handleQuery() { this.queryParams.pageNum = 1; this.getList() },
    resetQuery() { this.resetForm('queryForm'); this.handleQuery() },
    createV2() {
      if (!this.canWriteV2) { this.$modal.msgWarning('请先建立当前后台账号到有效官方会员的可审计授权'); return }
      this.draft = this.emptyDraft(); this.reward = this.emptyReward(); this.editorStep = 0; this.publishCheck = null; this.view = 'editor'
    },
    openDraft(id) {
      getOfficialEventV2Draft(id).then(res => {
        const data = res.data || {}
        this.draft = { event: data.event || {}, bindings: data.bindings || [], missions: data.missions || [], parties: data.parties || [], fulfillmentRequirements: data.fulfillmentRequirements || [] }
        this.draft.bindings.forEach(item => { item._key = localKey('binding') })
        this.draft.missions.forEach(item => { item._key = localKey('mission') })
        this.draft.parties.forEach(item => { item._key = localKey('party') })
        this.draft.fulfillmentRequirements.forEach(item => { item._key = localKey('requirement') })
        normalizeDraftDates(this.draft)
        this.reward = this.parseReward(this.draft.event.rewardJson)
        this.editorStep = 0; this.publishCheck = null; this.view = 'editor'
      })
    },
    parseReward(value) { try { return Object.assign(this.emptyReward(), value ? JSON.parse(value) : {}) } catch (e) { return this.emptyReward() } },
    sourceOptions(binding) {
      if (binding.bindingType === 'ROAM_POI' || binding.bindingType === 'THEME') return [{ value: 'REFERENCE', label: '引用现有对象' }]
      if (binding.bindingType === 'EVENT_OWNED_POINT') return [{ value: 'EVENT_OWNED', label: '活动自有对象' }]
      return [{ value: 'REFERENCE', label: '引用现有对象' }, { value: 'EVENT_OWNED', label: '活动自有对象' }]
    },
    syncBindingSource(binding) {
      const options = this.sourceOptions(binding)
      if (!options.some(item => item.value === binding.sourceMode)) binding.sourceMode = options[0].value
      if (binding.sourceMode === 'REFERENCE') {
        binding.latitude = null; binding.longitude = null; binding.radiusM = null
      } else {
        binding.sourceRefId = null
      }
    },
    addBinding() { this.draft.bindings.push({ _key: localKey('binding'), bindingCode: '', bindingType: 'ROAM_POI', sourceMode: 'REFERENCE', sourceRefId: null, city: this.draft.event.city || '' }) },
    removeBinding(index) { this.draft.bindings.splice(index, 1) },
    addMission() { this.draft.missions.push({ _key: localKey('mission'), missionCode: '', bindingCode: '', missionType: 'ROAM_POI_ARRIVAL', title: '', shortDescription: '', ruleJson: '{}', ruleVersion: '1', requiredCount: 1, visibility: 1 }) },
    removeMission(index) { this.draft.missions.splice(index, 1) },
    addParty() { this.draft.parties.push({ _key: localKey('party'), partyType: 'OFFICIAL', partyId: null, role: 'ORGANIZER', status: 'INVITED', capacity: 0 }) },
    removeParty(index) { this.draft.parties.splice(index, 1) },
    addRequirement() { this.draft.fulfillmentRequirements.push({ _key: localKey('requirement'), missionCode: '', targetType: '', targetId: null, requiredRole: 'FULFILLMENT_MERCHANT', requiredCapacity: 1, critical: 1, status: 'DRAFT' }) },
    removeRequirement(index) { this.draft.fulfillmentRequirements.splice(index, 1) },
    payload() {
      const clean = item => { const copy = Object.assign({}, item); delete copy._key; return copy }
      const payload = {
        event: Object.assign({}, this.draft.event, { contractVersion: 2, rewardJson: JSON.stringify(this.reward) }),
        bindings: this.draft.bindings.map(clean),
        missions: this.draft.missions.map(clean),
        parties: this.draft.parties.map(clean),
        fulfillmentRequirements: this.draft.fulfillmentRequirements.map(clean)
      }
      normalizeDraftDates(payload)
      return payload
    },
    saveDraft() {
      if (!this.canWriteV2) { this.$modal.msgWarning('当前后台账号未绑定有效官方会员，不能写入 V2 合同'); return Promise.reject(new Error('operator authorization required')) }
      if (!this.draft.event.title || !this.draft.event.city) { this.$modal.msgWarning('请先填写活动标题和城市'); return Promise.reject(new Error('draft required')) }
      return saveOfficialEventV2Draft(this.payload()).then(res => {
        this.draft.event.id = res.data
        this.$modal.msgSuccess('V2 草稿已保存')
        return res.data
      })
    },
    runPublishCheck() {
      return this.saveDraft().then(id => checkOfficialEventV2Publish(id)).then(res => {
        this.publishCheck = res.data || { pass: false, blockers: ['发布检查没有返回结果'] }
        this.editorStep = 4
        return this.publishCheck
      })
    },
    schedule() {
      this.runPublishCheck().then(check => {
        if (!check || !check.pass) { this.$modal.msgWarning('请先修复发布检查阻塞项'); return null }
        return scheduleOfficialEventV2(this.draft.event.id)
      }).then(res => {
        if (!res) return
        this.$modal.msgSuccess('已提交平台审核，审核通过后进入排期')
        this.backToList()
      }).catch(() => {})
    },
    open360(id) { getOfficialEvent360(id).then(res => { this.activity360 = res.data || {}; this.view = 'cockpit' }) },
    pause() { this.pauseForm = this.emptyPause(); this.pauseOpen = true },
    submitPause() { if (!this.pauseForm.reason || !this.pauseForm.impactScope || !this.pauseForm.alternativePlan || !this.pauseForm.notificationPlan) { this.$modal.msgWarning('请完整填写暂停合同字段'); return } pauseOfficialEventV2(this.activity360.id, this.pauseForm).then(() => { this.pauseOpen = false; this.$modal.msgSuccess('活动已暂停，通知状态为待执行'); this.open360(this.activity360.id) }).catch(() => {}) },
    resume() { this.$prompt('恢复前会重新检查主办方、履约覆盖和当前时间窗口。', '恢复活动', { confirmButtonText: '确认恢复', cancelButtonText: '取消', inputValue: '风险已处理' }).then(({ value }) => resumeOfficialEventV2(this.activity360.id, value)).then(() => { this.$modal.msgSuccess('活动已恢复'); this.open360(this.activity360.id) }).catch(() => {}) },
    settle(row) {
      if (!auth.hasPermi('business:officialevent:settle')) { this.$modal.msgWarning('没有结算权限'); return }
      this.$modal.confirm('仅待结算活动可执行资格投递。确认继续？').then(() => settleOfficialEvent(row.id)).then(res => { this.$modal.msgSuccess(res.msg || '结算已执行'); if (this.view === 'cockpit') this.open360(row.id); else this.getList() }).catch(() => {})
    },
    backToList() { this.view = 'list'; this.getList() },
    openLegacy(row) { getOfficialEvent(row.id).then(res => { this.legacyEvent = res.data || row; this.legacyOpenVisible = true }) },
    openNotify(row) {
      if (!auth.hasPermi('business:officialevent:notify')) { this.$modal.msgWarning('没有通知权限'); return }
      if(this._broadcastRecovery&&this._broadcastRecovery.pending()){this.$modal.msgWarning('请先处理原广播请求');return}
      this.notifyEventId = row ? row.id : null; this.notify = Object.assign(this.emptyNotify(), { title: row ? row.title : '', city: row ? row.city || '' : '' }); this.notifyOpen = true
    },
    submitNotify() {
      if (!auth.hasPermi('business:officialevent:notify')) { this.$modal.msgWarning('没有通知权限'); return }
      if (!this.notify.audience.length) { this.$modal.msgWarning('请选择通知对象'); return }
      const data = { eventId: this.notifyEventId, title: this.notify.title || '官方通知', audience: this.notify.audience.join(','), copyMode: 1, contentJson: JSON.stringify({ title: this.notify.title || '官方通知', sub: this.notify.content }), channels: this.notify.channels.join(',') || 'inapp', city: this.notify.city || null }
      if(!data.title.trim()||!data.audience){this.$modal.msgWarning('请填写标题和通知对象');return}
      return this._broadcastRecovery.start(data)
    }
  }
}
</script>

<style scoped>
.official-event-console { min-height: 100%; background: #f3f6f7; color: #17232c; }
.console-list { max-width: 1480px; margin: 0 auto; }
.console-hero, .editor-header { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 28px; align-items: flex-start; padding: 28px var(--cy-space-7); background: linear-gradient(115deg, #132c35, #1f4a51); color: #f5fcf9; border-radius: 14px; }
.console-hero h1, .editor-header h1 { margin: var(--cy-space-1) 0 var(--cy-space-2); font-size: 26px; line-height: 1.25; letter-spacing: -.3px; }
.eyebrow { margin: 0; color: #69d0b1; font: 700 11px/1.3 monospace; letter-spacing: .11em; }
.hero-copy { max-width: 700px; margin: 0; color: #c8ddda; line-height: 1.7; }
.v2-readonly-alert { flex-basis: 100%; margin: -8px 0 0; }
.hero-actions, .editor-header-actions { flex: none; display: flex; gap: var(--cy-space-1-5); align-items: center; }
.status-rail { display: flex; align-items: center; gap: var(--cy-space-3); margin: var(--cy-space-3-5) 0; padding: var(--cy-space-2-5) var(--cy-space-3-5); background: var(--cy-color-bg-surface); border: 1px solid #dbe6e4; border-radius: 10px; overflow-x: auto; }
.rail-item { white-space: nowrap; color: #55706c; font-size: 12px; }
.rail-item i { display: inline-block; width: 7px; height: 7px; margin-right: var(--cy-space-0-5); background: #5bbd9e; border-radius: 50%; }
.operator-state { padding: var(--cy-space-1) var(--cy-space-2); border-radius: 999px; white-space: nowrap; font-size: 12px; }.operator-state.bound { color: #246b57; background: #e7f5ef; }.operator-state.unbound { color: #8c5c2e; background: #fff3e3; }.rail-note { margin-left: auto; white-space: nowrap; color: #7f908d; font-size: 12px; }
.search-row { margin: var(--cy-space-3-5) 0 var(--cy-space-0-5); }
.event-table { border-radius: 10px; overflow: hidden; box-shadow: 0 6px 20px rgba(19, 44, 53, .05); }
.event-title { font-weight: 650; color: #1c3032; }.event-meta, .time-line { color: #82928e; font-size: 12px; }.state-paused { color: #c35a47; }.state-normal { color: #438a74; }.state-unknown { color: #b8860b; }.control-unknown { color: #f0d9a8; font-size: 12px; }
.editor-shell, .cockpit-shell { min-height: 100vh; background: #f3f6f7; }.editor-header { border-radius: 0; }.editor-header .el-button--text { color: #aadbcf; padding-left: 0; }.editor-header h1 { margin-bottom: 0; }
.editor-main { display: grid; grid-template-columns: 255px minmax(0, 1fr); max-width: 1500px; margin: 0 auto; }.editor-nav { padding: 28px var(--cy-space-3-5); background: #e9efee; border-right: 1px solid #d8e2e0; }
.step-nav { display: grid; grid-template-columns: 34px 1fr; width: 100%; padding: var(--cy-space-2-5) var(--cy-space-3); /* ds-ok: 14px 无匹配间距token */ border: 0; border-left: 3px solid transparent; background: transparent; color: #64807a; text-align: left; cursor: pointer; }.step-nav span { grid-row: span 2; color: #91aaa5; font: 700 12px monospace; }.step-nav strong { font-size: 14px; }.step-nav small { margin-top: var(--cy-legacy-3); color: #9aaba8; font-size: 11px; }.step-nav.active { border-left-color: #1a9075; background: #f7fbfa; color: #123d38; }.step-nav.active span { color: #1a9075; }
.editor-panel { min-height: calc(100vh - 148px); padding: 42px clamp(var(--cy-space-4-5), 5vw, 74px) 64px; background: var(--cy-color-bg-surface); }.panel-heading { max-width: 760px; margin-bottom: 28px; }.panel-heading h2, .cockpit-card h2 { margin: var(--cy-legacy-5) 0 var(--cy-space-2); /* ds-ok: 5px 无匹配间距token */ color: #173737; font-size: 24px; }.panel-heading p:not(.eyebrow) { margin: 0; color: #71837f; line-height: 1.65; }.row-heading, .subsection-heading { display: flex; justify-content: space-between; gap: var(--cy-space-3-5); align-items: flex-start; }.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: var(--cy-space-4-5); }.form-grid .full { grid-column: 1 / -1; }.compact-grid { margin-top: var(--cy-space-1-5); }.field-hint { margin-left: var(--cy-space-2); color: #879995; font-size: 12px; }
.contract-card { margin: var(--cy-legacy-15) 0; padding: var(--cy-space-3-5) var(--cy-space-5) var(--cy-space-1); /* ds-ok: 18px 无匹配间距token */ border: 1px solid #d9e5e2; border-radius: 10px; background: #fbfdfd; }.card-header { display: flex; justify-content: space-between; align-items: center; padding-bottom: 7px; color: #234843; }.danger-text { color: #c65a4b; }.subsection-heading { margin: 45px 0 var(--cy-legacy-15); padding-top: var(--cy-space-5); border-top: 1px solid #e2ece9; }.subsection-heading h3 { margin: 0 0 var(--cy-legacy-5); color: #234843; }.subsection-heading p { margin: 0; color: #7d918d; font-size: 13px; line-height: 1.55; }.empty-stage, .policy-note, .inline-empty { padding: var(--cy-space-5); border: 1px dashed #bed2ce; border-radius: var(--cy-radius-card); color: #708783; background: #f8fbfa; }.policy-note { margin-top: var(--cy-space-3); }.notice-callout { display: flex; gap: 13px; margin-top: var(--cy-space-6); padding: var(--cy-space-4) var(--cy-space-3-5); /* ds-ok: 18px 无匹配间距token */ border-left: 4px solid #65bca4; background: #edf8f4; color: #335f55; }.notice-callout i { font-size: 18px; }.notice-callout p { margin: var(--cy-space-1) 0 0; color: #63857c; font-size: 13px; line-height: 1.5; }
.preview-grid { display: grid; grid-template-columns: minmax(0, 1.5fr) minmax(240px, .8fr); gap: var(--cy-space-5); }.preview-card { padding: var(--cy-space-4-5); border: 1px solid #d9e5e2; border-radius: var(--cy-radius-dialog); background: #fbfdfc; }.player-preview { background: linear-gradient(150deg, #f4fffb, #f9fbf7); }.preview-label { color: #248d73; font: 700 11px monospace; letter-spacing: .09em; }.preview-card h3 { margin: var(--cy-space-1-5) 0 var(--cy-space-0-5); color: #1b443c; }.preview-card > p { margin: 0 0 var(--cy-space-3-5); color: #6c817d; }.preview-mission { display: flex; align-items: center; gap: var(--cy-space-1-5); padding: 11px 0; border-top: 1px solid #e4efec; }.mission-dot { width: 9px; height: 9px; background: #6dc6ab; border-radius: 50%; }.preview-mission div { flex: 1; display: grid; gap: var(--cy-legacy-3); }.preview-mission small { color: #819490; }.preview-mission em { color: #80948f; font-size: 11px; font-style: normal; }.preview-empty { color: #8ba09a; }.preview-card dl { margin: var(--cy-space-3-5) 0 0; }.preview-card dt { margin-top: var(--cy-legacy-15); color: #7e938e; font-size: 12px; }.preview-card dd { margin: var(--cy-space-1) 0; color: #284741; font-weight: 600; }.publish-result { display: grid; gap: var(--cy-space-2-5); margin-top: 25px; padding: var(--cy-space-5); border-radius: 10px; }.publish-result.pass { background: #eaf8f2; border: 1px solid #a9dbc9; color: #2b6a58; }.publish-result.blocked { background: #fff4f1; border: 1px solid #f0c4b9; color: #955246; }.publish-result p { margin: var(--cy-legacy-5) 0 0; }.publish-result ul { margin: 0; padding-left: var(--cy-space-3-5); line-height: 1.7; }.publish-result .risks { color: #9a7a2e; }.publish-actions { display: flex; justify-content: flex-end; gap: var(--cy-space-1-5); margin-top: var(--cy-space-5); }.editor-footer { position: sticky; bottom: 0; display: flex; align-items: center; justify-content: flex-end; gap: var(--cy-space-2-5); padding: var(--cy-space-3) var(--cy-space-7); border-top: 1px solid #dbe6e3; background: rgba(255,255,255,.97); box-shadow: 0 -5px 16px rgba(15,49,42,.05); }.editor-footer span { margin-right: auto; color: #7f908d; font-size: 12px; }
.cockpit-main { max-width: 1450px; margin: 0 auto; padding: 28px; }.cockpit-overview { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--cy-space-2-5); }.cockpit-overview article { min-height: 130px; padding: 21px; border-radius: 10px; background: var(--cy-color-bg-surface); border: 1px solid #dbe7e4; }.cockpit-overview span, .cockpit-overview small { display: block; color: #7b908b; font-size: 12px; }.cockpit-overview strong { display: block; margin: 9px 0 var(--cy-space-0-5); color: #1e7060; font-size: 31px; }.cockpit-overview .status-word { font-size: 22px; }.cockpit-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--cy-space-4); margin-top: var(--cy-space-4); }.cockpit-card { padding: var(--cy-space-5); border: 1px solid #dbe7e4; border-radius: 10px; background: var(--cy-color-bg-surface); }.cockpit-card.wide { grid-column: 1 / -1; }.cockpit-card header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: var(--cy-legacy-15); }.cockpit-card h2 { font-size: 19px; }.party-row, .coverage-row { display: flex; align-items: center; justify-content: space-between; padding: 11px 0; border-top: 1px solid #edf2f1; }.party-row small { display: block; margin-top: var(--cy-legacy-3); color: #82938f; }.coverage-row { color: #45625c; font-size: 13px; }.risk-card { background: #fffaf6; }.risk-line { margin: 9px 0; color: #9c594d; }.risk-line i { margin-right: 7px; }
@media (max-width: 900px) { .console-hero, .editor-header { display: block; }.hero-actions, .editor-header-actions { margin-top: var(--cy-space-3-5); flex-wrap: wrap; }.editor-main { grid-template-columns: 1fr; }.editor-nav { display: flex; overflow-x: auto; padding: var(--cy-space-1-5); border-right: 0; border-bottom: 1px solid #d8e2e0; }.step-nav { min-width: 145px; }.form-grid, .preview-grid, .cockpit-overview, .cockpit-grid { grid-template-columns: 1fr; }.cockpit-card.wide { grid-column: auto; }.editor-panel { padding: 28px var(--cy-space-3-5) 64px; }.rail-note { display: none; } }
</style>
