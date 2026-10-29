<script setup lang="ts">
import type { Area } from '../lib/api'
import SellerLanguageSwitch from './SellerLanguageSwitch.vue'

defineProps<{
  area: Area
  account: string
  password: string
  error: string
  busy: boolean
  language: string
  t: (text: string, values?: readonly unknown[]) => string
}>()

const emit = defineEmits<{
  signIn: []
  changeLanguage: []
  'update:account': [value: string]
  'update:password': [value: string]
  'update:language': [value: string]
}>()
</script>

<template>
  <div :class="['auth-screen', { 'customer-auth-screen': area === 'customer' }]">
    <header v-if="area === 'customer'" class="auth-header">
      <div class="brand">
        <img class="brand-icon" src="/ytpeps-logo.svg" alt="YTPEPS">
        <span class="brand-name">YTPEPS</span>
      </div>
      <div class="auth-header-actions">
        <span class="auth-header-action">{{ t('Login') }}</span>
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
          <img class="brand-icon" src="/ytpeps-logo.svg" alt="">
          <div><strong>YTPEPS</strong><small>{{ t('Research materials marketplace') }}</small></div>
        </div>
        <div class="auth-copy">
          <h1>{{ area === 'admin' ? t('登录商家后台') : t('Sign In') }}</h1>
          <p>{{ area === 'admin' ? t('管理订单、商品、客户与收款渠道') : t('Sign in with your email and password') }}</p>
        </div>
        <form class="auth-form" @submit.prevent="emit('signIn')">
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
        <slot />
        <p class="auth-hint">{{ area === 'admin' ? t('请使用管理员提供的后台账号。') : t('Please contact your seller to request an account.') }}</p>
      </section>
    </div>

    <footer v-if="area === 'customer'" class="customer-footer auth-page-footer">
      <div class="footer-top">
        <div class="footer-left">
          <div class="footer-company-name">YTPEPS</div>
          <p class="footer-desc">{{ t('All materials listed are supplied for laboratory research use only and are not for human or veterinary use, food, or drug applications.') }}<br><br>{{ t('The nice research company') }}</p>
        </div>
        <div class="footer-right"><p><a href="#" @click.prevent>{{ t('Terms & Conditions') }}</a>{{ t('© 2026 YTPEPS Co., Ltd.') }}</p></div>
      </div>
    </footer>
    <p v-else class="auth-footer">{{ t('Research use only · YTPEPS Co., Ltd.') }}</p>
  </div>
</template>
