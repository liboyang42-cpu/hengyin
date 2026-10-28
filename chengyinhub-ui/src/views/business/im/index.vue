<template>
  <div class="app-container">
    <cy-page-header
      eyebrow="客服工作台"
      title="客服会话"
      description="集中处理客户咨询与即时沟通；违规认定和账号处置仍由风控链路负责。"
      :status="convsLoaded ? `${convs.length} 个会话` : ''"
      :updated-at="convsUpdatedAt"
    >
      <el-button v-if="!convsError && convsLoaded && convs.length" icon="el-icon-refresh" :loading="convsLoading" :disabled="convsLoading" @click="loadConvs">刷新会话</el-button>
    </cy-page-header>

    <el-row :gutter="16">
      <!-- 左:会话列表 -->
      <el-col :span="7">
        <el-card shadow="never" class="im-side">
          <div slot="header" class="im-side-hd">
            <span>会话列表</span>
          </div>
          <cy-resource-state
            v-if="convsError"
            kind="error"
            :title="convsError"
            retry-text="重新读取"
            :retrying="convsLoading"
            @retry="loadConvs"
          />
          <cy-empty-state
            v-else-if="convsLoaded && !convs.length"
            title="暂无客服会话"
            description="新的客户咨询会自动出现在这里。"
            icon="el-icon-chat-dot-round"
            action-text="刷新会话"
            :action-loading="convsLoading"
            @action="loadConvs"
          />
          <div v-else v-loading="convsLoading" class="im-conv-list">
            <div
              v-for="c in convs"
              :key="c.conversationId"
              class="im-conv"
              :class="{ active: current && current.conversationId === c.conversationId }"
              @click="selectConv(c)"
            >
              <el-avatar :size="40" :src="c.avatar">{{ (c.nickname || '?').charAt(0) }}</el-avatar>
              <div class="im-conv-body">
                <div class="im-conv-top">
                  <span class="im-conv-name">{{ c.nickname || ('用户' + c.memberId) }}</span>
                  <span>
                    <el-badge v-if="c.needReply" is-dot type="danger" class="im-need-dot" />
                    <el-tag v-if="c.blocked" size="mini" type="danger">已拉黑</el-tag>
                  </span>
                </div>
                <div class="im-conv-last">{{ c.lastMsgText }}</div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <!-- 右:聊天 -->
      <el-col :span="17">
        <el-card shadow="never" class="im-main">
          <div slot="header" class="im-main-hd" v-if="current">
            <span>{{ current.nickname || ('用户' + current.memberId) }}
              <span class="im-phone" v-if="current.phone">· {{ current.phone }}</span>
            </span>
          </div>

          <div v-if="!current" class="im-empty">
            <el-empty description="从左侧选择一个会话" />
          </div>

          <template v-else>
            <cy-resource-state
              v-if="messagesError"
              kind="error"
              :title="messagesError"
              description="请重新读取该客服会话；系统不会把读取失败显示为没有消息。"
              retry-text="重新读取"
              :retrying="messagesLoading"
              @retry="loadMsgs"
            />
            <div v-else v-loading="messagesLoading" class="im-msgs" ref="msgs">
              <div v-for="m in msgs" :key="m.id" class="im-msg" :class="m.senderId == 0 ? 'me' : 'other'">
                <div class="im-bubble">
                  <el-image v-if="m.msgType == 2" :src="m.content" style="max-width:200px;border-radius:6px" />
                  <span v-else>{{ m.content | sanitize }}</span>
                </div>
                <div class="im-time">{{ parseTime(m.createTime, '{m}-{d} {h}:{i}') }}</div>
              </div>
              <cy-empty-state
                v-if="!messagesLoading && !msgs.length"
                title="还没有消息"
                description="该客服会话尚未产生消息记录。"
                icon="el-icon-chat-dot-round"
              />
            </div>

            <el-alert v-if="sendRecoveryError" :title="sendRecoveryError" type="warning" :closable="false" />
            <el-button v-if="pendingSend" v-hasPermi="['business:im:send']"
              :loading="checkingSend" :disabled="sending || checkingSend" @click="checkSendReceipt">查询原结果</el-button>
            <div class="im-send">
              <el-input
                v-model="sendText"
                type="textarea"
                :rows="2"
                placeholder="输入消息，回车发送（Shift+回车换行）"
                @keyup.enter.native.exact="onEnter"
                :disabled="current.blocked || sending || !!pendingSend"
              />
              <el-button
                type="primary"
                v-hasPermi="['business:im:send']"
                :loading="sending"
                :disabled="!sendText.trim() || current.blocked || checkingSend"
                @click="doSend"
              >{{ pendingSend ? "按原请求重试" : "发送" }}</el-button>
            </div>
          </template>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script>
import { listImConversations, listImMessages, sendImMessage, getImSendReceipt } from '@/api/business/im'
import { adminSendIntent, clearAdminSendIntent } from '@/utils/im-admin-send-intent'
import { getToken } from '@/utils/auth'
import CyEmptyState from '@/components/CyEmptyState'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'

