<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="88px">
      <el-form-item label="审核状态" prop="checkStatus">
        <DictRadio v-model="queryParams.checkStatus" @change="handleQuery" size="small"
                   :radioData="dict.type.sys_topic_status" :showAll="'all'"/>
      </el-form-item>
      <el-form-item label="推荐状态" prop="isRecommend">
        <DictRadio v-model="queryParams.isRecommend" @change="handleQuery" size="small"
                   :radioData="dict.type.sys_recommend" :showAll="'all'"/>
      </el-form-item>
      <el-form-item label="内容主体" prop="contentScope">
        <el-select
          v-model="queryParams.contentScope"
          placeholder="全部来源"
          :clearable="!routeContentScope"
          :disabled="Boolean(routeContentScope)"
          @change="handleQuery"
        >
          <el-option label="官方主题" value="OFFICIAL" />
          <el-option label="主题广场（俱乐部 / 会员）" value="PLAZA" />
          <el-option label="历史待归属" value="HISTORICAL" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="isSourceEntry()" label="跨来源检索">
        <el-checkbox v-model="crossSourceSearch" @change="handleQuery">官方主题 + 主题广场</el-checkbox>
      </el-form-item>
      <el-form-item v-if="isPlazaEntry()" label="广场子队列">
        <el-radio-group v-model="plazaPublisherType" size="small" :disabled="crossSourceSearch" @change="handleQuery">
          <el-radio-button label="ALL">全部</el-radio-button>
          <el-radio-button label="CLUB">俱乐部发布</el-radio-button>
          <el-radio-button label="MEMBER">会员发布</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="玩法" prop="productType">
        <el-select v-model="queryParams.productType" clearable placeholder="全部玩法" @change="handleQuery">
          <el-option label="线性城市定向" :value="1" />
          <el-option label="主题内自由探索" :value="2" />
        </el-select>
      </el-form-item>
      <el-form-item label="运营阶段" prop="lifecycle">
        <el-select v-model="queryParams.lifecycle" clearable placeholder="全部阶段" @change="handleQuery">
          <el-option v-for="item in lifecycleOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="商家公开池" prop="merchantStatus">
        <el-select v-model="queryParams.merchantStatus" clearable placeholder="全部状态" @change="handleQuery">
          <el-option label="已开放" :value="1" />
          <el-option label="未开放" :value="0" />
        </el-select>
      </el-form-item>
      <el-form-item label="俱乐部公开池" prop="openClubPool">
        <el-select v-model="queryParams.openClubPool" clearable placeholder="全部状态" @change="handleQuery">
          <el-option label="已开放" :value="1" />
          <el-option label="未开放" :value="0" />
        </el-select>
      </el-form-item>
      <el-form-item label="俱乐部承接" prop="clubFulfillmentState">
        <el-select v-model="queryParams.clubFulfillmentState" clearable placeholder="全部承接状态" @change="handleQuery">
          <el-option label="不需要俱乐部承接" value="NOT_REQUIRED" />
          <el-option label="申请中" value="APPLYING" />
          <el-option label="待确认邀约" value="INVITED" />
          <el-option label="已签约" value="SIGNED" />
          <el-option label="带队中" value="LEADING" />
          <el-option label="待明确" value="UNCONFIRMED" />
        </el-select>
      </el-form-item>
      <el-form-item label="商家供给" prop="merchantSupplyState">
        <el-select v-model="queryParams.merchantSupplyState" clearable placeholder="全部供给状态" @change="handleQuery">
          <el-option label="不需要" value="NOT_REQUIRED" />
          <el-option label="征集中" value="RECRUITING" />
          <el-option label="待调配" value="PENDING_ALLOCATION" />
          <el-option label="缺供给" value="GAP" />
          <el-option label="部分就绪" value="PARTIALLY_READY" />
          <el-option label="已就绪" value="READY" />
        </el-select>
      </el-form-item>
      <el-form-item label="可开跑" prop="runReadiness">
        <el-select v-model="queryParams.runReadiness" clearable placeholder="全部可开跑状态" @change="handleQuery">
          <el-option label="可开跑" value="READY" />
          <el-option label="存在风险" value="AT_RISK" />
          <el-option label="当前阻塞" value="BLOCKED" />
        </el-select>
      </el-form-item>
      <el-form-item label="保证金状态" prop="depositStatus">
        <el-select v-model="queryParams.depositStatus" clearable placeholder="已接受邀约的保证金" @change="handleQuery">
          <el-option label="待锁价" :value="0" />
          <el-option label="支付中" :value="1" />
          <el-option label="已冻结" :value="2" />
          <el-option label="已抵扣" :value="3" />
          <el-option label="已退还" :value="4" />
          <el-option label="待缴" :value="5" />
        </el-select>
      </el-form-item>
      <el-form-item label="城市/地点" prop="cityKeyword">
        <el-input v-model="queryParams.cityKeyword" clearable placeholder="按主题地点模糊匹配" @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="执行窗口从" prop="operationStartDate">
        <el-date-picker v-model="queryParams.operationStartDate" type="date" value-format="yyyy-MM-dd" placeholder="开始日期" @change="handleQuery" />
      </el-form-item>
      <el-form-item label="执行窗口至" prop="operationEndDate">
        <el-date-picker v-model="queryParams.operationEndDate" type="date" value-format="yyyy-MM-dd" placeholder="结束日期" @change="handleQuery" />
      </el-form-item>
      <el-form-item label="仅看缺供给" prop="merchantSupplyGapOnly">
        <el-switch v-model="queryParams.merchantSupplyGapOnly" @change="handleQuery" />
      </el-form-item>
      <el-form-item label="仅看俱乐部申请" prop="pendingClubApplicationOnly">
        <el-switch v-model="queryParams.pendingClubApplicationOnly" @change="handleQuery" />
      </el-form-item>
      <el-form-item label="进行中未结客诉" prop="inProgressAnomalyOnly">
        <el-switch v-model="queryParams.inProgressAnomalyOnly" @change="handleQuery" />
      </el-form-item>
      <el-form-item label="用户端治理" prop="userStatus">
        <el-select v-model="queryParams.userStatus" clearable placeholder="全部状态" @change="handleQuery">
          <el-option label="已上架" :value="1" />
          <el-option label="未上架" :value="0" />
        </el-select>
      </el-form-item>
      <el-form-item label="主题名称" prop="name">
        <el-input
          v-model="queryParams.name"
          placeholder="请输入主题名称"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="invalidRouteContentScope"
      class="mb8"
      type="error"
      :closable="false"
      show-icon
      title="主题来源入口无效"
      description="已拒绝加载全量主题；请从“官方主题”或“主题广场”入口重新进入。"
    />

    <div v-if="isSourceEntry()" class="topic-history-queue mb8">
      <div>
        <strong>历史待归属</strong>
        <span>{{ historyPendingSummary() }}</span>
      </div>
      <el-badge :value="historyPendingCount || 0" :hidden="!historyPendingCount">
        <el-button size="mini" plain type="warning" :loading="historyPendingLoading" @click="openHistoricalQueue">处理待办</el-button>
      </el-badge>
    </div>
    <div v-else-if="isHistoryQueue()" class="topic-history-queue topic-history-queue--active mb8">
      <div>
        <strong>历史待归属处理队列</strong>
        <span>这些主题尚无生效 CONTENT 槽；在确认主体前不会归入官方主题或主题广场。</span>
      </div>
      <div class="topic-history-queue__actions">
        <el-button size="mini" plain @click="leaveHistoricalQueue('official')">返回官方主题</el-button>
        <el-button size="mini" plain @click="leaveHistoricalQueue('plaza')">返回主题广场</el-button>
      </div>
    </div>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5"><el-button type="primary" plain size="mini" icon="el-icon-data-analysis" @click="scoringConsoleOpen = true" v-hasPermi="['business:orientScoring:list']">计分申诉与争议</el-button></el-col>
      <el-col :span="1.5">
        <el-button
          v-if="isOfficialEntry()"
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          @click="handleAdd"
          v-hasPermi="['business:topic:add']"
        >新增主题</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          v-if="isOfficialEntry() && !crossSourceSearch"
          type="danger"
          plain
          icon="el-icon-delete"
          size="mini"
          :disabled="multiple"
          @click="handleDelete"
          v-hasPermi="['business:topic:remove']"
        >删除</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="topicList" @selection-change="handleSelectionChange">
      <el-table-column v-if="isOfficialEntry() && !crossSourceSearch" type="selection" width="55" align="center" />
      <el-table-column label="主题" min-width="220">
        <template slot-scope="scope">
          <div class="topic-identity">
            <strong>{{ scope.row.name || '未命名主题' }}</strong>
            <small>#{{ scope.row.id }} · {{ scope.row.addressName || '城市待补充' }}</small>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="内容主体" min-width="150">
        <template slot-scope="scope">
          <el-tag size="mini" :type="publisherTagType(scope.row)">{{ publisherLabel(scope.row) }}</el-tag>
          <div v-if="isKnownPublisher(scope.row)" class="topic-publisher-note">{{ publisherNameLabel(scope.row) }}</div>
          <div v-if="isKnownPublisher(scope.row) && scope.row.contentPublisherLeaderName" class="topic-publisher-note">主理人：{{ scope.row.contentPublisherLeaderName }}</div>
          <div v-if="scope.row.contentPublisherType && !isKnownPublisher(scope.row)" class="topic-publisher-note">CONTENT 槽主体类型异常，待处置</div>
        </template>
      </el-table-column>
      <el-table-column label="玩法" min-width="150">
        <template slot-scope="scope">
          <el-tag size="mini" effect="plain">{{ productTypeLabel(scope.row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="运营阶段" width="110">
        <template slot-scope="scope">
          <el-tag size="mini" :type="lifecycleTagType(scope.row)">{{ lifecycleLabel(scope.row) }}</el-tag>
        </template>
      </el-table-column>

      <el-table-column label="推荐状态" align="center" prop="isRecommend" width="100">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.sys_recommend" :value="scope.row.isRecommend" />
        </template>
      </el-table-column>

      <el-table-column label="主题时间" prop="startDate" width="180">
        <template v-slot="scope">
          <div>开始时间： {{ scope.row.startDate }}</div>
          <div>结束时间： {{ scope.row.endDate }}</div>
         </template>
      </el-table-column>
      <el-table-column label="公开招募" width="180">
        <template v-slot="scope">
          <div>商家池：{{ merchantPoolLabel(scope.row) }}</div>
          <div>俱乐部池：{{ clubPoolLabel(scope.row) }}</div>
          <small>{{ registrationWindowLabel(scope.row) }}</small>
         </template>
      </el-table-column>
      <el-table-column label="审核状态" align="center" prop="checkStatus"  width="100">
      <template slot-scope="scope">
        <el-tag size="mini" :type="auditStatusTagType(scope.row.checkStatus)">
          {{ auditStatusLabel(scope.row.checkStatus) }}
        </el-tag>
        <div v-if="scope.row.checkStatus == 2">原因：{{ scope.row.reason }}</div>
      </template>
      </el-table-column>
      <el-table-column label="用户端治理" align="center" prop="userStatus"  width="100">
      <template slot-scope="scope">
        <el-tag size="mini" :type="userGovernanceTagType(scope.row.userStatus)">
          {{ userGovernanceLabel(scope.row.userStatus) }}
        </el-tag>
      </template>
      </el-table-column>
      <el-table-column label="俱乐部承接" min-width="180">
        <template slot-scope="scope">
          <el-tag size="mini" :type="clubFulfillmentTagType(scope.row)">{{ clubFulfillmentLabel(scope.row) }}</el-tag>
          <small class="topic-operation-note">{{ clubFulfillmentDetail(scope.row) }}</small>
        </template>
      </el-table-column>
      <el-table-column label="商家供给" min-width="190">
        <template slot-scope="scope">
          <el-tag size="mini" :type="merchantSupplyTagType(scope.row)">{{ merchantSupplyLabel(scope.row) }}</el-tag>
          <small class="topic-operation-note">{{ merchantSupplyDetail(scope.row) }}</small>
        </template>
      </el-table-column>
      <el-table-column label="可开跑" min-width="180">
        <template slot-scope="scope">
          <el-tag size="mini" :type="runReadinessTagType(scope.row)">{{ runReadinessLabel(scope.row) }}</el-tag>
          <small class="topic-operation-note">{{ runReadinessDetail(scope.row) }}</small>
        </template>
      </el-table-column>
      <el-table-column label="售卖与履约" min-width="180">
        <template slot-scope="scope">
          <div>{{ ticketPriceLabel(scope.row) }}</div>
          <small class="topic-operation-note">{{ ticketInventoryDetail(scope.row) }}</small>
          <small class="topic-operation-note">{{ salesAndFulfillmentDetail(scope.row) }}</small>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center"  fixed="right"  width="300" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button
            v-if="isHistoryQueue()"
            size="mini"
            type="text"
            icon="el-icon-s-check"
            @click="openContentSlotReassign(scope.row)"
            v-hasPermi="['business:topic:content-slot:edit']"
          >确认内容主体</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-view"
            @click="goDetail(scope.row)"
            v-hasPermi="['business:topic:query']"
          >工作台</el-button>
          <el-dropdown trigger="click" @command="command => handleMore(command, scope.row)">
            <el-button size="mini" type="text">更多<i class="el-icon-arrow-down el-icon--right" /></el-button>
            <el-dropdown-menu slot="dropdown">
              <el-dropdown-item command="audit" v-hasPermi="['business:audit:list']">审核与处置</el-dropdown-item>
              <el-dropdown-item v-if="isKnownPublisher(scope.row)" command="roam-referral" v-hasPermi="['business:topic:edit']">漫游导流</el-dropdown-item>
              <template v-if="isOfficialTopic(scope.row)">
                <el-dropdown-item command="edit" v-hasPermi="['business:topic:edit']">编辑主题</el-dropdown-item>
                <el-dropdown-item command="recommend" v-hasPermi="['business:topic:edit']">调整推荐</el-dropdown-item>
                <el-dropdown-item command="copy" v-hasPermi="['business:topic:copy']">复制主题</el-dropdown-item>
                <el-dropdown-item command="delete" divided v-hasPermi="['business:topic:remove']">删除主题</el-dropdown-item>
              </template>
              <template v-else-if="isPlazaEntry()">
                <el-dropdown-item command="recommend" v-hasPermi="['business:topic:plaza:recommend']">调整推荐</el-dropdown-item>
              </template>
            </el-dropdown-menu>
          </el-dropdown>
        </template>
      </el-table-column>
    </el-table>
    
    <pagination
      v-show="total>0"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      @pagination="getList"
    />

    <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="120px">
        <el-form-item label="处理类型" prop="checkStatus">
          <!-- HF-05(A-41): 审核决定改用 sys_topic_status 放开 1通过/2驳回;原 sys_merchant_status+['0','3'] 永远发不出 2,导致无法驳回 -->
          <DictRadio v-model="form.checkStatus" :radioData="dict.type.sys_topic_status" size="small" :filter="['1','2']"></DictRadio>
        </el-form-item>
        <el-form-item label="驳回原因" prop="reason"  v-if="form.checkStatus == 2">
          <el-input v-model="form.reason" type="textarea" placeholder="请输入驳回原因" :rows="5" />
        </el-form-item>
      
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>

    <el-dialog title="确认内容主体" :visible.sync="contentSlotOpen" width="520px" append-to-body :close-on-click-modal="false">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="这会确定票款内容归属"
        description="仅确认已核实的官方、俱乐部或会员主体。服务端会拒绝不存在、停用或已删除的主体，并留下操作与前后值审计。"
        style="margin-bottom: 16px"
      />
      <el-form :model="contentSlotForm" label-width="104px" size="small">
        <el-form-item label="待处理主题">
          <span>{{ contentSlotForm.topicName || '主题待确认' }}</span>
        </el-form-item>
        <el-form-item label="归属主体" required>
          <el-radio-group v-model="contentSlotForm.entityType" @change="onContentSlotTypeChange">
            <el-radio label="PLATFORM">官方（平台自营）</el-radio>
            <el-radio label="CLUB">俱乐部</el-radio>
            <el-radio label="MEMBER">会员</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="contentSlotForm.entityType === 'CLUB'" label="俱乐部 ID" required>
          <el-input-number v-model="contentSlotForm.entityId" :min="1" :controls="false" placeholder="club_id" />
        </el-form-item>
        <el-form-item v-if="contentSlotForm.entityType === 'MEMBER'" label="会员 ID" required>
          <el-input-number v-model="contentSlotForm.entityId" :min="1" :controls="false" placeholder="member_id" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" :loading="contentSlotSaving" @click="submitContentSlotReassign">确认归属</el-button>
        <el-button :disabled="contentSlotSaving" @click="contentSlotOpen = false">取 消</el-button>
      </div>
    </el-dialog>

    <!-- CMS 官方主题入口只创建平台自营主题；俱乐部/会员主题由各自发布链路创建。 -->
    <el-dialog title="新增主题" :visible.sync="createOpen" width="600px" append-to-body :close-on-click-modal="false">
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="110px" size="small">
        <el-form-item label="内容主体">
          <span class="publisher-hint">官方（平台自营）。俱乐部与会员主题由其发布链路创建，后台仅做监管与处置。</span>
        </el-form-item>

        <el-form-item label="主题名称" prop="name"><el-input v-model="createForm.name" maxlength="60" show-word-limit /></el-form-item>
        <el-form-item label="副标题" prop="subtitle"><el-input v-model="createForm.subtitle" maxlength="60" /></el-form-item>
        <el-form-item label="玩法" prop="productType">
          <el-radio-group v-model="createForm.productType">
            <el-radio :label="1">经典定向</el-radio>
            <el-radio :label="2">自由定向</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="简介" prop="description"><el-input v-model="createForm.description" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="封面图" prop="imgUrl"><el-input v-model="createForm.imgUrl" placeholder="封面图URL" /></el-form-item>
        <el-form-item label="商家报名窗口">
          <el-date-picker
            v-model="createForm.merchantSignUpRange"
            type="datetimerange"
            value-format="yyyy-MM-dd HH:mm:ss"
            range-separator="至"
            start-placeholder="开始"
            end-placeholder="结束"
          />
          <div class="publisher-hint">填了才会对商家开放报名 —— 「填写开放给商家,就会出现报名的部分」。</div>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" :loading="createLoading" @click="submitCreateForm">确 定</el-button>
        <el-button @click="createOpen = false">取 消</el-button>
      </div>
    </el-dialog>

    <!-- 编辑主题内容弹窗(名称/简介/封面,回传完整对象保留章节票种) -->
    <el-dialog :title="editRoamOnly ? '漫游导流' : '编辑主题'" :visible.sync="editOpen" width="600px" append-to-body>
      <el-form ref="editForm" :model="editForm" label-width="100px" size="small">
        <template v-if="!editRoamOnly">
          <el-form-item label="主题名称" prop="name"><el-input v-model="editForm.name" /></el-form-item>
          <el-form-item label="简介" prop="description"><el-input v-model="editForm.description" type="textarea" :rows="4" /></el-form-item>
          <el-form-item label="主题长图" prop="imgUrl"><el-input v-model="editForm.imgUrl" placeholder="封面图URL" /></el-form-item>
          <el-form-item label="图片" prop="imgArr"><el-input v-model="editForm.imgArr" placeholder="展示图URL" /></el-form-item>
        </template>
        <el-form-item label="漫游导流">
          <el-switch v-model="editForm.roamReferralEnabled" :active-value="1" :inactive-value="0" />
          <div class="publisher-hint">逐期默认关闭；仅本期审核、承接与可售条件同时满足时才会展示。</div>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitEdit">保 存</el-button>
        <el-button @click="editOpen = false">取 消</el-button>
      </div>
    </el-dialog>

    <!-- 推荐状态修改弹框 -->
    <el-dialog :title="isPlazaTopic(recommendForm) ? '调整广场推荐' : '修改推荐状态'" :visible.sync="dialogRecommend" width="500px" append-to-body :close-on-click-modal="false">
      <el-alert
        type="info"
        :closable="false"
        style="margin-bottom:16px"
        :title="isPlazaTopic(recommendForm)
          ? '仅调整主题广场的发现推荐，不会改写发布者的剧情、路线、图片说明或票价。'
          : '设为推荐后，该主题将在小程序「达人页→开放的挑战」「针对你的建议」和「大促Banner」中展示。'"
      />
      <el-form ref="recommendForm" :model="recommendForm" :rules="recommendRules" label-width="100px">
        <el-form-item label="推荐状态" prop="isRecommend">
          <DictRadio v-model="recommendForm.isRecommend" 
                    :radioData="dict.type.sys_recommend" 
                    size="small"
                    :show-all="'false'"/>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitRecommendForm">确 定</el-button>
        <el-button @click="cancelRecommend">取 消</el-button>
      </div>
    </el-dialog>
    <orient-scoring-console :visible.sync="scoringConsoleOpen" />
  </div>
</template>

<script>
import { listTopic, getTopic, delTopic, addTopic, updateTopic, updatePlazaRecommendation, copyTopicRequest, reassignTopicContentSlot } from "@/api/business/topic"
import OrientScoringConsole from '../orient-scoring/components/OrientScoringConsole.vue'

export default {
  name: "Topic",
  components: { OrientScoringConsole },
  dicts: ['sys_topic_status','sys_topic_type','sys_topic_user_status','sys_merchant_status','sys_recommend'],
  data() {
    return {
      // 遮罩层
      loading: true,
      // 选中数组
      ids: [],
      // 非单个禁用
      single: true,
      // 非多个禁用
      multiple: true,
      // 显示搜索条件
      showSearch: true,
      scoringConsoleOpen: false,
      // 通过官方/广场独立路由进入时锁定来源范围，不能在页内切回全量列表。
      routeContentScope: null,
      invalidRouteContentScope: false,
      // 不是第三个菜单：仅在官方/广场入口的顶部搜索中显式合并两类有效 CONTENT 主体。
      crossSourceSearch: false,
      // 广场只分俱乐部/会员两个来源子队列；跨来源检索时不把该过滤残留带入官方主题。
      plazaPublisherType: 'ALL',
      // 只在官方/广场入口读取。null 代表读取失败，绝不默认当成 0 条待办。
      historyPendingCount: null,
      historyPendingLoading: false,
      historyPendingRequest: 0,
      // 同一组件在官方/广场间复用；只接受最后一次列表查询，避免旧范围慢响应覆盖新范围。
      listRequest: 0,
      lifecycleOptions: [
        { value: 0, label: '草稿' },
        { value: 1, label: '招募' },
        { value: 2, label: '定价' },
        { value: 3, label: '售票' },
        { value: 4, label: '进行' },
        { value: 5, label: '结算' },
        { value: 6, label: '关闭' }
      ],
      // 总条数
      total: 0,
      // 主题表格数据
      topicList: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      editOpen: false,
      editRoamOnly: false,
      editForm: {},
      // 仅历史待归属队列开放。CONTENT 槽决定票款归属，提交前需二次确认，后端再校验主体真实有效。
      contentSlotOpen: false,
      contentSlotSaving: false,
      contentSlotForm: {
        topicId: null,
        topicName: '',
        entityType: 'PLATFORM',
        entityId: null
      },
      // 新增主题(Task 2)。★ 独立于 open —— open 绑的是审核弹窗,
      // 从前 handleAdd 置 open=true 会弹出「审核」表单,这也是它一直没有入口的原因之一。
      createOpen: false,
      createLoading: false,
      createForm: {},
      createRules: {
        name: [{ required: true, message: "请输入主题名称", trigger: "blur" }]
      },
      dialogRecommend: false,
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        memberId: null,
        topicType: null,
        contentScope: null,
        productType: null,
        lifecycle: null,
        merchantStatus: null,
        openClubPool: null,
        clubFulfillmentState: null,
        merchantSupplyState: null,
        runReadiness: null,
        depositStatus: null,
        cityKeyword: null,
        operationStartDate: null,
        operationEndDate: null,
        merchantSupplyGapOnly: false,
        pendingClubApplicationOnly: false,
        inProgressAnomalyOnly: false,
        userStatus: null,
        topicId: null,
        name: null,
        description: null,
        imgUrl: null,
        imgArr: null,
        activityCategoryId: null,
        status: null,
        reason: null,
        checkStatus: null,
        isRecommend: null,
      },
      // 表单参数
      form: {},
      // 推荐状态表单参数
      recommendForm: {
        id: null,
        isRecommend: null,
        contentPublisherType: null
      },
      // 表单校验
      rules: {
      },
      // 推荐状态表单校验规则
      recommendRules: {
        isRecommend: [
          { required: true, message: '请选择推荐状态', trigger: 'change' }
        ]
      }
    }
  },
  created() {
    this.parseUrlParams();
    this.getList();
    this.loadHistoricalPendingCount()
  },
  beforeRouteUpdate(to, from, next) {
    this.syncRouteContentScope(to.query.scope)
    next()
    this.$nextTick(() => {
      this.handleQuery()
      this.loadHistoricalPendingCount()
    })
  },
  methods: {
    /** 解析URL参数 */
    parseUrlParams() {
      const urlParams = new URLSearchParams(window.location.search);
      const checkStatus = urlParams.get('check_status');
      const isRecommend = urlParams.get('is_recommend');
      const scope = urlParams.get('scope');

      this.syncRouteContentScope(scope)
      
      if (checkStatus !== null) {
        this.queryParams.checkStatus = checkStatus;
      }
      
      if (isRecommend !== null) {
        this.queryParams.isRecommend = isRecommend;
      }
    },

    syncRouteContentScope(scope) {
      if (scope == null || scope === '') {
        this.routeContentScope = null
        this.invalidRouteContentScope = false
        this.crossSourceSearch = false
        this.plazaPublisherType = 'ALL'
        this.queryParams.contentScope = null
        return
      }
      const resolvedScope = ({ official: 'OFFICIAL', plaza: 'PLAZA', historical: 'HISTORICAL' })[scope]
      if (resolvedScope) {
        this.routeContentScope = resolvedScope
        this.invalidRouteContentScope = false
        this.crossSourceSearch = false
        this.plazaPublisherType = 'ALL'
        this.queryParams.contentScope = resolvedScope
        return
      }

      // URL scope 拼错也不能悄悄回落到全量列表；后端会把这个未知值 fail-closed 为 1=0。
      this.routeContentScope = '__INVALID_ROUTE_SCOPE__'
      this.invalidRouteContentScope = true
      this.crossSourceSearch = false
      this.plazaPublisherType = 'ALL'
      this.queryParams.contentScope = this.routeContentScope
    },

    isSourceEntry() {
      return this.routeContentScope === 'OFFICIAL' || this.routeContentScope === 'PLAZA'
    },

    effectiveContentScope() {
      return this.crossSourceSearch && this.isSourceEntry() ? 'CROSS_SOURCE' : this.routeContentScope
    },

    isHistoryQueue() {
      return this.routeContentScope === 'HISTORICAL'
    },

    isOfficialEntry() {
      return this.routeContentScope === 'OFFICIAL'
    },

    isOfficialTopic(row) {
      return !!row && row.contentPublisherType === 'PLATFORM'
    },

    isPlazaTopic(row) {
      return !!row && ['CLUB', 'MEMBER'].indexOf(row.contentPublisherType) >= 0
    },

    isPlazaEntry() {
      return this.routeContentScope === 'PLAZA'
    },

    effectivePlazaPublisherType() {
      if (!this.isPlazaEntry() || this.crossSourceSearch) return null
      return this.plazaPublisherType === 'CLUB' || this.plazaPublisherType === 'MEMBER'
        ? this.plazaPublisherType
        : null
    },

    historyPendingSummary() {
      if (this.historyPendingCount === null) return '待归属数量暂不可用'
      return this.historyPendingCount > 0
        ? `当前有 ${this.historyPendingCount} 条主题需要确认内容主体`
        : '当前没有待归属主题'
    },

    openHistoricalQueue() {
      this.$router.push({ path: this.$route.path, query: { scope: 'historical' } })
    },

    leaveHistoricalQueue(scope) {
      const routeName = {
        official: 'OfficialTopic',
        plaza: 'TopicPlaza'
      }[scope]
      if (!routeName) return
      this.$router.push({ name: routeName, query: { scope } })
    },

    publisherLabel(row) {
      if (!row.contentPublisherType) return '历史待归属'
      return ({
        PLATFORM: '官方',
        CLUB: '俱乐部',
        MEMBER: '会员'
      })[row.contentPublisherType] || '主体异常'
    },

    publisherTagType(row) {
      if (!row.contentPublisherType) return 'info'
      return ({
        PLATFORM: 'success',
        CLUB: 'primary',
        MEMBER: 'warning'
      })[row.contentPublisherType] || 'danger'
    },

    publisherNameLabel(row) {
      if (!row || !row.contentPublisherType) return '待归属'
      return row.contentPublisherName || '主体名称暂不可用'
    },

    isKnownPublisher(row) {
      return ['PLATFORM', 'CLUB', 'MEMBER'].indexOf(row.contentPublisherType) >= 0
    },

    productTypeLabel(row) {
      return ({
        1: '线性城市定向',
        2: '主题内自由探索'
      })[row.productType] || '玩法待定义'
    },

    lifecycleLabel(row) {
      return ({
        0: '草稿',
        1: '招募',
        2: '定价',
        3: '售票',
        4: '进行',
        5: '结算',
        6: '关闭'
      })[row.lifecycle] || '阶段待定义'
    },

    lifecycleTagType(row) {
      return ({
        0: 'info',
        1: 'warning',
        2: 'warning',
        3: 'primary',
        4: 'success',
        5: 'warning',
        6: 'info'
      })[row.lifecycle] || 'info'
    },

    poolStatusLabel(value) {
      if (value === 1 || value === '1') return '开放'
      if (value === 0 || value === '0') return '关闭'
      return '待确认'
    },

    poolTagType(value) {
      if (value === 1 || value === '1') return 'success'
      if (value === 0 || value === '0') return 'info'
      return 'warning'
    },

    auditStatusLabel(value) {
      if (value === 0) return '待审核'
      if (value === 1) return '审核通过'
      if (value === 2) return '审核失败'
      return '待确认'
    },

    auditStatusTagType(value) {
      if (value === 0) return 'warning'
      if (value === 1) return 'success'
      if (value === 2) return 'danger'
      return 'warning'
    },

    userGovernanceLabel(value) {
      if (value === 1) return '已上架'
      if (value === 0) return '未上架'
      return '待确认'
    },

    userGovernanceTagType(value) {
      if (value === 1) return 'success'
      if (value === 0) return 'info'
      return 'warning'
    },

    merchantPoolLabel(row) {
      return this.poolStatusLabel(row && row.merchantStatus)
    },

    clubPoolLabel(row) {
      return this.poolStatusLabel(row && row.openClubPool)
    },

    registrationWindowLabel(row) {
      const merchantPool = this.merchantPoolLabel(row)
      if (merchantPool === '待确认') return '商家池状态待确认'
      if (merchantPool === '关闭') return '商家报名未开放'
      if (!row.merchantSignUpStartDate && !row.merchantSignUpEndDate) return '报名窗口待定义'
      return `${row.merchantSignUpStartDate || '未设开始'} 至 ${row.merchantSignUpEndDate || '未设结束'}`
    },

    merchantCountLabel(row) {
      const count = row && row.merchantCount
      return Number.isInteger(count) && count >= 0 ? String(count) : '待核验'
    },

    merchantCountTagType(row) {
      const count = row && row.merchantCount
      if (!Number.isInteger(count) || count < 0) return 'warning'
      return count > 0 ? 'success' : 'info'
    },

    operationSummary(row) {
      return row && row.operationSummary ? row.operationSummary : null
    },

    clubFulfillmentLabel(row) {
      const summary = row && row.operationSummary
      if (!summary) return '数据暂不可用'
      return ({
        NOT_REQUIRED: '不需要俱乐部承接',
        APPLYING: '申请中',
        INVITED: '待确认邀约',
        SIGNED: '已签约',
        LEADING: '带队中',
        UNCONFIRMED: '待明确'
      })[summary.clubFulfillmentState] || '状态待核验'
    },

    clubFulfillmentTagType(row) {
      const summary = row && row.operationSummary
      if (!summary) return 'warning'
      return ({
        NOT_REQUIRED: 'info',
        APPLYING: 'warning',
        INVITED: 'warning',
        SIGNED: 'primary',
        LEADING: 'success',
        UNCONFIRMED: 'danger'
      })[summary.clubFulfillmentState] || 'warning'
    },

    clubFulfillmentDetail(row) {
      const summary = row && row.operationSummary
      if (!summary) return '未取得批量运营投影'
      return `申请 ${summary.pendingClubApplicationCount} · 待邀 ${summary.pendingClubInviteCount} · 已签 ${summary.signedClubCooperationCount} · 带队 ${summary.activeLeadSessionCount}`
    },

    merchantSupplyLabel(row) {
      const summary = row && row.operationSummary
      if (!summary) return '数据暂不可用'
      return ({
        NOT_REQUIRED: '不需要',
        RECRUITING: '征集中',
        PENDING_ALLOCATION: '待调配',
        GAP: '缺供给',
        PARTIALLY_READY: '部分就绪',
        READY: '已就绪'
      })[summary.merchantSupplyState] || '状态待核验'
    },

    merchantSupplyTagType(row) {
      const summary = row && row.operationSummary
      if (!summary) return 'warning'
      return ({
        NOT_REQUIRED: 'info',
        RECRUITING: 'warning',
        PENDING_ALLOCATION: 'warning',
        GAP: 'danger',
        PARTIALLY_READY: 'primary',
        READY: 'success'
      })[summary.merchantSupplyState] || 'warning'
    },

    merchantSupplyDetail(row) {
      const summary = row && row.operationSummary
      if (!summary) return '未取得批量运营投影'
      return `申请 ${summary.candidateCount} · 待审 ${summary.pendingCandidateCount} · 入选 ${summary.awardedCandidateCount} · 合作 ${summary.merchantCooperationCount} · 生效供给 ${summary.activeOfferCount} · 完整章节 ${summary.suppliedRequiredChapterCount}/${summary.requiredChapterCount} · 缺 ${summary.missingRequiredChapterCount}`
    },

    runReadinessLabel(row) {
      const summary = row && row.operationSummary
      if (!summary) return '数据暂不可用'
      return ({
        READY: '可开跑',
        AT_RISK: '存在风险',
        BLOCKED: '当前阻塞'
      })[summary.runReadiness] || '状态待核验'
    },

    runReadinessTagType(row) {
      const summary = row && row.operationSummary
      if (!summary) return 'warning'
      return ({
        READY: 'success',
        AT_RISK: 'warning',
        BLOCKED: 'danger'
      })[summary.runReadiness] || 'warning'
    },

    runReadinessDetail(row) {
      const summary = row && row.operationSummary
      if (!summary) return '缺少可开跑状态回读'
      const labels = {
        LIFECYCLE_NOT_READY: '尚未售票',
        LIFECYCLE_TERMINAL: '已结算或关闭',
        CONTENT_STRUCTURE_MISSING: '章节或节点不完整',
        TICKET_CONFIGURATION_MISSING: '票务未配置',
        SELF_PLAY_PASS_CONFIGURATION_MISSING: '自玩通行证价格待配置',
        REQUIRED_CHAPTER_SUPPLY_MISSING: '必需章节缺供给',
        CLUB_INVITE_PENDING: '俱乐部邀约待确认',
        CLUB_FULFILLMENT_UNCONFIRMED: '俱乐部承接未确认'
      }
      const blockers = Array.isArray(summary.runBlockers) ? summary.runBlockers : []
      return blockers.length ? blockers.map(blocker => labels[blocker] || '阻塞项待核验').join('、') : '全部前置条件已满足'
    },

    ticketPriceLabel(row) {
      const summary = row && row.operationSummary
      if (!summary) return '票务数据暂不可用'
      if (summary.ticketCount === 0) return '未配置票种'
      const price = Number(summary.minTicketPrice)
      if (!Number.isFinite(price) || price < 0) return '票价待核验'
      return price === 0 ? '免费' : `¥${price} 起`
    },

    ticketInventoryDetail(row) {
      const summary = row && row.operationSummary
      if (!summary) return '未取得票务库存投影'
      const parts = []
      if (summary.standardTicketCount > 0) {
        parts.push(`标准票余量 ${summary.remainingTicketInventory}/${summary.totalTicketInventory}`)
      }
      if (summary.selfPlayPassConfigured) {
        const quota = Number(summary.selfPlayPassQuota)
        const inventory = Number.isFinite(quota) && quota > 0
          ? `${summary.selfPlayPassRemainingInventory}/${quota}`
          : '不限量'
        parts.push(`自玩通行证 ${inventory}`)
      }
      if (summary.selfPlayPassConfigurationInvalid) parts.push('自玩通行证价格待配置')
      return `票种 ${summary.ticketCount} · ${parts.length ? parts.join('；') : '库存待配置'}`
    },

    salesAndFulfillmentDetail(row) {
      const summary = row && row.operationSummary
      if (!summary) return '未取得售卖履约投影'
      return `已售 ${summary.paidRegistrationCount} · 核销 ${summary.verifiedHeadCount} · 未结客诉 ${summary.openComplaintCount || 0} · 待结算 ${summary.pendingSettlementCount}`
    },
    
    /** 处理推荐状态修改 */
    handleRecommendStatus(row) {
      if (this.isOfficialEntry() && !this.ensureOfficialTopicAction(row)) return
      const id = row.id || this.ids;
      getTopic(id).then(response => {
        const data = response.data;
        this.recommendForm = {
          id: data.id,
          isRecommend: data.isRecommend || 0,
          contentPublisherType: row && row.contentPublisherType
        };
        this.dialogRecommend = true;
      });
    },
    
    /** 提交推荐状态表单 */
    submitRecommendForm() {
      this.$refs["recommendForm"].validate(valid => {
        if (valid) {
          const request = this.isPlazaTopic(this.recommendForm)
            ? updatePlazaRecommendation(this.recommendForm.id, { isRecommend: this.recommendForm.isRecommend })
            : updateTopic({ id: this.recommendForm.id, isRecommend: this.recommendForm.isRecommend })
          request.then(response => {
            this.$modal.msgSuccess("修改成功");
            this.dialogRecommend = false;
            this.getList();
          }).catch(() => {});
        }
      });
    },
    
    /** 取消推荐状态修改 */
    cancelRecommend() {
      this.dialogRecommend = false;
      this.recommendForm = {
        id: null,
        isRecommend: null,
        contentPublisherType: null
      };
      if (this.$refs["recommendForm"]) {
        this.$refs["recommendForm"].clearValidate();
      }
    },
    
    // 新增方法：将日期时间字符串解析为时间戳
    parseDateTimeToTimestamp(dateTimeStr) {
      if (!dateTimeStr) return null
      
      // 如果已经是数字（可能是时间戳），直接返回
      if (typeof dateTimeStr === 'number') {
        return dateTimeStr
      }
      
      // 尝试解析字符串
      const date = new Date(dateTimeStr)
      if (isNaN(date.getTime())) {
        // 尝试解析其他常见格式
        const parsedDate = this.tryParseVariousFormats(dateTimeStr)
        return parsedDate ? parsedDate.getTime() : null
      }
      
      return date.getTime()
    },
    
    // 尝试解析多种日期格式
    tryParseVariousFormats(dateStr) {
      if (!dateStr) return null
      
      // 移除可能的时区信息
      const cleanStr = dateStr.replace(/T/g, ' ').replace(/Z/g, '').split('.')[0]
      
      // 尝试常见的日期格式
      const formats = [
        'yyyy-MM-dd HH:mm:ss',
        'yyyy/MM/dd HH:mm:ss',
        'yyyy-MM-dd HH:mm',
        'yyyy/MM/dd HH:mm'
      ]
      
      for (const format of formats) {
        const date = this.parseDateWithFormat(cleanStr, format)
        if (date && !isNaN(date.getTime())) {
          return date
        }
      }
      
      // 如果都不行，尝试直接解析
      return new Date(dateStr)
    },
    
    // 根据格式解析日期
    parseDateWithFormat(dateStr, format) {
      try {
        let year, month, day, hours = 0, minutes = 0, seconds = 0
        
        if (format === 'yyyy-MM-dd HH:mm:ss' || format === 'yyyy/MM/dd HH:mm:ss') {
          const separator = format.includes('-') ? '-' : '/'
          const parts = dateStr.split(' ')
          if (parts.length < 2) return null
          
          const dateParts = parts[0].split(separator)
          if (dateParts.length !== 3) return null
          
          const timeParts = parts[1].split(':')
          if (timeParts.length < 2) return null
          
          year = parseInt(dateParts[0], 10)
          month = parseInt(dateParts[1], 10) - 1
          day = parseInt(dateParts[2], 10)
          hours = parseInt(timeParts[0], 10)
          minutes = parseInt(timeParts[1], 10)
          seconds = timeParts.length > 2 ? parseInt(timeParts[2], 10) : 0
        } else if (format === 'yyyy-MM-dd HH:mm' || format === 'yyyy/MM/dd HH:mm') {
          const separator = format.includes('-') ? '-' : '/'
          const parts = dateStr.split(' ')
          if (parts.length < 2) return null
          
          const dateParts = parts[0].split(separator)
          if (dateParts.length !== 3) return null
          
          const timeParts = parts[1].split(':')
          if (timeParts.length < 2) return null
          
          year = parseInt(dateParts[0], 10)
          month = parseInt(dateParts[1], 10) - 1
          day = parseInt(dateParts[2], 10)
          hours = parseInt(timeParts[0], 10)
          minutes = parseInt(timeParts[1], 10)
        }
        
        return new Date(year, month, day, hours, minutes, seconds)
      } catch (error) {
        return null
      }
    },
    
    // 复制按钮操作
    handleCopy(row) {
      if (!this.ensureOfficialTopicAction(row)) return
      const id = row.id;
      this.$modal.confirm('确认要复制该主题吗？').then(() => {
        // 调用复制API
        this.copyTopic(id);
      }).catch(() => {});
    },
    
    // 复制主题方法
    copyTopic(id) {
      this.loading = true;
      copyTopicRequest(id).then(response => {
        this.$modal.msgSuccess("复制成功");
        this.getList();
        this.loading = false;
      }).catch(() => {
        this.loading = false;
      });
    },

    handleMore(command, row) {
      if (command === 'audit') {
        this.handleStatus(row)
      } else if (command === 'edit') {
        this.handleEdit(row)
      } else if (command === 'roam-referral') {
        this.handleRoamReferral(row)
      } else if (command === 'recommend') {
        this.handleRecommendStatus(row)
      } else if (command === 'copy') {
        this.handleCopy(row)
      } else if (command === 'delete') {
        this.handleDelete(row)
      }
    },
    
    // 将时间戳格式化为日期时间字符串
    formatTimestampToDateTime(timestamp) {
      if (!timestamp || isNaN(timestamp)) {
        return null
      }
      
      const date = new Date(Number(timestamp))
      if (isNaN(date.getTime())) {
        return null
      }
      
      const year = date.getFullYear()
      const month = String(date.getMonth() + 1).padStart(2, '0')
      const day = String(date.getDate()).padStart(2, '0')
      const hours = String(date.getHours()).padStart(2, '0')
      const minutes = String(date.getMinutes()).padStart(2, '0')
      const seconds = String(date.getSeconds()).padStart(2, '0')
      
      return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`
    },
    
    handleStatus(row) {
      this.$router.push({ name: 'Audit', query: { bizType: 'TOPIC', bizId: row.id } })
    },
    
    /** 查询主题列表 */
    getList() {
      const requestId = ++this.listRequest
      this.loading = true;
      
      // 构建查询参数
      const params = {
        ...this.queryParams,
        contentScope: this.effectiveContentScope(),
        contentPublisherType: this.effectivePlazaPublisherType()
      };
      
      listTopic(params).then(response => {
        if (requestId !== this.listRequest) return
        this.topicList = response.rows;
        this.total = response.total;
        if (this.isHistoryQueue()) this.historyPendingCount = Number(response.total || 0)
        this.loading = false;
      }).catch(() => {
        if (requestId !== this.listRequest) return
        this.topicList = []
        this.total = 0
        this.loading = false;
      });
    },

    loadHistoricalPendingCount() {
      const requestId = ++this.historyPendingRequest
      if (!this.isSourceEntry()) {
        this.historyPendingCount = null
        this.historyPendingLoading = false
        return
      }

      this.historyPendingLoading = true
      listTopic({ pageNum: 1, pageSize: 1, contentScope: 'HISTORICAL' }).then(response => {
        if (requestId !== this.historyPendingRequest) return
        this.historyPendingCount = Number(response.total || 0)
        this.historyPendingLoading = false
      }).catch(() => {
        if (requestId !== this.historyPendingRequest) return
        this.historyPendingCount = null
        this.historyPendingLoading = false
      })
    },
    
    // 取消按钮
    cancel() {
      this.open = false
      this.reset()
    },
    
    // 表单重置
    reset() {
      this.form = {
        id: null,
        memberId: null,
        topicType: null,
        topicId: null,
        name: null,
        description: null,
        imgUrl: null,
        imgArr: null,
        activityCategoryId: null,
        status: null,
        reason: null,
        delFlag: null,
        createBy: null,
        createTime: null,
        updateBy: null,
        updateTime: null
      }
      this.resetForm("form")
    },
    
    /** 搜索按钮操作 */
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    
    goDetail(row) {
      const scope = this.workspaceScope(row)
      if (!['OFFICIAL', 'PLAZA', 'HISTORICAL'].includes(scope)) {
        this.$modal.msgWarning('无法确认主题内容主体，先进入历史待归属队列处理。')
        return
      }
      this.$router.push({ path: '/topic/workspace', query: { id: row.id, scope } })
    },

    workspaceScope(row) {
      if (!row || !row.contentPublisherType) return 'HISTORICAL'
      if (row.contentPublisherType === 'PLATFORM') return 'OFFICIAL'
      if (row.contentPublisherType === 'CLUB' || row.contentPublisherType === 'MEMBER') return 'PLAZA'
      return null
    },

    contentSubjectLabel(entityType) {
      return ({
        PLATFORM: '官方（平台自营）',
        CLUB: '俱乐部',
        MEMBER: '会员'
      })[entityType] || '未知主体'
    },

    openContentSlotReassign(row) {
      if (!this.isHistoryQueue()) {
        this.$modal.msgWarning('仅可在历史待归属处理队列确认内容主体。')
        return
      }
      this.contentSlotForm = {
        topicId: row.id,
        topicName: row.name || `主题 #${row.id}`,
        entityType: 'PLATFORM',
        entityId: null
      }
      this.contentSlotOpen = true
    },

    onContentSlotTypeChange() {
      this.contentSlotForm.entityId = null
    },

    submitContentSlotReassign() {
      const form = this.contentSlotForm
      if (!form.topicId || !['PLATFORM', 'CLUB', 'MEMBER'].includes(form.entityType)) {
        this.$modal.msgWarning('请选择有效的内容主体。')
        return
      }
      const entityId = form.entityType === 'PLATFORM' ? null : Number(form.entityId)
      if (form.entityType !== 'PLATFORM' && (!Number.isInteger(entityId) || entityId <= 0)) {
        this.$modal.msgWarning(`请填写有效的${this.contentSubjectLabel(form.entityType)}ID。`)
        return
      }

      const subject = form.entityType === 'PLATFORM'
        ? this.contentSubjectLabel(form.entityType)
        : `${this.contentSubjectLabel(form.entityType)} #${entityId}`
      this.$modal.confirm(`确认将「${form.topicName}」的票款内容归属改判为${subject}？此操作会留下审计记录。`)
        .then(() => {
          this.contentSlotSaving = true
          return reassignTopicContentSlot(form.topicId, {
            entityType: form.entityType,
            entityId: entityId
          })
        })
        .then(() => {
          this.$modal.msgSuccess('内容主体已确认')
          this.contentSlotOpen = false
          this.contentSlotSaving = false
          this.getList()
          this.loadHistoricalPendingCount()
        })
        .catch(() => {
          this.contentSlotSaving = false
        })
    },
    
    /** 重置按钮操作 */
    resetQuery() {
      this.resetForm("queryForm")
      // 修改：重置时保留URL传入的checkStatus参数
      // 只重置其他查询条件，保留checkStatus和isRecommend
      this.queryParams.pageNum = 1;
      this.queryParams.name = null;
      this.queryParams.memberId = null;
      this.queryParams.productType = null;
      this.queryParams.lifecycle = null;
      this.queryParams.merchantStatus = null;
      this.queryParams.openClubPool = null;
      this.queryParams.clubFulfillmentState = null;
      this.queryParams.merchantSupplyState = null;
      this.queryParams.runReadiness = null;
      this.queryParams.depositStatus = null;
      this.queryParams.cityKeyword = null;
      this.queryParams.operationStartDate = null;
      this.queryParams.operationEndDate = null;
      this.queryParams.merchantSupplyGapOnly = false;
      this.queryParams.pendingClubApplicationOnly = false;
      this.queryParams.inProgressAnomalyOnly = false;
      this.crossSourceSearch = false;
      this.plazaPublisherType = 'ALL';
      this.queryParams.userStatus = null;
      this.queryParams.contentScope = this.routeContentScope;
      // this.queryParams.checkStatus = null; // 注释掉这行，不清除checkStatus
      // this.queryParams.isRecommend = null; // 不清除isRecommend
      this.handleQuery()
    },
    
    // 多选框选中数据
    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.id)
      this.single = selection.length!==1
      this.multiple = !selection.length
    },
    
    /** 新增按钮操作 */
    handleAdd() {
      if (!this.isOfficialEntry()) {
        this.$modal.msgWarning('主题广场与历史待归属队列不提供内容创作，请从官方主题入口进入。')
        return
      }
      // ★ 不复用 this.form/this.open —— open 绑的是审核弹窗。新增走自己的 createForm/createOpen。
      this.createForm = {
        publisherType: 'PLATFORM',
        name: undefined,
        subtitle: undefined,
        description: undefined,
        imgUrl: undefined,
        productType: 1,
        merchantSignUpRange: []
      }
      this.createOpen = true
      this.$nextTick(() => this.$refs.createFormRef && this.$refs.createFormRef.clearValidate())
    },

    submitCreateForm() {
      this.$refs["createFormRef"].validate(valid => {
        if (!valid) return
        const f = this.createForm
        const range = f.merchantSignUpRange || []
        const payload = {
          publisherType: 'PLATFORM',
          name: f.name,
          subtitle: f.subtitle,
          description: f.description,
          imgUrl: f.imgUrl,
          productType: f.productType,
          merchantSignUpStartDate: range[0],
          merchantSignUpEndDate: range[1]
        }
        this.createLoading = true
        addTopic(payload).then(() => {
          this.$modal.msgSuccess("新增成功")
          this.createOpen = false
          this.createLoading = false
          this.getList()
        }).catch(() => { this.createLoading = false })
      })
    },
    
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset()
      const id = row.id || this.ids
      getTopic(id).then(response => {
        this.form = response.data
        this.open = true
        this.title = "修改主题"
      })
    },

    /** 编辑主题内容：只保留生命周期允许的低风险字段。 */
    handleEdit(row) {
      if (!this.ensureOfficialTopicAction(row)) return
      getTopic(row.id).then(response => {
        const topic = response.data || {}
        this.editRoamOnly = false
        this.editForm = {
          id: topic.id,
          name: topic.name,
          subtitle: topic.subtitle,
          description: topic.description,
          imgUrl: topic.imgUrl,
          imgArr: topic.imgArr,
          roamReferralEnabled: topic.roamReferralEnabled === 1 ? 1 : 0
        }
        this.editOpen = true
      })
    },
    handleRoamReferral(row) {
      getTopic(row.id).then(response => {
        const topic = response.data || {}
        this.editRoamOnly = true
        this.editForm = {
          id: topic.id,
          roamReferralEnabled: topic.roamReferralEnabled === 1 ? 1 : 0
        }
        this.editOpen = true
      })
    },
    submitEdit() {
      const payload = {
        id: this.editForm.id,
        roamReferralEnabled: this.editForm.roamReferralEnabled
      }
      if (!this.editRoamOnly) Object.assign(payload, {
        name: this.editForm.name,
        subtitle: this.editForm.subtitle,
        description: this.editForm.description,
        imgUrl: this.editForm.imgUrl,
        imgArr: this.editForm.imgArr
      })
      updateTopic(payload).then(() => {
        this.$modal.msgSuccess("保存成功")
        this.editOpen = false
        this.getList()
      })
    },
    
    /** 提交按钮 */
    /**
     * 审核/状态弹窗的提交(open 绑的就是它)。只走 updateTopic。
     * 原先这里还有一条 addTopic(this.form) 的 else 分支:它既不可达(本弹窗只由 handleStatus 打开,
     * form.id 恒非空),形状也已经不对了 —— 新增改收 CmsTopicCreateDTO(发布者声明),
     * 而 this.form 是主题实体。留着它只会在将来被人「顺手复用」时发出一个后端必拒的请求。
     */
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          updateTopic(this.form).then(response => {
            this.$modal.msgSuccess("修改成功")
            this.open = false
            this.getList()
          })
        }
      })
    },
    
    /** 删除按钮操作 */
    handleDelete(row) {
      if (row && !this.ensureOfficialTopicAction(row)) return
      if (!row && this.crossSourceSearch) {
        this.$modal.msgWarning('跨来源检索不支持批量删除，请回到官方主题队列操作。')
        return
      }
      const ids = row.id || this.ids
      this.$modal.confirm('是否确认删除主题编号为"' + ids + '"的数据项？').then(function() {
        return delTopic(ids)
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess("删除成功")
      }).catch(() => {})
    },

    ensureOfficialTopicAction(row) {
      if (this.isOfficialTopic(row)) return true
      this.$modal.msgWarning('仅官方主题可执行此创作操作；广场主题请走审核与处置流程。')
      return false
    },
    
    /** 导出按钮操作 */
    handleExport() {
      this.download('business/topic/export', {
        ...this.queryParams
      }, `topic_${new Date().getTime()}.xlsx`)
    }
  }
}
</script>

