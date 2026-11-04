<script setup lang="ts">
import { computed, nextTick, onMounted, provide, reactive, ref, watch } from 'vue'
import { ApiRequestError, api, orderPaymentApi, orderParcelApi, type AdminCustomer, type PaymentOrder, auditOrderApi, clearToken, download, getToken, request, setToken, pinAdminToken, type Area, type AuditEditableOrder } from './lib/api'
import { chineseCustomerText } from './lib/customer-locale'
import { sellerTranslationKey, translateSeller } from './lib/seller-locale'
import AdminSidebar from './components/AdminSidebar.vue'
import type { AdminMember, MerchantIdentity } from './lib/api'
import SellerLanguageSwitch from './components/SellerLanguageSwitch.vue'
import SellerAccountSwitcher from './components/SellerAccountSwitcher.vue'
import { rememberSellerSession, type SellerIdentity } from './lib/seller-sessions'
import SellerSearchField from './components/SellerSearchField.vue'
import SellerFilePicker from './components/SellerFilePicker.vue'
import AppModal from './components/AppModal.vue'
import { applyMerchantBranding, storefrontBrand } from './lib/storefront'
import CustomerHeader from './components/CustomerHeader.vue'
import CustomerDatePicker from './components/CustomerDatePicker.vue'
import CustomerNotice from './components/CustomerNotice.vue'
import LoginScreen from './components/LoginScreen.vue'
import CustomerCertificatesPage from './components/CustomerCertificatesPage.vue'
import CustomerAccountPage from './components/CustomerAccountPage.vue'
import CustomerCheckoutPage from './components/CustomerCheckoutPage.vue'
import CustomerOrderHistoryPage from './components/CustomerOrderHistoryPage.vue'
import CustomerOrderEditModal from './components/CustomerOrderEditModal.vue'
import SellerOrderEditModal from './components/SellerOrderEditModal.vue'
import SellerParcelManager from './components/SellerParcelManager.vue'
import OrderParcelList from './components/OrderParcelList.vue'
import type { FinanceOrderStatus, FulfillmentSummary, OrderParcel, ParcelOrder } from './lib/api'
import CustomerPaymentModal from './components/CustomerPaymentModal.vue'
import OrderPaymentRecords from './components/OrderPaymentRecords.vue'
import { parseAmountCents, validPaymentAmount } from './lib/payment-amount'
import { formatOrderCopyRows } from './lib/order-copy-format'
import { fulfillmentStageState, partialFulfillmentStage, type FulfillmentStageKey, type FulfillmentStageState } from './lib/fulfillment-progress'
import CustomerProductCatalog from './components/CustomerProductCatalog.vue'
import SellerQuantityPriceEditor from './components/SellerQuantityPriceEditor.vue'
import { quantityPriceError, type TierQuantityPrices, type PriceTier } from './lib/quantity-pricing'
import type { CustomerAddress, CustomerCart, CustomerCartItem, CustomerCategory, CustomerCertificate, CustomerOrder, CustomerOrderItem, CustomerOrderQuote, CustomerOrderSummary, CustomerPaymentChannel, CustomerPaymentOptions, CustomerProduct, CustomerProfile, CustomerReferral, CustomerReferralSummary } from './types/customer'

type AnyRecord = Record<string, any>
type AdminOrderListItem = CustomerOrder & { customer?: { name?: string; email?: string } }
type FinanceListItem = AnyRecord & FinanceOrderStatus
type Page<T = AnyRecord> = { items: T[]; total: number; page: number; totalPages: number }
type ExportType = 'orders' | 'commissions' | 'bills'
type ExportPreviewResponse = { recordCount: number; rowCount: number; maxRows: number }
type ExportConfirmation = { type: ExportType; filter: Record<string, string>; preview: ExportPreviewResponse | null }
type CustomerOrderFilterSummary = { orderCount: number; pendingPaymentOrders: number; totalAmount: number | string; pendingPaymentAmount: number | string }
type BillingSummary = { orderBills: number; pendingOrders: number; orderAmount: number | string; receivedAmount: number | string; pendingReceivableAmount: number | string; commissionBills: number; payableCommissionBills: number; commissionAmount: number | string; settledCommissionAmount: number | string; payableCommissionAmount: number | string }
type RequestCache = { loaded: boolean; generation: number; pending: Promise<void> | null }

function createRequestCache(): RequestCache {
  return { loaded: false, generation: 0, pending: null }
}

function invalidateRequestCache(cache: RequestCache) {
  cache.loaded = false
  cache.generation += 1
  cache.pending = null
}

function loadCached<T>(cache: RequestCache, load: () => Promise<T>, apply: (value: T) => void): Promise<void> {
  if (cache.loaded) return Promise.resolve()
  if (cache.pending) return cache.pending
  const generation = cache.generation
  let pending: Promise<void>
  pending = Promise.resolve()
    .then(load)
    .then(value => {
      if (cache.generation === generation) {
        apply(value)
        cache.loaded = true
      }
    })
    .finally(() => {
      if (cache.pending === pending) cache.pending = null
    })
  cache.pending = pending
  return pending
}

pinAdminToken()

const area = ref<Area>(location.pathname.startsWith('/admin') ? 'admin' : 'customer')
const loggedIn = ref(Boolean(getToken(area.value)))
const loginMode = ref<Area>(area.value)
const loginAccount = ref('')
const loginPassword = ref('')
const loginError = ref('')
const busy = ref(false)
const signingOut = ref(false)
const pendingActions = reactive(new Set<string>())
const notice = ref('')
const noticeKind = ref<'success' | 'error'>('success')
const noticeHint = ref('')
let noticeSequence = 0
let noticeTimer: number | undefined

const customerPage = ref('products')
watch(customerPage, async () => {
  await nextTick()
  window.scrollTo(0, 0)
})
const customer = ref<CustomerProfile>({})
const categories = ref<CustomerCategory[]>([])
const products = ref<CustomerProduct[]>([])
const productSearch = ref('')
const activeCategory = ref<number | null>(null)
const visibleProducts = computed(() => {
  const query = productSearch.value.trim().toLocaleLowerCase()
  return products.value.filter(product => {
    if (activeCategory.value !== null && product.categoryId !== activeCategory.value) return false
    if (!query) return true
    return [product.name, product.sku, product.description || '']
      .some(value => value.toLocaleLowerCase().includes(query))
  })
})
const cart = ref<CustomerCart>({ items: [], subtotal: '0.00', shippingFee: '0.00', total: '0.00' })
const pendingCartProductIds = reactive(new Set<number>())
const catalogCartQuantityOverrides = reactive<Record<number, number | null>>({})
let cartMutationQueue: Promise<void> = Promise.resolve()
const addresses = ref<CustomerAddress[]>([])
const customerOrderSummary = ref<CustomerOrderSummary>({})
const customerOrderSummaryCache = createRequestCache()
const customerReferralSummaryCache = createRequestCache()
const addressFormOpen = ref(false)
const addressFormReturnToCheckout = ref(false)
const addressPickerOpen = ref(false)
const addressEditingId = ref<number | null>(null)
const addressDraft = reactive({ name: '', street: '', apt: '', city: '', region: '', postalCode: '', phone: '', isDefault: false })
type AddressField = 'name' | 'street' | 'city' | 'postalCode' | 'phone'
const addressFields: AddressField[] = ['name', 'street', 'city', 'postalCode', 'phone']
const addressErrors = reactive<Record<AddressField, string>>({ name: '', street: '', city: '', postalCode: '', phone: '' })
const addressTouched = reactive<Record<AddressField, boolean>>({ name: false, street: false, city: false, postalCode: false, phone: false })
const accountTab = ref('profile')
const checkoutStep = ref(0)
const checkoutQuote = ref<CustomerOrderQuote | null>(null)
const selectedAddressId = ref<number | null>(null)
const orderNote = ref('')
const paymentSuccessOpen = ref(false)
const customerPaymentOpen = ref(false)
const cryptoEnabled = ref(false)
const onlinePaymentLinkEnabled = ref(false)
const paymentMethod = ref<'crypto' | 'link'>('crypto')
const orders = ref<Page<CustomerOrder>>({ items: [], total: 0, page: 1, totalPages: 1 })
const selectedOrder = ref<AnyRecord>({}) as any
const orderEditOpen = ref(false)
const orderDetailOpen = ref(false)
const orderEditAddressId = ref<number | null>(null)
const orderEditItems = ref<Array<CustomerOrderItem & { productType?: string }>>([])
const orderDateFilter = ref('all')
const orderDateFrom = ref('')
const orderDateTo = ref('')
const orderPage = ref(1)
const orderPaymentFilter = ref('all')
const orderShippingFilter = ref('all')
const orderSearch = ref('')
const channels = ref<CustomerPaymentChannel[]>([])
const paymentChannel = ref<number | null>(null)
const transactionNo = ref('')
const paymentAmount = ref('')
const selectedChannel = computed(() => channels.value.find(channel => channel.id === paymentChannel.value))
const profileName = ref('')
const certificates = ref<CustomerCertificate[]>([])
const certificateSearch = ref('')
const referralCustomers = ref<CustomerReferral[]>([])
const referralSummary = ref<CustomerReferralSummary>({})
const oldPassword = ref('')
const newPassword = ref('')
const passwordConfirmation = ref('')

const adminPage = ref('orders')
const adminMode = ref('')
const currentMerchant = ref<MerchantIdentity | null>(null)
const canManageCoa = computed(() => adminPermissions.value.includes('certificates'))
const adminMember = ref<AnyRecord>({})
const adminPermissions = ref<string[]>([])
const dashboard = ref<AnyRecord>({})
const adminOrders = ref<Page<AdminOrderListItem>>({ items: [], total: 0, page: 1, totalPages: 1 })
const adminOrderPage = ref(1)
const auditOrders = ref<Page>({ items: [], total: 0, page: 1, totalPages: 1 })
const auditPage = ref(1)
const auditResultFilter = ref<'pending' | 'rejected' | 'approved'>('pending')
const auditSearch = ref('')
const auditOrder = ref<AnyRecord | null>(null)
const auditReviewMode = ref(false)
const auditRemark = ref('')
const auditSubmitting = ref(false)
const auditEditTarget = ref<{ id: number; orderNo: string } | null>(null)
const adminOrderSummary = ref<AnyRecord>({})
const adminOrderSummaryCache = createRequestCache()
const adminProducts = ref<Page>({ items: [], total: 0, page: 1, totalPages: 1 })
const adminProductPage = ref(1)
const adminCustomers = ref<Page<AdminCustomer>>({ items: [], total: 0, page: 1, totalPages: 1 })
const adminCustomerPage = ref(1)
let adminCustomerSearchTimer: number | undefined
const commissions = ref<Page<FinanceListItem>>({ items: [], total: 0, page: 1, totalPages: 1 })
const commissionSummary = ref<AnyRecord>({ count: 0, estimated: 0, payable: 0, settled: 0, commissionAmount: 0, settledAmount: 0 })
const commissionSummaryCache = createRequestCache()
const adminCommissionPage = ref(1)
const adminCommissionStatus = ref<'all' | 'payable' | 'settled'>('all')
const commissionSelectionMode = ref(false)
const commissionSelectedRows = ref<AnyRecord[]>([])
const commissionSettlementOpen = ref(false)
const commissionSettlementRows = ref<AnyRecord[]>([])
const commissionSettlementRemark = ref('')
const commissionSettlementSubmitting = ref(false)
const exportConfirmation = ref<ExportConfirmation | null>(null)
const exportPreviewLoading = ref(false)
const exportSubmitting = ref(false)
let exportPreviewSequence = 0
const commissionSettlementTotal = computed(() => commissionSettlementRows.value.reduce((cents, item) => cents + Math.round(Number(item.commissionAmount || 0) * 100), 0) / 100)
const commissionSettlementBaseTotal = computed(() => commissionSettlementRows.value.reduce((cents, item) => cents + Math.round(Number(item.baseAmount || item.orderAmount || item.amount || 0) * 100), 0) / 100)
const commissionStatusFilters = [
  { value: 'all', label: '全部' },
  { value: 'payable', label: '待支付' },
  { value: 'settled', label: '已支付' },
] as const
const bills = ref<Page<FinanceListItem>>({ items: [], total: 0, page: 1, totalPages: 1 })
const billSummary = ref<BillingSummary>({ orderBills: 0, pendingOrders: 0, orderAmount: 0, receivedAmount: 0, pendingReceivableAmount: 0, commissionBills: 0, payableCommissionBills: 0, commissionAmount: 0, settledCommissionAmount: 0, payableCommissionAmount: 0 })
const billSummaryCache = createRequestCache()
const adminBillPage = ref(1)
const adminBillTypeFilter = ref('all')
const adminBillStatusFilter = ref('all')
const adminBillDateFilter = ref('all')
const adminBillDateFrom = ref('')
const adminBillDateTo = ref('')
const adminChannels = ref<Page>({ items: [], total: 0, page: 1, totalPages: 1 })
const adminPaymentQrUrls = reactive<Record<number, { fileUuid: string; url: string }>>({})
const paymentQrPreview = ref<{ url: string; name: string } | null>(null)
let paymentQrRequestSequence = 0
watch(() => adminChannels.value.items, items => {
  const sequence = ++paymentQrRequestSequence
  const channelIds = new Set(items.map(item => Number(item.id)))
  Object.keys(adminPaymentQrUrls).forEach(id => {
    if (!channelIds.has(Number(id))) delete adminPaymentQrUrls[Number(id)]
  })
  items.forEach(item => {
    const channelId = Number(item.id)
    const fileUuid = String(item.qrFileUuid || '')
    if (!fileUuid) {
      delete adminPaymentQrUrls[channelId]
      return
    }
    void request<{ url: string }>(`/admin/files/${encodeURIComponent(fileUuid)}/signed-url`, {}, 'admin')
      .then(result => {
        if (sequence === paymentQrRequestSequence && adminChannels.value.items.some(channel => Number(channel.id) === channelId && channel.qrFileUuid === fileUuid)) {
          adminPaymentQrUrls[channelId] = { fileUuid, url: result.url }
        }
      })
      .catch(() => {
        if (sequence === paymentQrRequestSequence) delete adminPaymentQrUrls[channelId]
      })
  })
})
const adminCertificates = ref<Page>({ items: [], total: 0, page: 1, totalPages: 1 })
const adminCategories = ref<AnyRecord[]>([])
const adminCategoriesCache = createRequestCache()
const categoryProductsOpen = ref(false)
const categoryProductsCategory = ref<AnyRecord | null>(null)
const categoryProducts = ref<Page>({ items: [], total: 0, page: 1, totalPages: 1 })
const categoryProductsPage = ref(1)
const categoryProductsLoading = ref(false)
const categoryProductsError = ref('')
let categoryProductsRequestSequence = 0
const adminMembers = ref<Page<AdminMember>>({ items: [], total: 0, page: 1, totalPages: 1 })
const adminMemberSearch = ref('')
const adminMemberQuery = ref('')
const adminMembersLoading = ref(false)
function invalidateCustomerOrderSummary() { invalidateRequestCache(customerOrderSummaryCache) }
function invalidateCustomerReferralSummary() { invalidateRequestCache(customerReferralSummaryCache) }
function invalidateAdminOrderSummaries() {
  invalidateRequestCache(adminOrderSummaryCache)
  invalidateRequestCache(commissionSummaryCache)
  invalidateRequestCache(billSummaryCache)
}
function invalidateAdminOrderSummary() { invalidateRequestCache(adminOrderSummaryCache) }
function invalidateAdminCategories() { invalidateRequestCache(adminCategoriesCache) }
function resetRequestCaches() {
  invalidateCustomerOrderSummary()
  invalidateCustomerReferralSummary()
  invalidateAdminOrderSummaries()
  invalidateAdminCategories()
}
const categoryDraft = reactive({ name: '', sortNo: 10, active: true, expectedUpdatedAt: null as string | null })
const memberDraft = reactive({ account: '', name: '', password: '', confirmation: '', permissions: ['orders'] as string[] })
const memberTouched = reactive({ account: false, name: false, password: false, confirmation: false, permissions: false })
const memberFormSubmitted = ref(false)
const memberPermissionOptions = [
  { code: 'order_audit', name: '订单审核', description: '审核客户新提交的订单', symbol: '✓' },
  { code: 'orders', name: '订单管理', description: '查看订单并处理收款与履约', symbol: '▤' },
  { code: 'commissions', name: '佣金管理', description: '查看佣金记录并处理结算', symbol: '∿' },
  { code: 'bills', name: '账单管理', description: '查看账单及关联订单', symbol: '▧' },
  { code: 'customers', name: '客户管理', description: '管理客户资料与采购记录', symbol: '◎' },
  { code: 'products', name: '商品管理', description: '维护商品、价格与库存', symbol: '◇' },
  { code: 'certificates', name: '分析证书', description: '维护分析证书并关联商品', symbol: '⌁' },
  { code: 'categories', name: '商品类目', description: '维护商品类目结构', symbol: '⌘' },
  { code: 'payment_channels', name: '收款渠道', description: '配置收款渠道', symbol: '＋' },
  { code: 'members', name: '成员与权限', description: '管理后台成员及其菜单权限', symbol: '◌' },
]
const availableMemberPermissionOptions = computed(() => memberPermissionOptions)
const memberPermissionNames = Object.fromEntries(memberPermissionOptions.map(({ code, name }) => [code, name])) as Record<string, string>
const selectedMemberPermissions = computed(() => memberPermissionOptions.filter(permission => memberDraft.permissions.includes(permission.code)))
function memberPermissionName(code: string) { return memberPermissionNames[code] || code }
const memberFormErrors = computed(() => {
  const editing = Boolean(memberEditingId.value)
  const hasPassword = memberDraft.password.length > 0
  return {
    account: !memberDraft.account.trim() ? '请输入登录账号。' : memberDraft.account.trim().length > 64 ? '登录账号不能超过 64 个字符。' : '',
    name: !memberDraft.name.trim() ? '请输入成员姓名。' : memberDraft.name.trim().length > 100 ? '成员姓名不能超过 100 个字符。' : '',
    password: !editing && !hasPassword ? '新增成员必须设置密码。' : hasPassword && (memberDraft.password.length < 12 || memberDraft.password.length > 64) ? '密码须为 12～64 个字符。' : '',
    confirmation: !editing && !memberDraft.confirmation ? '请再次输入密码。' : !hasPassword && Boolean(memberDraft.confirmation) ? '请先填写新密码。' : hasPassword && !memberDraft.confirmation ? '请再次输入新密码。' : hasPassword && memberDraft.password !== memberDraft.confirmation ? '两次输入的密码不一致。' : '',
    permissions: memberDraft.permissions.length === 0 ? '至少选择一项菜单权限。' : '',
  }
})
const memberFormValid = computed(() => Object.values(memberFormErrors.value).every(error => !error))
function showMemberFieldError(field: keyof typeof memberTouched) {
  return memberFormSubmitted.value || memberTouched[field]
}
function resetMemberValidation() {
  Object.keys(memberTouched).forEach(field => { memberTouched[field as keyof typeof memberTouched] = false })
  memberFormSubmitted.value = false
}
const certificateDraft = reactive({ name: '', lot: '', purity: '', fill: '', testDate: '', productId: '', expectedUpdatedAt: null as string | null })
const certificateFile = ref<File | null>(null)
const certificateModalOpen = ref(false)
const certificateEditingId = ref<number | null>(null)
const certificateCurrentFileUuid = ref('')
const certificateProductSearch = ref('')
const certificateProductOptions = ref<AnyRecord[]>([])
const certificateProductOptionsOpen = ref(false)
const certificateProductOptionsLoading = ref(false)
const selectedCertificateProduct = ref<AnyRecord | null>(null)
let certificateProductSearchSequence = 0
const certificateProductDetailOpen = ref(false)
const certificateProductDetailLoading = ref(false)
const certificateProductDetail = ref<AnyRecord | null>(null)
const adminCertificatePage = ref(1)
const productQuantityPrices = ref<TierQuantityPrices[]>([])
const productSaving = ref(false)
const productFixedPrices = computed(() => Object.fromEntries((['V1', 'V2', 'V3', 'V4', 'V5', 'V6'] as const).map(tier => [tier, productDraft[`${tier.toLowerCase()}Price` as 'v1Price' | 'v2Price' | 'v3Price' | 'v4Price' | 'v5Price' | 'v6Price']] )) as Record<PriceTier, string | number>)
function setProductFixedPrice(tier: PriceTier, price: string) { productDraft[`${tier.toLowerCase()}Price` as 'v1Price' | 'v2Price' | 'v3Price' | 'v4Price' | 'v5Price' | 'v6Price'] = price }
const productModalOpen = ref(false)
const productEditingId = ref<number | null>(null)
const productUpdatedAt = ref<string | null>(null)
const productInitialStock = ref(0)
const productStockReason = ref('')
const productDraft = reactive({ categoryId: '', sku: '', name: '', description: '', productType: 'regular', active: true, basePrice: '', costPrice: '', stockAvailable: 0, v1Price: '', v2Price: '', v3Price: '', v4Price: '', v5Price: '', v6Price: '' })
const productSelectedCoas = ref<AnyRecord[]>([])
const productCoaQuery = ref('')
const productCoaOptions = ref<AnyRecord[]>([])
const productCoaOptionsOpen = ref(false)
const productCoaLoading = ref(false)
const adminProductSort = ref<{ by: string; direction: string }>({ by: 'id', direction: 'desc' })
const categoryModalOpen = ref(false)
const categoryEditingId = ref<number | null>(null)
const memberModalOpen = ref(false)
const memberEditingId = ref<number | null>(null)
const paymentModalOpen = ref(false)
const paymentEditingId = ref<number | null>(null)
const channelDraft = reactive<{ name: string; currencyCode: string; network: string; receivingAddress: string; instructions: string; additionalRate: number | null | ''; qrFileUuid: string; active: boolean; sortNo: number }>({ name: '', currencyCode: 'USDT', network: '', receivingAddress: '', instructions: '', additionalRate: null, qrFileUuid: '', active: true, sortNo: 10 })
const channelQrFile = ref<File | null>(null)
const customerLanguage = ref(localStorage.getItem('dailyactivity.language') === 'zh-CN' ? 'zh-CN' : 'en')
const sellerLanguage = ref(localStorage.getItem('dailyactivity.admin.language') === 'en' ? 'en' : 'zh-CN')
const language = computed({
  get: () => area.value === 'admin' ? sellerLanguage.value : customerLanguage.value,
  set: value => {
    if (area.value === 'admin') sellerLanguage.value = value === 'en' ? 'en' : 'zh-CN'
    else customerLanguage.value = value === 'zh-CN' ? 'zh-CN' : 'en'
  },
})
watch(language, value => { document.documentElement.lang = value }, { immediate: true })
const sellerPaymentText = (text: string, values?: readonly unknown[]) => translateSeller(text, sellerLanguage.value, values)
provide(sellerTranslationKey, sellerPaymentText)
const t = (text: string, values?: readonly unknown[]) => {
  if (area.value === 'admin') return sellerPaymentText(text, values)
  if (language.value !== 'zh-CN') return text
  if (text.endsWith(' added to cart')) return `${text.slice(0, -' added to cart'.length)}已加入购物车`
  return chineseCustomerText[text] || text
}

async function runPending(key: string, action: () => Promise<unknown>) {
  if (pendingActions.has(key)) return
  pendingActions.add(key)
  try { await action() } finally { pendingActions.delete(key) }
}
const userModalOpen = ref(false)
const userEditingId = ref<number | null>(null)
const userSaving = ref(false)
const userDraft = reactive({ email: '', name: '', password: '', confirmation: '', customerType: 'partner', priceTier: 'V1', referrerId: '', commissionRate: '0' as string | number })
const referrerDropdownOpen = ref(false)
const referrerSearch = ref('')
const referrerOptions = ref<AnyRecord[]>([])
const selectedReferrer = ref<AnyRecord | null>(null)
const referrerOptionsLoading = ref(false)
const referrerOptionsPage = ref(1)
const referrerOptionsTotalPages = ref(1)
const referrerSearchInput = ref<HTMLInputElement | null>(null)
let referrerSearchTimer: number | undefined
let referrerRequestSequence = 0
const customerFormSubmitted = ref(false)
const customerRequestError = ref('')
const customerServerErrors = reactive({ email: '', name: '', password: '', confirmation: '', customerType: '', priceTier: '', referrerId: '', commissionRate: '' })
const customerFormErrors = computed(() => {
  const editing = Boolean(userEditingId.value)
  const email = userDraft.email.trim()
  const hasPassword = userDraft.password.length > 0
  const referrerId = userDraft.referrerId.trim()
  const rate = String(userDraft.commissionRate ?? '').trim()
  return {
    email: !email || email.length > 128 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) ? '请输入有效邮箱，最多 128 个字符。' : '',
    name: !userDraft.name.trim() || userDraft.name.trim().length > 100 ? '姓名必填，最多 100 个字符。' : '',
    password: !editing && !hasPassword ? '新增客户必须设置初始密码。' : hasPassword && (!userDraft.password.trim() || userDraft.password.length < 12 || userDraft.password.length > 64) ? '密码须为 12～64 个字符，且不能全为空格。' : '',
    confirmation: (!editing || hasPassword) && !userDraft.confirmation ? '请再次输入密码。' : userDraft.confirmation && userDraft.password !== userDraft.confirmation ? '两次输入的密码不一致。' : '',
    customerType: !['partner', 'regular'].includes(userDraft.customerType) ? '请选择客户类型。' : '',
    priceTier: !['V1', 'V2', 'V3', 'V4', 'V5', 'V6'].includes(userDraft.priceTier) ? '请选择客户等级。' : '',
    referrerId: referrerId && (!/^\d+$/.test(referrerId) || Number(referrerId) < 1) ? '请选择有效的推荐人。' : '',
    commissionRate: referrerId && (!/^\d+$/.test(rate) || Number(rate) > 20) ? '请输入 0～20 的整数佣金率。' : '',
  }
})
const customerFormValid = computed(() => Object.values(customerFormErrors.value).every(error => !error))
type CustomerField = keyof typeof customerServerErrors
function customerFieldError(field: CustomerField) {
  return customerFormErrors.value[field] || customerServerErrors[field]
}
function clearCustomerServerError(field: CustomerField) {
  customerServerErrors[field] = ''
}
function clearCustomerServerErrors() {
  for (const field of Object.keys(customerServerErrors) as CustomerField[]) customerServerErrors[field] = ''
}
function applyCustomerServerError(error: unknown) {
  if (!(error instanceof ApiRequestError)) return false
  const fieldNames: Partial<Record<string, CustomerField>> = {
    email: 'email', name: 'name', password: 'password', confirmation: 'confirmation',
    customerType: 'customerType', priceTier: 'priceTier', referrerId: 'referrerId', commissionRate: 'commissionRate',
  }
  for (const [name, message] of Object.entries(error.fields)) {
    const field = fieldNames[name]
    if (field) customerServerErrors[field] = message
  }
  const fieldMessages: Partial<Record<string, [CustomerField, string]>> = {
    INVALID_EMAIL: ['email', '请输入有效邮箱，最多 128 个字符。'],
    CUSTOMER_EMAIL_EXISTS: ['email', '该邮箱已被其他客户使用，请更换邮箱。'],
    CUSTOMER_IDENTIFIER_EXISTS: ['email', '该邮箱已被其他客户使用，请更换邮箱。'],
    INVALID_CUSTOMER_NAME: ['name', '姓名必填，且不能超过 100 个字符。'],
    INVALID_CUSTOMER_TYPE: ['customerType', '请选择有效的客户类型。'],
    INVALID_PRICE_TIER: ['priceTier', '请选择有效的客户等级。'],
    INVALID_PASSWORD: ['password', '密码须为 12～64 个字符，且不能全为空格。'],
    PASSWORD_CONFIRMATION_MISMATCH: ['confirmation', '两次输入的密码不一致，请重新确认。'],
    INVALID_REFERRER: ['referrerId', '所选推荐人无效，请重新选择。'],
    SELF_REFERRAL: ['referrerId', '不能选择客户本人作为推荐人。'],
    REFERRER_INACTIVE: ['referrerId', '所选推荐人已停用，请重新选择。'],
    INVALID_COMMISSION_RATE: ['commissionRate', '请输入 0～20 的整数佣金率。'],
  }
  const match = fieldMessages[error.code]
  if (match) {
    const [field, message] = match
    customerServerErrors[field] = message
  }
  const hasFieldError = Boolean(match) || Object.keys(error.fields).some(name => Boolean(fieldNames[name]))
  if (hasFieldError) {
    customerFormSubmitted.value = true
  }
  return hasFieldError
}
const adminDateFilter = ref('all')
const adminDateFrom = ref('')
const adminDateTo = ref('')
const adminPaymentFilter = ref('all')
const adminShippingFilter = ref('all')
const adminProductType = ref('all')
const adminProductStatus = ref('all')
const adminCategoryFilter = ref('all')
const adminUserType = ref('all')
const adminUserPayment = ref('all')
const adminSelectedIds = ref<number[]>([])
const parcelManagerOrderId = ref<number | null>(null)
const paymentConfirmOpen = ref(false)
const paymentConfirmOrder = ref<PaymentOrder | null>(null)
const paymentConfirmTransactionNo = ref('')
const paymentConfirmRemark = ref('')
const paymentConfirmAmount = ref('')
const paymentConfirmError = ref('')
const paymentConfirmStale = ref(false)
const paymentConfirmChannelId = ref<number | null>(null)
const paymentConfirmChannels = ref<Array<{ id: number; name: string; network: string }>>([])
const paymentConfirmSubmitting = ref(false)
type PaymentConfirmField = 'amount' | 'channel' | 'transaction' | 'remark'
const paymentConfirmTouched = reactive<Record<PaymentConfirmField, boolean>>({ amount: false, channel: false, transaction: false, remark: false })
const paymentConfirmSubmitted = ref(false)
const paymentConfirmServerErrors = reactive<Record<PaymentConfirmField, string>>({ amount: '', channel: '', transaction: '', remark: '' })
const paymentConfirmValidationErrors = computed<Record<PaymentConfirmField, string>>(() => {
  const errors = { amount: '', channel: '', transaction: '', remark: '' }
  const order = paymentConfirmOrder.value
  if (!order) return errors
  const amountText = paymentConfirmAmount.value.trim()
  const amount = parseAmountCents(amountText)
  const balance = parseAmountCents(order.remainingAmount)
  if (!amountText) errors.amount = '请输入本次收款金额。'
  else if (amount === null) errors.amount = '请输入有效金额，最多保留两位小数。'
  else if (amount <= 0n) errors.amount = '本次收款金额必须大于 0。'
  else if (balance === null || amount > balance) errors.amount = `本次收款金额不能超过剩余待收 ${money(order.remainingAmount)}。`
  const transactionNo = paymentConfirmTransactionNo.value.trim()
  if (!transactionNo) errors.transaction = '请输入核实交易号。'
  else if (transactionNo.length > 256) errors.transaction = '交易号最多 256 字。'
  if (!order.pendingPaymentSubmission && !paymentConfirmChannels.value.some(channel => channel.id === paymentConfirmChannelId.value)) errors.channel = '请选择有效的支付渠道。'
  const remark = paymentConfirmRemark.value.trim()
  if (remark.length > 1000) errors.remark = '内部备注／差异原因最多 1,000 字。'
  else if (order.pendingPaymentSubmission && amount !== null && amount > 0n && amount !== parseAmountCents(order.pendingPaymentSubmission.declaredAmount) && !remark) errors.remark = '核实金额与申报金额不同，请填写差异原因。'
  return errors
})
function paymentConfirmFieldError(field: PaymentConfirmField): string {
  return paymentConfirmServerErrors[field] || (paymentConfirmSubmitted.value || paymentConfirmTouched[field] ? paymentConfirmValidationErrors.value[field] : '')
}
watch(paymentConfirmAmount, () => { paymentConfirmServerErrors.amount = ''; paymentConfirmServerErrors.remark = '' })
watch(paymentConfirmChannelId, () => { paymentConfirmServerErrors.channel = '' })
watch(paymentConfirmTransactionNo, () => { paymentConfirmServerErrors.transaction = '' })
watch(paymentConfirmRemark, () => { paymentConfirmServerErrors.remark = '' })
const adminSearch = ref('')
const commissionSettlementDetail = ref<AnyRecord | null>(null)
const financeOrderDetail = ref<AnyRecord | null>(null)
const financeOrderSource = ref<AnyRecord | null>(null)
const orderCopyFeedback = ref('')
const orderCopyFeedbackKind = ref<'success' | 'error'>('success')
let orderCopyFeedbackTimer: number | undefined
const customerOrderDetailOpen = ref(false)
const adminOrderDetailOpen = ref(false)
const adminOrderDetailError = ref('')
const orderCompletionOpen = ref(false)
const orderCompletionTarget = ref<{ id: number; orderNo: string } | null>(null)
const orderCompletionOrder = ref<ParcelOrder | null>(null)
const orderCompletionLoading = ref(false)
let orderCompletionRequestSequence = 0
const orderCompletionConfirmed = ref(false)
const orderCompletionNote = ref('')
const orderCompletionSaving = ref(false)
const orderCompletionError = ref('')
const orderCompletionStale = ref(false)
const canCompleteAdminOrder = computed(() => orderCompletionOpen.value
  && adminPermissions.value.includes('orders')
  && !orderCompletionLoading.value && !orderCompletionStale.value
  && orderCompletionOrder.value?.allowedActions.includes('complete') === true)
const orderImagePreviewUrl = ref('')
const customerDetailKind = ref<'orders' | 'referrals' | null>(null)
const selectedAdminCustomer = ref<AnyRecord | null>(null)
const customerOrders = ref<Page>({ items: [], total: 0, page: 1, totalPages: 1 })
const customerOrderFilterSummary = ref<CustomerOrderFilterSummary>({ orderCount: 0, pendingPaymentOrders: 0, totalAmount: 0, pendingPaymentAmount: 0 })
const customerOrdersLoading = ref(false)
const customerOrderDateFilter = ref('all')
const customerOrderPaymentFilter = ref('all')
const customerOrderShippingFilter = ref('all')
const customerOrderSearch = ref('')
let customerOrderSearchTimer: number | undefined
let customerOrdersRequestSequence = 0
const adminCustomerOrderSummaryCache = new Map<string, CustomerOrderFilterSummary>()
const adminCustomerOrderSummaryRequests = new Map<string, Promise<CustomerOrderFilterSummary>>()
let adminCustomerOrderSummaryGeneration = 0
function resetAdminCustomerOrderSummaryCache() {
  adminCustomerOrderSummaryGeneration += 1
  adminCustomerOrderSummaryCache.clear()
  adminCustomerOrderSummaryRequests.clear()
}
function loadAdminCustomerOrderSummary(customerId: number, query: URLSearchParams): Promise<CustomerOrderFilterSummary> {
  const key = `${customerId}?${query.toString()}`
  const cached = adminCustomerOrderSummaryCache.get(key)
  if (cached) return Promise.resolve(cached)
  const pending = adminCustomerOrderSummaryRequests.get(key)
  if (pending) return pending
  const generation = adminCustomerOrderSummaryGeneration
  let requestPromise: Promise<CustomerOrderFilterSummary>
  requestPromise = request<CustomerOrderFilterSummary>(`/admin/customers/${customerId}/orders/summary?${query.toString()}`, {}, 'admin')
    .then(summary => {
      if (generation === adminCustomerOrderSummaryGeneration) {
        adminCustomerOrderSummaryCache.set(key, summary)
        if (adminCustomerOrderSummaryCache.size > 20) adminCustomerOrderSummaryCache.delete(adminCustomerOrderSummaryCache.keys().next().value!)
      }
      return summary
    })
    .finally(() => {
      if (adminCustomerOrderSummaryRequests.get(key) === requestPromise) adminCustomerOrderSummaryRequests.delete(key)
    })
  adminCustomerOrderSummaryRequests.set(key, requestPromise)
  return requestPromise
}
const customerReferrals = ref<Page>({ items: [], total: 0, page: 1, totalPages: 1 })

