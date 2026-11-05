import { InMemoryWebStorage, User, UserManager, WebStorageStateStore } from 'oidc-client-ts';

// Authorization code flow with PKCE against Keycloak. The signed-in user (and so the access token) is kept in
// memory, not in localStorage, which any injected script can read. The in-flight login state (the PKCE verifier)
// has to survive the redirect to Keycloak, so it stays in the default sessionStorage.
const manager = new UserManager({
  authority: import.meta.env.VITE_OIDC_AUTHORITY ?? 'http://localhost:8180/realms/orderflow',
  client_id: 'orderflow-frontend',
  redirect_uri: window.location.origin + '/',
  post_logout_redirect_uri: window.location.origin + '/',
  response_type: 'code',
  scope: 'openid profile',
  userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
});

let current: User | null = null;

export const getAccessToken = () => (current && !current.expired ? current.access_token : null);
export const getUserName = () => current?.profile.preferred_username ?? null;

export const login = () => manager.signinRedirect();
export const logout = () => manager.signoutRedirect();

let initPromise: Promise<boolean> | null = null;

/**
 * Completes a login if the page was reached through Keycloak's redirect; returns whether a user is signed in.
 * Runs once and shares the result: the authorization code can be redeemed only once, and React StrictMode
 * runs effects twice in development.
 */
export function initAuth(): Promise<boolean> {
  initPromise ??= (async () => {
    const params = new URLSearchParams(window.location.search);
    if (params.has('code') && params.has('state')) {
      current = await manager.signinRedirectCallback();
      window.history.replaceState({}, document.title, window.location.pathname);
    } else {
      current = await manager.getUser();
    }
    return getAccessToken() !== null;
  })();
  return initPromise;
}
