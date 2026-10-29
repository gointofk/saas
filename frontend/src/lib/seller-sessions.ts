// Display metadata only; permissions and merchant scope always come from /admin/me.
export const SELLER_SESSIONS_KEY = 'dailyactivity.admin.accounts'
export type SellerIdentity = {
  member: { id: number; account: string; name: string }
  merchant: { id: number; name: string }
}
export type SavedSellerSession = {
  memberId: number; account: string; name: string; merchantName: string; token: string | null
}

export function savedSellerSessions(): SavedSellerSession[] {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(SELLER_SESSIONS_KEY) || '[]')
    if (!Array.isArray(value)) return []
    return value.filter((item): item is SavedSellerSession => item !== null && typeof item === 'object'
      && Number.isSafeInteger(item.memberId) && item.memberId > 0
      && typeof item.account === 'string' && typeof item.name === 'string'
      && typeof item.merchantName === 'string' && (typeof item.token === 'string' || item.token === null))
      .map(({ memberId, account, name, merchantName, token }) => ({ memberId, account, name, merchantName, token }))
  } catch { return [] }
}

export function rememberSellerSession(identity: SellerIdentity, token: string) {
  const sessions = savedSellerSessions().filter(item => item.memberId !== identity.member.id)
  sessions.push({ memberId: identity.member.id, account: identity.member.account, name: identity.member.name,
    merchantName: identity.merchant.name, token })
  localStorage.setItem(SELLER_SESSIONS_KEY, JSON.stringify(sessions))
}

export function invalidateSellerSession(token: string) {
  const sessions = savedSellerSessions()
  if (!sessions.some(item => item.token === token)) return
  localStorage.setItem(SELLER_SESSIONS_KEY, JSON.stringify(sessions.map(item => item.token === token ? { ...item, token: null } : item)))
}

export function removeSellerSession(memberId: number) {
  localStorage.setItem(SELLER_SESSIONS_KEY, JSON.stringify(savedSellerSessions().filter(item => item.memberId !== memberId)))
}