function adminOrderDateRange() {
  if (adminDateFilter.value === 'custom') {
    const from = adminDateFrom.value ? new Date(`${adminDateFrom.value}T00:00:00`) : null
    const to = adminDateTo.value ? new Date(`${adminDateTo.value}T00:00:00`) : null
    return [from, to ? new Date(to.getTime() + 86_400_000) : null]
  }
  return quickOrderDateRange(adminDateFilter.value)
}

function quickOrderDateRange(filter: string): [Date | null, Date | null] {
  const now = new Date()
  const startOfDay = (date: Date) => new Date(date.getFullYear(), date.getMonth(), date.getDate())
  const startOfWeek = (date: Date) => {
    const start = startOfDay(date)
    start.setDate(start.getDate() - ((start.getDay() + 6) % 7))
    return start
  }
  if (filter === 'week') return [startOfWeek(now), new Date(startOfWeek(now).getTime() + 7 * 86_400_000)]
  if (filter === 'lastweek') { const end = startOfWeek(now); return [new Date(end.getTime() - 7 * 86_400_000), end] }
  if (filter === 'month') return [new Date(now.getFullYear(), now.getMonth(), 1), new Date(now.getFullYear(), now.getMonth() + 1, 1)]
  if (filter === 'lastmonth') return [new Date(now.getFullYear(), now.getMonth() - 1, 1), new Date(now.getFullYear(), now.getMonth(), 1)]
  return [null, null]
}

function adminBillingDateRange(): [Date | null, Date | null] {
  if (adminBillDateFilter.value === 'custom') {
    const from = adminBillDateFrom.value ? new Date(`${adminBillDateFrom.value}T00:00:00`) : null
    const end = adminBillDateTo.value ? new Date(`${adminBillDateTo.value}T00:00:00`) : null
    return [from, end ? new Date(end.getTime() + 86_400_000) : null]
  }
  const now = new Date()
  if (adminBillDateFilter.value === 'week') {
    const from = new Date(now.getFullYear(), now.getMonth(), now.getDate())
    from.setDate(from.getDate() - ((from.getDay() + 6) % 7))
    return [from, new Date(from.getTime() + 7 * 86_400_000)]
  }
  if (adminBillDateFilter.value === 'lastweek') {
    const end = new Date(now.getFullYear(), now.getMonth(), now.getDate())
    end.setDate(end.getDate() - ((end.getDay() + 6) % 7))
    return [new Date(end.getTime() - 7 * 86_400_000), end]
  }
  if (adminBillDateFilter.value === 'month') return [new Date(now.getFullYear(), now.getMonth(), 1), new Date(now.getFullYear(), now.getMonth() + 1, 1)]
  if (adminBillDateFilter.value === 'lastmonth') return [new Date(now.getFullYear(), now.getMonth() - 1, 1), new Date(now.getFullYear(), now.getMonth(), 1)]
  return [null, null]
}

function customerOrderDateRange(): [Date | null, Date | null] {
  if (orderDateFilter.value === 'custom') {
    const from = orderDateFrom.value ? new Date(`${orderDateFrom.value}T00:00:00`) : null
    const to = orderDateTo.value ? new Date(`${orderDateTo.value}T00:00:00`) : null
    return [from, to ? new Date(to.getTime() + 86_400_000) : null]
  }
  const now = new Date()
  const startOfDay = (date: Date) => new Date(date.getFullYear(), date.getMonth(), date.getDate())
  const startOfWeek = (date: Date) => {
    const start = startOfDay(date)
    start.setDate(start.getDate() - ((start.getDay() + 6) % 7))
    return start
  }
  if (orderDateFilter.value === 'week') return [startOfWeek(now), new Date(startOfWeek(now).getTime() + 7 * 86_400_000)]
  if (orderDateFilter.value === 'lastweek') { const end = startOfWeek(now); return [new Date(end.getTime() - 7 * 86_400_000), end] }
  if (orderDateFilter.value === 'month') return [new Date(now.getFullYear(), now.getMonth(), 1), new Date(now.getFullYear(), now.getMonth() + 1, 1)]
  if (orderDateFilter.value === 'lastmonth') return [new Date(now.getFullYear(), now.getMonth() - 1, 1), new Date(now.getFullYear(), now.getMonth(), 1)]
  return [null, null]
}

function setOrderDateFilter(filter: string) {
  orderDateFilter.value = filter
  if (filter !== 'custom') { orderDateFrom.value = ''; orderDateTo.value = '' }
  orderPage.value = 1
  void loadCustomerPage()
}

function validDateInput(value: string) {
  if (!value) return true
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const parsed = new Date(`${value}T00:00:00.000Z`)
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value
}

function validateAdminDateRange(kind: 'orders' | 'bills') {
  const from = kind === 'bills' ? adminBillDateFrom.value : adminDateFrom.value
  const to = kind === 'bills' ? adminBillDateTo.value : adminDateTo.value
  if (!validDateInput(from) || !validDateInput(to)) {
    showNotice('Enter a valid date in YYYY-MM-DD format.', 'error')
    return false
  }
  if (from && to && from > to) {
    showNotice('Start date must be on or before end date.', 'error')
    return false
  }
  return true
}

function applyAdminDateRange(kind: 'orders' | 'bills') {
  if (busy.value || !validateAdminDateRange(kind)) return
  if (kind === 'bills') {
    adminBillDateFilter.value = 'custom'
    adminBillPage.value = 1
  } else {
    adminDateFilter.value = 'custom'
    adminOrderPage.value = 1
    adminCommissionPage.value = 1
  }
  void loadAdminPage()
}

function applyOrderDateRange() {
  if (!validDateInput(orderDateFrom.value) || !validDateInput(orderDateTo.value)) {
    showNotice('Enter a valid date in YYYY-MM-DD format.', 'error', 'Use the calendar or enter a valid date in YYYY-MM-DD format.')
    return
  }
  if (orderDateFrom.value && orderDateTo.value && orderDateFrom.value > orderDateTo.value) {
    showNotice('Start date must be on or before end date.', 'error', 'Choose an end date that is the same as or later than the start date.')
    return
  }
  orderDateFilter.value = 'custom'
  orderPage.value = 1
  void loadCustomerPage()
}

function setOrderPaymentFilter(filter: string) {
  orderPaymentFilter.value = filter
  orderPage.value = 1
  void loadCustomerPage()
}

function setOrderShippingFilter(filter: string) {
  orderShippingFilter.value = filter
  orderPage.value = 1
  void loadCustomerPage()
}

function setOrderPage(page: number) {
  if (page < 1 || page > orders.value.totalPages || page === orderPage.value) return
  orderPage.value = page
  void loadCustomerPage()
}

function setAdminOrderPage(page: number) {
  if (page < 1 || page > adminOrders.value.totalPages || page === adminOrderPage.value) return
  adminOrderPage.value = page
  void loadAdminPage()
}

function setAuditPage(page: number) {
  if (page < 1 || page > auditOrders.value.totalPages || page === auditPage.value) return
  auditPage.value = page
  void loadAdminPage()
}

function selectAuditResult(result: 'pending' | 'rejected' | 'approved') {
  if (auditResultFilter.value === result) return
  auditResultFilter.value = result
  auditPage.value = 1
  void loadAdminPage()
}

function searchAuditOrders() {
  auditPage.value = 1
  void loadAdminPage()
}

function setAdminProductPage(page: number) {
  if (page < 1 || page > adminProducts.value.totalPages || page === adminProductPage.value) return
  adminProductPage.value = page
  void loadAdminPage()
}

async function loadAdminMembers(page = adminMembers.value.page, query = adminMemberQuery.value) {
  if (adminMembersLoading.value) return
  adminMembersLoading.value = true
  try {
    const params = new URLSearchParams({ page: String(page) })
    if (query) params.set('q', query)
    const result = await request<Page<AdminMember>>(`/admin/members?${params.toString()}`, {}, 'admin')
    adminMembers.value = result
    adminMemberQuery.value = query
  } catch (error) {
    if (!getToken('admin')) loggedIn.value = false
    else showNotice(errorText(error), 'error')
  } finally { adminMembersLoading.value = false }
}

async function setAdminMemberPage(page: number) {
  if (adminMembersLoading.value || page < 1 || page > adminMembers.value.totalPages || page === adminMembers.value.page) return
  await loadAdminMembers(page)
}

async function searchAdminMembers() {
  await loadAdminMembers(1, adminMemberSearch.value.trim())
}

async function refreshAdminSearch() {
  if (busy.value || adminMembersLoading.value) return
  if (adminCustomerSearchTimer !== undefined) window.clearTimeout(adminCustomerSearchTimer)
  if (billSearchTimer !== undefined) window.clearTimeout(billSearchTimer)
  switch (adminPage.value) {
    case 'order-audit': auditPage.value = 1; break
    case 'orders': adminOrderPage.value = 1; invalidateRequestCache(adminOrderSummaryCache); break
    case 'products': adminProductPage.value = 1; break
    case 'certificates': adminCertificatePage.value = 1; break
    case 'customers': adminCustomerPage.value = 1; break
    case 'members': await searchAdminMembers(); return
    case 'finance':
      if (adminMode.value === 'billing') { adminBillPage.value = 1; invalidateRequestCache(billSummaryCache) }
      else { adminCommissionPage.value = 1; invalidateRequestCache(commissionSummaryCache) }
      break
  }
  await loadAdminPage()
}

function setAdminCertificatePage(page: number) {
  if (page < 1 || page > adminCertificates.value.totalPages || page === adminCertificatePage.value) return
  adminCertificatePage.value = page
  void loadAdminPage()
}

function setAdminCustomerPage(page: number) {
  if (page < 1 || page > adminCustomers.value.totalPages || page === adminCustomerPage.value) return
  adminCustomerPage.value = page
  void loadAdminPage()
}

function searchAdminCustomers() {
  if (adminCustomerSearchTimer !== undefined) window.clearTimeout(adminCustomerSearchTimer)
  adminCustomerSearchTimer = window.setTimeout(applyAdminCustomerSearch, 250)
}

function applyAdminCustomerSearch() {
  if (adminCustomerSearchTimer !== undefined) window.clearTimeout(adminCustomerSearchTimer)
  adminCustomerPage.value = 1
  void loadAdminPage()
}

function setAdminCommissionPage(page: number) {
  if (page < 1 || page > commissions.value.totalPages || page === adminCommissionPage.value) return
  adminCommissionPage.value = page
  void loadAdminPage()
}

function setAdminBillPage(page: number) {
  if (page < 1 || page > bills.value.totalPages || page === adminBillPage.value) return
  adminBillPage.value = page
  void loadAdminPage()
}

function setAdminBillType(type: string) {
  if (type === 'commission_payable' && !currentMerchant.value?.commissionEnabled) return
  adminBillTypeFilter.value = type
  adminBillStatusFilter.value = 'all'
  adminBillPage.value = 1
  void loadAdminPage()
}

function setAdminBillStatus(status: string) {
  adminBillStatusFilter.value = status
  adminBillPage.value = 1
  void loadAdminPage()
}

function formatDate(value: unknown) {
  const date = new Date(String(value || ''))
  return Number.isNaN(date.getTime()) ? String(value || '—') : new Intl.DateTimeFormat('sv-SE', { year: 'numeric', month: '2-digit', day: '2-digit', timeZone: 'UTC' }).format(date)
}

function formatDateTime(value: unknown) {
  const date = new Date(String(value || ''))
  if (Number.isNaN(date.getTime())) return String(value || '—')
  const locale = language.value === 'zh-CN' ? 'zh-CN' : 'en-US'
  const time = new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(date)
  return `${formatDate(date)} ${time}`
}

function orderItemNames(order: CustomerOrder) {
  return (order.items || []).map(item => item.name).join('  ')
}

async function copyOrderNumber(orderNo: string) {
  await copyCustomerValue(orderNo, 'Order number copied.')
}

async function copyCustomerValue(value: string, successMessage: string) {
  try {
    await navigator.clipboard.writeText(value)
    showNotice(successMessage)
  } catch { showNotice('Copy is unavailable in this browser.', 'error', 'Copy this information manually from the order details.') }
}

function copyTrackingNumber(value: string) {
  void copyCustomerValue(value, '运单号已复制。')
}

function copyOrderItemDetails(item: AnyRecord) {
  const details = [
    item.name,
    t('数量：{0}', [item.quantity]),
    t('单价：{0}', [money(item.unitPrice)]),
    t('小计：{0}', [money(item.lineTotal)]),
  ].join('\n')
  void copyCustomerValue(details, '商品详情已复制。')
}

function clearOrderCopyFeedback() {
  window.clearTimeout(orderCopyFeedbackTimer)
  orderCopyFeedbackTimer = undefined
  orderCopyFeedback.value = ''
}

function showOrderCopyFeedback(message: string, kind: 'success' | 'error') {
  window.clearTimeout(orderCopyFeedbackTimer)
  orderCopyFeedbackKind.value = kind
  orderCopyFeedback.value = message
  orderCopyFeedbackTimer = window.setTimeout(() => {
    orderCopyFeedback.value = ''
    orderCopyFeedbackTimer = undefined
  }, 3000)
}

async function copyOrderDetails() {
  const order = financeOrderDetail.value
  if (!order) return
  const address = order.address || {}
  const street = [address.street, address.apt].filter((value: unknown) => typeof value === 'string' && value.trim()).join(' ')
  const lines = [
    t('订单号：{0}', [order.orderNo || '']),
    t('收件人名称：{0}', [address.name || '']),
    t('联系电话：{0}', [address.phone || '']),
    t('地址：{0}', [street]),
    t('城市：{0}', [address.city || '']),
    t('州：{0}', [address.region || '']),
    t('邮编：{0}', [address.postalCode || '']),
    t('邮箱：{0}', [order.customer?.email || '']),
    '------------------------------------------------',
    ...formatOrderCopyRows(order.items || [], t('产品代号'), t('数量')),
  ]
  try {
    await navigator.clipboard.writeText(lines.join('\n'))
    showOrderCopyFeedback('复制成功', 'success')
  } catch {
    showOrderCopyFeedback('复制失败，请检查浏览器剪贴板权限。', 'error')
  }
}

const pageTitle = computed(() => t(({ products: 'Products', certificates: 'Certificates of Analysis', cart: 'Shopping Cart', orders: 'Order History', referrals: 'Referrals & Commission', account: 'My Account', security: 'Security' }[customerPage.value] || 'Products')))
const cartCount = computed(() => cart.value.items.reduce((sum, item) => sum + item.quantity, 0))
const cartProductQuantities = computed<Record<number, number | null>>(() => ({
  ...Object.fromEntries(cart.value.items.map(item => [item.productId, item.quantity])),
  ...catalogCartQuantityOverrides,
}))
const visibleOrders = computed(() => orders.value.items || [])
const orderPageNumbers = computed(() => {
  const pages = orders.value.totalPages || 1
  if (pages <= 5) return Array.from({ length: pages }, (_, index) => index + 1)
  const first = Math.min(Math.max(orderPage.value - 2, 1), pages - 4)
  return Array.from({ length: 5 }, (_, index) => first + index)
})
const adminOrderPageNumbers = computed(() => {
  const pages = adminOrders.value.totalPages || 1
  if (pages <= 5) return Array.from({ length: pages }, (_, index) => index + 1)
  const first = Math.min(Math.max(adminOrderPage.value - 2, 1), pages - 4)
  return Array.from({ length: 5 }, (_, index) => first + index)
})
const adminProductPageNumbers = computed(() => {
  const pages = adminProducts.value.totalPages || 1
  if (pages <= 5) return Array.from({ length: pages }, (_, index) => index + 1)
  const first = Math.min(Math.max(adminProductPage.value - 2, 1), pages - 4)
  return Array.from({ length: 5 }, (_, index) => first + index)
})
const adminMemberPageNumbers = computed(() => {
  const pages = adminMembers.value.totalPages || 1
  if (pages <= 5) return Array.from({ length: pages }, (_, index) => index + 1)
  const first = Math.min(Math.max(adminMembers.value.page - 2, 1), pages - 4)
  return Array.from({ length: 5 }, (_, index) => first + index)
})
const adminCertificatePageNumbers = computed(() => {
  const pages = adminCertificates.value.totalPages || 1
  if (pages <= 5) return Array.from({ length: pages }, (_, index) => index + 1)
  const first = Math.min(Math.max(adminCertificatePage.value - 2, 1), pages - 4)
  return Array.from({ length: 5 }, (_, index) => first + index)
})
const adminCustomerPageNumbers = computed(() => {
  const pages = adminCustomers.value.totalPages || 1
  if (pages <= 5) return Array.from({ length: pages }, (_, index) => index + 1)
  const first = Math.min(Math.max(adminCustomerPage.value - 2, 1), pages - 4)
  return Array.from({ length: 5 }, (_, index) => first + index)
})
const adminCommissionPageNumbers = computed(() => {
  const pages = commissions.value.totalPages || 1
  if (pages <= 5) return Array.from({ length: pages }, (_, index) => index + 1)
  const first = Math.min(Math.max(adminCommissionPage.value - 2, 1), pages - 4)
  return Array.from({ length: 5 }, (_, index) => first + index)
})
const adminBillPageNumbers = computed(() => {
  const pages = bills.value.totalPages || 1
  if (pages <= 5) return Array.from({ length: pages }, (_, index) => index + 1)
  const first = Math.min(Math.max(adminBillPage.value - 2, 1), pages - 4)
  return Array.from({ length: 5 }, (_, index) => first + index)
})
const orderStats = computed(() => ({
  total: customerOrderSummary.value.allOrders || 0,
  received: customerOrderSummary.value.received || 0,
  transit: customerOrderSummary.value.transit || 0,
  pendingShip: customerOrderSummary.value.pendingShip || 0,
  payableOrders: customerOrderSummary.value.payableOrders || 0,
  payable: customerOrderSummary.value.payableTotal || 0,
  cancelled: customerOrderSummary.value.cancelled || 0,
}))
const visibleAdminOrders = computed(() => adminOrders.value.items)
const visibleAdminMembers = computed(() => adminMembers.value.items)
const visibleBills = computed(() => bills.value.items.filter(item => {
  const query = adminSearch.value.trim().toLowerCase()
  return !query || `${item.billNo || ''} ${item.orderNo || ''} ${item.customer || ''} ${item.customerName || ''} ${item.email || ''}`.toLowerCase().includes(query)
}))
const money = (value: unknown) => `$${Number(value || 0).toFixed(2)}`
const statusLabel = (value: string) => t(({ pending: 'Pending', packed: 'Packed', shipped: 'Shipped', received: 'Received', completed: 'Completed', cancelled: 'Cancelled', unpaid: 'Unpaid', pending_verification: 'Pending verification', partially_paid: 'Partially paid', paid: 'Fully paid', estimated: 'Estimated', payable: 'Payable', settled: 'Settled' }[value] || value))
const sellerStatusLabel = (value: string) => t(({ pending_review: '待审核', approved: '审核通过', rejected: '审核不通过', withdrawn: '审核前撤回', pending: '待处理', packed: '已打包', shipped: '已发货', received: '已收货', completed: '已完成', cancelled: '已取消', unpaid: '待支付', pending_verification: '待验证', partially_paid: '部分支付', paid: '已付清', estimated: '预计', payable: '待结', settled: '已结算', void: '已作废' }[value] || value))
const billStatusCode = (bill: AnyRecord) => bill.billType === 'commission_payable' ? bill.status : bill.paymentStatus || bill.status
const billStatusLabel = (bill: AnyRecord) => bill.billType === 'commission_payable'
  ? (({ estimated: '待验证', payable: '待支付', settled: '已支付', void: '已作废' } as Record<string, string>)[bill.status] || sellerStatusLabel(bill.status))
  : sellerStatusLabel(bill.paymentStatus || bill.status)
const commissionStatusLabel = (value: string) => ({ estimated: '待验证', payable: '待支付', settled: '已支付', void: '已作废' }[value] || sellerStatusLabel(value))
const statusClass = (value: string) => `status-${value}`
function errorText(error: unknown) {
  const message = error instanceof Error ? error.message : 'Something went wrong.'
  if (area.value === 'admin') {
    const details = error instanceof ApiRequestError ? Object.entries(error.fields).map(([field, reason]) => `${field}：${reason}`) : []
    return [message, ...details].join('\n')
  }
  if (!(error instanceof ApiRequestError) || !Object.keys(error.fields).length) return t(message)
  const details = Object.entries(error.fields).map(([field, reason]) => `${t(field)}：${t(reason)}`).join('；')
  return `${t(message)} ${details}`
}

function clearNotice() {
  noticeSequence += 1
  window.clearTimeout(noticeTimer)
  noticeTimer = undefined
  notice.value = ''
  noticeHint.value = ''
}

function errorCorrection(error: unknown) {
  const message = error instanceof Error ? error.message.toLowerCase() : ''
  if (/stock|quantity|available/.test(message)) return 'Check the available stock and lower the quantity, then try again.'
  if (/quote|cart changed|pricing|price changed/.test(message)) return 'Refresh the cart and checkout again to get a current quote.'
  if (/address/.test(message)) return 'Choose a saved address or add a complete shipping address.'
  if (/password/.test(message)) return 'Confirm the current password and enter the same new password twice.'
  if (/payment|transaction|channel|total/.test(message)) return 'Check the payment channel, exact order amount, and transaction ID, then submit again.'
  if (/order|cancel|editable|status/.test(message)) return 'Refresh the order and check that its status still allows this action.'
  if (/fetch|network|timeout|connection/.test(message)) return 'Check your connection and retry. If it continues, contact the seller.'
  if (/auth|account|token|session/.test(message)) return 'Your session may have expired. Sign in again and retry the operation.'
  if (/invalid|required|valid|field/.test(message)) return 'Review the required fields, correct their values, and submit again.'
  return 'Review the reason above, correct the related information, then retry. If it still fails, contact the seller.'
}

function showNotice(text: string, kind: 'success' | 'error' = 'success', hint = '') {
  // Seller actions already reflect successful changes in their updated lists or closed forms.
  // Keep global notices for errors that still need the seller's attention.
  if (area.value === 'admin' && kind === 'success') {
    clearNotice()
    return
  }
  const sequence = ++noticeSequence
  window.clearTimeout(noticeTimer)
  notice.value = text
  noticeKind.value = kind
  noticeHint.value = hint
  noticeTimer = window.setTimeout(() => {
    if (sequence === noticeSequence) clearNotice()
  }, 3000)
}

function showFailure(error: unknown, hint = '') {
  showNotice(errorText(error), 'error', hint || errorCorrection(error))
}

function exportFilter(type: 'orders' | 'commissions' | 'bills') {
  const filter: Record<string, string> = {}
  if (adminSearch.value.trim()) filter.q = adminSearch.value.trim()
  const [from, to] = type === 'bills' ? adminBillingDateRange() : adminOrderDateRange()
  if (from) filter.from = from.toISOString()
  if (to) filter.to = to.toISOString()
  if (type === 'bills' && adminBillTypeFilter.value !== 'all') {
    filter.type = adminBillTypeFilter.value
    if (adminBillStatusFilter.value !== 'all') filter.status = adminBillStatusFilter.value
  }
  if (type === 'commissions' && adminCommissionStatus.value !== 'all') filter.status = adminCommissionStatus.value
  if (type === 'orders') {
    if (adminPaymentFilter.value !== 'all') filter.paymentStatus = adminPaymentFilter.value
    if (adminShippingFilter.value !== 'all') filter.status = adminShippingFilter.value
  }
  return filter
}

function closeExportConfirmation() {
  if (exportSubmitting.value) return
  exportPreviewSequence += 1
  exportPreviewLoading.value = false
  exportConfirmation.value = null
}

const exportTypeLabel = (type: ExportType) => ({ orders: '订单', commissions: '佣金', bills: '账单' })[type]
const exportCountLabel = (value: number) => Number(value || 0).toLocaleString()

async function startExport(type: ExportType) {
  if (exportPreviewLoading.value || exportSubmitting.value) return
  const dateKind = type === 'bills' ? 'bills' : 'orders'
  const dateFilter = type === 'bills' ? adminBillDateFilter.value : adminDateFilter.value
  if (dateFilter === 'custom' && !validateAdminDateRange(dateKind)) return
  const confirmation: ExportConfirmation = { type, filter: exportFilter(type), preview: null }
  exportConfirmation.value = confirmation
  const sequence = ++exportPreviewSequence
  exportPreviewLoading.value = true
  try {
    confirmation.preview = await request<ExportPreviewResponse>(`/admin/exports/${type}/count`, {
      method: 'POST',
      body: JSON.stringify({ filter: confirmation.filter }),
    }, 'admin')
  } catch (error) {
    if (sequence === exportPreviewSequence) {
      exportConfirmation.value = null
      showNotice(errorText(error), 'error')
    }
  } finally {
    if (sequence === exportPreviewSequence) exportPreviewLoading.value = false
  }
}

async function confirmExport() {
  const confirmation = exportConfirmation.value
  const preview = confirmation?.preview
  if (!confirmation || !preview || preview.rowCount <= 0 || preview.rowCount > preview.maxRows || exportSubmitting.value) return
  exportSubmitting.value = true
  try {
    const file = await download(`/admin/exports/${confirmation.type}`, {
      method: 'POST',
      body: JSON.stringify({ filter: confirmation.filter }),
    }, 'admin')
    const url = URL.createObjectURL(file.blob)
    const link = document.createElement('a')
    link.href = url
    link.download = file.filename
    link.click()
    URL.revokeObjectURL(url)
    exportConfirmation.value = null
    showNotice('导出已下载。')
  } catch (error) { showNotice(errorText(error), 'error') }
  finally { exportSubmitting.value = false }
}

function commissionCanSettle(item: AnyRecord) {
  return item.status === 'payable'
}

function startCommissionSelection() {
  commissionSelectedRows.value = []
  commissionSelectionMode.value = true
}

function cancelCommissionSelection() {
  commissionSelectedRows.value = []
  commissionSelectionMode.value = false
}

function isCommissionSelected(item: AnyRecord) {
  return commissionSelectedRows.value.some(selected => Number(selected.id) === Number(item.id))
}

function toggleCommissionSelection(item: AnyRecord) {
  if (!commissionCanSettle(item)) return
  commissionSelectedRows.value = isCommissionSelected(item)
    ? commissionSelectedRows.value.filter(selected => Number(selected.id) !== Number(item.id))
    : [...commissionSelectedRows.value, item]
}

const commissionPageEligible = computed(() => commissions.value.items.filter(commissionCanSettle))
const commissionPageAllSelected = computed(() => commissionPageEligible.value.length > 0 && commissionPageEligible.value.every(isCommissionSelected))

function toggleCommissionPageSelection() {
  if (commissionPageAllSelected.value) {
    const pageIds = new Set(commissionPageEligible.value.map(item => Number(item.id)))
    commissionSelectedRows.value = commissionSelectedRows.value.filter(item => !pageIds.has(Number(item.id)))
  } else {
    const selectedIds = new Set(commissionSelectedRows.value.map(item => Number(item.id)))
    commissionSelectedRows.value = [...commissionSelectedRows.value, ...commissionPageEligible.value.filter(item => !selectedIds.has(Number(item.id)))]
  }
}

function openCommissionSettlement(items: AnyRecord[]) {
  if (!items.length || items.some(item => !commissionCanSettle(item))) return
  commissionSettlementRows.value = [...items]
  commissionSettlementRemark.value = ''
  commissionSettlementOpen.value = true
}

function closeCommissionSettlement() {
  if (commissionSettlementSubmitting.value) return
  commissionSettlementOpen.value = false
  commissionSettlementRows.value = []
  commissionSettlementRemark.value = ''
}

async function submitCommissionSettlement() {
  if (commissionSettlementSubmitting.value || !commissionSettlementRows.value.length) return
  const items = commissionSettlementRows.value.map(item => ({ accrualId: Number(item.id), expectedAmount: Number(item.commissionAmount || 0) }))
  commissionSettlementSubmitting.value = true
  try {
    await request('/admin/commission-settlement-batches', { method: 'POST', body: JSON.stringify({ items, remark: commissionSettlementRemark.value.trim() || null, offlinePaidConfirmed: true }) }, 'admin')
    commissionSettlementOpen.value = false
    commissionSettlementRows.value = []
    commissionSettlementRemark.value = ''
    cancelCommissionSelection()
    invalidateRequestCache(commissionSummaryCache)
    invalidateRequestCache(billSummaryCache)
    await loadAdminPage()
  } catch (error) {
    showNotice(errorText(error), 'error')
    if (error instanceof ApiRequestError && ['COMMISSION_NOT_PAYABLE', 'COMMISSION_AMOUNT_CHANGED'].includes(error.code)) {
      commissionSettlementOpen.value = false
      commissionSettlementRows.value = []
      commissionSettlementRemark.value = ''
      cancelCommissionSelection()
      invalidateRequestCache(commissionSummaryCache)
      invalidateRequestCache(billSummaryCache)
      await loadAdminPage()
    }
  }
  finally { commissionSettlementSubmitting.value = false }
}

function goCustomer(page: string) {
  if (page === 'referrals' && !currentMerchant.value?.commissionEnabled) page = 'account'
  orderDetailOpen.value = false
  customerPaymentOpen.value = false
  paymentSuccessOpen.value = false
  addressPickerOpen.value = false
  if (page === 'orders') {
    invalidateCustomerOrderSummary()
    channels.value = []
    paymentChannel.value = null
    cryptoEnabled.value = false
    onlinePaymentLinkEnabled.value = false
    paymentMethod.value = 'crypto'
    transactionNo.value = ''
  }
  if (page === 'referrals' || page === 'security') {
    if (page === 'referrals') invalidateCustomerReferralSummary()
    customerPage.value = 'account'
    accountTab.value = page
  } else {
    customerPage.value = page
    if (page === 'account') accountTab.value = 'profile'
    if (page === 'cart') {
      checkoutStep.value = 0
      checkoutQuote.value = null
    }
  }
  history.pushState({}, '', page === 'products' ? '/' : `/${page}`)
  void loadCustomerPage()
}

function closePaymentModal() {
  customerPaymentOpen.value = false
  channels.value = []
  transactionNo.value = ''
  cryptoEnabled.value = false
  onlinePaymentLinkEnabled.value = false
}

function goAdmin(page: string) {
  const internalPage = ({ commissions: 'finance', billing: 'finance', coas: 'certificates', users: 'customers', payments: 'settings', permissions: 'members' } as Record<string, string>)[page] || page
  adminMode.value = page === 'billing' ? 'billing' : page === 'finance' || page === 'commissions' ? 'finance' : ''
  adminPage.value = internalPage
  if (internalPage === 'orders') invalidateRequestCache(adminOrderSummaryCache)
  if (internalPage === 'finance' && adminMode.value === 'billing') invalidateRequestCache(billSummaryCache)
  if (internalPage === 'finance' && adminMode.value !== 'billing') invalidateRequestCache(commissionSummaryCache)
  if (internalPage === 'categories') invalidateAdminCategories()
  history.pushState({}, '', page === 'dashboard' ? '/admin' : `/admin/${page}`)
  void loadAdminPage()
}

function chooseArea(next: Area) {
  parcelManagerOrderId.value = null
  loginMode.value = next
  area.value = next
  loginAccount.value = ''
  loginPassword.value = ''
  loginError.value = ''
}

async function signIn() {
  if (busy.value) return
  loginError.value = ''
  busy.value = true
  try {
    const body = loginMode.value === 'customer' ? { email: loginAccount.value, password: loginPassword.value } : { account: loginAccount.value, password: loginPassword.value }
    const result = await api.login(loginMode.value, body as unknown as Record<string, string>)
    if (loginMode.value === 'admin' && result.member) {
      rememberSellerSession({ member: result.member, merchant: result.merchant }, result.accessToken)
    }
    setToken(loginMode.value, result.accessToken)
    resetRequestCaches()
    area.value = loginMode.value
    loggedIn.value = true
    if (location.pathname.startsWith('/admin') !== (loginMode.value === 'admin')) {
      history.replaceState({}, '', loginMode.value === 'admin' ? '/admin' : '/products')
    }
    window.location.reload()
  } catch (error) {
    loginError.value = errorText(error)
  } finally {
    busy.value = false
  }
}

function activateSellerAccount() {
  resetRequestCaches()
  history.replaceState({}, '', '/admin')
  window.location.reload()
}

async function signOut() {
  if (signingOut.value) return
  const message = area.value === 'customer' ? t('Are you sure you want to sign out?') : '退出将使该账号在所有设备上的登录失效，确定退出吗？'
  if (!window.confirm(t(message))) return
  signingOut.value = true
  try { await request(`/${area.value}/auth/logout`, { method: 'POST' }, area.value) } catch {
    if (area.value === 'admin') sessionStorage.setItem('dailyactivity.admin.logout-unconfirmed', '1')
  }
  clearToken(area.value)
  resetRequestCaches()
  loggedIn.value = false
  parcelManagerOrderId.value = null
  clearNotice()
  window.location.reload()
}

async function loadCustomerSession() {
  const result = await api.me('customer')
  currentMerchant.value = result.merchant as MerchantIdentity
  if (!currentMerchant.value.commissionEnabled && accountTab.value === 'referrals') {
    accountTab.value = 'profile'
    history.replaceState({}, '', '/account')
  }
  customer.value = result.customer as CustomerProfile
  profileName.value = customer.value.name || ''
  await loadCustomerPage()
}

