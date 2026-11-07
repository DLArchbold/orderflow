// PLAN step 26 (screenshots): the Grafana image renderer plugin is not installed, so this takes the screenshots with Playwright instead.
//   node scripts/grafana_screenshots.mjs <output-dir>
// Opens the OrderFlow dashboard (kiosk mode) for the time window of each run, and the Prometheus graph of the OutboxLagHigh alert.
// The windows are the unix times printed in each run's timeline.txt (the data must still be inside Prometheus's retention, 15 days by default).
import { chromium } from '../frontend/node_modules/@playwright/test/index.mjs';
import fs from 'node:fs';
import path from 'node:path';

const out = process.argv[2] ?? 'docs/images';
fs.mkdirSync(out, { recursive: true });
const pad = 60;   // seconds of context before and after
const RUNS = [   // [file name, start, end] in unix seconds
  ['grafana-drill-kafka-stopped-244s', 1793926697, 1793927070],
  ['grafana-perf-ramp-to-1000', 1793927100, 1793928050],
  ['grafana-drill-kafka-stopped-60s', 1793928126, 1793928314],
  ['grafana-drill-inventory-killed-60s', 1793928332, 1793928520],
  ['grafana-drill-order-killed', 1793928537, 1793928725],
];

const browser = await chromium.launch({ channel: process.env.PW_CHANNEL ?? 'msedge' });
const page = await (await browser.newContext({ viewport: { width: 1400, height: 760 } })).newPage();

await page.goto('http://localhost:3000/login');
await page.locator('input[name="user"]').fill('admin');
await page.locator('input[name="password"]').fill('admin');
await page.locator('button[type="submit"]').click();
await page.waitForTimeout(2000);
const skip = page.getByRole('button', { name: /skip/i });   // "change your password" prompt on a fresh Grafana
if (await skip.count()) await skip.click();

for (const [name, start, end] of RUNS) {
  const url = `http://localhost:3000/d/orderflow/orderflow?orgId=1&kiosk&from=${(start - pad) * 1000}&to=${(end + pad) * 1000}`;
  await page.goto(url);
  await page.waitForTimeout(6000);   // let the four panels load their queries
  await page.screenshot({ path: path.join(out, `${name}.png`) });
  console.log('saved', name);
}

// Prometheus: the alert state over the 244 s drill (end time is UTC)
const [, s, e] = RUNS[0];
const endUtc = new Date((e + pad) * 1000).toISOString().replace('T', ' ').slice(0, 19);
const q = encodeURIComponent('ALERTS{alertname="OutboxLagHigh"}');
await page.setViewportSize({ width: 1400, height: 1000 });
await page.goto(`http://localhost:9090/graph?g0.expr=${q}&g0.tab=0&g0.range_input=${Math.ceil((e - s + 2 * pad) / 60)}m&g0.end_input=${encodeURIComponent(endUtc)}&g0.step_input=5`);
await page.waitForTimeout(5000);
await page.screenshot({ path: path.join(out, 'prometheus-alert-OutboxLagHigh.png') });
console.log('saved prometheus-alert-OutboxLagHigh');
await browser.close();
