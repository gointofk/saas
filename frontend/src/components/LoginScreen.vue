<script setup lang="ts">
import { onUnmounted, reactive, ref } from 'vue'
import { storefrontBrand } from '../lib/storefront'
import { ApiRequestError, customerRegistrationApi, type Area } from '../lib/api'
import SellerLanguageSwitch from './SellerLanguageSwitch.vue'
import AfterSalesContact from './AfterSalesContact.vue'

const props = defineProps<{
  area: Area
  account: string
  password: string
  error: string
  busy: boolean
  language: string
  registrationEnabled: boolean
  commissionEnabled: boolean
  merchantName: string
  merchantLogoUrl: string
  t: (text: string, values?: readonly unknown[]) => string
}>()

const emit = defineEmits<{
  signIn: []
  changeLanguage: []
  'update:account': [value: string]
  'update:password': [value: string]
  'update:language': [value: string]
  registered: [email: string]
}>()

const registrationMode = ref(false)
const registrationEmail = ref('')
const registrationName = ref('')
const registrationInviteCode = ref('')
const registrationCode = ref('')
const registrationPassword = ref('')
const registrationConfirmation = ref('')
const registrationError = ref('')
const registrationFieldErrors = reactive({ email: '', inviteCode: '', verificationCode: '', name: '', password: '', confirmation: '' })
const registrationSuccess = ref('')
const codeMessage = ref('')
const sendingCode = ref(false)
const submittingRegistration = ref(false)
const codeCooldown = ref(0)
let cooldownTimer: number | undefined

function openRegistration() {
  registrationMode.value = true
  registrationEmail.value = props.account
  registrationInviteCode.value = ''
  registrationError.value = ''
  clearRegistrationFieldErrors()
  registrationSuccess.value = ''
  codeMessage.value = ''
}

function backToLogin() {
  registrationMode.value = false
  registrationError.value = ''
  clearRegistrationFieldErrors()
  registrationInviteCode.value = ''
  registrationPassword.value = ''
  registrationConfirmation.value = ''
  registrationCode.value = ''
}

function message(error: unknown) {
  return error instanceof Error ? error.message : props.t('Something went wrong.')
}

function clearRegistrationFieldErrors() {
  for (const field of Object.keys(registrationFieldErrors) as Array<keyof typeof registrationFieldErrors>)
    registrationFieldErrors[field] = ''
}

function clearRegistrationFieldError(field: keyof typeof registrationFieldErrors) {
  registrationFieldErrors[field] = ''
}

function setRegistrationApiError(error: unknown) {
  if (error instanceof ApiRequestError) {
    const fieldByCode: Record<string, keyof typeof registrationFieldErrors> = {
      INVALID_EMAIL: 'email',
      INVALID_INVITATION_CODE: 'inviteCode',
      COMMISSION_FEATURE_DISABLED: 'inviteCode',
      REGISTRATION_CODE_INVALID: 'verificationCode',
      INVALID_CUSTOMER_NAME: 'name',
      INVALID_PASSWORD: 'password',
      PASSWORD_CONFIRMATION_MISMATCH: 'confirmation',
      CUSTOMER_EMAIL_EXISTS: 'email',
    }
    const field = fieldByCode[error.code]
    if (field) {
      registrationFieldErrors[field] = message(error)
      return
    }
  }
  registrationError.value = message(error)
}

function format(text: string, values: readonly unknown[]) {
  return values.reduce<string>((result, value, index) => result.replace(`{${index}}`, String(value)), props.t(text))
}

async function requestCode() {
  if (sendingCode.value || codeCooldown.value > 0) return
  registrationError.value = ''
  clearRegistrationFieldError('email')
  if (registrationEmail.value.trim().length > 128 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(registrationEmail.value.trim())) {
    registrationFieldErrors.email = props.t('Enter a valid email address of at most 128 characters.')
    return
  }
  sendingCode.value = true
  try {
    const result = await customerRegistrationApi.requestCode(registrationEmail.value.trim())
    codeMessage.value = props.t(result.message)
    codeCooldown.value = 60
    window.clearInterval(cooldownTimer)
    cooldownTimer = window.setInterval(() => {
      codeCooldown.value -= 1
      if (codeCooldown.value <= 0) window.clearInterval(cooldownTimer)
    }, 1000)
  } catch (error) {
    setRegistrationApiError(error)
  } finally {
    sendingCode.value = false
  }
}

