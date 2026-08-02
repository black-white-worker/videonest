#!/usr/bin/env node
/*
 * Reproducible local read-only performance suite for VideoNest.
 *
 * It deliberately does not exercise login, upload, comments, likes, follows,
 * review, or other write paths. Run it only against a disposable/local
 * environment when testing write workloads separately.
 */
const fs = require('node:fs');
const http = require('node:http');
const https = require('node:https');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const projectRoot = path.resolve(__dirname, '..');
const date = new Date().toISOString().slice(0, 10);
const timestamp = new Date().toISOString().replace(/[:.]/g, '-');

function usage() {
  console.log(`Usage: node scripts/run-performance-suite.js [options]

Options:
  --base-url <url>       Public/Nginx entry (default: http://127.0.0.1)
  --backend-url <url>    Direct backend entry (default: http://127.0.0.1:8080)
  --report <path>        Markdown report path (default: docs/performance-test-${date}.md)
  --results <path>       Raw JSON result path (default: docs/performance-results/${timestamp}.json)
  --quick                Run the representative subset (about 70 seconds)
  --skip-direct          Do not include the direct-backend comparison
  --help                 Show this help
`);
}

function argumentValue(argumentsList, index, name) {
  const value = argumentsList[index + 1];
  if (!value || value.startsWith('--')) throw new Error(`${name} requires a value`);
  return value;
}

function parseOptions() {
  const options = {
    baseUrl: 'http://127.0.0.1',
    backendUrl: 'http://127.0.0.1:8080',
    report: path.join('docs', `performance-test-${date}.md`),
    results: path.join('docs', 'performance-results', `${timestamp}.json`),
    quick: false,
    skipDirect: false
  };
  const argumentsList = process.argv.slice(2);
  for (let index = 0; index < argumentsList.length; index += 1) {
    const argument = argumentsList[index];
    if (argument === '--help') {
      usage();
      process.exit(0);
    }
    if (argument === '--quick') options.quick = true;
    else if (argument === '--skip-direct') options.skipDirect = true;
    else if (argument === '--base-url') options.baseUrl = argumentValue(argumentsList, index++, argument);
    else if (argument === '--backend-url') options.backendUrl = argumentValue(argumentsList, index++, argument);
    else if (argument === '--report') options.report = argumentValue(argumentsList, index++, argument);
    else if (argument === '--results') options.results = argumentValue(argumentsList, index++, argument);
    else throw new Error(`Unknown option: ${argument}`);
  }
  for (const key of ['baseUrl', 'backendUrl']) new URL(options[key]);
  return options;
}

function endpoint(baseUrl, endpointPath) {
  return new URL(endpointPath, baseUrl).toString();
}

function requestStatus(urlText) {
  const url = new URL(urlText);
  const transport = url.protocol === 'https:' ? https : http;
  return new Promise((resolve, reject) => {
    const request = transport.request(url, { method: 'GET', timeout: 10_000, headers: { Accept: 'application/json' } }, (response) => {
      response.resume();
      response.on('end', () => resolve(response.statusCode || 0));
    });
    request.on('timeout', () => request.destroy(new Error('health-check timeout')));
    request.on('error', reject);
    request.end();
  });
}

function metric(result, key) {
  return result?.[key] ?? result?.total?.[key] ?? '-';
}

function latency(result, key) {
  return result?.latencyMs?.[key] ?? result?.total?.latencyMs?.[key] ?? '-';
}

function row(result) {
  if (result.error) return `| ${result.name} | ${result.concurrency} | - | 失败 | - | - | - | ${result.error.replaceAll('|', '\\|')} |`;
  return `| ${result.name} | ${result.concurrency} | ${metric(result.data, 'requestsPerSecond')} | ${metric(result.data, 'successRatePercent')}% | ${latency(result.data, 'p50')} ms | ${latency(result.data, 'p95')} ms | ${latency(result.data, 'p99')} ms | ${metric(result.data, 'failed')} |`;
}

