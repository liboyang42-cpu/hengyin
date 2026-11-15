<template>
  <div class="app-container cy-citygame-page">
    <cy-page-header
      title="CITY 棋盘与领地治理"
      description="配置城瘾大富翁棋盘轨道（关联漫游 POI 地块）、加权骰子、过路费与挑战题，执行商家审批，并查看统一骰子审计与领地反作弊状态。"
    />

    <el-tabs v-model="activeTab" type="border-card" @tab-click="onTabChange">
      <!-- 1. 棋盘编排与发布 -->
      <el-tab-pane label="棋盘编排与发布" name="boards">
        <div class="cy-toolbar">
          <el-button type="primary" size="small" icon="el-icon-plus" @click="startNewBoard">新建棋盘草稿</el-button>
          <el-button size="small" icon="el-icon-refresh" @click="loadBoards">刷新列表</el-button>
        </div>

        <div class="cy-table-scroll">
          <el-table v-loading="boardsLoading" :data="boards" border size="small" aria-label="CITY棋盘列表">
            <el-table-column label="Board ID" prop="boardId" min-width="140" />
            <el-table-column label="标题" prop="title" min-width="150" />
            <el-table-column label="区域 / 赛季" min-width="150">
              <template slot-scope="s">{{ s.row.regionId }} / {{ s.row.seasonId }}</template>
            </el-table-column>
            <el-table-column label="生命周期" prop="lifecycle" width="110" align="center">
              <template slot-scope="s">
                <el-tag :type="lifecycleTagType(s.row.lifecycle)" size="mini">{{ s.row.lifecycle }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="发布 ID / Hash" min-width="170">
              <template slot-scope="s">
                <span>{{ s.row.releaseId || '-' }}</span>
                <span v-if="s.row.rulesHash" class="cy-mono"> · {{ String(s.row.rulesHash).slice(0, 10) }}...</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="220" align="center">
              <template slot-scope="s">
                <el-button type="text" size="mini" @click="openBoardEditor(s.row.boardId)">编辑</el-button>
                <el-button
                  v-hasPermi="['business:citygame:edit']"
                  type="text"
                  size="mini"
                  :disabled="s.row.lifecycle === 'FROZEN'"
                  @click="handlePublish(s.row.boardId)"
                >发布</el-button>
                <el-button
                  v-hasPermi="['business:citygame:edit']"
                  type="text"
                  size="mini"
                  :disabled="s.row.lifecycle !== 'OPEN'"
                  @click="handleFreeze(s.row.boardId)"
                >冻结</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <el-card v-if="editorVisible" shadow="never" class="cy-editor-card">
          <div slot="header" class="cy-editor-header">
            <span>棋盘编辑器 · {{ form.boardId || '新草稿' }}</span>
            <el-tag v-if="form.lifecycle" :type="lifecycleTagType(form.lifecycle)" size="mini">{{ form.lifecycle }}</el-tag>
          </div>

          <el-form ref="boardForm" :model="form" label-width="115px" size="small" @submit.native.prevent>
            <el-row :gutter="16">
              <el-col :xs="24" :sm="12" :md="8">
                <el-form-item label="棋盘 ID">
                  <el-input v-model="form.boardId" placeholder="自动生成或填入 board-xx" :disabled="form.lifecycle === 'FROZEN'" />
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="12" :md="8">
                <el-form-item label="区域 ID">
                  <el-input v-model="form.regionId" placeholder="如 shanghai" :disabled="form.lifecycle === 'FROZEN'" />
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="12" :md="8">
                <el-form-item label="赛季 ID">
                  <el-input v-model="form.seasonId" placeholder="如 s2026w" :disabled="form.lifecycle === 'FROZEN'" />
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="12" :md="8">
                <el-form-item label="棋盘名称">
                  <el-input v-model="form.title" maxlength="160" :disabled="form.lifecycle === 'FROZEN'" />
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="12" :md="8">
                <el-form-item label="每回合行动力">
                  <el-input-number v-model="form.actionsPerRound" :min="1" :max="50" />
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="12" :md="8">
                <el-form-item label="最大回合数">
                  <el-input-number v-model="form.maxRounds" :min="1" :max="100" />
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="12" :md="8">
                <el-form-item label="掷骰消耗">
                  <el-input-number v-model="form.rollCost" :min="1" :max="20" />
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="12" :md="8">
                <el-form-item label="占领积分成本">
                  <el-input-number v-model="form.acquirePoints" :min="0" :max="10000" />
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="12" :md="8">
                <el-form-item label="保护期（秒）">
                  <el-input-number v-model="form.protectionSeconds" :min="0" :max="86400" />
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="12" :md="12">
                <el-form-item label="骰子面值 JSON">
                  <el-input v-model="form.diceFaces" placeholder="[1,2,3,4,5,6]" />
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="12" :md="12">
                <el-form-item label="骰子权重 JSON">
                  <el-input v-model="form.diceWeights" placeholder="[1,1,1,1,1,1]">
                    <el-button slot="append" @click="handlePreviewDice">预览概率</el-button>
                  </el-input>
                </el-form-item>
              </el-col>
            </el-row>

            <div v-if="dicePreview.length" class="cy-dice-preview" aria-label="骰子概率分布">
              <span v-for="item in dicePreview" :key="item.faceIndex" class="cy-dice-chip">
                面值 {{ item.face }} · 权重 {{ item.weight }} · 概率 {{ (item.probability * 100).toFixed(1) }}%
              </span>
            </div>

            <div class="cy-section-bar">
              <h3>轨道地块与实景 POI 关联</h3>
              <div class="cy-section-actions">
                <el-select v-model="pickedPoiId" filterable clearable size="mini" placeholder="选择漫游 POI 添加" aria-label="选择漫游POI">
                  <el-option
                    v-for="poi in roamPois"
                    :key="poi.id"
                    :label="poi.name + ' (ID:' + poi.id + ')'"
                    :value="poi.id"
                  />
                </el-select>
                <el-button size="mini" @click="addPoiSpace">从 POI 添加地块</el-button>
                <el-button size="mini" @click="addBlankSpace">新增空白地块</el-button>
              </div>
            </div>

            <div class="cy-table-scroll">
              <el-table :data="form.spaces" border size="mini" aria-label="棋盘轨道地块列表">
                <el-table-column label="轨序" width="60" align="center">
                  <template slot-scope="s">{{ s.$index }}</template>
                </el-table-column>
                <el-table-column label="Point ID" width="120">
                  <template slot-scope="s"><el-input v-model="s.row.pointId" size="mini" aria-label="点位ID" /></template>
                </el-table-column>
                <el-table-column label="Property ID" width="120">
                  <template slot-scope="s"><el-input v-model="s.row.propertyId" size="mini" aria-label="地块ID" /></template>
                </el-table-column>
                <el-table-column label="名称" min-width="140">
                  <template slot-scope="s"><el-input v-model="s.row.title" size="mini" aria-label="地块名称" /></template>
                </el-table-column>
                <el-table-column label="关联 POI ID" width="110">
                  <template slot-scope="s"><el-input-number v-model="s.row.poiId" size="mini" :controls="false" style="width:90px" aria-label="POI ID" /></template>
                </el-table-column>
                <el-table-column label="纬度" width="115">
                  <template slot-scope="s"><el-input-number v-model="s.row.latitude" size="mini" :precision="6" :controls="false" style="width:95px" aria-label="纬度" /></template>
                </el-table-column>
                <el-table-column label="经度" width="115">
                  <template slot-scope="s"><el-input-number v-model="s.row.longitude" size="mini" :precision="6" :controls="false" style="width:95px" aria-label="经度" /></template>
                </el-table-column>
                <el-table-column label="角色" width="120">
                  <template slot-scope="s">
                    <el-select v-model="s.row.role" size="mini" aria-label="地块角色">
                      <el-option label="可经营 OPERABLE" value="OPERABLE" />
                      <el-option label="中立 NEUTRAL" value="NEUTRAL" />
                    </el-select>
                  </template>
                </el-table-column>
                <el-table-column label="过路积分" width="100">
                  <template slot-scope="s"><el-input-number v-model="s.row.tollPoints" size="mini" :min="0" :controls="false" style="width:76px" aria-label="过路积分" /></template>
                </el-table-column>
                <el-table-column label="删" width="60" align="center">
                  <template slot-scope="s">
                    <el-button type="text" size="mini" @click="form.spaces.splice(s.$index, 1)">移除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>

            <div class="cy-section-bar">
              <h3>挑战题目配置</h3>
              <el-button size="mini" @click="addTask">新增挑战题</el-button>
            </div>
            <div class="cy-table-scroll">
              <el-table :data="form.tasks" border size="mini" aria-label="棋盘挑战题列表">
                <el-table-column label="Task ID" width="120">
                  <template slot-scope="s"><el-input v-model="s.row.taskId" size="mini" aria-label="任务ID" /></template>
                </el-table-column>
                <el-table-column label="类型" width="150">
                  <template slot-scope="s">
                    <el-select v-model="s.row.access" size="mini" aria-label="任务类型">
                      <el-option label="远端中立" value="REMOTE_NEUTRAL" />
                      <el-option label="到店零效果" value="ONSITE_ZERO_EFFECT" />
                    </el-select>
                  </template>
                </el-table-column>
                <el-table-column label="题目" min-width="200">
                  <template slot-scope="s"><el-input v-model="s.row.question" size="mini" aria-label="题目" /></template>
                </el-table-column>
                <el-table-column label="正确选项ID" width="100">
                  <template slot-scope="s"><el-input v-model="s.row.answerOptionId" size="mini" aria-label="正确选项" /></template>
                </el-table-column>
                <el-table-column label="删" width="60" align="center">
                  <template slot-scope="s">
                    <el-button type="text" size="mini" @click="form.tasks.splice(s.$index, 1)">移除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>

            <div class="cy-editor-footer">
              <el-button
                v-hasPermi="['business:citygame:edit']"
                type="primary"
                size="small"
                :loading="saving"
                :disabled="form.lifecycle === 'FROZEN'"
                @click="handleSaveDraft"
              >保存草稿</el-button>
              <el-button
                v-if="form.boardId"
                v-hasPermi="['business:citygame:edit']"
                type="success"
                size="small"
                :loading="saving"
                :disabled="form.lifecycle === 'FROZEN'"
                @click="handlePublish(form.boardId)"
              >验证并发布棋盘</el-button>
            </div>
          </el-form>
        </el-card>
      </el-tab-pane>

      <!-- 2. 商家运营审批 -->
      <el-tab-pane label="商家运营审批" name="merchantOps">
        <div class="cy-toolbar">
          <el-select v-model="merchantOpsStatus" size="small" clearable placeholder="全部状态" aria-label="审批状态筛选" @change="loadMerchantOps">
            <el-option label="待审核 IN_REVIEW" value="IN_REVIEW" />
            <el-option label="已生效 APPLIED" value="APPLIED" />
            <el-option label="已驳回 REJECTED" value="REJECTED" />
          </el-select>
          <el-button size="small" @click="loadMerchantOps">刷新</el-button>
        </div>
        <div class="cy-table-scroll">
          <el-table v-loading="opsLoading" :data="merchantOps" border size="small" aria-label="商家运营审批队列">
            <el-table-column label="商家" min-width="140">
              <template slot-scope="s">{{ s.row.merchantName }} (#{{ s.row.merchantId }})</template>
            </el-table-column>
            <el-table-column label="区域" prop="regionId" width="120" />
            <el-table-column label="动作" prop="kind" width="150" />
            <el-table-column label="状态" prop="status" width="110" />
            <el-table-column label="运行态" prop="effectiveState" width="110" />
            <el-table-column label="原因" prop="reason" min-width="180" />
            <el-table-column label="审核" width="160" align="center">
              <template slot-scope="s">
                <template v-if="s.row.status === 'IN_REVIEW'">
                  <el-button v-hasPermi="['business:citygame:edit']" type="text" size="mini" @click="reviewOp(s.row, 'APPROVE')">通过</el-button>
                  <el-button v-hasPermi="['business:citygame:edit']" type="text" size="mini" @click="reviewOp(s.row, 'REJECT')">驳回</el-button>
                </template>
                <span v-else class="cy-muted">已处理</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <!-- 3. 公平性与统一骰子审计 -->
      <el-tab-pane label="公平性与骰子审计" name="fairness">
        <div class="cy-toolbar">
          <el-select v-model="auditBoardId" size="small" filterable placeholder="选择棋盘查看审计" aria-label="选择审计棋盘" @change="loadAuditAndFairness">
            <el-option v-for="b in boards" :key="b.boardId" :label="b.title + ' (' + b.boardId + ')'" :value="b.boardId" />
          </el-select>
          <el-button size="small" @click="loadAuditAndFairness">查询报告</el-button>
        </div>

        <el-card v-if="fairnessReport" shadow="never" class="cy-fairness-card">
          <div class="cy-metric-grid">
            <div class="cy-metric"><span>Formula</span><strong>{{ fairnessReport.formulaId }}</strong></div>
            <div class="cy-metric"><span>目标完成</span><strong>{{ fairnessFacts.goalCompletions || 0 }}</strong></div>
            <div class="cy-metric"><span>有效得分</span><strong>{{ fairnessFacts.effectiveGain || 0 }}</strong></div>
            <div class="cy-metric"><span>商家核验次数</span><strong>{{ fairnessFacts.merchantVerifications || 0 }}</strong></div>
            <div class="cy-metric"><span>核验总耗时(ms)</span><strong>{{ fairnessFacts.merchantVerificationMillis || 0 }}</strong></div>
          </div>
          <p class="cy-muted">未埋点字段（显式标注）：{{ (fairnessReport.coverageGaps || []).join(', ') }}</p>
        </el-card>

        <div class="cy-table-scroll">
          <el-table :data="rollAudit" border size="small" aria-label="统一骰子审计记录">
            <el-table-column label="时间" prop="createTime" width="160" />
            <el-table-column label="来源路径" prop="sourcePath" min-width="180" />
            <el-table-column label="玩家 / 小队" min-width="160">
              <template slot-scope="s">#{{ s.row.memberId }} / {{ String(s.row.teamId || '').slice(0, 8) }}</template>
            </el-table-column>
            <el-table-column label="点数 (票/总权)" width="140" align="center">
              <template slot-scope="s">{{ s.row.face }} ({{ s.row.randomTicket }}/{{ s.row.totalWeight }})</template>
            </el-table-column>
            <el-table-column label="轨道位移" width="130" align="center">
              <template slot-scope="s">{{ s.row.previousPosition }} → {{ s.row.newPosition }}</template>
            </el-table-column>
            <el-table-column label="落点" prop="landedPropertyId" width="120" />
            <el-table-column label="行动力" width="100" align="center">
              <template slot-scope="s">{{ s.row.beforeUnits }} → {{ s.row.afterUnits }}</template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <!-- 4. 领地、赛季榜与反作弊 -->
      <el-tab-pane label="领地与反作弊" name="territories">
        <div class="cy-toolbar">
          <el-select v-model="territoryStatusFilter" size="small" clearable placeholder="全部领地状态" aria-label="领地状态" @change="loadTerritories">
            <el-option label="ACTIVE" value="ACTIVE" />
            <el-option label="PAUSED" value="PAUSED" />
            <el-option label="REVOKED" value="REVOKED" />
          </el-select>
          <el-button size="small" @click="loadTerritories">刷新领地与榜单</el-button>
        </div>

        <h3>领地占领列表（积分模式，非现金）</h3>
        <div class="cy-table-scroll">
          <el-table :data="territoryList" border size="small" aria-label="漫游领地列表">
            <el-table-column label="名称" prop="title" min-width="140" />
            <el-table-column label="棋盘 / 赛季" min-width="150">
              <template slot-scope="s">{{ s.row.board_id || '-' }} / {{ s.row.season_id || '-' }}</template>
            </el-table-column>
            <el-table-column label="占领者" min-width="120">
              <template slot-scope="s">{{ s.row.ownerNickname }} (#{{ s.row.owner_member_id }})</template>
            </el-table-column>
            <el-table-column label="打卡 / 积分收益" width="140" align="center">
              <template slot-scope="s">{{ s.row.checkin_count }} 次 / {{ s.row.revenue_total_points }} 分</template>
            </el-table-column>
            <el-table-column label="状态" prop="status" width="100" align="center" />
            <el-table-column label="治理" width="160" align="center">
              <template slot-scope="s">
                <el-button v-hasPermi="['business:citygame:edit']" type="text" size="mini" @click="setTerritoryState(s.row.territory_id, 'ACTIVE')">启用</el-button>
                <el-button v-hasPermi="['business:citygame:edit']" type="text" size="mini" @click="setTerritoryState(s.row.territory_id, 'PAUSED')">暂停</el-button>
                <el-button v-hasPermi="['business:citygame:edit']" type="text" size="mini" @click="setTerritoryState(s.row.territory_id, 'REVOKED')">收回</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <el-row :gutter="16" class="cy-split-row">
          <el-col :xs="24" :md="12">
            <h3>赛季领地收益榜（积分）</h3>
            <div class="cy-table-scroll">
              <el-table :data="leaderboardRows" border size="small" aria-label="领地积分排行榜">
                <el-table-column label="排名" width="60" align="center">
                  <template slot-scope="s">{{ s.$index + 1 }}</template>
                </el-table-column>
                <el-table-column label="玩家" prop="ownerNickname" />
                <el-table-column label="领地数" prop="territoryCount" width="80" align="center" />
                <el-table-column label="累计积分" prop="totalRevenuePoints" width="100" align="center" />
              </el-table>
            </div>
          </el-col>
          <el-col :xs="24" :md="12">
            <h3>定位合理性与瞬移审计日志</h3>
            <div class="cy-table-scroll">
              <el-table :data="territoryAudit" border size="small" aria-label="反作弊审计日志">
                <el-table-column label="时间" prop="create_time" width="150" />
                <el-table-column label="会员" prop="member_id" width="80" />
                <el-table-column label="结论" width="95">
                  <template slot-scope="s">
                    <el-tag :type="s.row.verdict === 'BLOCKED' ? 'danger' : (s.row.verdict === 'FLAGGED' ? 'warning' : 'success')" size="mini">
                      {{ s.row.verdict }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="原因 / 速度(m/s)" min-width="140">
                  <template slot-scope="s">
                    {{ s.row.reason_code || 'OK' }}
                    <span v-if="s.row.implied_speed_mps != null"> ({{ Number(s.row.implied_speed_mps).toFixed(1) }} m/s)</span>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </el-col>
        </el-row>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script>
import {
  listBoards,
  getBoard,
  previewDice,
  saveBoardDraft,
  updateBoardDraft,
  publishBoard,
  freezeBoard,
  getBoardAudit,
  getMerchantOpsQueue,
  reviewMerchantOp,
  getFairnessReport,
  listTerritories,
  updateTerritoryStatus,
  getTerritoryAudit,
  getTerritoryLeaderboard
} from '@/api/business/citygame'
import { listRoamPoi } from '@/api/business/roamPoi'

const emptyForm = () => ({
  boardId: '',
  regionId: 'shanghai',
  seasonId: 's2026w',
  title: '',
  lifecycle: 'DRAFT',
  actionsPerRound: 4,
  maxRounds: 4,
  rollCost: 1,
  acquisitionCost: 1,
  acquirePoints: 10,
  protectionSeconds: 3600,
  diceFaces: '[1,2,3,4,5,6]',
  diceWeights: '[1,1,1,1,1,1]',
  spaces: [],
  tasks: []
})

export default {
  name: 'CityGameAdmin',
  data() {
    return {
      activeTab: 'boards',
      boardsLoading: false,
      boards: [],
      roamPois: [],
      pickedPoiId: null,
      editorVisible: false,
      saving: false,
      form: emptyForm(),
      dicePreview: [],
      merchantOpsStatus: '',
      opsLoading: false,
      merchantOps: [],
      auditBoardId: '',
      fairnessReport: null,
      rollAudit: [],
      territoryStatusFilter: '',
      territoryList: [],
      territoryAudit: [],
      leaderboardRows: []
    }
  },
  computed: {
    fairnessFacts() {
      return (this.fairnessReport && this.fairnessReport.facts) || {}
    }
  },
  created() {
    this.loadBoards()
    this.loadPois()
  },
  methods: {
    lifecycleTagType(lifecycle) {
      if (lifecycle === 'OPEN') return 'success'
      if (lifecycle === 'FROZEN') return 'info'
      return 'warning'
    },
    onTabChange(tab) {
      if (tab.name === 'merchantOps') this.loadMerchantOps()
      if (tab.name === 'fairness') this.loadAuditAndFairness()
      if (tab.name === 'territories') this.loadTerritories()
    },
    loadBoards() {
      this.boardsLoading = true
      listBoards().then(res => {
        this.boards = res.data || []
        if (!this.auditBoardId && this.boards.length) {
          this.auditBoardId = this.boards[0].boardId
        }
      }).finally(() => {
        this.boardsLoading = false
      })
    },
    loadPois() {
      listRoamPoi({ status: 1 }).then(res => {
        this.roamPois = res.data || res.rows || []
      }).catch(() => {})
    },
    startNewBoard() {
      this.form = emptyForm()
      this.dicePreview = []
      this.editorVisible = true
    },
    openBoardEditor(boardId) {
      getBoard(boardId).then(res => {
        const d = res.data || {}
        this.form = Object.assign(emptyForm(), d, {
          spaces: (d.spaces || []).map(x => Object.assign({}, x)),
          tasks: (d.tasks || []).map(x => Object.assign({}, x))
        })
        this.editorVisible = true
        this.handlePreviewDice()
      })
    },
    addPoiSpace() {
      const poi = this.roamPois.find(p => p.id === this.pickedPoiId)
      if (!poi) return
      const n = this.form.spaces.length
      this.form.spaces.push({
        pointId: 'pt-' + poi.id + '-' + n,
        propertyId: 'prop-' + poi.id + '-' + n,
        title: poi.name,
        poiId: poi.id,
        merchantId: poi.merchantId || null,
        latitude: Number(poi.lat),
        longitude: Number(poi.lng),
        role: 'OPERABLE',
        tollPoints: 5
      })
    },
    addBlankSpace() {
      const n = this.form.spaces.length
      this.form.spaces.push({
        pointId: 'pt-' + n,
        propertyId: 'prop-' + n,
        title: '地块 #' + (n + 1),
        poiId: null,
        latitude: 31.2304,
        longitude: 121.4737,
        role: n === 0 ? 'NEUTRAL' : 'OPERABLE',
        tollPoints: 5
      })
    },
    addTask() {
      const n = this.form.tasks.length + 1
      this.form.tasks.push({
        taskId: 'task-' + n,
        access: 'REMOTE_NEUTRAL',
        question: '确认完成该地点探索？',
        options: [{ id: 'A', label: '确认' }, { id: 'B', label: '跳过' }],
        answerOptionId: 'A',
        maxAttempts: 2
      })
    },
    handlePreviewDice() {
      previewDice({ diceFaces: this.form.diceFaces, diceWeights: this.form.diceWeights }).then(res => {
        this.dicePreview = (res.data && res.data.probabilities) || []
      })
    },
    handleSaveDraft() {
      this.saving = true
      const req = this.form.boardId ? updateBoardDraft(this.form.boardId, this.form) : saveBoardDraft(this.form)
      req.then(res => {
        this.$message.success('草稿已保存')
        if (res.data && res.data.boardId) this.form.boardId = res.data.boardId
        this.loadBoards()
      }).finally(() => {
        this.saving = false
      })
    },
    handlePublish(boardId) {
      this.$confirm('发布后将生成冻结规则摘要并开放玩家加入，是否继续？', '发布棋盘', { type: 'warning' }).then(() => {
        return publishBoard(boardId)
      }).then(() => {
        this.$message.success('棋盘已发布并开放')
        this.loadBoards()
      }).catch(() => {})
    },
    handleFreeze(boardId) {
      this.$confirm('冻结后棋盘将进入只读结算态，是否继续？', '冻结棋盘', { type: 'warning' }).then(() => {
        return freezeBoard(boardId)
      }).then(() => {
        this.$message.success('棋盘已冻结')
        this.loadBoards()
      }).catch(() => {})
    },
    loadMerchantOps() {
      this.opsLoading = true
      getMerchantOpsQueue({ status: this.merchantOpsStatus || undefined }).then(res => {
        this.merchantOps = res.data || []
      }).finally(() => {
        this.opsLoading = false
      })
    },
    reviewOp(row, decision) {
      reviewMerchantOp({
        merchantId: row.merchantId,
        regionId: row.regionId,
        decision,
        reason: decision === 'APPROVE' ? '运营核准执行' : '运营驳回申请'
      }).then(() => {
        this.$message.success(decision === 'APPROVE' ? '已核准并应用到运行态' : '已驳回')
        this.loadMerchantOps()
      })
    },
    loadAuditAndFairness() {
      if (!this.auditBoardId) return
      getFairnessReport({ boardId: this.auditBoardId }).then(res => {
        this.fairnessReport = res.data || null
      })
      getBoardAudit(this.auditBoardId, { limit: 50 }).then(res => {
        this.rollAudit = (res.data && res.data.rollAudit) || []
      })
    },
    loadTerritories() {
      listTerritories({ status: this.territoryStatusFilter || undefined }).then(res => {
        this.territoryList = res.data || []
      })
      getTerritoryAudit({ limit: 50 }).then(res => {
        this.territoryAudit = res.data || []
      })
      getTerritoryLeaderboard({ limit: 20 }).then(res => {
        this.leaderboardRows = (res.data && res.data.leaders) || []
      })
    },
    setTerritoryState(territoryId, status) {
      updateTerritoryStatus(territoryId, { status, reason: '后台运营调整' }).then(() => {
        this.$message.success('领地状态已更新')
        this.loadTerritories()
      })
    }
  }
}
</script>

<style scoped>
.cy-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}
.cy-table-scroll {
  width: 100%;
  overflow-x: auto;
}
.cy-editor-card {
  margin-top: 16px;
}
.cy-editor-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.cy-dice-preview {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 8px 0 16px;
}
.cy-dice-chip {
  padding: 4px 10px;
  background: #f4f4f5;
  border-radius: 4px;
  font-size: 12px;
}
.cy-section-bar {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin: 16px 0 8px;
}
.cy-section-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.cy-editor-footer {
  margin-top: 16px;
  display: flex;
  gap: 8px;
}
.cy-fairness-card {
  margin-bottom: 16px;
}
.cy-metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
  margin-bottom: 8px;
}
.cy-metric {
  padding: 8px 12px;
  background: #f8f9fa;
  border-radius: 4px;
}
.cy-metric span {
  display: block;
  font-size: 12px;
  color: #606266;
}
.cy-metric strong {
  font-size: 16px;
}
.cy-split-row {
  margin-top: 16px;
}
.cy-mono {
  font-family: monospace;
  font-size: 12px;
  color: #606266;
}
.cy-muted {
  color: #606266;
  font-size: 12px;
}
</style>
