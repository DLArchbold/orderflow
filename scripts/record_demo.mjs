// PLAN step 27(4): records the demo as numbered PNG frames (no ffmpeg needed); scripts/frames_to_gif.py turns them into docs/images/demo.gif.
//   node scripts/record_demo.mjs <frames-dir>
// Needs the compose apps stack running (UI on :5173). Flow: log in through Keycloak, create an order and watch it become CONFIRMED live,
// then kill inventory-service, create another order (accepted, stays PENDING), restart inventory-service and watch it become CONFIRMED.
import { chromium } from '../frontend/node_modules/@playwright/test/index.mjs';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const out = process.argv[2];
if (!out) throw new Error('usage: node scripts/record_demo.mjs <frames-dir>');
fs.mkdirSync(out, { recursive: true });
const compose = ['compose', '-f', 'phase2/infra/docker-compose.full.yml'];
const run = (...args) => execFileSync('docker', [...compose, ...args], { stdio: 'ignore', env: { ...process.env, MSYS_NO_PATHCONV: '1' } });

const browser = await chromium.launch({ channel: process.env.PW_CHANNEL ?? 'msedge' });
const page = await (await browser.newContext({ viewport: { width: 820, height: 500 } })).newPage();

let n = 0, caption = '';
async function snap(ms) {   // ms = how long this frame stays on screen in the GIF
  await page.evaluate((text) => {
    let el = document.getElementById('demo-caption');
    if (!el) {
      el = document.createElement('div');
      el.id = 'demo-caption';
      el.style.cssText = 'position:fixed;left:0;right:0;bottom:0;padding:10px 14px;background:#1b2733;color:#fff;font:600 15px system-ui;z-index:99999';
      document.body.appendChild(el);
    }
    el.textContent = text;
  }, caption).catch(() => {});
  await page.screenshot({ path: path.join(out, `f${String(n++).padStart(4, '0')}_${ms}.png`) });
}
async function hold(text, frames, every = 250, ms = 250) {
  caption = text;
  for (let i = 0; i < frames; i++) { await snap(ms); await page.waitForTimeout(every); }
}
const row = (q) => page.getByRole('listitem').filter({ hasText: `${q} x SKU-1` }).first();
async function createOrder(q) {
  await page.getByLabel('SKU').fill('SKU-1');
  await page.getByLabel('Quantity').fill(String(q));
  await page.getByRole('button', { name: 'Create order' }).click();
}

try {
  await page.goto('http://localhost:5173');
  await hold('1. OrderFlow: sign in through Keycloak (OIDC with PKCE)', 6, 200, 350);
  await page.getByRole('button', { name: 'Log in' }).click();
  await page.locator('#username').fill('alice');
  await page.locator('#password').fill('alice-dev-pw');
  await hold('Keycloak login form', 3, 100, 450);
  await page.locator('#kc-login').click();
  await page.getByText('Signed in as alice').waitFor();
  await hold('Signed in as alice', 4, 200, 350);

  const q1 = 7;
  await page.getByLabel('SKU').fill('SKU-1');
  await page.getByLabel('Quantity').fill(String(q1));
  await hold('2. Create an order: 7 x SKU-1', 4, 150, 350);
  await createOrder(q1);
  caption = '   the new order appears at the top and is CONFIRMED within seconds, pushed live (no refresh)';
  for (let i = 0; i < 40; i++) {
    await snap(300);
    if ((await row(q1).textContent().catch(() => '')).includes('CONFIRMED')) break;
    await page.waitForTimeout(150);
  }
  await hold('   CONFIRMED: inventory-service reserved the stock', 8, 200, 350);

  await hold('3. Failure drill: inventory-service is killed (docker compose kill)', 4, 200, 400);
  run('kill', 'inventory-service');
  const q2 = 8;
  await createOrder(q2);
  await hold('   inventory is down, yet the API still accepts the order: PENDING', 10, 500, 600);

  caption = '4. inventory-service restarted: the event waited in Kafka, nothing is lost';
  run('start', 'inventory-service');
  let confirmed = false;
  for (let i = 0; i < 120; i++) {
    await snap(i < 8 ? 600 : 250);
    if ((await row(q2).textContent().catch(() => '')).includes('CONFIRMED')) { confirmed = true; break; }
    await page.waitForTimeout(i < 8 ? 600 : 1000);
  }
  if (!confirmed) throw new Error('order never became CONFIRMED after inventory-service restarted');
  await hold('   CONFIRMED after the restart: 0 orders lost', 10, 200, 400);
} finally {
  try { run('start', 'inventory-service'); } catch {}
  await browser.close();
}
console.log(`frames: ${n}`);