async function loadCustomerPage(): Promise<boolean> {
  if (!loggedIn.value || area.value !== 'customer') return false
  busy.value = true
  try {
    if (customerPage.value === 'products') {
      const [categoryResult, productResult, cartResult] = await Promise.all([
        request<CustomerCategory[]>('/customer/categories'),
        request<CustomerProduct[]>('/customer/products'),
        request<CustomerCart>('/customer/cart'),
      ])
      categories.value = categoryResult
      products.value = productResult
      cart.value = cartResult
    } else if (customerPage.value === 'cart') {
      const [cartResult, addressResult] = await Promise.all([request<CustomerCart>('/customer/cart'), request<CustomerAddress[]>('/customer/addresses')])
      cart.value = cartResult
      addresses.value = addressResult
      if (selectedAddressId.value === null) selectedAddressId.value = (addresses.value.find(item => item.isDefault) || addresses.value[0])?.id || null
    } else if (customerPage.value === 'orders') {
      const query = new URLSearchParams()
      if (orderSearch.value.trim()) query.set('q', orderSearch.value.trim())
      if (orderPaymentFilter.value !== 'all') query.set('paymentStatus', orderPaymentFilter.value)
      if (orderShippingFilter.value !== 'all') query.set('status', orderShippingFilter.value)
      const [from, to] = customerOrderDateRange()
      if (from) query.set('from', from.toISOString())
      if (to) query.set('to', to.toISOString())
      query.set('page', String(orderPage.value))
      const [orderPageResult] = await Promise.all([
        request<Page<CustomerOrder>>(`/customer/orders${query.size ? `?${query.toString()}` : ''}`),
        loadCached(customerOrderSummaryCache, () => request<CustomerOrderSummary>('/customer/orders/summary'), value => { customerOrderSummary.value = value }),
      ])
      orders.value = orderPageResult
    } else if (customerPage.value === 'certificates') {
      certificates.value = await request<CustomerCertificate[]>('/customer/certificates')
    } else if (customerPage.value === 'referrals' || (customerPage.value === 'account' && accountTab.value === 'referrals')) {
      const [customerResult] = await Promise.all([
        request<CustomerReferral[]>('/customer/referrals'),
        loadCached(customerReferralSummaryCache, () => request<CustomerReferralSummary>('/customer/referrals/summary'), value => { referralSummary.value = value }),
      ])
      referralCustomers.value = customerResult
    } else if (customerPage.value === 'account') {
      addresses.value = await request<CustomerAddress[]>('/customer/addresses')
    }
    return true
  } catch (error) {
    if (!getToken('customer')) {
      loggedIn.value = false
      showFailure(error, 'Your session may have expired. Sign in again, then retry the operation.')
    } else showFailure(error)
    return false
  } finally {
    busy.value = false
  }
}

async function loadCustomerReferrals() {
  invalidateCustomerReferralSummary()
  await loadCustomerPage()
}

async function updateCatalogCartQuantity(product: CustomerProduct, quantity: number) {
  if (pendingCartProductIds.has(product.id) || !Number.isInteger(quantity) || quantity < 0 || quantity > product.stock) return
  pendingCartProductIds.add(product.id)
  catalogCartQuantityOverrides[product.id] = quantity || null
  checkoutQuote.value = null
  const update = async () => {
    try {
      if (quantity === 0) {
        await request(`/customer/cart/items/${product.id}`, { method: 'DELETE' })
        cart.value = await request<CustomerCart>('/customer/cart')
      } else {
        cart.value = await request<CustomerCart>(`/customer/cart/items/${product.id}`, { method: 'PUT', body: JSON.stringify({ quantity }) })
      }
    } catch (error) {
      showFailure(error)
      try { cart.value = await request<CustomerCart>('/customer/cart') } catch (refreshError) { showFailure(refreshError) }
    } finally {
      delete catalogCartQuantityOverrides[product.id]
      pendingCartProductIds.delete(product.id)
    }
  }
  cartMutationQueue = cartMutationQueue.then(update, update)
  await cartMutationQueue
}

async function setQuantity(item: CustomerCartItem, quantity: number) {
  if (!Number.isInteger(quantity) || quantity < 1 || quantity > item.product.stock) return
  checkoutQuote.value = null
  await runPending('cart', async () => {
    try { cart.value = await request<CustomerCart>(`/customer/cart/items/${item.productId}`, { method: 'PUT', body: JSON.stringify({ quantity }) }) }
    catch (error) { showFailure(error); await loadCustomerPage() }
  })
}

async function removeFromCart(productId: number) {
  checkoutQuote.value = null
  await runPending('cart', async () => {
    try { await request(`/customer/cart/items/${productId}`, { method: 'DELETE' }); await loadCustomerPage() }
    catch (error) { showFailure(error) }
  })
}

function defaultAddress() {
  return addresses.value.find(item => item.id === selectedAddressId.value) || addresses.value.find(item => item.isDefault) || addresses.value[0]
}

function selectCheckoutAddress(addressId: number | null) {
  selectedAddressId.value = addressId
  checkoutQuote.value = null
}

function continueCheckout() {
  if (!cart.value.items?.length) { showNotice('Your cart is empty.', 'error', 'Add at least one product to the cart before checkout.'); return }
  if (cart.value.items.some(item => Boolean(item.invalidReason) || item.product.stock < 1 || item.product.stock < item.quantity)) {
    showNotice('Some cart items are unavailable.', 'error', 'Remove unavailable items before continuing to checkout.')
    return
  }
  if (checkoutStep.value === 0) {
    checkoutStep.value = 1
    return
  }
  if (checkoutStep.value === 1) {
    const address = defaultAddress()
    if (!address) { showNotice('Add a shipping address before continuing.', 'error', 'Select a saved shipping address before continuing.'); openCheckoutAddressForm(); return }
    selectedAddressId.value = address.id
    checkoutStep.value = 2
    return
  }
  void checkout()
}

function backCheckout() {
  if (checkoutStep.value > 0) checkoutStep.value -= 1
}

async function checkout() {
  const address = defaultAddress()
  if (!address) { showNotice('Add a shipping address before checkout.', 'error', 'Choose a saved address or add a complete shipping address.'); openCheckoutAddressForm(); return }
  await runPending('checkout', async () => {
    try {
      let quote = checkoutQuote.value
      if (!quote) {
        quote = await request<CustomerOrderQuote>('/customer/order-quotes', { method: 'POST', body: JSON.stringify({ addressId: address.id, cartItems: cart.value.items.map(item => ({ productId: item.productId, quantity: item.quantity })) }) })
        const quotedItems = new Map(quote.items.map(item => [item.productId, item]))
        const amountsChanged = quote.items.length !== cart.value.items.length
          || money(quote.subtotal) !== money(cart.value.subtotal)
          || money(quote.shippingFee) !== money(cart.value.shippingFee)
          || money(quote.total) !== money(cart.value.total)
          || cart.value.items.some(item => {
            const quoted = quotedItems.get(item.productId)
            return !quoted
              || quoted.quantity !== item.quantity
              || quoted.name !== item.product.name
              || money(quoted.unitPrice) !== money(item.unitPrice)
              || money(quoted.lineTotal) !== money(item.lineTotal)
          })
        if (amountsChanged) {
          checkoutQuote.value = quote
          return
        }
      }
      const order = await request<AnyRecord>('/customer/orders', { method: 'POST', body: JSON.stringify({ quoteToken: quote.quoteToken, buyerNote: orderNote.value }) })
      invalidateCustomerOrderSummary()
      selectedOrder.value = order
      paymentAmount.value = String(order.remainingAmount)
      checkoutStep.value = 3
      checkoutQuote.value = null
      orderNote.value = ''
      try { cart.value = await request<CustomerCart>('/customer/cart') } catch { /* the created order remains available below */ }
      try {
        const paymentOptions = await request<CustomerPaymentOptions>('/customer/payment-options')
        if (customerPage.value === 'cart' && checkoutStep.value === 3 && selectedOrder.value?.id === order.id) {
          applyCustomerPaymentOptions(paymentOptions, order)
          transactionNo.value = ''
        }
      } catch {
        if (customerPage.value === 'cart' && checkoutStep.value === 3 && selectedOrder.value?.id === order.id) {
          cryptoEnabled.value = false
          onlinePaymentLinkEnabled.value = false
          channels.value = []
          paymentChannel.value = null
        }
      }
    } catch (error) {
      if (error instanceof ApiRequestError && ['QUOTE_CHANGED', 'QUOTE_EXPIRED', 'CART_CHANGED', 'INSUFFICIENT_STOCK'].includes(error.code)) {
        checkoutQuote.value = null
        if (['CART_CHANGED', 'INSUFFICIENT_STOCK'].includes(error.code)) {
          try { cart.value = await request<CustomerCart>('/customer/cart') } catch { /* keep the quote error visible if refresh also fails */ }
        }
        showFailure(error, 'Refresh the cart and checkout again to get a current quote.')
      } else showFailure(error)
    }
  })
}

async function openOrder(order: CustomerOrder) {
  if (orderDetailOpen.value && selectedOrder.value?.id === order.id) { closeOrderDetail(); return }
  customerPaymentOpen.value = false
  await runPending('order-detail', async () => {
    try {
      const detail = withSignedEventFiles(await request<AnyRecord>(`/customer/orders/${order.id}`))
      if (customerPage.value !== 'orders') return
      selectedOrder.value = detail
      orderDetailOpen.value = true
    } catch (error) { showFailure(error) }
  })
}

function withSignedEventFiles(detail: AnyRecord) {
  detail.events = (detail.events || []).map((event: AnyRecord) => ({
    ...event,
    files: (event.files || []).flatMap((fileUuid: string) => event.signedFileUrls?.[fileUuid] ? [{ fileUuid, url: event.signedFileUrls[fileUuid] }] : []),
  }))
  return detail
}

function closeOrderDetail() {
  orderDetailOpen.value = false
  selectedOrder.value = {}
}

async function editOrder(order: CustomerOrder) {
  orderDetailOpen.value = false
  customerPaymentOpen.value = false
  await runPending('order-edit', async () => {
    try {
      const [detail, addressResult] = await Promise.all([request<CustomerOrder>(`/customer/orders/${order.id}`), request<CustomerAddress[]>('/customer/addresses')])
      if (!detail.allowedActions?.includes('edit')) {
        showNotice('This order can no longer be edited.', 'error')
        await loadCustomerPage()
        return
      }
      selectedOrder.value = detail
      addresses.value = addressResult
      orderEditAddressId.value = (addressResult.find(item => item.id === Number(detail.address?.id)) || addressResult.find(item => item.isDefault) || addressResult[0])?.id || null
      orderNote.value = detail.buyerNote || ''
      orderEditItems.value = (detail.items || []).map(item => ({ ...item }))
      orderEditOpen.value = true
    } catch (error) { showFailure(error) }
  })
}

async function saveOrderEdit() {
  if (!selectedOrder.value?.id || !orderEditAddressId.value) { showNotice('Select a shipping address.', 'error', 'Select a saved shipping address before continuing.'); return }
  if (orderEditItems.value.some(item => item.productType !== 'gift' && (!Number.isInteger(Number(item.quantity)) || Number(item.quantity) < 1))) {
    showNotice('Enter a whole quantity of at least 1.', 'error', 'Review the required fields, correct their values, and submit again.')
    return
  }
  await runPending('order-edit', async () => {
    try {
      selectedOrder.value = await request<AnyRecord>(`/customer/orders/${selectedOrder.value.id}`, { method: 'PATCH', body: JSON.stringify({ addressId: orderEditAddressId.value, buyerNote: orderNote.value, items: orderEditItems.value.map(item => ({ productId: item.productId, quantity: Number(item.quantity), unitPrice: null })), expectedUpdatedAt: selectedOrder.value.updatedAt }) })
      invalidateCustomerOrderSummary()
      orderEditOpen.value = false
      orderDetailOpen.value = false
      await loadCustomerPage()
    } catch (error) {
      showFailure(error)
      if (error instanceof ApiRequestError && error.code === 'ORDER_COMMISSION_SETTLED') {
        try {
          const detail = await request<CustomerOrder>(`/customer/orders/${selectedOrder.value.id}`)
          selectedOrder.value = detail
          orderEditItems.value = (detail.items || []).map(item => ({ ...item }))
          if (!detail.allowedActions?.includes('edit')) orderEditOpen.value = false
          await loadCustomerPage()
        } catch (refreshError) { showFailure(refreshError) }
      }
    }
  })
}

function updateOrderItemQuantity(productId: number, quantity: number) {
  if (!selectedOrder.value.allowedActions?.includes('edit_items')) return
  const item = orderEditItems.value.find(candidate => candidate.productId === productId)
  if (item) item.quantity = quantity
}

async function openPayment(order: CustomerOrder) {
  if (order.status === 'cancelled') { showNotice('Payment is not available for a cancelled order.', 'error'); return }
  orderDetailOpen.value = false
  customerPaymentOpen.value = false
  await runPending('payment-channel-load', async () => {
    try {
      selectedOrder.value = order
      const [detail, paymentOptions] = await Promise.all([
        request<CustomerOrder>(`/customer/orders/${order.id}`),
        request<CustomerPaymentOptions>('/customer/payment-options'),
      ])
      if (customerPage.value !== 'orders' || selectedOrder.value?.id !== order.id) return
      if (detail.status === 'cancelled' || !['unpaid', 'partially_paid'].includes(detail.paymentStatus)) {
        await loadCustomerPage()
        showNotice('Payment is not available for this order.', 'error')
        return
      }
      selectedOrder.value = detail
      paymentAmount.value = String(detail.remainingAmount)
      applyCustomerPaymentOptions(paymentOptions, detail)
      if (!channels.value.length && !onlinePaymentLinkEnabled.value && !detail.paymentLinkRequested) {
        showNotice(t('No active payment methods are available. You can pay later from Order History.'), 'error')
        return
      }
      transactionNo.value = ''
      customerPaymentOpen.value = true
    } catch (error) { showFailure(error) }
  })
}

function applyCustomerPaymentOptions(options: CustomerPaymentOptions, order: Pick<CustomerOrder, 'paymentLinkRequested'>) {
  cryptoEnabled.value = options.cryptoEnabled
  onlinePaymentLinkEnabled.value = options.onlinePaymentLinkEnabled
  channels.value = options.channels
  paymentChannel.value = channels.value[0]?.id || null
  paymentMethod.value = Boolean(order.paymentLinkRequested)
    || (onlinePaymentLinkEnabled.value && !channels.value.length)
    ? 'link'
    : 'crypto'
}

async function requestPaymentLink() {
  if (!selectedOrder.value?.id || selectedOrder.value.status === 'cancelled' || !onlinePaymentLinkEnabled.value) return
  await runPending('payment-link-request', async () => {
    try {
      selectedOrder.value = await orderPaymentApi.requestLink<AnyRecord>(selectedOrder.value.id)
      paymentMethod.value = 'link'
      invalidateCustomerOrderSummary()
      if (customerPage.value === 'orders') await loadCustomerPage()
    } catch (error) { showFailure(error) }
  })
}

async function submitPayment() {
  if (selectedOrder.value?.status === 'cancelled') { showNotice('Payment is not available for a cancelled order.', 'error'); return }
  if (paymentMethod.value !== 'crypto') return
  if (!selectedOrder.value || !paymentChannel.value || !transactionNo.value.trim()) { showNotice('Choose a channel and enter the transaction ID.', 'error', 'Choose a payment channel and enter the transaction ID or transaction hash.'); return }
  if (!validPaymentAmount(paymentAmount.value.trim(), selectedOrder.value.remainingAmount)) { showNotice(t('Enter a positive amount with at most two decimals, within the remaining balance.'), 'error'); return }
  await runPending('payment-submit', async () => {
    try {
      selectedOrder.value = await orderPaymentApi.submit<CustomerOrder>(selectedOrder.value.id, { channelId: paymentChannel.value!, transactionNo: transactionNo.value.trim(), declaredAmount: paymentAmount.value.trim(), expectedTotalAmount: selectedOrder.value.total, expectedUpdatedAt: selectedOrder.value.updatedAt })
      invalidateCustomerOrderSummary()
      customerPaymentOpen.value = false
      if (customerPage.value === 'cart' && checkoutStep.value === 3) paymentSuccessOpen.value = true
      else if (customerPage.value === 'orders') await loadCustomerPage()
    } catch (error) {
      if (!(error instanceof ApiRequestError) || ['ORDER_CHANGED', 'TOTAL_MISMATCH', 'PAYMENT_NOT_ALLOWED', 'PAYMENT_EXCEEDS_REMAINING'].includes(error.code)) {
        try {
          selectedOrder.value = await request<CustomerOrder>(`/customer/orders/${selectedOrder.value.id}`)
          if (!['unpaid', 'partially_paid'].includes(selectedOrder.value.paymentStatus) || selectedOrder.value.status === 'cancelled') customerPaymentOpen.value = false
        } catch { /* Preserve the original payment error if refreshing fails. */ }
      }
      showFailure(error)
    }
  })
}

async function cancelOrder(order: CustomerOrder) {
  if (!window.confirm(`Cancel ${order.orderNo}?`)) return
  await runPending('order-cancel', async () => {
    try { selectedOrder.value = await request<AnyRecord>(`/customer/orders/${order.id}/cancel`, { method: 'POST', body: JSON.stringify({ reason: 'Cancelled from customer order desk', expectedUpdatedAt: order.updatedAt }) }); invalidateCustomerOrderSummary(); await loadCustomerPage() }
    catch (error) {
      showFailure(error)
      if (error instanceof ApiRequestError && error.code === 'ORDER_COMMISSION_SETTLED') await loadCustomerPage()
    }
  })
}

async function saveProfile() {
  await runPending('profile-save', async () => {
    try { customer.value = await request<CustomerProfile>('/customer/me/profile', { method: 'PATCH', body: JSON.stringify({ name: profileName.value }) }) }
    catch (error) { showFailure(error) }
  })
}

function addressFieldError(field: AddressField) {
  const value = addressDraft[field].trim()
  if (!value) return ({
    name: 'Recipient is required.',
    street: 'Street address is required.',
    city: 'City is required.',
    postalCode: 'Postal code is required.',
    phone: 'Phone number is required.',
  } satisfies Record<AddressField, string>)[field]
  if (field === 'postalCode' && !/^[A-Za-z0-9](?:[A-Za-z0-9 -]{0,10}[A-Za-z0-9])?$/.test(value)) return 'Enter a valid postal code.'
  if (field === 'phone') {
    const digits = value.replace(/\D/g, '')
    if (!/^[0-9+(). -]+$/.test(value) || value.length > 32 || digits.length < 6 || digits.length > 15) return 'Enter a valid phone number.'
  }
  return ''
}

function validateAddressField(field: AddressField) {
  addressErrors[field] = addressFieldError(field)
}

function touchAddressField(field: AddressField) {
  addressTouched[field] = true
  validateAddressField(field)
}

function updateAddressField(field: AddressField, event: Event) {
  addressDraft[field] = (event.target as HTMLInputElement).value
  if (addressTouched[field]) validateAddressField(field)
}

function validateAddressForm() {
  let valid = true
  for (const field of addressFields) {
    addressTouched[field] = true
    validateAddressField(field)
    if (addressErrors[field]) valid = false
  }
  return valid
}

function resetAddressValidation() {
  for (const field of addressFields) {
    addressErrors[field] = ''
    addressTouched[field] = false
  }
}

async function addAddress() {
  if (!validateAddressForm()) return
  await runPending('address-save', async () => {
    try {
      const editingId = addressEditingId.value
      const returnToCheckout = addressFormReturnToCheckout.value
      let createdAddressId: number | null = null
      if (editingId) await request(`/customer/addresses/${editingId}`, { method: 'PUT', body: JSON.stringify(addressDraft) })
      else createdAddressId = (await request<{ id: number }>('/customer/addresses', { method: 'POST', body: JSON.stringify(addressDraft) })).id
      addresses.value = await request<CustomerAddress[]>('/customer/addresses')
      selectedAddressId.value = returnToCheckout && createdAddressId !== null
        ? createdAddressId
        : (addresses.value.find(item => item.isDefault) || addresses.value[0])?.id || null
      if (returnToCheckout && orderEditOpen.value && createdAddressId !== null) orderEditAddressId.value = createdAddressId
      checkoutQuote.value = null
      addressPickerOpen.value = false
      addressFormOpen.value = false
      addressFormReturnToCheckout.value = false
      addressEditingId.value = null
    } catch (error) { showFailure(error) }
  })
}

function openAddressForm(address?: CustomerAddress, returnToCheckout = false) {
  addressEditingId.value = address?.id || null
  addressFormReturnToCheckout.value = returnToCheckout
  Object.assign(addressDraft, address ? { ...address } : { name: '', street: '', apt: '', city: '', region: '', postalCode: '', phone: '', isDefault: addresses.value.length === 0 })
  resetAddressValidation()
  addressFormOpen.value = true
}

function openCheckoutAddressForm() {
  checkoutQuote.value = null
  addressPickerOpen.value = false
  openAddressForm(undefined, true)
}

async function deleteAddress(address: CustomerAddress) {
  if (!window.confirm(t('Delete this shipping address?'))) return
  await runPending('address-save', async () => {
    try { await request(`/customer/addresses/${address.id}`, { method: 'DELETE' }); addresses.value = await request<CustomerAddress[]>('/customer/addresses') }
    catch (error) { showFailure(error) }
  })
}

async function makeDefaultAddress(address: CustomerAddress) {
  await runPending('address-save', async () => {
    try { await request(`/customer/addresses/${address.id}/default`, { method: 'PUT' }); addresses.value = await request<CustomerAddress[]>('/customer/addresses'); selectedAddressId.value = address.id }
    catch (error) { showFailure(error) }
  })
}

async function openCertificate(cert: AnyRecord) {
  const certificateTab = window.open('about:blank', '_blank')
  if (!certificateTab) {
    showNotice('Allow pop-ups to open the certificate in a new tab.', 'error', 'Enable pop-ups for this site, then click the COA link again.')
    return
  }
  certificateTab.opener = null
  try {
    const currentArea = area.value === 'admin' ? 'admin' : 'customer'
    const result = await request<{ url: string }>(`/${currentArea}/files/${cert.fileUuid}/signed-url`, {}, currentArea)
    certificateTab.location.replace(result.url)
  } catch (error) {
    certificateTab.close()
    showFailure(error)
  }
}

async function changePassword() {
  if (!oldPassword.value || !newPassword.value || newPassword.value !== passwordConfirmation.value) {
    showNotice('Enter the current password and matching new password.', 'error', 'Enter your current password and make the new password match in both fields.')
    return
  }
  await runPending('password-change', async () => {
    try {
      await request('/customer/me/password', { method: 'PUT', body: JSON.stringify({ oldPassword: oldPassword.value, newPassword: newPassword.value, confirmation: passwordConfirmation.value }) })
      oldPassword.value = ''
      newPassword.value = ''
      passwordConfirmation.value = ''
      showNotice('Password changed. Sign in again next time.')
    } catch (error) { showFailure(error) }
  })
}

async function loadAdminSession() {
  const result = await api.me('admin')
  const token = getToken('admin')
  if (token) rememberSellerSession(result as SellerIdentity, token)
  currentMerchant.value = result.merchant as MerchantIdentity
  applyMerchantBranding(currentMerchant.value.name, currentMerchant.value.logoUrl)
  if (!currentMerchant.value.commissionEnabled && adminBillTypeFilter.value === 'commission_payable') {
    adminBillTypeFilter.value = 'all'
    adminBillStatusFilter.value = 'all'
  }
  adminMember.value = result.member as AnyRecord
  adminPermissions.value = Array.isArray(result.permissions) ? result.permissions.map(String) : []
  if (!adminNavItems.value.some(item => item.key === adminPageKey.value)) {
    const firstPage = adminNavItems.value[0]?.key
    if (firstPage) {
      adminPage.value = ({ commissions: 'finance', billing: 'finance', coas: 'certificates', users: 'customers', payments: 'settings', permissions: 'members' } as Record<string, string>)[firstPage] || firstPage
      adminMode.value = firstPage === 'billing' ? 'billing' : firstPage === 'commissions' ? 'finance' : ''
      history.replaceState({}, '', `/admin/${firstPage}`)
    }
  }
  await loadAdminPage()
}

async function loadAdminPage() {
  if (!loggedIn.value || area.value !== 'admin') return
  if (adminPage.value === 'orders' || adminPage.value === 'finance') {
    const billing = adminPage.value === 'finance' && adminMode.value === 'billing'
    if ((billing ? adminBillDateFilter.value : adminDateFilter.value) === 'custom' && !validateAdminDateRange(billing ? 'bills' : 'orders')) return
  }
  busy.value = true
  try {
    if (adminPage.value === 'dashboard') dashboard.value = await request<AnyRecord>('/admin/dashboard', {}, 'admin')
    if (adminPage.value === 'orders') {
      const query = new URLSearchParams()
      if (adminSearch.value.trim()) query.set('q', adminSearch.value.trim())
      if (adminShippingFilter.value !== 'all') query.set('status', adminShippingFilter.value)
      const [from, to] = adminOrderDateRange()
      if (from) query.set('from', from.toISOString())
      if (to) query.set('to', to.toISOString())
      const selectedPaymentStatus = adminPaymentFilter.value
      if (selectedPaymentStatus !== 'all') query.set('paymentStatus', selectedPaymentStatus)
      query.set('page', String(adminOrderPage.value))
      const [orderPageResult] = await Promise.all([
        request<Page<AdminOrderListItem>>(`/admin/orders${query.size ? `?${query.toString()}` : ''}`, {}, 'admin'),
        loadCached(adminOrderSummaryCache, () => request<AnyRecord>('/admin/orders/summary', {}, 'admin'), value => { adminOrderSummary.value = value }),
      ])
      adminOrders.value = orderPageResult
    }
    if (adminPage.value === 'order-audit') {
      const query = new URLSearchParams({ result: auditResultFilter.value, page: String(auditPage.value) })
      if (auditSearch.value.trim()) query.set('q', auditSearch.value.trim())
      auditOrders.value = await request<Page>(`/admin/order-audits?${query.toString()}`, {}, 'admin')
    }
    if (adminPage.value === 'products') {
      const query = new URLSearchParams({ page: String(adminProductPage.value) })
      if (adminProductSort.value.by !== 'id') {
        query.set('sortBy', adminProductSort.value.by)
        query.set('sortDirection', adminProductSort.value.direction)
      }
      if (adminSearch.value.trim()) query.set('q', adminSearch.value.trim())
      if (adminCategoryFilter.value !== 'all') query.set('categoryId', adminCategoryFilter.value)
      if (adminProductType.value !== 'all') query.set('productType', adminProductType.value)
      if (adminProductStatus.value !== 'all') query.set('active', String(adminProductStatus.value === 'active'))
      const [products] = await Promise.all([
        request<Page>(`/admin/products?${query.toString()}`, {}, 'admin'),
        loadCached(adminCategoriesCache, () => request<AnyRecord[]>('/admin/categories', {}, 'admin'), value => { adminCategories.value = value }),
      ])
      adminProducts.value = products
    }
    if (adminPage.value === 'customers') {
      const query = new URLSearchParams({ page: String(adminCustomerPage.value) })
      if (adminSearch.value.trim()) query.set('q', adminSearch.value.trim())
      if (adminUserType.value !== 'all') query.set('customerType', adminUserType.value)
      if (adminUserPayment.value === 'unpaid') query.set('unpaid', 'true')
      adminCustomers.value = await request<Page<AdminCustomer>>(`/admin/customers?${query.toString()}`, {}, 'admin')
      adminCustomerPage.value = adminCustomers.value.page
    }
    if (adminPage.value === 'finance' && adminMode.value === 'billing') {
      const query = new URLSearchParams({ page: String(adminBillPage.value) })
      if (adminBillTypeFilter.value !== 'all') query.set('type', adminBillTypeFilter.value)
      if (adminBillTypeFilter.value !== 'all' && adminBillStatusFilter.value !== 'all') query.set('status', adminBillStatusFilter.value)
      if (adminSearch.value.trim()) query.set('q', adminSearch.value.trim())
      const [from, to] = adminBillingDateRange()
      if (from) query.set('from', from.toISOString())
      if (to) query.set('to', to.toISOString())
      const [billPage] = await Promise.all([
        request<Page<FinanceListItem>>(`/admin/bills?${query.toString()}`, {}, 'admin'),
        loadCached(billSummaryCache, () => request<BillingSummary>('/admin/bills/summary', {}, 'admin'), value => { billSummary.value = value }),
      ])
      bills.value = billPage
    }
    if (adminPage.value === 'finance' && adminMode.value !== 'billing') {
      const query = new URLSearchParams({ page: String(adminCommissionPage.value) })
      if (adminCommissionStatus.value !== 'all') query.set('status', adminCommissionStatus.value)
      if (adminSearch.value.trim()) query.set('q', adminSearch.value.trim())
      const [from, to] = adminOrderDateRange()
      if (from) query.set('from', from.toISOString())
      if (to) query.set('to', to.toISOString())
      const [commissionPage] = await Promise.all([
        request<Page<FinanceListItem>>(`/admin/commissions?${query.toString()}`, {}, 'admin'),
        loadCached(commissionSummaryCache, () => request<AnyRecord>('/admin/commissions/summary', {}, 'admin'), value => { commissionSummary.value = value }),
      ])
      commissions.value = commissionPage
    }
    if (adminPage.value === 'settings') {
      adminChannels.value = await request<Page>('/admin/payment-channels', {}, 'admin')
    }
    if (adminPage.value === 'certificates') {
      const query = new URLSearchParams({ page: String(adminCertificatePage.value) })
      if (adminSearch.value.trim()) query.set('q', adminSearch.value.trim())
      adminCertificates.value = await request<Page>(`/admin/certificates?${query.toString()}`, {}, 'admin')
      adminCertificatePage.value = adminCertificates.value.page
    }
    if (adminPage.value === 'categories') {
      await loadCached(adminCategoriesCache, () => request<AnyRecord[]>('/admin/categories', {}, 'admin'), value => { adminCategories.value = value })
    }
    if (adminPage.value === 'members') {
      await loadAdminMembers()
    }
  } catch (error) {
    if (!getToken('admin')) loggedIn.value = false
    else showNotice(errorText(error), 'error')
  } finally { busy.value = false }
}

async function confirmPayment(order: { id: number }) {
  try {
    const detail = await request<PaymentOrder>(`/admin/orders/${order.id}`, {}, 'admin')
    if ((parseAmountCents(detail.remainingAmount) ?? 0n) <= 0n) { paymentConfirmOpen.value = false; invalidateAdminOrderSummary(); await loadAdminPage(); showNotice('订单已付清，请查看收款记录。'); return }
    paymentConfirmOrder.value = detail
    paymentConfirmTransactionNo.value = detail.pendingPaymentSubmission?.transactionNo || ''
    paymentConfirmAmount.value = detail.pendingPaymentSubmission ? String(detail.pendingPaymentSubmission.declaredAmount) : ''
    paymentConfirmRemark.value = ''
    paymentConfirmError.value = ''
    paymentConfirmSubmitted.value = false
    Object.assign(paymentConfirmTouched, { amount: Boolean(detail.pendingPaymentSubmission && !validPaymentAmount(paymentConfirmAmount.value, detail.remainingAmount)), channel: false, transaction: false, remark: false })
    Object.assign(paymentConfirmServerErrors, { amount: '', channel: '', transaction: '', remark: '' })
    paymentConfirmStale.value = false
    paymentConfirmChannelId.value = detail.pendingPaymentSubmission?.channelId || null
    paymentConfirmChannels.value = []
    if (!detail.pendingPaymentSubmission) {
      paymentConfirmChannels.value = await request<Array<{ id: number; name: string; network: string }>>(`/admin/orders/${detail.id}/payment-channels`, {}, 'admin')
      paymentConfirmChannelId.value = detail.paymentLinkRequested && paymentConfirmChannels.value.some(channel => channel.id === 0)
        ? 0
        : paymentConfirmChannels.value[0]?.id ?? null
    }
    paymentConfirmOpen.value = true
  } catch (error) { showNotice(errorText(error), 'error') }
}

async function submitPaymentConfirmation() {
  const order = paymentConfirmOrder.value
  if (!order || paymentConfirmSubmitting.value || paymentConfirmStale.value) return
  paymentConfirmSubmitted.value = true
  paymentConfirmError.value = ''
  const invalidField = (Object.keys(paymentConfirmValidationErrors.value) as PaymentConfirmField[]).find(field => paymentConfirmValidationErrors.value[field])
  if (invalidField) {
    await nextTick()
    document.getElementById(`payment-confirm-${invalidField}`)?.focus()
    return
  }
  const transactionNo = paymentConfirmTransactionNo.value.trim()
  paymentConfirmSubmitting.value = true
  paymentConfirmError.value = ''
  try {
    await orderPaymentApi.confirm(order.id, {
      expectedTotalAmount: order.total, receivedAmount: paymentConfirmAmount.value.trim(),
      submissionId: order.pendingPaymentSubmission?.id || null, transactionNo,
      channelId: order.pendingPaymentSubmission ? null : paymentConfirmChannelId.value,
      internalNote: paymentConfirmRemark.value.trim() || null, expectedUpdatedAt: order.updatedAt,
    })
    paymentConfirmOpen.value = false
    invalidateAdminOrderSummary()
    await loadAdminPage()
    showNotice('本次收款已登记。')
  } catch (error) {
    paymentConfirmError.value = errorText(error)
    if (error instanceof ApiRequestError) {
      const fieldsByCode: Record<string, PaymentConfirmField> = {
        INVALID_PAYMENT_AMOUNT: 'amount', PAYMENT_EXCEEDS_REMAINING: 'amount',
        TRANSACTION_NO_REQUIRED: 'transaction', INVALID_TRANSACTION_NO: 'transaction',
        PAYMENT_CHANNEL_REQUIRED: 'channel', INVALID_PAYMENT_NOTE: 'remark', PAYMENT_DIFFERENCE_REASON_REQUIRED: 'remark',
      }
      const field = fieldsByCode[error.code]
      if (field) { paymentConfirmServerErrors[field] = paymentConfirmError.value; paymentConfirmError.value = '' }
    }
    // A transport failure may follow a committed receipt. Refresh before another submission.
    if (!(error instanceof ApiRequestError) || ['ORDER_CHANGED', 'TOTAL_MISMATCH', 'PAYMENT_SUBMISSION_CHANGED', 'PAYMENT_NOT_ALLOWED'].includes(error.code)) paymentConfirmStale.value = true
  } finally { paymentConfirmSubmitting.value = false }
}

async function parcelOrderUpdated(order: ParcelOrder) {
  if (financeOrderDetail.value?.id === order.id) financeOrderDetail.value = withSignedEventFiles(order)
  invalidateAdminOrderSummaries()
  await loadAdminPage()
}

function sellerFulfillmentLabel(status: string, summary?: FulfillmentSummary) {
  const partial = !['completed', 'cancelled'].includes(status) ? partialFulfillmentStage(summary) : null
  if (partial) return t({ packed: '部分打包', shipped: '部分发货', received: '部分收货' }[partial])
  return sellerStatusLabel(status)
}

async function createCategory() {
  try {
    const path = categoryEditingId.value ? `/admin/categories/${categoryEditingId.value}` : '/admin/categories'
    await request(path, { method: categoryEditingId.value ? 'PUT' : 'POST', body: JSON.stringify(categoryDraft) }, 'admin')
    invalidateAdminCategories()
    categoryDraft.name = ''
    categoryDraft.sortNo = 10
    categoryEditingId.value = null
    categoryModalOpen.value = false
    await loadAdminPage()
    showNotice('Category created.')
  } catch (error) { showNotice(errorText(error), 'error') }
}