export default {
  name: 'ImWorkbench',
  components: { CyEmptyState, CyPageHeader, CyResourceState },
  data() {
    return {
      convs: [],
      convsLoading: false,
      convsLoaded: false,
      convsError: '',
      convsUpdatedAt: '',
      current: null,
      msgs: [],
      messagesLoading: false,
      messagesError: '',
      messageRequestId: 0,
      sendText: '',
      checkingSend: false,
      pendingSend: null,
      sendRecoveryError: '',
      sending: false
    }
  },
  created() {
    this.ensureCanonicalRoute()
  },
  beforeDestroy() { this._sendEpoch = (this._sendEpoch || 0) + 1 },
  watch: {
    '$store.state.user.token'() {
      this._sendEpoch = (this._sendEpoch || 0) + 1
      this.clearCurrentConversation()
      this.convs = []
      this.convsLoading = false
      this.convsLoaded = false
      this.convsError = ''
      this.sending = false
      this.checkingSend = false
    },
    '$route'() {
      this.ensureCanonicalRoute()
    }
  },
  methods: {
    ensureCanonicalRoute() {
      if (String(this.$route.path || '').replace(/\/+$/, '') !== '/customer/im') {
        this.$router.replace({ name: 'customerIm', query: Object.assign({}, this.$route.query) })
        return
      }
      this.loadConvs()
    },
    readerGuard() {
      const token = getToken()
      const id = String(this.$store.state.user.id)
      const epoch = this._sendEpoch || 0
      return () => token === getToken() && id === String(this.$store.state.user.id) &&
        epoch === (this._sendEpoch || 0)
    },
    loadConvs() {
      if (this.convsLoading) return
      this.convsLoading = true
      const isReader = this.readerGuard()
      return listImConversations()
        .then(res => {
          if (!isReader()) return
          this.convs = res.data || []
          this.convsLoaded = true
          this.convsError = ''
          this.convsUpdatedAt = this.parseTime(new Date(), '{y}-{m}-{d} {h}:{i}')
          if (this.current) {
            const c = this.convs.find(x => x.conversationId === this.current.conversationId)
            if (c) this.current = c
            else this.clearCurrentConversation()
          }
        })
        .catch(() => { if (isReader()) this.convsError = '客服会话读取失败，请检查网络后重试。' })
        .finally(() => { if (isReader()) this.convsLoading = false })
    },
    selectConv(c) {
      if (!c || !c.conversationId) return
      this.current = c
      this.sendText = ''
      this.pendingSend = null
      this.sendRecoveryError = ''
      this.restoreSendIntent()
      this.msgs = []
      this.messagesError = ''
      return this.loadMsgs()
    },
    loadMsgs() {
      if (!this.current || !this.current.conversationId) return Promise.resolve()
      const conversationId = this.current.conversationId
      const isReader = this.readerGuard()
      const requestId = ++this.messageRequestId
      this.messagesLoading = true
      this.messagesError = ''
      return listImMessages({ conversationId: conversationId, cursorId: 0, size: 50 }).then(res => {
        if (!isReader() || requestId !== this.messageRequestId || !this.current || String(this.current.conversationId) !== String(conversationId)) return
        this.msgs = (res.data && res.data.list) || []
        this.$nextTick(this.scrollBottom)
      }).catch(() => {
        if (!isReader() || requestId !== this.messageRequestId || !this.current || String(this.current.conversationId) !== String(conversationId)) return
        this.msgs = []
        this.messagesError = '会话消息读取失败，请重新读取。'
      }).finally(() => {
        if (isReader() && requestId === this.messageRequestId) this.messagesLoading = false
      })
    },
    clearCurrentConversation() {
      this.messageRequestId += 1
      this.current = null
      this.pendingSend = null
      this.sendText = ''
      this.sendRecoveryError = ''
      this.msgs = []
      this.messagesError = ''
      this.messagesLoading = false
    },
    scrollBottom() {
      const el = this.$refs.msgs
      if (el) el.scrollTop = el.scrollHeight
    },
    onEnter(e) {
      if (e.shiftKey) return
      this.doSend()
    },
    restoreSendIntent() {
      if (!this.current) return
      try {
        this.pendingSend = adminSendIntent(window.sessionStorage, this.$store.state.user.id,
          this.current.memberId, '', () => '')
        if (this.pendingSend) {
          this.sendText = this.pendingSend.content
          this.sendRecoveryError = '原消息结果待确认；重试将使用原请求号和原内容。'
        }
      } catch (e) { this.sendRecoveryError = e.message }
    },
    checkSendReceipt() {
      if (!this.pendingSend || !this.current || this.sending || this.checkingSend) return
      const intent = this.pendingSend
      const conversationId = this.current.conversationId
      const isReader = this.readerGuard()
      this.checkingSend = true
      return getImSendReceipt({ memberId: intent.memberId, requestId: intent.requestId }).then(res => {
        if (!isReader()) return
        const receipt = res && res.data
        if (res.code !== 200 || !receipt) throw new Error('原消息回执读取失败')
        if (!receipt.found) {
          if (this.isCurrentConversation(conversationId)) this.sendRecoveryError = '暂未查到原消息；结果仍待确认，原请求号和内容已保留。'
          return
        }
        if (receipt.requestId !== intent.requestId || String(receipt.memberId) !== intent.memberId ||
            receipt.msgType !== intent.msgType || receipt.content !== intent.content ||
            (receipt.extraJson != null && receipt.extraJson !== '') ||
            !/^[1-9][0-9]*$/.test(String(receipt.messageId))) throw new Error('原回执与冻结内容不一致，请保留原记录核查')
        clearAdminSendIntent(window.sessionStorage, intent)
        if (this.isCurrentConversation(conversationId) && this.pendingSend &&
            this.pendingSend.requestId === intent.requestId && this.pendingSend.memberId === intent.memberId) {
          this.pendingSend = null
          this.sendText = ''
          this.sendRecoveryError = receipt.status === 0 ? '' : '原消息已发送，当前已隐藏或删除；不会再次发送。'
          this.loadMsgs()
        }
        this.loadConvs()
      }).catch(e => {
        if (isReader() && this.isCurrentConversation(conversationId)) this.sendRecoveryError =
          (e && e.message ? e.message : '回执读取失败') + '；原请求已保留。'
      }).finally(() => { if (isReader()) this.checkingSend = false })
    },
    doSend() {
      if (this.sending || !this.current || this.checkingSend || this.current.blocked) return
      const conversationId = this.current.conversationId
      const token = getToken()
      const adminId = String(this.$store.state.user.id)
      const epoch = this._sendEpoch || 0
      let intent
      try {
        intent = adminSendIntent(window.sessionStorage, adminId, this.current.memberId,
          this.sendText, () => window.crypto.randomUUID())
        if (!intent) return
      } catch (e) { this.$modal.msgError(e.message); return }
      this.pendingSend = intent
      this.sendText = intent.content
      this.sending = true
      const current = () => epoch === (this._sendEpoch || 0) && token === getToken() &&
        adminId === String(this.$store.state.user.id)
      return sendImMessage({ memberId: intent.memberId, msgType: intent.msgType,
        content: intent.content, requestId: intent.requestId }).then(res => {
        if (!current()) return
        if (res.code !== 200) throw new Error(res.msg || '原消息结果待确认')
        clearAdminSendIntent(window.sessionStorage, intent)
        if (this.isCurrentConversation(conversationId)) {
          this.pendingSend = null
          this.sendText = ''
          this.sendRecoveryError = ''
          this.loadMsgs()
        }
        this.loadConvs()
      }).catch(e => {
        if (current() && this.isCurrentConversation(conversationId)) {
          this.sendRecoveryError = (e && e.message ? e.message + '；' : '') +
            '保留原请求号与内容，可按原请求重试。'
        }
      }).finally(() => { if (current()) this.sending = false })
    },
    isCurrentConversation(conversationId) {
      return Boolean(this.current) && String(this.current.conversationId) === String(conversationId)
    }
  }
}
</script>

