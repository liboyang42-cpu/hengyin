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

            <div class="im-send">
              <el-input
                v-model="sendText"
                type="textarea"
                :rows="2"
                placeholder="输入消息，回车发送（Shift+回车换行）"
                @keyup.enter.native.exact="onEnter"
                :disabled="current.blocked"
              />
              <el-button
                type="primary"
                v-hasPermi="['business:im:send']"
                :loading="sending"
                :disabled="!sendText.trim() || current.blocked"
                @click="doSend"
              >发送</el-button>
            </div>
          </template>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script>
import { listImConversations, listImMessages, sendImMessage } from '@/api/business/im'
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
      sending: false
    }
  },
  created() {
    this.ensureCanonicalRoute()
  },
  watch: {
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
    loadConvs() {
      if (this.convsLoading) return
      this.convsLoading = true
      return listImConversations()
        .then(res => {
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
        .catch(() => { this.convsError = '客服会话读取失败，请检查网络后重试。' })
        .finally(() => { this.convsLoading = false })
    },
    selectConv(c) {
      if (!c || !c.conversationId) return
      this.current = c
      this.msgs = []
      this.messagesError = ''
      return this.loadMsgs()
    },
    loadMsgs() {
      if (!this.current || !this.current.conversationId) return Promise.resolve()
      const conversationId = this.current.conversationId
      const requestId = ++this.messageRequestId
      this.messagesLoading = true
      this.messagesError = ''
      return listImMessages({ conversationId: conversationId, cursorId: 0, size: 50 }).then(res => {
        if (requestId !== this.messageRequestId || !this.current || String(this.current.conversationId) !== String(conversationId)) return
        this.msgs = (res.data && res.data.list) || []
        this.$nextTick(this.scrollBottom)
      }).catch(() => {
        if (requestId !== this.messageRequestId || !this.current || String(this.current.conversationId) !== String(conversationId)) return
        this.msgs = []
        this.messagesError = '会话消息读取失败，请重新读取。'
      }).finally(() => {
        if (requestId === this.messageRequestId) this.messagesLoading = false
      })
    },
    clearCurrentConversation() {
      this.messageRequestId += 1
      this.current = null
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
    doSend() {
      const content = (this.sendText || '').trim()
      if (!content || this.sending || !this.current) return
      const conversationId = this.current.conversationId
      const memberId = this.current.memberId
      this.sending = true
      return sendImMessage({ memberId: memberId, msgType: 1, content: content }).then(res => {
        if (res.code === 200) {
          if (this.isCurrentConversation(conversationId)) {
            this.sendText = ''
            this.loadMsgs()
          }
          this.loadConvs()
        } else {
          this.$modal.msgError(res.msg || '发送失败')
        }
      }).catch(() => {
        this.$modal.msgError('发送失败，请稍后重试。')
      }).finally(() => {
        this.sending = false
      })
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
