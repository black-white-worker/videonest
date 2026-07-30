#!/usr/bin/env node
const http = require('node:http');
const { performance } = require('node:perf_hooks');

const [
  urlText,
  concurrencyText = '20',
  durationText = '15',
  methodText = 'GET',
  bodyText = ''
] = process.argv.slice(2);

if (!urlText) {
  throw new Error('Usage: node scripts/benchmark-http.js <url> <concurrency> <duration-seconds> [method] [json-body]');
}

const url = new URL(urlText);
const concurrency = Number.parseInt(concurrencyText, 10);
const durationMs = Number.parseInt(durationText, 10) * 1000;
const method = methodText.toUpperCase();
const body = bodyText
  ? (bodyText.startsWith('base64:') ? Buffer.from(bodyText.slice(7), 'base64') : Buffer.from(bodyText))
  : null;
const agent = new http.Agent({ keepAlive: true, maxSockets: concurrency, maxFreeSockets: concurrency });
const result = { completed: 0, succeeded: 0, failed: 0, statusCodes: {}, appCodes: {}, errors: {}, latenciesMs: [] };
const deadline = performance.now() + durationMs;

function oneRequest() {
  return new Promise((resolve) => {
    const started = performance.now();
    const request = http.request({
      protocol: url.protocol,
      hostname: url.hostname,
      port: url.port || 80,
      path: `${url.pathname}${url.search}`,
      method,
      agent,
      timeout: 10_000,
      headers: {
        Accept: 'application/json',
        Connection: 'keep-alive',
        ...(body ? { 'Content-Type': 'application/json', 'Content-Length': body.length } : {})
      }
    }, (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => {
        const status = response.statusCode || 0;
        const responseBody = Buffer.concat(chunks).toString('utf8');
        let appCode = null;
        if ((response.headers['content-type'] || '').includes('application/json')) {
          try {
            const parsed = JSON.parse(responseBody);
            if (typeof parsed.code === 'number') appCode = parsed.code;
          } catch {
            appCode = 'INVALID_JSON';
          }
        }
        result.completed += 1;
        result.statusCodes[status] = (result.statusCodes[status] || 0) + 1;
        if (appCode !== null) result.appCodes[appCode] = (result.appCodes[appCode] || 0) + 1;
        if (status >= 200 && status < 300 && (appCode === null || appCode === 200)) result.succeeded += 1;
        else result.failed += 1;
        result.latenciesMs.push(performance.now() - started);
        resolve();
      });
    });
    request.on('timeout', () => request.destroy(new Error('timeout')));
    request.on('error', (error) => {
      result.completed += 1;
      result.failed += 1;
      result.errors[error.code || error.name] = (result.errors[error.code || error.name] || 0) + 1;
      result.latenciesMs.push(performance.now() - started);
      resolve();
    });
    if (body) request.write(body);
    request.end();
  });
}

async function worker() {
  while (performance.now() < deadline) await oneRequest();
}

(async () => {
  const started = performance.now();
  await Promise.all(Array.from({ length: concurrency }, worker));
  const elapsedSeconds = (performance.now() - started) / 1000;
  result.latenciesMs.sort((a, b) => a - b);
  const percentile = (p) => result.latenciesMs[Math.min(result.latenciesMs.length - 1, Math.ceil(result.latenciesMs.length * p) - 1)] || 0;
  console.log(JSON.stringify({
    target: url.toString(), method, concurrency,
    elapsedSeconds: Number(elapsedSeconds.toFixed(3)),
    requests: result.completed, successful: result.succeeded, failed: result.failed,
    successRatePercent: Number((result.succeeded / Math.max(result.completed, 1) * 100).toFixed(3)),
    requestsPerSecond: Number((result.completed / elapsedSeconds).toFixed(2)),
    latencyMs: {
      p50: Number(percentile(0.5).toFixed(2)),
      p95: Number(percentile(0.95).toFixed(2)),
      p99: Number(percentile(0.99).toFixed(2)),
      max: Number(percentile(1).toFixed(2))
    },
    statusCodes: result.statusCodes, appCodes: result.appCodes, errors: result.errors
  }));
  agent.destroy();
})();
