<template>
  <main class="app-container topic-workspace">
    <cy-page-header
      eyebrow="主题 360 · 运营工作台"
      :title="topic.name || '主题加载中'"
      :description="`主题 #${topic.id || '-'} · 以当前状态决定下一步，而不是堆叠管理入口。`"
      :status="lifecycleLabel"
      :updated-at="updatedAt"
    >
      <el-button icon="el-icon-back" @click="goBack">返回主题列表</el-button>
      <el-button
        v-if="canOpenTopicEditor"
        slot="primary"
        type="primary"
        icon="el-icon-edit-outline"
        @click="openLegacyDetail"
      >{{ editorActionLabel }}</el-button>
    </cy-page-header>

    <cy-resource-state
      v-if="error"
      class="topic-workspace__alert"
      kind="error"
      :title="error"
      :retry-text="$route.query.id ? '重新读取' : ''"
      :retrying="loading"
      @retry="loadWorkspace"
    />

    <section v-loading="loading" class="topic-workspace__identity" aria-label="主题身份与运营状态">
      <article>
        <span>内容主体</span>
        <strong>{{ publisherLabel }}</strong>
        <p>{{ publisherDescription }}</p>
      </article>
      <article>
        <span>官方活动</span>
        <strong>{{ officialActivityRelationLabel }}</strong>
        <p>{{ officialActivityRelationDescription }}</p>
      </article>
      <article>
        <span>主题玩法</span>
        <strong>{{ productTypeLabel }}</strong>
        <p>主题ID、城市与玩法来自主题主数据。</p>
      </article>
      <article>
        <span>主题地点</span>
        <strong>{{ topic.addressName || '地点待配置' }}</strong>
        <p>主题 #{{ topic.id || '-' }} · 当前地点由主题主数据提供。</p>
      </article>
      <article>
        <span>运营阶段</span>
        <strong>{{ lifecycleLabel }}</strong>
        <p>{{ auditLabel }} · {{ userStatusLabel }}</p>
      </article>
      <article>
        <span>公开招募</span>
        <strong>{{ recruitmentLabel }}</strong>
        <p>公开池只描述可申请范围，不替代合作与履约事实。</p>
      </article>
      <article>
        <span>运营主办方</span>
        <strong>{{ organizerLabel }}</strong>
        <p>{{ organizerDescription }}</p>
      </article>
      <article>
        <span>实际履约方</span>
        <strong>{{ actualFulfillmentLabel }}</strong>
        <p>{{ actualFulfillmentDescription }}</p>
      </article>
      <article>
        <span>俱乐部保证金</span>
        <strong>{{ depositSummaryLabel }}</strong>
        <p>{{ depositSummaryDescription }}</p>
      </article>
    </section>

    <section v-loading="loading" class="topic-workspace__decision" aria-label="可开跑判断与下一步">
      <div class="topic-workspace__decision-main">
        <div class="topic-workspace__eyebrow">Run readiness</div>
        <div class="topic-workspace__decision-title">
          <div>
            <strong>{{ readinessLabel }}</strong>
            <p>{{ readinessDescription }}</p>
          </div>
          <cy-status-tag :label="readinessLabel" :tone="readinessTone" />
        </div>
        <div class="topic-workspace__relay" aria-label="主题运营接力线">
          <span :class="{ 'is-current': activeTab === 'overview' }">内容</span>
          <i />
          <span :class="{ 'is-current': activeTab === 'cooperation' }">供给</span>
          <i />
          <span :class="{ 'is-current': activeTab === 'execution' }">执行</span>
          <i />
          <span :class="{ 'is-current': activeTab === 'review' }">复盘</span>
        </div>
      </div>
      <aside class="topic-workspace__next-action">
        <span>建议下一步</span>
        <strong>{{ nextActionLabel }}</strong>
        <p>{{ editPolicyDescription }}</p>
      </aside>
      <dl class="topic-workspace__dates">
        <div><dt>招募截止</dt><dd>{{ formatDate(operation.recruitDeadline) }}</dd></div>
        <div><dt>商家报名</dt><dd>{{ dateRange(operation.merchantSignUpStartDate, operation.merchantSignUpEndDate) }}</dd></div>
        <div><dt>主题周期</dt><dd>{{ dateRange(operation.startDate, operation.endDate) }}</dd></div>
      </dl>
    </section>

    <section v-loading="loading" class="topic-workspace__summary" aria-label="主题运营摘要">
      <cy-metric-card
        label="路线完整度"
        :value="isAxisAvailable('structure') ? `${chapters.length} / ${nodeCount}` : '—'"
        :description="structureDescription"
        :connected="isAxisAvailable('structure')"
        :connection-label="axisCapabilityLabel('structure')"
        :connection-tone="axisCapabilityTone('structure')"
      />
      <cy-metric-card
        label="章节供给"
        :value="isAxisAvailable('merchantSupply') ? `${merchantSupply.activeOfferCount || 0}` : '—'"
        :description="merchantSupplyDescription"
        :connected="isAxisAvailable('merchantSupply')"
        :connection-label="axisCapabilityLabel('merchantSupply')"
        :connection-tone="axisCapabilityTone('merchantSupply')"
      />
      <cy-metric-card
        label="俱乐部履约"
        :value="isAxisAvailable('clubFulfillment') ? `${clubFulfillment.signedCooperationCount || 0}` : '—'"
        :description="clubFulfillmentDescription"
        :connected="isAxisAvailable('clubFulfillment')"
        :connection-label="axisCapabilityLabel('clubFulfillment')"
        :connection-tone="axisCapabilityTone('clubFulfillment')"
      />
      <cy-metric-card
        label="已核销销售额"
        :value="fulfillmentMetricValue"
        :description="fulfillmentDescription"
        :connected="isAxisAvailable('fulfillment')"
        :connection-label="axisCapabilityLabel('fulfillment')"
        :connection-tone="axisCapabilityTone('fulfillment')"
      />
    </section>

    <section v-loading="loading" class="topic-workspace__body">
      <el-tabs v-model="activeTab" class="topic-workspace__tabs">
        <el-tab-pane label="总览" name="overview">
          <div class="pane-heading">
            <div>
              <span>Overview</span>
              <h2>{{ isHistoricalTopic ? '历史待归属主题总览' : (isPlazaEntry ? '广场主题运营总览' : '官方主题运营总览') }}</h2>
            </div>
            <cy-status-tag :label="readinessLabel" :tone="readinessTone" />
          </div>
          <div class="overview-grid">
            <article class="overview-card overview-card--next">
              <span>下一步</span>
              <strong>{{ nextActionLabel }}</strong>
              <p>{{ readinessDescription }}</p>
            </article>
            <article class="overview-card">
              <span>内容结构</span>
              <strong>{{ chapters.length }} 章 · {{ nodeCount }} 节点</strong>
              <p>{{ structureDescription }}</p>
            </article>
            <article class="overview-card">
              <span>有效商家供给</span>
              <strong>{{ merchantSupply.activeOfferCount || 0 }} 家</strong>
              <p>{{ merchantSupplyDescription }}</p>
            </article>
            <article class="overview-card">
              <span>带队执行</span>
              <strong>{{ clubFulfillment.activeSessionCount || 0 }} 场</strong>
              <p>{{ clubFulfillment.attendanceCount || 0 }} 条集合到场事实</p>
            </article>
          </div>
          <section class="overview-governance" :class="{ 'is-plaza': isPlazaEntry }">
            <div class="pane-heading">
              <div><span>Governance context</span><h2>发布与治理上下文</h2></div>
              <cy-status-tag :label="auditLabel" :tone="governance.checkStatus === 1 ? 'success' : 'warning'" />
            </div>
            <div class="status-grid">
              <article><span>后台审核</span><strong>{{ auditLabel }}</strong><p>{{ governance.reason || '当前没有驳回说明。' }}</p></article>
              <article><span>用户端可见</span><strong>{{ userStatusLabel }}</strong><p>可见性仍由审核链路决定。</p></article>
              <article><span>结算状态</span><strong>{{ settlementStatusOverviewLabel }}</strong><p>{{ axisCapabilityDescription('settlement') }}</p></article>
              <article><span>内容编辑边界</span><strong>{{ editPolicyLabel }}</strong><p>{{ editPolicyDescription }}</p></article>
            </div>
          </section>
          <section class="topic-workspace__operations" aria-label="主题统一待办">
            <cy-entity-header
              v-if="!operationsError && !operationsUnavailable"
              eyebrow="Operation snapshot"
              title="待处理与关系状态"
              :status="{ label: operationSummary.actionableCount ? `${operationSummary.actionableCount} 项可处理` : '全部只读', tone: operationSummary.actionableCount ? 'warning' : 'neutral' }"
              :facts="operationFacts"
            />
            <cy-resource-state
              v-if="operationsError"
              class="topic-workspace__operations-alert"
              kind="error"
              title="待处理快照当前不可用"
              :description="operationsError"
              retry-text="重新读取"
              :retrying="loading"
              @retry="loadWorkspace"
            />
            <cy-resource-state
              v-else-if="operationsUnavailable"
              class="topic-workspace__operations-alert"
              kind="forbidden"
              title="主题待办快照保持关闭"
              :description="operationsScopeMessage"
            />
            <template v-else>
              <p class="topic-workspace__operations-note">{{ operationsScopeMessage }}</p>
              <cy-data-table
                :data="operationItems"
                :columns="operationColumns"
                :total="operationItems.length"
                :page="1"
                :limit="Math.max(operationItems.length, 1)"
                :loading="loading"
                :show-refresh="true"
                empty-text="当前主题没有可展示的待办摘要"
                @refresh="loadWorkspace"
              >
                <template #source="{ row }">
                  <strong>{{ operationSourceLabel(row.sourceRef) }}</strong>
                </template>
                <template #displayState="{ row }">
                  <cy-status-tag :label="row.displayState || '状态待核验'" :tone="operationTone(row.actionability)" />
                </template>
                <template #actionability="{ row }">
                  <span class="topic-workspace__actionability">{{ operationActionabilityLabel(row.actionability) }}</span>
                </template>
                <template #latestAt="{ row }">
                  {{ formatDate(row.latestAt) }}
                </template>
                <template #actions="{ row }">
                  <span v-if="row.allowedActions && row.allowedActions.length" class="topic-workspace__disabled-actions">
                    <el-tooltip
                      v-for="action in row.allowedActions"
                      :key="action.code"
                      :content="operationDisabledReason(action.disabledReasonCode)"
                      placement="top"
                    >
                      <span>
                        <el-button size="mini" type="text" disabled>{{ operationActionLabel(action.code) }}</el-button>
                      </span>
                    </el-tooltip>
                  </span>
                  <span v-else class="topic-workspace__actionability">无可用动作</span>
                </template>
              </cy-data-table>
            </template>
          </section>
        </el-tab-pane>

        <el-tab-pane label="内容与售卖" name="content">
          <div class="pane-heading">
            <div><span>Content & sales</span><h2>章节、节点与票务</h2></div>
            <el-button
              v-if="canOpenTopicEditor"
              size="small"
              icon="el-icon-edit"
              @click="openLegacyDetail"
            >{{ editorActionLabel }}</el-button>
          </div>
          <cy-empty-state
            v-if="!isAxisAvailable('structure')"
            :title="axisCapabilityLabel('structure')"
            :description="axisCapabilityDescription('structure')"
            icon="el-icon-warning-outline"
          />
          <cy-empty-state
            v-else-if="!loading && !error && !chapters.length"
            title="尚未配置章节"
            description="内容结构读取成功，但该主题还没有章节与城市节点。"
            icon="el-icon-collection"
          />
          <el-collapse v-else v-model="openChapters" class="content-chapters">
            <el-collapse-item v-for="chapter in chapters" :key="chapter.id" :name="chapter.id">
              <template slot="title">
                <strong>{{ chapter.name || `章节 #${chapter.id}` }}</strong>
                <span class="chapter-count">{{ (chapter.nodes || []).length }} 个节点</span>
              </template>
              <p class="chapter-description">{{ chapter.description || '暂无章节说明' }}</p>
              <el-table :data="chapter.nodes || []" size="small" empty-text="本章尚未配置节点">
                <el-table-column label="节点" min-width="150">
                  <template slot-scope="{ row }"><strong>{{ row.name || `节点 #${row.id}` }}</strong></template>
                </el-table-column>
                <el-table-column label="地址" prop="address" min-width="220" show-overflow-tooltip />
                <el-table-column label="停留时间" width="110">
                  <template slot-scope="{ row }">{{ row.nodeTime == null ? '-' : `${row.nodeTime} 分钟` }}</template>
                </el-table-column>
                <el-table-column label="状态" width="100">
                  <template slot-scope="{ row }"><cy-status-tag :label="row.status === 1 ? '显示' : '隐藏'" :tone="row.status === 1 ? 'success' : 'neutral'" /></template>
                </el-table-column>
              </el-table>
            </el-collapse-item>
          </el-collapse>

          <el-divider content-position="left">票务与售卖</el-divider>
          <cy-empty-state
            v-if="!isAxisAvailable('tickets')"
            :title="axisCapabilityLabel('tickets')"
            :description="axisCapabilityDescription('tickets')"
            icon="el-icon-warning-outline"
          />
          <el-table v-else :data="tickets" empty-text="票务数据已读取，但尚未配置票种">
            <el-table-column label="票务" prop="name" min-width="170" />
            <el-table-column label="价格" width="120"><template slot-scope="{ row }">{{ money(row.price) }}</template></el-table-column>
            <el-table-column label="库存" width="130"><template slot-scope="{ row }">{{ ticketInventoryLabel(row) }}</template></el-table-column>
            <el-table-column label="有效期" min-width="250"><template slot-scope="{ row }">{{ dateRange(row.startTime, row.endTime) }}</template></el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="合作供给" name="cooperation">
          <div class="pane-heading">
            <div><span>Cooperation</span><h2>俱乐部承接与章节供给</h2></div>
            <cy-status-tag :label="clubFulfillmentMetricLabel" :tone="isAxisAvailable('clubFulfillment') ? 'brand' : axisCapabilityTone('clubFulfillment')" />
          </div>
          <cy-empty-state
            v-if="!isAxisAvailable('clubFulfillment')"
            :title="axisCapabilityLabel('clubFulfillment')"
            :description="axisCapabilityDescription('clubFulfillment')"
            icon="el-icon-warning-outline"
          />
          <template v-else>
            <div class="cooperation-strip">
              <div><span>待处理申请</span><strong>{{ clubFulfillment.pendingApplicationCount || 0 }}</strong></div>
              <div><span>待确认邀约</span><strong>{{ clubFulfillment.pendingInviteCount || 0 }}</strong></div>
              <div><span>已签合作</span><strong>{{ clubFulfillment.signedCooperationCount || 0 }}</strong></div>
              <div><span>带队场次</span><strong>{{ clubFulfillment.activeSessionCount || 0 }}</strong></div>
            </div>
            <el-table :data="clubFulfillment.relationships || []" empty-text="俱乐部承接数据已读取，目前没有申请、邀约或生效合作">
              <el-table-column label="俱乐部" min-width="180">
                <template slot-scope="{ row }"><strong>{{ row.clubName || `俱乐部 #${row.clubId}` }}</strong><small v-if="row.leaderName">{{ row.leaderName }}</small></template>
              </el-table-column>
              <el-table-column label="申请意向" min-width="160"><template slot-scope="{ row }">{{ applicationLabel(row) }}</template></el-table-column>
              <el-table-column label="邀约与保证金" min-width="190"><template slot-scope="{ row }">{{ inviteLabel(row) }}</template></el-table-column>
              <el-table-column label="合同" min-width="130"><template slot-scope="{ row }">{{ row.cooperationId ? `合作单 #${row.cooperationId}` : '尚未签约' }}</template></el-table-column>
              <el-table-column label="履约场次" width="110"><template slot-scope="{ row }">{{ row.sessionCount || 0 }} 场</template></el-table-column>
            </el-table>
          </template>

          <el-divider content-position="left">章节有效供给</el-divider>
          <cy-empty-state
            v-if="!isAxisAvailable('merchantSupply')"
            :title="axisCapabilityLabel('merchantSupply')"
            :description="axisCapabilityDescription('merchantSupply')"
            icon="el-icon-warning-outline"
          />
          <div v-else class="supply-grid">
            <article v-for="chapter in merchantSupply.chapters || []" :key="chapter.chapterId" class="supply-card">
              <header>
                <div><strong>{{ chapter.chapterName || `章节 #${chapter.chapterId}` }}</strong><span>{{ chapter.required ? '必选章节' : '可选章节' }}</span></div>
                <cy-status-tag :label="chapterSupplyStatusLabel(chapter)" :tone="chapterSupplyTone(chapter)" />
              </header>
              <dl>
                <div><dt>候选</dt><dd>{{ chapter.candidateCount || 0 }}</dd></div>
                <div><dt>有效供给</dt><dd>{{ chapter.activeOfferCount || 0 }}</dd></div>
                <div><dt>历史/失效</dt><dd>{{ chapter.inactiveOfferCount || 0 }}</dd></div>
                <div><dt>缺口</dt><dd>{{ chapter.missingMerchantCount == null ? '待定义' : chapter.missingMerchantCount }}</dd></div>
              </dl>
              <p>{{ offerSummary(chapter) }}</p>
            </article>
          </div>

          <el-divider content-position="left">商家候选</el-divider>
          <div v-if="isAxisAvailable('candidates')" class="cooperation-strip candidate-strip">
            <div><span>报名候选</span><strong>{{ merchantSupply.candidateCount || 0 }}</strong></div>
            <div><span>待审核</span><strong>{{ merchantSupply.pendingReviewCount || 0 }}</strong></div>
            <div><span>审核通过</span><strong>{{ merchantSupply.approvedCount || 0 }}</strong></div>
            <div><span>已调配</span><strong>{{ merchantSupply.awardedCount || 0 }}</strong></div>
          </div>
          <cy-empty-state
            v-if="!isAxisAvailable('candidates')"
            :title="axisCapabilityLabel('candidates')"
            :description="axisCapabilityDescription('candidates')"
            icon="el-icon-warning-outline"
          />
          <el-table v-else :data="candidates" empty-text="候选数据已读取，目前没有商家报名">
            <el-table-column label="商家" min-width="170"><template slot-scope="{ row }"><strong>{{ row.merchantName || '商家档案待补全' }}</strong></template></el-table-column>
            <el-table-column label="竞争位" min-width="170"><template slot-scope="{ row }">{{ row.addressName || (row.nodeId ? `节点 #${row.nodeId}` : '自由探索') }}</template></el-table-column>
            <el-table-column label="报名审核" width="120"><template slot-scope="{ row }"><cy-status-tag :label="candidateReviewLabel(row.status)" :tone="candidateReviewTone(row.status)" /></template></el-table-column>
            <el-table-column label="调配" width="110"><template slot-scope="{ row }"><cy-status-tag :label="awardLabel(row.auditStatus)" :tone="awardTone(row.auditStatus)" /></template></el-table-column>
            <el-table-column label="报名时间" width="170"><template slot-scope="{ row }">{{ formatDate(row.createTime) }}</template></el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="履约执行" name="execution">
          <div class="pane-heading"><div><span>Execution</span><h2>销售、核销与带队场次</h2></div></div>
          <cy-empty-state
            v-if="!isAxisAvailable('fulfillment')"
            :title="axisCapabilityLabel('fulfillment')"
            :description="axisCapabilityDescription('fulfillment')"
            icon="el-icon-warning-outline"
          />
          <template v-else>
            <div class="fulfillment-strip">
              <div><span>主题销售额</span><strong>{{ money(fulfillment.totalSales) }}</strong></div>
              <div><span>已核销人数</span><strong>{{ fulfillment.verifiedHeads || 0 }}</strong></div>
              <div><span>已核销销售额</span><strong>{{ money(fulfillment.verifiedSales) }}</strong></div>
              <div><span>集合到场</span><strong>{{ clubFulfillment.attendanceCount || 0 }}</strong></div>
              <div><span>未结客诉</span><strong>{{ fulfillment.openComplaintCount || 0 }}</strong></div>
            </div>
            <cy-empty-state
              v-if="!isAxisAvailable('clubFulfillment')"
              :title="axisCapabilityLabel('clubFulfillment')"
              :description="axisCapabilityDescription('clubFulfillment')"
              icon="el-icon-warning-outline"
            />
            <el-table v-else :data="clubFulfillment.sessions || []" empty-text="带队履约数据已读取，目前没有俱乐部场次">
              <el-table-column label="场次" min-width="160"><template slot-scope="{ row }">{{ row.activityName || `活动 #${row.activityId}` }}</template></el-table-column>
              <el-table-column label="集合" min-width="210"><template slot-scope="{ row }"><div>{{ formatDate(row.assemblyTime) }}</div><small>{{ row.assemblyLocation || '未填写集合地点' }}</small></template></el-table-column>
              <el-table-column label="责任领队" min-width="130"><template slot-scope="{ row }">{{ row.leaderDisplayName || '领队待确认' }}</template></el-table-column>
              <el-table-column label="进度" width="110"><template slot-scope="{ row }"><cy-status-tag :label="leadStatusLabel(row.status)" :tone="leadStatusTone(row.status)" /></template></el-table-column>
              <el-table-column label="当前章节" min-width="120"><template slot-scope="{ row }">{{ row.currentChapterId ? `章节 #${row.currentChapterId}` : '尚未进入章节' }}</template></el-table-column>
            </el-table>
          </template>
        </el-tab-pane>

        <el-tab-pane label="复盘结算" name="review">
          <div class="pane-heading"><div><span>Review & settlement</span><h2>结算分项与运营复盘</h2></div><cy-status-tag :label="settlementStatusOverviewLabel" :tone="isAxisAvailable('settlement') ? 'brand' : axisCapabilityTone('settlement')" /></div>
          <cy-empty-state
            v-if="!isAxisAvailable('settlement')"
            :title="axisCapabilityLabel('settlement')"
            :description="axisCapabilityDescription('settlement')"
            icon="el-icon-warning-outline"
          />
          <el-table v-else :data="settlements" empty-text="结算数据已读取，尚未生成结算分项">
            <el-table-column label="分项" min-width="180"><template slot-scope="{ row }">{{ lineTypeLabel(row.lineType) }}</template></el-table-column>
            <el-table-column label="状态" width="110"><template slot-scope="{ row }"><cy-status-tag :label="settlementStatusLabel(row.status)" :tone="settlementStatusTone(row.status)" /></template></el-table-column>
            <el-table-column label="可打款时间" width="180"><template slot-scope="{ row }">{{ formatDate(row.payableTime) }}</template></el-table-column>
            <el-table-column label="打款时间" width="180"><template slot-scope="{ row }">{{ formatDate(row.payoutTime) }}</template></el-table-column>
          </el-table>
          <el-divider content-position="left">其他已生效合作</el-divider>
          <el-table v-if="isAxisAvailable('cooperation')" :data="cooperations" empty-text="合作数据已读取，当前没有其他生效合作单">
            <el-table-column label="合作单" width="100"><template slot-scope="{ row }">#{{ row.id }}</template></el-table-column>
            <el-table-column label="合作方" min-width="150"><template slot-scope="{ row }">{{ counterpartyLabel(row) }}</template></el-table-column>
            <el-table-column label="冻结条款" min-width="180"><template slot-scope="{ row }">{{ termsLabel(row) }}</template></el-table-column>
            <el-table-column label="状态" width="100"><template slot-scope="{ row }"><cy-status-tag :label="cooperationStatusLabel(row.status)" :tone="cooperationStatusTone(row.status)" /></template></el-table-column>
          </el-table>
          <cy-empty-state
            v-else
            :title="axisCapabilityLabel('cooperation')"
            :description="axisCapabilityDescription('cooperation')"
            icon="el-icon-warning-outline"
          />
        </el-tab-pane>
      </el-tabs>
    </section>
  </main>
