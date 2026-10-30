<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import AppModal from './components/AppModal.vue'
import MerchantManagement from './components/MerchantManagement.vue'
import {
  api, clearToken, getToken, platformApi, request, setToken,
  type PlatformAuditEntry, type PlatformBuyer, type PlatformBuyerContact,
  type PlatformBuyerFilter, type PlatformMerchantPerformance, type PlatformOrder,
  type PlatformOrderContact, type PlatformOrderFilter, type PlatformOverview,
  type PlatformPage,
} from './lib/api'

type PlatformSection = 'overview' | 'tasks' | 'orders' | 'buyers' | 'performance' | 'merchants' | 'audit'
type TaskType = 'audit' | 'payment' | 'fulfillment'
type PendingAccess = { kind: 'order' | 'buyer'; merchantId: number; id: number }
const taskTabs: TaskType[] = ['audit', 'payment', 'fulfillment']

const authenticated = ref(false)
const busy = ref(false)
const account = ref('')
const password = ref('')
const error = ref('')
const activeSection = ref<PlatformSection>('overview')
const overview = ref<PlatformOverview | null>(null)
const overviewLoading = ref(false)
const sectionLoading = ref(false)
const sectionError = ref('')
const taskType = ref<TaskType>('audit')
const taskMerchantId = ref<number | null>(null)
const taskResult = ref<PlatformPage<PlatformOrder>>({ items: [], page: 1, pageSize: 10, total: 0, totalPages: 1 })
const orderResult = ref<PlatformPage<PlatformOrder>>({ items: [], page: 1, pageSize: 10, total: 0, totalPages: 1 })
const buyerResult = ref<PlatformPage<PlatformBuyer>>({ items: [], page: 1, pageSize: 10, total: 0, totalPages: 1 })
const performanceResult = ref<PlatformPage<PlatformMerchantPerformance> & { from: string; to: string }>({ items: [], page: 1, pageSize: 10, total: 0, totalPages: 1, from: '', to: '' })
const auditResult = ref<PlatformPage<PlatformAuditEntry>>({ items: [], page: 1, pageSize: 10, total: 0, totalPages: 1 })
const orderFilter = reactive<PlatformOrderFilter>({ q: '', merchantId: null, orderStatus: '', paymentStatus: '', auditStatus: '' })
const buyerFilter = reactive<PlatformBuyerFilter>({ q: '', merchantId: null, active: null })
const orderRange = reactive({ from: '', to: '' })
const buyerRange = reactive({ from: '', to: '' })
const performanceRange = reactive({ from: '', to: '' })
const accessPending = ref<PendingAccess | null>(null)
const accessOpen = ref(false)
const accessPurpose = ref('')
const accessLoading = ref(false)
const detailError = ref('')
const orderDetail = ref<PlatformOrder | null>(null)
const buyerDetail = ref<PlatformBuyer | null>(null)
const orderContact = ref<PlatformOrderContact | null>(null)
const buyerContact = ref<PlatformBuyerContact | null>(null)
const detailOpen = ref(false)
const contactPurpose = ref('')
const contactLoading = ref(false)
const exportBusy = ref(false)
const taskTotal = computed(() => (overview.value?.pendingAuditCount || 0) + (overview.value?.pendingVerificationCount || 0) + (overview.value?.pendingFulfillmentCount || 0))

const sectionTitle: Record<PlatformSection, string> = {
  overview: '经营总览', tasks: '待处理订单', orders: '订单中心', buyers: '买家中心',
  performance: '商户分析', merchants: '商户管理', audit: '平台审计',
}
const sectionDescription: Record<PlatformSection, string> = {
  overview: '查看全平台交易、买家增长和需要处理的业务状态。',
  tasks: '集中查看待审核、待核款和待履约订单。',
  orders: '跨商户查询订单，查看付款与履约进度。',
  buyers: '按商户查询买家账户及购买概况。',
  performance: '比较商户订单、收款和买家变化。',
  merchants: '开通商户并维护商城配置。',
  audit: '追踪平台业务数据访问和商户配置变更。',
}
const orderStatusLabels: Record<string, string> = { pending: '待处理', packed: '已打包', shipped: '运输中', received: '已收货', completed: '已完成', cancelled: '已取消' }
const paymentStatusLabels: Record<string, string> = { unpaid: '待付款', pending_verification: '待核款', partially_paid: '部分付款', paid: '已付清' }
const auditStatusLabels: Record<string, string> = { pending: '待审核', approved: '已通过', rejected: '未通过', withdrawn: '已撤回' }
const taskLabels: Record<TaskType, string> = { audit: '待审核', payment: '待核款', fulfillment: '待履约' }
const auditActionLabels: Record<string, string> = { detail_view: '查看详情', contact_reveal: '查看联系方式', merchant_created: '创建商户', merchant_updated: '更新商户' }
const nav = computed((): Array<{ id: PlatformSection; label: string; symbol: string; badge?: number }> => [
  { id: 'overview' as const, label: '经营总览', symbol: '⌂' },
  { id: 'tasks' as const, label: '待处理订单', symbol: '◷', badge: taskTotal.value },
  { id: 'orders' as const, label: '订单中心', symbol: '▤' },
  { id: 'buyers' as const, label: '买家中心', symbol: '◎' },
  { id: 'performance' as const, label: '商户分析', symbol: '⌁' },
  { id: 'merchants' as const, label: '商户管理', symbol: '▦' },
  { id: 'audit' as const, label: '平台审计', symbol: '≋' },
])
const trendDays = computed(() => {
  const values = new Map((overview.value?.trend || []).map(item => [item.day, item] as const))
  const today = new Date()
  const dates: string[] = []
  for (let offset = 6; offset >= 0; offset -= 1) {
    const day = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate() - offset)).toISOString().slice(0, 10)
    dates.push(day)
  }
  return dates.map(day => ({ day, ...(values.get(day) || { orderCount: 0, orderAmount: 0 }) }))
})
const trendMax = computed(() => Math.max(1, ...trendDays.value.map(item => Number(item.orderCount))))

