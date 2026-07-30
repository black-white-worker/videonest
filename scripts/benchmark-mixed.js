#!/usr/bin/env node
const http = require('node:http');
const { performance } = require('node:perf_hooks');

const [baseUrlText = 'http://127.0.0.1', concurrencyText = '100', durationText = '60'] = process.argv.slice(2);
const baseUrl = new URL(baseUrlText);
const concurrency = Number.parseInt(concurrencyText, 10);
const durationMs = Number.parseInt(durationText, 10) * 1000;

const scenarios = [
  { name: 'hot', path: '/api/videos/hot?limit=10', weight: 35 },
  { name: 'video-list', path: '/api/videos?page=1&size=12', weight: 35 },
  { name: 'categories', path: '/api/categories', weight: 15 },
  { name: 'comments', path: '/api/videos/1/comments?page=1&size=10', weight: 15 }
];

if (!Number.isInteger(concurrency) || concurrency < 1 || !Number.isFinite(durationMs) || durationMs < 1000) {
  throw new Error('Usage: node scripts/benchmark-mixed.js <base-url> <concurrency> <duration-seconds>');
}

const agent = new http.Agent({ keepAlive: true, maxSockets: concurrency, maxFreeSockets: concurrency });
const createResult = () => ({ completed: 0, succeeded: 0, failed: 0, statusCodes: {}, errors: {}, latenciesMs: [] });
const total = createResult();
const perScenario = Object.fromEntries(scenarios.map((scenario) => [scenario.name, createResult()]));
const deadline = performance.now() + durationMs;

function selectScenario() {
  const value = Math.random() * 100;
  let cumulative = 0;
  for (const scenario of scenarios) {
    cumulative += scenario.weight;
    if (value < cumulative) return scenario;
  }
  return scenarios[scenarios.length - 1];
}

function record(result, status, latency, error) {
  result.completed += 1;
  result.latenciesMs.push(latency);
  if (status >= 200 && status < 300) result.succeeded += 1;
  else result.failed += 1;
  if (status) result.statusCodes[status] = (result.statusCodes[status] || 0) + 1;
  if (error) result.errors[error] = (result.errors[error] || 0) + 1;
}

function oneRequest() {
  const scenario = selectScenario();
  const scenarioResult = perScenario[scenario.name];
  return new Promise((resolve) => {
    const started = performance.now();
    const request = http.request({
      protocol: baseUrl.protocol,
      hostname: baseUrl.hostname,
      port: baseUrl.port || 80,
      path: scenario.path,
      method: 'GET',
      agent,
      timeout: 10_000,
      headers: { Accept: 'application/json', Connection: 'keep-alive' }
    }, (response) => {
      response.resume();
      response.on('end', () => {
        const status = response.statusCode || 0;
        const latency = performance.now() - started;
        record(total, status, latency);
        record(scenarioResult, status, latency);
        resolve();
      });
    });
    request.on('timeout', () => request.destroy(new Error('timeout')));
    request.on('error', (error) => {
      const latency = performance.now() - started;
      const errorName = error.code || error.name;
      record(total, 0, latency, errorName);
      record(scenarioResult, 0, latency, errorName);
      resolve();
    });
    request.end();
  });
}

async function worker() {
  while (performance.now() < deadline) await oneRequest();
}

function summarize(result, elapsedSeconds) {
  result.latenciesMs.sort((a, b) => a - b);
  const percentile = (p) => result.latenciesMs[Math.min(result.latenciesMs.length - 1, Math.ceil(result.latenciesMs.length * p) - 1)] || 0;
  return {
    requests: result.completed,
    successful: result.succeeded,
    failed: result.failed,
    successRatePercent: Number((result.succeeded / Math.max(result.completed, 1) * 100).toFixed(3)),
    requestsPerSecond: Number((result.completed / elapsedSeconds).toFixed(2)),
    latencyMs: {
      p50: Number(percentile(0.5).toFixed(2)),
      p95: Number(percentile(0.95).toFixed(2)),
      p99: Number(percentile(0.99).toFixed(2)),
      max: Number(percentile(1).toFixed(2))
    },
    statusCodes: result.statusCodes,
    errors: result.errors
  };
}

(async () => {
  const started = performance.now();
  await Promise.all(Array.from({ length: concurrency }, worker));
  const elapsedSeconds = (performance.now() - started) / 1000;
  console.log(JSON.stringify({
    baseUrl: baseUrl.toString(),
    concurrency,
    elapsedSeconds: Number(elapsedSeconds.toFixed(3)),
    workload: scenarios,
    total: summarize(total, elapsedSeconds),
    scenarios: Object.fromEntries(
      Object.entries(perScenario).map(([name, result]) => [name, summarize(result, elapsedSeconds)])
    )
  }));
  agent.destroy();
})();
