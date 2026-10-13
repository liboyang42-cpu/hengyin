<template>
  <main class="app-container club-console">
    <cy-page-header
      eyebrow="俱乐部与漫游"
      title="俱乐部运营工作台"
      description="从申请审核、主理人能力到承接主题，用一条业务链判断俱乐部是否具备履约能力。"
      status="运营中"
    />

    <section class="club-console__filters" aria-label="俱乐部筛选">
      <el-form ref="queryForm" :model="queryParams" size="small" :inline="true">
        <el-form-item label="俱乐部名称" prop="name">
          <el-input v-model="queryParams.name" placeholder="搜索名称" clearable @keyup.enter.native="handleQuery" />
        </el-form-item>
        <el-form-item label="审核状态" prop="status">
          <el-select v-model="queryParams.status" placeholder="全部状态" clearable>
            <el-option label="待审核" :value="0" />
            <el-option label="已通过" :value="1" />
            <el-option label="已驳回" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="handleQuery">查询</el-button>
          <el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </section>

    <section class="club-console__table">
      <div class="section-heading">
        <div><span>Club portfolio</span><h2>俱乐部档案与准入状态</h2></div>
        <strong>{{ total }} 个档案</strong>
      </div>
      <el-table v-loading="loading" :data="clubList" row-key="id" @row-dblclick="openWorkspace">
        <el-table-column label="俱乐部" min-width="230">
          <template slot-scope="{ row }">
            <div class="club-identity">
              <el-avatar :size="38" :src="row.logo"><span>{{ (row.name || '?').slice(0, 1) }}</span></el-avatar>
              <div><strong>{{ row.name || `俱乐部 #${row.id}` }}</strong><span>{{ row.city || row.address || '城市未填写' }}</span></div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="主理人" prop="leaderName" min-width="130" show-overflow-tooltip />
        <el-table-column label="能力画像" min-width="190">
          <template slot-scope="{ row }">
            <el-tag v-if="row.canDesignRoute === 1" size="mini">路线</el-tag>
            <el-tag v-if="row.canDesignTask === 1" size="mini">任务</el-tag>
            <el-tag v-if="row.canNpc === 1" size="mini">NPC</el-tag>
            <span v-if="!hasCapability(row)" class="muted">待补充</span>
          </template>
        </el-table-column>
        <el-table-column label="规模" width="110">
          <template slot-scope="{ row }">{{ row.memberCount || 0 }} 人</template>
        </el-table-column>
        <el-table-column label="审核" width="110">
          <template slot-scope="{ row }"><cy-status-tag :label="statusLabel(row.status)" :tone="statusTone(row.status)" /></template>
        </el-table-column>
        <el-table-column label="定级" width="90">
          <template slot-scope="{ row }"><strong>{{ row.leaderLevel ? `L${row.leaderLevel}` : '未定级' }}</strong></template>
        </el-table-column>
        <el-table-column label="申请时间" prop="createTime" width="165" />
        <el-table-column label="操作" width="290" fixed="right" class-name="small-padding fixed-width">
          <template slot-scope="{ row }">
            <el-button size="mini" type="text" icon="el-icon-view" v-hasPermi="['business:club:query']" @click="openWorkspace(row)">工作区</el-button>
            <el-button size="mini" type="text" icon="el-icon-edit" v-hasPermi="['business:club:edit']" @click="handleEdit(row)">编辑</el-button>
            <el-button v-if="row.status !== 1" size="mini" type="text" icon="el-icon-check" v-hasPermi="['business:club:audit']" @click="reviewClub(row, 1)">通过</el-button>
            <el-button size="mini" type="text" icon="el-icon-medal" v-hasPermi="['business:club:audit']" @click="handleGrade(row)">定级</el-button>
            <el-dropdown trigger="click" @command="command => handleMore(command, row)">
              <el-button size="mini" type="text">更多<i class="el-icon-arrow-down el-icon--right" /></el-button>
              <el-dropdown-menu slot="dropdown">
                <el-dropdown-item v-if="row.status !== 2" v-hasPermi="['business:club:audit']" command="reject">驳回申请</el-dropdown-item>
                <el-dropdown-item v-hasPermi="['business:club:remove']" command="delete" divided>删除俱乐部</el-dropdown-item>
              </el-dropdown-menu>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />
    </section>

    <el-drawer
      title="俱乐部运营工作区"
      :visible.sync="workspaceOpen"
      size="74%"
      append-to-body
      destroy-on-close
      custom-class="club-workspace-drawer"
    >
      <div v-loading="workspaceLoading" class="club-workspace">
        <cy-resource-state
          v-if="workspaceError"
          kind="error"
          :title="workspaceError"
          retry-text="重新读取"
          :retrying="workspaceLoading"
          @retry="retryWorkspace"
        />
        <template v-else>
          <header class="club-workspace__hero">
            <el-avatar :size="70" :src="current.logo"><span>{{ (current.name || '?').slice(0, 1) }}</span></el-avatar>
            <div class="club-workspace__title">
              <span>CLUB #{{ current.id || '-' }}</span>
              <h2>{{ current.name || '俱乐部档案' }}</h2>
              <p>{{ current.description || '尚未填写俱乐部简介。' }}</p>
            </div>
            <div class="club-workspace__state">
              <cy-status-tag :label="statusLabel(current.status)" :tone="statusTone(current.status)" />
              <strong>{{ leader.level ? `L${leader.level}` : '未定级' }}</strong>
            </div>
          </header>

          <div class="club-workspace__metrics">
            <cy-metric-card label="俱乐部成员" :value="String(current.memberCount || 0)" description="当前档案冗余口径" connected />
            <cy-metric-card label="承接主题" :value="topicAccessDenied ? '-' : String(topicList.length)" :description="topicAccessDenied ? '无主题列表权限' : '按 club_id 读取'" :connected="!topicAccessDenied" />
            <cy-metric-card label="常规规模" :value="capacityLabel" description="主理人申请口径" connected />
            <cy-metric-card label="资质材料" :value="String(certImages.length)" description="证书与案例图片" connected />
          </div>

          <el-tabs v-model="workspaceTab" class="club-workspace__tabs">
            <el-tab-pane label="基础档案" name="profile">
              <div class="profile-grid">
                <article><span>主理人</span><strong>{{ leader.leaderName || current.leaderName || '-' }}</strong><p>{{ leader.identity || '身份说明未填写' }}</p></article>
                <article><span>联系方式</span><strong>{{ leader.phone || current.phone || '-' }}</strong><p>{{ current.city || current.address || '城市未填写' }}</p></article>
                <article><span>俱乐部类型</span><strong>{{ current.clubType || current.clubTypes || '-' }}</strong><p>{{ current.style || '调性未填写' }}</p></article>
                <article><span>联合创始人</span><strong>{{ leader.coFounders || '-' }}</strong><p>{{ leader.hasExperience === 1 ? '有组织经验' : '未声明组织经验' }}</p></article>
              </div>
              <div class="text-card"><span>加入理由</span><p>{{ current.joinReason || '未填写' }}</p></div>
              <div class="text-card"><span>过往经验</span><p>{{ leader.experience || current.experience || '未填写' }}</p></div>
              <div class="tag-line"><span>运营标签</span><el-tag v-for="tag in splitValues(leader.tags || current.tags)" :key="tag" size="small">{{ tag }}</el-tag><em v-if="!splitValues(leader.tags || current.tags).length">未定标签</em></div>
            </el-tab-pane>

            <el-tab-pane label="能力与资质" name="capability">
              <div class="capability-grid">
                <article v-for="item in capabilities" :key="item.label" :class="{ active: item.enabled }">
                  <i :class="item.icon" /><div><strong>{{ item.label }}</strong><span>{{ item.enabled ? '具备能力' : '未声明' }}</span></div>
                </article>
              </div>
              <cy-evidence-panel
                eyebrow="Evidence"
                title="资质与案例材料"
                :items="clubEvidenceItems"
                empty-text="申请未上传图片材料"
              />
            </el-tab-pane>

            <el-tab-pane label="承接主题" name="topics">
              <el-alert v-if="topicAccessDenied" type="warning" title="当前账号没有主题列表权限，俱乐部查询权限不会自动扩大主题可见范围。" :closable="false" show-icon />
              <el-table v-else v-loading="topicLoading" :data="topicList" empty-text="该俱乐部暂无承接主题">
                <el-table-column label="主题" min-width="220"><template slot-scope="{ row }"><strong>{{ row.name || `主题 #${row.id}` }}</strong></template></el-table-column>
                <el-table-column label="审核" width="110"><template slot-scope="{ row }">{{ topicAuditLabel(row.checkStatus) }}</template></el-table-column>
                <el-table-column label="用户端" width="110"><template slot-scope="{ row }">{{ row.userStatus === 1 ? '已上架' : '未上架' }}</template></el-table-column>
                <el-table-column label="创建时间" prop="createTime" width="170" />
                <el-table-column label="操作" width="110"><template slot-scope="{ row }"><el-button type="text" v-hasPermi="['business:topic:query']" @click="openTopic(row)">主题工作区</el-button></template></el-table-column>
              </el-table>
            </el-tab-pane>

            <el-tab-pane label="带队履约" name="fulfillment">
              <div class="governance-note">
                <div><strong>活动取消退款</strong><span>部分已核销父订单的判额、人工凭证和第二人复核统一在资金退款台处理。</span></div>
                <el-button v-if="checkRefundReviewPermi" icon="el-icon-tickets" plain @click="openActivityCancelRefundQueue">查看本俱乐部工单</el-button>
              </div>
              <el-alert v-if="fulfillmentAccessDenied" type="warning" title="带队履约包含主题事实，需同时拥有俱乐部查询与主题列表权限。" :closable="false" show-icon />
              <template v-else>
                <section class="fulfillment-section">
                  <div class="governance-note"><div><strong>集合签到</strong><span>仅展示已生效承接下的场次；未触发带队流程的场次会保留为待激活。</span></div></div>
                  <el-table v-loading="fulfillmentLoading" :data="fulfillment.sessions || []" empty-text="该俱乐部暂无带队场次">
                    <el-table-column label="主题 / 场次" min-width="220"><template slot-scope="{ row }"><strong>{{ row.topicName || `主题 #${row.topicId}` }}</strong><small>{{ row.activityName || `场次 #${row.activityId}` }}</small></template></el-table-column>
                    <el-table-column label="场次时间 / 地点" min-width="205"><template slot-scope="{ row }"><div>{{ formatTime(row.assemblyTime) }}</div><small>{{ row.assemblyLocation || '未填写场次地点' }}</small></template></el-table-column>
                    <el-table-column label="责任领队" min-width="120"><template slot-scope="{ row }">{{ row.leaderDisplayName || '待分配' }}</template></el-table-column>
                    <el-table-column label="进度" width="110"><template slot-scope="{ row }"><cy-status-tag :label="leadStatusLabel(row.status)" :tone="row.status === 3 ? 'success' : row.status === 2 ? 'brand' : 'neutral'" /></template></el-table-column>
                    <el-table-column label="当前章节" min-width="110"><template slot-scope="{ row }">{{ row.currentChapterId ? `章节 #${row.currentChapterId}` : '尚未进入章节' }}</template></el-table-column>
                    <el-table-column label="签到名单" min-width="230"><template slot-scope="{ row }"><span v-if="row.attendees && row.attendees.length" class="attendance-chips"><el-tag v-for="attendee in row.attendees" :key="`${row.activityId}-${attendee.memberId}`" size="mini">{{ attendee.memberDisplayName }} · {{ formatTime(attendee.arrivedAt) }}</el-tag></span><span v-else class="muted">暂无签到</span></template></el-table-column>
                  </el-table>
                </section>
                <section class="fulfillment-section">
                  <div class="governance-note"><div><strong>团核销</strong><span>按商家接待该领队团的不可变核销记录回读，不在这里改写结算或核销事实。</span></div></div>
                  <el-table v-loading="fulfillmentLoading" :data="fulfillment.groupVerifications || []" empty-text="暂无团核销记录">
                    <el-table-column label="主题" min-width="190"><template slot-scope="{ row }">{{ row.topicName || `主题 #${row.topicId}` }}</template></el-table-column>
                    <el-table-column label="接待商家" min-width="150"><template slot-scope="{ row }">{{ row.merchantDisplayName || `成员 #${row.merchantMemberId}` }}</template></el-table-column>
                    <el-table-column label="带队责任人" min-width="150"><template slot-scope="{ row }">{{ row.leaderDisplayName || `成员 #${row.leaderMemberId}` }}</template></el-table-column>
                    <el-table-column label="核销时间" min-width="175"><template slot-scope="{ row }">{{ formatTime(row.verifyTime) }}</template></el-table-column>
                  </el-table>
                </section>
              </template>
            </el-tab-pane>

            <el-tab-pane label="成员治理" name="members">
              <el-alert type="info" title="主理人不属于普通成员表；管理员最多 2 名，角色变更继续由后端事务锁裁决。" :closable="false" show-icon />
              <el-table v-loading="governanceLoading" :data="memberList" class="governance-table" empty-text="该俱乐部暂无普通成员">
                <el-table-column label="成员" min-width="220">
                  <template slot-scope="{ row }">
                    <div class="club-identity">
                      <el-avatar :size="34" :src="row.avatar"><span>{{ (row.nickname || '?').slice(0, 1) }}</span></el-avatar>
                      <div><strong>{{ row.nickname || `会员 #${row.memberId}` }}</strong><span>会员 #{{ row.memberId }}</span></div>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="角色" width="120"><template slot-scope="{ row }"><cy-status-tag :label="row.role === 1 ? '管理员' : '成员'" :tone="row.role === 1 ? 'brand' : 'neutral'" /></template></el-table-column>
                <el-table-column label="状态" width="110"><template slot-scope="{ row }">{{ row.status === 1 ? '正常' : '非正常' }}</template></el-table-column>
                <el-table-column label="加入时间" prop="joinTime" width="170" />
                <el-table-column label="治理" width="210" fixed="right">
                  <template slot-scope="{ row }">
                    <el-button type="text" v-hasPermi="['business:club:audit']" @click="toggleMemberRole(row)">{{ row.role === 1 ? '取消管理员' : '设为管理员' }}</el-button>
                    <el-button type="text" class="danger-action" v-hasPermi="['business:club:audit']" @click="removeMember(row)">移除成员</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <el-tab-pane label="内容治理" name="content">
              <div class="governance-note">
                <div><strong>动态只在这里调证</strong><span>业务页不直接改下架状态；有风险的内容提交统一审核台，由证据链完成处置。</span></div>
                <el-button v-if="checkAuditListPermi" type="primary" plain icon="el-icon-s-check" @click="openAudit('CLUB_POST')">打开统一审核台</el-button>
              </div>
              <el-table v-loading="governanceLoading" :data="postList" class="governance-table" empty-text="该俱乐部暂无公开动态">
                <el-table-column label="动态内容" min-width="300">
                  <template slot-scope="{ row }"><div class="post-summary"><strong>动态 #{{ row.id }}</strong><p>{{ row.content | sanitize || (row.images ? '图片动态' : '无正文') }}</p></div></template>
                </el-table-column>
                <el-table-column label="作者" min-width="150"><template slot-scope="{ row }">{{ row.nickname || `会员 #${row.authorMemberId}` }}</template></el-table-column>
                <el-table-column label="互动" width="130"><template slot-scope="{ row }">{{ row.likeCount || 0 }} 赞 · {{ row.commentCount || 0 }} 评</template></el-table-column>
                <el-table-column label="发布时间" prop="createTime" width="170" />
                <el-table-column label="治理" width="150" fixed="right">
                  <template slot-scope="{ row }"><el-button type="text" v-hasPermi="['business:club:audit']" @click="submitPostReview(row)">提交统一审核</el-button></template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <el-tab-pane v-if="checkAuditListPermi" label="平台工单" name="platform-cases">
              <div class="governance-note">
                <div><strong>举报与申诉分层处理</strong><span>工单裁决只记录平台判断；批准成员举报后，仍需单独确认“永久封禁成员”，避免审核动作暗中扩大为处罚。</span></div>
                <el-button icon="el-icon-refresh" plain :loading="platformCasesLoading" @click="loadPlatformCases(current.id)">刷新工单</el-button>
              </div>
              <el-alert v-if="platformCasesError" type="error" :title="platformCasesError" :closable="false" show-icon />
              <el-table v-else v-loading="platformCasesLoading" :data="platformCases" class="governance-table" empty-text="该俱乐部暂无举报或申诉工单">
                <el-table-column label="工单" min-width="135">
                  <template slot-scope="{ row }"><strong>#{{ row.id }}</strong><div>{{ row.caseType === 'APPEAL' ? '封禁申诉' : '内容 / 成员举报' }}</div></template>
                </el-table-column>
                <el-table-column label="提交人与对象" min-width="190">
                  <template slot-scope="{ row }"><div>提交人 #{{ row.filerMemberId }}</div><small>对象：{{ governanceTargetLabel(row) }}</small></template>
                </el-table-column>
                <el-table-column label="理由与证据" min-width="300">
                  <template slot-scope="{ row }"><div class="case-reason">{{ row.reason || '未填写理由' }}</div><pre v-if="row.evidenceJson && row.evidenceJson !== '{}'">{{ formatEvidence(row.evidenceJson) }}</pre><small v-else>无附加证据</small></template>
                </el-table-column>
                <el-table-column label="状态" width="115">
                  <template slot-scope="{ row }"><cy-status-tag :label="governanceCaseStatusLabel(row.status)" :tone="governanceCaseStatusTone(row.status)" /></template>
                </el-table-column>
                <el-table-column label="提交时间" width="170"><template slot-scope="{ row }">{{ formatTime(row.createTime) }}</template></el-table-column>
                <el-table-column label="平台处理" width="260" fixed="right">
                  <template slot-scope="{ row }">
                    <template v-if="checkAuditReviewPermi && row.status === 'PENDING'">
                      <el-button type="text" @click="startGovernanceAction('approve', row)">批准工单</el-button>
                      <el-button type="text" class="danger-action" @click="startGovernanceAction('reject', row)">驳回工单</el-button>
                    </template>
                    <el-button
                      v-if="checkAuditReviewPermi && row.status === 'APPROVED' && row.caseType === 'REPORT' && row.targetType === 'MEMBER'"
                      type="text"
                      class="danger-action"
                      @click="startGovernanceAction('ban', row)"
                    >永久封禁成员</el-button>
                    <span v-if="!checkAuditReviewPermi" class="muted">仅可查看</span>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </template>
      </div>
    </el-drawer>

    <el-dialog title="编辑俱乐部档案" :visible.sync="editOpen" width="680px" append-to-body>
      <el-form ref="editForm" :model="editForm" label-width="100px" size="small">
        <el-row :gutter="16">
          <el-col :span="12"><el-form-item label="俱乐部名称"><el-input v-model="editForm.name" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="主理人"><el-input v-model="editForm.leaderName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="联系电话"><el-input v-model="editForm.phone" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="所在城市"><el-input v-model="editForm.city" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="简介"><el-input v-model="editForm.description" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="俱乐部类型"><el-input v-model="editForm.clubTypes" placeholder="逗号分隔" /></el-form-item>
        <el-form-item label="活动倾向"><el-input v-model="editForm.activityPrefs" placeholder="逗号分隔" /></el-form-item>
        <el-form-item label="活动经验"><el-input v-model="editForm.experience" type="textarea" :rows="2" /></el-form-item>
        <el-divider content-position="left">会员经营配置</el-divider>
        <el-form-item label="成员优先报名">
          <el-switch v-model="editForm.prioritySignupEnabled" :active-value="1" :inactive-value="0" active-text="开启" inactive-text="关闭" />
          <p class="form-hint">开启后，普通玩家不能占用下方配置的会员保留名额。</p>
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12"><el-form-item label="会员优惠价"><el-input v-model="editForm.memberDiscountPrice" clearable placeholder="留空则不启用"><template slot="append">元</template></el-input></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="会员保留名额"><el-input-number v-model="editForm.memberReservedQuota" :min="0" :precision="0" controls-position="right" style="width:100%" /></el-form-item></el-col>
        </el-row>
        <el-alert type="info" title="优惠价对该俱乐部归属主题下的活动生效；报名服务端会重新计算价格与保留名额。" :closable="false" show-icon />
        <el-alert type="info" title="普通编辑只维护申请档案；审核状态、主理人等级和标签必须走独立动作。" :closable="false" show-icon />
      </el-form>
      <span slot="footer"><el-button @click="editOpen = false">取消</el-button><el-button type="primary" :loading="editSaving" @click="submitEdit">保存档案</el-button></span>
    </el-dialog>

    <el-dialog title="主理人定级与标签" :visible.sync="gradeOpen" width="520px" append-to-body>
      <el-form :model="gradeForm" label-width="80px">
        <el-form-item label="等级">
          <el-select v-model="gradeForm.level" placeholder="选择 L1-L5" style="width:100%">
            <el-option v-for="level in 5" :key="level" :label="`L${level} ${levelLabel(level)}`" :value="level" />
          </el-select>
        </el-form-item>
        <el-form-item label="标签"><el-input v-model="gradeForm.tags" placeholder="逗号分隔，如：城市探索,商业联动" /></el-form-item>
      </el-form>
      <span slot="footer"><el-button @click="gradeOpen = false">取消</el-button><el-button type="primary" :loading="gradeSaving" @click="submitGrade">确认定级</el-button></span>
    </el-dialog>

    <cy-confirm-action
      :visible.sync="rejectOpen"
      title="驳回俱乐部申请"
      :impact="rejectImpact"
      severity="danger"
      confirm-text="确认驳回"
      reason-label="驳回原因"
      reason-placeholder="填写可执行的驳回原因"
      :reason-maxlength="200"
      :loading="rejectSaving"
      @confirm="submitReject"
    />

    <cy-confirm-action
      :visible.sync="governanceActionOpen"
      :title="governanceActionTitle"
      :impact="governanceActionImpact"
      :severity="governanceActionKind === 'ban' || governanceActionKind === 'reject' ? 'danger' : 'warning'"
      :confirm-text="governanceActionConfirmText"
      reason-label="平台处理说明"
      reason-placeholder="填写可回溯、可执行的平台处理依据"
      :reason-maxlength="500"
      :loading="governanceActionSaving"
      @confirm="submitGovernanceAction"
    />
  </main>