<style scoped>
/* 新增主题弹窗:发布者选择的旁注 */
.publisher-hint {
  color: #909399;
  font-size: 12px;
  line-height: 1.5;
  margin-top: var(--cy-space-1);
}

.topic-identity strong,
.topic-identity small,
.topic-publisher-note {
  display: block;
}

.topic-identity small,
.topic-publisher-note,
.topic-operation-note {
  display: block;
  margin-top: var(--cy-space-1);
  color: #909399;
  font-size: 12px;
}

.topic-history-queue {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--cy-space-4);
  padding: var(--cy-space-1-5) var(--cy-space-3);
  border: 1px solid #f3d19e;
  border-radius: 4px;
  background: #fdf6ec;
  color: #606266;
}

.topic-history-queue strong {
  color: #e6a23c;
  margin-right: var(--cy-space-2);
}

.topic-history-queue--active {
  border-color: #e6a23c;
}

.topic-history-queue__actions {
  flex: none;
  white-space: nowrap;
}

/* 方案二的样式 */
.image-preview {
  position: relative;
  display: inline-block;
}

.image-count {
  position: absolute;
  bottom: 0;
  right: 0;
}

/* 方案三的样式 */
.avatar-group {
  display: flex;
  align-items: center;
}

.avatar-group .el-avatar {
  cursor: pointer;
  border: 2px solid var(--cy-color-bg-surface);
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
}

/* 图片容器的样式 */
.image-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--cy-legacy-5);
  justify-content: center;
}

.small-img.circle-img {
  border-radius: 50%;
}
</style>