function openCategory(category?: AnyRecord) {
  categoryEditingId.value = category?.id || null
  categoryDraft.expectedUpdatedAt = category?.updatedAt || null
  categoryDraft.name = category?.name || ''
  categoryDraft.sortNo = category?.sortNo || 10
  categoryDraft.active = category?.active !== false
  categoryModalOpen.value = true
}

async function toggleCategory(category: AnyRecord) {
  const action = t(category.active ? '停用' : '启用')
  if (!window.confirm(t('确定{0}类目“{1}”吗？', [action, category.name]))) return
  try { await request(`/admin/categories/${category.id}`, { method: 'PUT', body: JSON.stringify({ name: category.name, sortNo: category.sortNo, active: !category.active }) }, 'admin'); invalidateAdminCategories(); await loadAdminPage() } catch (error) { showNotice(errorText(error), 'error') }
}

async function deleteCategory(category: AnyRecord) {
  if (!window.confirm(t('确定删除类目“{0}”吗？', [category.name]))) return
  try { await request(`/admin/categories/${category.id}`, { method: 'DELETE' }, 'admin'); invalidateAdminCategories(); await loadAdminPage(); showNotice('Category deleted.') } catch (error) { showNotice(errorText(error), 'error') }
}

async function openCategoryProducts(category: AnyRecord) {
  categoryProductsCategory.value = category
  categoryProducts.value = { items: [], total: 0, page: 1, totalPages: 1 }
  categoryProductsPage.value = 1
  categoryProductsError.value = ''
  categoryProductsOpen.value = true
  await loadCategoryProducts(1)
}

async function loadCategoryProducts(page: number) {
  const category = categoryProductsCategory.value
  if (!category) return
  const sequence = ++categoryProductsRequestSequence
  categoryProductsLoading.value = true
  categoryProductsError.value = ''
  categoryProductsPage.value = page
  try {
    const query = new URLSearchParams({ categoryId: String(category.id), page: String(page) })
    const result = await request<Page>(`/admin/products?${query.toString()}`, {}, 'admin')
    if (sequence !== categoryProductsRequestSequence) return
    categoryProducts.value = result
    categoryProductsPage.value = result.page
  } catch (error) {
    if (sequence === categoryProductsRequestSequence) categoryProductsError.value = errorText(error)
  } finally {
    if (sequence === categoryProductsRequestSequence) categoryProductsLoading.value = false
  }
}

function setCategoryProductsPage(page: number) {
  if (categoryProductsLoading.value || page < 1 || page > categoryProducts.value.totalPages || page === categoryProductsPage.value) return
  void loadCategoryProducts(page)
}

function closeCategoryProducts() {
  categoryProductsRequestSequence++
  categoryProductsOpen.value = false
  categoryProductsCategory.value = null
  categoryProductsLoading.value = false
  categoryProductsError.value = ''
}

async function createMember() {
  memberFormSubmitted.value = true
  Object.keys(memberTouched).forEach(field => { memberTouched[field as keyof typeof memberTouched] = true })
  if (!memberFormValid.value) {
    return
  }
  try {
    const path = memberEditingId.value ? `/admin/members/${memberEditingId.value}` : '/admin/members'
    const editing = Boolean(memberEditingId.value)
    await request(path, { method: editing ? 'PUT' : 'POST', body: JSON.stringify({ ...memberDraft, account: memberDraft.account.trim(), name: memberDraft.name.trim() }) }, 'admin')
    memberDraft.account = ''
    memberDraft.name = ''
    memberDraft.password = ''
    memberDraft.confirmation = ''
    memberDraft.permissions = ['orders']
    memberEditingId.value = null
    memberModalOpen.value = false
    resetMemberValidation()
    await loadAdminPage()
    showNotice(editing ? '成员信息已更新。' : '成员已创建。')
  } catch (error) {
    if (error instanceof ApiRequestError && error.code === 'MEMBER_ACCOUNT_EXISTS') {
      showNotice('登录账号已被使用。', 'error', '请换一个尚未使用的登录账号。')
      return
    }
    showNotice(errorText(error), 'error')
  }
}

function openMember(member?: AdminMember) {

  memberEditingId.value = member?.id || null
  memberDraft.account = member?.account || ''
  memberDraft.name = member?.name || ''
  memberDraft.password = ''
  memberDraft.confirmation = ''
  memberDraft.permissions = Array.isArray(member?.permissions) ? [...member.permissions] : ['orders']
  resetMemberValidation()
  memberModalOpen.value = true
}

async function createCertificate() {
  if (!certificateDraft.name.trim() || !certificateDraft.testDate || (!certificateFile.value && !certificateCurrentFileUuid.value)) { showNotice('COA name, Test Date, and certificate file are required.', 'error'); return }
  if (!validDateInput(certificateDraft.testDate)) { showNotice('Enter a valid date in YYYY-MM-DD format.', 'error'); return }
  try {
    let fileUuid = certificateCurrentFileUuid.value
    let uploadToken: string | null = null
    if (certificateFile.value) {
      const form = new FormData()
      form.append('file', certificateFile.value)
      const uploaded = await request<AnyRecord>('/admin/files?purpose=coa&stage=coa', { method: 'POST', body: form }, 'admin')
      fileUuid = uploaded.fileUuid
      uploadToken = uploaded.uploadToken
    }
    const body = { ...certificateDraft, testDate: certificateDraft.testDate || null, fileUuid, uploadToken }
    const creating = !certificateEditingId.value
    let id = certificateEditingId.value
    if (id) await request(`/admin/certificates/${id}`, { method: 'PUT', body: JSON.stringify(body) }, 'admin')
    else id = (await request<AnyRecord>('/admin/certificates', { method: 'POST', body: JSON.stringify(body) }, 'admin')).id
    await request(`/admin/certificates/${id}/product`, { method: 'PUT', body: JSON.stringify({ productId: certificateDraft.productId ? Number(certificateDraft.productId) : null }) }, 'admin')
    certificateDraft.name = ''
    certificateDraft.lot = ''
    certificateDraft.purity = ''
    certificateDraft.fill = ''
    certificateDraft.testDate = ''
    certificateDraft.productId = ''
    certificateFile.value = null
    certificateCurrentFileUuid.value = ''
    certificateEditingId.value = null
    certificateModalOpen.value = false
    if (creating) adminCertificatePage.value = 1
    await loadAdminPage()
    showNotice('Certificate saved.')
  } catch (error) { showNotice(errorText(error), 'error') }
}

function openCertificateModal(certificate?: AnyRecord) {
  certificateEditingId.value = certificate?.id || null
  certificateDraft.expectedUpdatedAt = certificate?.updatedAt || null
  certificateDraft.name = certificate?.name || ''
  certificateDraft.lot = certificate?.lot || ''
  certificateDraft.purity = certificate?.purity || ''
  certificateDraft.fill = certificate?.fill || ''
  certificateDraft.testDate = certificate?.testDate || ''
  certificateDraft.productId = certificate?.productId ? String(certificate.productId) : ''
  certificateCurrentFileUuid.value = certificate?.fileUuid || ''
  certificateFile.value = null
  selectedCertificateProduct.value = certificate?.productId
    ? { id: certificate.productId, sku: certificate.sku, name: certificate.productName }
    : null
  certificateProductSearch.value = selectedCertificateProduct.value
    ? `${selectedCertificateProduct.value.sku} · ${selectedCertificateProduct.value.name}`
    : ''
  certificateProductOptions.value = []
  certificateProductOptionsOpen.value = false
  certificateModalOpen.value = true
  void loadCertificateProductOptions(certificate?.sku || '')
}

async function loadCertificateProductOptions(query = certificateProductSearch.value) {
  const sequence = ++certificateProductSearchSequence
  certificateProductOptionsLoading.value = true
  try {
    const params = new URLSearchParams()
    if (query.trim()) params.set('q', query.trim())
    const queryString = params.toString()
    const suffix = queryString ? `?${queryString}` : ''
    const path = certificateEditingId.value
      ? `/admin/certificates/${certificateEditingId.value}/product-options${suffix}`
      : `/admin/certificate-product-options${suffix}`
    const options = await request<AnyRecord[]>(path, {}, 'admin')
    if (sequence === certificateProductSearchSequence) certificateProductOptions.value = options
  } catch (error) {
    if (sequence === certificateProductSearchSequence) showNotice(errorText(error), 'error')
  } finally {
    if (sequence === certificateProductSearchSequence) certificateProductOptionsLoading.value = false
  }
}

function searchCertificateProducts() {
  const selected = selectedCertificateProduct.value
  if (selected && certificateProductSearch.value !== `${selected.sku} · ${selected.name}`) {
    selectedCertificateProduct.value = null
    certificateDraft.productId = ''
  }
  certificateProductOptionsOpen.value = true
  void loadCertificateProductOptions(certificateProductSearch.value)
}

async function refreshCertificateProducts() {
  if (certificateProductOptionsLoading.value) return
  certificateProductOptionsOpen.value = true
  const selected = selectedCertificateProduct.value
  await loadCertificateProductOptions(selected && certificateProductSearch.value === `${selected.sku} · ${selected.name}` ? selected.sku : certificateProductSearch.value)
}

function selectCertificateProduct(product: AnyRecord) {
  selectedCertificateProduct.value = product
  certificateDraft.productId = String(product.id)
  certificateProductSearch.value = `${product.sku} · ${product.name}`
  certificateProductOptionsOpen.value = false
}

async function openCertificateProduct(certificate: AnyRecord) {
  if (!certificate.productId) return
  certificateProductDetailOpen.value = true
  certificateProductDetailLoading.value = true
  certificateProductDetail.value = null
  try {
    certificateProductDetail.value = await request<AnyRecord>(`/admin/certificates/${certificate.id}/product`, {}, 'admin')
  } catch (error) {
    certificateProductDetailOpen.value = false
    showNotice(errorText(error), 'error')
  } finally {
    certificateProductDetailLoading.value = false
  }
}

async function deleteCertificate(certificate: AnyRecord) {
  if (!window.confirm(t('确定删除 COA“{0}”吗？', [certificate.name]))) return
  try {
    await request(`/admin/certificates/${certificate.id}`, { method: 'DELETE' }, 'admin')
    if (adminCertificates.value.items.length === 1 && adminCertificatePage.value > 1) adminCertificatePage.value -= 1
    await loadAdminPage()
    showNotice('Certificate deleted.')
  } catch (error) { showNotice(errorText(error), 'error') }
}

async function toggleProduct(product: AnyRecord) {
  const action = t(product.active ? '下架' : '上架')
  if (!window.confirm(t('确认{0}商品“{1}”吗？', [action, product.name]))) return
  try { await request(`/admin/products/${product.id}/status`, { method: 'PATCH', body: JSON.stringify({ active: !product.active, expectedUpdatedAt: product.updatedAt }) }, 'admin'); await loadAdminPage() } catch (error) { showNotice(errorText(error), 'error') }
}

function openProduct(product?: AnyRecord) {
  productQuantityPrices.value = JSON.parse(JSON.stringify(product?.quantityPrices || []))
  productEditingId.value = product?.id || null
  productUpdatedAt.value = product?.updatedAt || null
  productInitialStock.value = product?.stock || 0
  productStockReason.value = ''
  productSelectedCoas.value = Array.isArray(product?.coas) ? [...product.coas] : []
  productCoaQuery.value = ''
  productCoaOptions.value = []
  productCoaOptionsOpen.value = false
  Object.assign(productDraft, {
    categoryId: String(product?.categoryId || adminCategories.value[0]?.id || ''),
    sku: product?.sku || '', name: product?.name || '', description: product?.description || '',
    productType: product?.productType || 'regular', active: product?.active !== false,
    basePrice: product?.basePrice || product?.price || '', costPrice: product?.costPrice || '', stockAvailable: product?.stock || 0,
    v1Price: product?.tierPrices?.find((x: AnyRecord) => x.tier === 'V1')?.price || '', v2Price: product?.tierPrices?.find((x: AnyRecord) => x.tier === 'V2')?.price || '', v3Price: product?.tierPrices?.find((x: AnyRecord) => x.tier === 'V3')?.price || '', v4Price: product?.tierPrices?.find((x: AnyRecord) => x.tier === 'V4')?.price || '', v5Price: product?.tierPrices?.find((x: AnyRecord) => x.tier === 'V5')?.price || '', v6Price: product?.tierPrices?.find((x: AnyRecord) => x.tier === 'V6')?.price || '',
  })
  productModalOpen.value = true
}

async function searchProductCoas() {
  productCoaLoading.value = true
  try {
    const query = new URLSearchParams()
    if (productCoaQuery.value.trim()) query.set('q', productCoaQuery.value.trim())
    const options = await request<AnyRecord[]>(`/admin/product-coa-options${query.size ? `?${query.toString()}` : ''}`, {}, 'admin')
    const selectedIds = new Set(productSelectedCoas.value.map(coa => coa.id))
    productCoaOptions.value = options.filter(coa => !selectedIds.has(coa.id))
  } catch (error) { showNotice(errorText(error), 'error') }
  finally { productCoaLoading.value = false }
}

function addProductCoa(coa: AnyRecord) {
  if (!productSelectedCoas.value.some(item => item.id === coa.id)) productSelectedCoas.value.push(coa)
  productCoaQuery.value = ''
  productCoaOptionsOpen.value = false
  productCoaOptions.value = []
}

function toggleProductSort(by: 'price' | 'stock') {
  adminProductSort.value = { by, direction: adminProductSort.value.by === by && adminProductSort.value.direction === 'asc' ? 'desc' : 'asc' }
  adminProductPage.value = 1
  void loadAdminPage()
}

async function saveProduct() {
  if (productSaving.value) return
  const quantityError = productDraft.productType === 'gift' ? '' : quantityPriceError(productQuantityPrices.value)
  if (quantityError) { showNotice(quantityError, 'error'); return }
  if (!productDraft.categoryId || !productDraft.sku.trim() || !productDraft.name.trim()) { showNotice('Category, SKU, and product name are required.', 'error'); return }
  if (productDraft.sku.trim().length > 64 || productDraft.name.trim().length > 200 || productDraft.description.length > 2000) { showNotice('SKU 最多 64 个字符，商品名称最多 200 个字符，描述最多 2,000 个字符。', 'error'); return }
  if (!/^\d+$/.test(String(productDraft.stockAvailable)) || Number(productDraft.stockAvailable) < 0 || Number(productDraft.stockAvailable) > 2147483647) { showNotice('库存必须是 0 到 2,147,483,647 之间的整数。', 'error'); return }
  const moneyInputs = [productDraft.costPrice, ...(productDraft.productType === 'gift' ? [] : [productDraft.basePrice, productDraft.v1Price, productDraft.v2Price, productDraft.v3Price, productDraft.v4Price, productDraft.v5Price, productDraft.v6Price])].filter(value => value !== '')
  if (moneyInputs.some(value => !/^\d+(\.\d{1,2})?$/.test(String(value)) || Number(value) < 0) || (productDraft.productType !== 'gift' && [productDraft.basePrice, productDraft.v1Price, productDraft.v2Price, productDraft.v3Price, productDraft.v4Price, productDraft.v5Price, productDraft.v6Price].some(value => value !== '' && Number(value) <= 0))) { showNotice('价格最多两位小数；正品价格必须大于 0，成本价不能小于 0。', 'error'); return }
  const targetStock = Number(productDraft.stockAvailable || 0)
  const stockChanged = Boolean(productEditingId.value) && targetStock !== productInitialStock.value
  const editing = Boolean(productEditingId.value)
  if (stockChanged && !productStockReason.value.trim()) { showNotice('Enter a reason for the stock adjustment.', 'error'); return }
  productSaving.value = true
  try {
    const tierPrices = productDraft.productType === 'gift' ? [] : (['V1', 'V2', 'V3', 'V4', 'V5', 'V6'] as const).flatMap(tier => {
      const price = productDraft[`${tier.toLowerCase()}Price` as 'v1Price' | 'v2Price' | 'v3Price' | 'v4Price' | 'v5Price' | 'v6Price']
      return price === '' ? [] : [{ tier, price: Number(price) }]
    })
    const basePrice = productDraft.productType === 'gift' ? 0 : Number(productDraft.basePrice || 0)
    const body = {
      ...productDraft,
      categoryId: Number(productDraft.categoryId),
      basePrice,
      costPrice: productDraft.costPrice ? Number(productDraft.costPrice) : null,
      stockAvailable: targetStock,
      stockReason: stockChanged ? productStockReason.value.trim() : null,
      expectedUpdatedAt: productUpdatedAt.value,
      tierPrices,
      quantityPrices: productDraft.productType === 'gift' ? [] : productQuantityPrices.value,
    }
    const path = productEditingId.value ? `/admin/products/${productEditingId.value}` : '/admin/products'
    const saved = await request<AnyRecord>(path, { method: productEditingId.value ? 'PUT' : 'POST', body: JSON.stringify(body) }, 'admin')
    const productId = productEditingId.value || saved.id
    if (canManageCoa.value) await request(`/admin/products/${productId}/certificates`, { method: 'PUT', body: JSON.stringify({ coaIds: productSelectedCoas.value.map(coa => coa.id) }) }, 'admin')
    productModalOpen.value = false
    if (!editing) {
      adminProductPage.value = 1
      adminSearch.value = ''
      adminCategoryFilter.value = 'all'
      adminProductType.value = 'all'
      adminProductStatus.value = 'all'
    }
    await loadAdminPage()
    showNotice(editing ? 'Product updated.' : 'Product created.')
    productEditingId.value = null
  } catch (error) { showNotice(errorText(error), 'error') }
  finally { productSaving.value = false }
}

async function deleteProduct(product: AnyRecord) {
  if (product.active) return
  if (!window.confirm(t('确定删除“{0}”吗？', [product.name]))) return
  try { await request(`/admin/products/${product.id}`, { method: 'DELETE', body: JSON.stringify({ expectedUpdatedAt: product.updatedAt }) }, 'admin'); adminProductPage.value = 1; await loadAdminPage(); showNotice('Product deleted.') } catch (error) { showNotice(errorText(error), 'error') }
}

async function saveUser() {
  if (userSaving.value) return
  customerFormSubmitted.value = true
  customerRequestError.value = ''
  clearCustomerServerErrors()
  if (!customerFormValid.value) {
    customerRequestError.value = '请检查下方标红字段，修正后再保存。'
    return
  }
  userSaving.value = true
  const editing = Boolean(userEditingId.value)
  try {
    const referrerId = userDraft.referrerId ? Number(userDraft.referrerId) : null
    const body = { ...userDraft, email: userDraft.email.trim().toLowerCase(), name: userDraft.name.trim(), referrerId, commissionRate: referrerId === null ? null : Number(userDraft.commissionRate) }
    const path = userEditingId.value ? `/admin/customers/${userEditingId.value}` : '/admin/customers'
    await request(path, { method: userEditingId.value ? 'PUT' : 'POST', body: JSON.stringify(body) }, 'admin')
  } catch (error) {
    const hasFieldError = applyCustomerServerError(error)
    if (!hasFieldError) {
      customerRequestError.value = error instanceof ApiRequestError
        ? `保存失败（${error.code || error.status}）：${error.message}`
        : `保存失败：${errorText(error)}`
    }
    userSaving.value = false
    return
  }
  closeUserModal()
  if (!editing) adminCustomerPage.value = 1
  userEditingId.value = null
  try {
    await loadAdminPage()
  } catch (error) {
    showNotice(`客户已保存，但列表刷新失败：${errorText(error)}`, 'error')
  } finally {
    userSaving.value = false
  }
}

async function loadReferrerOptions(page = 1, append = false) {
  const sequence = ++referrerRequestSequence
  referrerOptionsLoading.value = true
  try {
    const query = new URLSearchParams({ page: String(page) })
    if (referrerSearch.value.trim()) query.set('q', referrerSearch.value.trim())
    const result = await request<Page>(`/admin/customers?${query.toString()}`, {}, 'admin')
    if (sequence !== referrerRequestSequence) return
    const available = result.items.filter((customer: AnyRecord) => customer.active && customer.id !== userEditingId.value)
    referrerOptions.value = append
      ? [...referrerOptions.value, ...available.filter((customer: AnyRecord) => !referrerOptions.value.some(existing => existing.id === customer.id))]
      : available
    referrerOptionsPage.value = result.page
    referrerOptionsTotalPages.value = result.totalPages
  } catch (error) {
    if (sequence === referrerRequestSequence) showNotice(errorText(error), 'error')
  } finally {
    if (sequence === referrerRequestSequence) referrerOptionsLoading.value = false
  }
}

function openReferrerDropdown() {
  referrerDropdownOpen.value = !referrerDropdownOpen.value
  if (referrerDropdownOpen.value) {
    if (!referrerOptions.value.length) void loadReferrerOptions()
    void nextTick(() => referrerSearchInput.value?.focus())
  }
}

function closeReferrerDropdownOnFocusout(event: FocusEvent) {
  const control = event.currentTarget as HTMLElement
  if (event.relatedTarget instanceof Node && control.contains(event.relatedTarget)) return
  window.setTimeout(() => { referrerDropdownOpen.value = false }, 0)
}

function closeReferrerDropdownOnOutsidePointer(event: PointerEvent) {
  const target = event.target
  if (target instanceof Element && target.closest('.customer-referrer-select')) return
  referrerDropdownOpen.value = false
}

watch(referrerDropdownOpen, open => {
  if (open) document.addEventListener('pointerdown', closeReferrerDropdownOnOutsidePointer, true)
  else {
    document.removeEventListener('pointerdown', closeReferrerDropdownOnOutsidePointer, true)
    if (referrerSearchTimer !== undefined) window.clearTimeout(referrerSearchTimer)
    referrerRequestSequence += 1
    referrerOptionsLoading.value = false
  }
})

function searchReferrerOptions() {
  if (referrerSearchTimer !== undefined) window.clearTimeout(referrerSearchTimer)
  referrerSearchTimer = window.setTimeout(() => { void loadReferrerOptions() }, 250)
}

async function refreshReferrerOptions() {
  if (referrerOptionsLoading.value) return
  if (referrerSearchTimer !== undefined) window.clearTimeout(referrerSearchTimer)
  await loadReferrerOptions()
}

function chooseReferrer(customer: AnyRecord | null) {
  selectedReferrer.value = customer
  userDraft.referrerId = customer ? String(customer.id) : ''
  if (!userDraft.commissionRate) userDraft.commissionRate = '0'
  clearCustomerServerError('referrerId')
  clearCustomerServerError('commissionRate')
  referrerDropdownOpen.value = false
}

function openUser(user?: AnyRecord) {
  referrerRequestSequence += 1
  if (referrerSearchTimer !== undefined) window.clearTimeout(referrerSearchTimer)
  referrerOptions.value = []
  referrerSearch.value = ''
  referrerOptionsPage.value = 1
  referrerOptionsTotalPages.value = 1
  referrerOptionsLoading.value = false
  referrerDropdownOpen.value = false
  selectedReferrer.value = null
  userEditingId.value = user?.id || null
  customerFormSubmitted.value = false
  customerRequestError.value = ''
  clearCustomerServerErrors()
  Object.assign(userDraft, { email: user?.email || '', name: user?.name || '', password: '', confirmation: '', customerType: user?.customerType || 'partner', priceTier: user?.priceTier || 'V1', referrerId: user?.referrerId ? String(user.referrerId) : '', commissionRate: user?.commissionRate == null ? '0' : String(user.commissionRate) })
  userModalOpen.value = true
  if (user?.referrerId) {
    const referrerId = Number(user.referrerId)
    void request<AnyRecord>(`/admin/customers/${referrerId}`, {}, 'admin').then(customer => {
      if (userModalOpen.value && Number(userDraft.referrerId) === referrerId) selectedReferrer.value = customer
    }).catch(error => showNotice(errorText(error), 'error'))
  }
}

function closeUserModal() {
  userModalOpen.value = false
  referrerDropdownOpen.value = false
}

async function toggleUser(user: AnyRecord) {
  const action = t(user.active ? '禁用' : '启用')
  if (!window.confirm(t('确定{0}客户“{1}”（{2}）吗？', [action, user.name, user.userCode]))) return
  try { await request(`/admin/customers/${user.id}/status`, { method: 'PATCH', body: JSON.stringify({ active: !user.active }) }, 'admin'); await loadAdminPage() } catch (error) { showNotice(errorText(error), 'error') }
}

async function toggleMember(member: AdminMember) {

  const action = t(member.active ? '停用' : '启用')
  if (!window.confirm(t('确定{0}成员“{1}”（{2}）吗？', [action, member.name, member.account]))) return
  try {
    await request(`/admin/members/${member.id}/status`, { method: 'PATCH', body: JSON.stringify({ active: !member.active }) }, 'admin')
    await loadAdminPage()
  } catch (error) { showNotice(errorText(error), 'error') }
}

async function openCommissionRecord(item: AnyRecord) {
  if (item.status === 'settled') {
    try { commissionSettlementDetail.value = await request<AnyRecord>(`/admin/commissions/${item.id}/settlement`, {}, 'admin') } catch (error) { showNotice(errorText(error), 'error') }
    return
  }
  await openFinanceOrder(item, 'commission')
}

async function openFinanceOrder(item: AnyRecord, source: 'commission' | 'bill') {
  clearOrderCopyFeedback()
  try {
    const path = source === 'commission' ? `/admin/commissions/${item.id}/order` : `/admin/bills/${item.id}/order`
    const detail = withSignedEventFiles(await request<AnyRecord>(path, {}, 'admin'))
    commissionSettlementDetail.value = null
    customerDetailKind.value = null
    selectedAdminCustomer.value = null
    customerOrderDetailOpen.value = false
    adminOrderDetailOpen.value = false
    financeOrderDetail.value = detail
    financeOrderSource.value = item
  } catch (error) { showNotice(errorText(error), 'error') }
}

async function openSettlementOrder() {
  if (!commissionSettlementDetail.value?.accrualId) return
  await openFinanceOrder({ id: commissionSettlementDetail.value.accrualId, orderNo: commissionSettlementDetail.value.orderNo }, 'commission')
}

async function openCustomerOrders(customerItem: AnyRecord, page = 1, resetFilters = false, initialPaymentFilter?: string) {
  if (resetFilters) {
    resetAdminCustomerOrderSummaryCache()
    if (customerOrderSearchTimer !== undefined) window.clearTimeout(customerOrderSearchTimer)
    customerOrderDateFilter.value = 'all'
    customerOrderPaymentFilter.value = initialPaymentFilter || 'all'
    customerOrderShippingFilter.value = 'all'
    customerOrderSearch.value = ''
  }
  selectedAdminCustomer.value = customerItem
  customerDetailKind.value = 'orders'
  financeOrderDetail.value = null
  financeOrderSource.value = null
  const sequence = ++customerOrdersRequestSequence
  customerOrdersLoading.value = true
  try {
    const query = new URLSearchParams({ page: String(page) })
    if (customerOrderPaymentFilter.value !== 'all') query.set('paymentStatus', customerOrderPaymentFilter.value)
    if (customerOrderShippingFilter.value !== 'all') query.set('status', customerOrderShippingFilter.value)
    if (customerOrderSearch.value.trim()) query.set('q', customerOrderSearch.value.trim())
    const [from, to] = quickOrderDateRange(customerOrderDateFilter.value)
    if (from) query.set('from', from.toISOString())
    if (to) query.set('to', to.toISOString())
    const summaryQuery = new URLSearchParams(query)
    summaryQuery.delete('page')
    const [result, summary] = await Promise.all([
      request<Page>(`/admin/customers/${customerItem.id}/orders?${query.toString()}`, {}, 'admin'),
      loadAdminCustomerOrderSummary(Number(customerItem.id), summaryQuery),
    ])
    if (sequence === customerOrdersRequestSequence) {
      customerOrders.value = result
      customerOrderFilterSummary.value = summary
    }
  } catch (error) {
    if (sequence === customerOrdersRequestSequence) showNotice(errorText(error), 'error')
  } finally {
    if (sequence === customerOrdersRequestSequence) customerOrdersLoading.value = false
  }
}

function filterCustomerOrders() {
  if (selectedAdminCustomer.value) void openCustomerOrders(selectedAdminCustomer.value, 1)
}

function searchCustomerOrders() {
  if (customerOrderSearchTimer !== undefined) window.clearTimeout(customerOrderSearchTimer)
  customerOrderSearchTimer = window.setTimeout(filterCustomerOrders, 250)
}

function applyCustomerOrderSearch() {
  if (customerOrderSearchTimer !== undefined) window.clearTimeout(customerOrderSearchTimer)
  filterCustomerOrders()
}

function refreshCustomerOrders() {
  if (customerOrdersLoading.value) return
  resetAdminCustomerOrderSummaryCache()
  applyCustomerOrderSearch()
}

async function openCustomerReferrals(customerItem: AnyRecord, page = 1) {
  selectedAdminCustomer.value = customerItem
  customerDetailKind.value = 'referrals'
  try {
    customerReferrals.value = await request<Page>(`/admin/customers/${customerItem.id}/referrals?page=${page}`, {}, 'admin')
  } catch (error) { showNotice(errorText(error), 'error') }
}

async function openCustomerOrderDetail(customerItem: AnyRecord, orderItem: AnyRecord) {
  clearOrderCopyFeedback()
  try {
    financeOrderDetail.value = withSignedEventFiles(await request<AnyRecord>(`/admin/customers/${customerItem.id}/orders/${orderItem.id}`, {}, 'admin'))
    customerOrderDetailOpen.value = true
    financeOrderSource.value = orderItem
  } catch (error) { showNotice(errorText(error), 'error') }
}

async function openAdminOrderDetail(order: AnyRecord) {
  if (pendingActions.has('admin-order-detail')) return
  clearOrderCopyFeedback()
  financeOrderDetail.value = { id: order.id, orderNo: order.orderNo }
  financeOrderSource.value = null
  customerOrderDetailOpen.value = false
  adminOrderDetailOpen.value = true
  adminOrderDetailError.value = ''
  customerDetailKind.value = null
  await runPending('admin-order-detail', async () => {
    try {
      const detail = withSignedEventFiles(await request<AnyRecord>(`/admin/orders/${order.id}`, {}, 'admin'))
      if (adminOrderDetailOpen.value && financeOrderDetail.value?.id === order.id) financeOrderDetail.value = detail
    } catch (error) {
      if (adminOrderDetailOpen.value && financeOrderDetail.value?.id === order.id) adminOrderDetailError.value = errorText(error)
    }
  })
}

async function openAuditOrderDetail(order: AnyRecord) {
  await loadAuditOrder(order, false)
}

function openAuditOrderEdit(order: AnyRecord) {
  if (auditSubmitting.value || pendingActions.has('order-audit-load') || auditEditTarget.value) return
  auditEditTarget.value = { id: Number(order.id), orderNo: String(order.orderNo) }
}

async function auditOrderEdited(order: AuditEditableOrder) {
  if (auditOrder.value?.id === order.id) auditOrder.value = order
  auditEditTarget.value = null
  await loadAdminPage()
  showNotice('订单修改已保存。')
}

async function openAuditOrder(order: AnyRecord) {
  await loadAuditOrder(order, true)
}

async function loadAuditOrder(order: AnyRecord, reviewMode: boolean) {
  auditReviewMode.value = reviewMode
  auditRemark.value = ''
  auditOrder.value = order
  await runPending('order-audit-load', async () => {
    try {
      auditOrder.value = await request<AnyRecord>(`/admin/order-audits/${order.id}`, {}, 'admin')
      auditRemark.value = auditOrder.value.auditRemark || ''
    } catch (error) {
      auditOrder.value = null
      showNotice(errorText(error), 'error')
      await loadAdminPage()
    }
  })
}

function closeAuditOrder() {
  if (auditSubmitting.value) return
  auditOrder.value = null
  auditReviewMode.value = false
  auditRemark.value = ''
}

async function submitAuditDecision(result: 'approved' | 'rejected') {
  if (!auditOrder.value || auditSubmitting.value) return
  auditSubmitting.value = true
  try {
    await auditOrderApi.decide(auditOrder.value.id, {
      result,
      remark: auditRemark.value.trim() || null,
      expectedUpdatedAt: auditOrder.value.updatedAt,
    })
    auditOrder.value = null
    auditRemark.value = ''
    if (auditOrders.value.items.length === 1 && auditPage.value > 1) auditPage.value -= 1
    invalidateAdminOrderSummaries()
    await loadAdminPage()
    showNotice(result === 'approved' ? '订单审核已通过。' : '订单审核未通过，库存已退回。')
  } catch (error) {
    showNotice(errorText(error), 'error')
    if (error instanceof ApiRequestError && ['ORDER_CHANGED', 'ORDER_AUDIT_ALREADY_DECIDED'].includes(error.code)) {
      auditOrder.value = null
      await loadAdminPage()
    }
  } finally { auditSubmitting.value = false }
}

function orderItemQuantity(order: AnyRecord) {
  return (order.items || []).reduce((total: number, item: AnyRecord) => total + Number(item.quantity || 0), 0)
}

const adminFulfillmentStages: Array<{ key: FulfillmentStageKey; label: string }> = [
  { key: 'pending', label: '待处理' },
  { key: 'packed', label: '已打包' },
  { key: 'shipped', label: '已发货' },
  { key: 'received', label: '已收货' },
  { key: 'completed', label: '已完成' },
]
function fulfillmentStageTitle(stage: { key: FulfillmentStageKey; label: string }, status: string, summary?: FulfillmentSummary) {
  return !['completed', 'cancelled'].includes(status) && stage.key === partialFulfillmentStage(summary)
    ? sellerFulfillmentLabel(status, summary)
    : stage.label
}
function fulfillmentStageStateLabel(state: FulfillmentStageState) {
  return { done: '已完成', current: '当前状态', partial: '部分完成', upcoming: '待完成', stopped: '已停止' }[state]
}
function orderEventLabel(event: AnyRecord) {
  if (event.parcelId) {
    const parcel = (financeOrderDetail.value?.parcels as OrderParcel[] | undefined)?.find(item => item.id === event.parcelId)
    return `包裹 ${parcel?.parcelNo || ''} · ${sellerStatusLabel(event.toStatus || event.type)}`
  }
  if (event.type === 'parcels_repacked') return '重新分包'
  if (event.type === 'audit_discounted') return '订单优惠'
  if (event.toStatus && event.fromStatus !== event.toStatus) return sellerStatusLabel(event.toStatus)
  return ({ submitted: '订单提交', audit_approved: '审核通过', audit_rejected: '审核不通过', audit_updated: '审核前修改订单', customer_updated: '客户修改订单', seller_updated: '商家修改订单', cancelled: '订单取消', payment_submitted: '提交付款申报', payment_confirmed: '确认收款', payment_link_requested: '申请线上支付链接' } as Record<string, string>)[event.type] || sellerStatusLabel(event.toStatus || event.type || '')
}

function resetOrderCompletion() {
  orderCompletionRequestSequence += 1
  orderCompletionOpen.value = false
  orderCompletionTarget.value = null
  orderCompletionOrder.value = null
  orderCompletionLoading.value = false
  orderCompletionConfirmed.value = false
  orderCompletionNote.value = ''
  orderCompletionError.value = ''
  orderCompletionStale.value = false
}

