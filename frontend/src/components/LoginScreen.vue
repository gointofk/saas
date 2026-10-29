<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { storefrontBrand } from '../lib/storefront'
import { customerRegistrationApi, type Area } from '../lib/api'
import SellerLanguageSwitch from './SellerLanguageSwitch.vue'

const props = defineProps<{
  area: Area
  account: string
  password: string
  error: string
  busy: boolean
  language: string
  registrationEnabled: boolean
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
const registrationCode = ref('')
const registrationPassword = ref('')
const registrationConfirmation = ref('')
const registrationError = ref('')
const registrationSuccess = ref('')
const codeMessage = ref('')
const sendingCode = ref(false)
const submittingRegistration = ref(false)
const codeCooldown = ref(0)
let cooldownTimer: number | undefined

function openRegistration() {
  registrationMode.value = true
  registrationEmail.value = props.account
  registrationError.value = ''
  registrationSuccess.value = ''
  codeMessage.value = ''
}

function backToLogin() {
  registrationMode.value = false
  registrationError.value = ''
  registrationPassword.value = ''
  registrationConfirmation.value = ''
  registrationCode.value = ''
}

function message(error: unknown) {
  return error instanceof Error ? error.message : props.t('Something went wrong.')
}

function format(text: string, values: readonly unknown[]) {
  return values.reduce<string>((result, value, index) => result.replace(`{${index}}`, String(value)), props.t(text))
}

async function requestCode() {
  if (sendingCode.value || codeCooldown.value > 0) return
  registrationError.value = ''
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(registrationEmail.value.trim())) {
    registrationError.value = props.t('Enter a valid email address of at most 128 characters.')
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
    registrationError.value = message(error)
  } finally {
    sendingCode.value = false
  }
}

async function register() {
  if (submittingRegistration.value) return
  registrationError.value = ''
  if (registrationName.value.trim().length < 1 || registrationName.value.trim().length > 100) {
    registrationError.value = props.t('Name is required and must be at most 100 characters.')
    return
  }
  if (registrationPassword.value.length < 12 || registrationPassword.value.length > 64) {
    registrationError.value = props.t('Passwords must be 12–64 characters.')
    return
  }
  if (registrationPassword.value !== registrationConfirmation.value) {
    registrationError.value = props.t('The passwords do not match.')
    return
  }
  submittingRegistration.value = true
  try {
    await customerRegistrationApi.register({
      email: registrationEmail.value.trim(),
      name: registrationName.value.trim(),
      verificationCode: registrationCode.value.trim(),
      password: registrationPassword.value,
      confirmation: registrationConfirmation.value,
    })
    registrationMode.value = false
    registrationSuccess.value = props.t('Registration complete. Sign in with your email and password.')
    emit('registered', registrationEmail.value.trim())
    registrationPassword.value = ''
    registrationConfirmation.value = ''
    registrationCode.value = ''
  } catch (error) {
    registrationError.value = message(error)
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
        <img class="brand-icon" :src="area === 'customer' ? storefrontBrand.logoUrl : '/ytpeps-logo.svg'" :alt="storefrontBrand.name">
        <span class="brand-name">{{ storefrontBrand.name }}</span>
      </div>
      <div class="auth-header-actions">
        <button v-if="registrationEnabled" type="button" class="auth-header-action" @click="registrationMode ? backToLogin() : openRegistration()">{{ t(registrationMode ? 'Sign In' : 'Create account') }}</button>
        <span v-else class="auth-header-action">{{ t('Login') }}</span>
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
        <img class="brand-icon" src="/ytpeps-logo.svg" alt="YTPEPS">
        <strong>YTPEPS</strong>
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
          <img class="brand-icon" :src="area === 'customer' ? storefrontBrand.logoUrl : '/ytpeps-logo.svg'" alt="">
          <div><strong>{{ storefrontBrand.name }}</strong><small>{{ t('Research materials marketplace') }}</small></div>
        </div>
        <div class="auth-copy">
          <h1>{{ area === 'admin' ? t('登录商家后台') : registrationMode ? t('Create account') : t('Sign In') }}</h1>
          <p>{{ area === 'admin' ? t('管理订单、商品、客户与收款渠道') : registrationMode ? t('Create a customer account using an email verification code') : t('Sign in with your email and password') }}</p>
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
        <form v-else class="auth-form registration-form" @submit.prevent="register">
          <label>{{ t('Email') }}
            <div class="registration-code-row">
              <input v-model="registrationEmail" type="email" autocomplete="email" required maxlength="128" :disabled="sendingCode || submittingRegistration" placeholder="your@email.com">
              <button class="button registration-code-button" type="button" :disabled="sendingCode || submittingRegistration || codeCooldown > 0" @click="requestCode">{{ sendingCode ? t('Sending…') : codeCooldown > 0 ? format('Resend in {0}s', [codeCooldown]) : t('Send code') }}</button>
            </div>
          </label>
          <p v-if="codeMessage" class="registration-info" role="status">{{ codeMessage }}</p>
          <label>{{ t('Verification code') }}<input v-model="registrationCode" inputmode="numeric" autocomplete="one-time-code" required maxlength="6" pattern="[0-9]{6}" placeholder="000000" :disabled="submittingRegistration"></label>
          <label>{{ t('Full name') }}<input v-model="registrationName" autocomplete="name" required maxlength="100" :disabled="submittingRegistration"></label>
          <label>{{ t('Password') }}<input v-model="registrationPassword" type="password" autocomplete="new-password" required minlength="12" maxlength="64" :disabled="submittingRegistration"></label>
          <label>{{ t('Confirm password') }}<input v-model="registrationConfirmation" type="password" autocomplete="new-password" required minlength="12" maxlength="64" :disabled="submittingRegistration"></label>
          <p v-if="registrationError" class="form-error" role="alert">{{ t(registrationError) }}</p>
          <button class="button button-dark wide" :disabled="submittingRegistration || sendingCode">{{ submittingRegistration ? t('Creating account…') : t('Create account') }}</button>
          <button type="button" class="auth-back-button" :disabled="submittingRegistration" @click="backToLogin">{{ t('Back to sign in') }}</button>
        </form>
        <slot v-if="!registrationMode" />
        <p class="auth-hint">{{ area === 'admin' ? t('请使用管理员提供的后台账号。') : registrationMode ? t('Passwords must be 12–64 characters.') : registrationEnabled ? t('Please sign in or create a customer account.') : t('Please contact your seller to request an account.') }}</p>
      </section>
    </div>

    <footer v-if="area === 'customer'" class="customer-footer auth-page-footer">
      <div class="footer-top">
        <div class="footer-left">
          <div class="footer-company-name">{{ storefrontBrand.name }}</div>
          <p class="footer-desc">{{ t('All materials listed are supplied for laboratory research use only and are not for human or veterinary use, food, or drug applications.') }}<br><br>{{ t('The nice research company') }}</p>
        </div>
        <div class="footer-right"><p><a href="#" @click.prevent>{{ t('Terms & Conditions') }}</a>© {{ new Date().getFullYear() }} {{ storefrontBrand.name }}</p></div>
      </div>
    </footer>
    <p v-else class="auth-footer">{{ t('Research use only · YTPEPS Co., Ltd.') }}</p>
  </div>
</template>