</template>

<script>
import { getTopicWorkspace, getTopicOperations } from '@/api/business/topic'
import { checkPermi } from '@/utils/permission'
import CyDataTable from '@/components/CyDataTable'
import CyEntityHeader from '@/components/CyEntityHeader'
import CyPageHeader from '@/components/CyPageHeader'
import CyMetricCard from '@/components/CyMetricCard'
import CyEmptyState from '@/components/CyEmptyState'
import CyResourceState from '@/components/CyResourceState'
import CyStatusTag from '@/components/CyStatusTag'

export default {
  name: 'TopicWorkspace',
  components: { CyDataTable, CyEntityHeader, CyPageHeader, CyMetricCard, CyEmptyState, CyResourceState, CyStatusTag },
  data() {
    return {
      activeTab: 'overview',
      loading: false,
      error: '',
      operationsError: '',
      updatedAt: '',
      openChapters: [],
      workspace: {
        topic: {}, publisher: {}, officialActivityRelation: {}, operation: {}, governance: {}, merchantSupply: {},
        clubFulfillment: {}, chapters: [], tickets: [], candidates: [], cooperations: [],
        settlements: [], fulfillment: {}, capabilities: {}
      },
      operations: {
        available: true,
        unavailableReasonCode: '',
        topic: {},
        items: [],
        summary: { actionableCount: 0, waitingExternalCount: 0, blockedCount: 0, readOnlyCount: 0 }
      }
    }
  },
  computed: {
    topic() { return this.workspace.topic || {} },
    publisher() { return this.workspace.publisher || {} },
    officialActivityRelation() { return this.workspace.officialActivityRelation || {} },
    operation() { return this.workspace.operation || {} },
    responsibility() { return this.operation.responsibility || {} },
    governance() { return this.workspace.governance || {} },
    merchantSupply() { return this.workspace.merchantSupply || {} },
    clubFulfillment() { return this.workspace.clubFulfillment || {} },
    capabilities() { return this.workspace.capabilities || {} },
    chapters() { return this.workspace.chapters || [] },
    tickets() { return this.workspace.tickets || [] },
    candidates() { return this.workspace.candidates || [] },
    cooperations() { return this.workspace.cooperations || [] },
    settlements() { return this.workspace.settlements || [] },
    operationItems() { return this.operations.items || [] },
    operationSummary() {
      return Object.assign({ actionableCount: 0, waitingExternalCount: 0, blockedCount: 0, readOnlyCount: 0 }, this.operations.summary || {})
    },
    operationsUnavailable() { return this.operations.available === false },
    operationFacts() {
      return [
        { label: '可处理', value: String(this.operationSummary.actionableCount) },
        { label: '等待外部', value: String(this.operationSummary.waitingExternalCount) },
        { label: '对象授权待补', value: String(this.operationSummary.blockedCount) },
        { label: '只读观察', value: String(this.operationSummary.readOnlyCount) }
      ]
    },
    operationColumns() {
      return [
        { key: 'source', label: '来源', minWidth: 190, locked: true },
        { key: 'displayState', label: '当前状态', minWidth: 150 },
        { key: 'actionability', label: '可处理性', minWidth: 115 },
        { key: 'latestAt', label: '最近变化', width: 170 },
        { key: 'actions', label: '动作边界', minWidth: 190, locked: true, fixed: 'right' }
      ]
    },
    operationsScopeMessage() {
      const snapshotTopic = this.operations.topic || {}
      if (this.operations.available === false) {
        return '当前尚未配置可查询、可撤销、可审计的对象级主题受托范围（后台账号→主题）；待办快照保持关闭，既有只读工作台不受影响。'
      }
      if (snapshotTopic.contentOwnershipState === 'UNRESOLVED') {
        return '主题处于历史待归属队列；未建立可撤销、可审计的受托范围前，所有动作保持只读。'
      }
      if (snapshotTopic.readOnlyReasonCode) {
        return '主题受托范围尚未满足写入条件；当前仅展示只读事实，不提供任何历史页深链。'
      }
      return '安全快照只聚合来源状态；领域写命令仍由各自服务在对象级授权成立后再次裁决。'
    },
    fulfillment() { return this.workspace.fulfillment || {} },
    nodeCount() { return this.chapters.reduce((sum, chapter) => sum + (chapter.nodes || []).length, 0) },
    isHistoricalTopic() { return this.publisher.historicalPending || !this.publisher.entityType },
    isPlazaEntry() { return !this.isHistoricalTopic && ['CLUB', 'MEMBER'].includes(this.publisher.entityType) },
    isOfficialTopic() { return !this.isHistoricalTopic && this.publisher.entityType === 'PLATFORM' },
    lifecycleLabel() {
      return ['草稿', '招募中', '待定价', '售票中', '进行中', '结算中', '已关闭'][this.operation.lifecycle] || '状态未知'
    },
    auditLabel() {
      return ['待审核', '审核通过', '审核驳回', '升级处理'][this.governance.checkStatus] || '状态未知'
    },
    userStatusLabel() {
      return ({ 0: '用户端未上架', 1: '用户端已上架' })[this.governance.userStatus] || '用户端状态未定义'
    },
    merchantPoolLabel() {
      return ({ 0: '商家池关', 1: '商家池开' })[this.operation.merchantPoolStatus] || '商家池未定义'
    },
    clubPoolLabel() {
      return ({ 0: '俱乐部池关', 1: '俱乐部池开' })[this.operation.clubPoolStatus] || '俱乐部池未定义'
    },
    recruitmentLabel() { return `${this.merchantPoolLabel} · ${this.clubPoolLabel}` },
    publisherLabel() {
      if (this.publisher.historicalPending || !this.publisher.entityType) return '历史待归属'
      const type = ({ PLATFORM: '官方', CLUB: '俱乐部', MEMBER: '会员' })[this.publisher.entityType] || '主体异常'
      return this.publisher.displayName ? `${type} · ${this.publisher.displayName}` : type
    },
    publisherDescription() {
      if (this.publisher.historicalPending || !this.publisher.entityType) {
        return '未找到 CONTENT 内容槽，需人工确认归属后才能进入来源队列。'
      }
      if (this.publisher.displayName) return `CONTENT 槽：${this.publisher.displayName}。`
      return this.publisher.entityId == null
        ? 'CONTENT 槽：平台主体。'
        : `CONTENT 槽：${this.publisher.entityType} #${this.publisher.entityId}。`
    },
    officialActivityRelationLabel() {
      return this.officialActivityRelation.message || '未关联官方活动'
    },
    officialActivityRelationDescription() {
      if (this.officialActivityRelation.state === 'UNLINKED' || !this.officialActivityRelation.state) {
        return '当前没有可验证的官方活动关系；执行场次和主题本身不会被冒充为官方活动。'
      }
      return '官方活动关系待读取。'
    },
    organizerLabel() {
      return this.responsibility.organizerLabel || '责任待确认'
    },
    organizerDescription() {
      return ({
        CONTENT_SUBJECT_DEFAULT: '尚未形成唯一带队责任时，运营主办方按内容主体显示。',
        CLUB_LEADING: '唯一已签俱乐部已有带队事实，运营主办方按其显示。',
        RESPONSIBILITY_UNCONFIRMED: '存在多方或尚未明确带队责任，不能静默猜测主办方。',
        CONTENT_SUBJECT_UNCONFIRMED: '历史主题尚未确认内容主体，不能判断运营主办方。'
      })[this.responsibility.organizerState] || '后端未确认责任归属。'
    },
    actualFulfillmentLabel() {
      const fulfillers = this.responsibility.actualFulfillers || []
      return fulfillers.length ? `${fulfillers.length} 方` : '尚无已确认履约方'
    },
    actualFulfillmentDescription() {
      const fulfillers = this.responsibility.actualFulfillers || []
      return fulfillers.length ? fulfillers.join('；') : '公开池开关和候选报名不等于实际履约。'
    },
    depositSummaryLabel() {
      const summary = this.clubFulfillment.depositSummary || {}
      if (summary.payableCount) return `${summary.payableCount} 笔待缴`
      if (summary.payingCount) return `${summary.payingCount} 笔支付中`
      if (summary.awaitingPricingCount) return `${summary.awaitingPricingCount} 笔待锁价`
      if (summary.frozenCount) return `${summary.frozenCount} 笔已冻结`
      if (summary.deductedCount) return `${summary.deductedCount} 笔已抵扣`
      if (summary.refundedCount) return `${summary.refundedCount} 笔已退还`
      return summary.unknownCount ? '状态待确认' : '暂无已接受俱乐部合作'
    },
    depositSummaryDescription() {
      const summary = this.clubFulfillment.depositSummary || {}
      if (summary.payableCount || summary.payingCount || summary.awaitingPricingCount) {
        return '保证金与邀请接受分开呈现；待锁价不等于已缴。'
      }
      return '仅统计已接受俱乐部合作的保证金状态。'
    },
    productTypeLabel() {
      return ({ 1: '线性城市定向', 2: '主题内自由探索' })[this.operation.productType] || '玩法待定义'
    },
    readinessLabel() {
      return ({ READY: '可开跑', AT_RISK: '存在风险', BLOCKED: '当前阻塞' })[this.operation.runReadiness] || '待判断'
    },
    readinessTone() {
      return ({ READY: 'success', AT_RISK: 'warning', BLOCKED: 'danger' })[this.operation.runReadiness] || 'neutral'
    },
    readinessDescription() {
      const blockers = this.operation.runBlockers || []
      if (!blockers.length) return '内容、供给与执行前置条件已满足。'
      return blockers.map(this.runBlockerLabel).join('、')
    },
    nextActionLabel() {
      return ({
        EDIT_CONTENT: '补齐内容',
        HANDLE_RECRUITMENT: '处理招募',
        RESOLVE_SUPPLY_GAPS: '补齐供给缺口',
        LOCK_LINEUP: '锁定阵容',
        MONITOR_FULFILLMENT: '跟进履约',
        SETTLE_AND_REVIEW: '结算并复盘',
        VIEW_REVIEW: '查看复盘'
      })[this.operation.nextAction] || '等待运营判断'
    },
    editPolicyLabel() {
      return ({ FULL: '可完整编辑', CONTENT_CORRECTION_ONLY: '仅内容勘误', LOCKED: '已锁定' })[this.operation.editPolicy] || '已锁定'
    },
    editPolicyDescription() {
      return ({
        FULL: '当前阶段可进入主题编辑页修改内容与运营日期。',
        CONTENT_CORRECTION_ONLY: '阵容已锁，普通编辑只允许内容勘误；日期、招募与推荐位已受服务端限制。',
        LOCKED: '结算或关闭阶段不允许通过通用编辑修改主题。'
      })[this.operation.editPolicy] || '服务端未确认编辑边界，默认不开放编辑。'
    },
    canOpenTopicEditor() {
      return this.isOfficialTopic
        && this.operation.editPolicy !== 'LOCKED'
        && checkPermi(['business:topic:edit'])
    },
    editorActionLabel() {
      return this.operation.editPolicy === 'CONTENT_CORRECTION_ONLY' ? '内容勘误' : '编辑主题'
    },
    structureDescription() {
      return this.axisCapabilityDescription('structure', '章节与城市节点已读取')
    },
    merchantSupplyDescription() {
      if (!this.isAxisAvailable('merchantSupply')) return this.axisCapabilityDescription('merchantSupply')
      const missing = this.merchantSupply.missingRequiredChapterCount || 0
      return missing ? `${missing} 个必选章节尚未达到目标商家数` : '必选章节当前均达到目标商家数'
    },
    clubFulfillmentDescription() {
      if (!this.isAxisAvailable('clubFulfillment')) return this.axisCapabilityDescription('clubFulfillment')
      return `${this.clubFulfillmentStateLabel} · ${this.clubFulfillment.pendingApplicationCount || 0} 个待处理申请，${this.clubFulfillment.pendingInviteCount || 0} 个待确认邀约`
    },
    clubFulfillmentStateLabel() {
      return ({
        NOT_REQUIRED: '不需要俱乐部承接',
        APPLYING: '申请中',
        INVITED: '待确认邀约',
        SIGNED: '已签约',
        LEADING: '带队中',
        UNCONFIRMED: '待明确'
      })[this.clubFulfillment.state] || '承接状态待确认'
    },
    clubFulfillmentMetricLabel() {
      return this.isAxisAvailable('clubFulfillment')
        ? `${this.clubFulfillmentStateLabel} · ${this.clubFulfillment.relationships ? this.clubFulfillment.relationships.length : 0} 个关系主体`
        : this.axisCapabilityLabel('clubFulfillment')
    },
    fulfillmentMetricValue() {
      return this.isAxisAvailable('fulfillment') ? this.money(this.fulfillment.verifiedSales) : '—'
    },
    fulfillmentDescription() {
      return this.axisCapabilityDescription('fulfillment', `销售、核销与 ${this.fulfillment.openComplaintCount || 0} 条未结客诉已读取`)
    },
    settlementStatusOverviewLabel() {
      if (!this.isAxisAvailable('settlement')) return this.axisCapabilityLabel('settlement')
      return this.topic.isSettlement === 1 ? '已结算' : '待结算'
    }
  },
  created() {
    if (this.$route.query.id) this.loadWorkspace()
    else this.error = '缺少主题 ID，无法读取工作台。'
  },
  methods: {
    loadWorkspace() {
      if (this.loading) return
      this.loading = true
      this.error = ''
      this.operationsError = ''
      const topicId = this.$route.query.id
      return Promise.all([
        getTopicWorkspace(topicId),
        getTopicOperations(topicId).catch(() => {
          this.operationsError = '待处理快照当前不可用；主题结构和既有只读数据仍可继续查看。'
          return null
        })
      ])
        .then(([workspaceResponse, operationsResponse]) => {
          this.workspace = workspaceResponse.data || this.workspace
          if (operationsResponse) {
            this.operations = operationsResponse.data || this.operations
            this.operationsError = ''
          }
          this.openChapters = this.chapters.length ? [this.chapters[0].id] : []
          this.updatedAt = this.parseTime(new Date(), '{y}-{m}-{d} {h}:{i}')
        })
        .catch(() => { this.error = '主题工作台读取失败，请检查网络或权限后重试。' })
        .finally(() => { this.loading = false })
    },
    goBack() {
      if (this.isHistoricalTopic) {
        this.$router.push({ name: 'OfficialTopic', query: { scope: 'historical' } })
        return
      }
      this.$router.push({
        name: this.isPlazaEntry ? 'TopicPlaza' : 'OfficialTopic',
        query: { scope: this.isPlazaEntry ? 'plaza' : 'official' }
      })
    },
    openLegacyDetail() {
      if (!this.canOpenTopicEditor) return
      this.$router.push({ path: '/topic/detail', query: { id: this.topic.id } })
    },
    operationSourceLabel(sourceRef) {
      if (!sourceRef) return '来源待核验'
      const type = ({
        MERCHANT_REGISTRATION: '商家报名',
        MERCHANT_CHAPTER_OFFER: '章节供给',
        CLUB_APPLICATION: '俱乐部承接申请',
        CLUB_INVITE: '俱乐部邀约',
        CLUB_COOPERATION: '俱乐部合作单'
      })[sourceRef.type] || '业务来源'
      return `${type} #${sourceRef.id || '-'}`
    },
    operationTone(actionability) {
      return ({ ACTIONABLE: 'success', WAITING_EXTERNAL: 'warning', BLOCKED: 'danger', READ_ONLY: 'neutral' })[actionability] || 'neutral'
    },
    operationActionabilityLabel(actionability) {
      return ({ ACTIONABLE: '可处理', WAITING_EXTERNAL: '等待外部', BLOCKED: '已阻断', READ_ONLY: '只读' })[actionability] || '待核验'
    },
    operationActionLabel(code) {
      return ({
        VIEW_EVIDENCE: '查看证据',
        OPEN_LEGACY_DISPATCH: '调配入口',
        OPEN_LEGACY_OFFER: '供给入口'
      })[code] || '受限动作'
    },
    operationDisabledReason(code) {
      return ({
        BLOCKED_OBJECT_AUTHORIZATION_UNAVAILABLE: '尚未建立后台账号到主题的对象级受托范围，动作保持禁用。',
        BLOCKED_P1_COMMERCIAL_GATES: '商业邀约与合作写入属于 P1，前置门槛未满足，动作保持禁用。'
      })[code] || '当前动作尚未满足可审计的执行条件。'
    },
    axisCapability(axis) {
      const capability = this.capabilities[axis]
      return capability && capability.state
        ? capability
        : { state: 'LOAD_FAILED', reasonCode: 'CAPABILITY_CONTRACT_MISSING' }
    },
    isAxisAvailable(axis) {
      return this.axisCapability(axis).state === 'AVAILABLE'
    },
    axisCapabilityLabel(axis) {
      return ({
        AVAILABLE: '已接通',
        NOT_APPLICABLE: '不适用',
        NOT_GRANTED: '无查看权限',
        NOT_INTEGRATED: '暂未接入',
        LOAD_FAILED: '读取失败'
      })[this.axisCapability(axis).state] || '读取失败'
    },
    axisCapabilityTone(axis) {
      return ({
        AVAILABLE: 'success',
        NOT_APPLICABLE: 'neutral',
        NOT_GRANTED: 'warning',
        NOT_INTEGRATED: 'neutral',
        LOAD_FAILED: 'danger'
      })[this.axisCapability(axis).state] || 'danger'
    },
    axisCapabilityDescription(axis, availableDescription) {
      const capability = this.axisCapability(axis)
      if (capability.state === 'AVAILABLE') {
        if (!capability.asOf) return availableDescription || '数据已接通。'
        return `${availableDescription || '数据已接通'} · 观察于 ${this.formatDate(capability.asOf)}`
      }
      if (capability.state === 'NOT_APPLICABLE') return '该主题不适用此状态轴。'
      if (capability.state === 'NOT_GRANTED') return '当前角色无查看权限。'
      if (capability.state === 'NOT_INTEGRATED') return '该数据源尚未接入工作台，不能用空值代替。'
      return capability.reasonCode === 'CAPABILITY_CONTRACT_MISSING'
        ? '后端未返回能力合同，不能安全展示数据。'
        : '本次读取失败，请重试。'
    },
    runBlockerLabel(blocker) {
      return ({
        LIFECYCLE_NOT_READY: '主题阶段尚未满足开跑条件',
        LIFECYCLE_TERMINAL: '主题已结算或关闭，不能开跑',
        CONTENT_STRUCTURE_MISSING: '内容结构或城市节点不完整',
        TICKET_CONFIGURATION_MISSING: '票务尚未配置',
        SELF_PLAY_PASS_CONFIGURATION_MISSING: '自玩通行证价格待配置',
        REQUIRED_CHAPTER_SUPPLY_MISSING: '必选章节存在商家供给缺口',
        CLUB_INVITE_PENDING: '存在待确认的俱乐部邀约',
        CLUB_FULFILLMENT_UNCONFIRMED: '公开俱乐部承接尚未形成生效合作'
      })[blocker] || '存在待确认的运营条件'
    },
    ticketInventoryLabel(ticket) {
      if (ticket.totalInventory == null || ticket.remainingInventory == null) return '不限量'
      return `${ticket.remainingInventory} / ${ticket.totalInventory}`
    },
    isKnownNumeric(value) {
      if (value === null || value === undefined || typeof value === 'boolean') return false
      if (typeof value === 'string' && !value.trim()) return false
      return Number.isFinite(Number(value))
    },
    money(value) {
      return this.isKnownNumeric(value) ? `¥${Number(value).toFixed(2)}` : '待核验'
    },
    formatDate(value) { return value ? String(value).replace('T', ' ') : '-' },
    dateRange(start, end) { return `${this.formatDate(start)} 至 ${this.formatDate(end)}` },
    candidateReviewLabel(status) {
      if (status === 0) return '待审核'
      if (status === 1) return '已通过'
      if (status === 2) return '已驳回'
      if (status === 3) return '调配驳回'
      return status == null ? '审核状态待确认' : '审核状态异常'
    },
    candidateReviewTone(status) {
      if (status === 1) return 'success'
      if (status === 2 || status === 3) return 'danger'
      return status === 0 ? 'warning' : 'neutral'
    },
    awardLabel(status) {
      if (status === 0) return '待调配'
      if (status === 1) return '已中标'
      if (status === 2) return '未中标'
      return status == null ? '调配状态待确认' : '调配状态异常'
    },
    awardTone(status) {
      if (status === 1) return 'success'
      if (status === 2) return 'neutral'
      return status === 0 ? 'warning' : 'neutral'
    },
    applicationLabel(row) {
      if (row.applicationStatus === 0) return '待处理申请'
      if (row.applicationStatus === 1) return '申请已婉拒'
      if (row.applicationStatus === 2) return '申请已撤回'
      return row.applicationStatus == null ? '尚未申请' : '申请状态待确认'
    },
    inviteLabel(row) {
      const invite = ({ 0: '待确认邀约', 1: '邀约已接受', 2: '邀约已拒绝', 3: '邀约已取消', 4: '已顶替', 5: '邀约已过期' })[row.inviteStatus]
        || (row.inviteStatus == null ? '尚未邀约' : '邀约状态待确认')
      if (row.inviteStatus === 1 && (row.depositStatus == null || row.depositStatus === 0)) {
        return `${invite} · 待锁价`
      }
      if (row.depositStatus == null) return invite
      return `${invite} · ${this.depositLabel(row.depositStatus)}`
    },
    depositLabel(status) {
      return ({ 1: '保证金支付中', 2: '保证金已冻结', 3: '保证金已扣', 4: '保证金已退', 5: '保证金待缴' })[status] || '保证金状态待确认'
    },
    chapterSupplyStatusLabel(chapter) {
      if (!chapter.required) return '可选供给'
      if (chapter.activeOfferCount === 0) return '缺有效供给'
      if (chapter.missingMerchantCount > 0) return '仍有缺口'
      return '供给就绪'
    },
    chapterSupplyTone(chapter) {
      if (!chapter.required) return 'neutral'
      if (chapter.activeOfferCount === 0) return 'danger'
      return chapter.missingMerchantCount > 0 ? 'warning' : 'success'
    },
    offerSummary(chapter) {
      const offers = chapter.offers || []
      if (!offers.length) return '当前没有已登记的章节供给。'
      const active = offers.filter(item => item.active)
      const descriptions = active.map(item => {
        const merchant = item.merchantName || '商家档案待补全'
        if (!item.cooperationId) return `${merchant}（供给已生效，合作单待关联）`
        return `${merchant}（合作单 #${item.cooperationId} · 团核销 ${item.verifiedGroupCount || 0}）`
      })
      return descriptions.length ? `有效：${descriptions.join('；')}` : '存在历史供给，但当前均已失效。'
    },
    leadStatusLabel(status) {
      if (status === 1) return '集合中'
      if (status === 2) return '行进中'
      if (status === 3) return '已结算'
      return status == null ? '状态待确认' : '状态异常'
    },
    leadStatusTone(status) {
      if (status === 3) return 'success'
      if (status === 2) return 'brand'
      return status === 1 ? 'neutral' : 'warning'
    },
    counterpartyLabel(row) {
      const typeLabel = ({ club: '俱乐部', merchant: '商家' })[row.counterpartyType]
      if (!typeLabel) return row.counterpartyId == null ? '未知合作方' : `未知合作方 #${row.counterpartyId}`
      return row.counterpartyId == null ? `${typeLabel} #待确认` : `${typeLabel} #${row.counterpartyId}`
    },
    termsLabel(row) {
      if (row.shareMode === 0) return '引流合作'
      if (row.shareMode === 1) return this.isKnownNumeric(row.shareRate) ? `核销销售额 × ${row.shareRate}%` : '条款待确认'
      if (row.shareMode === 2) return this.isKnownNumeric(row.fixedFee) ? `${this.money(row.fixedFee)} / 核销人头` : '条款待确认'
      return row.shareMode == null ? '条款待确认' : '条款状态异常'
    },
    cooperationStatusLabel(status) {
      if (status === 0) return '生效'
      if (status === 1) return '已结束'
      return '状态待确认'
    },
    cooperationStatusTone(status) {
      if (status === 0) return 'success'
      if (status === 1) return 'neutral'
      return 'warning'
    },
    lineTypeLabel(type) {
      return ({ PLATFORM: '平台服务费', INITIATOR_GUARANTEE: '发起方保底', INITIATOR_MARGIN: '发起方毛利', LEAD_FEE: '带队服务费', MERCHANT_VERIFY: '商家核销分润', DEPOSIT_DEDUCTION: '保证金抵扣' })[type] || type || '未分类'
    },
    settlementStatusLabel(status) {
      if (status === 0) return '待打款'
      if (status === 1) return '已打款'
      if (status === 2) return '已作废'
      return status == null ? '状态待确认' : '状态异常'
    },
    settlementStatusTone(status) {
      if (status === 1) return 'success'
      if (status === 2) return 'neutral'
      return status === 0 ? 'warning' : 'neutral'
    }
  }
}
</script>