function message(e: unknown) { return e instanceof Error ? e.message : '请求失败，请稍后重试。' }
function queryMerchantId(value: number | null | undefined) { return value && value > 0 ? value : null }
function money(value: number | string | null | undefined) {
  const amount = Number(value || 0)
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(Number.isFinite(amount) ? amount : 0)
}
function number(value: number | string | null | undefined) { return new Intl.NumberFormat('en-US').format(Number(value || 0)) }
function dateTime(value: string | null | undefined) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC' }).format(date) + ' UTC'
}
function dayLabel(day: string) { return day.slice(5).replace('-', '/') }
function orderStatusName(value: string) { return orderStatusLabels[value] || value }
function paymentStatusName(value: string) { return paymentStatusLabels[value] || value }
function auditStatusName(value: string) { return auditStatusLabels[value] || value }
function taskTitle(type: TaskType) { return taskLabels[type] }
function auditActionName(value: string) { return auditActionLabels[value] || value }
function statusClass(value: string) { return `platform-status-${value}` }

async function login() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await request<{ accessToken: string }>('/platform/auth/login', { method: 'POST', body: JSON.stringify({ account: account.value, password: password.value }) }, 'platform', { token: null, clearOnUnauthorized: false })
    setToken('platform', result.accessToken)
    password.value = ''
    authenticated.value = true
    await loadOverview()
  } catch (e) { error.value = message(e) }
  finally { busy.value = false }
}

async function logout() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await request('/platform/auth/logout', { method: 'POST' }, 'platform')
    clearToken('platform')
    authenticated.value = false
  } catch (e) { error.value = message(e) }
  finally { busy.value = false }
}

async function loadOverview() {
  overviewLoading.value = true
  sectionError.value = ''
  try { overview.value = await platformApi.overview() }
  catch (e) { sectionError.value = message(e) }
  finally { overviewLoading.value = false }
}

async function loadOrders(page = orderResult.value.page) {
  sectionLoading.value = true; sectionError.value = ''
  try { orderResult.value = await platformApi.orders({ ...orderFilter, merchantId: queryMerchantId(orderFilter.merchantId), from: utcStart(orderRange.from) || undefined, to: utcEndExclusive(orderRange.to) || undefined }, page) }
  catch (e) { sectionError.value = message(e) }
  finally { sectionLoading.value = false }
}

async function loadBuyers(page = buyerResult.value.page) {
  sectionLoading.value = true; sectionError.value = ''
  try { buyerResult.value = await platformApi.buyers({ ...buyerFilter, merchantId: queryMerchantId(buyerFilter.merchantId), from: utcStart(buyerRange.from) || undefined, to: utcEndExclusive(buyerRange.to) || undefined }, page) }
  catch (e) { sectionError.value = message(e) }
  finally { sectionLoading.value = false }
}

async function loadTasks(page = taskResult.value.page) {
  sectionLoading.value = true; sectionError.value = ''
  try { taskResult.value = await platformApi.tasks(taskType.value, queryMerchantId(taskMerchantId.value), page) }
  catch (e) { sectionError.value = message(e) }
  finally { sectionLoading.value = false }
}

