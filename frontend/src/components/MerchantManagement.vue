<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import AppModal from './AppModal.vue'
import { ApiRequestError, merchantApi, type Merchant, type MerchantPage, type MerchantMailSettingsInput, type MerchantShippingFees, type MerchantFreeShippingThresholds } from '../lib/api'
import { useSellerTranslation } from '../lib/seller-locale'
const t = useSellerTranslation()
const emit = defineEmits<{ saved: [] }>()
const result = ref<MerchantPage>({ items: [], page: 1, total: 0, totalPages: 1 })
const pageNumbers = computed(() => {
  const pages = result.value.totalPages || 1
  if (pages <= 5) return Array.from({ length: pages }, (_, index) => index + 1)
  const first = Math.min(Math.max(result.value.page - 2, 1), pages - 4)
  return Array.from({ length: 5 }, (_, index) => first + index)
})
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const modal = ref(false)
const editing = ref<Merchant | null>(null)
const uploadingLogo = ref(false)
const logoFileInput = ref<HTMLInputElement | null>(null)
const testingMail = ref(false)
const mailTestStatus = ref('')
const mailExpanded = ref(false)
const shippingTiers = ['v1', 'v2', 'v3', 'v4', 'v5', 'v6'] as const
type ShippingFeeDraft = Record<keyof MerchantShippingFees, string | number>
type ShippingThresholdDraft = Record<keyof MerchantFreeShippingThresholds, string>
function zeroShippingFees(): ShippingFeeDraft { return { v1: '0.00', v2: '0.00', v3: '0.00', v4: '0.00', v5: '0.00', v6: '0.00' } }
function emptyShippingThresholds(): ShippingThresholdDraft { return { v1: '', v2: '', v3: '', v4: '', v5: '', v6: '' } }
const registrationTiers = ['V1', 'V2', 'V3', 'V4', 'V5', 'V6'] as const
const draft = reactive({ domain: '', logoUrl: '/ytpeps-logo.svg', theme: 'classic', code: '', name: '', active: true, shippingFees: zeroShippingFees(), freeShippingThresholds: emptyShippingThresholds(), adminAccount: '', adminName: '', password: '', confirmation: '', registrationEnabled: false, registrationDefaultTier: 'V1' as typeof registrationTiers[number], commissionEnabled: true, cryptoEnabled: true, onlinePaymentLinkEnabled: false, smtpHost: '', smtpPort: '587', smtpUsername: '', smtpPassword: '', tlsMode: 'starttls' as 'starttls' | 'ssl', senderEmail: '', senderName: '', testRecipient: '' })
function message(e: unknown) { return e instanceof Error ? e.message : 'Request failed.' }
async function load(page = result.value.page) {
  loading.value = true; error.value = ''
  try { result.value = await merchantApi.page(page) } catch (e) { error.value = message(e) } finally { loading.value = false }
}
function setPage(page: number) {
  if (loading.value || page < 1 || page > result.value.totalPages || page === result.value.page) return
  void load(page)
}
function open(item: Merchant | null = null) {
  editing.value = item; error.value = ''; mailTestStatus.value = ''
  mailExpanded.value = item?.registrationEnabled ?? false
  const shippingFees = Object.fromEntries(shippingTiers.map(tier => [tier, String(item?.shippingFees?.[tier] ?? '0.00')])) as ShippingFeeDraft
  const freeShippingThresholds = Object.fromEntries(shippingTiers.map(tier => [tier, item?.freeShippingThresholds?.[tier] == null ? '' : String(item.freeShippingThresholds[tier])])) as ShippingThresholdDraft
  Object.assign(draft, { domain: item?.domain || '', logoUrl: item?.logoUrl || '/ytpeps-logo.svg', theme: item?.theme || 'classic', code: item?.code || '', name: item?.name || '', active: item?.active ?? true, shippingFees, freeShippingThresholds, adminAccount: '', adminName: '', password: '', confirmation: '', registrationEnabled: item?.registrationEnabled ?? false, registrationDefaultTier: item?.registrationDefaultTier || 'V1', commissionEnabled: item?.commissionEnabled ?? true, cryptoEnabled: item?.cryptoEnabled ?? true, onlinePaymentLinkEnabled: item?.onlinePaymentLinkEnabled ?? false, smtpHost: item?.mailSettings?.smtpHost || '', smtpPort: String(item?.mailSettings?.smtpPort || 587), smtpUsername: item?.mailSettings?.smtpUsername || '', smtpPassword: '', tlsMode: item?.mailSettings?.tlsMode || 'starttls', senderEmail: item?.mailSettings?.senderEmail || '', senderName: item?.mailSettings?.senderName || '', testRecipient: '' })
  modal.value = true
}
function close() { if (!saving.value && !testingMail.value && !uploadingLogo.value) { modal.value = false; draft.password = ''; draft.confirmation = ''; draft.smtpPassword = '' } }
function chooseLogo() { logoFileInput.value?.click() }
async function uploadLogo(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || uploadingLogo.value) return
  error.value = ''
  if (!['image/jpeg', 'image/png'].includes(file.type) || file.size === 0 || file.size > 5 * 1024 * 1024) {
    error.value = t('请选择不超过 5 MiB 的 JPEG 或 PNG 图片。')
    return
  }
  uploadingLogo.value = true
  try { draft.logoUrl = (await merchantApi.uploadLogo(file)).logoUrl }
  catch (e) { error.value = message(e) }
  finally { uploadingLogo.value = false }
}
function registrationChanged() { if (draft.registrationEnabled) mailExpanded.value = true }
function toggleMail(event: Event) { mailExpanded.value = (event.target as HTMLDetailsElement).open }
function revealInvalidField(event: Event) {
  const details = (event.target as HTMLElement).closest<HTMLDetailsElement>('.merchant-mail-settings')
  if (details) { details.open = true; mailExpanded.value = true }
}
function mailSettingsPayload(): MerchantMailSettingsInput | null {
  if (editing.value && !mailSettingsDirty.value) return null
  const hasSettings = [draft.smtpHost, draft.smtpUsername, draft.smtpPassword, draft.senderEmail, draft.senderName].some(value => value.trim())
  if (!hasSettings) return null
  const smtpPort = String(draft.smtpPort ?? '').trim()
  const port = smtpPort ? Number(smtpPort) : null
  return { smtpHost: draft.smtpHost.trim(), smtpPort: port, smtpUsername: draft.smtpUsername.trim(), smtpPassword: draft.smtpPassword, tlsMode: draft.tlsMode, senderEmail: draft.senderEmail.trim(), senderName: draft.senderName.trim() }
}
const mailSettingsDirty = computed(() => {
  const saved = editing.value?.mailSettings
  return !editing.value || Boolean(draft.smtpPassword) || draft.smtpHost !== (saved?.smtpHost || '')
    || String(draft.smtpPort ?? '') !== String(saved?.smtpPort || 587) || draft.smtpUsername !== (saved?.smtpUsername || '')
    || draft.tlsMode !== (saved?.tlsMode || 'starttls') || draft.senderEmail !== (saved?.senderEmail || '')
    || draft.senderName !== (saved?.senderName || '')
})
const mailTestHint = computed(() => {
  if (saving.value) return t('邮件设置正在保存，请稍后再测试。')
  if (testingMail.value) return t('测试邮件正在发送，请稍候。')
  if (mailSettingsDirty.value) return t('邮件设置有未保存的修改，请先保存并重新打开商户后再测试。')
  if (!editing.value?.mailSettings?.passwordConfigured) return t('请先完整配置并保存 SMTP 信息，之后才能发送测试邮件。')
  return t('测试邮件使用已保存的配置，修改后请先保存。')
})
async function save() {
  if (saving.value || testingMail.value || uploadingLogo.value) return
  error.value = ''
  const validThreshold = (value: string) => {
    const amount = String(value ?? '').trim()
    return !amount || (/^\d+(\.\d{1,2})?$/.test(amount) && Number(amount) > 0)
  }
  if (!draft.name.trim() || !shippingTiers.every(tier => /^\d+(\.\d{1,2})?$/.test(String(draft.shippingFees[tier] ?? '').trim()))) { error.value = t('请填写商户名称及 V1 至 V6 的有效运费。'); return }
  if (!shippingTiers.every(tier => validThreshold(draft.freeShippingThresholds[tier]))) { error.value = t('免邮门槛须留空或填写大于 0 且最多两位小数的金额。'); return }
  const passwordRequested = !editing.value || Boolean(draft.password || draft.confirmation)
  if (passwordRequested && (!draft.password.trim() || draft.password.length < 12 || draft.password.length > 64 || draft.password !== draft.confirmation)) { error.value = t('密码须为 12–64 位，两次输入需一致。'); return }
  if (draft.registrationEnabled && (!draft.smtpHost.trim() || !draft.smtpUsername.trim() || !draft.senderEmail.trim() || !draft.senderName.trim() || (!draft.smtpPassword && !editing.value?.mailSettings?.passwordConfigured))) { mailExpanded.value = true; error.value = t('开放注册前请完整配置 SMTP 邮件信息。'); return }
  saving.value = true
  try {
    const mailSettings = mailSettingsPayload()
    const shippingFees = Object.fromEntries(shippingTiers.map(tier => [tier, String(draft.shippingFees[tier] ?? '').trim()])) as MerchantShippingFees
    const freeShippingThresholds = Object.fromEntries(shippingTiers.map(tier => [tier, String(draft.freeShippingThresholds[tier] ?? '').trim() || null])) as MerchantFreeShippingThresholds
    if (editing.value) await merchantApi.update(editing.value.id, { domain: draft.domain.trim(), logoUrl: draft.logoUrl.trim(), theme: draft.theme, name: draft.name.trim(), active: draft.active, shippingFees, freeShippingThresholds, expectedUpdatedAt: editing.value.updatedAt, registrationEnabled: draft.registrationEnabled, registrationDefaultTier: draft.registrationDefaultTier, commissionEnabled: draft.commissionEnabled, cryptoEnabled: draft.cryptoEnabled, onlinePaymentLinkEnabled: draft.onlinePaymentLinkEnabled, mailSettings, ...(passwordRequested ? { password: draft.password, confirmation: draft.confirmation } : {}) })
    else await merchantApi.create({ code: draft.code.trim(), domain: draft.domain.trim(), logoUrl: draft.logoUrl.trim(), theme: draft.theme, name: draft.name.trim(), shippingFees, freeShippingThresholds, adminAccount: draft.adminAccount.trim(), adminName: draft.adminName.trim(), password: draft.password, confirmation: draft.confirmation, registrationEnabled: draft.registrationEnabled, registrationDefaultTier: draft.registrationDefaultTier, commissionEnabled: draft.commissionEnabled, cryptoEnabled: draft.cryptoEnabled, onlinePaymentLinkEnabled: draft.onlinePaymentLinkEnabled, mailSettings })
    modal.value = false; draft.password = ''; draft.confirmation = ''; draft.smtpPassword = ''
    await load(); emit('saved')
  } catch (e) {
    if (e instanceof ApiRequestError && ['INVALID_MERCHANT_EMAIL_SETTINGS', 'MERCHANT_EMAIL_REQUIRED'].includes(e.code)) mailExpanded.value = true
    error.value = e instanceof ApiRequestError && e.code === 'COMMISSION_CLOSE_BLOCKED'
      ? t('仍有未结佣金或待审核订单，请先结清佣金并处理相关订单后再关闭。')
      : message(e)
  } finally { saving.value = false }
}
async function testEmail() {
  if (!editing.value || testingMail.value || saving.value) return
  error.value = ''; mailTestStatus.value = ''
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(draft.testRecipient.trim())) { error.value = t('请输入有效的测试收件邮箱。'); return }
  testingMail.value = true
  try { await merchantApi.testEmail(editing.value.id, draft.testRecipient.trim()); mailTestStatus.value = t('测试邮件已发送。') }
  catch (e) { error.value = message(e) }
  finally { testingMail.value = false }
}
onMounted(() => load())
</script>