<style lang="scss" scoped>
.topic-workspace { max-width: 1680px; margin: 0 auto; }
.topic-workspace__alert { margin: 0 0 var(--cy-space-4); }
.topic-workspace__identity { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); gap: var(--cy-space-3); margin-bottom: var(--cy-space-3); }
.topic-workspace__summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--cy-space-3); margin-bottom: var(--cy-space-3); }
.topic-workspace__identity article,
.overview-card,
.supply-card { min-height: 118px; padding: var(--cy-space-3-5); border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-subtle); }
.topic-workspace__identity span,
.overview-card span,
.supply-card span,
.topic-workspace__next-action > span { color: var(--cy-color-text-tertiary); font-size: 12px; font-weight: 700; }
.topic-workspace__identity strong,
.overview-card strong { display: block; margin-top: var(--cy-space-1-5); color: var(--cy-color-text-primary); font-size: 18px; }
.topic-workspace__identity p,
.overview-card p { margin: var(--cy-space-2) 0 0; color: var(--cy-color-text-secondary); font-size: 12px; line-height: 1.55; }
.topic-workspace__decision { display: grid; grid-template-columns: minmax(360px, 1.6fr) minmax(220px, .7fr) minmax(320px, 1fr); gap: 0; margin: 0 0 var(--cy-space-3-5); overflow: hidden; border: 1px solid #D8E2FF; border-radius: var(--cy-radius-card); background: linear-gradient(100deg, #F7F9FF 0%, var(--cy-color-bg-surface) 70%); box-shadow: var(--cy-shadow-card); }
.topic-workspace__decision-main,
.topic-workspace__next-action,
.topic-workspace__dates { padding: var(--cy-space-3-5) var(--cy-space-5); }
.topic-workspace__next-action,
.topic-workspace__dates { border-left: 1px solid #E1E7F7; }
.topic-workspace__eyebrow,
.pane-heading span { color: var(--cy-color-brand); font-size: 11px; font-weight: 800; letter-spacing: .12em; text-transform: uppercase; }
.topic-workspace__decision-title { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--cy-space-4); margin-top: 7px; }
.topic-workspace__decision-title strong { color: var(--cy-color-text-primary); font-size: 24px; line-height: 1.1; }
.topic-workspace__decision-title p,
.topic-workspace__next-action p { margin: var(--cy-space-2) 0 0; color: var(--cy-color-text-secondary); font-size: 12px; line-height: 1.55; }
.topic-workspace__relay { display: flex; align-items: center; margin-top: 17px; color: var(--cy-color-text-tertiary); font-size: 12px; font-weight: 700; }
.topic-workspace__relay span { position: relative; z-index: 1; padding: var(--cy-space-1) var(--cy-space-2); border-radius: 999px; }
.topic-workspace__relay span.is-current { color: var(--cy-color-text-inverse); background: var(--cy-color-brand); }
.topic-workspace__relay i { width: var(--cy-space-6); height: 1px; background: #B9C7E8; }
.topic-workspace__next-action strong { display: block; margin-top: var(--cy-space-1-5); color: var(--cy-color-brand); font-size: 20px; }
.topic-workspace__dates { display: grid; gap: 11px; margin: 0; }
.topic-workspace__dates div { display: grid; grid-template-columns: 74px 1fr; gap: var(--cy-space-2); align-items: baseline; }
.topic-workspace__dates dt { color: var(--cy-color-text-tertiary); font-size: 12px; }
.topic-workspace__dates dd { margin: 0; color: var(--cy-color-text-primary); font-size: 12px; font-variant-numeric: tabular-nums; }
.topic-workspace__summary { margin-bottom: var(--cy-space-3-5); }
.topic-workspace__body { min-height: 500px; padding: var(--cy-space-2) var(--cy-space-4-5) var(--cy-space-6); border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-surface); box-shadow: var(--cy-shadow-card); }
.topic-workspace__operations-alert { margin-top: var(--cy-space-5); }
.topic-workspace__tabs ::v-deep .el-tabs__header { margin-bottom: var(--cy-space-4-5); }
.topic-workspace__tabs ::v-deep .el-tabs__item { height: 54px; color: var(--cy-color-text-secondary); font-weight: 700; line-height: 54px; }
.topic-workspace__tabs ::v-deep .el-tabs__item.is-active { color: var(--cy-color-brand); }
.topic-workspace__tabs ::v-deep .el-tabs__active-bar { background: var(--cy-color-brand); }
.pane-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--cy-space-4); margin-bottom: var(--cy-space-3-5); }
.pane-heading h2 { margin: var(--cy-legacy-5) 0 0; color: var(--cy-color-text-primary); font-size: 19px; }
.topic-workspace__operations { padding-top: var(--cy-space-5); }
.topic-workspace__operations-note { margin: 0 0 var(--cy-space-4); padding: var(--cy-space-3) var(--cy-space-2-5); border-left: 3px solid var(--cy-color-brand); border-radius: 0 var(--cy-radius-control) var(--cy-radius-control) 0; background: var(--cy-color-bg-subtle); color: var(--cy-color-text-secondary); font-size: 13px; line-height: 1.6; }
.topic-workspace__actionability { color: var(--cy-color-text-secondary); font-size: 13px; }
.topic-workspace__disabled-actions { display: inline-flex; flex-wrap: wrap; gap: var(--cy-space-1); }
.topic-workspace__disabled-actions .el-button { margin: 0; }
.overview-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--cy-space-3); }
.overview-card--next { border-color: #B8C7FB; background: #F4F6FF; }
.overview-governance { margin-top: var(--cy-space-4-5); padding-top: var(--cy-space-4-5); border-top: 1px solid var(--cy-color-border-subtle); }
.overview-governance.is-plaza { border-top-color: #AFBFF6; }
.status-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--cy-space-3); }
.status-grid article { min-height: 116px; padding: var(--cy-space-4); border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-subtle); }
.status-grid span,
.fulfillment-strip span,
.cooperation-strip span { color: var(--cy-color-text-tertiary); font-size: 12px; font-weight: 700; }
.status-grid strong { display: block; margin-top: var(--cy-space-1-5); color: var(--cy-color-text-primary); font-size: 18px; }
.status-grid p { margin: var(--cy-space-2) 0 0; color: var(--cy-color-text-secondary); font-size: 12px; line-height: 1.55; }
.content-chapters { margin-bottom: var(--cy-space-1); }
.chapter-count { margin-left: var(--cy-space-3); color: var(--cy-color-text-tertiary); font-size: 12px; font-weight: 500; }
.chapter-description { margin: 0 0 var(--cy-space-2-5); color: var(--cy-color-text-secondary); font-size: 13px; }
.cooperation-strip,
.fulfillment-strip { display: grid; margin-bottom: var(--cy-space-3-5); border: 1px solid #DDE4F6; border-radius: var(--cy-radius-card); background: #F8FAFF; }
.cooperation-strip { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.fulfillment-strip { grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); }
.cooperation-strip div,
.fulfillment-strip div { padding: var(--cy-space-4) var(--cy-space-3-5); border-right: 1px solid #DDE4F6; }
.cooperation-strip div:last-child,
.fulfillment-strip div:last-child { border-right: 0; }
.cooperation-strip strong,
.fulfillment-strip strong { display: block; margin-top: var(--cy-space-0-5); color: var(--cy-color-brand); font-size: 22px; font-variant-numeric: tabular-nums; }
.supply-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--cy-space-3); }
.supply-card { min-height: 180px; }
.supply-card header { display: flex; justify-content: space-between; gap: var(--cy-space-3); }
.supply-card header strong { display: block; color: var(--cy-color-text-primary); font-size: 15px; }
.supply-card header span { display: block; margin-top: var(--cy-legacy-5); }
.supply-card dl { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); margin: var(--cy-space-3-5) 0 0; }
.supply-card dt { color: var(--cy-color-text-tertiary); font-size: 11px; }
.supply-card dd { margin: var(--cy-legacy-5) 0 0; color: var(--cy-color-text-primary); font-size: 15px; font-weight: 700; font-variant-numeric: tabular-nums; }
.supply-card p { margin: var(--cy-space-2-5) 0 0; color: var(--cy-color-text-secondary); font-size: 12px; line-height: 1.5; }
.el-table small { display: block; margin-top: var(--cy-space-1); color: var(--cy-color-text-tertiary); font-size: 12px; }
@media (max-width: 1250px) {
  .topic-workspace__decision { grid-template-columns: 1fr 1fr; }
  .topic-workspace__dates { grid-column: 1 / -1; grid-template-columns: repeat(3, minmax(0, 1fr)); border-top: 1px solid #E1E7F7; border-left: 0; }
  .overview-grid, .status-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .supply-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 960px) {
  .topic-workspace__identity, .topic-workspace__summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .topic-workspace__decision { grid-template-columns: 1fr; }
  .topic-workspace__next-action, .topic-workspace__dates { border-top: 1px solid #E1E7F7; border-left: 0; }
  .topic-workspace__dates { grid-template-columns: 1fr; }
}
@media (max-width: 720px) {
  .topic-workspace__identity, .topic-workspace__summary, .overview-grid, .status-grid, .supply-grid, .cooperation-strip, .fulfillment-strip { grid-template-columns: 1fr; }
  .topic-workspace__body { padding: var(--cy-space-2) var(--cy-space-2-5) var(--cy-space-5); }
  .cooperation-strip div, .fulfillment-strip div { border-right: 0; border-bottom: 1px solid #DDE4F6; }
}
</style>