function utcStart(value: string) { return value ? `${value}T00:00:00Z` : null }
function utcEndExclusive(value: string) {
  if (!value) return null
  const date = new Date(`${value}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + 1)
  return date.toISOString()
}
async function loadPerformance(page = performanceResult.value.page) {
  sectionLoading.value = true; sectionError.value = ''
  try { performanceResult.value = await platformApi.merchantPerformance(utcStart(performanceRange.from), utcEndExclusive(performanceRange.to), page) }
  catch (e) { sectionError.value = message(e) }
  finally { sectionLoading.value = false }
}

async function loadAudit(page = auditResult.value.page) {
  sectionLoading.value = true; sectionError.value = ''
  try { auditResult.value = await platformApi.audit(page) }
  catch (e) { sectionError.value = message(e) }
  finally { sectionLoading.value = false }
}

function openMerchantOrders(merchantId: number) {
  Object.assign(orderFilter, { q: '', merchantId, orderStatus: '', paymentStatus: '', auditStatus: '' })
  Object.assign(orderRange, { from: '', to: '' })
  void setSection('orders')
}

function openMerchantBuyers(merchantId: number) {
  Object.assign(buyerFilter, { q: '', merchantId, active: null })
  Object.assign(buyerRange, { from: '', to: '' })
  void setSection('buyers')
}

async function setSection(section: PlatformSection) {
  activeSection.value = section
  if (section === 'overview') await loadOverview()
  else if (section === 'orders') await loadOrders(1)
  else if (section === 'buyers') await loadBuyers(1)
  else if (section === 'tasks') await loadTasks(1)
  else if (section === 'performance') await loadPerformance(1)
  else if (section === 'audit') await loadAudit(1)
}

function changeTaskType(type: TaskType) { taskType.value = type; void loadTasks(1) }
function openAccess(target: PendingAccess) {
  accessPending.value = target
  accessPurpose.value = ''
  detailError.value = ''
  accessOpen.value = true
}
function closeAccess() { if (!accessLoading.value) { accessOpen.value = false; accessPending.value = null } }
function closeDetail() { if (!contactLoading.value) { detailOpen.value = false; orderDetail.value = null; buyerDetail.value = null; orderContact.value = null; buyerContact.value = null } }

async function confirmAccess() {
  const target = accessPending.value
  if (!target || accessLoading.value) return
  accessLoading.value = true; detailError.value = ''
  try {
    if (target.kind === 'order') orderDetail.value = await platformApi.orderAccess(target.merchantId, target.id, { purpose: accessPurpose.value.trim() })
    else buyerDetail.value = await platformApi.buyerAccess(target.merchantId, target.id, { purpose: accessPurpose.value.trim() })
    accessOpen.value = false
    detailOpen.value = true
    contactPurpose.value = ''
  } catch (e) { detailError.value = message(e) }
  finally { accessLoading.value = false }
}

async function revealContact() {
  if (!contactPurpose.value.trim() || contactLoading.value) return
  contactLoading.value = true; detailError.value = ''
  try {
    if (orderDetail.value) orderContact.value = await platformApi.orderContact(orderDetail.value.merchantId, orderDetail.value.id, { purpose: contactPurpose.value.trim() })
    else if (buyerDetail.value) buyerContact.value = await platformApi.buyerContact(buyerDetail.value.merchantId, buyerDetail.value.id, { purpose: contactPurpose.value.trim() })
    contactPurpose.value = ''
  } catch (e) { detailError.value = message(e) }
  finally { contactLoading.value = false }
}

function pageButtons(result: PlatformPage<unknown>) {
  const total = result.totalPages || 1
  if (total <= 5) return Array.from({ length: total }, (_, index) => index + 1)
  const first = Math.min(Math.max(result.page - 2, 1), total - 4)
  return Array.from({ length: 5 }, (_, index) => first + index)
}
function changePage(kind: 'orders' | 'buyers' | 'tasks' | 'performance' | 'audit', page: number) {
  if (sectionLoading.value || page < 1) return
  if (kind === 'orders' && page <= orderResult.value.totalPages) void loadOrders(page)
  else if (kind === 'buyers' && page <= buyerResult.value.totalPages) void loadBuyers(page)
  else if (kind === 'tasks' && page <= taskResult.value.totalPages) void loadTasks(page)
  else if (kind === 'performance' && page <= performanceResult.value.totalPages) void loadPerformance(page)
  else if (kind === 'audit' && page <= auditResult.value.totalPages) void loadAudit(page)
}

async function exportRows(type: 'orders' | 'buyers' | 'merchants') {
  if (exportBusy.value) return
  exportBusy.value = true; sectionError.value = ''
  try {
    const body = type === 'orders' ? { ...orderFilter, merchantId: queryMerchantId(orderFilter.merchantId), from: utcStart(orderRange.from), to: utcEndExclusive(orderRange.to) }
      : type === 'buyers' ? { ...buyerFilter, merchantId: queryMerchantId(buyerFilter.merchantId), from: utcStart(buyerRange.from), to: utcEndExclusive(buyerRange.to) }
        : { from: utcStart(performanceRange.from), to: utcEndExclusive(performanceRange.to) }
    const result = await platformApi.export(type, body)
    const url = URL.createObjectURL(result.blob)
    const anchor = document.createElement('a')
    anchor.href = url; anchor.download = result.filename; anchor.click()
    URL.revokeObjectURL(url)
  } catch (e) { sectionError.value = message(e) }
  finally { exportBusy.value = false }
}

function authCleared(event: Event) {
  if ((event as CustomEvent<string>).detail === 'platform') { authenticated.value = false; password.value = '' }
}
function storageChanged(event: StorageEvent) {
  if (event.key === 'dailyactivity.platform.token' || event.key === null) location.reload()
}

onMounted(async () => {
  window.addEventListener('authentication-cleared', authCleared)
  window.addEventListener('storage', storageChanged)
  if (!getToken('platform')) return
  busy.value = true
  try { await api.me('platform'); authenticated.value = true; await loadOverview() }
  catch (e) { error.value = message(e) }
  finally { busy.value = false }
})
onUnmounted(() => {
  window.removeEventListener('authentication-cleared', authCleared)
  window.removeEventListener('storage', storageChanged)
})
</script>

<template>
  <main class="platform-console demo-seller-shell">
    <section v-if="!authenticated" class="platform-login-stage">
      <form class="platform-login-panel" @submit.prevent="login">
        <div class="platform-login-mark" aria-hidden="true">DA</div>
        <p class="platform-kicker">DailyActivity 平台入口</p>
        <h1>平台运营中心</h1>
        <p class="platform-login-copy">登录后查看商户经营状态、订单和买家数据。</p>
        <label class="form-group">平台账号<input v-model="account" autocomplete="username" required maxlength="64" :disabled="busy"></label>
        <label class="form-group">密码<input v-model="password" type="password" autocomplete="current-password" required :disabled="busy"></label>
        <p v-if="error" class="field-error" role="alert">{{ error }}</p>
        <button class="btn platform-primary-button" :disabled="busy">{{ busy ? '正在验证…' : '登录平台' }}</button>
      </form>
    </section>

    <template v-else>
      <aside class="platform-rail">
        <div class="platform-brand"><span class="platform-brand-mark">DA</span><span><strong>DailyActivity</strong><small>平台运营中心</small></span></div>
        <nav class="platform-nav" aria-label="平台功能">
          <button v-for="item in nav" :key="item.id" type="button" :class="['platform-nav-item', { active: activeSection === item.id }]" :aria-current="activeSection === item.id ? 'page' : undefined" @click="setSection(item.id)">
            <span class="platform-nav-symbol" aria-hidden="true">{{ item.symbol }}</span><span>{{ item.label }}</span><span v-if="item.badge" class="platform-nav-badge">{{ item.badge }}</span>
          </button>
        </nav>
        <div class="platform-rail-foot"><span class="platform-live-dot"></span><span>全平台业务视图</span></div>
      </aside>

      <div class="platform-workspace">
        <header class="platform-topbar">
          <div><span class="platform-breadcrumb">DailyActivity / 平台</span><h1>{{ sectionTitle[activeSection] }}</h1></div>
          <button class="platform-logout" :disabled="busy" @click="logout">退出登录</button>
        </header>
        <section class="platform-content">
          <div class="platform-page-intro"><p>{{ sectionDescription[activeSection] }}</p><span v-if="activeSection === 'overview' && overview" class="platform-updated">UTC · 最近 7 天</span></div>
          <p v-if="sectionError" class="platform-error" role="alert">{{ sectionError }}</p>

          <section v-if="activeSection === 'overview'" class="platform-overview">
            <p v-if="overviewLoading && !overview" class="platform-loading">正在读取平台数据…</p>
            <template v-else-if="overview">
              <div class="platform-metric-band">
                <article class="platform-main-metric"><span>未取消订单金额</span><strong>{{ money(overview.grossOrderAmount) }}</strong><small>{{ number(overview.orderCount) }} 笔订单，累计已收 {{ money(overview.receivedAmount) }}</small></article>
                <div class="platform-small-metrics"><article><span>商户</span><strong>{{ number(overview.activeMerchantCount) }}<small> / {{ number(overview.merchantCount) }}</small></strong><em>当前启用</em></article><article><span>买家</span><strong>{{ number(overview.activeBuyerCount) }}<small> / {{ number(overview.buyerCount) }}</small></strong><em>当前启用</em></article></div>
              </div>
              <div class="platform-overview-grid">
                <section class="platform-panel platform-trend-panel">
                  <header class="platform-panel-heading"><div><h2>订单节奏</h2><p>每天新建订单数</p></div><button type="button" class="platform-text-action" @click="setSection('orders')">打开订单中心</button></header>
                  <div class="platform-chart" role="img" aria-label="最近七天每日订单数柱状图">
                    <div v-for="item in trendDays" :key="item.day" class="platform-chart-column"><strong>{{ number(item.orderCount) }}</strong><div class="platform-chart-track"><span :style="{ height: `${Math.max(5, Number(item.orderCount) / trendMax * 100)}%` }"></span></div><small>{{ dayLabel(item.day) }}</small></div>
                  </div>
                  <footer class="platform-chart-foot"><span>七天订单金额</span><strong>{{ money(trendDays.reduce((sum, item) => sum + Number(item.orderAmount || 0), 0)) }}</strong></footer>
                </section>
                <section class="platform-panel platform-queue-panel">
                  <header class="platform-panel-heading"><div><h2>待处理队列</h2><p>按业务状态持续更新</p></div><button type="button" class="platform-text-action" @click="setSection('tasks')">查看全部</button></header>
                  <button class="platform-queue-row" @click="taskType = 'audit'; setSection('tasks')"><span class="platform-queue-icon audit">A</span><span><strong>订单待审核</strong><small>等待商户确认订单</small></span><b>{{ number(overview.pendingAuditCount) }}</b></button>
                  <button class="platform-queue-row" @click="taskType = 'payment'; setSection('tasks')"><span class="platform-queue-icon payment">$</span><span><strong>付款待核验</strong><small>买家已提交付款信息</small></span><b>{{ number(overview.pendingVerificationCount) }}</b></button>
                  <button class="platform-queue-row" @click="taskType = 'fulfillment'; setSection('tasks')"><span class="platform-queue-icon fulfillment">↗</span><span><strong>订单待履约</strong><small>已审核，尚未完成发货</small></span><b>{{ number(overview.pendingFulfillmentCount) }}</b></button>
                </section>
              </div>
              <section class="platform-panel platform-overview-note"><strong>平台数据范围</strong><span>当前视图汇总所有商户。订单和买家联系资料在列表中遮蔽，查看详情或完整联系方式会记录访问用途。</span></section>
            </template>
            <button v-else class="btn" @click="loadOverview">重试加载</button>
          </section>

          <section v-else-if="activeSection === 'tasks'" class="platform-panel">
            <div class="platform-tabs" role="tablist" aria-label="待处理类型">
              <button v-for="kind in taskTabs" :key="kind" :class="{ active: taskType === kind }" role="tab" :aria-selected="taskType === kind" @click="changeTaskType(kind)">{{ taskTitle(kind) }}</button>
            </div>
            <div class="platform-table-toolbar"><span>{{ number(taskResult.total) }} 笔{{ taskTitle(taskType) }}订单</span><label class="platform-merchant-filter">商户 ID<input v-model.number="taskMerchantId" type="number" min="1" placeholder="全部"></label><button class="btn" :disabled="sectionLoading" @click="loadTasks(1)">查询</button><button class="btn" :disabled="sectionLoading" @click="loadTasks()">刷新</button></div>
            <div class="platform-table-wrap" :aria-busy="sectionLoading"><table class="platform-table"><thead><tr><th>订单</th><th>商户</th><th>买家</th><th>审核</th><th>付款</th><th>履约</th><th>金额</th><th>创建时间</th><th></th></tr></thead><tbody>
              <tr v-for="row in taskResult.items" :key="`${row.merchantId}-${row.id}`"><td><strong>{{ row.orderNo }}</strong></td><td>{{ row.merchantName }}</td><td>{{ row.buyerName }}<small>{{ row.email }}</small></td><td><span :class="['platform-status', statusClass(row.auditStatus)]">{{ auditStatusName(row.auditStatus) }}</span></td><td><span :class="['platform-status', statusClass(row.paymentStatus)]">{{ paymentStatusName(row.paymentStatus) }}</span></td><td><span :class="['platform-status', statusClass(row.orderStatus)]">{{ orderStatusName(row.orderStatus) }}</span></td><td>{{ money(row.totalAmount) }}</td><td>{{ dateTime(row.createdAt) }}</td><td><button class="platform-link" @click="openAccess({ kind: 'order', merchantId: row.merchantId, id: row.id })">查看</button></td></tr>
              <tr v-if="!sectionLoading && !taskResult.items.length"><td colspan="9" class="platform-empty">当前队列没有待处理订单。</td></tr>
            </tbody></table><p v-if="sectionLoading" class="platform-loading">正在读取订单…</p></div>
            <div class="platform-pagination"><span>共 {{ taskResult.total }} 条 · 第 {{ taskResult.page }} / {{ taskResult.totalPages }} 页</span><div><button :disabled="sectionLoading || taskResult.page <= 1" @click="changePage('tasks', taskResult.page - 1)">上一页</button><button v-for="numberPage in pageButtons(taskResult)" :key="numberPage" :class="{ active: taskResult.page === numberPage }" :disabled="sectionLoading" @click="changePage('tasks', numberPage)">{{ numberPage }}</button><button :disabled="sectionLoading || taskResult.page >= taskResult.totalPages" @click="changePage('tasks', taskResult.page + 1)">下一页</button></div></div>
          </section>

          <section v-else-if="activeSection === 'orders'" class="platform-panel">
            <form class="platform-filter-bar" @submit.prevent="loadOrders(1)"><label class="platform-search">搜索<input v-model="orderFilter.q" placeholder="订单号、买家或商户" maxlength="100"></label><label>商户 ID<input v-model.number="orderFilter.merchantId" type="number" min="1" placeholder="全部商户"></label><label>订单状态<select v-model="orderFilter.orderStatus"><option value="">全部</option><option value="pending">待处理</option><option value="packed">已打包</option><option value="shipped">运输中</option><option value="received">已收货</option><option value="completed">已完成</option><option value="cancelled">已取消</option></select></label><label>付款状态<select v-model="orderFilter.paymentStatus"><option value="">全部</option><option value="unpaid">待付款</option><option value="pending_verification">待核款</option><option value="partially_paid">部分付款</option><option value="paid">已付清</option></select></label><label>审核状态<select v-model="orderFilter.auditStatus"><option value="">全部</option><option value="pending">待审核</option><option value="approved">已通过</option><option value="rejected">未通过</option><option value="withdrawn">已撤回</option></select></label><label>从<input v-model="orderRange.from" type="date"></label><label>至<input v-model="orderRange.to" type="date"></label><button class="btn platform-primary-button" :disabled="sectionLoading">查询</button><button type="button" class="btn" :disabled="exportBusy" @click="exportRows('orders')">{{ exportBusy ? '导出中…' : '导出 CSV' }}</button></form>
            <div class="platform-table-toolbar"><span>共 {{ number(orderResult.total) }} 笔订单</span><button class="btn" :disabled="sectionLoading" @click="loadOrders()">刷新</button></div>
            <div class="platform-table-wrap" :aria-busy="sectionLoading"><table class="platform-table"><thead><tr><th>订单号</th><th>商户</th><th>买家</th><th>状态</th><th>付款</th><th>审核</th><th>订单金额</th><th>已收</th><th>下单时间</th><th></th></tr></thead><tbody>
              <tr v-for="row in orderResult.items" :key="`${row.merchantId}-${row.id}`"><td><strong>{{ row.orderNo }}</strong></td><td>{{ row.merchantName }}<small>{{ row.merchantCode }}</small></td><td>{{ row.buyerName }}<small>{{ row.email }}</small></td><td><span :class="['platform-status', statusClass(row.orderStatus)]">{{ orderStatusName(row.orderStatus) }}</span></td><td>{{ paymentStatusName(row.paymentStatus) }}</td><td>{{ auditStatusName(row.auditStatus) }}</td><td>{{ money(row.totalAmount) }}</td><td>{{ money(row.paidAmount) }}</td><td>{{ dateTime(row.createdAt) }}</td><td><button class="platform-link" @click="openAccess({ kind: 'order', merchantId: row.merchantId, id: row.id })">详情</button></td></tr>
              <tr v-if="!sectionLoading && !orderResult.items.length"><td colspan="10" class="platform-empty">没有符合条件的订单。调整筛选后再试。</td></tr>
            </tbody></table><p v-if="sectionLoading" class="platform-loading">正在读取订单…</p></div>
            <div class="platform-pagination"><span>共 {{ orderResult.total }} 条 · 第 {{ orderResult.page }} / {{ orderResult.totalPages }} 页</span><div><button :disabled="sectionLoading || orderResult.page <= 1" @click="changePage('orders', orderResult.page - 1)">上一页</button><button v-for="numberPage in pageButtons(orderResult)" :key="numberPage" :class="{ active: orderResult.page === numberPage }" :disabled="sectionLoading" @click="changePage('orders', numberPage)">{{ numberPage }}</button><button :disabled="sectionLoading || orderResult.page >= orderResult.totalPages" @click="changePage('orders', orderResult.page + 1)">下一页</button></div></div>
          </section>

          <section v-else-if="activeSection === 'buyers'" class="platform-panel">
            <form class="platform-filter-bar" @submit.prevent="loadBuyers(1)"><label class="platform-search">搜索<input v-model="buyerFilter.q" placeholder="邮箱、客户编号、姓名或商户" maxlength="100"></label><label>商户 ID<input v-model.number="buyerFilter.merchantId" type="number" min="1" placeholder="全部商户"></label><label>账号状态<select v-model="buyerFilter.active"><option :value="null">全部</option><option :value="true">启用</option><option :value="false">停用</option></select></label><label>注册起始<input v-model="buyerRange.from" type="date"></label><label>注册结束<input v-model="buyerRange.to" type="date"></label><button class="btn platform-primary-button" :disabled="sectionLoading">查询</button><button type="button" class="btn" :disabled="exportBusy" @click="exportRows('buyers')">{{ exportBusy ? '导出中…' : '导出 CSV' }}</button></form>
            <div class="platform-table-toolbar"><span>共 {{ number(buyerResult.total) }} 位买家</span><button class="btn" :disabled="sectionLoading" @click="loadBuyers()">刷新</button></div>
            <div class="platform-table-wrap" :aria-busy="sectionLoading"><table class="platform-table"><thead><tr><th>买家</th><th>商户</th><th>编号</th><th>账号</th><th>客户类型 / 等级</th><th>订单数</th><th>累计订单金额</th><th>最后登录</th><th></th></tr></thead><tbody>
              <tr v-for="row in buyerResult.items" :key="`${row.merchantId}-${row.id}`"><td><strong>{{ row.customerName }}</strong><small>{{ row.email }}</small></td><td>{{ row.merchantName }}<small>{{ row.merchantCode }}</small></td><td>{{ row.customerCode }}</td><td><span :class="['platform-status', row.active ? 'platform-status-active' : 'platform-status-inactive']">{{ row.active ? '启用' : '停用' }}</span></td><td>{{ row.customerType }} / {{ row.priceTier }}</td><td>{{ number(row.orderCount) }}<small v-if="row.openPaymentCount">{{ row.openPaymentCount }} 笔待收款</small></td><td>{{ money(row.orderAmount) }}</td><td>{{ dateTime(row.lastLoginAt) }}</td><td><button class="platform-link" @click="openAccess({ kind: 'buyer', merchantId: row.merchantId, id: row.id })">详情</button></td></tr>
              <tr v-if="!sectionLoading && !buyerResult.items.length"><td colspan="9" class="platform-empty">没有符合条件的买家。调整筛选后再试。</td></tr>
            </tbody></table><p v-if="sectionLoading" class="platform-loading">正在读取买家…</p></div>
            <div class="platform-pagination"><span>共 {{ buyerResult.total }} 条 · 第 {{ buyerResult.page }} / {{ buyerResult.totalPages }} 页</span><div><button :disabled="sectionLoading || buyerResult.page <= 1" @click="changePage('buyers', buyerResult.page - 1)">上一页</button><button v-for="numberPage in pageButtons(buyerResult)" :key="numberPage" :class="{ active: buyerResult.page === numberPage }" :disabled="sectionLoading" @click="changePage('buyers', numberPage)">{{ numberPage }}</button><button :disabled="sectionLoading || buyerResult.page >= buyerResult.totalPages" @click="changePage('buyers', buyerResult.page + 1)">下一页</button></div></div>
          </section>

          <section v-else-if="activeSection === 'performance'" class="platform-panel">
            <form class="platform-filter-bar" @submit.prevent="loadPerformance(1)"><label>起始日期<input v-model="performanceRange.from" type="date"></label><label>结束日期<input v-model="performanceRange.to" type="date"></label><span class="platform-filter-note">默认统计最近 30 天</span><button class="btn platform-primary-button" :disabled="sectionLoading">查询</button><button type="button" class="btn" :disabled="exportBusy" @click="exportRows('merchants')">{{ exportBusy ? '导出中…' : '导出报表' }}</button></form>
            <div class="platform-table-toolbar"><span>按区间订单金额排序</span><button class="btn" :disabled="sectionLoading" @click="loadPerformance()">刷新</button></div>
            <div class="platform-table-wrap" :aria-busy="sectionLoading"><table class="platform-table"><thead><tr><th>商户</th><th>状态</th><th>区间订单</th><th>订单金额</th><th>已收金额</th><th>待履约</th><th>买家总数</th><th>新增买家</th><th>最近订单</th><th>查询</th></tr></thead><tbody>
              <tr v-for="row in performanceResult.items" :key="row.merchantId"><td><strong>{{ row.merchantName }}</strong><small>{{ row.merchantCode }} · {{ row.domain }}</small></td><td><span :class="['platform-status', row.active ? 'platform-status-active' : 'platform-status-inactive']">{{ row.active ? '启用' : '停用' }}</span></td><td>{{ number(row.orderCount) }}</td><td>{{ money(row.grossAmount) }}</td><td>{{ money(row.receivedAmount) }}</td><td>{{ number(row.openOrderCount) }}</td><td>{{ number(row.buyerCount) }}</td><td>{{ number(row.newBuyerCount) }}</td><td>{{ dateTime(row.lastOrderAt) }}</td><td><button class="platform-link" @click="openMerchantOrders(row.merchantId)">订单</button><button class="platform-link" @click="openMerchantBuyers(row.merchantId)">买家</button></td></tr>
              <tr v-if="!sectionLoading && !performanceResult.items.length"><td colspan="10" class="platform-empty">暂无商户经营数据。</td></tr>
            </tbody></table><p v-if="sectionLoading" class="platform-loading">正在汇总商户数据…</p></div>
            <div class="platform-pagination"><span>共 {{ performanceResult.total }} 家商户 · 第 {{ performanceResult.page }} / {{ performanceResult.totalPages }} 页</span><div><button :disabled="sectionLoading || performanceResult.page <= 1" @click="changePage('performance', performanceResult.page - 1)">上一页</button><button v-for="numberPage in pageButtons(performanceResult)" :key="numberPage" :class="{ active: performanceResult.page === numberPage }" :disabled="sectionLoading" @click="changePage('performance', numberPage)">{{ numberPage }}</button><button :disabled="sectionLoading || performanceResult.page >= performanceResult.totalPages" @click="changePage('performance', performanceResult.page + 1)">下一页</button></div></div>
          </section>

          <MerchantManagement v-else-if="activeSection === 'merchants'" />

          <section v-else-if="activeSection === 'audit'" class="platform-panel">
            <div class="platform-table-toolbar"><span>平台数据访问和商户配置操作</span><button class="btn" :disabled="sectionLoading" @click="loadAudit()">刷新</button></div>
            <div class="platform-table-wrap" :aria-busy="sectionLoading"><table class="platform-table"><thead><tr><th>时间</th><th>操作人</th><th>商户</th><th>类型</th><th>操作</th><th>访问用途 / 变更说明</th></tr></thead><tbody>
              <tr v-for="row in auditResult.items" :key="`${row.source}-${row.auditId}`"><td>{{ dateTime(row.createdAt) }}</td><td>{{ row.actorName }}</td><td>{{ row.merchantName || `商户 #${row.merchantId}` }}</td><td>{{ row.source === 'data_access' ? '数据访问' : '商户配置' }}</td><td>{{ auditActionName(row.action) }}</td><td class="platform-audit-purpose">{{ row.purpose }}</td></tr>
              <tr v-if="!sectionLoading && !auditResult.items.length"><td colspan="6" class="platform-empty">还没有平台审计记录。</td></tr>
            </tbody></table><p v-if="sectionLoading" class="platform-loading">正在读取审计记录…</p></div>
            <div class="platform-pagination"><span>共 {{ auditResult.total }} 条 · 第 {{ auditResult.page }} / {{ auditResult.totalPages }} 页</span><div><button :disabled="sectionLoading || auditResult.page <= 1" @click="changePage('audit', auditResult.page - 1)">上一页</button><button v-for="numberPage in pageButtons(auditResult)" :key="numberPage" :class="{ active: auditResult.page === numberPage }" :disabled="sectionLoading" @click="changePage('audit', numberPage)">{{ numberPage }}</button><button :disabled="sectionLoading || auditResult.page >= auditResult.totalPages" @click="changePage('audit', auditResult.page + 1)">下一页</button></div></div>
          </section>
        </section>
      </div>
    </template>

    <AppModal v-if="accessOpen" label="记录访问用途" content-class="platform-purpose-modal" @close="closeAccess">
      <form @submit.prevent="confirmAccess"><h2 class="modal-title">记录访问用途</h2><p class="platform-modal-copy">平台访问订单或买家详情会留存操作人、记录和用途。</p><label class="form-group">访问用途<textarea v-model="accessPurpose" required minlength="5" maxlength="500" rows="4" placeholder="例如：协助商户排查订单付款状态"></textarea></label><p v-if="detailError" class="field-error" role="alert">{{ detailError }}</p><div class="modal-actions"><button type="button" class="modal-cancel" :disabled="accessLoading" @click="closeAccess">取消</button><button class="modal-save" :disabled="accessLoading || accessPurpose.trim().length < 5">{{ accessLoading ? '读取中…' : '继续查看' }}</button></div></form>
    </AppModal>

    <AppModal v-if="detailOpen && orderDetail" :label="`订单 ${orderDetail.orderNo}`" content-class="platform-detail-modal" @close="closeDetail">
      <header class="platform-detail-heading"><div><p class="platform-kicker">{{ orderDetail.merchantName }} · {{ orderDetail.merchantCode }}</p><h2>{{ orderDetail.orderNo }}</h2></div><button class="close-button" aria-label="关闭" @click="closeDetail">×</button></header>
      <div class="platform-detail-status"><span :class="['platform-status', statusClass(orderDetail.orderStatus)]">{{ orderStatusName(orderDetail.orderStatus) }}</span><span :class="['platform-status', statusClass(orderDetail.paymentStatus)]">{{ paymentStatusName(orderDetail.paymentStatus) }}</span><span :class="['platform-status', statusClass(orderDetail.auditStatus)]">{{ auditStatusName(orderDetail.auditStatus) }}</span></div>
      <div class="platform-detail-grid"><div><span>买家</span><strong>{{ orderDetail.buyerName }}</strong></div><div><span>联系邮箱</span><strong>{{ orderDetail.email }}</strong></div><div><span>订单金额</span><strong>{{ money(orderDetail.totalAmount) }}</strong></div><div><span>累计已收</span><strong>{{ money(orderDetail.paidAmount) }}</strong></div><div><span>下单时间</span><strong>{{ dateTime(orderDetail.createdAt) }}</strong></div><div><span>收货城市</span><strong>{{ orderDetail.city || '—' }}</strong></div></div>
      <section class="platform-detail-section"><h3>商品</h3><div v-for="(item, index) in orderDetail.items || []" :key="`${item.sku || item.skuSnapshot}-${index}`" class="platform-detail-line"><span>{{ item.name }}<small>{{ item.sku || item.skuSnapshot }} · ×{{ item.quantity }}</small></span><strong>{{ money(item.lineAmount) }}</strong></div><p v-if="!orderDetail.items?.length" class="platform-empty">没有商品行记录。</p></section>
      <section class="platform-detail-section"><h3>付款记录</h3><div v-for="(item, index) in orderDetail.payments || []" :key="`${item.kind}-${index}`" class="platform-detail-line"><span>{{ item.kind === 'receipt' ? '已确认收款' : '买家付款申报' }}<small>{{ item.transactionNo || '无交易号' }} · {{ dateTime(item.occurredAt) }}</small></span><strong>{{ money(item.amount) }}</strong></div><p v-if="!orderDetail.payments?.length" class="platform-empty">暂无付款记录。</p></section>
      <section class="platform-detail-section"><h3>订单记录</h3><div v-for="(item, index) in orderDetail.events || []" :key="`${item.action}-${index}`" class="platform-event-line"><span>{{ item.actorName }} · {{ item.action }}</span><small>{{ dateTime(item.occurredAt) }}<template v-if="item.note"> · {{ item.note }}</template></small></div></section>
      <section class="platform-contact-reveal"><h3>买家联系方式</h3><p v-if="!orderContact">列表和订单详情已遮蔽联系方式。填写用途后可查看订单中的收件资料。</p><div v-else class="platform-contact-values"><strong>{{ orderContact.customerName }} · {{ orderContact.email }}</strong><span>{{ orderContact.recipientName }}，{{ orderContact.address.street }} {{ orderContact.address.apt || '' }}，{{ orderContact.city }} {{ orderContact.address.region || '' }} {{ orderContact.address.postalCode || '' }}</span><span>电话：{{ orderContact.address.phone || '未提供' }}</span></div><form v-if="!orderContact" @submit.prevent="revealContact"><label>访问用途<input v-model="contactPurpose" required minlength="5" maxlength="500" placeholder="说明查看完整联系方式的原因"></label><button class="btn" :disabled="contactLoading || contactPurpose.trim().length < 5">{{ contactLoading ? '正在记录…' : '查看完整联系方式' }}</button></form></section>
      <p v-if="detailError" class="field-error" role="alert">{{ detailError }}</p>
    </AppModal>

    <AppModal v-if="detailOpen && buyerDetail" :label="`买家 ${buyerDetail.customerCode}`" content-class="platform-detail-modal" @close="closeDetail">
      <header class="platform-detail-heading"><div><p class="platform-kicker">{{ buyerDetail.merchantName }} · {{ buyerDetail.merchantCode }}</p><h2>买家 {{ buyerDetail.customerCode }}</h2></div><button class="close-button" aria-label="关闭" @click="closeDetail">×</button></header>
      <div class="platform-detail-status"><span :class="['platform-status', buyerDetail.active ? 'platform-status-active' : 'platform-status-inactive']">{{ buyerDetail.active ? '账号启用' : '账号停用' }}</span><span class="platform-status">{{ buyerDetail.customerType }} / {{ buyerDetail.priceTier }}</span></div>
      <div class="platform-detail-grid"><div><span>买家</span><strong>{{ buyerDetail.customerName }}</strong></div><div><span>邮箱</span><strong>{{ buyerDetail.email }}</strong></div><div><span>累计订单</span><strong>{{ number(buyerDetail.orderCount) }} 笔</strong></div><div><span>累计订单金额</span><strong>{{ money(buyerDetail.orderAmount) }}</strong></div><div><span>注册时间</span><strong>{{ dateTime(buyerDetail.createdAt) }}</strong></div><div><span>最后登录</span><strong>{{ dateTime(buyerDetail.lastLoginAt) }}</strong></div></div>
      <section class="platform-detail-section"><h3>最近订单</h3><div v-for="item in buyerDetail.recentOrders || []" :key="item.orderNo" class="platform-detail-line"><span>{{ item.orderNo }}<small>{{ orderStatusName(item.orderStatus) }} · {{ paymentStatusName(item.paymentStatus) }} · {{ dateTime(item.createdAt) }}</small></span><strong>{{ money(item.totalAmount) }}</strong></div><p v-if="!buyerDetail.recentOrders?.length" class="platform-empty">该买家还没有订单。</p></section>
      <section class="platform-contact-reveal"><h3>买家联系资料</h3><p v-if="!buyerContact">完整姓名和邮箱已遮蔽。填写用途后可查看账号联系资料。</p><div v-else class="platform-contact-values"><strong>{{ buyerContact.customerName }} · {{ buyerContact.email }}</strong><span>邮箱验证：{{ buyerContact.emailVerifiedAt ? dateTime(buyerContact.emailVerifiedAt) : '未验证' }}</span></div><form v-if="!buyerContact" @submit.prevent="revealContact"><label>访问用途<input v-model="contactPurpose" required minlength="5" maxlength="500" placeholder="说明查看买家联系资料的原因"></label><button class="btn" :disabled="contactLoading || contactPurpose.trim().length < 5">{{ contactLoading ? '正在记录…' : '查看完整资料' }}</button></form></section>
      <p v-if="detailError" class="field-error" role="alert">{{ detailError }}</p>
    </AppModal>
  </main>
</template>
