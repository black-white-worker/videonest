#!/usr/bin/env node
// 公网压测脚本（从本地发压到公网入口）
const http = require('node:http');
const { performance } = require('node:perf_hooks');

const argv = {};
for (const a of process.argv.slice(2)) {
  const m = /^--([^=]+)(?:=(.*))?$/.exec(a);
  if (m) argv[m[1]] = m[2] === undefined ? true : m[2];
}

const base = new URL(argv.base || 'http://101.201.208.223:8082');
const path = argv.path || '/api/videos?page=1&size=12';
const secs = Number.parseInt(argv.secs || '20', 10);
const ladder = String(argv.ladder || '8,16,32,64').split(',').map((x) => Number.parseInt(x, 10));
const reqTimeout = Number.parseInt(argv['req-timeout'] || '10000', 10);
const label = argv.label || path;

function request(agent, path) {
  return new Promise((resolve) => {
    const started = performance.now();
    const req = http.request({
      protocol: base.protocol,
      hostname: base.hostname,
      port: base.port || 80,
      path,
      method: 'GET',
      agent,
      timeout: reqTimeout,
      headers: { Accept: 'application/json', Connection: 'keep-alive' }
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const bytes = Buffer.concat(chunks).length;
        resolve({
          status: res.statusCode || 0,
          latency: performance.now() - started,
          bytes
        });
      });
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', (err) => resolve({ status: 0, latency: performance.now() - started, error: err.code }));
    req.end();
  });
}

async function runOne(conc) {
  const agent = new http.Agent({ keepAlive: true, maxSockets: conc });
  const results = [];
  const started = performance.now();
  const endTime = started + secs * 1000;

  const workers = Array.from({ length: conc }, async () => {
    while (performance.now() < endTime) {
      const r = await request(agent, path);
      results.push(r);
    }
  });

  await Promise.all(workers);
  const elapsed = (performance.now() - started) / 1000;

  const lats = results.map(r => r.latency).sort((a, b) => a - b);
  const totalBytes = results.reduce((s, r) => s + (r.bytes || 0), 0);
  const ok = results.filter(r => r.status >= 200 && r.status < 300).length;
  const statusCodes = {};
  results.forEach(r => { statusCodes[r.status] = (statusCodes[r.status] || 0) + 1; });

  const p = (n) => lats[Math.floor(lats.length * n / 100)] || 0;

  return {
    label, conc, elapsed,
    requests: results.length,
    qps: Math.round(results.length / elapsed),
    p50: Math.round(p(50)),
    p95: Math.round(p(95)),
    p99: Math.round(p(99)),
    successRate: (ok / results.length * 100).toFixed(3) + '%',
    statusCodes,
    avgBytes: Math.round(totalBytes / results.length),
    throughputKBps: Math.round(totalBytes / 1024 / elapsed)
  };
}

async function main() {
  console.log(`=== 公网压测: ${base.host}${path} ===`);
  console.log(`并发阶梯: ${ladder.join(', ')}，每档 ${secs}s`);
  console.log('');

  for (const conc of ladder) {
    process.stdout.write(`并发 ${conc} ... `);
    const r = await runOne(conc);
    console.log(`QPS=${r.qps}  p50=${r.p50}ms  p95=${r.p95}ms  p99=${r.p99}ms  成功率=${r.successRate}  吞吐=${r.throughputKBps}KB/s  状态=${JSON.stringify(r.statusCodes)}`);
  }
}

main().catch(e => { console.error(e); process.exit(1); });
