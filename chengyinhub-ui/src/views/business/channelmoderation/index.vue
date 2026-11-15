<template>
  <div class="app-container">
    <cy-page-header eyebrow="审核与风控" title="频道治理" description="平台对俱乐部与队伍频道的处置：屏蔽消息、禁言、移出空间。每一步都写入平台审计。" />

    <el-tabs v-model="tab" @tab-click="load">
      <el-tab-pane label="举报" name="reports">
        <el-form size="small" :inline="true">
          <el-form-item label="状态">
            <el-select v-model="reportQuery.status" clearable style="width: 130px" placeholder="全部">
              <el-option v-for="(t, k) in reportStatusText" :key="k" :label="t" :value="k" />
            </el-select>
          </el-form-item>
          <el-form-item label="频道编号"><el-input v-model="reportQuery.channelId" clearable placeholder="频道编号" style="width: 130px" /></el-form-item>
          <el-form-item><el-button type="primary" icon="el-icon-search" @click="load">筛选</el-button></el-form-item>
        </el-form>
        <cy-resource-state v-if="error" kind="error" title="举报暂时无法读取" retry-text="重新读取" :retrying="loading" @retry="load" />
        <cy-empty-state v-else-if="!loading && !reports.length" title="没有符合条件的举报" />
        <el-table v-else v-loading="loading" :data="reports" size="small" border>
          <el-table-column label="举报" width="80" prop="id" />
          <el-table-column label="频道" min-width="140">
            <template slot-scope="s">{{ s.row.channelName || '频道' }} #{{ s.row.channelId }}</template>
          </el-table-column>
          <el-table-column label="理由" min-width="140">
            <template slot-scope="s">{{ reasonText(s.row.reasonCode) }}<span v-if="s.row.reasonText">：{{ s.row.reasonText }}</span></template>
          </el-table-column>
          <el-table-column label="被举报内容" min-width="240" show-overflow-tooltip>
            <template slot-scope="s">玩家 #{{ s.row.senderId }}：{{ s.row.content || '（内容已不存在）' }}</template>
          </el-table-column>
          <el-table-column label="消息" width="90" align="center">
            <template slot-scope="s">{{ messageStatusText(s.row.messageStatus) }}</template>
          </el-table-column>
          <el-table-column label="举报状态" width="90" align="center">
            <template slot-scope="s">{{ reportStatusText[s.row.status] || '未知' }}</template>
          </el-table-column>
          <el-table-column label="时间" prop="createTime" width="160" />
          <el-table-column label="操作" width="210" align="center">
            <template slot-scope="s">
              <template v-if="s.row.status === 'PENDING'">
                <el-button v-if="s.row.messageStatus === 0" v-hasPermi="['business:channelModeration:message']" type="text" size="mini" @click="hide({ id: s.row.targetId })">屏蔽消息</el-button>
                <el-button v-hasPermi="['business:channelModeration:message']" type="text" size="mini" @click="dismiss(s.row)">驳回举报</el-button>
              </template>
              <el-button v-if="s.row.guildKind" v-hasPermi="['business:channelModeration:member']" type="text" size="mini" @click="openMemberAction('mute', { channelId: s.row.channelId, memberId: s.row.senderId })">禁言</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="频道" name="channels">
        <el-form size="small" :inline="true">
          <el-form-item label="关键词"><el-input v-model="channelQuery.keyword" clearable placeholder="频道名或话题" style="width: 180px" /></el-form-item>
          <el-form-item label="空间">
            <el-select v-model="channelQuery.guildKind" clearable placeholder="全部" style="width: 110px">
              <el-option label="俱乐部" :value="1" />
              <el-option label="队伍" :value="3" />
            </el-select>
          </el-form-item>
          <el-form-item label="空间编号"><el-input v-model="channelQuery.guildId" clearable placeholder="空间编号" style="width: 120px" /></el-form-item>
          <el-form-item><el-button type="primary" icon="el-icon-search" @click="load">筛选</el-button></el-form-item>
        </el-form>
        <el-table v-loading="loading" :data="channels" size="small" border>
          <el-table-column label="频道" min-width="180">
            <template slot-scope="s">{{ s.row.name }} #{{ s.row.id }}</template>
          </el-table-column>
          <el-table-column label="空间" width="150">
            <template slot-scope="s">{{ guildText(s.row.guildKind) }} #{{ s.row.guildId }}</template>
          </el-table-column>
          <el-table-column label="话题" prop="topic" min-width="160" show-overflow-tooltip />
          <el-table-column label="状态" width="80" align="center">
            <template slot-scope="s">{{ channelStatusText(s.row.status) }}</template>
          </el-table-column>
          <el-table-column label="待处理举报" prop="pendingReports" width="100" align="center" />
          <el-table-column label="操作" width="120" align="center">
            <template slot-scope="s"><el-button type="text" size="mini" @click="viewMessages(s.row)">看消息</el-button></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="消息" name="messages">
        <el-form size="small" :inline="true">
          <el-form-item label="频道编号"><el-input v-model="messageQuery.channelId" clearable placeholder="频道编号" style="width: 120px" /></el-form-item>
          <el-form-item label="发送人"><el-input v-model="messageQuery.senderId" clearable placeholder="玩家编号" style="width: 120px" /></el-form-item>
          <el-form-item label="关键词"><el-input v-model="messageQuery.keyword" clearable placeholder="消息内容" style="width: 160px" /></el-form-item>
          <el-form-item label="状态">
            <el-select v-model="messageQuery.status" clearable placeholder="全部" style="width: 110px">
              <el-option v-for="(t, k) in messageStatusMap" :key="k" :label="t" :value="Number(k)" />
            </el-select>
          </el-form-item>
          <el-form-item><el-button type="primary" icon="el-icon-search" @click="load">搜索</el-button></el-form-item>
        </el-form>
        <cy-empty-state v-if="!loading && !messages.length" title="按频道、发送人或关键词搜索消息" />
        <el-table v-else v-loading="loading" :data="messages" size="small" border>
          <el-table-column label="消息" width="80" prop="id" />
          <el-table-column label="频道" min-width="130">
            <template slot-scope="s">{{ s.row.channelName }} #{{ s.row.channelId }}</template>
          </el-table-column>
          <el-table-column label="发送人" width="110">
            <template slot-scope="s">玩家 #{{ s.row.senderId }}</template>
          </el-table-column>
          <el-table-column label="内容" prop="content" min-width="260" show-overflow-tooltip />
          <el-table-column label="状态" width="90" align="center">
            <template slot-scope="s">{{ messageStatusText(s.row.status) }}</template>
          </el-table-column>
          <el-table-column label="时间" prop="createTime" width="160" />
          <el-table-column label="操作" width="200" align="center">
            <template slot-scope="s">
              <el-button v-if="s.row.status === 0" v-hasPermi="['business:channelModeration:message']" type="text" size="mini" @click="hide(s.row)">屏蔽</el-button>
              <el-button v-if="s.row.status === 2" v-hasPermi="['business:channelModeration:message']" type="text" size="mini" @click="restore(s.row)">恢复</el-button>
              <el-button v-hasPermi="['business:channelModeration:member']" type="text" size="mini" @click="openMemberAction('mute', { channelId: s.row.channelId, memberId: s.row.senderId })">禁言</el-button>
              <el-button v-hasPermi="['business:channelModeration:member']" type="text" size="mini" @click="openMemberAction('remove', { guildKind: s.row.guildKind, guildId: s.row.guildId, memberId: s.row.senderId })">移出空间</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button v-if="messages.length >= 50" size="small" style="margin-top: 12px" @click="loadMoreMessages">更早的消息</el-button>
      </el-tab-pane>

      <el-tab-pane label="处置记录" name="actions">
        <el-form size="small" :inline="true">
          <el-form-item label="玩家编号"><el-input v-model="actionQuery.targetMemberId" clearable placeholder="玩家编号" style="width: 130px" /></el-form-item>
          <el-form-item label="空间编号"><el-input v-model="actionQuery.guildId" clearable placeholder="空间编号" style="width: 130px" /></el-form-item>
          <el-form-item><el-button type="primary" icon="el-icon-search" @click="load">筛选</el-button></el-form-item>
        </el-form>
        <el-table v-loading="loading" :data="actions" size="small" border>
          <el-table-column label="处置" min-width="110">
            <template slot-scope="s">{{ actionText(s.row.actionType) }}</template>
          </el-table-column>
          <el-table-column label="对象" width="120">
            <template slot-scope="s">玩家 #{{ s.row.targetMemberId }}</template>
          </el-table-column>
          <el-table-column label="范围" min-width="160">
            <template slot-scope="s">{{ guildText(s.row.guildKind) }} #{{ s.row.guildId }}<span v-if="s.row.scopeType === 'CHANNEL'"> · 频道 #{{ s.row.scopeId }}</span></template>
          </el-table-column>
          <el-table-column label="理由" min-width="180" show-overflow-tooltip>
            <template slot-scope="s">{{ reasonText(s.row.reasonCode) }}：{{ s.row.reasonText }}</template>
          </el-table-column>
          <el-table-column label="操作人" width="100">
            <template slot-scope="s">{{ s.row.operatorId ? '空间管理员' : '平台' }}</template>
          </el-table-column>
          <el-table-column label="到期" prop="expiresAt" width="160" />
          <el-table-column label="状态" width="80" align="center">
            <template slot-scope="s">{{ actionStatusText(s.row.status) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="center">
            <template slot-scope="s">
              <el-button v-if="s.row.status === 'ACTIVE'" v-hasPermi="['business:channelModeration:member']" type="text" size="mini" @click="revoke(s.row)">撤销</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog :title="memberAction.kind === 'mute' ? '频道禁言' : '移出空间'" :visible.sync="memberActionOpen" width="480px" append-to-body>
      <el-form size="small" label-width="90px">
        <el-form-item label="玩家编号"><el-input v-model="memberAction.memberId" /></el-form-item>
        <el-form-item v-if="memberAction.kind === 'mute'" label="频道编号"><el-input v-model="memberAction.channelId" /></el-form-item>
        <template v-else>
          <el-form-item label="空间">
            <el-select v-model="memberAction.guildKind">
              <el-option label="俱乐部" :value="1" />
              <el-option label="队伍" :value="3" />
            </el-select>
          </el-form-item>
          <el-form-item label="空间编号"><el-input v-model="memberAction.guildId" /></el-form-item>
        </template>
        <el-form-item label="时长">
          <el-select v-model="memberAction.durationSec">
            <el-option v-for="d in durations" :key="d.value" :label="d.label" :value="d.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="理由">
          <el-select v-model="memberAction.reasonCode">
            <el-option v-for="(t, k) in reasonMap" :key="k" :label="t" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="memberAction.reasonText" type="textarea" :rows="2" maxlength="500" placeholder="必填，空间成员可见" /></el-form-item>
        <el-alert v-if="memberAction.kind === 'remove' && memberAction.guildKind === 1" type="info" :closable="false"
          title="俱乐部：只封禁频道发言，不终止会籍；终止会籍请走俱乐部治理。" />
      </el-form>
      <div slot="footer">
        <el-button size="small" @click="memberActionOpen = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="submitMemberAction">确定</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import {
  moderationChannels, moderationMessages, moderationReports, moderationActions, hideChannelMessage, restoreChannelMessage,
  dismissChannelReport, muteChannelMember, removeSpaceMember, revokeModerationAction
} from '@/api/business/channelModeration'
import CyEmptyState from '@/components/CyEmptyState'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'

const REASONS = {
  SPAM: '垃圾广告', HARASSMENT: '骚扰', HATE: '仇恨言论', FRAUD: '诈骗', DANGEROUS: '危险行为', MINOR_SAFETY: '未成年人安全',
  SELF_HARM: '自我伤害', SEXUAL_CONTENT: '色情内容', IMPERSONATION: '冒充他人', FALSE_INFORMATION: '虚假信息',
  PRIVACY: '泄露隐私', INTELLECTUAL_PROPERTY: '侵犯版权', OTHER: '其他'
}

export default {
  name: 'ChannelModeration',
  components: { CyEmptyState, CyPageHeader, CyResourceState },
  data() {
    return {
      tab: 'reports',
      loading: false,
      saving: false,
      error: false,
      reports: [],
      channels: [],
      messages: [],
      actions: [],
      reportQuery: { status: 'PENDING', channelId: '' },
      channelQuery: { keyword: '', guildKind: null, guildId: '' },
      messageQuery: { channelId: '', senderId: '', keyword: '', status: null },
      actionQuery: { targetMemberId: '', guildId: '' },
      reportStatusText: { PENDING: '待处理', RESOLVED: '已处理', DISMISSED: '已驳回' },
      messageStatusMap: { 0: '正常', 1: '已撤回', 2: '平台屏蔽' },
      reasonMap: REASONS,
      durations: [
        { value: 3600, label: '1 小时' }, { value: 86400, label: '1 天' }, { value: 604800, label: '7 天' },
        { value: 2419200, label: '28 天' }, { value: 7776000, label: '90 天（仅移出空间）' }
      ],
      memberActionOpen: false,
      memberAction: {}
    }
  },
  created() {
    this.load()
  },
  methods: {
    clean(o) {
      const out = {}
      Object.keys(o).forEach(k => { if (o[k] !== '' && o[k] !== null && o[k] !== undefined) out[k] = o[k] })
      return out
    },
    load() {
      const calls = {
        reports: () => moderationReports(this.clean(this.reportQuery)).then(r => { this.reports = r.data || [] }),
        channels: () => moderationChannels(this.clean(this.channelQuery)).then(r => { this.channels = r.data || [] }),
        messages: () => {
          const q = this.clean(this.messageQuery)
          if (!q.channelId && !q.senderId && !q.keyword) { this.messages = []; return Promise.resolve() }
          return moderationMessages(q).then(r => { this.messages = r.data || [] })
        },
        actions: () => moderationActions(this.clean(this.actionQuery)).then(r => { this.actions = r.data || [] })
      }
      this.loading = true
      this.error = false
      calls[this.tab]().catch(() => { this.error = true }).finally(() => { this.loading = false })
    },
    loadMoreMessages() {
      const last = this.messages[this.messages.length - 1]
      moderationMessages(Object.assign(this.clean(this.messageQuery), { beforeId: last.id })).then(r => {
        this.messages = this.messages.concat(r.data || [])
      })
    },
    viewMessages(row) {
      this.messageQuery = { channelId: String(row.id), senderId: '', keyword: '', status: null }
      this.tab = 'messages'
      this.load()
    },
    ask(title) {
      return this.$prompt('请填写说明（会写入平台审计）', title, { inputValidator: v => !!(v && v.trim()) || '说明必填' }).then(r => r.value)
    },
    hide(row) {
      this.ask('屏蔽消息').then(reason => hideChannelMessage(row.id, { reason })).then(() => {
        this.$modal.msgSuccess('已屏蔽，相关举报已标为已处理')
        this.load()
      }).catch(() => {})
    },
    restore(row) {
      this.ask('恢复消息').then(reason => restoreChannelMessage(row.id, { reason })).then(() => {
        this.$modal.msgSuccess('已恢复')
        this.load()
      }).catch(() => {})
    },
    dismiss(row) {
      this.ask('驳回举报').then(reason => dismissChannelReport(row.id, { reason })).then(() => {
        this.$modal.msgSuccess('已驳回')
        this.load()
      }).catch(() => {})
    },
    revoke(row) {
      this.ask('撤销处置').then(reason => revokeModerationAction(row.id, { reason })).then(() => {
        this.$modal.msgSuccess('已撤销')
        this.load()
      }).catch(() => {})
    },
    openMemberAction(kind, preset) {
      this.memberAction = Object.assign({ kind, memberId: '', channelId: '', guildKind: 1, guildId: '', durationSec: 86400, reasonCode: 'SPAM', reasonText: '' }, preset)
      this.memberActionOpen = true
    },
    submitMemberAction() {
      const a = this.memberAction
      const call = a.kind === 'mute'
        ? muteChannelMember({ channelId: a.channelId, memberId: a.memberId, durationSec: a.durationSec, reasonCode: a.reasonCode, reasonText: a.reasonText })
        : removeSpaceMember({ guildKind: a.guildKind, guildId: a.guildId, memberId: a.memberId, durationSec: a.durationSec, reasonCode: a.reasonCode, reasonText: a.reasonText })
      this.saving = true
      call.then(() => {
        this.$modal.msgSuccess(a.kind === 'mute' ? '已禁言' : '已移出空间')
        this.memberActionOpen = false
        this.load()
      }).finally(() => {
        this.saving = false
      })
    },
    reasonText(code) { return REASONS[code] || '其他' },
    guildText(kind) { return kind === 1 ? '俱乐部' : kind === 3 ? '队伍' : '空间' },
    channelStatusText(s) { return s === 0 ? '正常' : s === 1 ? '已归档' : '已关闭' },
    messageStatusText(s) { return this.messageStatusMap[s] || '—' },
    actionText(t) { return { TIMEOUT: '频道禁言', KICK: '踢出', BAN: '封禁 / 移出空间' }[t] || '处置' },
    actionStatusText(s) { return { ACTIVE: '生效中', REVOKED: '已撤销', APPLIED: '已执行', EXPIRED: '已到期' }[s] || '—' }
  }
}
</script>
