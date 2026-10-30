<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import AppModal from './AppModal.vue'
import { ApiRequestError, api, setToken } from '../lib/api'
import { SELLER_SESSIONS_KEY, invalidateSellerSession, rememberSellerSession, removeSellerSession, savedSellerSessions, type SavedSellerSession } from '../lib/seller-sessions'

const props = defineProps<{
  currentMemberId?: number
  disabled?: boolean
  canSignOut?: boolean
  signingOut?: boolean
  t: (text: string) => string
}>()
const emit = defineEmits<{ activated: []; signOut: [] }>()
const open = ref(false)
const adding = ref(false)
const sessions = ref(savedSellerSessions())
const account = ref('')
const password = ref('')
const error = ref('')
const pending = ref(false)
const locked = computed(() => pending.value || props.signingOut === true)

function refresh() { sessions.value = savedSellerSessions() }
function storageChanged(event: StorageEvent) {
  if (event.key === null || event.key === SELLER_SESSIONS_KEY) refresh()
}
onMounted(() => window.addEventListener('storage', storageChanged))
onBeforeUnmount(() => window.removeEventListener('storage', storageChanged))

function show() {
  refresh()
  adding.value = false
  account.value = ''
  password.value = ''
  error.value = ''
  open.value = true
}
function close() {
  if (locked.value) return
  password.value = ''
  open.value = false
}
function add(accountName = '') {
  account.value = accountName
  password.value = ''
  error.value = ''
  adding.value = true
}
function failure(cause: unknown) {
  error.value = cause instanceof Error ? cause.message : 'Request failed.'
}

async function select(session: SavedSellerSession) {
  if (locked.value || session.memberId === props.currentMemberId) return
  if (!session.token) { add(session.account); return }
  pending.value = true
  error.value = ''
  try {
    // Validate with the target token without replacing or clearing the current login.
    const identity = await api.sellerSession(session.token)
    rememberSellerSession(identity, session.token)
    setToken('admin', session.token)
    emit('activated')
  } catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 401) {
      invalidateSellerSession(session.token)
      refresh()
      add(session.account)
      error.value = '该账号登录已失效，请重新登录。'
    } else failure(cause)
  } finally { pending.value = false }
}

async function submit() {
  if (locked.value) return
  if (!account.value.trim() || account.value.trim().length > 64 || !password.value) {
    error.value = '请填写有效账号和密码。'
    return
  }
  pending.value = true
  error.value = ''
  try {
    const result = await api.login('admin', { account: account.value.trim(), password: password.value })
    if (!result.member) throw new Error('Request failed.')
    rememberSellerSession({ member: result.member, merchant: result.merchant }, result.accessToken)
    setToken('admin', result.accessToken)
    password.value = ''
    emit('activated')
  } catch (cause) { failure(cause) }
  finally { pending.value = false; password.value = '' }
}

function signOut() {
  if (locked.value || !props.canSignOut) return
  emit('signOut')
}

function remove(session: SavedSellerSession) {
  if (locked.value || session.memberId === props.currentMemberId) return
  removeSellerSession(session.memberId)
  refresh()
}
</script>

<template>
  <button type="button" class="seller-account-trigger" :disabled="disabled || locked" @click="show">{{ t('切换账号') }}</button>
  <Teleport v-if="open" to="body">
    <AppModal class="seller-account-overlay" :label="t('切换账号')" content-class="seller-account-modal" @close="close">
      <div class="seller-account-modal-header">
        <h2 class="modal-title">{{ t(adding ? '添加账号' : '切换账号') }}</h2>
        <button type="button" class="seller-account-close" :aria-label="t('关闭')" :disabled="locked" @click="close">
          <svg viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="m5 5 10 10M15 5 5 15" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" /></svg>
        </button>
      </div>
      <form v-if="adding" class="seller-account-form" @submit.prevent="submit">
        <label for="switch-account">{{ t('登录账号') }}</label>
        <input id="switch-account" v-model="account" autocomplete="username" maxlength="64" required :disabled="locked">
        <label for="switch-password">{{ t('密码') }}</label>
        <input id="switch-password" v-model="password" type="password" autocomplete="current-password" required :disabled="locked">
        <p v-if="error" class="form-error" role="alert">{{ t(error) }}</p>
        <div class="modal-actions">
          <button type="button" class="modal-cancel" :disabled="locked" @click="adding = false; password = ''; error = ''">{{ t('返回') }}</button>
          <button type="submit" class="modal-save" :disabled="locked">{{ t(pending ? '正在登录…' : '登录并切换') }}</button>
        </div>
      </form>
      <template v-else>
        <div class="seller-account-list" :aria-busy="locked">
          <p v-if="!sessions.length" class="seller-account-hint">{{ t('暂无已保存账号，请先添加账号。') }}</p>
          <div v-for="session in sessions" :key="session.memberId" :class="['seller-account-row', { 'is-current': session.memberId === currentMemberId }]">
            <button type="button" class="seller-account-select" :aria-current="session.memberId === currentMemberId ? 'true' : undefined" :disabled="locked || session.memberId === currentMemberId" @click="select(session)">
              <strong>{{ session.merchantName }} · {{ session.name }}</strong>
              <span>{{ session.account }}</span>
              <small v-if="session.memberId !== currentMemberId">{{ t(session.token ? '点击切换' : '需重新登录') }}</small>
            </button>
            <span v-if="session.memberId === currentMemberId" class="seller-account-current-badge"><svg viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="m3 8 3 3 7-7" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg>{{ t('当前账号') }}</span>
            <button v-if="session.memberId !== currentMemberId" type="button" class="seller-account-remove" :disabled="locked" :aria-label="`${t('移除账号')} ${session.account}`" @click="remove(session)">{{ t('移除') }}</button>
          </div>
        </div>
        <p v-if="pending" class="seller-account-hint" role="status">{{ t('正在验证登录状态…') }}</p>
        <p v-if="error" class="form-error" role="alert">{{ t(error) }}</p>
        <button type="button" class="modal-save seller-account-add-button" :disabled="locked" @click="add()">{{ t('添加账号') }}</button>
      </template>
      <div v-if="canSignOut && !adding" class="seller-account-signout">
        <button type="button" :disabled="locked" :aria-busy="signingOut" @click="signOut">{{ t('退出登录') }}</button>
      </div>
    </AppModal>
  </Teleport>