async function register() {
  if (submittingRegistration.value) return
  registrationError.value = ''
  clearRegistrationFieldErrors()
  const email = registrationEmail.value.trim()
  const inviteCode = registrationInviteCode.value.trim().toUpperCase()
  if (email.length > 128 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))
    registrationFieldErrors.email = props.t('Enter a valid email address of at most 128 characters.')
  if (!/^[0-9]{6}$/.test(registrationCode.value.trim()))
    registrationFieldErrors.verificationCode = props.t('Enter a valid 6-digit verification code.')
  if (!registrationName.value.trim() || registrationName.value.trim().length > 100)
    registrationFieldErrors.name = props.t('Name is required and must be at most 100 characters.')
  if (props.commissionEnabled && inviteCode && !/^[0-9A-Z]{8}$/.test(inviteCode))
    registrationFieldErrors.inviteCode = props.t('Invitation code must contain 8 letters or digits.')
  if (registrationPassword.value.length < 12 || registrationPassword.value.length > 64)
    registrationFieldErrors.password = props.t('Passwords must be 12–64 characters.')
  if (registrationPassword.value !== registrationConfirmation.value)
    registrationFieldErrors.confirmation = props.t('The passwords do not match.')
  if (Object.values(registrationFieldErrors).some(Boolean)) return
  submittingRegistration.value = true
  try {
    await customerRegistrationApi.register({
      email,
      name: registrationName.value.trim(),
      verificationCode: registrationCode.value.trim(),
      password: registrationPassword.value,
      confirmation: registrationConfirmation.value,
      ...(props.commissionEnabled && inviteCode ? { inviteCode } : {}),
    })
    registrationMode.value = false
    registrationSuccess.value = props.t('Registration complete. Sign in with your email and password.')
    emit('registered', registrationEmail.value.trim())
    registrationPassword.value = ''
    registrationConfirmation.value = ''
    registrationCode.value = ''
  } catch (error) {
    setRegistrationApiError(error)
  } finally {
    submittingRegistration.value = false
  }
}

onUnmounted(() => window.clearInterval(cooldownTimer))
</script>