</template>

<script>
import {
  auditClub, delClub, getClub, getClubFulfillment, getClubGovernance, getClubWorkspace, leaderAudit, listClub,
  listClubGovernanceCases, permanentBanClubMember, removeClubMember, reviewClubGovernanceCase,
  submitClubPostForReview, updateClub, updateClubMemberRole
} from '@/api/business/club'
import { listTopic } from '@/api/business/topic'
import { checkPermi } from '@/utils/permission'
import CyConfirmAction from '@/components/CyConfirmAction'
import CyMetricCard from '@/components/CyMetricCard'
import CyEvidencePanel from '@/components/CyEvidencePanel'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'
import CyStatusTag from '@/components/CyStatusTag'

export default {
  name: 'Club',
  components: { CyConfirmAction, CyEvidencePanel, CyMetricCard, CyPageHeader, CyResourceState, CyStatusTag },
  data() {
    return {
      loading: false,
      total: 0,
      clubList: [],
      queryParams: { pageNum: 1, pageSize: 10, name: null, status: null },
      workspaceOpen: false,
      workspaceLoading: false,
      workspaceError: '',
      workspaceTab: 'profile',
      current: {},
      leader: {},
      topicList: [],
      topicLoading: false,
      topicAccessDenied: false,
      memberList: [],
      postList: [],
      governanceLoading: false,
      platformCases: [],
      platformCasesLoading: false,
      platformCasesError: '',
      governanceActionOpen: false,
      governanceActionKind: '',
      governanceActionTarget: null,
      governanceActionRequestId: '',
      governanceActionSaving: false,
      fulfillment: { sessions: [], groupVerifications: [] },
      fulfillmentLoading: false,
      fulfillmentAccessDenied: false,
      editOpen: false,
      editSaving: false,
      editForm: {},
      gradeOpen: false,
      gradeSaving: false,
      gradeForm: { memberId: null, level: null, tags: null },
      rejectOpen: false,
      rejectTarget: null,
      rejectSaving: false
    }
  },
  computed: {
    capabilities() {
      const source = Object.keys(this.leader).length ? this.leader : this.current
      return [
        { label: '路线设计', key: 'canDesignRoute', icon: 'el-icon-map-location' },
        { label: '任务设计', key: 'canDesignTask', icon: 'el-icon-edit-outline' },
        { label: 'NPC / 角色', key: 'canNpc', icon: 'el-icon-user' },
        { label: '商家联动', key: 'canMerchantCoop', icon: 'el-icon-connection' },
        { label: '导游资质', key: 'hasGuideCert', icon: 'el-icon-document-checked' }
      ].map(item => ({ ...item, enabled: source[item.key] === 1 }))
    },
    certImages() { return this.splitValues(this.leader.certImages || this.current.certImages) },
    evidenceImages() {
      return [...this.certImages, ...this.splitValues(this.current.caseImages)].filter((url, index, all) => all.indexOf(url) === index)
    },
    clubEvidenceItems() {
      return this.evidenceImages.map(src => ({ kind: 'image', src, alt: '俱乐部资质或案例材料' }))
    },
    rejectImpact() {
      if (!this.rejectTarget) return ''
      const name = this.rejectTarget.name || `俱乐部 #${this.rejectTarget.id}`
      if (this.rejectTarget.status === 1) {
        return `驳回后，“${name}”将撤销现有准入资格；若主理人名下没有其他在营俱乐部，系统还会回收主理人能力。此操作不会自动恢复，恢复须另行审核通过。`
      }
      return `驳回后，“${name}”不会获得准入资格；填写原因会保存为俱乐部档案的驳回原因。此操作不会自动撤销，恢复须另行审核通过。`
    },
    capacityLabel() {
      const average = this.leader.avgEventSize || this.current.avgEventSize
      const maximum = this.leader.maxEventSize || this.current.maxEventSize
      return average || maximum ? `${average || '-'} / ${maximum || '-'} 人` : '-'
    },
    checkAuditListPermi() { return checkPermi(['business:audit:list']) },
    checkAuditReviewPermi() { return checkPermi(['business:audit:review']) },
    checkRefundReviewPermi() { return checkPermi(['business:refund:review']) },
    governanceActionTitle() {
      return ({ approve: '批准治理工单', reject: '驳回治理工单', ban: '永久封禁俱乐部成员' })[this.governanceActionKind] || '平台治理确认'
    },
    governanceActionConfirmText() {
      return ({ approve: '确认批准', reject: '确认驳回', ban: '确认永久封禁' })[this.governanceActionKind] || '确认执行'
    },
    governanceActionImpact() {
      const row = this.governanceActionTarget
      if (!row) return '平台治理动作会被写入审计链。'
      if (this.governanceActionKind === 'approve') {
        return row.caseType === 'APPEAL'
          ? `批准申诉工单 #${row.id} 将解除对应有效封禁；不会改写其他治理事实。`
          : `批准举报工单 #${row.id} 只记录平台判断，不会自动封禁成员；需要处罚时须另行确认。`
      }
      if (this.governanceActionKind === 'reject') return `驳回工单 #${row.id} 后，该工单不能再次裁决。`
      return `成员 #${row.targetId} 将被永久封禁并从俱乐部移除；会员权益只按后端回执处理，不在此承诺退款。`
    }
  },
  watch: {
    '$route'() {
      this.ensureCanonicalRoute();
    }
  },
  created() {
    this.ensureCanonicalRoute();
  },
  methods: {
    ensureCanonicalRoute() {
      if (String(this.$route.path || '').replace(/\/+$/, '') !== '/club/profile') {
        this.$router.replace({ name: 'clubProfile', query: Object.assign({}, this.$route.query) });
        return;
      }
      this.getList();
    },
    getList() {
      this.loading = true
      return listClub(this.queryParams)
        .then(response => { this.clubList = response.rows || []; this.total = response.total || 0 })
        .finally(() => { this.loading = false })
    },
    handleQuery() { this.queryParams.pageNum = 1; this.getList() },
    resetQuery() { this.queryParams = { pageNum: 1, pageSize: 10, name: null, status: null }; this.getList() },
    statusLabel(status) { return ({ 0: '待审核', 1: '已通过', 2: '已驳回' })[status] || '状态未知' },
    statusTone(status) { return ({ 0: 'warning', 1: 'success', 2: 'danger' })[status] || 'neutral' },
    topicAuditLabel(status) { return ({ 0: '待审核', 1: '已通过', 2: '已驳回', 3: '升级处理' })[status] || '未知' },
    leadStatusLabel(status) { return status == null ? '待激活' : (({ 1: '集合中', 2: '行进中', 3: '已结算' })[status] || '状态未知') },
    formatTime(value) { return value ? String(value).replace('T', ' ') : '-' },
    hasCapability(row) { return [row.canDesignRoute, row.canDesignTask, row.canNpc, row.canMerchantCoop].some(value => value === 1) },
    splitValues(value) { return String(value || '').split(',').map(item => item.trim()).filter(Boolean) },
    levelLabel(level) { return ['新手', '稳定', '成熟', '城市运营者', '区域策划方'][level - 1] },
    openWorkspace(row) {
      this.workspaceOpen = true
      this.workspaceLoading = true
      this.workspaceError = ''
      this.workspaceTab = 'profile'
      this.current = { ...row }
      this.leader = {}
      this.topicList = []
      this.memberList = []
      this.postList = []
      this.platformCases = []
      this.platformCasesError = ''
      this.fulfillment = { sessions: [], groupVerifications: [] }
      this.fulfillmentAccessDenied = false
      return getClubWorkspace(row.id)
        .then(response => {
          const data = response.data || {}
          this.current = data.club || row
          this.leader = data.leader || {}
          return Promise.all([
            this.loadTopics(row.id), this.loadGovernance(row.id), this.loadFulfillment(row.id),
            this.loadPlatformCases(row.id)
          ])
        })
        .catch(() => { this.workspaceError = '俱乐部工作区读取失败，请检查网络或查询权限后重试。' })
        .finally(() => { this.workspaceLoading = false })
    },
    retryWorkspace() {
      if (this.workspaceLoading || !this.current.id) return
      return this.openWorkspace(this.current)
    },
    loadTopics(clubId) {
      this.topicAccessDenied = !checkPermi(['business:topic:list'])
      if (this.topicAccessDenied) return Promise.resolve()
      this.topicLoading = true
      return listTopic({ pageNum: 1, pageSize: 100, clubId })
        .then(response => { this.topicList = response.rows || [] })
        .catch(() => { this.topicList = [] })
        .finally(() => { this.topicLoading = false })
    },
    loadGovernance(clubId) {
      this.governanceLoading = true
      return getClubGovernance(clubId)
        .then(response => {
          const data = response.data || {}
          this.memberList = data.members || []
          this.postList = data.posts || []
        })
        .catch(() => { this.memberList = []; this.postList = [] })
        .finally(() => { this.governanceLoading = false })
    },
    loadFulfillment(clubId) {
      this.fulfillmentAccessDenied = !(checkPermi(['business:club:query']) && checkPermi(['business:topic:list']))
      if (this.fulfillmentAccessDenied) return Promise.resolve()
      this.fulfillmentLoading = true
      return getClubFulfillment(clubId)
        .then(response => { this.fulfillment = response.data || { sessions: [], groupVerifications: [] } })
        .catch(() => { this.fulfillment = { sessions: [], groupVerifications: [] } })
        .finally(() => { this.fulfillmentLoading = false })
    },
    loadPlatformCases(clubId) {
      this.platformCasesError = ''
      if (!this.checkAuditListPermi || !clubId) {
        this.platformCases = []
        return Promise.resolve()
      }
      this.platformCasesLoading = true
      return listClubGovernanceCases({ clubId })
        .then(response => { this.platformCases = response.data || [] })
        .catch(() => {
          this.platformCases = []
          this.platformCasesError = '平台治理工单读取失败，请检查审核权限或网络后重试。'
        })
        .finally(() => { this.platformCasesLoading = false })
    },
    governanceCaseStatusLabel(status) {
      return ({ PENDING: '待裁决', APPROVED: '已批准', REJECTED: '已驳回' })[status] || '状态未知'
    },
    governanceCaseStatusTone(status) {
      return ({ PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger' })[status] || 'neutral'
    },
    governanceTargetLabel(row) {
      if (!row) return '-'
      if (row.targetType === 'MEMBER') return `成员 #${row.targetId}`
      if (row.targetType === 'POST') return `动态 #${row.targetId}（作者 #${row.subjectMemberId}）`
      if (row.targetType === 'BAN') return `封禁记录 #${row.targetId}`
      return `${row.targetType || '未知对象'} #${row.targetId || '-'}`
    },
    formatEvidence(value) {
      try {
        const parsed = JSON.parse(value)
        return Object.keys(parsed).map(key => `${key}: ${parsed[key]}`).join('\n') || '无附加证据'
      } catch (error) {
        return '证据格式异常，请通过接口原始回执核对'
      }
    },
    startGovernanceAction(kind, row) {
      if (!this.checkAuditReviewPermi || !['approve', 'reject', 'ban'].includes(kind) || !row) return
      if (kind === 'ban' && !(row.status === 'APPROVED' && row.caseType === 'REPORT'
          && row.targetType === 'MEMBER' && row.targetId)) return
      if (kind !== 'ban' && row.status !== 'PENDING') return
      this.governanceActionKind = kind
      this.governanceActionTarget = row
      this.governanceActionRequestId = `club-gov-${kind}-${row.id}-${Date.now().toString(36)}`
      this.governanceActionOpen = true
    },
    submitGovernanceAction(reason) {
      const row = this.governanceActionTarget
      const kind = this.governanceActionKind
      if (!row || !this.checkAuditReviewPermi) return
      this.governanceActionSaving = true
      const action = kind === 'ban'
        ? permanentBanClubMember({
          clubId: row.clubId,
          targetMemberId: row.targetId,
          reason,
          requestId: this.governanceActionRequestId
        })
        : reviewClubGovernanceCase({
          caseId: row.id,
          version: row.version,
          approved: kind === 'approve',
          reason,
          requestId: this.governanceActionRequestId
        })
      return action
        .then(() => {
          this.governanceActionOpen = false
          if (kind === 'ban') this.$modal.msgSuccess('成员已永久封禁并移出俱乐部')
          else if (kind === 'approve' && row.caseType === 'APPEAL') this.$modal.msgSuccess('申诉已批准，对应有效封禁已解除')
          else if (kind === 'approve') this.$modal.msgSuccess('工单已批准；成员处罚需单独执行')
          else this.$modal.msgSuccess('工单已驳回')
          return this.loadPlatformCases(row.clubId)
        })
        .finally(() => { this.governanceActionSaving = false })
    },
    openTopic(row) { this.$router.push({ path: '/topic/workspace', query: { id: row.id } }) },
    openAudit(bizType) { this.$router.push({ name: 'Audit', query: { bizType } }) },
    openActivityCancelRefundQueue() {
      if (!this.checkRefundReviewPermi || !this.current.id) return
      this.$router.push({ name: 'financeRefund', query: { queue: 'activity-cancel', clubId: this.current.id } })
    },
    toggleMemberRole(row) {
      const nextRole = row.role === 1 ? 0 : 1
      const action = nextRole === 1 ? '设为管理员' : '取消管理员'
      return this.$modal.confirm(`确认将“${row.nickname || `会员 #${row.memberId}`}”${action}？`)
        .then(() => updateClubMemberRole(this.current.id, row.memberId, nextRole))
        .then(() => { this.$modal.msgSuccess(`${action}成功`); return this.loadGovernance(this.current.id) })
    },
    removeMember(row) {
      return this.$modal.confirm(`确认从俱乐部移除“${row.nickname || `会员 #${row.memberId}`}”？`)
        .then(() => removeClubMember(this.current.id, row.memberId))
        .then(() => { this.$modal.msgSuccess('成员已移除'); return this.loadGovernance(this.current.id) })
    },
    submitPostReview(row) {
      return this.$modal.confirm(`确认把动态 #${row.id} 提交统一审核？业务页不会直接下架。`)
        .then(() => submitClubPostForReview(this.current.id, row.id))
        .then(response => {
          const taskId = response.data && response.data.taskId
          this.$modal.msgSuccess(taskId ? `已进入审核队列 #${taskId}` : '已进入审核队列')
          if (this.checkAuditListPermi) this.openAudit('CLUB_POST')
        })
    },
    handleEdit(row) {
      return getClub(row.id).then(response => {
        const source = response.data || row
        this.editForm = {
          id: source.id, name: source.name, leaderName: source.leaderName, phone: source.phone,
          city: source.city, description: source.description, clubTypes: source.clubTypes,
          activityPrefs: source.activityPrefs, experience: source.experience,
          prioritySignupEnabled: Number(source.prioritySignupEnabled) === 1 ? 1 : 0,
          memberDiscountPrice: source.memberDiscountPrice === null || source.memberDiscountPrice === undefined
            ? null : String(source.memberDiscountPrice),
          memberReservedQuota: Number(source.memberReservedQuota || 0),
          operationConfigUpdated: true
        }
        this.editOpen = true
      })
    },
    submitEdit() {
      this.editSaving = true
      const discount = this.editForm.memberDiscountPrice
      const payload = {
        ...this.editForm,
        memberDiscountPrice: discount === '' || discount === null || discount === undefined ? null : Number(discount)
      }
      return updateClub(payload)
        .then(() => { this.$modal.msgSuccess('俱乐部档案已保存'); this.editOpen = false; return this.getList() })
        .finally(() => { this.editSaving = false })
    },
    reviewClub(row, status) {
      return this.$modal.confirm(`确认通过俱乐部“${row.name}”的准入申请？`)
        .then(() => auditClub({ id: row.id, status }))
        .then(() => { this.$modal.msgSuccess('俱乐部已通过审核'); return this.getList() })
    },
    handleGrade(row) {
      this.gradeForm = { memberId: row.memberId, level: row.leaderLevel || null, tags: row.leaderTags || null }
      this.gradeOpen = true
    },
    submitGrade() {
      if (!this.gradeForm.memberId) return this.$modal.msgError('缺少主理人标识')
      if (!this.gradeForm.level) return this.$modal.msgError('请选择 L1-L5 等级')
      this.gradeSaving = true
      return leaderAudit(this.gradeForm)
        .then(() => { this.$modal.msgSuccess('主理人定级已更新'); this.gradeOpen = false; return this.getList() })
        .finally(() => { this.gradeSaving = false })
    },
    handleMore(command, row) {
      if (command === 'delete') return this.handleDelete(row)
      if (command === 'reject') {
        this.rejectTarget = row
        this.rejectOpen = true
      }
    },
    submitReject(reason) {
      this.rejectSaving = true
      return auditClub({ id: this.rejectTarget.id, status: 2, rejectReason: reason })
        .then(() => { this.$modal.msgSuccess('申请已驳回'); this.rejectOpen = false; return this.getList() })
        .finally(() => { this.rejectSaving = false })
    },
    handleDelete(row) {
      return this.$modal.confirm(`确认删除俱乐部“${row.name}”？该动作不可用于代替审核驳回。`)
        .then(() => delClub(row.id))
        .then(() => { this.$modal.msgSuccess('俱乐部已删除'); return this.getList() })
    }
  }
}
</script>

<style lang="scss" scoped>
.club-console { max-width: 1680px; margin: 0 auto; }
.club-console__filters, .club-console__table { margin-bottom: var(--cy-space-3-5); padding: var(--cy-space-5) var(--cy-space-4-5); border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-surface); box-shadow: var(--cy-shadow-card); }
.club-console__filters { padding-bottom: 2px; }
.section-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--cy-space-4); margin-bottom: var(--cy-space-3-5); }
.section-heading span, .club-workspace__title > span { color: var(--cy-color-brand); font-size: 11px; font-weight: 800; letter-spacing: .12em; text-transform: uppercase; }
.section-heading h2, .section-heading h3 { margin: var(--cy-space-1) 0 0; color: var(--cy-color-text-primary); }
.section-heading > strong { color: var(--cy-color-text-secondary); font-size: 13px; }
.club-identity { display: flex; align-items: center; gap: var(--cy-space-3); text-align: left; }
.club-identity div { display: flex; min-width: 0; flex-direction: column; gap: var(--cy-space-1); }
.club-identity strong { overflow: hidden; color: var(--cy-color-text-primary); text-overflow: ellipsis; white-space: nowrap; }
.club-identity span, .muted { color: var(--cy-color-text-tertiary); font-size: 12px; }
.club-console__table ::v-deep .el-tag + .el-tag { margin-left: 4px; }
.club-console__table ::v-deep .el-dropdown { margin-left: var(--cy-space-1-5); }
.club-workspace { min-height: calc(100vh - 130px); padding: 0 28px 30px; }
.club-workspace__hero { display: flex; align-items: center; gap: var(--cy-space-3-5); padding: var(--cy-space-6); border-radius: var(--cy-radius-card); background: linear-gradient(135deg, var(--cy-color-brand-soft), var(--cy-color-bg-surface)); }
.club-workspace__title { min-width: 0; flex: 1; }
.club-workspace__title h2 { margin: var(--cy-legacy-5) 0; color: var(--cy-color-text-primary); font-size: 26px; }
.club-workspace__title p { margin: 0; color: var(--cy-color-text-secondary); line-height: 1.65; }
.club-workspace__state { display: flex; align-items: flex-end; flex-direction: column; gap: var(--cy-space-3); }
.club-workspace__state strong { color: var(--cy-color-brand); font-size: 24px; }
.club-workspace__metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--cy-space-3); margin: var(--cy-space-3-5) 0; }
.club-workspace__tabs { padding: 0 var(--cy-space-5) var(--cy-space-4-5); border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-surface); }
.club-workspace__tabs ::v-deep .el-tabs__item { height: 54px; color: var(--cy-color-text-secondary); font-weight: 700; line-height: 54px; }
.club-workspace__tabs ::v-deep .el-tabs__item.is-active { color: var(--cy-color-brand); }
.profile-grid, .capability-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--cy-space-3); }
.profile-grid article, .text-card, .capability-grid article { padding: var(--cy-space-3-5); border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-dialog); background: var(--cy-color-bg-subtle); }
.profile-grid span, .text-card span, .tag-line > span { display: block; margin-bottom: var(--cy-space-2); color: var(--cy-color-text-tertiary); font-size: 12px; font-weight: 700; }
.profile-grid strong { display: block; color: var(--cy-color-text-primary); font-size: 17px; }
.profile-grid p, .text-card p { margin: var(--cy-space-0-5) 0 0; color: var(--cy-color-text-secondary); line-height: 1.7; white-space: pre-wrap; }
.text-card { margin-top: var(--cy-space-3); }
.tag-line { display: flex; align-items: center; flex-wrap: wrap; gap: var(--cy-space-2); margin-top: var(--cy-space-2-5); }
.tag-line > span { width: 100%; margin-bottom: 0; }
.tag-line em { color: var(--cy-color-text-tertiary); font-style: normal; }
.capability-grid article { display: flex; align-items: center; gap: var(--cy-space-2-5); opacity: .62; }
.capability-grid article.active { border-color: var(--cy-color-brand); background: var(--cy-color-brand-soft); opacity: 1; }
.capability-grid i { color: var(--cy-color-brand); font-size: 24px; }
.capability-grid div { display: flex; flex-direction: column; gap: var(--cy-space-1); }
.capability-grid span { color: var(--cy-color-text-secondary); font-size: 12px; }
.governance-table { margin-top: var(--cy-space-4); }
.fulfillment-section + .fulfillment-section { margin-top: var(--cy-space-4-5); }
.fulfillment-section .governance-note { margin-bottom: var(--cy-space-2-5); }
.fulfillment-section small { display: block; margin-top: var(--cy-space-1); color: var(--cy-color-text-tertiary); font-size: 12px; }
.attendance-chips { display: flex; flex-wrap: wrap; gap: var(--cy-legacy-5); }
.governance-note { display: flex; align-items: center; justify-content: space-between; gap: var(--cy-space-3-5); padding: var(--cy-space-4) var(--cy-space-3-5); border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-dialog); background: var(--cy-color-bg-subtle); }
.governance-note div { display: flex; min-width: 0; flex-direction: column; gap: var(--cy-legacy-5); }
.governance-note strong { color: var(--cy-color-text-primary); }
.governance-note span, .post-summary p { color: var(--cy-color-text-secondary); font-size: 13px; line-height: 1.6; }
.governance-note + .el-alert { margin-top: var(--cy-space-3); }
.case-reason { color: var(--cy-color-text-primary); line-height: 1.6; white-space: pre-wrap; }
.governance-table pre { max-height: 96px; margin: var(--cy-space-1) 0 0; overflow: auto; color: var(--cy-color-text-secondary); font-family: inherit; font-size: 12px; line-height: 1.55; white-space: pre-wrap; word-break: break-word; }
.governance-table small { color: var(--cy-color-text-tertiary); font-size: 12px; }
.post-summary strong { color: var(--cy-color-text-primary); }
.post-summary p { display: -webkit-box; margin: var(--cy-space-1) 0 0; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.danger-action { color: var(--cy-color-status-danger); }
.form-hint { margin: var(--cy-space-0-5) 0 0; color: var(--cy-color-text-tertiary); font-size: 12px; line-height: 1.5; }
@media (max-width: 1100px) { .club-workspace__metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 720px) { .club-workspace__hero, .governance-note { align-items: flex-start; flex-wrap: wrap; } .club-workspace__state { align-items: flex-start; } .club-workspace__metrics, .profile-grid, .capability-grid { grid-template-columns: 1fr; } }
</style>
