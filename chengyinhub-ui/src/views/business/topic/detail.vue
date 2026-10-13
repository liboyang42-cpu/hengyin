<template>
  <div class="topic-detail-wrapper">
    <el-main v-loading="loading">
      <!-- 主题基本信息 -->
      <el-card class="mt10">
        <div slot="header" class="clearfix">
          <span style="font-size: 16px;font-weight: bold">主题基本信息</span>
          <el-button
            v-if="topicDetail.productType === 2"
            v-hasPermi="['business:topic:content-slot:edit']"
            type="primary" size="mini" style="float:right"
            @click="declareOrganizer"
          >声明平台组织方</el-button>
        </div>
        <el-descriptions :column="2" border label-class-name="my-label" contentClassName="my-content" :label-style="LS">
          <el-descriptions-item label="主题名称">{{ topicDetail.name }}</el-descriptions-item>
          <el-descriptions-item label="主题类型">
            <dict-tag :options="dict.type.sys_topic_type" :value="topicDetail.topicType" />
          </el-descriptions-item>
          <el-descriptions-item label="审核状态">
            <dict-tag :options="dict.type.sys_topic_status" :value="topicDetail.status" />
          </el-descriptions-item>
          <el-descriptions-item label="用户端状态">
            <dict-tag :options="dict.type.sys_topic_user_status" :value="topicDetail.userStatus" />
          </el-descriptions-item>
          <el-descriptions-item label="商家端状态">
            <dict-tag :options="dict.type.sys_topic_user_status" :value="topicDetail.merchantStatus" />
          </el-descriptions-item>
          <el-descriptions-item label="创建人">{{ topicDetail.memberId }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ topicDetail.createTime }}</el-descriptions-item>
          <el-descriptions-item label="主题时间">
            {{ topicDetail.startDate }} 至 {{ topicDetail.endDate }}
          </el-descriptions-item>
          <el-descriptions-item label="商家报名时间">
            {{ topicDetail.merchantSignUpStartDate }} 至 {{ topicDetail.merchantSignUpEndDate }}
          </el-descriptions-item>
          <el-descriptions-item label="总时长">{{ formatTime(topicDetail.totalTime) }}</el-descriptions-item>
          <el-descriptions-item label="总里程">{{ topicDetail.totalMileage }}KM</el-descriptions-item>
          <el-descriptions-item label="地点数">{{ topicDetail.locationCount }}</el-descriptions-item>
          <el-descriptions-item label="模板数">{{ topicDetail.templateCount }}</el-descriptions-item>
          <el-descriptions-item label="是否同步创意广场">
            <el-tag :type="topicDetail.isSync === 1 ? 'success' : 'info'">
              {{ topicDetail.isSync === 1 ? '是' : '否' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="主题描述" :span="2">{{ topicDetail.description }}</el-descriptions-item>
          <el-descriptions-item label="驳回原因" :span="2" v-if="topicDetail.status === 2">
            {{ topicDetail.reason }}
          </el-descriptions-item>
        </el-descriptions>
        
        <!-- 主题长图 -->
        <div class="topic-cover mt20">
          <div class="label">主题长图：</div>
          <el-image 
            v-if="topicDetail.imgUrl" 
            :src="topicDetail.imgUrl" 
            :preview-src-list="[topicDetail.imgUrl]" 
            style="max-width: 300px; max-height: 200px;"
            fit="contain"
          ></el-image>
          <span v-else>无长图</span>
        </div>
        
        <!-- 主题轮播图 -->
        <div class="topic-carousel mt20" v-if="topicImgList && topicImgList.length > 0">
          <div class="label">主题轮播图：</div>
          <div class="img-list">
            <el-image 
              v-for="(img, index) in topicImgList" 
              :key="index" 
              :src="img" 
              :preview-src-list="topicImgList" 
              style="width: 120px; height: 80px; margin-right: 10px;"
              fit="cover"
            ></el-image>
          </div>
        </div>
      </el-card>
      
      <el-card class="mt10" v-if="topicDetail.productType === 2">
        <div slot="header" class="clearfix">
          <span style="font-size:16px;font-weight:bold">期级资金快照（PROVISIONAL）</span>
          <span style="float:right">
            <el-button v-hasPermi="['business:topic:edit']" size="mini" @click="repairPurchaseKind">修复未知购票类型</el-button>
            <el-button v-hasPermi="['business:topic:edit']" size="mini" @click="openCostDialog">录入成本</el-button>
            <el-button v-hasPermi="['business:topic:edit']" size="mini" @click="openHoursDialog">登记计划工时</el-button>
            <!-- 2026-08-25：「确认实际工时」随自报整条退役而摘除（后端已直接拒）。
                 「登记计划工时」保留 —— 它是 F 的金额来源，由平台登记，不是自报。 -->
            <el-button v-hasPermi="['business:topic:edit']" type="primary" size="mini" @click="recomputeFunds">重算</el-button>
          </span>
        </div>

        <!-- ★这一排是「四个 A」在后台的唯一入口。此前 13 个新端点里只有工时那 2 个有按钮，
             而 A4 已经是硬开售闸：没有一版已审核的对公收款人，探店日期次在控制台上根本开不了售。 -->
        <div class="club-funds-actions mb10">
          <el-alert v-if="paymentRegistrationDisabled" class="mb10"
                    :title="paymentSeparationReason" :description="paymentSeparationAction"
                    :type="paymentSeparation ? 'error' : 'warning'" :closable="false" show-icon />
          <!-- ★这个按钮是上面所有「填 id」动作的前提：没有它，操作者只能连生产库去捞
               payableId / batchId。资金明细里的 id 就是下面各动作的候选值。 -->
          <el-button v-hasPermi="['business:editionfinal:execute', 'business:clubpayable:confirm', 'business:clubpayable:paid']"
                     type="primary" size="mini"
                     @click="loadEditionLedger">查本期资金明细</el-button>
          <el-button v-hasPermi="['business:club:query']" size="mini" @click="loadClubPayee">查对公收款人</el-button>
          <el-button v-hasPermi="['business:club:edit']" size="mini" @click="openPayeeDialog">写对公资料</el-button>
          <el-button v-hasPermi="['business:clubpayee:verify']" size="mini" @click="verifyPayee">财务审核收款人</el-button>
          <el-button v-hasPermi="['business:clubpayee:payout']" size="mini" @click="showPayoutDetail">付款解密</el-button>
          <!-- 2026-08-25：额外工时(EXTRA)两个按钮随「俱乐部自报工时」整条退役而摘除。
               后端 ClubEditionCompensationServiceImpl.rejectRetiredHoursWrite() 直接拒，
               留着等于给运营一个点了必然报错的入口。
               F(固定执行费，读开售冻结值)与 Q(质量奖励，下面两键)都不受影响。 -->
          <el-button v-hasPermi="['business:clubquality:confirm']" size="mini" @click="confirmQuality">质量奖励确认</el-button>
          <el-button v-hasPermi="['business:clubquality:exclude']" size="mini" @click="excludeQuality">过错排除该维</el-button>
          <!-- 名字必须与上面那张「期级资金快照」区分开：仓库里有两个 FINAL，
               六量快照那个至今抛 EDITION_FUNDS_FINAL_UNAVAILABLE、压根没实现。 -->
          <el-button v-hasPermi="['business:editionfinal:execute']" type="warning" size="mini" @click="finalizeEdition">俱乐部补偿 FINAL 结算</el-button>
          <el-button v-hasPermi="['business:editionfinal:execute']" size="mini" @click="reversePayable">应付红冲</el-button>
          <el-button v-hasPermi="['business:editionfinal:execute']" size="mini" @click="reaccruePayable">红冲后重计提</el-button>
          <el-button v-hasPermi="['business:clubpayable:confirm']" size="mini" @click="createBatch">建付款批次(本期)</el-button>
          <el-button v-hasPermi="['business:clubpayable:confirm']" size="mini" @click="confirmBatch">确认批次</el-button>
          <el-button v-hasPermi="['business:clubpayable:paid']" size="mini"
                     :disabled="paymentRegistrationDisabled" @click="markBatchPaid">登记付款</el-button>
          <el-button v-hasPermi="['business:clubpayable:confirm']" size="mini" @click="issueInvoice">开票</el-button>
          <el-button v-hasPermi="['business:clubpayable:confirm']" size="mini" @click="redInvoice">红字发票</el-button>
        </div>
        <el-empty v-if="!fundsSnapshot" description="尚未生成期级资金快照" />
        <el-descriptions v-else :column="3" border>
          <el-descriptions-item label="版本">{{ fundsSnapshot.version }}</el-descriptions-item>
          <el-descriptions-item label="现金单数">{{ fundsSnapshot.ncash }}</el-descriptions-item>
          <el-descriptions-item label="计算时间">{{ fundsSnapshot.computedAt }}</el-descriptions-item>
          <el-descriptions-item label="g_cash">{{ fundsSnapshot.gcash }}</el-descriptions-item>
          <el-descriptions-item label="u_out">{{ fundsSnapshot.uout }}</el-descriptions-item>
          <el-descriptions-item label="u_pending">{{ fundsSnapshot.upending }}</el-descriptions-item>
          <el-descriptions-item label="r_refund">{{ fundsSnapshot.rrefund }}</el-descriptions-item>
          <el-descriptions-item label="g_earned">{{ fundsSnapshot.gearned }}</el-descriptions-item>
        </el-descriptions>
        <el-descriptions v-if="financeOverview" class="mt10" :column="3" border>
          <el-descriptions-item label="快照口径">{{ financeOverview.snapshotKind === 'FINAL' ? '最终快照' : (financeOverview.snapshotKind === 'PROVISIONAL' ? '暂结快照' : '未知') }}</el-descriptions-item>
          <el-descriptions-item label="平台八项成本">{{ financeOverview.platformCostTotal }}</el-descriptions-item>
          <el-descriptions-item label="CM">{{ financeOverview.contributionMargin == null ? financeOverview.contributionMarginReason : financeOverview.contributionMargin }}</el-descriptions-item>
          <el-descriptions-item label="保本人数 N_BE">{{ financeOverview.breakEvenTickets == null ? financeOverview.breakEvenReason : financeOverview.breakEvenTickets }}（只展示，不作开售闸）</el-descriptions-item>
        </el-descriptions>
        <el-table v-if="financeOverview && financeOverview.hours && financeOverview.hours.length" class="mt10" :data="financeOverview.hours" border>
          <el-table-column prop="clubId" label="俱乐部" />
          <el-table-column prop="hourKind" label="工时类型" />
          <el-table-column prop="plannedHours" label="计划工时" />
          <el-table-column prop="reportedActualHours" label="自报工时" />
          <el-table-column prop="confirmedActualHours" label="确认工时" />
          <el-table-column prop="confirmStatus" label="确认状态" />
        </el-table>
      </el-card>

      <!-- 现场七段漏斗 + 现场服务达标线（只读）。
           ★ 为什么必须有这块：达标线是**硬性判定**（等待 P90 ≤20 分钟 且 样本覆盖率 ≥95%，
             不足即判不达标，不是提示），而在此之前它算得出、控制台上零读面 ——
             验收只能 curl，等于「有门禁没人看得见 verdict」。
           权限复用 business:topic:query（进得来这个页面就有），不新开权限点、不需要菜单迁移。 -->
      <el-card class="mt10" v-if="topicDetail.productType === 2">
        <div slot="header" class="clearfix">
          <span style="font-size:16px;font-weight:bold">现场漏斗与达标线（只读）</span>
          <span style="float:right">
            <el-button size="mini" :loading="funnelLoading" @click="loadFunnel">刷新</el-button>
          </span>
        </div>
        <el-alert v-if="funnelError" :title="funnelError" type="error" :closable="false" show-icon />
        <el-empty v-else-if="!funnel" description="尚无现场数据（本期还没有人进店/核销时这里是空的，不是坏了）" />
        <template v-else>
          <el-alert
            :title="funnel.g2Passed ? '达标判定：通过' : '达标判定：失败'"
            :type="funnel.g2Passed ? 'success' : 'error'"
            :closable="false"
            show-icon
            style="margin-bottom: 12px"
          >
            <!-- ⚠️ 不许把「没有原因」显示成「一切正常」：g2Passed=false 而 reasons 为空
                 说明后端换了口径，那时该去看后端而不是当成小问题。 -->
            <div v-if="funnel.g2Reasons && funnel.g2Reasons.length">
              <div v-for="(reason, idx) in funnel.g2Reasons" :key="idx">· {{ reason }}</div>
            </div>
            <div v-else-if="!funnel.g2Passed">未返回不达标原因，请联系技术核对数据口径</div>
          </el-alert>
          <el-descriptions :column="4" border>
            <el-descriptions-item label="N_service 应服务人数">{{ funnel.nService }}</el-descriptions-item>
            <el-descriptions-item label="A 到店">{{ funnel.arrived }}</el-descriptions-item>
            <el-descriptions-item label="S1 首店服务">{{ funnel.s1 }}</el-descriptions-item>
            <el-descriptions-item label="S3 三店服务">{{ funnel.s3 }}</el-descriptions-item>
            <el-descriptions-item label="C 通关">{{ funnel.completed }}</el-descriptions-item>
            <el-descriptions-item label="J 自愿关注/入群">{{ funnel.joined }}</el-descriptions-item>
            <el-descriptions-item label="R90 观察中">{{ funnel.r90Observing }}</el-descriptions-item>
            <el-descriptions-item label="R90 已满窗 / 复访">
              {{ funnel.r90Eligible }} / {{ funnel.r90Completed }}
            </el-descriptions-item>
            <el-descriptions-item label="到店率">{{ toPercent(funnel.arrivalRate) }}</el-descriptions-item>
            <el-descriptions-item label="通关率">{{ toPercent(funnel.completionRate) }}</el-descriptions-item>
            <el-descriptions-item label="退款人数 / 率">
              {{ funnel.refunded }} / {{ toPercent(funnel.refundRate) }}
            </el-descriptions-item>
            <el-descriptions-item label="投诉人数 / 率">
              {{ funnel.complaintUsers }} / {{ toPercent(funnel.complaintUserRate) }}
            </el-descriptions-item>
            <el-descriptions-item label="等待样本覆盖率">
              <span :class="{ 'danger-text': !(funnel.waitCoverage >= 0.95) }">{{ toPercent(funnel.waitCoverage) }}</span>
              <span style="color:#909399"> （达标要求 ≥95%）</span>
            </el-descriptions-item>
            <el-descriptions-item label="等待门">
              {{ funnel.waitGatePassed ? '通过' : '失败' }}
            </el-descriptions-item>
            <el-descriptions-item label="负等待样本" :span="2">
              {{ funnel.negativeWaitSamples }}
              <span v-if="funnel.negativeWaitWarning" class="danger-text">（存在负等待，进店时间晚于服务开始 ⇒ 数据可疑）</span>
            </el-descriptions-item>
          </el-descriptions>
          <!-- 逐店：达标线的等待门是**逐章判**的，混在一起算会把某一家的爆队藏起来。 -->
          <el-table
            v-if="funnel.chapterWaits && funnel.chapterWaits.length"
            class="mt10"
            :data="funnel.chapterWaits"
            border
          >
            <el-table-column label="章节" prop="chapterId" align="center" width="100" />
            <el-table-column label="等待 P90(分钟)" align="center" width="150">
              <template slot-scope="{row}">
                <!-- ⚠️ p90Minutes 为 null 是「样本不足，按 fail-closed 判失败」，不是 0 分钟。
                     显示成 0 会被读成「完全不用等」——恰好把该红的那一格粉饰成最好看的一格。 -->
                <span :class="{ 'danger-text': row.p90Minutes != null && row.p90Minutes > 20 }">
                  {{ row.p90Minutes == null ? '样本不足' : row.p90Minutes }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="样本 / 人数" align="center" width="130">
              <template slot-scope="{row}">{{ row.sampleSize }} / {{ row.population }}</template>
            </el-table-column>
            <el-table-column label="本章覆盖率" align="center" width="120">
              <template slot-scope="{row}">
                <span :class="{ 'danger-text': !(row.population > 0 && row.sampleSize / row.population >= 0.95) }">
                  {{ row.population > 0 ? toPercent(row.sampleSize / row.population) : '无人数' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="判定" align="center" width="100">
              <template slot-scope="{row}">
                <el-tag :type="row.passed ? 'success' : 'danger'" size="mini">
                  {{ row.passed ? '通过' : '失败' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </el-card>

      <!-- 章节信息 -->
      <el-card class="mt10">
        <div slot="header" class="clearfix">
          <div style="display: flex; justify-content: space-between; align-items: center;">
            <span style="font-size: 16px;font-weight: bold">章节信息</span>
            <el-button
              v-if="canAddChapter"
              type="primary"
              size="mini"
              icon="el-icon-plus"
              @click="handleAddChapter"
            >新增章节</el-button>
          </div>
        </div>
        <el-alert
          title="首期可先建四个店位章节；章节数量不是领域上限，可按实际路线继续新增。"
          type="info"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
        />
        <el-empty v-if="!chapterList || chapterList.length === 0" description="暂无章节，先创建第一个店位章节">
          <el-button v-if="canAddChapter" type="primary" size="small" @click="handleAddChapter">新增章节</el-button>
        </el-empty>
        <el-collapse v-else v-model="activeChapterNames">
          <el-collapse-item 
            v-for="chapter in chapterList"
            :key="chapter.id"
            :name="chapter.id"
            :title="chapter.name"
          >
            <el-descriptions :column="2" border>
              <el-descriptions-item label="章节描述">{{ chapter.description }}</el-descriptions-item>
              <el-descriptions-item label="章节时长">{{ formatTime(chapter.totalTime) }}</el-descriptions-item>
              <el-descriptions-item label="章节里程">{{ chapter.totalMileage }}KM</el-descriptions-item>
              <el-descriptions-item label="地点数">{{ chapter.locationCount }}</el-descriptions-item>
              <el-descriptions-item label="模板数">{{ chapter.templateCount }}</el-descriptions-item>
              <el-descriptions-item label="排序ID">{{ chapter.sortId }}</el-descriptions-item>
              <!-- [D21] 条款档决定这一章的钱怎么算，所以既要看得见、也要改得了。
                   ⚠️ 不填 = 权益档（与 DDL DEFAULT 'PERK' 同口径），别把空显示成「未设置」——
                   那会让人以为这章还没定档，其实它已经在按权益档收钱了。 -->
              <el-descriptions-item label="供给条款">
                <el-tag :type="termsModeTagType(chapter.termsMode)" size="mini">
                  {{ termsModeLabel(chapter.termsMode) }}
                </el-tag>
                <!-- ★ 必须把上限也显示出来：计酬档没配上限时商家入驻恒被拒，
                     而后端不会（也不该）替运营报警——运营只能靠这里看出「这章为什么没人入驻」。 -->
                <el-tag
                  v-if="(chapter.termsMode || 'PERK') === 'REVSHARE' && !(chapter.maxPerHeadFee > 0)"
                  type="danger" size="mini" style="margin-left: 6px"
                >⚠️ 未配人头费上限，商家进不来</el-tag>
                <span v-else-if="(chapter.termsMode || 'PERK') === 'REVSHARE'" style="margin-left: 6px; color: #909399">
                  ≤{{ chapter.maxPerHeadFee }} 元/人次
                </span>
                <span v-else-if="(chapter.termsMode || 'PERK') === 'PERK' && chapter.perkMinValue" style="margin-left: 6px; color: #909399">
                  门槛 ≥{{ chapter.perkMinValue }} 元
                </span>
                <span v-if="chapter.maxMerchant > 0" style="margin-left: 6px; color: #909399">
                  · 限 {{ chapter.maxMerchant }} 家
                </span>
                <el-button
                  v-if="canEditChapter"
                  type="text"
                  size="mini"
                  style="margin-left: 8px"
                  @click="openTermsModeDialog(chapter)"
                >配置</el-button>
              </el-descriptions-item>
              <el-descriptions-item label="人工批次容量">
                <span>{{ serviceCapacitySummary(chapter.id) }}</span>
                <el-button
                  v-if="canEditChapter"
                  type="text"
                  size="mini"
                  style="margin-left: 8px"
                  @click="openServiceCapacityDialog(chapter)"
                >配置</el-button>
              </el-descriptions-item>
              <el-descriptions-item label="章节操作" :span="2">
                <el-button v-if="canEditChapter" type="text" size="mini" @click="handleEditChapter(chapter)">编辑</el-button>
                <el-button v-if="canRemoveChapter" type="text" size="mini" class="danger-text" @click="handleDeleteChapter(chapter)">删除</el-button>
              </el-descriptions-item>
            </el-descriptions>
            
            <!-- 章节图片 -->
            <div class="chapter-images mt10" v-if="getChapterImages(chapter.imgArr).length > 0">
              <div class="label">章节图片：</div>
              <div class="img-list">
                <el-image 
                  v-for="(img, imgIndex) in getChapterImages(chapter.imgArr)" 
                  :key="imgIndex" 
                  :src="img" 
                  :preview-src-list="getChapterImages(chapter.imgArr)" 
                  style="width: 120px; height: 80px; margin-right: 10px;"
                  fit="cover"
                ></el-image>
              </div>
            </div>
            
            <!-- 节点信息（店位）。首三期平台组局：节点由平台在这里从零建，不开放商家自助发布。 -->
            <div class="node-list mt20">
              <div class="label" style="display:flex;justify-content:space-between;align-items:center">
                <span>节点（店位）信息：</span>
                <el-button
                  v-if="canAddNode"
                  type="primary"
                  size="mini"
                  icon="el-icon-plus"
                  @click="handleAddNode(chapter)"
                >新增节点</el-button>
              </div>
              <el-empty
                v-if="getChapterNodes(chapter.id).length === 0"
                description="本章还没有店位节点。没有节点 ⇒ 玩家扫不到码 ⇒ 这一章永远进不了店。"
              >
                <el-button v-if="canAddNode" type="primary" size="small" @click="handleAddNode(chapter)">新增节点</el-button>
              </el-empty>
              <el-table v-else :data="getChapterNodes(chapter.id)" border>
                <el-table-column label="节点名称" prop="name" align="center"></el-table-column>
                <el-table-column label="节点地址" prop="address" align="center"></el-table-column>
                <el-table-column label="节点时间" align="center" width="100">
                  <template slot-scope="{row}">
                    {{ row.nodeTime }}分钟
                  </template>
                </el-table-column>
                <el-table-column label="营业时间" prop="businessTime" align="center" width="120"></el-table-column>
                <!-- 归属商家 memberId：绑上它，商家在小程序侧才取得到自己这家店的海报码。
                     审核态由后端强制置「已过审」—— 绑了商家却没置，节点会在玩家侧静默消失。 -->
                <el-table-column label="归属商家(memberId)" align="center" width="160">
                  <template slot-scope="{row}">
                    <span v-if="!row.merchantMemberId" style="color:#909399">平台自建</span>
                    <span v-else>
                      {{ row.merchantMemberId }}
                      <el-tag :type="row.nodeAuditStatus === 1 ? 'success' : 'danger'" size="mini">
                        {{ nodeAuditLabel(row.nodeAuditStatus) }}
                      </el-tag>
                    </span>
                  </template>
                </el-table-column>
                <!-- 打卡码只读：换码 = 店里已印好的海报全部作废，所以不给编辑入口。 -->
                <el-table-column label="打卡码(二维码内容·只读)" align="center" width="240">
                  <template slot-scope="{row}">
                    <span v-if="row.checkinCode" style="font-family:monospace;font-size:12px">{{ row.checkinCode }}</span>
                    <span v-else class="danger-text">未生成 · 玩家扫不出来</span>
                  </template>
                </el-table-column>
                <el-table-column label="状态" align="center" width="80">
                  <template slot-scope="{row}">
                    <el-tag :type="row.status === 1 ? 'success' : 'info'">
                      {{ row.status === 1 ? '显示' : '隐藏' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="排序" prop="sortId" align="center" width="80"></el-table-column>
                <el-table-column label="节点操作" align="center" width="120">
                  <template slot-scope="{row}">
                    <el-button v-if="canEditNode" type="text" size="mini" @click="handleEditNode(chapter, row)">编辑</el-button>
                    <el-button v-if="canRemoveNode" type="text" size="mini" class="danger-text" @click="handleDeleteNode(row)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </el-collapse-item>
        </el-collapse>
      </el-card>
      
      <!-- 商家信息 -->
      <el-card class="mt10" v-if="merchantList && merchantList.length > 0">
        <div slot="header" class="clearfix">
          <span style="font-size: 16px;font-weight: bold">商家信息</span>
        </div>
        <el-table :data="merchantList" border>
          <el-table-column label="商家名称" prop="name" align="center"></el-table-column>
          <el-table-column label="商家地址" prop="address" align="center"></el-table-column>
          <el-table-column label="营业时间" prop="business_hours" align="center"></el-table-column>
          <el-table-column label="联系人" prop="contact" align="center"></el-table-column>
          <el-table-column label="联系电话" prop="phone" align="center"></el-table-column>
          <el-table-column label="状态" align="center" width="80">
            <template slot-scope="{row}">
              <el-tag :type="row.status === 1 ? 'success' : 'info'">
                {{ row.status === 1 ? '合作中' : '已结束' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
      
      <!-- 模板信息 -->
      <el-card class="mt10" v-if="templateList && templateList.length > 0">
        <div slot="header" class="clearfix">
          <span style="font-size: 16px;font-weight: bold">模板信息</span>
        </div>
        <el-table :data="templateList" border>
          <el-table-column label="模板名称" prop="name" align="center"></el-table-column>
          <el-table-column label="模板类型" prop="type" align="center">
            <template slot-scope="{row}">
              <dict-tag :options="dict.type.cms_template_type" :value="row.type" />
            </template>
          </el-table-column>
          <el-table-column label="使用次数" prop="use_count" align="center"></el-table-column>
          <el-table-column label="评分" prop="rating" align="center">
            <template slot-scope="{row}">
              <el-rate v-model="row.rating" disabled show-score text-color="#ff9900" score-template="{value}"></el-rate>
            </template>
          </el-table-column>
          <el-table-column label="状态" align="center" width="80">
            <template slot-scope="{row}">
              <el-tag :type="row.status === 1 ? 'success' : 'info'">
                {{ row.status === 1 ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" prop="create_time" align="center" width="120"></el-table-column>
        </el-table>
      </el-card>
      
      <!-- 阵容信息 -->
      <el-card class="mt10" v-if="lineupList && lineupList.length > 0">
        <div slot="header" class="clearfix">
          <span style="font-size: 16px;font-weight: bold">阵容信息</span>
        </div>
          <el-row :gutter="20">
          <el-col :span="8" v-for="(item, index) in lineupList" :key="index" class="mb20">
            <div class="lineup-item text-center">
              <el-avatar :size="80" :src="item.memberAvatar" class="mb10"></el-avatar>
              <div class="name">{{ item.memberRealName }}/{{ item.memberId }}</div>
              <div class="role">艺人</div>
              <!-- <div class="rating" v-if="item.rating">
                <el-rate v-model="item.rating" disabled show-score text-color="#ff9900" score-template="{value}"></el-rate>
              </div> -->
            </div>
          </el-col>
        </el-row>
      </el-card>

      <!-- 票务信息 -->
      <el-card class="mt10">
        <div slot="header" class="clearfix"><span style="font-size: 16px;font-weight: bold">承接履约</span></div>
        <el-empty v-if="leadFulfillment.length === 0" description="暂无带队场次（数据来自活动与带队状态，只读）" />
        <el-table v-else :data="leadFulfillment" border>
          <el-table-column label="活动ID" prop="activityId" width="90" align="center" />
          <el-table-column label="责任领队" prop="leaderMemberId" width="100" align="center" />
          <el-table-column label="当前章节" prop="currentChapterId" width="100" align="center" />
          <el-table-column label="状态" prop="status" width="90" align="center">
            <template slot-scope="{row}"><el-tag size="mini">{{ leadStatus(row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="到场人数" width="100" align="center">
            <template slot-scope="{row}">{{ (row.checkins || []).length }}</template>
          </el-table-column>
          <el-table-column label="最近播报" prop="broadcastText" min-width="180" show-overflow-tooltip />
        </el-table>
      </el-card>

      <!-- 票务信息 -->
      <el-card class="mt10">
        <div slot="header" class="clearfix">
          <div style="display: flex; justify-content: space-between; align-items: center;">
            <span style="font-size: 16px;font-weight: bold">票务信息</span>
            <el-button
              type="primary"
              size="mini"
              icon="el-icon-plus"
              v-if="canAddTicket"
              @click="handleAddTicket"
            >添加票务</el-button>
          </div>
        </div>
        
        <template v-if="ticketList && ticketList.length > 0">
          <el-table :data="ticketList" border>
            <el-table-column label="票务名称" prop="name" align="center"></el-table-column>
            <el-table-column label="有效时间" align="center" width="220">
              <template slot-scope="{row}">
                {{ formatDate(row.startTime) }} 至 {{ formatDate(row.endTime) }}
              </template>
            </el-table-column>
            <el-table-column label="价格" prop="price" align="center" width="100">
              <template slot-scope="{row}">
                ¥{{ row.price }}
              </template>
            </el-table-column>
            <el-table-column label="总库存" prop="totalInventory" align="center" width="80"></el-table-column>
            <el-table-column label="剩余库存" prop="remainingInventory" align="center" width="80"></el-table-column>
            <el-table-column label="操作" align="center" width="100">
              <template slot-scope="{row, $index}">
                <el-button
                  type="text"
                  size="mini"
                  v-if="canEditTicket"
                  @click="handleEditTicket(row, $index)"
                >编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
        </template>
        <template v-else>
          <div class="text-center" style="padding: 20px; color: #909399;">
            <i class="el-icon-tickets" style="font-size: 48px; margin-bottom: 10px;"></i>
            <p>暂无票务信息，点击添加票务按钮创建票务</p>
          </div>
        </template>
      </el-card>

    </el-main>

    <!-- 编辑票务对话框 -->
    <el-dialog :title="ticketDialogTitle" :visible.sync="ticketDialogVisible" width="500px" append-to-body :close-on-click-modal="false">
      <el-form ref="ticketForm" :model="ticketForm" :rules="ticketRules" label-width="100px">
        <el-form-item label="票务名称" prop="name">
          <el-input v-model="ticketForm.name" placeholder="请输入票务名称" />
        </el-form-item>
        <el-form-item label="价格" prop="price">
          <el-input-number 
            v-model="ticketForm.price" 
            :precision="2" 
            :min="0" 
            :step="0.01" 
            style="width: 100%;" 
            placeholder="请输入价格"
          />
        </el-form-item>
        <el-form-item label="总库存" prop="totalInventory">
          <el-input-number 
            v-model="ticketForm.totalInventory" 
            :min="0" 
            :step="1"
            style="width: 100%;" 
            placeholder="请输入总库存"
          />
        </el-form-item>
        <el-form-item label="开始时间" prop="startTime">
          <el-date-picker
            v-model="ticketForm.startTime"
            type="datetime"
            placeholder="选择开始时间"
            format="yyyy-MM-dd HH:mm:ss"
            value-format="yyyy-MM-dd HH:mm:ss"
            style="width: 100%;"
          />
        </el-form-item>
        <el-form-item label="结束时间" prop="endTime">
          <el-date-picker
            v-model="ticketForm.endTime"
            type="datetime"
            placeholder="选择结束时间"
            format="yyyy-MM-dd HH:mm:ss"
            value-format="yyyy-MM-dd HH:mm:ss"
            style="width: 100%;"
          />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button @click="ticketDialogVisible = false">取 消</el-button>
        <el-button type="primary" @click="submitTicketForm" :loading="ticketLoading">确 定</el-button>
      </div>
    </el-dialog>

    <!-- 章节新增 / 编辑。排序通过真实 sortId 写回，列表重新读取后按服务端顺序展示。 -->
    <el-dialog :title="chapterDialogTitle" :visible.sync="chapterDialogVisible" width="560px" append-to-body :close-on-click-modal="false">
      <el-form ref="chapterForm" :model="chapterForm" :rules="chapterRules" label-width="120px">
        <el-form-item label="章节名称" prop="name">
          <el-input v-model="chapterForm.name" maxlength="100" show-word-limit placeholder="例如：第一站 · 咖啡店" />
        </el-form-item>
        <el-form-item label="章节描述" prop="description">
          <el-input v-model="chapterForm.description" type="textarea" :rows="4" placeholder="填写本章体验与到店说明" />
        </el-form-item>
        <el-form-item label="排序" prop="sortId">
          <el-input-number v-model="chapterForm.sortId" :min="1" :step="1" :precision="0" />
          <span style="margin-left: 8px; color: #909399">数字越小越靠前</span>
        </el-form-item>
        <el-form-item label="是否必选" prop="required">
          <el-radio-group v-model="chapterForm.required">
            <el-radio :label="1">必选章节</el-radio>
            <el-radio :label="0">可选章节</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="开启招商" prop="recruitEnabled">
          <el-switch v-model="chapterForm.recruitEnabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item v-if="chapterForm.recruitEnabled === 1" label="商家类目ID" prop="categoryId">
          <el-input-number v-model="chapterForm.categoryId" :min="1" :step="1" :precision="0" />
          <span style="margin-left: 8px; color: #909399">开启招商时由后端校验类目</span>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button @click="chapterDialogVisible = false">取 消</el-button>
        <el-button type="primary" :loading="chapterSaving" @click="submitChapterForm">确 定</el-button>
      </div>
    </el-dialog>

    <!-- 节点（店位）新增 / 编辑。首三期平台组局：平台在这里代建，不开放商家自助发布。
         ⚠️ 打卡码不在表单里：新建时后端生成（32 位十六进制、SecureRandom），编辑时一律不回传 ——
            换码会让店里已印好的海报全部作废。 -->
    <el-dialog :title="nodeDialogTitle" :visible.sync="nodeDialogVisible" width="600px" append-to-body :close-on-click-modal="false">
      <el-form ref="nodeForm" :model="nodeForm" :rules="nodeRules" label-width="150px">
        <el-form-item label="所属章节">
          <span>{{ nodeForm.chapterName }}</span>
        </el-form-item>
        <el-form-item label="节点名称" prop="name">
          <el-input v-model="nodeForm.name" maxlength="80" show-word-limit placeholder="例如：毛孩子咖啡" />
        </el-form-item>
        <el-form-item label="节点描述" prop="description">
          <el-input v-model="nodeForm.description" type="textarea" :rows="3" placeholder="到店后要做什么、注意什么" />
        </el-form-item>
        <el-form-item label="门店地址" prop="address">
          <el-input v-model="nodeForm.address" placeholder="玩家导航用的门店地址" />
        </el-form-item>
        <el-form-item label="经度 / 纬度">
          <el-input v-model="nodeForm.longitude" style="width: 45%" placeholder="经度 longitude" />
          <span style="margin: 0 6px">/</span>
          <el-input v-model="nodeForm.latitude" style="width: 45%" placeholder="纬度 latitude" />
        </el-form-item>
        <el-form-item label="营业时间" prop="businessTime">
          <el-input v-model="nodeForm.businessTime" placeholder="例如：10:00-22:00" />
        </el-form-item>
        <el-form-item label="预计停留(分钟)" prop="nodeTime">
          <el-input v-model="nodeForm.nodeTime" placeholder="例如：30" />
        </el-form-item>
        <el-form-item label="归属商家 memberId" prop="merchantMemberId">
          <el-input-number v-model="nodeForm.merchantMemberId" :min="1" :step="1" :precision="0" controls-position="right" />
          <div style="color:#909399;line-height:1.5">
            填了商家 memberId，该商家才能在小程序侧取到这家店的海报码（需同章节有生效承接供给）。
            <br>留空 = 平台自建点位。⚠️ 这里填的是 <b>memberId</b>，不是 mms_merchant.id；
            审核态由后端强制置「已过审」，不需要也不能在这里改。
          </div>
        </el-form-item>
        <el-form-item label="排序" prop="sortId">
          <el-input-number v-model="nodeForm.sortId" :min="1" :step="1" :precision="0" />
          <span style="margin-left: 8px; color: #909399">数字越小越靠前</span>
        </el-form-item>
        <el-form-item label="是否显示" prop="status">
          <el-radio-group v-model="nodeForm.status">
            <el-radio :label="1">显示</el-radio>
            <el-radio :label="0">隐藏</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="nodeForm.id" label="打卡码(只读)">
          <span style="font-family:monospace;font-size:12px">{{ nodeForm.checkinCode || '未生成' }}</span>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button @click="nodeDialogVisible = false">取 消</el-button>
        <el-button type="primary" :loading="nodeSaving" @click="submitNodeForm">确 定</el-button>
      </div>
    </el-dialog>

    <!-- [D21] 章节供给条款配置 -->
    <el-dialog title="配置供给条款" :visible.sync="termsModeDialog.visible" width="560px" append-to-body :close-on-click-modal="false">
      <el-form label-width="110px">
        <el-form-item label="章节">
          <span>{{ termsModeDialog.chapterName }}</span>
        </el-form-item>
        <el-form-item label="条款档">
          <el-radio-group v-model="termsModeDialog.termsMode">
            <el-radio label="PERK">权益档</el-radio>
            <el-radio label="REVSHARE">计酬档</el-radio>
            <el-radio label="TRAFFIC">引流档</el-radio>
          </el-radio-group>
        </el-form-item>
        <!-- ⚠️ 计酬档必填：留空 = 商家入驻恒被拒（后端 fail-closed）= 配了个谁也进不来的章 -->
        <el-form-item v-if="termsModeDialog.termsMode === 'REVSHARE'" label="人头费上限" required>
          <el-input-number v-model="termsModeDialog.maxPerHeadFee" :min="0.01" :precision="2" :step="1" />
          <span style="margin-left: 8px; color: #909399">元/人次 · <b>必填</b>，商家自报的人头费不得超过它</span>
        </el-form-item>
        <!-- 只有权益档有门槛；空 = 不设门槛（后端同口径） -->
        <el-form-item v-if="termsModeDialog.termsMode === 'PERK'" label="权益门槛">
          <el-input-number v-model="termsModeDialog.perkMinValue" :min="0.01" :precision="2" :step="1" />
          <span style="margin-left: 8px; color: #909399">元 · 留空 = 不设门槛</span>
        </el-form-item>
        <el-form-item label="商家数上限">
          <el-input-number v-model="termsModeDialog.maxMerchant" :min="0" :max="127" :step="1" />
          <span style="margin-left: 8px; color: #909399">家 · 0 = 不限（三档通用）</span>
        </el-form-item>
      </el-form>
      <el-alert type="warning" :closable="false" show-icon>
        <div slot="title" style="font-weight: normal; line-height: 1.7">
          <div><b>条款档决定这一章的钱怎么算，改之前先看清：</b></div>
          <div>· <b>权益档</b>：商家承诺实物权益，走名额上限（超卖了商家赔不起）。</div>
          <div>· <b>计酬档</b>：<b>平台按人头付现金</b>给商家。只能标在<b>平台自营</b>路线上——
            后台发的主题天然是自营；若被拒，说明这条路线是小程序发布器建的，
            需要先把它的内容槽改判为 PLATFORM，<b>不要绕开这道闸</b>。
            <b>人头费上限必填</b>——不填的话商家一个都进不来，而且没有任何报错。</div>
          <div>· <b>引流档</b>：零承诺零结算，商家接待打卡即可。</div>
          <div style="margin-top: 6px">
            ⚠️ <b>改档位</b>时该章已有生效商家供给会被拒：商家入驻时谈定的条款是当时的快照，
            中途改档会让两者打架（存量权益档商家的接待上限会失守）。要改先下掉供给。
            <b>同档位下只调上限/门槛不受此限</b>——给已在营的章补配上限就走这里。
          </div>
        </div>
      </el-alert>
      <div slot="footer" class="dialog-footer">
        <el-button @click="termsModeDialog.visible = false">取 消</el-button>
        <el-button type="primary" :loading="termsModeDialog.loading" @click="submitTermsMode">确 定</el-button>
      </div>
    </el-dialog>

    <el-dialog title="配置人工批次容量" :visible.sync="serviceCapacityDialog.visible" width="520px" append-to-body :close-on-click-modal="false">
      <el-form label-width="150px" v-loading="serviceCapacityDialog.loading">
        <el-form-item label="章节">
          <span>{{ serviceCapacityDialog.chapterName }}</span>
        </el-form-item>
        <el-form-item label="每批人数" required>
          <el-input-number v-model="serviceCapacityDialog.batchSize" :min="1" :step="1" :precision="0" />
          <span style="margin-left: 8px; color: #909399">人</span>
        </el-form-item>
        <el-form-item label="批次间隔" required>
          <el-input-number v-model="serviceCapacityDialog.batchIntervalMinutes" :min="1" :step="1" :precision="0" />
          <span style="margin-left: 8px; color: #909399">分钟</span>
        </el-form-item>
        <el-form-item label="最大同时接待" required>
          <el-input-number v-model="serviceCapacityDialog.effortMaxConcurrent" :min="1" :step="1" :precision="0" />
          <span style="margin-left: 8px; color: #909399">人</span>
        </el-form-item>
        <el-alert title="三项必须一次性填写正整数；本配置只记录人工批次能力，不会自动叫号或分流。" type="info" :closable="false" show-icon />
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button @click="serviceCapacityDialog.visible = false">取 消</el-button>
        <el-button type="primary" :loading="serviceCapacityDialog.loading" @click="submitServiceCapacity">确 定</el-button>
      </div>
    </el-dialog>

    <el-dialog title="录入期级成本" :visible.sync="costDialogVisible" width="680px" append-to-body>
      <el-form label-width="150px">
        <el-form-item v-for="field in costMoneyFields" :key="field.key" :label="field.label">
          <el-input-number v-model="costForm[field.key]" :min="0" :precision="2" :step="1" />
        </el-form-item>
        <el-form-item label="平台现场工时"><el-input-number v-model="costForm.platformOnsiteHours" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="N_BE 其他固定成本"><el-input-number v-model="costForm.breakEvenFixedCost" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="N_BE 每票可变成本"><el-input-number v-model="costForm.variableCostPerTicket" :min="0" :precision="2" /></el-form-item>
        <!-- ★承接费封顶必须【不】跟着 costMoneyFields 兜成 0：那个数组的契约是「null → 0」，
             而这里 0 和「留空」是两个完全不同的意思 —— cap=0 会让 min(单价×票数, 0) 恒等于 0，
             配了单价也一分不发，且零告警。同 extraApproverUserId 的 0-豁免，理由一样。 -->
        <el-form-item label="承接费封顶">
          <el-input-number v-model="costForm.headcountCap" :min="0" :precision="2" />
          <span class="el-upload__tip">留空 = 不封顶。填 0 会被当成留空（0 元封顶等于不发，要不发请把单价填 0）。</span>
        </el-form-item>
        <!-- ★项目负责人：EXTRA 事前审批人，开售时冻进条款与指纹，开售后再改这里也改不动它。
             不填 / 填一个按不动「额外工时事前预批」的账号，开售会被后端拒绝并点名。 -->
        <el-form-item label="项目负责人 userId">
          <el-input-number v-model="costForm.extraApproverUserId" :min="1" :precision="0" />
          <span class="el-upload__tip">
            EXTRA 事前审批人（<b>项目负责人</b>），开售冻结后不可改。该账号必须持
            business:clubextra:approve，否则开售会被拒。
          </span>
        </el-form-item>
        <el-form-item label="构成说明"><el-input v-model="costForm.note" type="textarea" /></el-form-item>
      </el-form>
      <div slot="footer"><el-button @click="costDialogVisible=false">取消</el-button><el-button type="primary" @click="submitCost">保存</el-button></div>
    </el-dialog>

    <!-- 工时不再是「一个表单塞所有列」：计划工时开售即冻结，实际工时确认要专用权限且旧状态必须是 REPORTED
         （自报由俱乐部在小程序侧做，后台不代填）。所以拆成两个窄动作，各自一个按钮。 -->
    <el-dialog title="登记计划工时(开售前)" :visible.sync="hoursDialogVisible" width="560px" append-to-body>
      <el-form label-width="130px">
        <el-form-item label="俱乐部ID"><el-input-number v-model="hoursForm.clubId" :min="1" :precision="0" /></el-form-item>
        <el-form-item label="工时类型"><el-select v-model="hoursForm.hourKind"><el-option v-for="k in ['PREP','CONTENT','ONSITE','REVIEW']" :key="k" :label="k" :value="k" /></el-select></el-form-item>
        <el-form-item label="计划工时"><el-input-number v-model="hoursForm.plannedHours" :min="0" :precision="2" /></el-form-item>
        <el-form-item><span class="el-upload__tip">开售冻结后计划工时不可再改，届时保存会被后端拒绝。</span></el-form-item>
      </el-form>
      <div slot="footer"><el-button @click="hoursDialogVisible=false">取消</el-button><el-button type="primary" @click="submitPlanHours">保存</el-button></div>
    </el-dialog>

    <!-- A4 对公收款人：九组最小资料。缺任何一组后端逐组点名拒绝；账户名必须等于法定名称。
         写完还要走「财务一次审核」，未审核的俱乐部承接的探店日期次开不了售。 -->
    <el-dialog title="俱乐部对公收款人资料" :visible.sync="payeeDialogVisible" width="640px" append-to-body>
      <el-form label-width="170px">
        <el-form-item label="俱乐部ID"><el-input-number v-model="payeeForm.clubId" :min="1" :precision="0" /></el-form-item>
        <el-form-item label="法定名称"><el-input v-model="payeeForm.legalName" /></el-form-item>
        <el-form-item label="统一社会信用代码"><el-input v-model="payeeForm.unifiedSocialCreditCode" /></el-form-item>
        <el-form-item label="开户行"><el-input v-model="payeeForm.bankName" /></el-form-item>
        <el-form-item label="对公账号"><el-input v-model="payeeForm.corporateBankAccountNo" /></el-form-item>
        <el-form-item label="账户名"><el-input v-model="payeeForm.bankAccountName" placeholder="必须与法定名称完全一致" /></el-form-item>
        <el-form-item label="发票类型"><el-input v-model="payeeForm.invoiceType" /></el-form-item>
        <el-form-item label="税号"><el-input v-model="payeeForm.taxNo" placeholder="留空默认取统一社会信用代码" /></el-form-item>
        <el-form-item label="财务联系人"><el-input v-model="payeeForm.financeContactName" /></el-form-item>
        <el-form-item label="财务联系电话"><el-input v-model="payeeForm.financeContactPhone" /></el-form-item>
        <el-form-item label="执照对象存储 key"><el-input v-model="payeeForm.licenseObjectKey" placeholder="私有对象存储 key，不是公网 URL" /></el-form-item>
        <el-form-item label="执照文件 SHA-256"><el-input v-model="payeeForm.licenseHash" /></el-form-item>
        <el-form-item>
          <span class="el-upload__tip">
            提交后新建一版待审。审核是<b>一次</b>财务审核，本仓刻意不设 maker/checker；
            「俱乐部不得自审」由 app 侧零 payee 入口保证。
          </span>
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="payeeDialogVisible=false">取消</el-button>
        <el-button type="primary" @click="submitPayee">保存新版本</el-button>
      </div>
    </el-dialog>

    <el-dialog title="探店日俱乐部资金动作" :visible.sync="clubFundsDialogVisible" width="640px" append-to-body>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="当前对公收款人">
          <span v-if="payeeView">{{ payeeView.legalName }} / {{ payeeView.verifyStatus }} / v{{ payeeView.version }}</span>
          <span v-else>未查询</span>
        </el-descriptions-item>
      </el-descriptions>
      <div slot="footer"><el-button @click="clubFundsDialogVisible=false">关闭</el-button></div>
    </el-dialog>

    <!-- ★本期资金明细：应付原始行 / 批次双轴 / 三维质量决定 / EXTRA 审批链。
         这里的 id 就是红冲、更正、批次四个动作要填的那些 —— 不看这张表就只能连库猜。 -->
    <el-dialog title="本期资金明细（应付 / 批次 / 质量 / EXTRA）" :visible.sync="ledgerDialogVisible"
               width="900px" append-to-body>
      <el-alert v-if="paymentRegistrationDisabled" class="mb10"
                :title="paymentSeparationReason" :description="paymentSeparationAction"
                type="error" :closable="false" show-icon />
      <el-descriptions :column="3" border size="mini" class="mb10">
        <el-descriptions-item label="俱乐部补偿 FINAL">
          {{ editionLedger.finalization ? editionLedger.finalization.finalStatus : '未终结' }}
        </el-descriptions-item>
        <el-descriptions-item label="冻结 F">
          {{ editionLedger.terms ? editionLedger.terms.fixedCompensation : '未冻结' }}
        </el-descriptions-item>
        <el-descriptions-item label="冻结审批人 userId">
          {{ editionLedger.terms ? editionLedger.terms.extraApproverUserId : '未冻结' }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="mb10" style="font-weight:bold">应付原始行（红冲要填的 id 在这里）</div>
      <el-table :data="editionLedger.payables || []" size="mini" border>
        <el-table-column prop="id" label="id" width="70" />
        <el-table-column prop="component" label="行类型" width="90" />
        <el-table-column prop="entryType" label="方向" width="90" />
        <el-table-column prop="amount" label="金额" width="110" />
        <el-table-column prop="batchId" label="所属批次" width="90" />
        <el-table-column prop="reason" label="理由" show-overflow-tooltip />
      </el-table>

      <div class="mb10 mt10" style="font-weight:bold">付款批次（确认 / 付款 / 开票要填的 id 在这里）</div>
      <el-table :data="editionLedger.batches || []" size="mini" border>
        <el-table-column prop="id" label="id" width="70" />
        <el-table-column prop="batchNo" label="批次号" width="150" />
        <el-table-column prop="totalAmount" label="总额" width="110" />
        <el-table-column prop="batchStatus" label="付款轴" width="100" />
        <el-table-column prop="invoiceStatus" label="发票轴" width="90" />
        <el-table-column prop="invoiceNo" label="发票号" />
        <el-table-column prop="redInvoiceNo" label="红字发票号" />
      </el-table>

      <div class="mb10 mt10" style="font-weight:bold">三维质量决定 / EXTRA 审批链（更正要填的 id 在这里）</div>
      <el-table :data="editionLedger.qualityDecisions || []" size="mini" border>
        <el-table-column prop="dimension" label="维度" width="170" />
        <el-table-column prop="decisionStatus" label="状态" width="100" />
        <el-table-column prop="amount" label="金额" width="90" />
        <el-table-column prop="faultParty" label="归责方" width="100" />
        <!-- 理由是这两个不可逆动作唯一留得下来的判据（@Log 不写请求体），必须在操作面上读得回来。 -->
        <el-table-column prop="reason" label="判定/定责依据" show-overflow-tooltip />
        <el-table-column prop="evidenceHash" label="证据" show-overflow-tooltip />
      </el-table>
      <el-table :data="editionLedger.extraApprovals || []" size="mini" border class="mt10">
        <el-table-column prop="id" label="id" width="70" />
        <el-table-column prop="category" label="类别" width="90" />
        <el-table-column prop="approvedHours" label="批准人时" width="100" />
        <el-table-column prop="correctionOf" label="更正自" width="90" />
        <el-table-column prop="workStartedAt" label="开工时刻" />
      </el-table>
      <div slot="footer"><el-button @click="ledgerDialogVisible=false">关闭</el-button></div>
    </el-dialog>

  </div>
</template>

<script>
import {
  getTopic,
  getTopicChapter,
  addTopicChapter,
  updateTopicChapter,
  delTopicChapter,
  configureChapterSupplyTerms,
  getChapterServiceCapacity,
  configureChapterServiceCapacity,
  getTopicLeadFulfillment,
  declareTopicOrganizerActor,
  getTopicEditionFinanceOverview,
  recomputeTopicEditionFunds,
  repairTopicPurchaseKind,
  saveTopicEditionCost,
  planTopicEditionHours,
  getClubPayee,
  saveClubPayee,
  verifyClubPayee,
  getClubPayeePayoutDetail,
  confirmClubQuality,
  excludeClubQualityByFault,
  getTopicEditionLedger,
  finalizeTopicEdition,
  reverseClubPayable,
  reaccrueClubPayable,
  createClubPayableBatch,
  confirmClubPayableBatch,
  markClubPayableBatchPaid,
  issueClubPayableInvoice,
  redClubPayableInvoice
} from "@/api/business/topic"
import { addTicket, updateTicket } from "@/api/business/ticket"
import { addTopicNode, updateTopicNode, delTopicNode } from "@/api/business/topicnode"
import { getTopicFunnel } from "@/api/business/topicFunnel"
import { parseTime } from "@/utils/ruoyi"

const hasAllPermissions = (permissions, required) =>
  permissions.includes('*:*:*') || required.every(permission => permissions.includes(permission))

const hasPermission = (permissions, required) =>
  permissions.includes('*:*:*') || permissions.includes(required)

export default {
  name: "TopicDetail",
  dicts: ['sys_topic_type', 'sys_topic_status', 'sys_topic_user_status'],
  data() {
    // 验证结束时间是否晚于开始时间
    const validateEndTime = (rule, value, callback) => {
      if (!value) {
        callback(new Error('请选择结束时间'))
      } else if (this.ticketForm.startTime && value) {
        const start = new Date(this.ticketForm.startTime)
        const end = new Date(value)
        if (end <= start) {
          callback(new Error('结束时间必须晚于开始时间'))
        } else {
          callback()
        }
      } else {
        callback()
      }
    }
    
    // 验证开始时间是否早于结束时间
    const validateStartTime = (rule, value, callback) => {
      if (!value) {
        callback(new Error('请选择开始时间'))
      } else if (this.ticketForm.endTime && value) {
        const start = new Date(value)
        const end = new Date(this.ticketForm.endTime)
        if (end <= start) {
          callback(new Error('开始时间必须早于结束时间'))
        } else {
          callback()
        }
      } else {
        callback()
      }
    }

    return {
      loading: false,
      topicDetail: {},
      fundsSnapshot: null,
      financeOverview: null,
      costDialogVisible: false,
      hoursDialogVisible: false,
      payeeDialogVisible: false,
      clubFundsDialogVisible: false,
      ledgerDialogVisible: false,
      // 本期资金明细的本地缓存：下面各动作用它给出候选 id，不再要求操作者去连库捞。
      editionLedger: { finalization: null, payables: [], batches: [], terms: null, separation: null,
        qualityDecisions: [], extraApprovals: [], hours: [] },
      payeeForm: {},
      payeeView: null,
      costMoneyFields: [
        { key: 'taxCost', label: '税费 T' }, { key: 'paymentCost', label: '支付 P' },
        { key: 'materialCost', label: '物料 M' }, { key: 'kitCost', label: 'Kit K' },
        { key: 'insuranceRiskCost', label: '保险风险 I' }, { key: 'acquisitionCost', label: '获客 D' },
        { key: 'platformLaborCost', label: '平台人力 L' }, { key: 'otherCost', label: '其他 O' },
        { key: 'fixedCompensation', label: '固定执行费 F' }, { key: 'bonuses', label: '绩效奖励 B' },
        // 2026-08-25 承接费换轨：按【已赚票数】计酬，取代按工时的 EXTRA。逐期填、开售当场冻结。
        // 单价留在本数组里是对的：兜成 0 = 这一期只发保底 F，本来就是合法且最常见的配置。
        // ⚠️ 封顶【不能】放这里 —— 见下方 headcountCap 单独渲染处的说明。
        { key: 'headcountRate', label: '每已赚票单价' }
      ],
      costForm: {},
      hoursForm: {},
      chapterList: [],    // 章节列表
      merchantList: [],   // 商家列表
      templateList: [],   // 模板列表
      lineupList: [],     // 阵容列表
      ticketList: [], // 票务信息列表
      leadFulfillment: [],
      activeChapterNames: [0], // 默认展开第一个章节
      chapterDialogVisible: false,
      chapterDialogTitle: '',
      chapterSaving: false,
      chapterEditRequestId: 0,
      chapterForm: {
        id: null,
        topicId: null,
        name: '',
        description: '',
        sortId: 1,
        required: 1,
        recruitEnabled: 0,
        categoryId: null
      },
      chapterRules: {
        name: [
          { required: true, message: '请输入章节名称', trigger: 'blur' },
          { max: 100, message: '章节名称不能超过100个字符', trigger: 'blur' }
        ],
        sortId: [
          { required: true, message: '请输入排序值', trigger: 'change' }
        ]
      },
      // 节点（店位）新增 / 编辑。打卡码不进表单 —— 见对话框注释。
      nodeDialogVisible: false,
      nodeDialogTitle: '',
      nodeSaving: false,
      nodeForm: {
        id: null,
        chapterId: null,
        chapterName: '',
        name: '',
        description: '',
        address: '',
        longitude: '',
        latitude: '',
        businessTime: '',
        nodeTime: '',
        merchantMemberId: undefined,
        sortId: 1,
        status: 1,
        checkinCode: ''
      },
      nodeRules: {
        name: [
          { required: true, message: '请输入节点名称', trigger: 'blur' },
          { max: 80, message: '节点名称不能超过80个字符', trigger: 'blur' }
        ],
        address: [
          { required: true, message: '请输入门店地址', trigger: 'blur' }
        ],
        sortId: [
          { required: true, message: '请输入排序值', trigger: 'change' }
        ]
      },
      // 现场七段漏斗 + 现场服务达标线（只读）
      funnel: null,
      funnelLoading: false,
      funnelError: '',
      // [D21] 章节条款档改判
      termsModeDialog: {
        visible: false,
        loading: false,
        chapterId: null,
        chapterName: '',
        currentMode: '',
        termsMode: ''
      },
      serviceCapacityByChapter: {},
      serviceCapacityDialog: {
        visible: false,
        loading: false,
        chapterId: null,
        chapterName: '',
        batchSize: null,
        batchIntervalMinutes: null,
        effortMaxConcurrent: null
      },
      LS: {
        'text-align': 'left',
        'width': '120px',
        'word-break': 'keep-all'
      },
      // 票务相关数据
      ticketDialogVisible: false,
      ticketDialogTitle: '',
      ticketForm: {
        id: null,
        ownerId: null,
        ownerType: 1, // 1:主题, 2:活动
        name: '',
        price: 0,
        startTime: '',
        endTime: '',
        totalInventory: 0,
        remainingInventory: 0,
        memberId: null
      },
      editingTicketIndex: -1,
      ticketLoading: false,
      ticketRules: {
        name: [
          { required: true, message: '请输入票务名称', trigger: 'blur' },
          { max: 255, message: '票务名称不能超过255个字符', trigger: 'blur' }
        ],
        price: [
          { required: true, message: '请输入价格', trigger: 'blur' },
          { type: 'number', min: 0, message: '价格不能为负数', trigger: 'blur' }
        ],
        totalInventory: [
          { required: true, message: '请输入总库存', trigger: 'blur' },
          { type: 'number', min: 0, message: '库存不能为负数', trigger: 'blur' }
        ],
        startTime: [
          { required: true, message: '请选择开始时间', trigger: 'change' },
          { validator: validateStartTime, trigger: 'change' }
        ],
        endTime: [
          { required: true, message: '请选择结束时间', trigger: 'change' },
          { validator: validateEndTime, trigger: 'change' }
        ]
      }
    }
  },
  computed: {
    canAddChapter() {
      const permissions = (this.$store && this.$store.getters && this.$store.getters.permissions) || []
      return hasPermission(permissions, 'business:topicchapter:add')
    },
    canEditChapter() {
      const permissions = (this.$store && this.$store.getters && this.$store.getters.permissions) || []
      return hasAllPermissions(permissions, [
        'business:topicchapter:edit',
        'business:topicchapter:query'
      ])
    },
    canRemoveChapter() {
      const permissions = (this.$store && this.$store.getters && this.$store.getters.permissions) || []
      return hasPermission(permissions, 'business:topicchapter:remove')
    },
    canAddNode() {
      const permissions = (this.$store && this.$store.getters && this.$store.getters.permissions) || []
      return hasPermission(permissions, 'business:topicnode:add')
    },
    canEditNode() {
      const permissions = (this.$store && this.$store.getters && this.$store.getters.permissions) || []
      return hasPermission(permissions, 'business:topicnode:edit')
    },
    canRemoveNode() {
      const permissions = (this.$store && this.$store.getters && this.$store.getters.permissions) || []
      return hasPermission(permissions, 'business:topicnode:remove')
    },
    canAddTicket() {
      const permissions = (this.$store && this.$store.getters && this.$store.getters.permissions) || []
      return hasAllPermissions(permissions, ['business:ticket:add', 'business:topic:edit'])
    },
    canEditTicket() {
      const permissions = (this.$store && this.$store.getters && this.$store.getters.permissions) || []
      return hasAllPermissions(permissions, ['business:ticket:edit', 'business:topic:edit'])
    },
    paymentSeparation() {
      return (this.editionLedger && this.editionLedger.separation) || null
    },
    paymentRegistrationDisabled() {
      return !this.paymentSeparation || this.paymentSeparation.degraded !== false
    },
    paymentSeparationReason() {
      return this.paymentSeparation && this.paymentSeparation.reason
        ? this.paymentSeparation.reason
        : '付款双人分离状态尚未读取'
    },
    paymentSeparationAction() {
      return this.paymentSeparation && this.paymentSeparation.deploymentAction
        ? this.paymentSeparation.deploymentAction
        : '请先查询本期资金明细，确认两个不同账号分别持有确认与付款权限'
    },
    // 处理主题轮播图数据
    topicImgList() {
      if (this.topicDetail.imgArr) {
        return this.topicDetail.imgArr.split(';').filter(img => img.trim() !== '')
      }
      return []
    }
  },
  created() {
    const { id } = this.$route.query
    if (id) {
      this.getDetail(id)
    }
  },
  methods: {
    parseTime,
    declareOrganizer() {
      return this.$modal.confirm('确认将本期 ORGANIZER actor 声明为平台？开售后不可修改。')
        .then(() => declareTopicOrganizerActor(this.topicDetail.id))
        .then(() => this.$modal.msgSuccess('平台组织方已声明'))
    },
    loadFunds() {
      if (!this.topicDetail.id || this.topicDetail.productType !== 2) return
      return getTopicEditionFinanceOverview(this.topicDetail.id).then(res => {
        this.financeOverview = res.code === 200 ? (res.data || null) : null
        this.fundsSnapshot = this.financeOverview ? this.financeOverview.funds : null
      })
    },
    recomputeFunds() {
      const key = `admin:${this.topicDetail.id}:${Date.now()}`
      return recomputeTopicEditionFunds(this.topicDetail.id, key)
        .then(res => { this.fundsSnapshot = res.data; this.$modal.msgSuccess('资金快照已重算') })
    },
    repairPurchaseKind() {
      return this.$modal.confirm('只会把本期已支付且 purchase_kind 为空的探店日订单修复为探索票，是否继续？')
        .then(() => repairTopicPurchaseKind(this.topicDetail.id))
        .then(res => this.$modal.msgSuccess(`已修复 ${res.data || 0} 条，请重新计算`))
    },
    openCostDialog() {
      const current = (this.financeOverview && this.financeOverview.cost) || {}
      this.costForm = Object.assign({ note: '', platformOnsiteHours: null, breakEvenFixedCost: null, variableCostPerTicket: null, headcountCap: null }, current)
      this.costMoneyFields.forEach(field => { if (this.costForm[field.key] == null) this.$set(this.costForm, field.key, 0) })
      // ⚠️ 项目负责人<b>不</b>随金额项默认成 0：0 不是一个人。缺就让开售那一刻点名拒绝。
      if (this.costForm.extraApproverUserId === 0) this.$set(this.costForm, 'extraApproverUserId', null)
      this.costDialogVisible = true
    },
    submitCost() {
      // ★归一放在写边界，不放在打开弹窗时：后者挡得住「读回来的 0」，挡不住「手打一个 0 再保存」。
      //   cap=0 会让 min(单价×净票数, 0) 恒等于 0 —— 配了单价也一分不发，且零告警。
      if (this.costForm.headcountCap === 0) this.$set(this.costForm, 'headcountCap', null)
      return saveTopicEditionCost(this.topicDetail.id, this.costForm).then(() => {
        this.costDialogVisible = false
        this.$modal.msgSuccess('期级成本已保存')
        return this.loadFunds()
      })
    },
    openHoursDialog() {
      this.hoursForm = { clubId: null, hourKind: 'PREP', plannedHours: 0 }
      this.hoursDialogVisible = true
    },
    submitPlanHours() {
      return planTopicEditionHours(this.topicDetail.id, this.hoursForm).then(() => {
        this.hoursDialogVisible = false
        this.$modal.msgSuccess('计划工时已登记')
        return this.loadFunds()
      })
    },

    // ── 四个 A 的后台入口 ────────────────────────────────────────────────
    // 这些动作的参数都很窄（一个 id / 一个数 / 一句理由），用 $prompt 逐项收；
    // 只有九组齐全的对公资料值得一个表单。刻意不做「资金工作台」重设计。
    askClubId(title) {
      // 执行俱乐部是开售冻结的事实，明细里读得到 —— 有它就默认填上，不再让人凭记忆敲。
      const frozen = this.editionLedger.terms && this.editionLedger.terms.executingClubId
      return this.$prompt('俱乐部ID' + (frozen ? '（本期冻结的执行俱乐部：' + frozen + '）' : ''), title,
        { inputPattern: /^\d+$/, inputErrorMessage: '请填数字ID',
          inputValue: frozen == null ? '' : String(frozen) })
        .then(({ value }) => Number(value))
    },

    /**
     * ★本期资金明细：应付原始行 / 批次双轴 / 三维质量决定 / EXTRA 审批链。
     *
     * 这是红冲、更正、批次四个动作的 id 来源。上一版这些 id 在界面上从不出现，
     * 操作者只能直连生产库查 —— 与「这一排是四个 A 在后台的唯一入口」自相矛盾。
     */
    loadEditionLedger() {
      return getTopicEditionLedger(this.topicDetail.id).then(res => {
        this.editionLedger = Object.assign(
          { finalization: null, payables: [], batches: [], terms: null, separation: null,
            qualityDecisions: [], extraApprovals: [], hours: [] }, res.data || {})
        this.ledgerDialogVisible = true
      })
    },

    /** 明细里可选的候选 id，拼进 $prompt 的提示语；没加载过就先加载一次。 */
    candidateHint(rows, label) {
      const ids = (rows || []).map(row => row.id).filter(id => id != null)
      return ids.length ? '（本期' + label + '：' + ids.join('、') + '）' : '（本期没有' + label + '，先点「查本期资金明细」）'
    },
    loadClubPayee() {
      return this.askClubId('查对公收款人').then(clubId => getClubPayee(clubId).then(res => {
        this.payeeView = res.data
        this.clubFundsDialogVisible = true
      }))
    },
    openPayeeDialog() {
      this.payeeForm = { clubId: null, legalName: '', unifiedSocialCreditCode: '', bankName: '',
        corporateBankAccountNo: '', bankAccountName: '', invoiceType: '', taxNo: '',
        financeContactName: '', financeContactPhone: '', licenseObjectKey: '', licenseHash: '' }
      this.payeeDialogVisible = true
    },
    submitPayee() {
      const { clubId, ...body } = this.payeeForm
      return saveClubPayee(clubId, body).then(() => {
        this.payeeDialogVisible = false
        this.$modal.msgSuccess('已写入新版本，待财务审核')
      })
    },
    verifyPayee() {
      // 执照哈希由审核人**另行下载执照后自己算**再填，不是从资料里回显的那一份 ——
      // 回显等于让提交方自证，这道核对就没意义了。
      // ★这是本仓唯一的一次财务审核（不设 maker/checker），所以「驳回」必须也发得出来：
      //   只发得出通过的话，资料有问题时 profile 会永久停在 PENDING，而 A4 是硬开售闸。
      let clubId
      let profileId
      let pass
      return this.askClubId('财务审核对公收款人')
        .then(id => { clubId = id; return this.$prompt('待审版本 profileId', '财务审核',
          { inputPattern: /^\d+$/, inputErrorMessage: '请填数字ID' }) })
        .then(({ value }) => {
          profileId = Number(value)
          // 三态：通过 / 驳回 / 中止。×、ESC、遮罩一律中止，绝不静默落成驳回（一次性不可逆）。
          return this.$confirm('这一版对公资料通过还是驳回？', '财务一次审核，不可改判', {
            confirmButtonText: '通过', cancelButtonText: '驳回', type: 'warning',
            distinguishCancelAndClose: true, closeOnPressEscape: false, closeOnClickModal: false
          }).then(() => true, reason => {
            if (reason === 'cancel') return false
            throw reason
          })
        })
        .then(decision => {
          pass = decision
          // 驳回不需要执照哈希：service 侧只在 pass=true 时比对它。
          if (!pass) return { value: null }
          return this.$prompt('你自己核对后算出的执照 SHA-256', '财务审核通过')
        })
        // ⚠️ 键名必须是 licenseHash：Controller 读的就是这个键（ClubCorporatePayeeController:64）。
        // 叫别的名字 ⇒ body.get 恒 null ⇒ isBlank ⇒ 无条件 PAYEE_LICENSE_HASH_MISMATCH，
        // 而 A4 是硬开售闸，审不过就没有一期探店日开得了售。契约测试按 Controller 的键名对齐。
        .then(hash => verifyClubPayee(clubId, profileId, { pass: pass, licenseHash: pass ? hash.value : null }))
        .then(() => this.$modal.msgSuccess(pass ? '已审核通过' : '已驳回，俱乐部需重交新版本'))
        .catch(() => {})
    },
    showPayoutDetail() {
      return this.askClubId('付款解密（完整明文，单独权限）')
        .then(clubId => getClubPayeePayoutDetail(clubId))
        .then(res => { this.payeeView = res.data; this.clubFundsDialogVisible = true })
    },
    // 2026-08-25：preapproveExtra / correctExtra 随 EXTRA 整条退役一并删除
    // （按钮已从模板摘掉，方法留着就是死代码）。要恢复请连同后端
    // rejectRetiredHoursWrite() 一起 revert 本次提交。
    confirmQuality() {
      const form = {}
      return this.askClubId('质量奖励一次确认')
        .then(id => { form.clubId = id
          return this.$prompt('维度 DELIVERY_SAFETY/PLAYER_EXPERIENCE/CONTENT_REPORT', '一次确认') })
        .then(({ value }) => { form.dimension = value
          // ★三态必须分开：判过 / 判不过 / 中止。这是不可逆动作（confirmQualityCas 旧状态写死 PENDING），
          // 误判不过 = 该维永久锁死 0 元，唯一的翻案路径要写一条不实的过错定责，等于把审计写脏。
          // 不开 distinguishCancelAndClose 时，×/ESC/遮罩都以 'cancel' reject，会和「判不过 0」同码。
          return this.$confirm('判过（200）还是判不过（0）？', '一次确认，不可改判', {
            confirmButtonText: '判过 200', cancelButtonText: '判不过 0', type: 'warning',
            distinguishCancelAndClose: true, closeOnPressEscape: false, closeOnClickModal: false
          }).then(() => true, reason => {
            // 只有显式点了「判不过 0」才是 'cancel'；'close'（×）走 rethrow ⇒ 整条链中止、不发请求。
            if (reason === 'cancel') return false
            throw reason
          }) })
        // 理由必填且**落库**（club_quality_decision.reason）：本仓 @Log 不写请求体，
        // 一次确认又不可逆，判据不落列就只剩操作者的记忆。后端空理由直接拒。
        .then(pass => { form.pass = pass
          return this.$prompt('判定依据（必填，会落进质量决定行）', '一次确认',
            { inputPattern: /\S/, inputErrorMessage: '理由必填' }) })
        .then(({ value }) => confirmClubQuality(this.topicDetail.id, { ...form, reason: value }))
        .then(() => { this.$modal.msgSuccess('已确认'); return this.loadFunds() })
        .catch(() => {})
    },
    excludeQuality() {
      const form = {}
      return this.askClubId('商家/平台过错排除该维')
        .then(id => { form.clubId = id; return this.$prompt('维度', '过错排除') })
        .then(({ value }) => { form.dimension = value
          return this.$prompt('归责方 MERCHANT / PLATFORM', '过错排除') })
        // ★理由必填且落库：这是全仓唯一能推翻一个已确认 Q 决定的动作，方向是加钱
        // （每维 +200，一期最多 +600），且只凭操作者自行断言归责方 —— fault_party 只有两个
        // 枚举值，@Log 又不写请求体，「是哪一笔事故、依据什么定的责」只有这一列留得下来。
        .then(({ value }) => { form.faultParty = value
          return this.$prompt('定责依据（必填，会落进质量决定行）', '过错排除',
            { inputPattern: /\S/, inputErrorMessage: '理由必填' }) })
        .then(({ value }) => excludeClubQualityByFault(this.topicDetail.id, { ...form, reason: value }))
        // 方向按总纲字面：过错从俱乐部绩效责任中剔除 ⇒ 该维不扣分、照拿 200。
        .then(() => { this.$modal.msgSuccess('已排除，该维不因商家/平台过错扣分'); return this.loadFunds() })
        .catch(() => {})
    },
    finalizeEdition() {
      return this.$confirm('FINAL 一期只能一次，且要求退款已落定、投诉工单已结。继续？', '期级 FINAL 结算',
        { type: 'warning' })
        .then(() => finalizeTopicEdition(this.topicDetail.id))
        .then(() => { this.$modal.msgSuccess('已 FINAL'); return this.loadFunds() })
    },
    reversePayable() {
      let payableId
      // 候选 id 从本期明细里来，不再要求操作者先连生产库查（叠加「批次不绑期」就是把钱记到别的期）。
      return this.ensureLedger()
        .then(() => this.$prompt('要红冲的应付行 id'
          + this.candidateHint(this.editionLedger.payables, '应付行'), '应付红冲',
        { inputPattern: /^\d+$/, inputErrorMessage: '请填数字ID' }))
        .then(({ value }) => { payableId = Number(value)
          // 理由是必填且**落库**的：本仓 @Log 不写请求体，日志里查不到它。
          return this.$prompt('红冲理由（必填，会落进台账行）', '应付红冲') })
        .then(({ value }) => reverseClubPayable(this.topicDetail.id, payableId, { reason: value }))
        .then(() => { this.$modal.msgSuccess('已写入反向行'); return this.loadFunds() })
    },
    reaccruePayable() {
      return this.$prompt('重计提理由（必填，会落进台账行）', '红冲后重计提')
        .then(({ value }) => reaccrueClubPayable(this.topicDetail.id, { reason: value }))
        .then(() => { this.$modal.msgSuccess('已按差额追加新行'); return this.loadFunds() })
    },
    createBatch() {
      return this.askClubId('建付款批次（一期一批次一张发票）')
        .then(clubId => createClubPayableBatch(this.topicDetail.id, { clubId }))
        // ★把后端返回的批次 id 回显：确认 / 付款 / 开票三步接着就要填它，丢掉它等于让人去连库捞。
        .then(res => { this.$modal.msgSuccess('批次已建立 #' + ((res.data && res.data.id) || '?'))
          return this.loadEditionLedger() })
    },
    /** 明细没加载过就先拉一次，让候选 id 一定是真的。 */
    ensureLedger() {
      if ((this.editionLedger.payables || []).length || (this.editionLedger.batches || []).length) {
        return Promise.resolve()
      }
      return getTopicEditionLedger(this.topicDetail.id)
        .then(res => { this.editionLedger = Object.assign(this.editionLedger, res.data || {}) })
        .catch(() => {})
    },
    askBatchId(title) {
      return this.ensureLedger()
        .then(() => this.$prompt('批次 id' + this.candidateHint(this.editionLedger.batches, '批次'), title,
          { inputPattern: /^\d+$/, inputErrorMessage: '请填数字ID' }))
        .then(({ value }) => Number(value))
    },
    confirmBatch() {
      return this.askBatchId('确认批次')
        .then(batchId => confirmClubPayableBatch(this.topicDetail.id, batchId))
        .then(() => this.$modal.msgSuccess('批次已确认；付款登记必须换一个人'))
    },
    markBatchPaid() {
      if (this.paymentRegistrationDisabled) {
        this.$modal.msgError(this.paymentSeparationReason + '；' + this.paymentSeparationAction)
        return Promise.resolve(false)
      }
      let batchId
      return this.askBatchId('登记付款')
        .then(id => { batchId = id; return this.$prompt('付款凭证号', '登记付款') })
        .then(({ value }) => markClubPayableBatchPaid(this.topicDetail.id, batchId, { paymentReference: value }))
        .then(() => this.$modal.msgSuccess('付款已登记'))
    },
    issueInvoice() {
      let batchId
      return this.askBatchId('开票（与付款状态互不影响）')
        .then(id => { batchId = id; return this.$prompt('发票号', '开票') })
        .then(({ value }) => issueClubPayableInvoice(this.topicDetail.id, batchId, { invoiceNo: value }))
        .then(() => this.$modal.msgSuccess('已开票'))
    },
    redInvoice() {
      let batchId
      return this.askBatchId('红字发票（必须先开过票）')
        .then(id => { batchId = id; return this.$prompt('红字发票号', '红字发票') })
        .then(({ value }) => redClubPayableInvoice(this.topicDetail.id, batchId, { redInvoiceNo: value }))
        .then(() => this.$modal.msgSuccess('已红冲发票'))
    },
    nodeAuditLabel(status) {
      if (status === 1) return '已过审'
      if (status === 2) return '已驳回·玩家看不到'
      if (status === 0) return '待审·玩家看不到'
      // 绑了商家却没有审核态 = mapper 的可见性闸把它挡在玩家侧之外，页面必须说出来。
      return '无审核态·玩家看不到'
    },
    handleAddNode(chapter) {
      const nodes = this.getChapterNodes(chapter.id)
      const nextSortId = nodes.reduce((max, node) => {
        const sortId = Number(node.sortId)
        return Number.isFinite(sortId) ? Math.max(max, sortId) : max
      }, 0) + 1
      this.nodeDialogTitle = '新增节点'
      this.nodeForm = {
        id: null,
        chapterId: chapter.id,
        chapterName: chapter.name || ('章节 #' + chapter.id),
        name: '',
        description: '',
        address: '',
        longitude: '',
        latitude: '',
        businessTime: '',
        nodeTime: '',
        merchantMemberId: undefined,
        sortId: nextSortId,
        status: 1,
        checkinCode: ''
      }
      this.nodeDialogVisible = true
      this.$nextTick(() => {
        if (this.$refs.nodeForm) this.$refs.nodeForm.clearValidate()
      })
    },
    handleEditNode(chapter, node) {
      this.nodeDialogTitle = '编辑节点'
      // 直接用列表里那一行：节点随 getTopic 一起返回，再拉一次单查等于多一个会漂移的真源。
      this.nodeForm = {
        id: node.id,
        chapterId: chapter.id,
        chapterName: chapter.name || ('章节 #' + chapter.id),
        name: node.name || '',
        description: node.description || '',
        address: node.address || '',
        longitude: node.longitude || '',
        latitude: node.latitude || '',
        businessTime: node.businessTime || '',
        nodeTime: node.nodeTime == null ? '' : String(node.nodeTime),
        merchantMemberId: node.merchantMemberId == null ? undefined : Number(node.merchantMemberId),
        sortId: Number(node.sortId) || 1,
        status: Number(node.status) === 1 ? 1 : 0,
        checkinCode: node.checkinCode || ''
      }
      this.nodeDialogVisible = true
      this.$nextTick(() => {
        if (this.$refs.nodeForm) this.$refs.nodeForm.clearValidate()
      })
    },
    submitNodeForm() {
      this.$refs.nodeForm.validate(valid => {
        if (!valid) return
        const editing = this.nodeForm.id != null
        // ⚠️ 刻意不带 checkinCode / nodeAuditStatus：
        //   前者由后端生成且换码会作废已印海报；后者由后端按「绑没绑商家」强制。
        const payload = {
          topicId: this.topicDetail.id,
          chapterId: this.nodeForm.chapterId,
          name: this.nodeForm.name.trim(),
          description: this.nodeForm.description || '',
          address: (this.nodeForm.address || '').trim(),
          longitude: this.nodeForm.longitude || '',
          latitude: this.nodeForm.latitude || '',
          businessTime: this.nodeForm.businessTime || '',
          nodeTime: this.nodeForm.nodeTime || '',
          sortId: Number(this.nodeForm.sortId),
          status: this.nodeForm.status
        }
        if (this.nodeForm.merchantMemberId) payload.merchantMemberId = Number(this.nodeForm.merchantMemberId)
        if (editing) payload.id = this.nodeForm.id
        this.nodeSaving = true
        const request = editing ? updateTopicNode(payload) : addTopicNode(payload)
        request.then(res => {
          if (res.code !== 200) return
          this.$modal.msgSuccess(editing ? '节点已保存' : '节点已创建（打卡码已自动生成）')
          this.nodeDialogVisible = false
          return this.getDetail(this.topicDetail.id)
        }).catch(() => {}).finally(() => { this.nodeSaving = false })
      })
    },
    handleDeleteNode(node) {
      return this.$modal.confirm(
        '确认删除节点「' + (node.name || ('#' + node.id)) + '」？'
        + '已有玩家进店记录会指向一个不存在的节点，删前先看现场漏斗。'
      ).then(() => delTopicNode(node.id)).then(res => {
        if (res.code !== 200) return
        this.$modal.msgSuccess('节点已删除')
        return this.getDetail(this.topicDetail.id)
      }).catch(() => {})
    },
    toPercent(value) {
      // ⚠️ 不给 null 兜 0：0% 会被读成「一个人都没到」，而真相是这一格没有数。
      if (value === null || value === undefined || value === '') return '未记录'
      return (Number(value) * 100).toFixed(2) + '%'
    },
    loadFunnel() {
      const id = this.topicDetail.id
      if (!id) return
      this.funnelLoading = true
      this.funnelError = ''
      return getTopicFunnel(id).then(res => {
        this.funnel = res.code === 200 ? (res.data || null) : null
        if (res.code !== 200) this.funnelError = res.msg || '现场漏斗读取失败'
      }).catch(err => {
        this.funnel = null
        this.funnelError = (err && err.message) || '现场漏斗读取失败'
      }).finally(() => { this.funnelLoading = false })
    },
    handleAddChapter() {
      this.chapterEditRequestId += 1
      const nextSortId = (this.chapterList || []).reduce((max, chapter) => {
        const sortId = Number(chapter.sortId)
        return Number.isFinite(sortId) ? Math.max(max, sortId) : max
      }, 0) + 1
      this.chapterDialogTitle = '新增章节'
      this.chapterDialogVisible = true
      this.chapterForm = {
        id: null,
        topicId: this.topicDetail.id,
        name: '',
        description: '',
        sortId: nextSortId,
        required: 1,
        recruitEnabled: 0,
        categoryId: null
      }
      this.$nextTick(() => {
        if (this.$refs.chapterForm) this.$refs.chapterForm.clearValidate()
      })
    },
    handleEditChapter(chapter) {
      const requestId = ++this.chapterEditRequestId
      this.chapterDialogTitle = '编辑章节'
      this.chapterSaving = true
      return getTopicChapter(chapter.id)
        .then(res => {
          if (requestId !== this.chapterEditRequestId || res.code !== 200 || !res.data) return
          const current = res.data
          this.chapterForm = {
            id: current.id,
            topicId: current.topicId,
            name: current.name || '',
            description: current.description || '',
            sortId: Number(current.sortId) || 1,
            required: current.required === 0 ? 0 : 1,
            recruitEnabled: current.recruitEnabled === 1 ? 1 : 0,
            categoryId: current.categoryId || null
          }
          this.chapterDialogVisible = true
          this.$nextTick(() => {
            if (this.$refs.chapterForm) this.$refs.chapterForm.clearValidate()
          })
        })
        .finally(() => {
          if (requestId === this.chapterEditRequestId) this.chapterSaving = false
        })
    },
    submitChapterForm() {
      this.$refs.chapterForm.validate(valid => {
        if (!valid) return
        const editing = this.chapterForm.id != null
        const payload = {
          name: this.chapterForm.name.trim(),
          description: this.chapterForm.description || '',
          sortId: Number(this.chapterForm.sortId),
          required: this.chapterForm.required,
          recruitEnabled: this.chapterForm.recruitEnabled === 1 ? 1 : 0,
          categoryId: this.chapterForm.recruitEnabled === 1 ? this.chapterForm.categoryId : null
        }
        if (editing) {
          payload.id = this.chapterForm.id
        } else {
          payload.topicId = this.topicDetail.id
          payload.termsMode = 'PERK'
          payload.maxMerchant = 1
        }
        this.chapterSaving = true
        const request = editing ? updateTopicChapter(payload) : addTopicChapter(payload)
        request.then(res => {
          if (res.code !== 200) return
          this.$modal.msgSuccess(editing ? '章节已保存' : '章节已创建')
          this.chapterDialogVisible = false
          return this.getDetail(this.topicDetail.id)
        }).catch(() => {}).finally(() => { this.chapterSaving = false })
      })
    },
    handleDeleteChapter(chapter) {
      return this.$modal.confirm(`确认删除章节“${chapter.name}”吗？章节已有申请或供给时后端会拒绝。`)
        .then(() => delTopicChapter(chapter.id))
        .then(res => {
          if (res.code !== 200) return
          this.$modal.msgSuccess('章节已删除')
          return this.getDetail(this.topicDetail.id)
        })
        .catch(() => {})
    },
    // [D21] 空 = 权益档，与 DDL cms_topic_chapter.terms_mode DEFAULT 'PERK' 同口径。
    // ⚠️ 别显示成「未设置」——那会让人以为这章还没定档，其实它已经在按权益档收钱了。
    termsModeLabel(mode) {
      const map = { PERK: '权益档', REVSHARE: '计酬档', TRAFFIC: '引流档' }
      return map[mode || 'PERK'] || mode
    },
    termsModeTagType(mode) {
      // 计酬档是唯一要平台掏钱的档，给它一个和另两档不同的颜色，扫一眼就能看出哪些章在花钱。
      const map = { PERK: 'success', REVSHARE: 'danger', TRAFFIC: 'info' }
      return map[mode || 'PERK'] || 'info'
    },
    openTermsModeDialog(chapter) {
      this.termsModeDialog = {
        visible: true,
        loading: false,
        chapterId: chapter.id,
        chapterName: chapter.name,
        // 空 = 权益档,与 DDL DEFAULT 'PERK' 同口径
        termsMode: chapter.termsMode || 'PERK',
        // max_merchant 是 NOT NULL；历史空/非法值在弹窗打开时归一成 0（不限）。
        maxPerHeadFee: chapter.maxPerHeadFee,
        perkMinValue: chapter.perkMinValue,
        maxMerchant: Number.isInteger(Number(chapter.maxMerchant))
          && Number(chapter.maxMerchant) >= 0 && Number(chapter.maxMerchant) <= 127
          ? Number(chapter.maxMerchant) : 0
      }
    },
    submitTermsMode() {
      const d = this.termsModeDialog
      if (d.termsMode === 'REVSHARE' && !(d.maxPerHeadFee > 0)) {
        // 后端也拦(fail-closed),这里只是省一次往返并把话说清楚:
        // 留空不是「不设上限」,是「商家一个都进不来」——而运营那边只会看到「配置成功」。
        this.$modal.msgWarning('计酬档必须填人头费上限,否则商家入驻会被直接拒')
        return
      }
      const maxMerchant = Number(d.maxMerchant)
      if (!Number.isInteger(maxMerchant) || maxMerchant < 0 || maxMerchant > 127) {
        this.$modal.msgWarning('商家数上限请输入0到127之间的整数；0表示不限')
        return
      }
      // 跟同文件 submitTicketForm 一样上 loading 守卫:配的是「这一章的钱怎么算」,
      // 双击发两个 PUT 会让第二个撞上已经变了的状态,弹一句莫名其妙的失败。
      d.loading = true
      // ⚠️ 按档位清掉不属于该档的字段:后端形状校验会拒「引流档带着人头费上限」这种组合。
      // 这不是迎合校验 —— 是别把上一次选计酬档时填的数,悄悄留在一个引流档的章上。
      configureChapterSupplyTerms(d.chapterId, {
        termsMode: d.termsMode,
        maxPerHeadFee: d.termsMode === 'REVSHARE' ? d.maxPerHeadFee : null,
        perkMinValue: d.termsMode === 'PERK' ? (d.perkMinValue == null ? null : d.perkMinValue) : null,
        maxMerchant
      })
        .then(() => {
          this.$modal.msgSuccess("供给条款已保存")
          d.visible = false
          // ⚠️ 必须带 id:getDetail 直接把它喂给 getTopic(id),传空会打到 /business/topic/undefined。
          this.getDetail(this.topicDetail.id)
        })
        // ⚠️ catch 里**什么都不做**是刻意的:后端四道闸(档位合法/形状自洽/仅③-自营/改档位时无生效供给)拒绝时抛的
        // IllegalStateException 消息本身就说清了是哪一道,若依的响应拦截器会原样弹出来。
        // 在这里包一层「改判失败」只会把那句真正有用的话盖掉。
        // 但链尾必须收住 —— finally 不吃异常,不 catch 就漏成 unhandled rejection。
        .catch(() => {})
        .finally(() => { d.loading = false })
    },
    serviceCapacitySummary(chapterId) {
      const capacity = this.serviceCapacityByChapter[chapterId]
      if (!capacity) return '未读取'
      if (!(capacity.batchSize > 0)
          || !(capacity.batchIntervalMinutes > 0)
          || !(capacity.effortMaxConcurrent > 0)) return '未配置'
      return `${capacity.batchSize} 人/批 · 每 ${capacity.batchIntervalMinutes} 分钟 · 并行 ${capacity.effortMaxConcurrent} 人`
    },
    applyServiceCapacityReadback(chapterId, data) {
      const capacity = {
        batchSize: data && data.batchSize != null ? Number(data.batchSize) : null,
        batchIntervalMinutes: data && data.batchIntervalMinutes != null
          ? Number(data.batchIntervalMinutes) : null,
        effortMaxConcurrent: data && data.effortMaxConcurrent != null
          ? Number(data.effortMaxConcurrent) : null
      }
      this.$set(this.serviceCapacityByChapter, chapterId, capacity)
      if (this.serviceCapacityDialog.chapterId !== chapterId) return capacity
      this.serviceCapacityDialog.batchSize = capacity.batchSize
      this.serviceCapacityDialog.batchIntervalMinutes = capacity.batchIntervalMinutes
      this.serviceCapacityDialog.effortMaxConcurrent = capacity.effortMaxConcurrent
      return capacity
    },
    openServiceCapacityDialog(chapter) {
      this.serviceCapacityDialog = {
        visible: true,
        loading: true,
        chapterId: chapter.id,
        chapterName: chapter.name,
        batchSize: null,
        batchIntervalMinutes: null,
        effortMaxConcurrent: null
      }
      return getChapterServiceCapacity(chapter.id)
        .then(res => {
          if (res.code === 200) this.applyServiceCapacityReadback(chapter.id, res.data || {})
        })
        .catch(() => {})
        .finally(() => {
          if (this.serviceCapacityDialog.chapterId === chapter.id) {
            this.serviceCapacityDialog.loading = false
          }
        })
    },
    submitServiceCapacity() {
      const d = this.serviceCapacityDialog
      const chapterId = d.chapterId
      const values = [d.batchSize, d.batchIntervalMinutes, d.effortMaxConcurrent]
      if (!values.every(value => Number.isInteger(Number(value)) && Number(value) > 0)) {
        this.$modal.msgWarning('每批人数、批次间隔和并行位三项都必须填写正整数')
        return Promise.resolve()
      }
      const payload = {
        batchSize: Number(d.batchSize),
        batchIntervalMinutes: Number(d.batchIntervalMinutes),
        effortMaxConcurrent: Number(d.effortMaxConcurrent)
      }
      d.loading = true
      return configureChapterServiceCapacity(chapterId, payload)
        .then(res => {
          if (res.code !== 200) return null
          // 回执不等于观测：PUT 成功后重新 GET，由服务端返回值刷新页面。
          return getChapterServiceCapacity(chapterId)
        })
        .then(res => {
          if (!res || res.code !== 200) return
          this.applyServiceCapacityReadback(chapterId, res.data || {})
          if (this.serviceCapacityDialog.chapterId !== chapterId) return
          this.serviceCapacityDialog.visible = false
          this.$modal.msgSuccess('人工批次容量已保存并读回')
        })
        .catch(() => {})
        .finally(() => {
          if (this.serviceCapacityDialog.chapterId === chapterId) {
            this.serviceCapacityDialog.loading = false
          }
        })
    },
    // 格式化时间（秒转换为时分秒）
    formatTime(seconds) {
      if (!seconds) return '0秒'
      const hours = Math.floor(seconds / 3600)
      const minutes = Math.floor((seconds % 3600) / 60)
      const secs = seconds % 60
      
      let result = ''
      if (hours > 0) result += `${hours}小时`
      if (minutes > 0) result += `${minutes}分钟`
      if (secs > 0) result += `${secs}秒`
      
      return result || '0秒'
    },
    
    // 格式化日期显示
    formatDate(dateString) {
      if (!dateString) return ''
      // 如果日期字符串包含时间，则显示完整格式
      if (dateString.includes(' ')) {
        return dateString
      }
      // 否则只显示日期部分
      return dateString
    },
    
    // 获取章节图片
    getChapterImages(imgArr) {
      if (imgArr) {
        return imgArr.split(';').filter(img => img.trim() !== '')
      }
      return []
    },
    
    // 获取章节下的节点
    getChapterNodes(chapterId) {
      const chapter = this.chapterList.find(chapter => chapter.id === chapterId);
      return chapter && chapter.cmsTopicNodeList ? chapter.cmsTopicNodeList : [];
    },
    
    // 获取主题详情
    getDetail(id) {
      this.loading = true
      getTopic(id).then(res => {
        if (res.code === 200) {
          this.topicDetail = res.data
          this.chapterList = res.data.cmsTopicChapterList || []
          this.activeChapterNames = this.chapterList.length > 0 ? [this.chapterList[0].id] : []
          this.merchantList = res.data.merchantList || []
          this.templateList = res.data.templateList || []
          this.lineupList = res.data.cmsCollaboratorsList || []
          this.ticketList = res.data.omsTicketList || []
          
          // 设置票务表单的默认ownerId
          this.ticketForm.ownerId = id
          this.ticketForm.memberId = res.data.memberId
          getTopicLeadFulfillment(id).then(r => { this.leadFulfillment = r.code === 200 ? (r.data || []) : [] })
          this.loadFunds()
          // 只有探店日（③ FREE_EXPLORE）有现场漏斗；端点对其它产品类型直接报错，别白调一次。
          if (res.data.productType === 2) this.loadFunnel()
        }
        this.loading = false
      }).catch(() => {
        this.loading = false
      })
    },
    leadStatus(status) {
      return ({ 1: '集合中', 2: '行进中', 3: '已结算' })[status] || '未知'
    },
    
    // 票务管理相关方法
    // 编辑单个票务
    handleEditTicket(row, index) {
      this.ticketDialogTitle = '编辑票务'
      this.ticketDialogVisible = true
      this.editingTicketIndex = index
      
      // 复制票务数据到表单
      this.ticketForm = {
        ...row,
        ownerId: this.topicDetail.id,
        ownerType: 1,
        memberId: this.topicDetail.memberId,
        price: parseFloat(row.price) || 0,
        totalInventory: parseInt(row.totalInventory) || 0,
        remainingInventory: parseInt(row.remainingInventory) || 0
      }
      
      this.$nextTick(() => {
        if (this.$refs.ticketForm) {
          this.$refs.ticketForm.clearValidate()
        }
      })
    },
    
    // 添加票务
    handleAddTicket() {
      this.ticketDialogTitle = '添加票务'
      this.ticketDialogVisible = true
      this.editingTicketIndex = -1
      
      // 设置默认开始和结束时间
      const today = new Date()
      const endDate = new Date(today)
      endDate.setDate(endDate.getDate() + 7) // 默认7天后结束
      
      const formatDateTime = (date) => {
        const year = date.getFullYear()
        const month = String(date.getMonth() + 1).padStart(2, '0')
        const day = String(date.getDate()).padStart(2, '0')
        const hours = String(date.getHours()).padStart(2, '0')
        const minutes = String(date.getMinutes()).padStart(2, '0')
        const seconds = String(date.getSeconds()).padStart(2, '0')
        return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`
      }
      
      this.ticketForm = {
        id: null,
        ownerId: this.topicDetail.id,
        ownerType: 1,
        name: '',
        price: 0,
        startTime: formatDateTime(today),
        endTime: endDate.toISOString().split('T')[0] + ' 23:59:59',
        totalInventory: 0,
        remainingInventory: 0,
        memberId: this.topicDetail.memberId
      }
      
      this.$nextTick(() => {
        if (this.$refs.ticketForm) {
          this.$refs.ticketForm.clearValidate()
        }
      })
    },
    
    // 提交票务表单
    submitTicketForm() {
      this.$refs['ticketForm'].validate(valid => {
        if (valid) {
          this.ticketLoading = true
          const isEditing = this.editingTicketIndex !== -1
          const apiCall = isEditing ? updateTicket : addTicket

          // 编辑不能重置订单流维护的 remainingInventory。ownerType/ownerId 归属声明新增和编辑都必须带：
          // 服务端据此拒绝「本页面的票 id 其实属于别的活动/主题」的跨归属修改（缺声明直接 400）。
          const submitData = {
            id: this.ticketForm.id,
            ownerId: this.ticketForm.ownerId,
            ownerType: this.ticketForm.ownerType,
            name: this.ticketForm.name,
            price: parseFloat(this.ticketForm.price) || 0,
            totalInventory: parseInt(this.ticketForm.totalInventory) || 0,
            startTime: this.ticketForm.startTime,
            endTime: this.ticketForm.endTime
          }
          if (!isEditing) {
            submitData.memberId = this.ticketForm.memberId
            submitData.remainingInventory = submitData.totalInventory
          }
          
          apiCall(submitData).then(res => {
            this.ticketLoading = false
            
            if (res.code === 200) {
              // 显示成功消息
              this.$modal.msgSuccess(`${isEditing ? '修改' : '添加'}成功`)
              this.ticketDialogVisible = false
              // 后端会保留编辑时的 remainingInventory；重新读取才能展示真实库存。
              return this.getDetail(this.topicDetail.id)
            } else {
              this.$modal.msgError(res.msg || '操作失败')
            }
          }).catch(err => {
            this.ticketLoading = false
            console.error('操作失败:', err)
            this.$modal.msgError('操作失败，请重试')
          })
        }
      })
    }
  }
}
</script>

<style lang="scss" scoped>
.topic-detail-wrapper {
  .label {
    font-weight: bold;
    margin-bottom: var(--cy-space-2);
    color: #606266;
  }
  
  .lineup-item {
    padding: var(--cy-legacy-15);
    border: 1px solid #ebeef5;
    border-radius: 4px;
    
    .name {
      font-weight: bold;
      margin-bottom: var(--cy-legacy-5);
    }
    
    .role {
      color: #909399;
      font-size: 13px;
      margin-bottom: var(--cy-space-1-5);
    }
  }
  
  .my-label {
    width: 100px;
  }
  
  .my-content {
    width: 400px;
  }
  
  .img-list {
    display: flex;
    flex-wrap: wrap;
    margin-top: var(--cy-space-1-5);
    
    .el-image {
      margin-bottom: var(--cy-space-1-5);
    }
  }
}
</style>