<template>
  <section class="panel active">
    <p v-if="error && !modal" class="field-error" role="alert">{{ t(error) }}</p>
    <div class="card">
      <div class="card-head"><button class="btn platform-primary-button" :disabled="loading" @click="open()">{{ t('新增商户') }}</button><button class="btn btn-outline" :disabled="loading" @click="load()">{{ t('刷新') }}</button></div>
      <div class="card-body table-wrap" :aria-busy="loading">
        <p v-if="loading">{{ t('加载中…') }}</p>
        <table v-else class="admin-table"><thead><tr><th>{{ t('商城 Logo') }}</th><th>{{ t('商户编号') }}</th><th>{{ t('商户名称') }}</th><th>{{ t('状态') }}</th><th>{{ t('买家注册') }}</th><th>{{ t('新注册等级') }}</th><th>{{ t('佣金机制') }}</th><th>{{ t('等级运费 / 免邮门槛') }}</th><th>{{ t('操作') }}</th></tr></thead>
          <tbody><tr v-for="item in result.items" :key="item.id"><td><span class="merchant-list-logo"><img :src="item.logoUrl || '/ytpeps-logo.svg'" :alt="item.name"></span></td><td>{{ item.code }}</td><td>{{ item.name }}<small class="merchant-domain">{{ item.domain }}</small></td><td>{{ t(item.active ? '启用' : '停用') }}</td><td>{{ t(item.registrationEnabled ? '开放' : '未开放') }}</td><td>{{ item.registrationDefaultTier }}</td><td>{{ t(item.commissionEnabled ? '开启' : '关闭') }}</td><td><span v-for="tier in shippingTiers" :key="tier">{{ tier.toUpperCase() }} USD {{ item.shippingFees[tier] }}<template v-if="item.freeShippingThresholds?.[tier] != null"> · {{ t('满 {0} 免邮', [item.freeShippingThresholds[tier]]) }}</template><br></span></td><td><button class="link-button" @click="open(item)">{{ t('编辑') }}</button></td></tr><tr v-if="!result.items.length"><td colspan="9">{{ t('暂无商户') }}</td></tr></tbody>
        </table>
      </div>
      <div v-if="result.total" class="admin-pagination">
        <span>{{ t('共 {0} 条 · 第 {1} / {2} 页', [result.total, result.page, result.totalPages]) }}</span>
        <div>
          <button type="button" :disabled="loading || result.page <= 1" @click="setPage(1)">«</button>
          <button type="button" :disabled="loading || result.page <= 1" @click="setPage(result.page - 1)">‹</button>
          <button v-if="pageNumbers[0] > 1" type="button" :disabled="loading" @click="setPage(1)">1</button>
          <span v-if="pageNumbers[0] > 2">…</span>
          <button v-for="page in pageNumbers" :key="page" type="button" :class="{ active: result.page === page }" :aria-current="result.page === page ? 'page' : undefined" :disabled="loading" @click="setPage(page)">{{ page }}</button>
          <span v-if="pageNumbers[pageNumbers.length - 1] < result.totalPages - 1">…</span>
          <button v-if="pageNumbers[pageNumbers.length - 1] < result.totalPages" type="button" :disabled="loading" @click="setPage(result.totalPages)">{{ result.totalPages }}</button>
          <button type="button" :disabled="loading || result.page >= result.totalPages" @click="setPage(result.page + 1)">›</button>
          <button type="button" :disabled="loading || result.page >= result.totalPages" @click="setPage(result.totalPages)">»</button>
        </div>
      </div>
    </div>
    <AppModal v-if="modal" :label="t(editing ? '编辑商户' : '新增商户')" content-class="seller-modal merchant-editor-modal" @close="close">
      <form class="merchant-editor" @submit.prevent="save" @invalid.capture="revealInvalidField">
        <header class="merchant-editor-heading">
          <div>
            <h2 class="modal-title">{{ t(editing ? '编辑商户' : '新增商户') }}</h2>
            <p v-if="editing">{{ editing.name }} · {{ editing.code }}</p>
          </div>
          <button type="button" class="merchant-editor-close" :aria-label="t('关闭')" :disabled="saving || testingMail || uploadingLogo" @click="close">×</button>
        </header>

        <div class="merchant-editor-body">
          <fieldset :disabled="saving || testingMail || uploadingLogo" class="merchant-fields">
            <section class="merchant-form-section" aria-labelledby="merchant-basic-title">
              <header class="merchant-section-heading"><h3 id="merchant-basic-title">{{ t('基本信息') }}</h3></header>
              <div class="merchant-section-content">
                <div class="form-grid">
                  <label class="form-group">{{ t('商户编号') }}<input v-model="draft.code" required pattern="[A-Za-z0-9_-]{2,32}" maxlength="32" :disabled="!!editing"></label>
                  <label class="form-group">{{ t('商户名称') }}<input v-model="draft.name" required maxlength="100"></label>
                  <label class="form-group merchant-field-wide">{{ t('商城域名') }}<input v-model="draft.domain" required maxlength="253" placeholder="shop.example.com"></label>
                </div>
                <div v-if="editing" class="merchant-setting-row">
                  <div><strong>{{ t('商户状态') }}</strong></div>
                  <label class="checkbox-field"><input v-model="draft.active" type="checkbox">{{ t('启用商户') }}</label>
                </div>
              </div>
            </section>

            <section class="merchant-form-section" aria-labelledby="merchant-storefront-title">
              <header class="merchant-section-heading"><h3 id="merchant-storefront-title">{{ t('商城设置') }}</h3></header>
              <div class="merchant-section-content">
                <div class="form-grid">
                  <div class="form-group merchant-field-wide merchant-logo-field">
                    <label for="merchant-logo-input">{{ t('商城 Logo') }}</label>
                    <div class="merchant-logo-control" :aria-busy="uploadingLogo">
                      <div class="merchant-logo-preview"><img :src="draft.logoUrl" alt=""></div>
                      <div class="merchant-logo-actions">
                        <input id="merchant-logo-input" ref="logoFileInput" class="merchant-logo-input" type="file" accept="image/png,image/jpeg" @change="uploadLogo">
                        <button type="button" class="btn btn-outline" :disabled="saving || testingMail || uploadingLogo" @click="chooseLogo">{{ t(uploadingLogo ? '上传中…' : '上传或更换 Logo') }}</button>
                        <span v-if="uploadingLogo" role="status">{{ t('上传中…') }}</span>
                      </div>
                    </div>
                  </div>
                  <label class="form-group merchant-field-wide">{{ t('商城样式') }}<select v-model="draft.theme"><option value="classic">{{ t('经典') }}</option><option value="forest">{{ t('森林') }}</option><option value="editorial">{{ t('简约目录') }}</option></select></label>
                </div>
                <div class="merchant-shipping-tier-list">
                  <div v-for="tier in shippingTiers" :key="tier" class="merchant-shipping-tier-row">
                    <label class="form-group">{{ tier.toUpperCase() }} {{ t('等级运费') }} (USD)<input v-model="draft.shippingFees[tier]" required type="number" min="0" step="0.01"></label>
                    <label class="form-group">{{ tier.toUpperCase() }} {{ t('免邮门槛') }} (USD)<input v-model="draft.freeShippingThresholds[tier]" type="text" inputmode="decimal" :placeholder="t('不启用')"></label>
                  </div>
                </div>
                <div class="merchant-setting-row merchant-commission-setting">
                  <div><strong>{{ t('佣金机制') }}</strong></div>
                  <label class="checkbox-field"><input v-model="draft.commissionEnabled" type="checkbox">{{ t('启用佣金') }}</label>
                </div>
              </div>
            </section>

            <section class="merchant-form-section" aria-labelledby="merchant-payment-title">
              <header class="merchant-section-heading"><h3 id="merchant-payment-title">{{ t('客户可用的付款方式') }}</h3></header>
              <div class="merchant-section-content">
                <div class="merchant-setting-row merchant-registration-setting">
                  <div><strong>{{ t('加密货币') }}</strong></div>
                  <label class="checkbox-field"><input v-model="draft.cryptoEnabled" type="checkbox">{{ t('启用') }}</label>
                </div>
                <div class="merchant-setting-row merchant-registration-setting">
                  <div><strong>{{ t('线上支付链接') }}</strong></div>
                  <label class="checkbox-field"><input v-model="draft.onlinePaymentLinkEnabled" type="checkbox">{{ t('启用') }}</label>
                </div>
              </div>
            </section>

            <section class="merchant-form-section" aria-labelledby="merchant-admin-title">
              <header class="merchant-section-heading"><h3 id="merchant-admin-title">{{ t('管理员账号') }}</h3></header>
              <div class="merchant-section-content">
                <div v-if="!editing" class="form-grid">
                  <label class="form-group">{{ t('初始管理员账号') }}<input v-model="draft.adminAccount" required maxlength="64" autocomplete="off"></label>
                  <label class="form-group">{{ t('管理员姓名') }}<input v-model="draft.adminName" required maxlength="100"></label>
                  <label class="form-group">{{ t('密码') }}<input v-model="draft.password" required type="password" minlength="12" maxlength="64" autocomplete="new-password"></label>
                  <label class="form-group">{{ t('确认密码') }}<input v-model="draft.confirmation" required type="password" minlength="12" maxlength="64" autocomplete="new-password"></label>
                </div>
                <template v-else>
                  <div v-if="editing.administrator" class="merchant-admin-identity"><span>{{ t('初始管理员账号') }}</span><strong>{{ editing.administrator.account }}</strong><small>{{ editing.administrator.name }}</small></div>
                  <div class="form-grid">
                    <label class="form-group">{{ t('管理员新密码') }}<input v-model="draft.password" type="password" minlength="12" maxlength="64" autocomplete="new-password" :disabled="!editing.administrator" :required="!!draft.confirmation" :placeholder="t('留空则保持原密码')"></label>
                    <label class="form-group">{{ t('确认新密码') }}<input v-model="draft.confirmation" type="password" minlength="12" maxlength="64" autocomplete="new-password" :disabled="!editing.administrator" :required="!!draft.password"></label>
                  </div>
                </template>
              </div>
            </section>

            <section class="merchant-form-section" aria-labelledby="merchant-registration-title">
              <header class="merchant-section-heading"><h3 id="merchant-registration-title">{{ t('注册与邮件') }}</h3></header>
              <div class="merchant-section-content">
                <div class="merchant-setting-row merchant-registration-setting">
                  <div><strong>{{ t('买家邮箱注册') }}</strong></div>
                  <label class="checkbox-field"><input v-model="draft.registrationEnabled" type="checkbox" @change="registrationChanged">{{ t('开放注册') }}</label>
                </div>
                <label class="form-group">{{ t('新注册客户默认价格等级') }}<select v-model="draft.registrationDefaultTier"><option v-for="tier in registrationTiers" :key="tier" :value="tier">{{ tier }}</option></select></label>
                <p class="merchant-mail-test-hint">{{ t('该等级用于自助注册客户；填写邀请码只建立推荐关系，不改变等级。') }}</p>
                <details class="merchant-mail-settings" :open="mailExpanded" @toggle="toggleMail">
                  <summary><span>{{ t('邮件发送设置') }}</span><small>{{ editing?.mailSettings?.passwordConfigured ? t('已保存配置') : t('尚未配置') }}</small></summary>
                  <div class="merchant-mail-body">
                    <div class="form-grid">
                      <label class="form-group">SMTP {{ t('服务器') }}<input v-model="draft.smtpHost" maxlength="253" placeholder="smtp.example.com"></label>
                      <label class="form-group">SMTP {{ t('端口') }}<input v-model="draft.smtpPort" type="number" min="1" max="65535"></label>
                      <label class="form-group">SMTP {{ t('账号') }}<input v-model="draft.smtpUsername" maxlength="255" autocomplete="off"></label>
                      <label class="form-group">SMTP {{ t('密码') }}<input v-model="draft.smtpPassword" type="password" maxlength="255" autocomplete="new-password" :placeholder="editing?.mailSettings?.passwordConfigured ? t('留空则保持已保存的密码') : ''"></label>
                      <label class="form-group">{{ t('加密方式') }}<select v-model="draft.tlsMode"><option value="starttls">STARTTLS</option><option value="ssl">SSL/TLS</option></select></label>
                      <label class="form-group">{{ t('发件邮箱') }}<input v-model="draft.senderEmail" type="email" maxlength="254"></label>
                      <label class="form-group merchant-field-wide">{{ t('发件人名称') }}<input v-model="draft.senderName" maxlength="100"></label>
                    </div>
                    <template v-if="editing">
                      <div class="registration-test-row">
                        <label class="form-group">{{ t('测试收件邮箱') }}<input v-model="draft.testRecipient" type="text" inputmode="email" maxlength="254"></label>
                        <button type="button" class="btn btn-outline" :disabled="saving || testingMail || mailSettingsDirty || !editing.mailSettings?.passwordConfigured" aria-describedby="merchant-mail-test-hint" @click="testEmail">{{ testingMail ? t('发送中…') : t('发送测试邮件') }}</button>
                      </div>
                      <p id="merchant-mail-test-hint" class="merchant-mail-test-hint" :class="{ 'is-warning': saving || testingMail || mailSettingsDirty || !editing.mailSettings?.passwordConfigured }" role="status">{{ mailTestHint }}</p>
                    </template>
                    <p v-if="mailTestStatus" class="merchant-mail-test-status" role="status">{{ mailTestStatus }}</p>
                  </div>
                </details>
              </div>
            </section>
          </fieldset>
        </div>

        <footer class="merchant-editor-footer">
          <p v-if="error" class="field-error" role="alert">{{ t(error) }}</p>
          <div class="modal-actions"><button type="button" class="modal-cancel" :disabled="saving || testingMail || uploadingLogo" @click="close">{{ t('取消') }}</button><button type="submit" class="modal-save" :disabled="saving || testingMail || uploadingLogo">{{ t(saving ? '保存中…' : editing ? '保存修改' : '创建商户') }}</button></div>
        </footer>
      </form>
    </AppModal>
  </section>
</template>
<style scoped>
.merchant-domain { display:block; color:#667085; margin-top:4px; }
.merchant-fields { border: 0; padding: 0; margin: 0; min-width: 0; }
.registration-test-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 12px; align-items: end; margin-top: 8px; }
@media (max-width: 640px) { .form-grid { grid-template-columns: 1fr; } }
@media (max-width: 640px) { .registration-test-row { grid-template-columns: 1fr; } }
</style>
