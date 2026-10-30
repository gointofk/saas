<script setup lang="ts">
import { computed } from 'vue'
import type { CustomerAddress, CustomerProfile, CustomerReferral, CustomerReferralSummary } from '../types/customer'

const props = defineProps<{
  tab: string
  commissionEnabled: boolean
  customer: CustomerProfile
  profileName: string
  referrals: CustomerReferral[]
  referralSummary: CustomerReferralSummary
  addresses: CustomerAddress[]
  oldPassword: string
  newPassword: string
  passwordConfirmation: string
  profileSaving: boolean
  addressSaving: boolean
  passwordChanging: boolean
  t: (text: string) => string
  money: (value: unknown) => string
}>()

const emit = defineEmits<{
  'update:tab': [tab: string]
  'update:profileName': [name: string]
  'update:oldPassword': [value: string]
  'update:newPassword': [value: string]
  'update:passwordConfirmation': [value: string]
  loadReferrals: []
  saveProfile: []
  openAddressForm: [address?: CustomerAddress]
  makeDefaultAddress: [address: CustomerAddress]
  editAddress: [address: CustomerAddress]
  deleteAddress: [address: CustomerAddress]
  changePassword: []
}>()

const tabs = computed(() => props.commissionEnabled
  ? ['profile', 'referrals', 'address', 'security']
  : ['profile', 'address', 'security'])
</script>

<template>
  <main class="container account-page">
    <h1 class="page-title">{{ t('My Account') }}</h1>
    <nav class="horizontal-tab-menu" :aria-label="t('Account sections')">
      <button v-for="item in tabs" :key="item" class="tab-item" :class="{ active: tab === item }" :aria-current="tab === item ? 'page' : undefined" type="button" @click="emit('update:tab', item); item === 'referrals' && emit('loadReferrals')">
        {{ t(({ profile: 'Profile', referrals: 'My Referrals', address: 'Address Book', security: 'Account Security' } as Record<string, string>)[item]) }}
      </button>
    </nav>

    <div class="main-content">
      <section v-if="tab === 'profile'" class="panel active profile-tab-panel">
        <div class="content-card">
          <h2 class="card-title">{{ t('Personal Profile') }}</h2>
          <label class="form-group">{{ t('User Code') }}<input autocomplete="off" :value="customer.userCode" disabled></label>
          <label class="form-group">{{ t('Full Name') }}<input autocomplete="off" :value="profileName" @input="emit('update:profileName', ($event.target as HTMLInputElement).value)"></label>
          <label class="form-group">{{ t('Registered Email') }}<input autocomplete="off" :value="customer.email" disabled></label>
          <button class="btn-primary" type="button" :disabled="profileSaving" :aria-busy="profileSaving" @click="emit('saveProfile')">{{ t(profileSaving ? 'Saving…' : 'Save Changes') }}</button>
        </div>
      </section>

      <section v-else-if="tab === 'referrals' && commissionEnabled" class="panel active">
        <div class="content-card">
          <h2 class="card-title">{{ t('My Referrals') }}</h2>
          <div class="referral-summary">
            <div class="referral-stat"><span>{{ t('Total commission orders') }}</span><strong>{{ referralSummary.orderCount ?? 0 }}</strong></div>
            <div class="referral-stat"><span>{{ t('To collect') }}</span><strong>{{ referralSummary.pendingPaymentOrderCount ?? 0 }}</strong></div>
            <div class="referral-stat"><span>{{ t('Collected') }}</span><strong>{{ referralSummary.paidOrderCount ?? 0 }}</strong></div>
            <div class="referral-stat"><span>{{ t('Total commission') }}</span><strong>{{ money(referralSummary.commissionAmount) }}</strong></div>
            <div class="referral-stat"><span>{{ t('Commission to collect') }}</span><strong>{{ money(referralSummary.payableCommissionAmount) }}</strong></div>
            <div class="referral-stat"><span>{{ t('Commission collected') }}</span><strong>{{ money(referralSummary.settledCommissionAmount) }}</strong></div>
          </div>
          <div class="referral-table-wrap">
            <table class="referral-table">
              <thead><tr><th>{{ t('User') }}</th><th>{{ t('Commission collected') }}</th><th>{{ t('Pending commission') }}</th></tr></thead>
              <tbody>
                <tr v-for="item in referrals" :key="item.id">
                  <td>{{ item.name }}</td>
                  <td>{{ money(item.settledCommissionAmount) }}</td><td>{{ money(item.pendingCommission) }}</td>
                </tr>
                <tr v-if="!referrals.length"><td class="referral-empty-row" colspan="3">{{ t('No direct referrals or commission history yet.') }}</td></tr>
              </tbody>
            </table>
          </div>
          <p class="referral-muted">{{ t('Orders approved by the merchant are eligible for commission settlement. The commission base is the product subtotal after the discount and excludes shipping and other charges.') }}</p>
        </div>
      </section>

      <section v-else-if="tab === 'address'" class="panel active">
        <div class="content-card">
          <div class="address-book-heading">
            <h2 class="card-title">{{ t('Address Book') }}</h2>
            <button class="addr-add-btn" type="button" :disabled="addressSaving" @click="emit('openAddressForm')">{{ t('+ Add New Shipping Address') }}</button>
          </div>
          <div id="addrList" class="address-list">
            <article v-for="address in addresses" :key="address.id" class="addr-card">
              <div class="addr-info">
                <div class="addr-card-heading">
                  <div class="addr-recipient">
                    <h3>{{ address.name || '—' }}</h3>
                    <span v-if="address.isDefault" class="default-tag">{{ t('Default') }}</span>
                  </div>
                  <span class="addr-phone">{{ address.phone || '—' }}</span>
                </div>
                <div class="addr-location">
                  <p class="addr-street">{{ address.street || '—' }}<template v-if="address.apt">, {{ address.apt }}</template></p>
                  <p>{{ [address.city, address.region, address.postalCode].filter(Boolean).join(', ') || '—' }}</p>
                </div>
              </div>
              <div class="addr-action">
                <button v-if="!address.isDefault" type="button" :disabled="addressSaving" @click="emit('makeDefaultAddress', address)">{{ t('Make default') }}</button>
                <button type="button" :disabled="addressSaving" @click="emit('editAddress', address)">{{ t('Edit') }}</button>
                <button class="addr-delete-action" type="button" :disabled="addressSaving" @click="emit('deleteAddress', address)">{{ t('Delete') }}</button>
              </div>
            </article>
            <p v-if="!addresses.length" class="empty-state">{{ t('No saved shipping addresses.') }}</p>
          </div>
        </div>
      </section>

      <section v-else class="panel active">
        <div class="content-card">
          <h2 class="card-title">{{ t('Change Password') }}</h2>
          <label class="form-group">{{ t('Current Password') }}<input autocomplete="off" :value="oldPassword" type="password" @input="emit('update:oldPassword', ($event.target as HTMLInputElement).value)"></label>
          <label class="form-group">{{ t('New Password') }}<input autocomplete="off" :value="newPassword" type="password" @input="emit('update:newPassword', ($event.target as HTMLInputElement).value)"></label>
          <label class="form-group">{{ t('Confirm New Password') }}<input autocomplete="off" :value="passwordConfirmation" type="password" @input="emit('update:passwordConfirmation', ($event.target as HTMLInputElement).value)"></label>
          <button class="btn-primary" type="button" :disabled="passwordChanging" :aria-busy="passwordChanging" @click="emit('changePassword')">{{ t(passwordChanging ? 'Updating…' : 'Update Password') }}</button>
        </div>
      </section>
    </div>
  </main>
</template>