<template>
  <div :class="['auth-screen', { 'customer-auth-screen': area === 'customer' }]">
    <header v-if="area === 'customer'" class="auth-header">
      <div class="brand">
        <img v-if="storefrontBrand.logoUrl" class="brand-icon" :src="storefrontBrand.logoUrl" :alt="storefrontBrand.name">
        <span class="brand-name">{{ storefrontBrand.name }}</span>
      </div>
      <div class="auth-header-actions">
        <label class="auth-language-picker">
          <span class="sr-only">{{ t('Language') }}</span>
          <select :value="language" @change="emit('update:language', ($event.target as HTMLSelectElement).value); emit('changeLanguage')">
            <option value="en">EN</option>
            <option value="zh-CN">中文</option>
          </select>
        </label>
      </div>
    </header>

    <div :class="['auth-shell', { 'seller-auth-shell': area === 'admin' }]">
      <div v-if="area === 'admin'" class="seller-login-brand">
        <img v-if="merchantLogoUrl" class="brand-icon" :src="merchantLogoUrl" :alt="merchantName || 'DailyActivity'">
        <strong>{{ merchantName || 'DailyActivity' }}</strong>
        <small>{{ t('商家管理后台') }}</small>
        <SellerLanguageSwitch :model-value="language" :label="t('Language')" @update:model-value="emit('update:language', $event); emit('changeLanguage')" />
      </div>
      <section v-if="area === 'customer'" class="auth-story-panel">
        <div>
          <h2>{{ t('Research materials,') }}<br>{{ t('clearly documented.') }}</h2>
          <p>{{ t('We are a peptide source factory, specializing in bulk peptide orders. We take 100% responsibility for product quality, logistics transportation and customs‑clearance issues.') }}</p>
        </div>
      </section>

      <section class="auth-card">
        <div class="auth-brand customer-only-brand">
          <img v-if="area === 'customer' && storefrontBrand.logoUrl" class="brand-icon" :src="storefrontBrand.logoUrl" alt="">
          <div><strong>{{ storefrontBrand.name }}</strong><small>{{ t('Research materials marketplace') }}</small></div>
        </div>
        <div class="auth-copy">
          <h1>{{ area === 'admin' ? t('登录商家后台') : registrationMode ? t('Create account') : t('Sign In') }}</h1>
          <p v-if="area === 'admin' || !registrationMode">{{ area === 'admin' ? t('管理订单、商品、客户与收款渠道') : t('Sign in with your email and password') }}</p>
        </div>
        <p v-if="registrationSuccess && !registrationMode" class="form-success" role="status">{{ registrationSuccess }}</p>
        <form v-if="!registrationMode" class="auth-form" @submit.prevent="emit('signIn')">
          <label>
            {{ area === 'admin' ? t('登录账号') : t('Email') }}
            <input
              :value="account"
              :type="area === 'admin' ? 'text' : 'email'"
              :placeholder="area === 'admin' ? t('请输入登录账号') : t('your@email.com')"
              autocomplete="username"
              :aria-invalid="Boolean(error)"
              :aria-describedby="error ? 'login-error' : undefined"
              @input="emit('update:account', ($event.target as HTMLInputElement).value)"
            >
          </label>
          <label>
            {{ area === 'admin' ? t('密码') : t('Password') }}
            <input
              :value="password"
              type="password"
              :placeholder="area === 'admin' ? t('请输入密码') : t('Password')"
              autocomplete="current-password"
              :aria-invalid="Boolean(error)"
              :aria-describedby="error ? 'login-error' : undefined"
              @input="emit('update:password', ($event.target as HTMLInputElement).value)"
            >
          </label>
          <p v-if="error" id="login-error" class="form-error" role="alert">{{ t(error) }}</p>
          <button class="button button-dark wide" :disabled="busy">
            {{ busy ? t('Signing in…') : area === 'admin' ? t('登录') : t('Sign In') }}
          </button>
        </form>
        <p v-if="!registrationMode && area === 'customer' && registrationEnabled" class="auth-mode-switch">
          <span>{{ t('New to the store?') }}</span>
          <button type="button" @click="openRegistration">{{ t('Register') }}</button>
        </p>
        <form v-if="registrationMode" class="auth-form registration-form" novalidate @submit.prevent="register">
          <label>{{ t('Email') }}
            <div class="registration-code-row">
              <input v-model="registrationEmail" type="email" autocomplete="email" required maxlength="128" :disabled="sendingCode || submittingRegistration" :class="{ 'registration-input-invalid': registrationFieldErrors.email }" :aria-invalid="Boolean(registrationFieldErrors.email)" :aria-describedby="registrationFieldErrors.email ? 'registration-email-error' : undefined" placeholder="your@email.com" @input="clearRegistrationFieldError('email')">
              <button class="button registration-code-button" type="button" :disabled="sendingCode || submittingRegistration || codeCooldown > 0" @click="requestCode">{{ sendingCode ? t('Sending…') : codeCooldown > 0 ? format('Resend in {0}s', [codeCooldown]) : t('Send code') }}</button>
            </div>
            <p v-if="registrationFieldErrors.email" id="registration-email-error" class="registration-field-error" role="alert">{{ t(registrationFieldErrors.email) }}</p>
          </label>
          <p v-if="codeMessage" class="registration-info" role="status">{{ codeMessage }}</p>
          <label>{{ t('Verification code') }}<input v-model="registrationCode" inputmode="numeric" autocomplete="one-time-code" required maxlength="6" pattern="[0-9]{6}" placeholder="000000" :disabled="submittingRegistration" :class="{ 'registration-input-invalid': registrationFieldErrors.verificationCode }" :aria-invalid="Boolean(registrationFieldErrors.verificationCode)" :aria-describedby="registrationFieldErrors.verificationCode ? 'registration-code-error' : undefined" @input="clearRegistrationFieldError('verificationCode')"><p v-if="registrationFieldErrors.verificationCode" id="registration-code-error" class="registration-field-error" role="alert">{{ t(registrationFieldErrors.verificationCode) }}</p></label>
          <label>{{ t('Full name') }}<input v-model="registrationName" autocomplete="name" required maxlength="100" :disabled="submittingRegistration" :class="{ 'registration-input-invalid': registrationFieldErrors.name }" :aria-invalid="Boolean(registrationFieldErrors.name)" :aria-describedby="registrationFieldErrors.name ? 'registration-name-error' : undefined" @input="clearRegistrationFieldError('name')"><p v-if="registrationFieldErrors.name" id="registration-name-error" class="registration-field-error" role="alert">{{ t(registrationFieldErrors.name) }}</p></label>
          <label v-if="commissionEnabled">{{ t('Invitation code (optional)') }}<input v-model="registrationInviteCode" type="text" autocomplete="off" autocapitalize="characters" maxlength="8" :disabled="submittingRegistration" :class="{ 'registration-input-invalid': registrationFieldErrors.inviteCode }" :aria-invalid="Boolean(registrationFieldErrors.inviteCode)" :aria-describedby="registrationFieldErrors.inviteCode ? 'registration-invite-error' : undefined" @input="clearRegistrationFieldError('inviteCode')"><p v-if="registrationFieldErrors.inviteCode" id="registration-invite-error" class="registration-field-error" role="alert">{{ t(registrationFieldErrors.inviteCode) }}</p></label>
          <label>{{ t('Password') }}<input v-model="registrationPassword" type="password" autocomplete="new-password" required minlength="12" maxlength="64" :disabled="submittingRegistration" :class="{ 'registration-input-invalid': registrationFieldErrors.password }" :aria-invalid="Boolean(registrationFieldErrors.password)" :aria-describedby="registrationFieldErrors.password ? 'registration-password-error' : undefined" @input="clearRegistrationFieldError('password')"><p v-if="registrationFieldErrors.password" id="registration-password-error" class="registration-field-error" role="alert">{{ t(registrationFieldErrors.password) }}</p></label>
          <label>{{ t('Confirm password') }}<input v-model="registrationConfirmation" type="password" autocomplete="new-password" required minlength="12" maxlength="64" :disabled="submittingRegistration" :class="{ 'registration-input-invalid': registrationFieldErrors.confirmation }" :aria-invalid="Boolean(registrationFieldErrors.confirmation)" :aria-describedby="registrationFieldErrors.confirmation ? 'registration-confirmation-error' : undefined" @input="clearRegistrationFieldError('confirmation')"><p v-if="registrationFieldErrors.confirmation" id="registration-confirmation-error" class="registration-field-error" role="alert">{{ t(registrationFieldErrors.confirmation) }}</p></label>
          <p v-if="registrationError" class="form-error" role="alert">{{ t(registrationError) }}</p>
          <button class="button button-dark wide" :disabled="submittingRegistration || sendingCode">{{ submittingRegistration ? t('Creating account…') : t('Create account') }}</button>
          <button type="button" class="auth-back-button" :disabled="submittingRegistration" @click="backToLogin">{{ t('Back to sign in') }}</button>
        </form>
        <slot v-if="!registrationMode" />
        <p v-if="area === 'admin' || (!registrationMode && !registrationEnabled)" class="auth-hint">{{ area === 'admin' ? t('请使用管理员提供的后台账号。') : t('Please contact your seller to request an account.') }}</p>
      </section>
    </div>

    <footer v-if="area === 'customer'" class="customer-footer auth-page-footer">
      <div class="footer-top">
        <div class="footer-left">
          <div class="footer-company-name">{{ storefrontBrand.name }}</div>
          <p class="footer-desc">{{ t('All materials listed are supplied for laboratory research use only and are not for human or veterinary use, food, or drug applications.') }}</p>
        </div>
        <div class="footer-right"><AfterSalesContact :t="t" :whatsapp-number="storefrontBrand.whatsappNumber" /><p><a href="#" @click.prevent>{{ t('Terms & Conditions') }}</a>© {{ new Date().getFullYear() }} {{ storefrontBrand.name }}</p></div>
      </div>
    </footer>
    <p v-else class="auth-footer">{{ t('商家管理后台') }}</p>
  </div>
</template>