function section(title, results) {
  if (!results.length) return '';
  return `\n### ${title}\n\n| 场景 | 并发 | QPS | 成功率 | P50 | P95 | P99 | 失败请求 |\n|---|---:|---:|---:|---:|---:|---:|---:|\n${results.map(row).join('\n')}\n`;
}

function composeReport(run) {
  const failed = run.results.filter((result) => result.error);
  const executed = run.results.filter((result) => !result.error);
  const status = run.preflight.error ? '未执行（环境不可用）' : failed.length ? '已完成，但部分场景失败' : '通过';
  const intro = run.preflight.error
    ? `压测在预检阶段停止：${run.preflight.error}。未产生任何压测流量，因此本报告不包含性能结论。`
    : `本次为本机、只读 HTTP 压测，流量从同一台主机发起。结果受机器负载、数据量、缓存预热和 Docker 资源限制影响，不能直接作为生产容量承诺。`;
  const groups = {
    '静态资源与代理链路': run.results.filter((result) => result.group === 'edge'),
    '热门视频（Redis 热路径）': run.results.filter((result) => result.group === 'hot'),
    '视频列表（MySQL 查询路径）': run.results.filter((result) => result.group === 'list'),
    '其他只读接口': run.results.filter((result) => result.group === 'other'),
    '混合只读流量': run.results.filter((result) => result.group === 'mixed')
  };
  const rawLink = path.relative(path.dirname(run.reportPath), run.resultsPath).replaceAll('\\', '/');
  return `# VideoNest 本机综合压测报告\n\n测试日期：${new Date(run.startedAt).toLocaleString('zh-CN', { hour12: false })}  \n测试状态：**${status}**  \n公共入口：\`${run.options.baseUrl}\`  \n直连后端：\`${run.options.backendUrl}\`\n\n## 1. 测试范围\n\n${intro}\n\n- 仅覆盖 GET 查询：首页、热门视频、视频列表、分类和评论列表。\n- 不覆盖登录、上传、评论新增、点赞、收藏、关注、审核、转码及其它写操作，避免污染开发数据。\n- 单请求超时为 10 秒，成功条件为 HTTP 2xx。\n- 原始结果：[\`${rawLink}\`](${rawLink})。\n\n## 2. 执行摘要\n\n- 预检：${run.preflight.error ? `失败（${run.preflight.error}）` : `通过（HTTP ${run.preflight.status}）`}\n- 已完成场景：${executed.length}\n- 失败场景：${failed.length}\n- 测试模式：${run.options.quick ? '快速代表性子集' : '完整阶梯并发与混合流量'}\n${Object.entries(groups).map(([title, results]) => section(title, results)).join('')}\n## 3. 复现方式\n\n\`\`\`powershell\n# 完整套件：约 7 分钟\nnode scripts/run-performance-suite.js\n\n# 快速回归：约 100 秒\nnode scripts/run-performance-suite.js --quick\n\n# 指向其它环境，并把报告写到指定位置\nnode scripts/run-performance-suite.js --base-url \"http://127.0.0.1\" --backend-url \"http://127.0.0.1:8080\" --report \"docs/performance-test-${date}.md\"\n\`\`\`\n\n脚本会先请求 \`/api/videos/hot?limit=1\` 进行健康预检；预检失败时不会开始发压。\n`;
}

function writeArtifacts(run) {
  fs.mkdirSync(path.dirname(run.resultsPath), { recursive: true });
  fs.mkdirSync(path.dirname(run.reportPath), { recursive: true });
  fs.writeFileSync(run.resultsPath, `${JSON.stringify(run, null, 2)}\n`, 'utf8');
  fs.writeFileSync(run.reportPath, composeReport(run), 'utf8');
}

