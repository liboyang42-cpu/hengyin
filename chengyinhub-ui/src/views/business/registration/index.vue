<template>
  <div class="app-container">
    <cy-page-header
      eyebrow="资金与售后"
      title="报名台账"
      description="按报名类型、编号、名称和用户定位报名记录与核销事实。"
    />

    <cy-resource-state
      v-if="listError"
      kind="error"
      title="报名列表暂时无法读取"
      description="读取失败不会伪装成空数据；请检查权限或网络后重试。"
      retry-text="重新读取"
      :retrying="loading"
      @retry="getList"
    />

    <el-form v-if="!listError" :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">

      <el-form-item label="报名类型" prop="ownerType">
        <DictRadio v-model="queryParams.ownerType" :radioData="dict.type.sys_ticket_type" size="small"
          :show-all="'all'"  @change="handleQuery"></DictRadio>
      </el-form-item>

      <el-form-item label="报名编号" prop="registrationNo">
        <el-input
          v-model="queryParams.registrationNo"
          placeholder="请输入报名编号"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="名称" prop="activityTitle">
        <el-input
          v-model="queryParams.activityTitle"
          placeholder="请输入活动/主题名称"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="用户ID" prop="memberId">
        <el-input
          v-model="queryParams.memberId"
          placeholder="请输入用户ID"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="核销状态" prop="verificationStatus">
        <el-select v-model="queryParams.verificationStatus" placeholder="全部" clearable @change="handleQuery" style="width:120px">
          <el-option label="未核销" :value="0" />
          <el-option label="已核销" :value="1" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <!-- <el-col :span="1.5">
        <el-button
          type="success"
          plain
          icon="el-icon-edit"
          size="mini"
          :disabled="single"
          @click="handleUpdate"
          v-hasPermi="['business:registration:edit']"
        >修改</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="danger"
          plain
          icon="el-icon-delete"
          size="mini"
          :disabled="multiple"
          @click="handleDelete"
          v-hasPermi="['business:registration:remove']"
        >删除</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="warning"
          plain
          icon="el-icon-download"
          size="mini"
          @click="handleExport"
          v-hasPermi="['business:registration:export']"
        >导出</el-button>
      </el-col> -->
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="registrationList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="报名ID" align="center" prop="id" />
      
      <el-table-column label="报名编号" align="center" prop="registrationNo" />

      <el-table-column label="报名类型" align="center" prop="ownerType">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.sys_ticket_type" :value="scope.row.ownerType" />
        </template>
      </el-table-column>


      <el-table-column label="活动信息" prop="totalAmount" width="180">
        <template v-slot="scope">
          <div>
            {{scope.row.ownerType==1 ? '主题ID':'活动ID'}}： {{ scope.row.ownerId }}
          </div>
          <div>
            {{scope.row.ownerType==1 ? '主题名称':'活动名称'}}： {{ scope.row.activityTitle }}
          </div>
          <div>票种ID： {{ scope.row.ticketId }}</div>
          <div>票种名称： {{ scope.row.ticketName }}</div>
          <div>票种价格： {{ scope.row.ticketPrice }}</div>
         </template>
      </el-table-column>
      <el-table-column label="报名信息" prop="realName" width="280">
        <template v-slot="scope">
           <div>
            <span>{{ scope.row.memberId }}</span>
          </div>
          <div v-if="canViewPii">
            <span>{{ scope.row.realName }} / {{ scope.row.phone }} / {{ scope.row.email }}</span>
          </div>
          <div v-else>
            <span>{{ maskName(scope.row.realName) }} / {{ maskPhone(scope.row.phone) }} / {{ maskEmail(scope.row.email) }}</span>
          </div>
          <div v-if="canViewPii">
            <span>{{ scope.row.address }}</span>
          </div>
          <div v-else>
            <span>{{ maskAddress(scope.row.address) }}</span>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="合计" prop="totalAmount" width="180">
        <template v-slot="scope">
           <div>
            订票数量： {{ scope.row.orderNum }}
          </div>
          <div>
            原始金额： {{ scope.row.originalAmount }}
          </div>
          <div v-if="scope.row.discountAmount>0">折扣金额： {{ scope.row.discountAmount }}</div>
          <div>应付总额：<span style="color: red;"> {{ scope.row.totalAmount }}</span></div>

          <div v-if="scope.row.pointUsed>0">使用积分： {{ scope.row.pointUsed }}</div>
          <div v-if="scope.row.discountAmount>0">积分抵扣金额： {{ scope.row.discountAmount }}</div>
          <div v-if="scope.row.pointDeductRate>0">积分抵扣比例： {{ scope.row.pointDeductRate }}</div>
          <div v-if="scope.row.maxPointDeduct>0">最大抵扣金额： {{ scope.row.maxPointDeduct }}</div>
          <div v-if="scope.row.discountAmount>0">折扣金额： {{ scope.row.discountAmount }}</div>
          <div>实际支付金额：<span style="color: red;"> {{ scope.row.payableAmount }}</span></div>

        </template>
      </el-table-column>

      <el-table-column label="支付信息" prop="totalAmount" width="180">
        <template v-slot="scope">
            <dict-tag  :options="dict.type.sys_payment_status" :value="scope.row.paymentStatus" />
          <div  v-if="scope.row.pointPaymentAmount==0">
            支付方式： 纯积分
          </div>
          <div  v-if="scope.row.pointPaymentAmount==1">
            支付方式： 纯微信
          </div>
          <div  v-if="scope.row.pointPaymentAmount==2">
            支付方式：积分+微信
          </div>
          <div>
            支付时间： <span>{{ parseTime(scope.row.paymentTime, '{y}-{m}-{d}') }}</span>
          </div>
          <div>
            支付交易号： {{ scope.row.transactionId }}
          </div>
          <div v-if="scope.row.pointPaymentAmount>0">积分支付金额： {{ scope.row.pointPaymentAmount }}</div>
          <div v-if="scope.row.wechatPaymentAmount>0">微信支付金额： {{ scope.row.wechatPaymentAmount }}</div>
        </template>
      </el-table-column>

      <el-table-column label="报名状态" align="center" prop="registrationStatus">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.sys_registration_status" :value="scope.row.registrationStatus" />
        </template>
      </el-table-column>

      <el-table-column label="二维码" align="center" prop="logo" >
          <template slot-scope="scope">
            <el-image class="small-img circle-img" :src="scope.row.qrcodeUrl" :preview-src-list="[scope.row.qrcodeUrl]" style="height: 50px;width: 50px;" />
            </template>
      </el-table-column>

      <el-table-column label="核销信息" prop="totalAmount" width="180">
        <template v-slot="scope">
          <div>核销码： {{ scope.row.verificationCode }}</div>
          <div  v-if="scope.row.verificationStatus==0">
            核销状态：未核销
          </div>
          <div  v-if="scope.row.verificationStatus==1">
            核销状态：已核销
          </div>
          <div>核销时间： <span>{{ parseTime(scope.row.verificationTime, '{y}-{m}-{d}') }}</span></div>
          <div>核销人ID： {{ scope.row.verifierId }}</div>
        </template>
      </el-table-column>
 
      <el-table-column label="操作" align="center" fixed="right" width="160" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-tickets"
            @click="handleRedeemRecords(scope.row)"
            v-hasPermi="['business:registration:query']"
          >核销记录</el-button>
          <el-button
            v-if="canApplyRefund(scope.row)"
            size="mini"
            type="text"
            icon="el-icon-refresh-left"
            :disabled="refundSubmitting"
            :loading="refundSubmitting"
            @click="handleRefund(scope.row)"
            v-hasPermi="['business:refund:review']"
          >申请退款</el-button>
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

    <!-- 核销流水对话框:含已撤销行(何时/因何被撤读得回来);撤销按钮是平台侧唯一纠错入口 -->
    <el-dialog title="核销记录" :visible.sync="redeemOpen" width="760px" append-to-body>
      <div class="redeem-hint">
        本票的核销流水(探索票按章核销)。整单核销的活动票没有按章流水,可用下方「撤销整单核销」恢复为未核销。
        撤销会回补商家配额、退回玩家权益、冲正平台应付,并留痕(操作人/原因/时间)。
      </div>
      <el-button v-if="canReverseWholeOrder" type="danger" size="mini" :disabled="reverseSubmitting"
        v-hasPermi="['business:registration:reverseRedeem']" @click="handleReverseWholeOrder">撤销整单核销</el-button>
      <el-button v-if="canReverseParentOrder" type="danger" size="mini" :disabled="reverseSubmitting"
        v-hasPermi="['business:registration:reverseRedeem']" @click="handleReverseParentOrder">撤销本父订单全部生效核销</el-button>
      <el-table v-loading="redeemLoading" :data="redeemList" size="mini" empty-text="该票没有按章核销流水">
        <el-table-column label="流水ID" prop="id" width="80" />
        <el-table-column label="章节ID" width="80">
          <template slot-scope="scope">
            {{ scope.row.chapterId ? scope.row.chapterId : '整单' }}
          </template>
        </el-table-column>
        <el-table-column label="商家ID" prop="merchantId" width="90" />
        <el-table-column label="操作人ID" prop="operatorId" width="90" />
        <el-table-column label="状态" width="90">
          <template slot-scope="scope">
            <el-tag :type="scope.row.status === 'ACTIVE' ? 'success' : 'info'" size="mini">
              {{ scope.row.status === 'ACTIVE' ? '生效' : '已撤销' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="核销时间" width="150">
          <template slot-scope="scope">{{ parseTime(scope.row.createTime, '{y}-{m}-{d} {h}:{i}:{s}') }}</template>
        </el-table-column>
        <el-table-column label="撤销时间" width="150">
          <template slot-scope="scope">{{ parseTime(scope.row.reversedAt, '{y}-{m}-{d} {h}:{i}:{s}') }}</template>
        </el-table-column>
        <el-table-column label="撤销原因" prop="reverseReason" min-width="120" show-overflow-tooltip />
        <el-table-column label="操作" width="80" fixed="right">
          <template slot-scope="scope">
            <el-button
              v-if="scope.row.status === 'ACTIVE'"
              size="mini"
              type="text"
              :disabled="reverseSubmitting"
              @click="handleReverseRedeem(scope.row)"
              v-hasPermi="['business:registration:reverseRedeem']"
            >撤销</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 撤销核销确认:原因必填留痕。第二轮拍板第 4 条:可勾选同时冲回这次核销发出的积分和经验 -->
    <el-dialog :title="reverseWholeOrderId ? '撤销整单核销' : (reverseParentOrderNo ? '撤销父订单全部核销' : '撤销核销')" :visible.sync="reverseOpen" width="480px" append-to-body :close-on-click-modal="false">
      <p v-if="reverseParentOrderNo">逐章撤销此父订单全部生效核销；任一失败都不会提交。请核对原订单及撤销原因。</p>
      <p v-if="reverseWholeOrderId">把这张活动票恢复为未核销(票款入账不变);活动已结算或主题票请走人工售后。</p>
      <el-input
        v-model="reverseForm.reason"
        type="textarea"
        :rows="2"
        maxlength="255"
        show-word-limit
        placeholder="撤销原因(必填,会留痕:操作人/原因/时间),如:店员扫错票 / 核错项目"
      />
      <div class="reverse-clawback">
        <el-checkbox v-model="reverseForm.clawbackRewards">同时冲回因这次核销发的积分和经验</el-checkbox>
        <div class="redeem-hint">勋章不收回;连带发的通关券未使用则作废,已使用只记审计不追回。积分/经验不够扣到 0,差额记进审计。</div>
      </div>
      <div slot="footer" class="dialog-footer">
        <el-button @click="reverseOpen = false">取 消</el-button>
        <el-button type="danger" :loading="reverseSubmitting" @click="submitReverseRedeem">确定撤销</el-button>
      </div>
    </el-dialog>
    
    <!-- 添加或修改活动/主题报名对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="报名编号(唯一)" prop="registrationNo">
          <el-input v-model="form.registrationNo" placeholder="请输入报名编号(唯一)" />
        </el-form-item>
        <el-form-item label="主题ID/活动ID" prop="ownerId">
          <el-input v-model="form.ownerId" placeholder="请输入主题ID/活动ID" />
        </el-form-item>
        <el-form-item label="活动/主题名称(冗余)" prop="activityTitle">
          <el-input v-model="form.activityTitle" placeholder="请输入活动/主题名称(冗余)" />
        </el-form-item>
        <el-form-item label="票种ID" prop="ticketId">
          <el-input v-model="form.ticketId" placeholder="请输入票种ID" />
        </el-form-item>
        <el-form-item label="票种名称(冗余)" prop="ticketName">
          <el-input v-model="form.ticketName" placeholder="请输入票种名称(冗余)" />
        </el-form-item>
        <el-form-item label="票种价格(冗余)" prop="ticketPrice">
          <el-input v-model="form.ticketPrice" placeholder="请输入票种价格(冗余)" />
        </el-form-item>
        <el-form-item label="用户ID" prop="memberId">
          <el-input v-model="form.memberId" placeholder="请输入用户ID" />
        </el-form-item>
        <el-form-item label="报名姓名" prop="realName">
          <el-input v-model="form.realName" placeholder="请输入报名姓名" />
        </el-form-item>
        <el-form-item label="联系电话" prop="phone">
          <el-input v-model="form.phone" placeholder="请输入联系电话" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" placeholder="请输入邮箱" />
        </el-form-item>
        <el-form-item label="地址" prop="address">
          <el-input v-model="form.address" placeholder="请输入地址" />
        </el-form-item>
        <el-form-item label="地址ID" prop="addressId">
          <el-input v-model="form.addressId" placeholder="请输入地址ID" />
        </el-form-item>
        <el-form-item label="订票数量" prop="orderNum">
          <el-input v-model="form.orderNum" placeholder="请输入订票数量" />
        </el-form-item>
        <el-form-item label="原始金额" prop="originalAmount">
          <el-input v-model="form.originalAmount" placeholder="请输入原始金额" />
        </el-form-item>
        <el-form-item label="折扣金额" prop="discountAmount">
          <el-input v-model="form.discountAmount" placeholder="请输入折扣金额" />
        </el-form-item>
        <el-form-item label="应付总额" prop="totalAmount">
          <el-input v-model="form.totalAmount" placeholder="请输入应付总额" />
        </el-form-item>
        <el-form-item label="使用积分数量" prop="pointUsed">
          <el-input v-model="form.pointUsed" placeholder="请输入使用积分数量" />
        </el-form-item>
        <el-form-item label="积分抵扣金额" prop="pointDeductAmount">
          <el-input v-model="form.pointDeductAmount" placeholder="请输入积分抵扣金额" />
        </el-form-item>
        <el-form-item label="积分抵扣比例(1积分=0.01元)" prop="pointDeductRate">
          <el-input v-model="form.pointDeductRate" placeholder="请输入积分抵扣比例(1积分=0.01元)" />
        </el-form-item>
        <el-form-item label="最大可抵扣金额" prop="maxPointDeduct">
          <el-input v-model="form.maxPointDeduct" placeholder="请输入最大可抵扣金额" />
        </el-form-item>
        <el-form-item label="实际需支付金额" prop="payableAmount">
          <el-input v-model="form.payableAmount" placeholder="请输入实际需支付金额" />
        </el-form-item>
        <el-form-item label="支付时间" prop="paymentTime">
          <el-date-picker clearable
            v-model="form.paymentTime"
            type="date"
            value-format="yyyy-MM-dd"
            placeholder="请选择支付时间">
          </el-date-picker>
        </el-form-item>
        <el-form-item label="支付交易号" prop="transactionId">
          <el-input v-model="form.transactionId" placeholder="请输入支付交易号" />
        </el-form-item>
        <el-form-item label="积分支付金额" prop="pointPaymentAmount">
          <el-input v-model="form.pointPaymentAmount" placeholder="请输入积分支付金额" />
        </el-form-item>
        <el-form-item label="微信支付金额" prop="wechatPaymentAmount">
          <el-input v-model="form.wechatPaymentAmount" placeholder="请输入微信支付金额" />
        </el-form-item>
        <el-form-item label="支付过期时间" prop="payExpireTime">
          <el-date-picker clearable
            v-model="form.payExpireTime"
            type="date"
            value-format="yyyy-MM-dd"
            placeholder="请选择支付过期时间">
          </el-date-picker>
        </el-form-item>
        <el-form-item label="核销码" prop="verificationCode">
          <el-input v-model="form.verificationCode" placeholder="请输入核销码" />
        </el-form-item>
        <el-form-item label="核销二维码图片URL" prop="qrcodeUrl">
          <el-input v-model="form.qrcodeUrl" placeholder="请输入核销二维码图片URL" />
        </el-form-item>
        <el-form-item label="核销时间" prop="verificationTime">
          <el-date-picker clearable
            v-model="form.verificationTime"
            type="date"
            value-format="yyyy-MM-dd"
            placeholder="请选择核销时间">
          </el-date-picker>
        </el-form-item>
        <el-form-item label="核销人ID" prop="verifierId">
          <el-input v-model="form.verifierId" placeholder="请输入核销人ID" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listRegistration, getRegistration, delRegistration, updateRegistration, listRegistrationRedemptions, reverseRedemption, reverseParentOrderRedemptions, reverseWholeOrderVerification } from "@/api/business/registration"
import { addRefund } from "@/api/business/refund"
import auth from '@/plugins/auth'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'

function maskName(value) {
  if (!value) return '—'
  const s = String(value)
  if (s.length <= 1) return s + '*'
  return s.charAt(0) + '*'.repeat(Math.min(s.length - 1, 4))
}

function maskPhone(value) {
  if (!value) return '—'
  const s = String(value)
  if (s.length < 7) return s.charAt(0) + '***'
  return s.slice(0, 3) + '****' + s.slice(-4)
}

function maskEmail(value) {
  if (!value) return '—'
  const s = String(value)
  const at = s.indexOf('@')
  if (at <= 1) return s.charAt(0) + '***'
  return s.charAt(0) + '***' + s.slice(at)
}

function maskAddress(value) {
  if (!value) return '—'
  const s = String(value)
  if (s.length <= 3) return s + '****'
  return s.slice(0, 3) + '****'
}

export default {
  name: "Registration",
  components: { CyPageHeader, CyResourceState },
  dicts: [ 'sys_business_status','sys_ticket_type','sys_yes_no_num','sys_payment_status','sys_registration_status'],
  data() {
    return {
      // 列表错误态
      listError: false,
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
      // 总条数
      total: 0,
      // 活动/主题报名表格数据
      registrationList: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      // 退款提交中
      refundSubmitting: false,
      // 核销流水弹窗
      redeemOpen: false,
      redeemLoading: false,
      redeemRegistrationId: null,
      redeemParentOrderNo: '',
      reverseParentOrderNo: '',
      // 当前打开的行是否为可撤销的整单核销活动票(owner_type=2、已核销、非③)
      redeemWholeOrderRow: false,
      reverseWholeOrderId: null,
      redeemList: [],
      // 撤销核销提交中
      reverseSubmitting: false,
      // 撤销核销确认弹窗(原因 + 是否冲回积分/经验)
      reverseOpen: false,
      reverseForm: { redemptionId: null, reason: '', clawbackRewards: false },
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        registrationNo: null,
        verificationStatus: null,
        ownerType: null,
        ownerId: null,
        activityTitle: null,
        ticketId: null,
        ticketName: null,
        ticketPrice: null,
        memberId: null,
        realName: null,
        phone: null,
        email: null,
        address: null,
        addressId: null,
        orderNum: null,
        originalAmount: null,
        discountAmount: null,
        totalAmount: null,
        pointUsed: null,
        pointDeductAmount: null,
        pointDeductRate: null,
        maxPointDeduct: null,
        payableAmount: null,
        paymentStatus: null,
        paymentType: null,
        paymentTime: null,
        transactionId: null,
        pointPaymentAmount: null,
        wechatPaymentAmount: null,
        registrationStatus: null,
        payExpireTime: null,
        verificationCode: null,
        qrcodeUrl: null,
        verificationStatus: null,
        verificationTime: null,
        verifierId: null
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
        registrationNo: [
          { required: true, message: "报名编号(唯一)不能为空", trigger: "blur" }
        ],
        ownerId: [
          { required: true, message: "主题ID/活动ID不能为空", trigger: "blur" }
        ],
        ticketId: [
          { required: true, message: "票种ID不能为空", trigger: "blur" }
        ],
        ticketPrice: [
          { required: true, message: "票种价格(冗余)不能为空", trigger: "blur" }
        ],
        memberId: [
          { required: true, message: "用户ID不能为空", trigger: "blur" }
        ],
        originalAmount: [
          { required: true, message: "原始金额不能为空", trigger: "blur" }
        ],
        totalAmount: [
          { required: true, message: "应付总额不能为空", trigger: "blur" }
        ],
        payableAmount: [
          { required: true, message: "实际需支付金额不能为空", trigger: "blur" }
        ],
        paymentStatus: [
          { required: true, message: "支付状态：0待支付 1支付中 2已支付 3支付失败 4已退款不能为空", trigger: "change" }
        ],
        registrationStatus: [
          { required: true, message: "报名状态：1待支付 2报名成功 3已取消 4已过期不能为空", trigger: "change" }
        ],
        payExpireTime: [
          { required: true, message: "支付过期时间不能为空", trigger: "blur" }
        ],
      }
    }
  },
  computed: {
    canViewPii() {
      return auth.hasRole('admin')
    },
    // 整单核销的活动票只有在流水读完且没有生效的按章核销时才走整单撤销;有按章流水的逐条撤销。
    canReverseWholeOrder() {
      return this.redeemWholeOrderRow && !this.redeemLoading
        && !(this.redeemList || []).some(row => row && row.status === 'ACTIVE')
    },
    // 父订单批量撤销只撤按章流水:本票没有生效的按章核销(整单核销票)时不出现,与整单撤销互斥。
    canReverseParentOrder() {
      return !!this.redeemParentOrderNo && !this.redeemLoading
        && (this.redeemList || []).some(row => row && row.status === 'ACTIVE')
    }
  },
  created() {
    this.getList()
  },
  methods: {
    /** 查询活动/主题报名列表 */
    getList() {
      this.loading = true
      this.listError = false
      listRegistration(this.queryParams).then(response => {
        this.registrationList = response.rows
        this.total = response.total
        this.loading = false
      }).catch(() => {
        this.loading = false
        this.listError = true
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
        registrationNo: null,
        ownerType: null,
        ownerId: null,
        activityTitle: null,
        ticketId: null,
        ticketName: null,
        ticketPrice: null,
        memberId: null,
        realName: null,
        phone: null,
        email: null,
        address: null,
        addressId: null,
        orderNum: null,
        originalAmount: null,
        discountAmount: null,
        totalAmount: null,
        pointUsed: null,
        pointDeductAmount: null,
        pointDeductRate: null,
        maxPointDeduct: null,
        payableAmount: null,
        paymentStatus: null,
        paymentType: null,
        paymentTime: null,
        transactionId: null,
        pointPaymentAmount: null,
        wechatPaymentAmount: null,
        registrationStatus: null,
        payExpireTime: null,
        createBy: null,
        createTime: null,
        updateBy: null,
        updateTime: null,
        verificationCode: null,
        qrcodeUrl: null,
        verificationStatus: null,
        verificationTime: null,
        verifierId: null
      }
      this.resetForm("form")
    },
    /** 搜索按钮操作 */
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    /** 重置按钮操作 */
    resetQuery() {
      this.resetForm("queryForm")
      this.handleQuery()
    },
    // 多选框选中数据
    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.id)
      this.single = selection.length!==1
      this.multiple = !selection.length
    },
    maskName,
    maskPhone,
    maskEmail,
    maskAddress,
    canApplyRefund(row) {
      return row && row.paymentStatus === 2 && row.verificationStatus !== 1
    },
    handleRefund(row) {
      if (this.refundSubmitting) return
      const amount = row.payableAmount || row.totalAmount || row.originalAmount || 0
      this.refundSubmitting = true
      this.$prompt("请输入退款原因", "申请退款", {
        confirmButtonText: "确定",
        cancelButtonText: "取消",
        inputValue: "用户申请退款",
        inputValidator: value => !!value,
        inputErrorMessage: "退款原因不能为空"
      }).then(({ value }) => {
        return addRefund({
          refundNo: "RF" + Date.now(),
          sourceType: "registration",
          sourceId: row.id,
          ownerType: row.ownerType,
          ownerId: row.ownerId,
          memberId: row.memberId,
          paidAmount: amount,
          refundAmount: amount,
          status: 0,
          reason: value,
          idempotencyKey: "registration:" + row.id
        })
      }).then(() => {
        this.$modal.msgSuccess("退款申请已提交")
        this.getList()
      }).catch(() => {}).finally(() => {
        this.refundSubmitting = false
      })
    },
    /** 打开核销流水弹窗(含已撤销行,撤销留痕读得回来) */
    handleRedeemRecords(row) {
      this.redeemRegistrationId = row.id
      this.redeemParentOrderNo = row.parentOrderNo || ''
      this.redeemWholeOrderRow = Number(row.ownerType) === 2 && Number(row.verificationStatus) === 1
        && Number(row.purchaseKind) !== 3
      this.redeemList = []
      this.redeemOpen = true
      this.loadRedemptions()
    },
    loadRedemptions() {
      this.redeemLoading = true
      listRegistrationRedemptions(this.redeemRegistrationId).then(response => {
        this.redeemList = response.data || []
        this.redeemLoading = false
      }).catch(() => {
        this.redeemLoading = false
      })
    },
    /**
     * 撤销核销(平台侧唯一纠错入口)。
     * 原因由操作人现填,前端不预填默认值 —— 服务端会把它连同操作者落进不可变审计。
     * 「同时冲回积分和经验」默认不勾:冲回是对玩家的扣减,必须操作人明确选择。
     */
    handleReverseRedeem(row) {
      if (this.reverseSubmitting) return
      this.reverseParentOrderNo = ''
      this.reverseWholeOrderId = null
      this.reverseForm = { redemptionId: row.id, reason: '', clawbackRewards: false }
      this.reverseOpen = true
    },
    handleReverseParentOrder() {
      if (this.reverseSubmitting || !this.canReverseParentOrder) return
      this.reverseParentOrderNo = this.redeemParentOrderNo
      this.reverseWholeOrderId = null
      this.reverseForm = { redemptionId: null, reason: '', clawbackRewards: false }
      this.reverseOpen = true
    },
    handleReverseWholeOrder() {
      if (this.reverseSubmitting || !this.canReverseWholeOrder) return
      this.reverseParentOrderNo = ''
      this.reverseWholeOrderId = this.redeemRegistrationId
      this.reverseForm = { redemptionId: null, reason: '', clawbackRewards: false }
      this.reverseOpen = true
    },
    submitReverseRedeem() {
      if (this.reverseSubmitting) return Promise.resolve()
      const reason = (this.reverseForm.reason || '').trim()
      if (!reason) {
        this.$modal.msgError("撤销原因不能为空")
        return Promise.resolve()
      }
      this.reverseSubmitting = true
      if (this.reverseWholeOrderId) {
        const clawbackRewards = this.reverseForm.clawbackRewards === true
        return reverseWholeOrderVerification({ registrationId: this.reverseWholeOrderId, reason, clawbackRewards }).then(() => {
          this.reverseOpen = false
          this.redeemWholeOrderRow = false
          this.$modal.msgSuccess(clawbackRewards ? "已撤销整单核销,到店积分和经验已冲回(明细见审计)" : "已撤销整单核销")
          this.loadRedemptions()
          this.getList()
        }).catch(() => {}).finally(() => {
          this.reverseSubmitting = false
        })
      }
      const payload = { reason, clawbackRewards: this.reverseForm.clawbackRewards === true }
      const reverse = this.reverseParentOrderNo ? reverseParentOrderRedemptions : reverseRedemption
      if (this.reverseParentOrderNo) payload.parentOrderNo = this.reverseParentOrderNo
      else payload.redemptionId = this.reverseForm.redemptionId
      return reverse(payload).then(() => {
        this.reverseOpen = false
        // 票上还有其他生效核销/主题仍通关时服务端不冲回,不能笼统说「已冲回」;明细在审计里
        this.$modal.msgSuccess(this.reverseForm.clawbackRewards ? "已撤销,符合条件的积分和经验已冲回(明细见审计)" : "已撤销")
        this.loadRedemptions()
        this.getList()
      }).catch(() => {}).finally(() => {
        this.reverseSubmitting = false
      })
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset()
      const id = row.id || this.ids
      getRegistration(id).then(response => {
        this.form = response.data
        this.open = true
        this.title = "修改活动/主题报名"
      })
    },
    /** 提交按钮 */
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            updateRegistration(this.form).then(response => {
              this.$modal.msgSuccess("修改成功")
              this.open = false
              this.getList()
            })
          } else {
            this.$modal.msgError("通用新增报名已关闭，请通过正式下单流程创建报名")
          }
        }
      })
    },
    /** 删除按钮操作 */
    handleDelete(row) {
      const ids = row.id || this.ids
      this.$modal.confirm('是否确认删除活动/主题报名编号为"' + ids + '"的数据项？').then(function() {
        return delRegistration(ids)
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess("删除成功")
      }).catch(() => {})
    },
    /** 导出按钮操作 */
    handleExport() {
      this.download('business/registration/export', {
        ...this.queryParams
      }, `registration_${new Date().getTime()}.xlsx`)
    }
  }
}
</script>

<style scoped>
.redeem-hint {
  margin-bottom: 12px;
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
}
.reverse-clawback {
  margin-top: 12px;
}
.reverse-clawback .redeem-hint {
  margin: 4px 0 0 24px;
}
</style>