<style scoped>
.im-side-hd, .im-main-hd { display: flex; align-items: center; justify-content: space-between; }
.im-phone { color: #909399; font-size: 12px; }
.im-conv-list { min-height: 136px; max-height: 70vh; overflow-y: auto; }
.im-conv { display: flex; align-items: center; padding: var(--cy-space-1-5) var(--cy-space-2); cursor: pointer; border-radius: var(--cy-radius-control); }
.im-conv:hover, .im-conv.active { background: #f0f7ff; }
.im-conv-body { flex: 1; margin-left: var(--cy-space-1-5); overflow: hidden; }
.im-conv-top { display: flex; align-items: center; justify-content: space-between; }
.im-conv-name { font-weight: 600; color: #303133; }
.im-conv-last { color: #909399; font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-top: 2px; }
.im-empty { padding: 60px 0; }
.im-msgs { height: 56vh; overflow-y: auto; padding: var(--cy-space-2) var(--cy-space-1); background: #fafafa; border-radius: var(--cy-radius-control); }
.im-msg { display: flex; flex-direction: column; margin: var(--cy-space-2) 0; }
.im-msg.me { align-items: flex-end; }
.im-msg.other { align-items: flex-start; }
.im-bubble { max-width: 60%; padding: var(--cy-space-2) var(--cy-space-3); border-radius: 10px; word-break: break-all; }
.im-msg.me .im-bubble { background: #409eff; color: var(--cy-color-text-inverse); }
.im-msg.other .im-bubble { background: var(--cy-color-bg-surface); color: #303133; border: 1px solid #ebeef5; }
.im-time { font-size: 11px; color: #c0c4cc; margin-top: 2px; }
.im-send { display: flex; align-items: flex-end; gap: var(--cy-space-1-5); margin-top: var(--cy-space-3); }
.im-send .el-textarea { flex: 1; }
</style>