function specs(options) {
  const standardDuration = options.quick ? 10 : 20;
  const hotLevels = options.quick ? [100] : [50, 100, 200, 400, 800];
  const listLevels = options.quick ? [100] : [50, 100, 200, 400];
  const output = [
    { group: 'edge', name: 'Nginx 首页', script: 'benchmark-read.js', url: endpoint(options.baseUrl, '/'), concurrency: 100, duration: standardDuration },
    { group: 'edge', name: '热门视频（经 Nginx）', script: 'benchmark-read.js', url: endpoint(options.baseUrl, '/api/videos/hot?limit=10'), concurrency: 100, duration: standardDuration },
    ...hotLevels.map((concurrency) => ({ group: 'hot', name: `热门视频（${concurrency} 并发）`, script: 'benchmark-read.js', url: endpoint(options.baseUrl, '/api/videos/hot?limit=10'), concurrency, duration: standardDuration })),
    ...listLevels.map((concurrency) => ({ group: 'list', name: `视频列表（${concurrency} 并发）`, script: 'benchmark-read.js', url: endpoint(options.baseUrl, '/api/videos?page=1&size=12'), concurrency, duration: standardDuration })),
    { group: 'other', name: '分类列表', script: 'benchmark-read.js', url: endpoint(options.baseUrl, '/api/categories'), concurrency: 100, duration: standardDuration },
    { group: 'other', name: '评论列表', script: 'benchmark-read.js', url: endpoint(options.baseUrl, '/api/videos/1/comments?page=1&size=10'), concurrency: 100, duration: standardDuration },
    ...(options.quick
      ? [{ group: 'mixed', name: '混合只读流量（100 并发）', script: 'benchmark-mixed.js', url: options.baseUrl, concurrency: 100, duration: 30 }]
      : [100, 200, 400].map((concurrency) => ({ group: 'mixed', name: `混合只读流量（${concurrency} 并发）`, script: 'benchmark-mixed.js', url: options.baseUrl, concurrency, duration: concurrency === 200 ? 60 : 30 })))
  ];
  if (!options.skipDirect) output.splice(1, 0, { group: 'edge', name: '热门视频（直连后端）', script: 'benchmark-read.js', url: endpoint(options.backendUrl, '/api/videos/hot?limit=10'), concurrency: 100, duration: standardDuration });
  return output;
}

function execute(spec) {
  console.log(`\n[压测] ${spec.name}：${spec.concurrency} 并发，${spec.duration} 秒`);
  const processResult = spawnSync(process.execPath, [path.join(__dirname, spec.script), spec.url, String(spec.concurrency), String(spec.duration)], {
    cwd: projectRoot,
    encoding: 'utf8',
    timeout: (spec.duration + 20) * 1000
  });
  const result = { ...spec };
  if (processResult.error) {
    result.error = processResult.error.message;
    return result;
  }
  if (processResult.status !== 0) {
    result.error = (processResult.stderr || `exit code ${processResult.status}`).trim();
    return result;
  }
  try {
    result.data = JSON.parse(processResult.stdout.trim());
  } catch (error) {
    result.error = `无法解析压测输出：${error.message}`;
  }
  return result;
}

(async () => {
  const options = parseOptions();
  const run = {
    startedAt: new Date().toISOString(),
    options,
    reportPath: path.resolve(projectRoot, options.report),
    resultsPath: path.resolve(projectRoot, options.results),
    preflight: {},
    results: []
  };
  const healthUrl = endpoint(options.baseUrl, '/api/videos/hot?limit=1');
  console.log(`[预检] ${healthUrl}`);
  try {
    run.preflight.status = await requestStatus(healthUrl);
    if (run.preflight.status < 200 || run.preflight.status >= 300) throw new Error(`HTTP ${run.preflight.status}`);
  } catch (error) {
    run.preflight.error = error.message;
  }
  if (!run.preflight.error) run.results = specs(options).map(execute);
  run.finishedAt = new Date().toISOString();
  writeArtifacts(run);
  console.log(`\n报告：${run.reportPath}`);
  console.log(`原始结果：${run.resultsPath}`);
  if (run.preflight.error) {
    console.error(`预检失败，未开始压测：${run.preflight.error}`);
    process.exitCode = 2;
  } else if (run.results.some((result) => result.error)) {
    console.error('部分场景失败，请查看报告和原始结果。');
    process.exitCode = 1;
  }
})().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});