async function beginOrderCompletion(order: { id: number; orderNo: string }) {
  if (!adminPermissions.value.includes('orders') || orderCompletionSaving.value || orderCompletionLoading.value) return
  resetOrderCompletion()
  orderCompletionTarget.value = { id: order.id, orderNo: order.orderNo }
  orderCompletionOpen.value = true
  await reloadOrderCompletion()
}

async function reloadOrderCompletion() {
  const target = orderCompletionTarget.value
  if (!target || orderCompletionSaving.value || orderCompletionLoading.value) return
  const sequence = ++orderCompletionRequestSequence
  orderCompletionLoading.value = true
  orderCompletionOrder.value = null
  orderCompletionConfirmed.value = false
  orderCompletionError.value = ''
  orderCompletionStale.value = false
  try {
    const order = await orderParcelApi.detail(target.id)
    if (sequence !== orderCompletionRequestSequence) return
    orderCompletionOrder.value = order
    if (!order.allowedActions.includes('complete')) orderCompletionError.value = t('订单当前状态不能完成，请刷新列表。')
  } catch (error) {
    if (sequence === orderCompletionRequestSequence) orderCompletionError.value = errorText(error)
  } finally {
    if (sequence === orderCompletionRequestSequence) orderCompletionLoading.value = false
  }
}

function closeOrderCompletion() {
  if (!orderCompletionSaving.value) resetOrderCompletion()
}

async function completeAdminOrder() {
  if (!canCompleteAdminOrder.value || !orderCompletionOpen.value || orderCompletionSaving.value || orderCompletionStale.value) return
  if (!orderCompletionConfirmed.value) { orderCompletionError.value = t('请勾选确认后提交。'); return }
  const order = orderCompletionOrder.value
  if (!order) return
  orderCompletionSaving.value = true
  orderCompletionError.value = ''
  try {
    const updated = await orderParcelApi.complete(order.id, orderCompletionNote.value.trim() || null, order.updatedAt)
    resetOrderCompletion()
    await parcelOrderUpdated(updated)
    showNotice('订单已完成。')
  } catch (error) {
    orderCompletionError.value = errorText(error)
    // The response may be lost after completion; reload before submitting again.
    orderCompletionStale.value = true
  } finally { orderCompletionSaving.value = false }
}

function closeFinanceOrderDetail() {
  clearOrderCopyFeedback()
  financeOrderDetail.value = null
  financeOrderSource.value = null
  orderImagePreviewUrl.value = ''
  customerOrderDetailOpen.value = false
  adminOrderDetailOpen.value = false
  adminOrderDetailError.value = ''
}

function closeAdminDetail() {
  clearOrderCopyFeedback()
  customerOrdersRequestSequence += 1
  resetAdminCustomerOrderSummaryCache()
  customerOrdersLoading.value = false
  if (customerOrderSearchTimer !== undefined) window.clearTimeout(customerOrderSearchTimer)
  commissionSettlementDetail.value = null
  financeOrderDetail.value = null
  financeOrderSource.value = null
  customerOrderDetailOpen.value = false
  customerDetailKind.value = null
  selectedAdminCustomer.value = null
}

function openPaymentChannel(channel?: AnyRecord) {
  paymentEditingId.value = channel?.id || null
  Object.assign(channelDraft, { name: channel?.name || '', currencyCode: channel?.currencyCode || 'USDT', network: channel?.network || '', receivingAddress: channel?.receivingAddress || '', instructions: channel?.instructions || '', additionalRate: channel?.additionalRate == null ? null : Number(channel.additionalRate), qrFileUuid: channel?.qrFileUuid || '', active: channel?.active !== false, sortNo: channel?.sortNo || 10 })
  channelQrFile.value = null
  paymentModalOpen.value = true
}

function openPaymentQr(channel: AnyRecord) {
  const image = adminPaymentQrUrls[channel.id]
  if (!image || image.fileUuid !== channel.qrFileUuid) return
  paymentQrPreview.value = { url: image.url, name: channel.name }
}

async function savePaymentChannel() {
  if (!channelDraft.name.trim() || !channelDraft.network.trim() || !channelDraft.receivingAddress.trim()) { showNotice('Channel name, network, and receiving address are required.', 'error'); return }
  if (channelDraft.additionalRate !== null && channelDraft.additionalRate !== '') {
    const rate = Number(channelDraft.additionalRate)
    if (!Number.isFinite(rate) || rate < 0 || rate > 100 || Math.abs(rate * 100 - Math.round(rate * 100)) > 1e-8) { showNotice('Additional rate must be between 0 and 100 with at most two decimal places.', 'error'); return }
  }
  try {
    if (channelQrFile.value) {
      const form = new FormData()
      form.append('file', channelQrFile.value)
      const uploaded = await request<AnyRecord>('/admin/files?purpose=payment_channel_qr', { method: 'POST', body: form }, 'admin')
      channelDraft.qrFileUuid = uploaded.fileUuid
    }
    if (!channelDraft.qrFileUuid) { showNotice('Upload a payment QR code before saving.', 'error'); return }
    const path = paymentEditingId.value ? `/admin/payment-channels/${paymentEditingId.value}` : '/admin/payment-channels'
    await request(path, { method: paymentEditingId.value ? 'PUT' : 'POST', body: JSON.stringify({ ...channelDraft, additionalRate: channelDraft.additionalRate === '' ? null : channelDraft.additionalRate }) }, 'admin')
    paymentModalOpen.value = false
    await loadAdminPage()
    showNotice(paymentEditingId.value ? 'Payment channel updated.' : 'Payment channel created.')
    paymentEditingId.value = null
  } catch (error) { showNotice(errorText(error), 'error') }
}

function changeLanguage() {
  localStorage.setItem(area.value === 'admin' ? 'dailyactivity.admin.language' : 'dailyactivity.language', language.value)
}

async function deletePaymentChannel(channel: AnyRecord) {
  if (!window.confirm(t('确定删除“{0}”吗？', [channel.name]))) return
  try { await request(`/admin/payment-channels/${channel.id}`, { method: 'DELETE' }, 'admin'); await loadAdminPage(); showNotice('Payment channel deleted.') } catch (error) { showNotice(errorText(error), 'error') }
}

const adminPageKey = computed(() => adminMode.value === 'billing' ? 'billing' : adminMode.value === 'finance' ? 'commissions' : ({ certificates: 'coas', customers: 'users', settings: 'payments', members: 'permissions' } as Record<string, string>)[adminPage.value] || adminPage.value)
function navLabel(page: string) { return ({ merchants: '商户管理', dashboard: '概览', 'order-audit': '订单审核', orders: '订单管理', products: '商品管理', customers: '客户管理', users: '客户管理', finance: '佣金管理', commissions: '佣金管理', billing: '账单管理', certificates: 'COA 管理', coas: 'COA 管理', categories: '类目管理', members: '权限管理', permissions: '权限管理', settings: '收款渠道', payments: '收款渠道' }[page] || page) }
function adminDescription(page: string) { return ({ merchants: '开通商户并管理商城配置', dashboard: '查看商家运营概览', 'order-audit': '审核客户新提交的订单', orders: '查看并处理已审核订单', products: '管理本商户商品、库存和上架状态', customers: '查看客户账户和采购记录', users: '查看客户账户和采购记录', finance: '查看佣金记录和结算状态', commissions: '查看佣金记录和结算状态', billing: '查看账单和收款状态', certificates: '维护本商户 COA 证书', coas: '维护本商户 COA 证书', categories: '维护本商户商品类目', members: '管理后台成员和权限', permissions: '管理后台成员和权限', settings: '配置收款渠道', payments: '配置收款渠道' }[page] || 'YTPEPS 商家管理后台') }
const adminHeaderTitle = computed(() => navLabel(adminPageKey.value))
const adminHeaderDescription = computed(() => adminDescription(adminPageKey.value))
const adminBillTypeOptions = computed(() => currentMerchant.value?.commissionEnabled
  ? [['all', t('全部')], ['order_receivable', t('下单')], ['commission_payable', t('结佣')]]
  : [['all', t('全部')], ['order_receivable', t('下单')]])
const adminNavItems = computed(() => [
  { key: 'order-audit', label: '订单审核' },
  { key: 'orders', label: '订单管理' },
  { key: 'commissions', label: '佣金管理' },
  { key: 'billing', label: '账单管理' },
  { key: 'products', label: '商品管理' },
  { key: 'coas', label: 'COA 管理' },
  { key: 'users', label: '客户管理' },
  { key: 'categories', label: '类目管理' },
  { key: 'payments', label: '收款渠道' },
  { key: 'permissions', label: '权限管理' },

].filter(item => {
  if (item.key === 'commissions' && !currentMerchant.value?.commissionEnabled) return false
  const permission = ({ 'order-audit': 'order_audit', commissions: 'commissions', billing: 'bills', coas: 'certificates', users: 'customers', payments: 'payment_channels', permissions: 'members' } as Record<string, string>)[item.key] || item.key
  return adminPermissions.value.includes(permission)
}))
const navSymbols: Record<string, string> = { merchants: '▦', dashboard: '◒', 'order-audit': '✓', orders: '▤', products: '◇', users: '◎', commissions: '∿', billing: '▧', coas: '⌁', categories: '⌘', permissions: '◌', payments: '＋' }

function syncRoute() {
  area.value = location.pathname.startsWith('/admin') ? 'admin' : 'customer'
  if (area.value === 'admin') {
    const routePage = location.pathname.split('/')[2] || 'orders'
    adminMode.value = routePage === 'billing' || routePage === 'finance' || routePage === 'commissions' ? (routePage === 'billing' ? 'billing' : 'finance') : ''
    adminPage.value = ({ billing: 'finance', finance: 'finance', commissions: 'finance', coas: 'certificates', users: 'customers', payments: 'settings', permissions: 'members' } as Record<string, string>)[routePage] || routePage
    if (routePage === 'orders') invalidateRequestCache(adminOrderSummaryCache)
    if (adminPage.value === 'finance' && adminMode.value === 'billing') invalidateRequestCache(billSummaryCache)
    if (adminPage.value === 'finance' && adminMode.value !== 'billing') invalidateRequestCache(commissionSummaryCache)
    if (routePage === 'categories') invalidateAdminCategories()
  } else {
    const routePage = location.pathname.split('/')[1] || 'products'
    customerPage.value = routePage === 'security' || routePage === 'referrals' ? 'account' : routePage
    accountTab.value = routePage === 'security' || routePage === 'referrals' ? routePage : 'profile'
    if (routePage === 'orders') invalidateCustomerOrderSummary()
    if (routePage === 'referrals') invalidateCustomerReferralSummary()
  }
}

onMounted(async () => {
  addEventListener('storage', event => {
    if (event.key === null || (event.key === `dailyactivity.${area.value}.token` && event.oldValue !== event.newValue)) window.location.reload()
  })
  addEventListener('authentication-cleared', event => {
    if ((event as CustomEvent<Area>).detail === area.value && loggedIn.value) window.location.reload()
  })
  if (area.value === 'admin' && sessionStorage.getItem('dailyactivity.admin.logout-unconfirmed')) {
    sessionStorage.removeItem('dailyactivity.admin.logout-unconfirmed')
    loginError.value = '本机登录已清除，服务端退出未确认；其他设备可能仍保持登录。'
  }
  syncRoute()
  addEventListener('popstate', () => {
    syncRoute()
    void (area.value === 'admin' ? loadAdminPage() : loadCustomerPage())
  })
  if (!loggedIn.value) return
  try { if (area.value === 'customer') await loadCustomerSession(); else await loadAdminSession() } catch (error) {
    if (error instanceof ApiRequestError && error.status === 401) loggedIn.value = false
    else showFailure(error)
  }
})

let orderSearchTimer: number | undefined
watch(orderSearch, () => {
  window.clearTimeout(orderSearchTimer)
  orderSearchTimer = window.setTimeout(() => {
    orderPage.value = 1
    if (area.value === 'customer' && customerPage.value === 'orders') void loadCustomerPage()
  }, 250)
})

let billSearchTimer: number | undefined
watch([adminDateFilter, adminDateFrom, adminDateTo, adminCommissionStatus, adminSearch], () => cancelCommissionSelection())
watch(adminSearch, () => {
  if (area.value !== 'admin' || adminPage.value !== 'finance' || adminMode.value !== 'billing') return
  window.clearTimeout(billSearchTimer)
  billSearchTimer = window.setTimeout(() => {
    adminBillPage.value = 1
    void loadAdminPage()
  }, 250)
})
</script>