</template>

<style scoped>
.seller-account-trigger { padding: 7px 12px; border: 1px solid #e5e5ea; border-radius: 999px; background: #fff; color: #424245; font-size: 12px; cursor: pointer; }
.seller-account-trigger:hover { border-color: #06c; color: #06c; }
.seller-account-modal-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.seller-account-modal-header .modal-title { margin: 0; }
.seller-account-close { display: grid; width: 36px; height: 36px; flex: 0 0 36px; place-items: center; padding: 0; border: 0; border-radius: 50%; background: transparent; color: #6e6e73; cursor: pointer; }
.seller-account-close:hover:not(:disabled) { background: #f5f5f7; color: #1d1d1f; }
.seller-account-close svg { width: 18px; height: 18px; }
.seller-account-add-button { width: 100%; min-height: 42px; margin-top: 12px; }
.seller-account-hint { margin: 8px 0 12px; color: #6e6e73; font-size: 12px; line-height: 1.6; }
.seller-account-list { display: grid; gap: 8px; max-height: 40vh; overflow-y: auto; }
.seller-account-row { display: flex; align-items: center; gap: 8px; border: 1px solid #e5e5ea; border-radius: 10px; padding: 4px 10px; }
.seller-account-row.is-current { border-color: #06c; background: #eff6ff; }
.seller-account-row.is-current .seller-account-select:disabled { opacity: 1; }
.seller-account-current-badge { display: inline-flex; flex-shrink: 0; align-items: center; gap: 4px; padding: 5px 8px; border-radius: 999px; background: #06c; color: #fff; font-size: 12px; font-weight: 600; white-space: nowrap; }
.seller-account-current-badge svg { width: 14px; height: 14px; }
.seller-account-select { display: grid; flex: 1; min-width: 0; gap: 4px; padding: 10px 0; background: transparent; border: 0; text-align: left; cursor: pointer; color: #1d1d1f; }
.seller-account-select strong, .seller-account-select span { overflow-wrap: anywhere; }
.seller-account-select span, .seller-account-select small { font-size: 12px; color: #6e6e73; }
.seller-account-remove { flex-shrink: 0; border: 0; background: transparent; color: #06c; cursor: pointer; padding: 8px; }
.seller-account-signout { margin-top: 16px; padding-top: 12px; border-top: 1px solid #e5e5ea; }
.seller-account-signout button { width: 100%; padding: 10px; border: 0; border-radius: 8px; background: transparent; color: #d70015; font-size: 13px; cursor: pointer; }
.seller-account-signout button:hover:not(:disabled) { background: #fff0f0; }
.seller-account-form { display: grid; gap: 8px; margin-top: 16px; }
.seller-account-form label { font-size: 13px; }
.seller-account-form input { width: 100%; min-width: 0; padding: 10px 12px; border: 1px solid #d2d2d7; border-radius: 8px; }
button:disabled { cursor: default; opacity: .6; }
button:focus-visible, input:focus-visible { outline: 2px solid #06c; outline-offset: 2px; }
</style>

<style>
/* Teleported outside the filtered topbar; keep the seller palette at the new root. */
.modal-overlay.seller-account-overlay {
  --color-primary: #06c;
  --color-primary-strong: #0071e3;
  --color-surface: #fff;
  --color-line: #e5e5ea;
  z-index: 60;
  color: #1d1d1f;
  background: rgba(29, 29, 31, .32);
  backdrop-filter: blur(8px);
}
.modal-box.seller-account-modal { width: min(460px, calc(100vw - 24px)); max-height: calc(100dvh - 40px); overflow-y: auto; }
</style>