<template>
  <LoginScreen
    v-if="!loggedIn"
    :area="area"
    :account="loginAccount"
    :password="loginPassword"
    :error="loginError"
    :busy="busy"
    :language="language"
    :registration-enabled="area === 'customer' && storefrontBrand.registrationEnabled"
    :commission-enabled="area === 'customer' && storefrontBrand.commissionEnabled"
    :merchant-name="currentMerchant?.name || storefrontBrand.name"
    :merchant-logo-url="currentMerchant?.logoUrl || storefrontBrand.logoUrl"
    :t="t"
    @update:account="loginAccount = $event"
    @update:password="loginPassword = $event"
    @update:language="language = $event"
    @change-language="changeLanguage"
    @sign-in="signIn"
    @registered="loginAccount = $event; loginPassword = ''; loginError = ''"
  />

  <div v-else-if="area === 'customer'" class="demo-customer-shell">
    <CustomerHeader
      :active-page="customerPage"
      :cart-count="cartCount"
      :language="language"
      :t="t"
      @navigate="goCustomer"
      @sign-out="signOut"
      @update:language="language = $event"
      @change-language="changeLanguage"
    />

    <CustomerProductCatalog
      v-if="customerPage === 'products'"
      :categories="categories"
      :active-category="activeCategory"
      :product-search="productSearch"
      :visible-products="visibleProducts"
      :cart-pending="busy"
      :pending-cart-product-ids="pendingCartProductIds"
      :cart-quantities="cartProductQuantities"
      :t="t"
      :money="money"
      @update:active-category="activeCategory = $event"
      @update:product-search="productSearch = $event"
      @open-certificate="openCertificate"
      @update-cart-quantity="updateCatalogCartQuantity"
    />
    <CustomerCertificatesPage
      v-else-if="customerPage === 'certificates'"
      :items="certificates"
      :search="certificateSearch"
      :t="t"
      @update:search="certificateSearch = $event"
      @open-certificate="openCertificate"
    />
    <CustomerCheckoutPage
      v-else-if="customerPage === 'cart'"
      :cart="cart"
      :addresses="addresses"
      :checkout-step="checkoutStep"
      :selected-address-id="selectedAddressId"
      :selected-address="defaultAddress()"
      :address-picker-open="addressPickerOpen"
      :order-note="orderNote"
      :order-no="selectedOrder?.orderNo"
      :order-total="selectedOrder?.total"
      :order-subtotal="selectedOrder?.subtotal"
      :order-shipping-fee="selectedOrder?.shippingFee"
      :order-discount-amount="selectedOrder?.discountAmount"
      :order-payment-status="selectedOrder?.paymentStatus"
      :order-paid-amount="selectedOrder?.paidAmount"
      :order-remaining-amount="selectedOrder?.remainingAmount"
      :checkout-quote="checkoutQuote"
      :payment-channel="paymentChannel"
      :crypto-enabled="cryptoEnabled"
      :online-payment-link-enabled="onlinePaymentLinkEnabled"
      :payment-method="paymentMethod"
      :payment-link-requested="Boolean(selectedOrder?.paymentLinkRequested)"
      :channels="channels"
      :selected-channel="selectedChannel"
      :transaction-no="transactionNo"
      :payment-amount="paymentAmount"
      :payment-success-open="paymentSuccessOpen"
      :t="t"
      :money="money"
      :status-label="statusLabel"
      :cart-pending="pendingActions.has('cart')"
      :checkout-pending="pendingActions.has('checkout')"
      :payment-pending="pendingActions.has('payment-submit')"
      :payment-link-pending="pendingActions.has('payment-link-request')"
      @update:checkout-step="checkoutStep = $event"
      @update:selected-address-id="selectCheckoutAddress"
      @update:address-picker-open="addressPickerOpen = $event"
      @update:order-note="orderNote = $event"
      @update:payment-channel="paymentChannel = $event"
      @update:payment-method="paymentMethod = $event"
      @update:transaction-no="transactionNo = $event"
      @update:payment-amount="paymentAmount = $event"
      @continue="continueCheckout"
      @back="backCheckout"
      @set-quantity="setQuantity"
      @remove-from-cart="removeFromCart"
      @open-address-form="openCheckoutAddressForm"
      @submit-payment="submitPayment"
      @request-payment-link="requestPaymentLink"
      @close-payment-success="paymentSuccessOpen = false"
      @navigate="goCustomer"
    />

    <CustomerOrderHistoryPage
      v-else-if="customerPage === 'orders'"
      :orders="orders"
      :order-stats="orderStats"
      :order-page-numbers="orderPageNumbers"
      :selected-order="selectedOrder"
      :detail-open="orderDetailOpen"
      :date-filter="orderDateFilter"
      :date-from="orderDateFrom"
      :date-to="orderDateTo"
      :date-locale="language"
      :payment-filter="orderPaymentFilter"
      :shipping-filter="orderShippingFilter"
      :search="orderSearch"
      :loading="busy"
      :pending-actions="pendingActions"
      :t="t"
      :money="money"
      :format-date-time="formatDateTime"
      :order-item-names="orderItemNames"
      :status-label="statusLabel"
      :status-class="statusClass"
      @date-filter="setOrderDateFilter"
      @payment-filter="setOrderPaymentFilter"
      @shipping-filter="setOrderShippingFilter"
      @update:date-from="orderDateFrom = $event"
      @update:date-to="orderDateTo = $event"
      @update:search="orderSearch = $event"
      @apply-date-range="applyOrderDateRange"
      @set-page="setOrderPage"
      @copy-order-number="copyOrderNumber"
      @open-payment="openPayment"
      @edit-order="editOrder"
      @open-order="openOrder"
      @close-order-detail="closeOrderDetail"
      @copy-value="copyCustomerValue"
      @cancel-order="cancelOrder"
    />

    <CustomerAccountPage
      v-else
      :tab="accountTab"
      :commission-enabled="currentMerchant?.commissionEnabled ?? false"
      :customer="customer"
      :profile-name="profileName"
      :referrals="referralCustomers"
      :referral-summary="referralSummary"
      :addresses="addresses"
      :old-password="oldPassword"
      :new-password="newPassword"
      :password-confirmation="passwordConfirmation"
      :profile-saving="pendingActions.has('profile-save')"
      :address-saving="pendingActions.has('address-save')"
      :password-changing="pendingActions.has('password-change')"
      :t="t"
      :money="money"
      @update:tab="accountTab = $event"
      @update:profile-name="profileName = $event"
      @update:old-password="oldPassword = $event"
      @update:new-password="newPassword = $event"
      @update:password-confirmation="passwordConfirmation = $event"
      @load-referrals="loadCustomerReferrals"
      @save-profile="saveProfile"
      @open-address-form="openAddressForm"
      @make-default-address="makeDefaultAddress"
      @edit-address="openAddressForm"
      @delete-address="deleteAddress"
      @change-password="changePassword"
    />

    <footer class="customer-footer"><div class="footer-top"><div class="footer-left"><div class="footer-company-name">{{ storefrontBrand.name }}</div><p class="footer-desc">{{ t('All materials listed are supplied for laboratory research use only and are not for human or veterinary use, food, or drug applications.') }}<br><br>{{ t('The nice research company') }}</p></div><div class="footer-right"><p><a href="#" @click.prevent>{{ t('Terms & Conditions') }}</a>© {{ new Date().getFullYear() }} {{ storefrontBrand.name }}</p></div></div></footer>
    <a v-if="customerPage === 'products'" class="float-cart" href="/cart" @click.prevent="goCustomer('cart')"><span class="cart-count-badge">{{ cartCount }}</span><span class="float-cart-label">{{ money(cart.total) }}{{ t('View cart →') }}</span></a>
    <CustomerOrderEditModal
      v-if="orderEditOpen"
      :order="selectedOrder"
      :addresses="addresses"
      :selected-address-id="orderEditAddressId"
      :items="orderEditItems"
      :note="orderNote"
      :pending="pendingActions.has('order-edit')"
      :money="money"
      :t="t"
      @update:selected-address-id="orderEditAddressId = $event"
      @update:quantity="updateOrderItemQuantity"
      @update:note="orderNote = $event"
      @add-address="openAddressForm(undefined, true)"
      @save="saveOrderEdit"
      @close="orderEditOpen = false"
    />
    <AppModal v-if="addressFormOpen" :label="t(addressEditingId ? 'Edit Address' : 'Add New Address')" content-class="address-form-modal" @close="addressFormOpen = false">
      <h2 class="modal-title">{{ t(addressEditingId ? 'Edit Address' : 'Add New Address') }}</h2>
      <div class="address-form-grid">
        <div class="form-group">
          <label for="address-name">{{ t('Full name *') }}</label>
          <input id="address-name" autocomplete="off" :value="addressDraft.name" :class="{ 'address-input-invalid': addressErrors.name }" :aria-invalid="Boolean(addressErrors.name)" :aria-describedby="addressErrors.name ? 'address-name-error' : undefined" :placeholder="t('Full name')" @input="updateAddressField('name', $event)" @blur="touchAddressField('name')">
          <p v-if="addressErrors.name" id="address-name-error" class="address-field-error" role="alert">{{ t(addressErrors.name) }}</p>
        </div>
        <div class="form-group">
          <label for="address-street">{{ t('Address *') }}</label>
          <input id="address-street" autocomplete="off" :value="addressDraft.street" :class="{ 'address-input-invalid': addressErrors.street }" :aria-invalid="Boolean(addressErrors.street)" :aria-describedby="addressErrors.street ? 'address-street-error' : undefined" :placeholder="t('Street address')" @input="updateAddressField('street', $event)" @blur="touchAddressField('street')">
          <p v-if="addressErrors.street" id="address-street-error" class="address-field-error" role="alert">{{ t(addressErrors.street) }}</p>
        </div>
        <div class="form-group">
          <label for="address-apt">{{ t('Apt, suite (optional)') }}</label>
          <input id="address-apt" autocomplete="off" v-model="addressDraft.apt" :placeholder="t('Apt, suite (optional)')">
        </div>
        <div class="form-group">
          <label for="address-city">{{ t('City *') }}</label>
          <input id="address-city" autocomplete="off" :value="addressDraft.city" :class="{ 'address-input-invalid': addressErrors.city }" :aria-invalid="Boolean(addressErrors.city)" :aria-describedby="addressErrors.city ? 'address-city-error' : undefined" :placeholder="t('City')" @input="updateAddressField('city', $event)" @blur="touchAddressField('city')">
          <p v-if="addressErrors.city" id="address-city-error" class="address-field-error" role="alert">{{ t(addressErrors.city) }}</p>
        </div>
        <div class="form-group">
          <label for="address-region">{{ t('State / Province / Region') }}</label>
          <input id="address-region" autocomplete="off" v-model="addressDraft.region" :placeholder="t('Optional')">
        </div>
        <div class="form-group">
          <label for="address-postal-code">{{ t('Postal code *') }}</label>
          <input id="address-postal-code" autocomplete="off" :value="addressDraft.postalCode" :class="{ 'address-input-invalid': addressErrors.postalCode }" :aria-invalid="Boolean(addressErrors.postalCode)" :aria-describedby="addressErrors.postalCode ? 'address-postal-code-error' : undefined" :placeholder="t('Postal code')" @input="updateAddressField('postalCode', $event)" @blur="touchAddressField('postalCode')">
          <p v-if="addressErrors.postalCode" id="address-postal-code-error" class="address-field-error" role="alert">{{ t(addressErrors.postalCode) }}</p>
        </div>
        <div class="form-group">
          <label for="address-phone">{{ t('Phone number *') }}</label>
          <input id="address-phone" autocomplete="off" type="tel" inputmode="tel" :value="addressDraft.phone" :class="{ 'address-input-invalid': addressErrors.phone }" :aria-invalid="Boolean(addressErrors.phone)" :aria-describedby="addressErrors.phone ? 'address-phone-error' : undefined" :placeholder="t('Phone number')" @input="updateAddressField('phone', $event)" @blur="touchAddressField('phone')">
          <p v-if="addressErrors.phone" id="address-phone-error" class="address-field-error" role="alert">{{ t(addressErrors.phone) }}</p>
        </div>
      </div>
      <label class="checkbox-field"><input autocomplete="off" v-model="addressDraft.isDefault" type="checkbox">{{ t('Use as default address') }}</label>
      <div class="modal-actions">
        <button class="modal-cancel" :disabled="pendingActions.has('address-save')" @click="addressFormOpen = false">{{ t('Cancel') }}</button>
        <button class="modal-save" :disabled="pendingActions.has('address-save')" :aria-busy="pendingActions.has('address-save')" @click="addAddress">{{ t(pendingActions.has('address-save') ? 'Saving…' : addressFormReturnToCheckout && !addressEditingId ? 'Save and use address' : 'Save') }}</button>
      </div>
    </AppModal>
    <CustomerPaymentModal
      v-if="customerPaymentOpen && !(customerPage === 'cart' && checkoutStep === 3) && !orderEditOpen && !orderDetailOpen && selectedOrder && ['unpaid', 'partially_paid'].includes(selectedOrder.paymentStatus) && (channels.length || onlinePaymentLinkEnabled || selectedOrder.paymentLinkRequested)"
      :order="selectedOrder"
      :channels="channels"
      :crypto-enabled="cryptoEnabled"
      :online-payment-link-enabled="onlinePaymentLinkEnabled"
      :payment-method="paymentMethod"
      :payment-link-pending="pendingActions.has('payment-link-request')"
      :payment-channel="paymentChannel"
      :transaction-no="transactionNo"
      :payment-amount="paymentAmount"
      :pending="pendingActions.has('payment-submit')"
      :t="t"
      :money="money"
      @update:payment-channel="paymentChannel = $event"
      @update:payment-method="paymentMethod = $event"
      @update:transaction-no="transactionNo = $event"
      @update:payment-amount="paymentAmount = $event"
      @submit="submitPayment"
      @request-payment-link="requestPaymentLink"
      @close="closePaymentModal"
    />
  </div>

  <div v-else-if="area === 'admin'" class="demo-seller-shell">
    <AdminSidebar
      :items="adminNavItems"
      :active-page="adminPageKey"
      :open-orders="Number(dashboard.openOrders || 0)"
      :merchant-name="currentMerchant?.name || ''"
      :merchant-logo-url="currentMerchant?.logoUrl || ''"
      @navigate="goAdmin"
    />
    <div class="main"><header class="topbar"><div><div class="topbar-title">{{ t(adminHeaderTitle) }}</div><div class="topbar-desc">{{ t(adminHeaderDescription) }}</div></div><div class="topbar-right"><span class="topbar-user">{{ currentMerchant?.name }} · {{ adminMember.name || 'Admin' }}</span><SellerAccountSwitcher :current-member-id="adminMember.id" :disabled="busy || signingOut" can-sign-out :signing-out="signingOut" :t="t" @activated="activateSellerAccount" @sign-out="signOut" /><SellerLanguageSwitch :model-value="language" :label="t('Language')" @update:model-value="language = $event; changeLanguage()" /></div></header>
      <main class="content">
        <section v-if="adminPage === 'order-audit'" class="panel active" id="panel-order-audit">
          <div class="filter-card seller-filter-card audit-filter-card">
            <div class="filter-row">
              <div class="filter-group">
                <span class="fg-label">{{ t('审核结果') }}</span>
                <div class="seg">
                  <button v-for="option in [['pending', t('待审核')], ['rejected', t('不通过')], ['approved', t('通过')]]" :key="option[0]" :class="{ active: auditResultFilter === option[0] }" type="button" @click="selectAuditResult(option[0] as 'pending' | 'rejected' | 'approved')">{{ t(option[1]) }}</button>
                </div>
              </div>
              <SellerSearchField class="seller-search" :loading="busy" @refresh="refreshAdminSearch"><input v-model="auditSearch" autocomplete="off" :placeholder="t('订单号 / 用户名 / 邮箱 / 运单号 / 商品名 / 商品 SKU…')" @keyup.enter="searchAuditOrders"></SellerSearchField>
            </div>
          </div>
          <div class="card">
            <div class="card-body table-wrap">
              <table class="admin-table order-audit-table">
                <thead><tr><th>{{ t('订单号') }}</th><th>{{ t('下单时间') }}</th><th>{{ t('收货人') }}</th><th>{{ t('商品数') }}</th><th>{{ t('商品金额') }}</th><th>{{ t('运费') }}</th><th>{{ t('优惠额') }}</th><th>{{ t('应付') }}</th><th class="payment-status-heading">{{ t('支付状态') }}</th><th>{{ t('操作') }}</th></tr></thead>
                <tbody><tr v-for="order in auditOrders.items" :key="order.id">
                  <td><button class="link-button" type="button" @click="openAuditOrderDetail(order)">{{ order.orderNo }}</button></td>
                  <td>{{ formatDateTime(order.createdAt) }}</td>
                  <td>{{ order.customer?.name || order.customer?.email || '—' }}</td><td>{{ orderItemQuantity(order) }}</td>
                  <td>{{ money(order.subtotal) }}</td><td>{{ money(order.shippingFee) }}</td><td>{{ money(order.discountAmount || 0) }}</td><td>{{ money(order.total) }}</td>
                  <td class="payment-status-cell"><span v-if="order.status === 'cancelled'">—</span><span v-else :class="['status-badge', 'payment-status-badge', statusClass(order.paymentStatus)]"><span v-if="order.paymentStatus === 'pending_verification'" class="status-attention-icon" aria-hidden="true">!</span>{{ sellerStatusLabel(order.paymentStatus) }}</span></td>
                  <td><div class="audit-row-actions"><button v-if="order.auditStatus === 'pending' && order.status === 'pending'" class="btn btn-outline" type="button" :disabled="!!auditEditTarget || pendingActions.has('order-audit-load')" @click="openAuditOrderEdit(order)">{{ t('编辑') }}</button><button class="btn btn-outline" type="button" :disabled="!!auditEditTarget || pendingActions.has('order-audit-load')" @click="order.auditStatus === 'pending' ? openAuditOrder(order) : openAuditOrderDetail(order)">{{ order.auditStatus === 'pending' ? t('进入审核') : t('查看详情') }}</button></div></td>
                </tr></tbody>
              </table>
              <div v-if="busy && adminPage === 'order-audit'" class="table-empty" role="status">{{ t('正在加载审核列表…') }}</div><div v-else-if="!auditOrders.items.length" class="table-empty">{{ auditSearch.trim() ? t('没有匹配的订单') : auditResultFilter === 'pending' ? t('暂无待审核订单') : auditResultFilter === 'rejected' ? t('暂无审核不通过订单') : t('暂无审核通过订单') }}</div>
            </div>
            <div v-if="auditOrders.total" class="admin-pagination">
              <span>{{ t('共 {0} 条 · 第 {1} / {2} 页', [auditOrders.total, auditOrders.page, auditOrders.totalPages]) }}</span>
              <div><button :disabled="auditOrders.page <= 1" @click="setAuditPage(auditOrders.page - 1)">‹</button><button v-for="page in Array.from({ length: auditOrders.totalPages }, (_, index) => index + 1)" :key="page" :class="{ active: auditOrders.page === page }" @click="setAuditPage(page)">{{ page }}</button><button :disabled="auditOrders.page >= auditOrders.totalPages" @click="setAuditPage(auditOrders.page + 1)">›</button></div>
            </div>
          </div>
        </section>

        <section v-else-if="adminPage === 'orders'" class="panel active" id="panel-orders"><div class="stat-grid"><article class="stat-card"><span>{{ t('订单总数') }}</span><strong>{{ adminOrderSummary.allOrders || 0 }}</strong></article><article class="stat-card"><span>{{ t('待支付订单') }}</span><strong>{{ adminOrderSummary.pendingPaymentOrders || 0 }}</strong></article><article class="stat-card"><span>{{ t('待发货') }}</span><strong>{{ adminOrderSummary.notShippedOrders || 0 }}</strong></article><article class="stat-card"><span>{{ t('应付总额') }}</span><strong>{{ money(adminOrderSummary.grossOrderValue || 0) }}</strong></article><article class="stat-card"><span>{{ t('待收金额') }}</span><strong>{{ money(adminOrderSummary.pendingReceivableAmount || 0) }}</strong></article><article class="stat-card"><span>{{ t('已完成') }}</span><strong>{{ adminOrderSummary.completedOrders || 0 }}</strong></article><article class="stat-card"><span>{{ t('累计实收') }}</span><strong>{{ money(adminOrderSummary.paidAmount || 0) }}</strong></article></div><div class="filter-card seller-filter-card"><div class="filter-row"><div class="filter-group"><span class="fg-label">{{ t('日期') }}</span><div class="seg"><button v-for="item in [['all',t('全部时间')],['week',t('本周')],['lastweek',t('上周')],['month',t('本月')],['lastmonth',t('上月')]]" :key="item[0]" :class="{ active: adminDateFilter === item[0] }" @click="adminDateFilter = item[0]; adminDateFrom = ''; adminDateTo = ''; adminOrderPage = 1; loadAdminPage()">{{ t(item[1]) }}</button></div></div><div class="filter-group"><span class="fg-label">{{ t('区间') }}</span><CustomerDatePicker v-model="adminDateFrom" class="seller-date-control" :locale="language" :label="t('Start date')" :disabled="busy" :t="t" @apply="applyAdminDateRange('orders')" /><span>{{ t('至') }}</span><CustomerDatePicker v-model="adminDateTo" class="seller-date-control calendar-align-end" :locale="language" :label="t('End date')" :disabled="busy" :t="t" @apply="applyAdminDateRange('orders')" /></div></div><div class="filter-row"><div class="filter-group"><span class="fg-label">{{ t('付款') }}</span><div class="seg"><button v-for="item in [['all',t('全部')],['unpaid',t('待支付')],['pending_verification',t('待验证')],['partially_paid',t('部分支付')],['paid',t('已付清')]]" :key="item[0]" :class="{ active: adminPaymentFilter === item[0] }" @click="adminPaymentFilter = item[0]; adminOrderPage = 1; loadAdminPage()">{{ t(item[1]) }}</button></div></div><div class="filter-group"><span class="fg-label">{{ t('发货') }}</span><div class="seg"><button v-for="item in [['all',t('全部')],['not',t('待发货')],['partial',t('部分发货')],['shipped',t('全部已发货')]]" :key="item[0]" :class="{ active: adminShippingFilter === item[0] }" @click="adminShippingFilter = item[0]; adminOrderPage = 1; loadAdminPage()">{{ t(item[1]) }}</button></div></div><SellerSearchField class="seller-search" :loading="busy" @refresh="refreshAdminSearch"><input autocomplete="off" v-model="adminSearch" :placeholder="t('订单号 / 用户名 / 邮箱 / tracking number / 商品名 / 商品 SKU…')" @keyup.enter="adminOrderPage = 1; loadAdminPage()"></SellerSearchField><button class="btn btn-outline" :disabled="exportPreviewLoading || exportSubmitting" @click="startExport('orders')">{{ t('导出') }}</button></div></div><div class="card"><div class="card-body table-wrap"><table class="admin-table order-table"><thead><tr><th>{{ t('订单号') }}</th><th>{{ t('收货人') }}</th><th>{{ t('商品数') }}</th><th>{{ t('商品金额') }}</th><th>{{ t('运费') }}</th><th>{{ t('优惠额') }}</th><th>{{ t('应付') }}</th><th>{{ t('已付') }}</th><th>{{ t('待付') }}</th><th class="payment-status-heading">{{ t('支付状态') }}</th><th class="fulfillment-status-heading">{{ t('订单状态') }}</th><th>{{ t('操作') }}</th></tr></thead><tbody><tr v-for="order in visibleAdminOrders" :key="order.id"><td><button class="link-button" type="button" :disabled="pendingActions.has('admin-order-detail')" :aria-label="t('查看订单 {0} 的详情', [order.orderNo])" aria-haspopup="dialog" @click="openAdminOrderDetail(order)">{{ order.orderNo }}</button></td><td>{{ order.customer?.name || order.customer?.email || '—' }}</td><td>{{ order.items?.length || 0 }}</td><td>{{ money(order.subtotal) }}</td><td>{{ money(order.shippingFee) }}</td><td>{{ money(order.discountAmount || 0) }}</td><td>{{ money(order.total) }}</td><td>{{ money(order.paidAmount) }}</td><td>{{ money(order.remainingAmount) }}</td><td class="payment-status-cell"><span v-if="order.status === 'cancelled'">—</span><span v-else :class="['status-badge', 'payment-status-badge', statusClass(order.paymentStatus)]"><span v-if="order.paymentStatus === 'pending_verification'" class="status-attention-icon" aria-hidden="true">!</span>{{ sellerStatusLabel(order.paymentStatus) }}</span><small v-if="order.paymentLinkRequested && order.paymentStatus !== 'paid'" class="payment-link-request-note">{{ t('已申请在线支付链接') }}</small></td><td class="fulfillment-status-cell"><span :class="['status-badge', 'fulfillment-status-badge', statusClass(order.status)]">{{ sellerFulfillmentLabel(order.status, order.fulfillmentSummary) }}</span><small v-if="order.fulfillmentSummary?.started && !['received', 'completed'].includes(order.status)" class="seller-parcel-count">{{ order.fulfillmentSummary.parcelCount }} {{ t('包 · 已发') }} {{ order.fulfillmentSummary.shippedParcelCount }} {{ t('· 已收') }} {{ order.fulfillmentSummary.receivedParcelCount }}</small></td><td class="table-actions"><button v-if="order.paymentStatus !== 'paid' && order.status !== 'cancelled'" class="link-button" @click="confirmPayment({ id: order.id })">{{ t('登记收款') }}</button><button v-if="!['completed', 'cancelled'].includes(order.status)" class="link-button" type="button" @click="parcelManagerOrderId = order.id">{{ t('管理包裹') }}</button><button v-if="order.status === 'received' && adminPermissions.includes('orders')" class="link-button" type="button" :disabled="orderCompletionLoading || orderCompletionSaving" @click="beginOrderCompletion(order)">{{ t('完成订单') }}</button></td></tr></tbody></table><div v-if="!visibleAdminOrders.length" class="table-empty">{{ t('暂无订单') }}</div></div><div class="admin-pagination"><span>{{ t('共 {0} 条 · 第 {1} / {2} 页', [adminOrders.total, adminOrders.page, adminOrders.totalPages]) }}</span><div><button :disabled="adminOrders.page <= 1" @click="setAdminOrderPage(adminOrders.page - 1)">‹</button><button v-for="page in adminOrderPageNumbers" :key="page" :class="{ active: adminOrders.page === page }" @click="setAdminOrderPage(page)">{{ page }}</button><button :disabled="adminOrders.page >= adminOrders.totalPages" @click="setAdminOrderPage(adminOrders.page + 1)">›</button></div></div></div></section>

        <section v-else-if="adminPage === 'finance' && adminMode !== 'billing'" class="panel active" id="panel-commissions">
          <div class="stat-grid">
            <article class="stat-card"><span>{{ t('佣金记录') }}</span><strong>{{ commissionSummary.count ?? commissions.total }}</strong></article>
            <article class="stat-card"><span>{{ t('待支付') }}</span><strong>{{ commissionSummary.payable || 0 }}</strong></article>
            <article class="stat-card"><span>{{ t('已支付') }}</span><strong>{{ commissionSummary.settled || 0 }}</strong></article>
            <article class="stat-card"><span>{{ t('佣金总额') }}</span><strong>{{ money(commissionSummary.commissionAmount) }}</strong></article>
            <article class="stat-card"><span>{{ t('已结算额') }}</span><strong>{{ money(commissionSummary.settledAmount) }}</strong></article>
          </div>
          <div class="filter-card seller-filter-card">
            <div class="filter-row">
              <div class="filter-group"><span class="fg-label">{{ t('日期') }}</span><div class="seg"><button v-for="item in [['all',t('全部时间')],['week',t('本周')],['month',t('本月')]]" :key="item[0]" :class="{ active: adminDateFilter === item[0] }" @click="adminDateFilter = item[0]; adminCommissionPage = 1; loadAdminPage()">{{ t(item[1]) }}</button></div></div>
              <div class="filter-group"><span class="fg-label">{{ t('状态') }}</span><div class="seg"><button v-for="item in commissionStatusFilters" :key="item.value" :class="{ active: adminCommissionStatus === item.value }" @click="adminCommissionStatus = item.value; adminCommissionPage = 1; loadAdminPage()">{{ t(item.label) }}</button></div></div>
              <SellerSearchField class="seller-search" :loading="busy" @refresh="refreshAdminSearch"><input autocomplete="off" v-model="adminSearch" :placeholder="t('搜索订单号 / 推荐人 / 客户…')" @keyup.enter="adminCommissionPage = 1; loadAdminPage()"></SellerSearchField>
              <div class="commission-actions">
                <button class="btn btn-outline" :disabled="exportPreviewLoading || exportSubmitting" @click="startExport('commissions')">{{ t('导出') }}</button>
                <template v-if="commissionSelectionMode">
                  <span class="commission-selection-count">{{ t('已选') }} {{ commissionSelectedRows.length }} {{ t('笔') }}</span>
                  <button class="btn" :disabled="!commissionSelectedRows.length" @click="openCommissionSettlement(commissionSelectedRows)">{{ t('确认结算') }}</button>
                  <button class="btn btn-outline" @click="cancelCommissionSelection">{{ t('取消') }}</button>
                </template>
                <button v-else class="btn" @click="startCommissionSelection">{{ t('批量结算') }}</button>
              </div>
            </div>
          </div>
          <div class="card">
            <div class="card-body table-wrap">
              <table class="admin-table commission-table">
                <thead><tr><th v-if="commissionSelectionMode" class="commission-check-cell"><input type="checkbox" :aria-label="t('全选本页可结算佣金')" :checked="commissionPageAllSelected" :disabled="!commissionPageEligible.length" @change="toggleCommissionPageSelection"></th><th>{{ t('订单号') }}</th><th>{{ t('结佣客户') }}</th><th>{{ t('下单客户') }}</th><th>{{ t('佣金基数') }}</th><th>{{ t('佣金率') }}</th><th>{{ t('佣金额') }}</th><th class="commission-status-heading">{{ t('佣金状态') }}</th><th class="commission-order-heading">{{ t('订单状态') }}</th><th>{{ t('支付状态') }}</th><th>{{ t('操作') }}</th></tr></thead>
                <tbody><tr v-for="item in commissions.items" :key="item.id">
                  <td v-if="commissionSelectionMode" class="commission-check-cell"><input type="checkbox" :aria-label="t('选择订单 {0} 进行结算', [item.orderNo || ''])" :checked="isCommissionSelected(item)" :disabled="!commissionCanSettle(item)" @change="toggleCommissionSelection(item)"></td>
                  <td><button class="link-button" @click="openFinanceOrder(item, 'commission')">{{ item.orderNo || '—' }}</button></td>
                  <td>{{ item.referrer || item.referrerName || '—' }}</td><td>{{ item.referred || item.customer || item.customerName || '—' }}</td>
                  <td>{{ money(item.baseAmount || item.orderAmount || item.amount) }}</td><td>{{ item.rate || item.commissionRate || 0 }}%</td><td>{{ money(item.commissionAmount || item.amount) }}</td>
                  <td class="commission-status-cell"><span :class="['status-badge', 'commission-status-badge', statusClass(item.status)]">{{ t(commissionStatusLabel(item.status)) }}</span></td><td class="commission-order-cell"><span :class="['status-badge', 'commission-order-status-badge', statusClass(item.orderStatus)]">{{ sellerFulfillmentLabel(item.orderStatus, item.fulfillmentSummary) }}</span></td><td>{{ sellerStatusLabel(item.paymentStatus) }}</td>
                  <td><button v-if="item.status === 'settled'" class="link-button" @click="openCommissionRecord(item)">{{ t('查看结算') }}</button><button v-else-if="commissionCanSettle(item)" class="link-button commission-settle-link" :disabled="commissionSelectionMode" :title="t('登记线下佣金结算')" @click="openCommissionSettlement([item])">{{ t('结算') }}</button><span v-else>—</span></td>
                </tr></tbody>
              </table>
              <div v-if="!commissions.items.length" class="table-empty">{{ t('暂无佣金记录') }}</div>
            </div>
            <div v-if="commissions.total" class="admin-pagination">
              <span>{{ t('共 {0} 条 · 第 {1} / {2} 页', [commissions.total, commissions.page, commissions.totalPages]) }}</span>
              <div>
                <button :disabled="commissions.page <= 1" @click="setAdminCommissionPage(1)">«</button>
                <button :disabled="commissions.page <= 1" @click="setAdminCommissionPage(commissions.page - 1)">‹</button>
                <button v-if="adminCommissionPageNumbers[0] > 1" @click="setAdminCommissionPage(1)">1</button>
                <span v-if="adminCommissionPageNumbers[0] > 2">…</span>
                <button v-for="page in adminCommissionPageNumbers" :key="page" :class="{ active: commissions.page === page }" @click="setAdminCommissionPage(page)">{{ page }}</button>
                <span v-if="adminCommissionPageNumbers[adminCommissionPageNumbers.length - 1] < commissions.totalPages - 1">…</span>
                <button v-if="adminCommissionPageNumbers[adminCommissionPageNumbers.length - 1] < commissions.totalPages" @click="setAdminCommissionPage(commissions.totalPages)">{{ commissions.totalPages }}</button>
                <button :disabled="commissions.page >= commissions.totalPages" @click="setAdminCommissionPage(commissions.page + 1)">›</button>
                <button :disabled="commissions.page >= commissions.totalPages" @click="setAdminCommissionPage(commissions.totalPages)">»</button>
              </div>
            </div>
          </div>
        </section>

        <section v-else-if="adminPage === 'finance' && adminMode === 'billing'" class="panel active" id="panel-billing">
          <div class="filter-card seller-filter-card billing-filter-card">
            <div class="filter-row billing-type-row">
              <div class="filter-group"><span class="fg-label">{{ t('类型') }}</span><div class="seg"><button v-for="item in adminBillTypeOptions" :key="item[0]" :class="{ active: adminBillTypeFilter === item[0] }" @click="setAdminBillType(item[0])">{{ t(item[1]) }}</button></div></div><div v-if="adminBillTypeFilter === 'order_receivable'" class="filter-group billing-status-filter"><span class="fg-label">{{ t('收款状态') }}</span><div class="seg"><button v-for="item in [['all',t('全部')],['unpaid',t('待支付')],['pending_verification',t('待验证')],['partially_paid',t('部分支付')],['paid',t('已付清')]]" :key="item[0]" :class="{ active: adminBillStatusFilter === item[0] }" @click="setAdminBillStatus(item[0])">{{ t(item[1]) }}</button></div></div><div v-else-if="adminBillTypeFilter === 'commission_payable' && currentMerchant?.commissionEnabled" class="filter-group billing-status-filter"><span class="fg-label">{{ t('结佣状态') }}</span><div class="seg"><button v-for="item in [['all',t('全部')],['payable',t('待支付')],['settled',t('已支付')]]" :key="item[0]" :class="{ active: adminBillStatusFilter === item[0] }" @click="setAdminBillStatus(item[0])">{{ t(item[1]) }}</button></div></div>
            </div>
            <div class="filter-row billing-query-row">
              <div class="filter-group"><span class="fg-label">{{ t('日期') }}</span><div class="seg"><button v-for="item in [['all',t('全部时间')],['week',t('本周')],['lastweek',t('上周')],['month',t('本月')],['lastmonth',t('上月')]]" :key="item[0]" :class="{ active: adminBillDateFilter === item[0] }" @click="adminBillDateFilter = item[0]; adminBillDateFrom = ''; adminBillDateTo = ''; adminBillPage = 1; loadAdminPage()">{{ t(item[1]) }}</button></div></div>
              <div class="filter-group billing-date-range"><span class="fg-label">{{ t('区间') }}</span><CustomerDatePicker v-model="adminBillDateFrom" class="seller-date-control" :locale="language" :label="t('Start date')" :disabled="busy" :t="t" @apply="applyAdminDateRange('bills')" /><span>{{ t('至') }}</span><CustomerDatePicker v-model="adminBillDateTo" class="seller-date-control calendar-align-end" :locale="language" :label="t('End date')" :disabled="busy" :t="t" @apply="applyAdminDateRange('bills')" /></div>
              <SellerSearchField class="seller-search" :loading="busy" @refresh="refreshAdminSearch"><input autocomplete="off" v-model="adminSearch" :placeholder="t('账单号 / 订单号 / 客户姓名 / 邮箱…')"></SellerSearchField>
              <button class="btn btn-outline" :disabled="exportPreviewLoading || exportSubmitting" @click="startExport('bills')">{{ t('导出') }}</button>
            </div>
          </div>
          <div class="stat-grid billing-stat-grid">
            <div class="billing-stat-line billing-order-stats">
              <article class="stat-card"><span>{{ t('订单数') }}</span><strong>{{ billSummary.orderBills }}</strong></article>
              <article class="stat-card"><span>{{ t('待支付') }}</span><strong>{{ billSummary.pendingOrders }}</strong></article>
              <article class="stat-card"><span>{{ t('有效订单总额') }}</span><strong>{{ money(billSummary.orderAmount) }}</strong></article>
              <article class="stat-card"><span>{{ t('已收金额') }}</span><strong>{{ money(billSummary.receivedAmount) }}</strong></article>
              <article class="stat-card"><span>{{ t('待收金额') }}</span><strong>{{ money(billSummary.pendingReceivableAmount) }}</strong></article>
            </div>
            <div v-if="currentMerchant?.commissionEnabled" class="billing-stat-line billing-commission-stats">
              <article class="stat-card"><span>{{ t('结佣总单') }}</span><strong>{{ billSummary.commissionBills }}</strong></article>
              <article class="stat-card"><span>{{ t('待支付') }}</span><strong>{{ billSummary.payableCommissionBills }}</strong></article>
              <article class="stat-card"><span>{{ t('佣金总额') }}</span><strong>{{ money(billSummary.commissionAmount) }}</strong></article>
              <article class="stat-card"><span>{{ t('已结佣金') }}</span><strong>{{ money(billSummary.settledCommissionAmount) }}</strong></article>
              <article class="stat-card"><span>{{ t('待结佣金') }}</span><strong>{{ money(billSummary.payableCommissionAmount) }}</strong></article>
            </div>
          </div>
          <div class="card">
            <div class="card-body table-wrap"><table class="admin-table billing-table"><thead><tr><th>{{ t('账单号') }}</th><th>{{ t('账单类型') }}</th><th>{{ t('关联订单') }}</th><th>{{ t('客户') }}</th><th>{{ t('账单金额') }}</th><th class="billing-status-heading">{{ t('账单状态') }}</th><th class="billing-order-heading">{{ t('订单状态') }}</th><th>{{ t('生成时间') }}</th><th>{{ t('操作') }}</th></tr></thead><tbody><tr v-for="item in visibleBills" :key="item.id || item.billNo"><td :title="item.billNo || item.id">{{ item.billNo || item.id }}</td><td><span class="bill-type-tag">{{ item.billType === 'commission_payable' ? t('结佣') : t('下单') }}</span></td><td><span class="billing-order-link"><button class="link-button" @click="openFinanceOrder(item, 'bill')">{{ item.orderNo || '—' }}</button><button class="billing-copy-button" :aria-label="t('复制订单号 {0}', [item.orderNo || ''])" @click="copyOrderNumber(item.orderNo)"><svg aria-hidden="true" viewBox="0 0 18 18"><rect x="6" y="3" width="9" height="11" rx="1"></rect><path d="M12 15H4a1 1 0 0 1-1-1V5"></path></svg></button></span></td><td :title="item.customer || item.customerName || '—'">{{ item.customer || item.customerName || '—' }}</td><td><strong class="billing-amount">{{ money(item.amount || item.total) }}</strong><template v-if="item.billType === 'order_receivable' && ['pending_verification', 'partially_paid'].includes(billStatusCode(item))"><small class="order-payment-balance">{{ t('已收') }} {{ money(item.settledAmount) }}<br>{{ t('待收') }} {{ money(item.remainingAmount) }}</small></template></td><td class="billing-status-cell"><span :class="['status-badge', 'bill-state-badge', statusClass(billStatusCode(item))]">{{ t(billStatusLabel(item)) }}</span></td><td class="billing-order-cell"><span :class="['status-badge', 'bill-order-status-badge', statusClass(item.orderStatus)]">{{ sellerFulfillmentLabel(item.orderStatus, item.fulfillmentSummary) }}</span></td><td>{{ formatDateTime(item.orderCreatedAt) }}</td><td><button class="billing-detail-button" @click="openFinanceOrder(item, 'bill')">{{ t('详情') }}</button></td></tr></tbody></table><div v-if="!visibleBills.length" class="table-empty">{{ t('暂无账单') }}</div></div>
            <div v-if="bills.total" class="admin-pagination"><span>{{ t('共 {0} 条 · 第 {1} / {2} 页', [bills.total, bills.page, bills.totalPages]) }}</span><div><button :disabled="bills.page <= 1" @click="setAdminBillPage(1)">«</button><button :disabled="bills.page <= 1" @click="setAdminBillPage(bills.page - 1)">‹</button><button v-if="adminBillPageNumbers[0] > 1" @click="setAdminBillPage(1)">1</button><span v-if="adminBillPageNumbers[0] > 2">…</span><button v-for="page in adminBillPageNumbers" :key="page" :class="{ active: bills.page === page }" @click="setAdminBillPage(page)">{{ page }}</button><span v-if="adminBillPageNumbers[adminBillPageNumbers.length - 1] < bills.totalPages - 1">…</span><button v-if="adminBillPageNumbers[adminBillPageNumbers.length - 1] < bills.totalPages" @click="setAdminBillPage(bills.totalPages)">{{ bills.totalPages }}</button><button :disabled="bills.page >= bills.totalPages" @click="setAdminBillPage(bills.page + 1)">›</button><button :disabled="bills.page >= bills.totalPages" @click="setAdminBillPage(bills.totalPages)">»</button></div></div>
          </div>
        </section>

        <section v-else-if="adminPage === 'products'" class="panel active" id="panel-products"><div class="card"><div class="card-body"><div class="toolbar seller-filter-row"><select autocomplete="off" v-model="adminCategoryFilter" @change="adminProductPage = 1; loadAdminPage()"><option value="all">{{ t('全部类目') }}</option><option v-for="category in adminCategories" :key="category.id" :value="String(category.id)">{{ category.name }}</option></select><div class="product-list-filter"><span class="fg-label">{{ t('类型') }}</span><div class="seg"><button v-for="item in [['all',t('全部')],['regular',t('正品')],['gift',t('赠品')]]" :key="item[0]" :class="{ active: adminProductType === item[0] }" @click="adminProductType = item[0]; adminProductPage = 1; loadAdminPage()">{{ t(item[1]) }}</button></div></div><div class="product-list-filter"><span class="fg-label">{{ t('状态') }}</span><div class="seg"><button v-for="item in [['all',t('全部')],['active',t('上架中')],['offline',t('已下架')]]" :key="item[0]" :class="{ active: adminProductStatus === item[0] }" @click="adminProductStatus = item[0]; adminProductPage = 1; loadAdminPage()">{{ t(item[1]) }}</button></div></div><SellerSearchField :loading="busy" @refresh="refreshAdminSearch"><input autocomplete="off" v-model="adminSearch" :placeholder="t('搜索商品名称 / SKU…')" @keyup.enter="adminProductPage = 1; loadAdminPage()"></SellerSearchField><button class="btn product-add-button" @click="openProduct()">{{ t('＋ 新增商品') }}</button></div><div class="table-wrap"><table class="admin-table product-table"><thead><tr><th>{{ t('类目') }}</th><th>{{ t('商品名称') }}</th><th>SKU</th><th>COA</th><th><button class="table-sort-button" @click="toggleProductSort('price')">{{ t('价格') }} {{ adminProductSort.by === 'price' ? (adminProductSort.direction === 'asc' ? '↑' : '↓') : '↕' }}</button></th><th><button class="table-sort-button" @click="toggleProductSort('stock')">{{ t('库存') }} {{ adminProductSort.by === 'stock' ? (adminProductSort.direction === 'asc' ? '↑' : '↓') : '↕' }}</button></th><th>{{ t('状态') }}</th><th>{{ t('操作') }}</th></tr></thead><tbody><tr v-for="product in adminProducts.items" :key="product.id"><td>{{ product.category }}</td><td><span class="product-name-with-type"><strong>{{ product.name }}</strong><span v-if="product.productType === 'gift'" class="product-type-tag">{{ t('赠品') }}</span></span></td><td>{{ product.sku }}</td><td><template v-if="product.coas?.length"><button v-for="coa in product.coas" :key="coa.id" class="link-button product-coa-link" @click="openCertificate(coa)" :aria-label="t('在新标签页打开 {0} ', [coa.name])">{{ coa.name }}<span class="product-coa-access-icon" aria-hidden="true">↗</span></button></template><span v-else>—</span></td><td>{{ money(product.price) }}</td><td>{{ product.stock }}</td><td><span :class="['status-badge', product.active ? 'status-paid' : 'status-cancelled']">{{ product.active ? t('上架中') : t('已下架') }}</span></td><td><button class="link-button" @click="openProduct(product)">{{ t('编辑') }}</button><button class="link-button" @click="toggleProduct(product)">{{ product.active ? t('下架') : t('上架') }}</button><button class="link-button danger-link" :disabled="product.active" :title="product.active ? t('请先下架商品再删除') : t('删除商品')" @click="deleteProduct(product)">{{ t('删除') }}</button></td></tr></tbody></table><div v-if="!adminProducts.items.length" class="table-empty">{{ t('暂无商品') }}</div></div></div><div v-if="adminProducts.total" class="admin-pagination"><span>{{ t('共 {0} 条 · 第 {1} / {2} 页', [adminProducts.total, adminProducts.page, adminProducts.totalPages]) }}</span><div><button :disabled="adminProducts.page <= 1" @click="setAdminProductPage(1)">«</button><button :disabled="adminProducts.page <= 1" @click="setAdminProductPage(adminProducts.page - 1)">‹</button><button v-if="adminProductPageNumbers[0] > 1" @click="setAdminProductPage(1)">1</button><span v-if="adminProductPageNumbers[0] > 2">…</span><button v-for="page in adminProductPageNumbers" :key="page" :class="{ active: adminProducts.page === page }" @click="setAdminProductPage(page)">{{ page }}</button><span v-if="adminProductPageNumbers[adminProductPageNumbers.length - 1] < adminProducts.totalPages - 1">…</span><button v-if="adminProductPageNumbers[adminProductPageNumbers.length - 1] < adminProducts.totalPages" @click="setAdminProductPage(adminProducts.totalPages)">{{ adminProducts.totalPages }}</button><button :disabled="adminProducts.page >= adminProducts.totalPages" @click="setAdminProductPage(adminProducts.page + 1)">›</button><button :disabled="adminProducts.page >= adminProducts.totalPages" @click="setAdminProductPage(adminProducts.totalPages)">»</button></div></div></div></section>

        <section v-else-if="adminPage === 'certificates'" class="panel active" id="panel-coas"><div class="card"><div class="card-head"><div class="toolbar"><SellerSearchField :loading="busy" @refresh="refreshAdminSearch"><input autocomplete="off" v-model="adminSearch" :placeholder="t('搜索 COA 名称 / 商品名 / 商品 SKU…')" @keyup.enter="adminCertificatePage = 1; loadAdminPage()"></SellerSearchField><button class="btn" @click="openCertificateModal()">{{ t('＋ 新增 COA') }}</button></div></div><div class="card-body table-wrap"><table class="admin-table coa-table"><thead><tr><th>{{ t('COA 名称') }}</th><th>{{ t('Lot') }}</th><th>{{ t('Purity') }}</th><th>{{ t('Fill') }}</th><th>{{ t('Test Date') }}</th><th>{{ t('关联商品') }}</th><th>{{ t('操作') }}</th></tr></thead><tbody><tr v-for="item in adminCertificates.items" :key="item.id"><td>{{ item.name }}</td><td>{{ item.lot || '—' }}</td><td>{{ item.purity || '—' }}</td><td>{{ item.fill || '—' }}</td><td>{{ item.testDate || '—' }}</td><td><button v-if="item.productId && item.sku" class="link-button" @click="openCertificateProduct(item)">{{ item.sku }}</button><span v-else>—</span></td><td><button class="link-button" @click="openCertificate(item)">{{ t('查看证书') }}</button><button class="link-button" @click="openCertificateModal(item)">{{ t('编辑') }}</button><button class="link-button danger-link" @click="deleteCertificate(item)">{{ t('删除') }}</button></td></tr></tbody></table><div v-if="!adminCertificates.items.length" class="table-empty">{{ t('暂无 COA 文档') }}</div></div><div v-if="adminCertificates.total" class="admin-pagination"><span>{{ t('共 {0} 条 · 第 {1} / {2} 页', [adminCertificates.total, adminCertificates.page, adminCertificates.totalPages]) }}</span><div><button :disabled="adminCertificates.page <= 1" @click="setAdminCertificatePage(1)">«</button><button :disabled="adminCertificates.page <= 1" @click="setAdminCertificatePage(adminCertificates.page - 1)">‹</button><button v-if="adminCertificatePageNumbers[0] > 1" @click="setAdminCertificatePage(1)">1</button><span v-if="adminCertificatePageNumbers[0] > 2">…</span><button v-for="page in adminCertificatePageNumbers" :key="page" :class="{ active: adminCertificates.page === page }" @click="setAdminCertificatePage(page)">{{ page }}</button><span v-if="adminCertificatePageNumbers[adminCertificatePageNumbers.length - 1] < adminCertificates.totalPages - 1">…</span><button v-if="adminCertificatePageNumbers[adminCertificatePageNumbers.length - 1] < adminCertificates.totalPages" @click="setAdminCertificatePage(adminCertificates.totalPages)">{{ adminCertificates.totalPages }}</button><button :disabled="adminCertificates.page >= adminCertificates.totalPages" @click="setAdminCertificatePage(adminCertificates.page + 1)">›</button><button :disabled="adminCertificates.page >= adminCertificates.totalPages" @click="setAdminCertificatePage(adminCertificates.totalPages)">»</button></div></div></div></section>

        <section v-else-if="adminPage === 'customers'" class="panel active" id="panel-users">
          <div class="card">
            <div class="filter-card">
              <div class="filter-row customer-filter-row">
                <div class="filter-group">
                  <span class="fg-label">{{ t('客户类型') }}</span>
                  <div class="seg">
                    <button :class="{ active: adminUserType === 'all' }" @click="adminUserType = 'all'; adminCustomerPage = 1; loadAdminPage()">{{ t('全部') }}</button>
                    <button :class="{ active: adminUserType === 'partner' }" @click="adminUserType = 'partner'; adminCustomerPage = 1; loadAdminPage()">{{ t('合作伙伴') }}</button>
                    <button :class="{ active: adminUserType === 'regular' }" @click="adminUserType = 'regular'; adminCustomerPage = 1; loadAdminPage()">{{ t('固定客户') }}</button>
                  </div>
                </div>
                <div class="filter-group">
                  <span class="fg-label">{{ t('付款状态') }}</span>
                  <div class="seg">
                    <button :class="{ active: adminUserPayment === 'all' }" @click="adminUserPayment = 'all'; adminCustomerPage = 1; loadAdminPage()">{{ t('全部') }}</button>
                    <button :class="{ active: adminUserPayment === 'unpaid' }" @click="adminUserPayment = 'unpaid'; adminCustomerPage = 1; loadAdminPage()">{{ t('待支付') }}</button>
                  </div>
                </div>
                <SellerSearchField class="customer-filter-search" :loading="busy" @refresh="refreshAdminSearch"><input autocomplete="off" v-model="adminSearch" :placeholder="t('搜索用户码 / 姓名 / 邮箱…')" @input="searchAdminCustomers" @keyup.enter="applyAdminCustomerSearch"></SellerSearchField>
                <button class="btn customer-add-button" @click="openUser()">{{ t('＋ 新增客户') }}</button>
              </div>
            </div>
            <div class="card-body table-wrap">
              <table class="admin-table">
                <thead><tr><th>{{ t('用户码') }}</th><th>{{ t('用户') }}</th><th>{{ t('推荐人') }}</th><th>{{ t('客户类型') }}</th><th>{{ t('最近登录') }}</th><th v-if="currentMerchant?.commissionEnabled">{{ t('推荐数') }}</th><th>{{ t('订单总数') }}</th><th>{{ t('总金额') }}</th><th>{{ t('待支付订单') }}</th><th>{{ t('待支付金额') }}</th><th>{{ t('操作') }}</th></tr></thead>
                <tbody>
                  <tr v-for="item in adminCustomers.items" :key="item.id">
                    <td><div class="customer-code-cell"><span class="customer-tier-mark">{{ item.priceTier || 'V1' }}</span><span>{{ item.userCode }}</span></div></td>
                    <td><strong>{{ item.name }}</strong><small>{{ item.email }}</small></td>
                    <td>{{ item.referrerName || '-' }}</td>
                    <td>{{ item.customerType === 'partner' ? t('合作伙伴') : t('固定客户') }}</td>
                    <td>{{ formatDateTime(item.lastLogin) }}</td>
                    <td v-if="currentMerchant?.commissionEnabled"><button class="link-button" type="button" :aria-label="t('查看 {0} 推荐的客户', [item.name])" @click="openCustomerReferrals(item)">{{ item.referralCount || 0 }}</button></td>
                    <td><button class="link-button" type="button" :aria-label="t('查看 {0} 的订单记录', [item.name])" @click="openCustomerOrders(item, 1, true)">{{ item.orderCount || 0 }}</button></td>
                    <td>{{ money(item.totalAmount) }}</td>
                    <td><button class="link-button" type="button" :aria-label="t('查看 {0} 的待支付订单', [item.name])" @click="openCustomerOrders(item, 1, true, 'payable')">{{ item.unpaidCount || 0 }}</button></td>
                    <td><strong :class="{ 'customer-unpaid-amount': Number(item.unpaidAmount) > 0 }">{{ money(item.unpaidAmount) }}</strong></td>
                    <td><button class="link-button" @click="openUser(item)">{{ t('编辑') }}</button><button class="link-button" @click="toggleUser(item)">{{ item.active ? t('禁用') : t('启用') }}</button></td>
                  </tr>
                </tbody>
              </table>
              <div v-if="!adminCustomers.items.length" class="table-empty">{{ t('暂无客户') }}</div>
            </div>
            <div v-if="adminCustomers.total" class="admin-pagination customer-pagination">
              <span>{{ t('共 {0} 条 · 第 {1} / {2} 页', [adminCustomers.total, adminCustomers.page, adminCustomers.totalPages]) }}</span>
              <div>
                <button :disabled="adminCustomers.page <= 1" @click="setAdminCustomerPage(1)">«</button>
                <button :disabled="adminCustomers.page <= 1" @click="setAdminCustomerPage(adminCustomers.page - 1)">‹</button>
                <button v-if="adminCustomerPageNumbers[0] > 1" @click="setAdminCustomerPage(1)">1</button>
                <span v-if="adminCustomerPageNumbers[0] > 2">…</span>
                <button v-for="page in adminCustomerPageNumbers" :key="page" :class="{ active: adminCustomers.page === page }" @click="setAdminCustomerPage(page)">{{ page }}</button>
                <span v-if="adminCustomerPageNumbers[adminCustomerPageNumbers.length - 1] < adminCustomers.totalPages - 1">…</span>
                <button v-if="adminCustomerPageNumbers[adminCustomerPageNumbers.length - 1] < adminCustomers.totalPages" @click="setAdminCustomerPage(adminCustomers.totalPages)">{{ adminCustomers.totalPages }}</button>
                <button :disabled="adminCustomers.page >= adminCustomers.totalPages" @click="setAdminCustomerPage(adminCustomers.page + 1)">›</button>
                <button :disabled="adminCustomers.page >= adminCustomers.totalPages" @click="setAdminCustomerPage(adminCustomers.totalPages)">»</button>
              </div>
            </div>
          </div>
        </section>

        <section v-else-if="adminPage === 'categories'" class="panel active" id="panel-categories"><div class="card"><div class="card-head"><button class="btn" @click="openCategory()">{{ t('＋ 新增类目') }}</button></div><div class="card-body table-wrap"><table class="admin-table"><thead><tr><th>{{ t('类目名字') }}</th><th>{{ t('排序') }}</th><th>{{ t('商品数') }}</th><th>{{ t('状态') }}</th><th>{{ t('更新人') }}</th><th>{{ t('更新时间') }}</th><th>{{ t('操作') }}</th></tr></thead><tbody><tr v-for="item in adminCategories" :key="item.id"><td>{{ item.name }}</td><td>{{ item.sortNo }}</td><td><button class="link-button" type="button" :aria-label="t('查看类目 {0} 的商品列表，共 {1} 件', [item.name, item.productCount || 0])" @click="openCategoryProducts(item)">{{ item.productCount || 0 }}</button></td><td><span :class="['status-badge', item.active ? 'status-paid' : 'status-cancelled']">{{ item.active ? t('启用') : t('停用') }}</span></td><td>{{ item.updatedBy || '—' }}</td><td>{{ formatDateTime(item.updatedAt) }}</td><td><button class="link-button" @click="openCategory(item)">{{ t('编辑') }}</button><button class="link-button" @click="toggleCategory(item)">{{ item.active ? t('停用') : t('启用') }}</button><button class="link-button danger-link" @click="deleteCategory(item)">{{ t('删除') }}</button></td></tr></tbody></table></div></div></section>

        <section v-else-if="adminPage === 'settings'" class="panel active" id="panel-payments">
          <div class="card">
            <div class="card-head"><button class="btn" @click="openPaymentChannel()">{{ t('＋ 新增支付渠道') }}</button></div>
            <div class="card-body table-wrap">
              <table class="admin-table payment-table">
                <colgroup>
                  <col class="payment-col-name"><col class="payment-col-network"><col class="payment-col-qr">
                  <col class="payment-col-address"><col class="payment-col-fee"><col class="payment-col-updated"><col class="payment-col-actions">
                </colgroup>
                <thead><tr><th>{{ t('渠道名') }}</th><th>{{ t('网络') }}</th><th>{{ t('收款二维码') }}</th><th>{{ t('收款地址') }}</th><th>{{ t('附加费率') }}</th><th>{{ t('更新时间') }}</th><th>{{ t('操作') }}</th></tr></thead>
                <tbody>
                  <tr v-for="item in adminChannels.items" :key="item.id">
                    <td>{{ item.name }}</td>
                    <td>{{ item.network || '—' }}</td>
                    <td>
                      <button
                        v-if="item.qrFileUuid && adminPaymentQrUrls[item.id]?.fileUuid === item.qrFileUuid"
                        class="payment-qr-thumbnail"
                        type="button"
                        :aria-label="`${t('收款二维码')}: ${item.name}`"
                        @click="openPaymentQr(item)"
                      ><img :src="adminPaymentQrUrls[item.id]?.url" alt=""></button>
                      <span v-else class="payment-qr-empty">—</span>
                    </td>
                    <td class="payment-address-cell" :title="item.receivingAddress">{{ item.receivingAddress }}</td>
                    <td>{{ item.additionalRate == null || Number(item.additionalRate) <= 0 ? '—' : '+' + Number(item.additionalRate).toFixed(2) + '%' }}</td>
                    <td>{{ formatDateTime(item.updatedAt) }}</td>
                    <td><button class="link-button" @click="openPaymentChannel(item)">{{ t('编辑') }}</button><button class="link-button danger-link" @click="deletePaymentChannel(item)">{{ t('删除') }}</button></td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </section>

        <section v-else-if="adminPage === 'members'" class="panel active" id="panel-permissions"><div class="card"><div class="card-head"><div class="toolbar"><SellerSearchField :loading="adminMembersLoading" @refresh="refreshAdminSearch"><input autocomplete="off" v-model="adminMemberSearch" :placeholder="t('搜索成员 / 账号…')" @keyup.enter="searchAdminMembers"></SellerSearchField><button class="btn" @click="openMember()">{{ t('＋ 新增成员') }}</button></div></div><div class="card-body table-wrap"><table class="admin-table permission-table"><thead><tr><th>{{ t('成员') }}</th><th>{{ t('登录账号') }}</th><th>{{ t('状态') }}</th><th>{{ t('菜单权限') }}</th><th>{{ t('添加人') }}</th><th>{{ t('更新时间') }}</th><th>{{ t('操作') }}</th></tr></thead><tbody><tr v-for="item in visibleAdminMembers" :key="item.id"><td><span>{{ item.name }}</span> <span v-if="item.merchantAdmin" class="merchant-admin-badge">{{ t('商户 Admin') }}</span></td><td>{{ item.account }}</td><td><span :class="['status-badge', item.active ? 'status-paid' : 'status-cancelled']">{{ item.active ? t('启用') : t('停用') }}</span></td><td><div class="member-permission-list"><span v-for="permission in item.permissions || []" :key="permission" class="member-permission-tag">{{ t(memberPermissionName(permission)) }}</span></div></td><td>Admin</td><td>{{ formatDateTime(item.updatedAt) }}</td><td><button class="link-button" :disabled="item.merchantAdmin" :title="item.merchantAdmin ? t('商户 Admin 账号由平台管理。') : undefined" @click="openMember(item)">{{ t('编辑') }}</button><button class="link-button" :disabled="item.merchantAdmin" :title="item.merchantAdmin ? t('商户 Admin 账号由平台管理。') : undefined" @click="toggleMember(item)">{{ item.active ? t('停用') : t('启用') }}</button></td></tr></tbody></table><div v-if="!visibleAdminMembers.length" class="table-empty">{{ t(adminMembersLoading ? '加载中…' : '暂无成员') }}</div></div>
          <div v-if="adminMembers.total" class="admin-pagination" :aria-busy="adminMembersLoading">
            <span>{{ t('共 {0} 条 · 第 {1} / {2} 页', [adminMembers.total, adminMembers.page, adminMembers.totalPages]) }}</span>
            <div>
              <button :disabled="adminMembersLoading || adminMembers.page <= 1" @click="setAdminMemberPage(1)">«</button>
              <button :disabled="adminMembersLoading || adminMembers.page <= 1" @click="setAdminMemberPage(adminMembers.page - 1)">‹</button>
              <button v-if="adminMemberPageNumbers[0] > 1" :disabled="adminMembersLoading" @click="setAdminMemberPage(1)">1</button>
              <span v-if="adminMemberPageNumbers[0] > 2">…</span>
              <button v-for="page in adminMemberPageNumbers" :key="page" :class="{ active: adminMembers.page === page }" :aria-current="adminMembers.page === page ? 'page' : undefined" :disabled="adminMembersLoading" @click="setAdminMemberPage(page)">{{ page }}</button>
              <span v-if="adminMemberPageNumbers[adminMemberPageNumbers.length - 1] < adminMembers.totalPages - 1">…</span>
              <button v-if="adminMemberPageNumbers[adminMemberPageNumbers.length - 1] < adminMembers.totalPages" :disabled="adminMembersLoading" @click="setAdminMemberPage(adminMembers.totalPages)">{{ adminMembers.totalPages }}</button>
              <button :disabled="adminMembersLoading || adminMembers.page >= adminMembers.totalPages" @click="setAdminMemberPage(adminMembers.page + 1)">›</button>
              <button :disabled="adminMembersLoading || adminMembers.page >= adminMembers.totalPages" @click="setAdminMemberPage(adminMembers.totalPages)">»</button>
            </div>
          </div>
        </div></section>
      </main>
    </div>
    <AppModal v-if="productModalOpen" :label="productEditingId ? t('编辑商品') : t('新增商品')" content-class="seller-modal product-modal" @close="productSaving || (productModalOpen = false)"><h2 class="modal-title">{{ productEditingId ? t('编辑商品') : t('新增商品') }}</h2><div class="form-grid"><label class="form-group">{{ t('商品类目') }}<select v-model="productDraft.categoryId" required><option value="" disabled>{{ t('选择类目') }}</option><option v-for="category in adminCategories" :key="category.id" :value="String(category.id)">{{ category.name }}</option></select><small>{{ t('请选择有效商品类目。') }}</small></label><label class="form-group">SKU<input v-model="productDraft.sku" maxlength="64" required><small>{{ t('必填，最多 64 个字符；SKU 必须唯一。') }}</small></label><label class="form-group">{{ t('商品名称') }}<input v-model="productDraft.name" maxlength="200" required><small>{{ t('必填，最多 200 个字符。') }}</small></label><label class="form-group">{{ t('商品类型') }}<select v-model="productDraft.productType"><option value="regular">{{ t('正品') }}</option><option value="gift">{{ t('赠品') }}</option></select></label><label class="form-group">{{ t('基础价格') }}<input v-model="productDraft.basePrice" type="number" :min="productDraft.productType === 'gift' ? 0 : 0.01" step="0.01" :disabled="productDraft.productType === 'gift'"><small>{{ t('正品价格大于 0，最多两位小数；赠品固定为 0。') }}</small></label><label class="form-group">{{ t('成本价') }}<input v-model="productDraft.costPrice" type="number" min="0" step="0.01"><small>{{ t('可留空；填写时必须不小于 0，最多两位小数。') }}</small></label><label class="form-group">{{ t('库存') }}<input v-model.number="productDraft.stockAvailable" type="number" min="0" max="2147483647" step="1" required><small>{{ t('非负整数，最大 2,147,483,647。') }}</small></label><label v-if="productEditingId && productDraft.stockAvailable !== productInitialStock" class="form-group">{{ t('库存调整原因') }}<input v-model="productStockReason" maxlength="1000" required :placeholder="t('例如：盘点差异补录')"></label></div><SellerQuantityPriceEditor v-if="productDraft.productType === 'regular'" v-model="productQuantityPrices" :fixed-prices="productFixedPrices" :disabled="productSaving" :t="t" @fixed-price="setProductFixedPrice" /><label class="form-group">{{ t('商品描述') }}<textarea v-model="productDraft.description" maxlength="2000"></textarea><small>{{ t('选填，最多 2,000 个字符。') }}</small></label><div v-if="canManageCoa" class="product-coa-picker form-group"><label for="product-coa-search">{{ t('关联 COA（可多选）') }}</label><div class="product-coa-chips"><span v-for="coa in productSelectedCoas" :key="coa.id" class="product-coa-chip">{{ coa.name }}<button type="button" :aria-label="t('移除 {0}', [coa.name])" @click="productSelectedCoas = productSelectedCoas.filter(item => item.id !== coa.id)">×</button></span></div><SellerSearchField :loading="productCoaLoading" @refresh="productCoaOptionsOpen = true; searchProductCoas()"><input id="product-coa-search" v-model="productCoaQuery" autocomplete="off" :placeholder="t('搜索 COA 名称或 Lot')" role="combobox" :aria-expanded="productCoaOptionsOpen" @focus="productCoaOptionsOpen = true; searchProductCoas()" @input="productCoaOptionsOpen = true; searchProductCoas()" @blur="productCoaOptionsOpen = false"></SellerSearchField><div v-if="productCoaOptionsOpen" class="product-coa-options" role="listbox"><p v-if="productCoaLoading">{{ t('正在搜索 COA…') }}</p><button v-for="coa in productCoaOptions" :key="coa.id" type="button" role="option" @mousedown.prevent="addProductCoa(coa)"><span>{{ coa.name }}</span></button><p v-if="!productCoaLoading && !productCoaOptions.length">{{ t('没有匹配的 COA') }}</p></div></div><label class="checkbox-field"><input v-model="productDraft.active" type="checkbox"> {{ t('上架中') }}</label><div class="modal-actions"><button class="modal-cancel" :disabled="productSaving" @click="productModalOpen = false">{{ t('取消') }}</button><button class="modal-save" :disabled="productSaving" :aria-busy="productSaving" @click="saveProduct">{{ t('保存') }}</button></div></AppModal>
    <AppModal v-if="categoryProductsOpen" :label="t('{0}商品列表', [categoryProductsCategory?.name || t('类目')])" content-class="seller-modal category-products-modal" @close="closeCategoryProducts">
      <div class="panel-header">
        <div><p class="eyebrow">{{ t('类目商品') }}</p><h2 class="modal-title">{{ categoryProductsCategory?.name }} · {{ categoryProducts.total }} {{ t('件') }}</h2></div>
        <button class="close-button" type="button" :aria-label="t('关闭商品列表')" @click="closeCategoryProducts">×</button>
      </div>
      <div class="table-wrap category-products-table-wrap">
        <table class="admin-table">
          <thead><tr><th>{{ t('商品名') }}</th><th>SKU</th><th>{{ t('价格') }}</th><th>{{ t('库存') }}</th></tr></thead>
          <tbody><tr v-for="product in categoryProducts.items" :key="product.id"><td>{{ product.name }}</td><td>{{ product.sku }}</td><td>{{ money(product.price) }}</td><td>{{ product.stock }}</td></tr></tbody>
        </table>
        <div v-if="categoryProductsLoading" class="table-empty" role="status">{{ t('正在加载商品…') }}</div>
        <div v-else-if="categoryProductsError" class="table-empty" role="alert">{{ t(categoryProductsError) }} <button class="link-button" type="button" @click="loadCategoryProducts(categoryProductsPage)">{{ t('重试') }}</button></div>
        <div v-else-if="!categoryProducts.items.length" class="table-empty">{{ t('该类目暂无商品') }}</div>
      </div>
      <div v-if="categoryProducts.totalPages > 1" class="admin-pagination">
        <span>{{ t('共 {0} 件 · 第 {1} / {2} 页', [categoryProducts.total, categoryProducts.page, categoryProducts.totalPages]) }}</span>
        <div>
          <button type="button" :disabled="categoryProductsLoading || categoryProductsPage <= 1" @click="setCategoryProductsPage(categoryProductsPage - 1)">{{ t('上一页') }}</button>
          <button type="button" :disabled="categoryProductsLoading || categoryProductsPage >= categoryProducts.totalPages" @click="setCategoryProductsPage(categoryProductsPage + 1)">{{ t('下一页') }}</button>
        </div>
      </div>
      <div class="modal-actions"><button class="modal-cancel" type="button" @click="closeCategoryProducts">{{ t('关闭') }}</button></div>
    </AppModal>
    <AppModal v-if="categoryModalOpen" :label="categoryEditingId ? t('编辑类目') : t('新增类目')" content-class="seller-modal" @close="categoryModalOpen = false"><h2 class="modal-title">{{ categoryEditingId ? t('编辑类目') : t('新增类目') }}</h2><label class="form-group">{{ t('类目名字') }}<input autocomplete="off" v-model="categoryDraft.name"></label><label class="form-group">{{ t('排序') }}<input autocomplete="off" v-model.number="categoryDraft.sortNo" type="number" min="1"></label><label class="checkbox-field"><input autocomplete="off" v-model="categoryDraft.active" type="checkbox"> {{ t('启用') }}</label><div class="modal-actions"><button class="modal-cancel" @click="categoryModalOpen = false">{{ t('取消') }}</button><button class="modal-save" @click="createCategory">{{ t('保存') }}</button></div></AppModal>
    <AppModal v-if="certificateModalOpen" :label="certificateEditingId ? t('编辑 COA') : t('新增 COA')" content-class="seller-modal" @close="certificateModalOpen = false"><h2 class="modal-title">{{ certificateEditingId ? t('编辑 COA') : t('新增 COA') }}</h2><label class="form-group">{{ t('COA 名称') }}<input autocomplete="off" v-model="certificateDraft.name" required></label><div class="form-group"><span>{{ t('Certificate file') }}</span><SellerFilePicker v-model="certificateFile" :label="t('Certificate file')" accept="application/pdf,image/png,image/jpeg" :required="!certificateCurrentFileUuid" /><small v-if="certificateCurrentFileUuid">{{ t('当前证书文件已保存；选择文件可替换。') }}</small></div><div class="form-grid"><label class="form-group">{{ t('Lot') }}<input autocomplete="off" v-model="certificateDraft.lot"></label><label class="form-group">{{ t('Purity') }}<input autocomplete="off" v-model="certificateDraft.purity"></label><label class="form-group">{{ t('Fill') }}<input autocomplete="off" v-model="certificateDraft.fill"></label><div class="form-group"><span>{{ t('Test Date') }}</span><CustomerDatePicker v-model="certificateDraft.testDate" class="seller-date-control seller-date-field" :locale="language" :label="t('Test Date')" :disabled="busy" :t="t" required /></div></div><div class="form-group coa-product-select"><label for="coa-product-search">{{ t('关联商品（选填）') }}</label><SellerSearchField :loading="certificateProductOptionsLoading" @refresh="refreshCertificateProducts"><input id="coa-product-search" v-model="certificateProductSearch" autocomplete="off" :placeholder="t('搜索 SKU 或商品名称')" role="combobox" :aria-expanded="certificateProductOptionsOpen" aria-controls="coa-product-options" @focus="certificateProductOptionsOpen = true" @input="searchCertificateProducts" @blur="certificateProductOptionsOpen = false"></SellerSearchField><div v-if="certificateProductOptionsOpen" id="coa-product-options" class="coa-product-options" role="listbox"><p v-if="certificateProductOptionsLoading" class="coa-product-options-message">{{ t('正在搜索商品…') }}</p><button v-for="product in certificateProductOptions" :key="product.id" type="button" role="option" :aria-selected="certificateDraft.productId === String(product.id)" @mousedown.prevent="selectCertificateProduct(product)"><strong>{{ product.sku }}</strong><span>{{ product.name }}</span></button><p v-if="!certificateProductOptionsLoading && !certificateProductOptions.length" class="coa-product-options-message">{{ t('没有可关联的商品') }}</p></div><small v-if="selectedCertificateProduct">{{ t('已选择：') }}{{ selectedCertificateProduct.sku }} · {{ selectedCertificateProduct.name }} <button class="link-button" type="button" @click="certificateDraft.productId = ''; selectedCertificateProduct = null; certificateProductSearch = ''">{{ t('清除') }}</button></small></div><div class="modal-actions"><button class="modal-cancel" @click="certificateModalOpen = false">{{ t('取消') }}</button><button class="modal-save" @click="createCertificate">{{ t('保存') }}</button></div></AppModal>
    <AppModal v-if="certificateProductDetailOpen" :label="t('关联商品详情')" content-class="seller-modal coa-product-detail-modal" @close="certificateProductDetailOpen = false"><div class="panel-header"><div><p class="eyebrow">{{ t('关联商品') }}</p><h2 class="modal-title">{{ certificateProductDetail?.name || t('商品详情') }}</h2></div><button class="close-button" :aria-label="t('关闭')" @click="certificateProductDetailOpen = false">×</button></div><p v-if="certificateProductDetailLoading" class="coa-product-detail-loading">{{ t('正在加载商品详情…') }}</p><dl v-else-if="certificateProductDetail" class="coa-product-detail-list"><div><dt>SKU</dt><dd>{{ certificateProductDetail.sku }}</dd></div><div><dt>{{ t('类目') }}</dt><dd>{{ certificateProductDetail.category || '—' }}</dd></div><div><dt>{{ t('类型') }}</dt><dd>{{ certificateProductDetail.productType === 'gift' ? t('赠品') : t('正品') }}</dd></div><div><dt>{{ t('基础价格') }}</dt><dd>{{ money(certificateProductDetail.basePrice) }}</dd></div><div><dt>{{ t('库存') }}</dt><dd>{{ certificateProductDetail.stock }}</dd></div><div><dt>{{ t('状态') }}</dt><dd>{{ certificateProductDetail.active ? t('上架中') : t('已下架') }}</dd></div><div class="coa-product-detail-description"><dt>{{ t('商品描述') }}</dt><dd>{{ certificateProductDetail.description || '—' }}</dd></div></dl></AppModal>
    <AppModal v-if="userModalOpen" :label="userEditingId ? t('编辑客户') : t('新增客户')" content-class="seller-modal customer-modal" @close="closeUserModal">
      <h2 class="modal-title">{{ userEditingId ? t('编辑客户') : t('新增客户') }}</h2>
      <form class="customer-user-form" novalidate @submit.prevent="saveUser">
      <p v-if="customerRequestError" class="field-error customer-form-error" role="alert" aria-live="assertive">{{ t(customerRequestError) }}</p>
      <div class="form-grid">
        <label class="form-group">{{ t('姓名') }} <input v-model="userDraft.name" autocomplete="name" maxlength="100" required :aria-invalid="customerFormSubmitted && Boolean(customerFieldError('name'))" @input="clearCustomerServerError('name')">
          <small v-if="customerFormSubmitted && customerFieldError('name')" class="field-error" role="alert">{{ t(customerFieldError('name')) }}</small>
        </label>
        <label class="form-group">{{ t('邮箱') }} <input v-model="userDraft.email" autocomplete="email" type="email" inputmode="email" maxlength="128" required :aria-invalid="customerFormSubmitted && Boolean(customerFieldError('email'))" @input="clearCustomerServerError('email')">
          <small v-if="customerFormSubmitted && customerFieldError('email')" class="field-error" role="alert">{{ t(customerFieldError('email')) }}</small>
        </label>
        <label class="form-group">{{ t('客户类型') }} <select v-model="userDraft.customerType" required @change="clearCustomerServerError('customerType')"><option value="partner">{{ t('合作伙伴') }}</option><option value="regular">{{ t('固定客户') }}</option></select>
          <small v-if="customerFormSubmitted && customerFieldError('customerType')" class="field-error" role="alert">{{ t(customerFieldError('customerType')) }}</small>
        </label>
        <label class="form-group">{{ t('客户等级') }} <select v-model="userDraft.priceTier" required @change="clearCustomerServerError('priceTier')"><option v-for="tier in ['V1', 'V2', 'V3', 'V4', 'V5', 'V6']" :key="tier" :value="tier">{{ tier }}</option></select>
          <small v-if="customerFormSubmitted && customerFieldError('priceTier')" class="field-error" role="alert">{{ t(customerFieldError('priceTier')) }}</small>
        </label>
        <label class="form-group">{{ userEditingId ? t('重置密码（选填）') : t('初始密码') }}
          <input v-model="userDraft.password" autocomplete="new-password" type="password" :required="!userEditingId" minlength="12" maxlength="64" :aria-invalid="customerFormSubmitted && Boolean(customerFieldError('password'))" @input="clearCustomerServerError('password')">
          <small class="field-hint">{{ userEditingId ? t('留空则保持原密码；设置新密码时须为 12～64 个字符。') : t('必填，须为 12～64 个字符。') }}</small>
          <small v-if="customerFormSubmitted && customerFieldError('password')" class="field-error" role="alert">{{ t(customerFieldError('password')) }}</small>
        </label>
        <label class="form-group">{{ t('确认密码') }} <input v-model="userDraft.confirmation" autocomplete="new-password" type="password" :required="!userEditingId || Boolean(userDraft.password)" :aria-invalid="customerFormSubmitted && Boolean(customerFieldError('confirmation'))" @input="clearCustomerServerError('confirmation')">
          <small v-if="customerFormSubmitted && customerFieldError('confirmation')" class="field-error" role="alert">{{ t(customerFieldError('confirmation')) }}</small>
        </label>
        <template v-if="currentMerchant?.commissionEnabled">
        <div class="form-group customer-referrer-field">
          <span>{{ t('推荐人（选填）') }}</span>
          <div class="customer-referrer-select" @focusout="closeReferrerDropdownOnFocusout">
            <button class="customer-referrer-trigger" type="button" :aria-expanded="referrerDropdownOpen" @click="openReferrerDropdown">
              <span>{{ selectedReferrer ? `${selectedReferrer.name} · ${selectedReferrer.code || selectedReferrer.userCode}` : userDraft.referrerId ? t('客户 {0}', [userDraft.referrerId]) : t('选择推荐人') }}</span><span aria-hidden="true">⌄</span>
            </button>
            <div v-if="referrerDropdownOpen" class="customer-referrer-dropdown">
              <SellerSearchField :loading="referrerOptionsLoading" @refresh="refreshReferrerOptions"><input ref="referrerSearchInput" v-model="referrerSearch" type="search" autocomplete="off" :placeholder="t('搜索姓名 / 用户码 / 邮箱')" @input="searchReferrerOptions" @keydown.esc.stop="referrerDropdownOpen = false"></SellerSearchField>
              <button class="customer-referrer-option customer-referrer-clear" type="button" @click="chooseReferrer(null)">{{ t('不设置推荐人') }}</button>
              <div v-if="referrerOptionsLoading" class="customer-referrer-empty">{{ t('搜索中…') }}</div>
              <template v-else>
                <button v-for="customer in referrerOptions" :key="customer.id" class="customer-referrer-option" type="button" @click="chooseReferrer(customer)">
                  <strong>{{ customer.name }}</strong><span>{{ customer.code || customer.userCode }} · {{ customer.email }}</span>
                </button>
                <p v-if="!referrerOptions.length" class="customer-referrer-empty">{{ t('没有匹配的客户。') }}</p>
                <button v-if="referrerOptionsPage < referrerOptionsTotalPages" class="customer-referrer-option customer-referrer-more" type="button" @click="loadReferrerOptions(referrerOptionsPage + 1, true)">{{ t('加载更多客户') }}</button>
              </template>
            </div>
          </div>
          <small class="field-hint">{{ t('可按姓名、用户码或邮箱搜索；不能选择当前客户。') }}</small>
          <small v-if="customerFormSubmitted && customerFieldError('referrerId')" class="field-error" role="alert">{{ t(customerFieldError('referrerId')) }}</small>
        </div>
        <label v-if="userDraft.referrerId" class="form-group">{{ t('佣金率 (%)') }} <input v-model="userDraft.commissionRate" type="number" inputmode="numeric" min="0" max="20" step="1" placeholder="0" required :aria-invalid="customerFormSubmitted && Boolean(customerFieldError('commissionRate'))" @input="clearCustomerServerError('commissionRate')">
          <small class="field-hint">{{ t('请输入 0～20 的整数；默认 0。') }}</small>
          <small v-if="customerFormSubmitted && customerFieldError('commissionRate')" class="field-error" role="alert">{{ t(customerFieldError('commissionRate')) }}</small>
        </label>
        </template>
      </div>
      <div class="modal-actions"><button class="modal-cancel" type="button" :disabled="userSaving" @click="closeUserModal">{{ t('取消') }}</button><button class="modal-save" type="submit" :disabled="userSaving" :aria-busy="userSaving">{{ userSaving ? t('保存中…') : t('保存') }}</button></div>
      </form>
    </AppModal>
    <AppModal v-if="commissionSettlementOpen && commissionSettlementRows.length" :label="commissionSettlementRows.length === 1 ? t('结算佣金 · {0}', [commissionSettlementRows[0].orderNo]) : t('批量结算佣金')" content-class="seller-modal commission-settlement-modal" @close="closeCommissionSettlement">
      <div class="panel-header">
        <div><p class="eyebrow">{{ commissionSettlementRows.length === 1 ? t('单笔结算') : t('批量结算') }}</p><h2 class="modal-title">{{ commissionSettlementRows.length === 1 ? t('结算佣金 · {0}', [commissionSettlementRows[0].orderNo]) : t('批量结算佣金') }}</h2></div>
        <button class="close-button" :disabled="commissionSettlementSubmitting" :aria-label="t('关闭结算确认')" @click="closeCommissionSettlement">×</button>
      </div>
      <template v-if="commissionSettlementRows.length === 1">
        <div class="commission-settlement-facts">
          <div><span>{{ t('结佣客户') }}</span><strong>{{ commissionSettlementRows[0].referrer || commissionSettlementRows[0].referrerName || '—' }}</strong></div>
          <div><span>{{ t('下单客户') }}</span><strong>{{ commissionSettlementRows[0].referred || commissionSettlementRows[0].customer || commissionSettlementRows[0].customerName || '—' }}</strong></div>
          <div><span>{{ t('佣金基数') }}</span><strong>{{ money(commissionSettlementRows[0].baseAmount || commissionSettlementRows[0].orderAmount || commissionSettlementRows[0].amount) }}</strong></div>
          <div><span>{{ t('佣金') }}</span><strong class="commission-settlement-amount">{{ money(commissionSettlementRows[0].commissionAmount || commissionSettlementRows[0].amount) }}（{{ commissionSettlementRows[0].rate || commissionSettlementRows[0].commissionRate || 0 }}%）</strong></div>
        </div>
        <p class="commission-settlement-notice">{{ t('订单已审核通过，佣金状态为待支付。确认表示佣金已在线下支付，系统将登记结算记录；同一订单不可重复结算。') }}</p>
      </template>
      <template v-else>
        <p class="commission-settlement-summary">{{ t('已选择') }} {{ commissionSettlementRows.length }} {{ t('笔订单，订单金额合计') }} <strong>{{ money(commissionSettlementBaseTotal) }}</strong>{{ t('，佣金金额合计') }} <strong>{{ money(commissionSettlementTotal) }}</strong>{{ t('。确认表示佣金已在线下支付，并将为所选订单登记结算记录。') }}</p>
        <div class="commission-settlement-table-wrap">
          <table class="commission-settlement-table">
            <thead><tr><th>{{ t('订单号') }}</th><th>{{ t('结佣客户') }}</th><th>{{ t('下单客户') }}</th><th>{{ t('佣金基数') }}</th><th>{{ t('佣金率') }}</th><th>{{ t('佣金') }}</th></tr></thead>
            <tbody><tr v-for="item in commissionSettlementRows" :key="item.id"><td>{{ item.orderNo || '—' }}</td><td>{{ item.referrer || item.referrerName || '—' }}</td><td>{{ item.referred || item.customer || item.customerName || '—' }}</td><td>{{ money(item.baseAmount || item.orderAmount || item.amount) }}</td><td>{{ item.rate || item.commissionRate || 0 }}%</td><td class="commission-settlement-amount">{{ money(item.commissionAmount || item.amount) }}</td></tr></tbody>
          </table>
        </div>
      </template>
      <label class="form-group commission-settlement-remark">{{ t('结算备注（选填）') }}<textarea v-model="commissionSettlementRemark" maxlength="1000" :placeholder="t('填写本次结算备注')"></textarea></label>
      <div class="modal-actions"><button class="modal-cancel" type="button" :disabled="commissionSettlementSubmitting" @click="closeCommissionSettlement">{{ t('取消') }}</button><button class="modal-save" type="button" :disabled="commissionSettlementSubmitting" :aria-busy="commissionSettlementSubmitting" @click="submitCommissionSettlement">{{ commissionSettlementSubmitting ? t('结算中…') : t('确认结算') }}</button></div>
    </AppModal>
    <AppModal v-if="commissionSettlementDetail" :label="t('佣金结算详情')" content-class="seller-modal" @close="closeAdminDetail"><div class="panel-header"><div><p class="eyebrow">{{ t('佣金结算详情') }}</p><h2 class="modal-title">{{ commissionSettlementDetail.batchNo }}</h2></div><button class="close-button" @click="closeAdminDetail">×</button></div><div class="detail-items"><div><span>{{ t('订单') }}</span><button class="link-button" @click="openSettlementOrder">{{ commissionSettlementDetail.orderNo }}</button></div><div><span>{{ t('结佣客户') }}</span><strong>{{ commissionSettlementDetail.referrer }}</strong></div><div><span>{{ t('下单客户') }}</span><strong>{{ commissionSettlementDetail.referred }}</strong></div><div><span>{{ t('结算基数 / 佣金率') }}</span><strong>{{ money(commissionSettlementDetail.baseAmount) }} · {{ commissionSettlementDetail.rate }}%</strong></div><div><span>{{ t('结算金额') }}</span><strong>{{ money(commissionSettlementDetail.commissionAmount) }}</strong></div><div><span>{{ t('结算时间') }}</span><strong>{{ commissionSettlementDetail.settledAt }}</strong></div><div><span>{{ t('备注') }}</span><strong>{{ commissionSettlementDetail.remark || '—' }}</strong></div></div></AppModal>

    <AppModal v-if="customerDetailKind && selectedAdminCustomer" :label="selectedAdminCustomer?.name || t('客户明细')" content-class="seller-modal customer-details-modal" @close="closeAdminDetail">
      <div class="panel-header">
        <div><p class="eyebrow">{{ t('客户明细') }}</p><h2 class="modal-title">{{ selectedAdminCustomer.name }}</h2></div>
        <button class="close-button" @click="closeAdminDetail">×</button>
      </div>
      <template v-if="customerDetailKind === 'orders'">
        <div class="filter-card seller-filter-card customer-order-filter-card">
          <div class="filter-row">
            <div class="filter-group"><span class="fg-label">{{ t('日期') }}</span><div class="seg"><button v-for="item in [['all',t('全部时间')],['week',t('本周')],['lastweek',t('上周')],['month',t('本月')],['lastmonth',t('上月')]]" :key="item[0]" :class="{ active: customerOrderDateFilter === item[0] }" @click="customerOrderDateFilter = item[0]; filterCustomerOrders()">{{ t(item[1]) }}</button></div></div>
          </div>
          <div class="filter-row">
            <div class="filter-group"><span class="fg-label">{{ t('付款') }}</span><div class="seg"><button v-for="item in [['all',t('全部')],['payable',t('待支付')],['pending_verification',t('待验证')],['partially_paid',t('部分支付')],['paid',t('已付清')]]" :key="item[0]" :title="item[0] === 'payable' ? t('包含待验证和部分支付订单') : undefined" :class="{ active: customerOrderPaymentFilter === item[0] }" @click="customerOrderPaymentFilter = item[0]; filterCustomerOrders()">{{ t(item[1]) }}</button></div></div>
            <div class="filter-group"><span class="fg-label">{{ t('发货') }}</span><div class="seg"><button v-for="item in [['all',t('全部')],['not',t('待发货')],['partial',t('部分发货')],['shipped',t('全部已发货')]]" :key="item[0]" :class="{ active: customerOrderShippingFilter === item[0] }" @click="customerOrderShippingFilter = item[0]; filterCustomerOrders()">{{ t(item[1]) }}</button></div></div>
            <SellerSearchField class="seller-search customer-order-search" :loading="customerOrdersLoading" @refresh="refreshCustomerOrders"><span class="sr-only">{{ t('订单号查询') }}</span><input autocomplete="off" v-model="customerOrderSearch" :placeholder="t('订单号 / 收货人 / 城市 / 运单号 / 商品…')" @input="searchCustomerOrders" @keyup.enter="applyCustomerOrderSearch"></SellerSearchField>
          </div>
        </div>
        <div class="table-wrap customer-orders-table-wrap">
          <table class="admin-table customer-orders-table">
            <thead><tr><th>{{ t('订单号') }}</th><th>{{ t('商品') }}</th><th>{{ t('订单状态') }}</th><th>{{ t('付款') }}</th><th>{{ t('金额') }}</th><th>{{ t('时间') }}</th></tr></thead>
            <tbody>
              <tr v-for="item in customerOrders.items" :key="item.id">
                <td><span class="customer-order-number"><button class="link-button" @click="openCustomerOrderDetail(selectedAdminCustomer, item)">{{ item.orderNo }}</button><button class="billing-copy-button" type="button" :aria-label="t('复制订单号 {0}', [item.orderNo])" @click.stop="copyOrderNumber(item.orderNo)"><svg aria-hidden="true" viewBox="0 0 18 18"><rect x="6" y="3" width="9" height="11" rx="1"></rect><path d="M12 15H4a1 1 0 0 1-1-1V5"></path></svg></button></span></td>
                <td><div class="customer-order-products"><span v-for="product in item.items || []" :key="product.productId">{{ product.name }} × {{ product.quantity }}</span><span v-if="!item.items?.length">—</span></div></td>
                <td>{{ sellerStatusLabel(item.status) }}</td><td><span :class="['status-badge', 'customer-payment-status', `customer-payment-${item.paymentStatus}`]">{{ sellerStatusLabel(item.paymentStatus) }}</span></td><td>{{ money(item.total) }}<small class="muted">{{ t('已收') }} {{ money(item.paidAmount) }} {{ t('· 待收') }} {{ money(item.remainingAmount) }}</small></td><td>{{ formatDateTime(item.createdAt) }}</td>
              </tr>
            </tbody>
          </table>
          <div v-if="customerOrdersLoading" class="table-empty" role="status">{{ t('正在加载订单…') }}</div>
          <div v-else-if="!customerOrders.items.length" class="table-empty">{{ t('暂无订单记录') }}</div>
        </div>
        <div class="admin-pagination customer-order-pagination">
          <span v-if="customerOrdersLoading" class="customer-order-summary" role="status">{{ t('正在更新当前筛选汇总…') }}</span>
          <span v-else class="customer-order-summary" aria-live="polite">{{ t('当前筛选汇总：订单数') }} {{ customerOrderFilterSummary.orderCount }} {{ t('· 待支付订单') }} {{ customerOrderFilterSummary.pendingPaymentOrders }} {{ t('· 总金额') }} {{ money(customerOrderFilterSummary.totalAmount) }} {{ t('· 待支付金额') }} {{ money(customerOrderFilterSummary.pendingPaymentAmount) }}</span>
          <div>
            <button :disabled="customerOrders.page <= 1 || customerOrdersLoading" @click="openCustomerOrders(selectedAdminCustomer, 1)">«</button>
            <button :disabled="customerOrders.page <= 1 || customerOrdersLoading" @click="openCustomerOrders(selectedAdminCustomer, customerOrders.page - 1)">‹</button>
            <span>{{ t('第') }} {{ customerOrders.page }} / {{ customerOrders.totalPages }} {{ t('页') }}</span>
            <button :disabled="customerOrders.page >= customerOrders.totalPages || customerOrdersLoading" @click="openCustomerOrders(selectedAdminCustomer, customerOrders.page + 1)">›</button>
            <button :disabled="customerOrders.page >= customerOrders.totalPages || customerOrdersLoading" @click="openCustomerOrders(selectedAdminCustomer, customerOrders.totalPages)">»</button>
          </div>
        </div>
      </template>
      <div v-else class="table-wrap">
        <table class="admin-table">
          <thead><tr><th>{{ t('用户码') }}</th><th>{{ t('用户') }}</th><th>{{ t('邮箱') }}</th><th>{{ t('客户类型') }}</th><th>{{ t('佣金率') }}</th><th>{{ t('最近登录时间') }}</th><th>{{ t('注册时间') }}</th></tr></thead>
          <tbody><tr v-for="item in customerReferrals.items" :key="item.id"><td>{{ item.userCode }}</td><td>{{ item.name }}</td><td>{{ item.email }}</td><td>{{ item.customerType === 'partner' ? t('合作伙伴') : t('固定客户') }}</td><td>{{ item.commissionRate == null ? '—' : item.commissionRate + '%' }}</td><td>{{ formatDateTime(item.lastLogin) }}</td><td>{{ formatDateTime(item.createdAt) }}</td></tr></tbody>
        </table>
        <p v-if="!customerReferrals.items.length" class="muted">{{ t('暂无直接推荐客户。') }}</p>
        <div class="modal-actions"><button class="modal-cancel" :disabled="customerReferrals.page <= 1" @click="openCustomerReferrals(selectedAdminCustomer, customerReferrals.page - 1)">{{ t('上一页') }}</button><span>{{ t('第') }} {{ customerReferrals.page }} / {{ customerReferrals.totalPages }} {{ t('页') }}</span><button class="modal-cancel" :disabled="customerReferrals.page >= customerReferrals.totalPages" @click="openCustomerReferrals(selectedAdminCustomer, customerReferrals.page + 1)">{{ t('下一页') }}</button></div>
      </div>
    </AppModal>
    <AppModal v-if="financeOrderDetail" :label="`${customerOrderDetailOpen || adminOrderDetailOpen ? t('订单详情') : t('关联订单详情')} ${financeOrderDetail.orderNo}`" content-class="seller-modal seller-order-detail-modal" @close="closeFinanceOrderDetail"><div class="panel-header"><div><p class="eyebrow">{{ customerOrderDetailOpen || adminOrderDetailOpen ? t('订单详情') : t('关联订单详情') }}</p><div class="seller-order-detail-title-row"><h2 class="modal-title">{{ financeOrderDetail.orderNo }}</h2><span v-if="financeOrderDetail.status" :class="['status-badge', 'seller-order-state-tag', statusClass(financeOrderDetail.status)]" :aria-label="t('订单状态：{0}', [sellerFulfillmentLabel(financeOrderDetail.status, financeOrderDetail.fulfillmentSummary)])">{{ sellerFulfillmentLabel(financeOrderDetail.status, financeOrderDetail.fulfillmentSummary) }}</span><button v-if="financeOrderDetail.items?.length" class="seller-order-copy-button" type="button" :aria-label="t('复制订单信息')" :title="t('复制订单信息')" @click="copyOrderDetails"><svg aria-hidden="true" viewBox="0 0 18 18"><rect x="6" y="3" width="9" height="11" rx="1"></rect><path d="M12 15H4a1 1 0 0 1-1-1V5"></path></svg><span>{{ t('复制订单') }}</span></button><span v-if="orderCopyFeedback" :class="['seller-order-copy-feedback', `is-${orderCopyFeedbackKind}`]" :role="orderCopyFeedbackKind === 'error' ? 'alert' : 'status'">{{ t(orderCopyFeedback) }}</span></div></div><button class="close-button" @click="closeFinanceOrderDetail">×</button></div><p v-if="adminOrderDetailOpen && pendingActions.has('admin-order-detail')" class="admin-order-detail-feedback" role="status">{{ t('正在加载订单详情…') }}</p><div v-else-if="adminOrderDetailOpen && adminOrderDetailError" class="admin-order-detail-feedback" role="alert"><p>{{ t(adminOrderDetailError) }}</p><button class="btn btn-outline" type="button" @click="openAdminOrderDetail(financeOrderDetail)">{{ t('重新加载') }}</button></div><template v-else><div v-if="adminOrderDetailOpen" class="detail-items"><div><span>{{ t('客户 / 收货人') }}</span><strong>{{ financeOrderDetail.customer?.name || financeOrderDetail.customer?.email || '—' }} / {{ financeOrderDetail.address?.name || '—' }}</strong></div><div><span>{{ t('收货地址') }}</span><strong>{{ [financeOrderDetail.address?.street, financeOrderDetail.address?.apt, financeOrderDetail.address?.city, financeOrderDetail.address?.region, financeOrderDetail.address?.postalCode, financeOrderDetail.address?.phone].filter(Boolean).join(', ') || '—' }}</strong></div><div><span>{{ t('买家备注') }}</span><strong>{{ financeOrderDetail.buyerNote || '—' }}</strong></div></div><div class="detail-items"><div v-for="(item, index) in financeOrderDetail.items" :key="item.productId || index"><span class="order-item-detail"><span class="product-name-with-type"><span>{{ item.name }} × {{ item.quantity }}</span><span v-if="item.productType === 'gift'" class="product-type-tag">{{ t('赠品') }}</span></span><button class="order-item-copy-button" type="button" :aria-label="t('复制商品详情：{0}', [item.name])" :title="t('复制商品详情')" @click="copyOrderItemDetails(item)"><svg aria-hidden="true" viewBox="0 0 20 20"><rect x="7" y="7" width="10" height="11" rx="1.5"></rect><path d="M13 7V5.5A1.5 1.5 0 0 0 11.5 4h-7A1.5 1.5 0 0 0 3 5.5v8A1.5 1.5 0 0 0 4.5 15H7"></path></svg></button></span><strong>{{ money(item.lineTotal) }}</strong></div></div><div class="summary-lines"><div><span>{{ t('商品金额') }}</span><strong>{{ money(financeOrderDetail.subtotal) }}</strong></div><div><span>{{ t('运费') }}</span><strong>{{ money(financeOrderDetail.shippingFee) }}</strong></div><div v-if="Number(financeOrderDetail.discountAmount) > 0"><span>{{ t('优惠额') }}</span><strong>−{{ money(financeOrderDetail.discountAmount) }}</strong></div><div class="summary-total"><span>{{ t('订单总额') }}</span><strong>{{ money(financeOrderDetail.total) }}</strong></div><div><span>{{ t('累计已收') }}</span><strong>{{ money(financeOrderDetail.paidAmount) }}</strong></div><div><span>{{ t('剩余待收') }}</span><strong>{{ money(financeOrderDetail.remainingAmount) }}</strong></div></div><OrderPaymentRecords v-if="financeOrderDetail.auditStatus === 'approved'" :order-id="financeOrderDetail.id" area="admin" :t="sellerPaymentText" :money="money" :format-date-time="formatDateTime" @copy-value="copyCustomerValue"><template #status><span v-if="financeOrderDetail.status !== 'cancelled' && financeOrderDetail.paymentStatus" :class="['status-badge', 'seller-order-state-tag', statusClass(financeOrderDetail.paymentStatus)]" :aria-label="t('支付状态：{0}', [sellerStatusLabel(financeOrderDetail.paymentStatus)])">{{ sellerStatusLabel(financeOrderDetail.paymentStatus) }}</span></template></OrderPaymentRecords><section v-if="adminOrderDetailOpen || financeOrderSource" class="admin-order-timeline"><h3>{{ t('履约进度') }}</h3><div v-if="financeOrderDetail.status === 'cancelled'" class="admin-order-cancelled">{{ t('订单已取消，后续履约已停止。') }}</div><ol><li v-for="stage in adminFulfillmentStages" :key="stage.key" :class="fulfillmentStageState(financeOrderDetail.status, financeOrderDetail.fulfillmentSummary, stage.key)"><span class="admin-timeline-marker" aria-hidden="true"></span><div><strong>{{ t(fulfillmentStageTitle(stage, financeOrderDetail.status, financeOrderDetail.fulfillmentSummary)) }}</strong><span>{{ t(fulfillmentStageStateLabel(fulfillmentStageState(financeOrderDetail.status, financeOrderDetail.fulfillmentSummary, stage.key))) }}</span></div></li></ol></section><section class="admin-order-parcels"><h3>{{ t('包裹与物流') }}</h3><button v-if="adminPermissions.includes('orders') && !['completed', 'cancelled'].includes(financeOrderDetail.status)" class="link-button" type="button" @click="parcelManagerOrderId = financeOrderDetail.id">{{ t('管理包裹') }}</button><OrderParcelList :parcels="financeOrderDetail.parcels || []" seller :format-date-time="formatDateTime" /></section><section v-if="adminOrderDetailOpen || financeOrderSource" class="admin-order-events"><h3>{{ t('操作记录') }}</h3><p v-if="!financeOrderDetail.events?.length" class="muted">{{ t('暂无操作记录') }}</p><article v-for="(event, index) in financeOrderDetail.events" :key="index" class="admin-order-event"><header><strong>{{ t(orderEventLabel(event)) }}</strong><time>{{ formatDateTime(event.occurredAt) }}</time></header><div class="admin-order-event-content"><p class="admin-order-event-actor">{{ t('操作人：') }}{{ event.actorName || t('未知操作人') }}</p><p class="admin-order-event-note">{{ event.note || t('无备注') }}</p><div v-if="event.files?.length" class="admin-order-event-files"><button v-for="file in event.files" :key="file.fileUuid" type="button" :aria-label="t('放大查看 {0} 操作图片', [t(orderEventLabel(event))])" @click="orderImagePreviewUrl = file.url"><img :src="file.url" :alt="t('履约操作图片')"></button></div></div></article></section><p class="muted" v-if="financeOrderSource && !customerOrderDetailOpen && !adminOrderDetailOpen">{{ t('关联记录：') }}{{ financeOrderSource.billNo || financeOrderSource.orderNo || financeOrderSource.id }}</p></template></AppModal>
    <div v-if="orderImagePreviewUrl" class="order-image-lightbox" role="dialog" aria-modal="true" :aria-label="t('履约操作图片预览')" tabindex="-1" @click.self="orderImagePreviewUrl = ''" @keydown.esc="orderImagePreviewUrl = ''"><button class="order-image-lightbox-close" type="button" :aria-label="t('关闭图片预览')" @click="orderImagePreviewUrl = ''">×</button><img :src="orderImagePreviewUrl" :alt="t('履约操作图片放大预览')"></div>
    <AppModal v-if="orderCompletionOpen && orderCompletionTarget" :label="t('确认订单完成')" content-class="seller-modal" @close="closeOrderCompletion">
      <form @submit.prevent="completeAdminOrder">
        <h2 class="modal-title">{{ t('确认订单完成') }}</h2>
        <p>{{ t('订单号') }}：{{ orderCompletionTarget.orderNo }}</p>
        <p v-if="orderCompletionLoading" role="status">{{ t('正在加载订单详情…') }}</p>
        <fieldset v-else-if="orderCompletionOrder?.allowedActions.includes('complete')" class="order-completion-fields" :disabled="orderCompletionSaving || orderCompletionStale">
          <label class="checkbox-field"><input v-model="orderCompletionConfirmed" type="checkbox" required> {{ t('确认全部商品已经收货并完成本订单') }}</label>
          <label class="form-group">{{ t('买家可见备注') }}<textarea v-model="orderCompletionNote" maxlength="256"></textarea></label>
        </fieldset>
        <p v-if="orderCompletionError" class="field-error" role="alert">{{ t(orderCompletionError) }}</p>
        <div class="modal-actions">
          <button type="button" class="modal-cancel" :disabled="orderCompletionSaving" @click="closeOrderCompletion">{{ t('取消') }}</button>
          <button v-if="orderCompletionError || orderCompletionStale" type="button" class="modal-cancel" :disabled="orderCompletionLoading || orderCompletionSaving" @click="reloadOrderCompletion">{{ t('重新加载订单') }}</button>
          <button type="submit" class="modal-save" :disabled="!canCompleteAdminOrder || !orderCompletionConfirmed || orderCompletionSaving">{{ t(orderCompletionSaving ? '保存中…' : '确认完成') }}</button>
        </div>
      </form>
    </AppModal>
    <SellerParcelManager v-if="loggedIn && area === 'admin' && parcelManagerOrderId !== null" :key="parcelManagerOrderId" :order-id="parcelManagerOrderId" :format-date-time="formatDateTime" @close="parcelManagerOrderId = null" @updated="parcelOrderUpdated" />
    <AppModal v-if="paymentModalOpen" :label="paymentEditingId ? t('编辑支付渠道') : t('新增支付渠道')" content-class="seller-modal" @close="paymentModalOpen = false"><h2 class="modal-title">{{ paymentEditingId ? t('编辑支付渠道') : t('新增支付渠道') }}</h2><div class="form-grid"><label class="form-group">{{ t('渠道名') }}<input autocomplete="off" v-model="channelDraft.name"></label><label class="form-group">{{ t('币种') }}<input autocomplete="off" v-model="channelDraft.currencyCode"></label><label class="form-group">{{ t('网络') }}<input autocomplete="off" v-model="channelDraft.network"></label><label class="form-group">{{ t('收款地址') }}<input autocomplete="off" v-model="channelDraft.receivingAddress"></label><label class="form-group">{{ t('附加费率 (%)') }}<input autocomplete="off" v-model.number="channelDraft.additionalRate" type="number" min="0" max="100" step="0.01" :placeholder="t('无附加费')"></label><div class="form-group"><span>{{ t('收款二维码') }}</span><SellerFilePicker v-model="channelQrFile" :label="t('收款二维码')" accept="image/png,image/jpeg" /><small v-if="channelDraft.qrFileUuid">{{ t('已关联二维码') }}</small></div><label class="form-group">{{ t('排序') }}<input autocomplete="off" v-model.number="channelDraft.sortNo" type="number" min="1"></label></div><label class="form-group">{{ t('说明') }}<textarea autocomplete="off" v-model="channelDraft.instructions"></textarea></label><label class="checkbox-field"><input autocomplete="off" v-model="channelDraft.active" type="checkbox"> {{ t('启用') }}</label><div class="modal-actions"><button class="modal-cancel" @click="paymentModalOpen = false">{{ t('取消') }}</button><button class="modal-save" @click="savePaymentChannel">{{ t('保存') }}</button></div></AppModal>
    <AppModal v-if="paymentQrPreview" :label="`${paymentQrPreview.name} · ${t('收款二维码')}`" content-class="seller-modal payment-qr-preview-modal" @close="paymentQrPreview = null">
      <button class="close-button" type="button" :aria-label="t('关闭')" @click="paymentQrPreview = null">×</button>
      <img :src="paymentQrPreview.url" :alt="`${paymentQrPreview.name} ${t('收款二维码')}`">
    </AppModal>
    <AppModal v-if="memberModalOpen" :label="memberEditingId ? t('编辑成员') : t('新增成员')" content-class="seller-modal member-modal" @close="memberModalOpen = false"><h2 class="modal-title">{{ memberEditingId ? t('编辑成员') : t('新增成员') }}</h2><div class="member-form-fields"><div :class="['form-group', 'member-field', { 'has-error': showMemberFieldError('account') && memberFormErrors.account }]"><label for="member-account">{{ t('登录账号') }}</label><input id="member-account" v-model="memberDraft.account" autocomplete="username" maxlength="64" required :aria-invalid="showMemberFieldError('account') && Boolean(memberFormErrors.account)" aria-describedby="member-account-hint member-account-error" @input="memberTouched.account = true" @blur="memberTouched.account = true"><small id="member-account-hint" class="field-hint">{{ t('1–64 个字符；不区分大小写，保存时会去除首尾空格。') }}</small><small v-if="showMemberFieldError('account') && memberFormErrors.account" id="member-account-error" class="field-error" role="alert">{{ t(memberFormErrors.account) }}</small></div><div :class="['form-group', 'member-field', { 'has-error': showMemberFieldError('name') && memberFormErrors.name }]"><label for="member-name">{{ t('成员姓名') }}</label><input id="member-name" v-model="memberDraft.name" autocomplete="name" maxlength="100" required :aria-invalid="showMemberFieldError('name') && Boolean(memberFormErrors.name)" aria-describedby="member-name-hint member-name-error" @input="memberTouched.name = true" @blur="memberTouched.name = true"><small id="member-name-hint" class="field-hint">{{ t('必填，最多 100 个字符。') }}</small><small v-if="showMemberFieldError('name') && memberFormErrors.name" id="member-name-error" class="field-error" role="alert">{{ t(memberFormErrors.name) }}</small></div><div :class="['form-group', 'member-field', { 'has-error': showMemberFieldError('password') && memberFormErrors.password }]"><label for="member-password">{{ memberEditingId ? t('重置密码') : t('初始密码') }}</label><input id="member-password" v-model="memberDraft.password" autocomplete="new-password" type="password" :required="!memberEditingId" minlength="12" maxlength="64" :aria-invalid="showMemberFieldError('password') && Boolean(memberFormErrors.password)" aria-describedby="member-password-hint member-password-error" @input="memberTouched.password = true" @blur="memberTouched.password = true"><small id="member-password-hint" class="field-hint">{{ memberEditingId ? t('留空表示不修改；设置新密码时须为 12～64 个字符。') : t('必填，须为 12～64 个字符。') }}</small><small v-if="showMemberFieldError('password') && memberFormErrors.password" id="member-password-error" class="field-error" role="alert">{{ t(memberFormErrors.password) }}</small></div><div :class="['form-group', 'member-field', { 'has-error': showMemberFieldError('confirmation') && memberFormErrors.confirmation }]"><label for="member-confirmation">{{ t('确认密码') }}</label><input id="member-confirmation" v-model="memberDraft.confirmation" autocomplete="new-password" type="password" :required="!memberEditingId || Boolean(memberDraft.password)" :aria-invalid="showMemberFieldError('confirmation') && Boolean(memberFormErrors.confirmation)" aria-describedby="member-confirmation-hint member-confirmation-error" @input="memberTouched.confirmation = true" @blur="memberTouched.confirmation = true"><small id="member-confirmation-hint" class="field-hint">{{ memberEditingId ? t('设置新密码时，请再次输入相同密码。') : t('请再次输入相同密码。') }}</small><small v-if="showMemberFieldError('confirmation') && memberFormErrors.confirmation" id="member-confirmation-error" class="field-error" role="alert">{{ t(memberFormErrors.confirmation) }}</small></div></div><fieldset :class="['member-permissions', { 'has-error': showMemberFieldError('permissions') && memberFormErrors.permissions }]" :aria-invalid="showMemberFieldError('permissions') && Boolean(memberFormErrors.permissions)" aria-describedby="member-permissions-hint member-permissions-error"><legend>{{ t('菜单权限') }}</legend><div class="member-permissions-heading"><p id="member-permissions-hint">{{ t('至少选择一项；勾选后，成员可使用该菜单下的全部功能。') }}</p><span>{{ memberDraft.permissions.length }} {{ t('项已选') }}</span></div><div class="member-permission-grid"><label v-for="permission in availableMemberPermissionOptions" :key="permission.code" :class="['member-permission-option', { selected: memberDraft.permissions.includes(permission.code) }]" @change="memberTouched.permissions = true"><input v-model="memberDraft.permissions" :value="permission.code" type="checkbox"><span class="member-permission-symbol" aria-hidden="true">{{ permission.symbol }}</span><span class="member-permission-copy"><strong>{{ t(permission.name) }}</strong><small>{{ t(permission.description) }}</small></span><span class="member-permission-check" aria-hidden="true">✓</span></label></div><small v-if="showMemberFieldError('permissions') && memberFormErrors.permissions" id="member-permissions-error" class="field-error" role="alert">{{ t(memberFormErrors.permissions) }}</small><p class="member-permission-summary"><span>{{ t('当前授权') }}</span><strong>{{ selectedMemberPermissions.map(permission => t(permission.name)).join(language === 'en' ? ', ' : '、') || t('尚未选择菜单') }}</strong></p></fieldset><div class="modal-actions"><button class="modal-cancel" @click="memberModalOpen = false">{{ t('取消') }}</button><button class="modal-save" @click="createMember">{{ memberEditingId ? t('保存修改') : t('创建成员') }}</button></div></AppModal>
    <SellerOrderEditModal v-if="auditEditTarget" :order-id="auditEditTarget.id" :order-no="auditEditTarget.orderNo" @close="auditEditTarget = null" @saved="auditOrderEdited" />
    <AppModal v-if="auditOrder && !auditEditTarget" :label="t('订单审核 ') + auditOrder.orderNo" content-class="seller-modal order-audit-modal" @close="closeAuditOrder">
      <div class="panel-header"><div><p class="eyebrow">{{ auditReviewMode ? t('订单审核') : t('订单详情') }}</p><h2 class="modal-title">{{ auditOrder.orderNo }}</h2></div><button class="close-button" type="button" :aria-label="t('关闭')" :disabled="auditSubmitting" @click="closeAuditOrder">×</button></div>
      <p v-if="pendingActions.has('order-audit-load')" class="muted" role="status">{{ t('正在加载订单详情…') }}</p>
      <template v-else>
        <div class="detail-status"><span class="status-badge">{{ auditOrder.auditStatus === 'pending' ? t('待审核') : auditOrder.auditStatus === 'approved' ? t('已通过') : t('未通过') }}</span><span :class="['status-badge', 'payment-status-badge', statusClass(auditOrder.paymentStatus)]">{{ sellerStatusLabel(auditOrder.paymentStatus) }}</span></div>
        <div class="detail-items"><div><span>{{ t('客户 / 收货人') }}</span><strong>{{ auditOrder.customer?.name || auditOrder.customer?.email || '—' }} / {{ auditOrder.recipientName || auditOrder.address?.name || '—' }}</strong></div><div><span>{{ t('联系电话') }}</span><strong>{{ auditOrder.address?.phone || '—' }}</strong></div><div><span>{{ t('收货地址') }}</span><strong>{{ [auditOrder.address?.street, auditOrder.address?.apt, auditOrder.address?.city, auditOrder.address?.region, auditOrder.address?.postalCode].filter(Boolean).join(', ') || '—' }}</strong></div><div><span>{{ t('交易号') }}</span><strong>{{ auditOrder.pendingPaymentSubmission?.transactionNo || auditOrder.paymentSubmission?.transactionNo || auditOrder.paymentReceipt?.transactionNo || '—' }}</strong></div><div v-if="auditOrder.paymentStatus === 'pending_verification' && auditOrder.pendingPaymentSubmission?.declaredAmount != null"><span>{{ t('申报支付金额') }}</span><strong>{{ money(auditOrder.pendingPaymentSubmission.declaredAmount) }}</strong></div><div><span>{{ t('买家备注') }}</span><strong>{{ auditOrder.buyerNote || '—' }}</strong></div></div>
        <div class="detail-items"><div v-for="(item, index) in auditOrder.items" :key="item.productId || index"><span>{{ item.name }} × {{ item.quantity }}</span><strong>{{ money(item.lineTotal) }}</strong></div></div>
        <div class="summary-lines"><div><span>{{ t('商品数') }}</span><strong>{{ orderItemQuantity(auditOrder) }}</strong></div><div><span>{{ t('商品金额') }}</span><strong>{{ money(auditOrder.subtotal) }}</strong></div><div><span>{{ t('运费') }}</span><strong>{{ money(auditOrder.shippingFee) }}</strong></div><div><span>{{ t('优惠额') }}</span><strong>−{{ money(auditOrder.discountAmount || 0) }}</strong></div><div class="summary-total"><span>{{ t('应付') }}</span><strong>{{ money(auditOrder.total) }}</strong></div></div>
        <div v-if="auditOrder.auditStatus !== 'pending'" class="detail-items"><div><span>{{ t('审核人 / 时间') }}</span><strong>{{ auditOrder.auditedByName || '—' }} / {{ formatDateTime(auditOrder.auditedAt) }}</strong></div><div><span>{{ t('审核备注') }}</span><strong>{{ auditOrder.auditRemark || '—' }}</strong></div></div>
        <p v-if="auditReviewMode && auditOrder.auditStatus === 'pending'" class="muted">{{ t('审核不通过会取消订单并恢复库存；已有付款申报状态会保留。') }}</p>
        <label v-if="auditReviewMode && auditOrder.auditStatus === 'pending'" class="form-group">{{ t('审核备注（选填）') }}<textarea v-model="auditRemark" maxlength="1000" :disabled="auditSubmitting" :placeholder="t('填写审核说明')"></textarea><small>{{ auditRemark.length }}/1000</small></label>
        <div class="modal-actions"><button class="modal-cancel" type="button" :disabled="auditSubmitting" @click="closeAuditOrder">{{ t('关闭') }}</button><button v-if="auditOrder.allowedActions?.includes('audit_edit')" class="modal-cancel" type="button" :disabled="auditSubmitting" @click="openAuditOrderEdit(auditOrder)">{{ t('编辑订单') }}</button><template v-if="auditReviewMode && auditOrder.auditStatus === 'pending'"><button class="modal-cancel audit-reject-button" type="button" :disabled="auditSubmitting" @click="submitAuditDecision('rejected')">{{ auditSubmitting ? t('提交中…') : t('审核不通过并取消订单') }}</button><button class="modal-save" type="button" :disabled="auditSubmitting" :aria-busy="auditSubmitting" @click="submitAuditDecision('approved')">{{ auditSubmitting ? t('提交中…') : t('审核通过') }}</button></template></div>
      </template>
    </AppModal>
    <AppModal v-if="paymentConfirmOpen && paymentConfirmOrder" :label="t('登记收款 ') + paymentConfirmOrder.orderNo" content-class="seller-modal order-action-modal" @close="!paymentConfirmSubmitting && (paymentConfirmOpen = false)">
      <div class="panel-header"><div><p class="eyebrow">{{ t('订单收款核验') }}</p><h2 class="modal-title">{{ t('登记收款 ·') }} {{ paymentConfirmOrder.orderNo }}</h2></div><button class="close-button" type="button" :aria-label="t('关闭')" :disabled="paymentConfirmSubmitting" @click="paymentConfirmOpen = false">×</button></div>
      <div class="summary-lines"><div><span>{{ t('当前订单应付') }}</span><strong>{{ money(paymentConfirmOrder.total) }}</strong></div><div><span>{{ t('累计已付') }}</span><strong>{{ money(paymentConfirmOrder.paidAmount) }}</strong></div><div><span>{{ t('剩余待付') }}</span><strong>{{ money(paymentConfirmOrder.remainingAmount) }}</strong></div></div>
      <div v-if="paymentConfirmOrder.pendingPaymentSubmission" class="summary-lines">
        <div><span>{{ t('申报金额') }}</span><strong>{{ money(paymentConfirmOrder.pendingPaymentSubmission.declaredAmount) }}</strong></div>
        <div><span>{{ t('申报渠道') }}</span><strong>{{ paymentConfirmOrder.pendingPaymentSubmission.channelSnapshot.name || '—' }}</strong></div>
        <div><span>{{ t('交易号') }}</span><strong class="order-tracking-value">{{ paymentConfirmOrder.pendingPaymentSubmission.transactionNo }}</strong></div>
      </div>
      <p v-if="paymentConfirmError" role="alert" class="seller-order-edit-error">{{ t(paymentConfirmError) }}</p>
      <button v-if="paymentConfirmStale" class="btn btn-outline" type="button" :disabled="paymentConfirmSubmitting" @click="confirmPayment(paymentConfirmOrder)">{{ t('重新加载订单后核对') }}</button>
      <fieldset :disabled="paymentConfirmSubmitting || paymentConfirmStale" class="payment-confirm-fields">
        <div class="form-group">
          <div class="payment-confirm-amount-heading">
            <label for="payment-confirm-amount">{{ t('本次核实收款金额 (USD)') }}</label>
            <button v-if="!paymentConfirmOrder.pendingPaymentSubmission" class="link-button" type="button" @click="paymentConfirmAmount = String(paymentConfirmOrder.remainingAmount)">{{ t('收取剩余全部款项') }}</button>
          </div>
          <input id="payment-confirm-amount" v-model="paymentConfirmAmount" inputmode="decimal" autocomplete="off" maxlength="19" :placeholder="t('本次收款金额')" :aria-invalid="Boolean(paymentConfirmFieldError('amount'))" :aria-describedby="paymentConfirmFieldError('amount') ? 'payment-confirm-amount-error' : undefined" @blur="paymentConfirmTouched.amount = true">
          <small v-if="paymentConfirmFieldError('amount')" id="payment-confirm-amount-error" class="payment-confirm-field-error" role="alert">{{ t(paymentConfirmFieldError('amount')) }}</small>
        </div>
        <label v-if="!paymentConfirmOrder.pendingPaymentSubmission" class="form-group" for="payment-confirm-channel">{{ t('支付渠道') }} <select id="payment-confirm-channel" v-model.number="paymentConfirmChannelId" :aria-invalid="Boolean(paymentConfirmFieldError('channel'))" :aria-describedby="paymentConfirmFieldError('channel') ? 'payment-confirm-channel-error' : undefined" @blur="paymentConfirmTouched.channel = true"><option :value="null" disabled>{{ t('请选择支付渠道') }}</option><option v-for="channel in paymentConfirmChannels" :key="channel.id" :value="channel.id">{{ channel.name }} · {{ channel.network }}</option></select>
          <small v-if="paymentConfirmFieldError('channel')" id="payment-confirm-channel-error" class="payment-confirm-field-error" role="alert">{{ t(paymentConfirmFieldError('channel')) }}</small>
        </label>
        <label class="form-group" for="payment-confirm-transaction">{{ t('核实交易号') }} <input id="payment-confirm-transaction" v-model="paymentConfirmTransactionNo" autocomplete="off" maxlength="256" :placeholder="t('请输入核实交易号')" :aria-invalid="Boolean(paymentConfirmFieldError('transaction'))" :aria-describedby="paymentConfirmFieldError('transaction') ? 'payment-confirm-transaction-error' : undefined" @blur="paymentConfirmTouched.transaction = true">
          <small v-if="paymentConfirmFieldError('transaction')" id="payment-confirm-transaction-error" class="payment-confirm-field-error" role="alert">{{ t(paymentConfirmFieldError('transaction')) }}</small>
        </label>
        <label class="form-group" for="payment-confirm-remark">{{ t('内部备注／差异原因') }} <textarea id="payment-confirm-remark" v-model="paymentConfirmRemark" maxlength="1000" :placeholder="t('核实金额与申报金额不同时必填')" :aria-invalid="Boolean(paymentConfirmFieldError('remark'))" :aria-describedby="paymentConfirmFieldError('remark') ? 'payment-confirm-remark-error' : undefined" @blur="paymentConfirmTouched.remark = true"></textarea>
          <small v-if="paymentConfirmFieldError('remark')" id="payment-confirm-remark-error" class="payment-confirm-field-error" role="alert">{{ t(paymentConfirmFieldError('remark')) }}</small>
        </label>
      </fieldset>
      <div class="modal-actions"><button class="modal-cancel" type="button" :disabled="paymentConfirmSubmitting" @click="paymentConfirmOpen = false">{{ t('取消') }}</button><button class="modal-save" type="button" :disabled="paymentConfirmSubmitting || paymentConfirmStale" @click="submitPaymentConfirmation">{{ paymentConfirmSubmitting ? t('提交中…') : t('登记本次收款') }}</button></div>
    </AppModal>
    <AppModal v-if="exportConfirmation" :label="t('确认导出')" content-class="seller-modal export-confirm-modal" @close="closeExportConfirmation">
      <div class="panel-header"><div><p class="eyebrow">{{ t(exportTypeLabel(exportConfirmation.type)) }}{{ t('数据') }}</p><h2 class="modal-title">{{ t('确认导出') }}</h2></div><button class="close-button" type="button" :aria-label="t('关闭导出确认')" :disabled="exportSubmitting" @click="closeExportConfirmation">×</button></div>
      <p v-if="exportPreviewLoading" role="status">{{ t('正在统计当前筛选下的数据…') }}</p>
      <template v-else-if="exportConfirmation.preview">
        <p v-if="exportConfirmation.type === 'orders'">{{ t('当前筛选匹配') }} <strong>{{ exportCountLabel(exportConfirmation.preview.recordCount) }}</strong> {{ t('个订单，将导出') }} <strong>{{ exportCountLabel(exportConfirmation.preview.rowCount) }}</strong> {{ t('条订单数据。') }}</p>
        <p v-else>{{ t('当前筛选匹配') }} <strong>{{ exportCountLabel(exportConfirmation.preview.recordCount) }}</strong> {{ t('条记录，将导出') }} <strong>{{ exportCountLabel(exportConfirmation.preview.rowCount) }}</strong> {{ t('条数据。') }}</p>
        <p v-if="exportConfirmation.preview.rowCount > exportConfirmation.preview.maxRows" class="field-error" role="alert">{{ t('数据量超过同步导出上限') }} {{ exportCountLabel(exportConfirmation.preview.maxRows) }} {{ t('条，请缩小筛选范围。') }}</p>
        <p v-else-if="exportConfirmation.preview.rowCount === 0" class="muted">{{ t('当前没有可导出的数据。') }}</p>
      </template>
      <div class="modal-actions"><button class="modal-cancel" type="button" :disabled="exportSubmitting" @click="closeExportConfirmation">{{ t('取消') }}</button><button class="modal-save" type="button" :disabled="exportPreviewLoading || exportSubmitting || !exportConfirmation.preview || exportConfirmation.preview.rowCount <= 0 || exportConfirmation.preview.rowCount > (exportConfirmation.preview?.maxRows ?? 0)" :aria-busy="exportSubmitting" @click="confirmExport">{{ exportSubmitting ? t('导出中…') : t('确认导出') }}</button></div>
    </AppModal>
  </div>

  <div v-if="busy" class="loading-line" role="progressbar" :aria-label="t('Loading')" :aria-valuetext="t('Loading')"></div>
  <CustomerNotice
    v-if="notice && area === 'customer'"
    :notice="t(notice)"
    :kind="noticeKind"
    :hint="t(noticeHint)"
    :t="t"
    @dismiss="clearNotice"
  />
  <div v-else-if="notice" class="toast seller-toast" role="alert" aria-live="assertive">{{ t(notice) }}</div>
</template>
