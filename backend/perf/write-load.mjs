import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { performance } from 'node:perf_hooks';
import { promisify } from 'node:util';

const exec = promisify(execFile);
const args = Object.fromEntries(process.argv.slice(2).map((arg) => {
  const [key, ...value] = arg.replace(/^--/, '').split('=');
  return [key, value.length ? value.join('=') : true];
}));

if (args['self-test']) {
  selfTest();
  process.exit(0);
}
if (args.compare) {
  const files = String(args.compare).split(',');
  if (files.length !== 2) throw new Error('--compare=pool10.json,pool20.json is required');
  const reports = await Promise.all(files.map((file) => readFile(file, 'utf8').then(JSON.parse)));
  const markdown = comparisonMarkdown(...reports);
  if (args.output) await writeText(args.output, markdown);
  else console.log(markdown);
  process.exit(0);
}

const base = String(args.base ?? 'http://127.0.0.1:18080');
const poolSize = numberArg('pool', 10);
const quick = Boolean(args.quick);
const levels = quick ? [1, 4] : [1, 5, 10, 20, 40];
const runId = `${Date.now().toString(36)}-${process.pid}`;
let sequence = 0;

console.error(`write-load: setup pool=${poolSize}, mode=${quick ? 'quick' : 'full'}`);
const fixture = await setupFixture();
const scenarios = [];
for (const concurrency of levels) {
  const count = quick ? Math.max(8, concurrency * 4) : Math.max(500, concurrency * 100);
  scenarios.push(await startScenario(concurrency, count));
  scenarios.push(await actionScenario('approve', concurrency, count));
  scenarios.push(await actionScenario('reject', concurrency, count));
  scenarios.push(await parallelScenario(concurrency, Math.ceil(count / 2)));
  scenarios.push(await actionScenario('transfer', concurrency, count));
}

console.error('write-load: conflict probes');
const conflicts = await conflictProbes();
const duplicateChecks = await globalDuplicateChecks();
const acceptance = assess(scenarios, conflicts, duplicateChecks);
const report = {
  generatedAt: new Date().toISOString(), runId, mode: quick ? 'quick' : 'full',
  base, poolSize, levels, fixture: {
    simpleFormCode: fixture.simple.code, parallelFormCode: fixture.parallel.code,
    users: fixture.users,
  },
  scenarios, conflicts, duplicateChecks, acceptance,
};
if (args.output) await writeText(args.output, `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify({ poolSize, mode: report.mode, acceptance,
  output: args.output ?? null }));

async function setupFixture() {
  const admin = await login('admin', 'ant.design');
  const roles = await expectOk(await request('/api/roles', { token: admin }), 'list roles');
  const userRole = roles.json.find((role) => role.code === 'user');
  assert(userRole?.id, 'seed user role not found');
  const password = 'LoadTest1!';
  const users = {};
  for (const label of ['a', 'b', 'c']) {
    const username = `load_${label}_${runId.replaceAll('-', '_')}`;
    const created = await expectOk(await request('/api/users', {
      method: 'POST', token: admin,
      body: { username, displayName: `Load ${label.toUpperCase()} ${runId}`,
        password, roleIds: [userRole.id] },
    }), `create user ${label}`);
    users[label] = { id: created.json.id, username };
  }
  const [aToken, bToken] = await Promise.all([
    login(users.a.username, password), login(users.b.username, password),
  ]);
  const simple = await createFlow('simple', simpleFlow(users.a.id, users.b.id), admin);
  const parallel = await createFlow('parallel', parallelFlow(users.a.id, users.b.id, users.c.id), admin);

  // Prove both forms are published with executable flows before measuring writes.
  const [simpleMobile, parallelMobile] = await Promise.all([
    request(`/api/mobile/forms/${simple.code}`, { token: admin }),
    request(`/api/mobile/forms/${parallel.code}`, { token: admin }),
  ]);
  assert.equal((await expectOk(simpleMobile, 'read simple mobile form')).json.process.children.type,
    'APPROVAL');
  assert.equal((await expectOk(parallelMobile, 'read parallel mobile form')).json.process.children.type,
    'PARALLEL');
  const [simpleProbe, parallelProbe] = await Promise.all([
    start(simple.code, `fixture-simple-${runId}`, admin),
    start(parallel.code, `fixture-parallel-${runId}`, admin),
  ]);
  assert.equal(simpleProbe.firstTaskIds.length, 1, 'simple flow was not activated');
  assert.equal(parallelProbe.firstTaskIds.length, 2, 'parallel flow was not activated');
  return { admin, aToken, bToken, users, simple, parallel };
}

async function createFlow(kind, process, token) {
  const code = `LOAD_${kind.toUpperCase()}_${runId.replaceAll('-', '_')}`;
  const form = await expectOk(await request('/api/forms/definitions', {
    method: 'POST', token,
    body: { code, name: `Write load ${kind} ${runId}`,
      schema: [{ id: 'subject', type: 'text', label: 'Subject' }],
      settings: { workflowEnabled: true } },
  }), `create ${kind} form`);
  const definition = await expectOk(await request('/api/processes/definitions', {
    method: 'POST', token, body: { formDefId: form.json.id, process },
  }), `create ${kind} process`);
  const published = await expectOk(await request(
    `/api/forms/definitions/${form.json.id}/publish-with-process`, {
      method: 'POST', token, body: { processDefinitionId: definition.json.id },
    }), `publish ${kind} flow`);
  assert.equal(published.json.formDefinition.status, 'PUBLISHED');
  assert.equal(published.json.processDefinition.status, 'PUBLISHED');
  return { code, formId: form.json.id, processId: definition.json.id };
}

async function startScenario(concurrency, count) {
  const prefix = `start-${concurrency}-${runId}-`;
  const jobs = Array.from({ length: count }, (_, index) => () => request('/api/mobile/instances', {
    method: 'POST', token: fixture.admin, key: uniqueKey('start'),
    body: startBody(fixture.simple.code, `${prefix}${index}`),
  }));
  const result = await runTimed('start', concurrency, jobs);
  const actual = await countSql(subjectSql(prefix, 'count(DISTINCT pi.id)'));
  result.invariant = invariant(actual === count, { expectedInstances: count, actualInstances: actual });
  return result;
}

async function actionScenario(name, concurrency, count) {
  const prefix = `${name}-${concurrency}-${runId}-`;
  console.error(`write-load: prepare ${name} c=${concurrency} n=${count}`);
  const started = await prepareStarts(fixture.simple.code, prefix, count, 1);
  const jobs = started.map((item) => () => {
    if (name === 'transfer') {
      return request(`/api/tasks/${item.firstTaskIds[0]}/transfer`, {
        method: 'POST', token: fixture.aToken,
        body: { targetUserId: fixture.users.c.id, comment: 'load' },
      });
    }
    return request(`/api/mobile/tasks/${item.firstTaskIds[0]}/${name}`, {
      method: 'POST', token: fixture.aToken, key: uniqueKey(name), body: { comment: 'load' },
    });
  });
  const result = await runTimed(name, concurrency, jobs);
  let actual;
  if (name === 'approve') {
    actual = await countSql(subjectSql(prefix, "count(*) FILTER (WHERE t.node_id = 'a2')", true));
    result.invariant = invariant(actual === count, { expectedDownstreamTasks: count, actualDownstreamTasks: actual });
  } else if (name === 'reject') {
    actual = await countSql(subjectSql(prefix, "count(*) FILTER (WHERE t.task_type = 'REWORK')", true));
    result.invariant = invariant(actual === count, { expectedReworkTasks: count, actualReworkTasks: actual });
  } else {
    const children = await countSql(subjectSql(prefix,
      'count(*) FILTER (WHERE t.parent_task_id IS NOT NULL)', true));
    const skipped = await countSql(subjectSql(prefix,
      "count(*) FILTER (WHERE t.parent_task_id IS NULL AND t.status = 'SKIPPED')", true));
    result.invariant = invariant(children === count && skipped === count,
      { expectedTransfers: count, childTasks: children, skippedParents: skipped });
  }
  return result;
}

async function parallelScenario(concurrency, instanceCount) {
  const prefix = `parallel-${concurrency}-${runId}-`;
  console.error(`write-load: prepare parallel c=${concurrency} instances=${instanceCount}`);
  const started = await prepareStarts(fixture.parallel.code, prefix, instanceCount, 2);
  const jobs = started.flatMap((item) => [
    () => request(`/api/mobile/tasks/${item.firstTaskIds[0]}/approve`, {
      method: 'POST', token: fixture.aToken, key: uniqueKey('parallel-a'), body: { comment: 'load' },
    }),
    () => request(`/api/mobile/tasks/${item.firstTaskIds[1]}/approve`, {
      method: 'POST', token: fixture.bToken, key: uniqueKey('parallel-b'), body: { comment: 'load' },
    }),
  ]);
  const result = await runTimed('parallel', concurrency, jobs);
  const downstream = await countSql(subjectSql(prefix,
    "count(*) FILTER (WHERE t.node_id = 'a3')", true));
  result.instances = instanceCount;
  result.invariant = invariant(downstream === instanceCount,
    { expectedJoinTasks: instanceCount, actualJoinTasks: downstream });
  return result;
}

async function prepareStarts(formCode, prefix, count, expectedTasks) {
  return mapLimit(Array.from({ length: count }, (_, index) => index), Math.max(4, poolSize),
    async (index) => {
      const result = await start(formCode, `${prefix}${index}`, fixture.admin);
      if (result.firstTaskIds.length !== expectedTasks) {
        throw new Error(`flow ${formCode} returned ${result.firstTaskIds.length}, expected ${expectedTasks}`);
      }
      return result;
    });
}

async function runTimed(name, concurrency, jobs) {
  console.error(`write-load: ${name} c=${concurrency} requests=${jobs.length}`);
  const beforeDb = await dbCounters();
  const sampler = systemSampler();
  const latencies = [];
  const statuses = {};
  const errorCodes = {};
  let cursor = 0;
  const started = performance.now();
  await Promise.all(Array.from({ length: concurrency }, async () => {
    while (true) {
      const index = cursor++;
      if (index >= jobs.length) return;
      const before = performance.now();
      try {
        const response = await jobs[index]();
        latencies.push(performance.now() - before);
        statuses[response.status] = (statuses[response.status] ?? 0) + 1;
        if (response.status >= 400) {
          const code = response.json?.code ?? `HTTP_${response.status}`;
          errorCodes[code] = (errorCodes[code] ?? 0) + 1;
        }
      } catch (error) {
        latencies.push(performance.now() - before);
        statuses.NETWORK_ERROR = (statuses.NETWORK_ERROR ?? 0) + 1;
        const code = error.name === 'TimeoutError' ? 'TIMEOUT' : 'NETWORK_ERROR';
        errorCodes[code] = (errorCodes[code] ?? 0) + 1;
      }
    }
  }));
  const elapsedSeconds = (performance.now() - started) / 1000;
  const system = await sampler.stop();
  const afterDb = await dbCounters();
  system.postgres.commits = afterDb.commits - beforeDb.commits;
  system.postgres.rollbacks = afterDb.rollbacks - beforeDb.rollbacks;
  system.postgres.deadlocks = afterDb.deadlocks - beforeDb.deadlocks;
  latencies.sort((a, b) => a - b);
  return {
    name, concurrency, requests: jobs.length, elapsedSeconds: round(elapsedSeconds),
    requestsPerSecond: round(jobs.length / elapsedSeconds),
    latencyMs: summarizeLatencies(latencies), statuses, errorCodes, system,
  };
}

async function conflictProbes() {
  const probes = [];

  const subject = `conflict-start-${runId}`;
  const body = startBody(fixture.simple.code, subject);
  const key = uniqueKey('same-start');
  const starts = await Promise.all(Array.from({ length: quick ? 6 : 20 }, () =>
    request('/api/mobile/instances', { method: 'POST', token: fixture.admin, key, body })));
  const replay = await request('/api/mobile/instances', {
    method: 'POST', token: fixture.admin, key, body,
  });
  const startCount = await countSql(subjectSql(subject, 'count(DISTINCT pi.id)'));
  const startAllowed = [...starts, replay].every((r) =>
    r.status === 200 || (r.status === 409 && r.json?.code === 'IDEMPOTENCY_IN_PROGRESS'));
  probes.push(probeResult('same-key start', starts, startAllowed && replay.status === 200 &&
    replay.headers.get('idempotency-replayed') === 'true' && startCount === 1,
  { instances: startCount, replayed: replay.headers.get('idempotency-replayed') === 'true' }));

  const approve = await start(fixture.simple.code, `conflict-approve-${runId}`, fixture.admin);
  const approveResponses = await duplicateActions(approve.firstTaskIds[0], 'approve', fixture.aToken);
  const approveDownstream = await taskCount(approve.instanceId, "node_id = 'a2'");
  probes.push(probeResult('same-task approve', approveResponses,
    successCount(approveResponses) === 1 && expectedConflictResponses(approveResponses)
      && approveDownstream === 1, { downstreamTasks: approveDownstream }));

  const race = await start(fixture.simple.code, `conflict-approve-reject-${runId}`, fixture.admin);
  const raceResponses = await Promise.all(['approve', 'reject'].map((action) =>
    request(`/api/mobile/tasks/${race.firstTaskIds[0]}/${action}`, {
      method: 'POST', token: fixture.aToken, key: uniqueKey(`race-${action}`), body: { comment: 'race' },
    })));
  const raceDownstream = await taskCount(race.instanceId, "node_id = 'a2'");
  const raceRework = await taskCount(race.instanceId, "task_type = 'REWORK'");
  probes.push(probeResult('approve/reject race', raceResponses,
    successCount(raceResponses) === 1 && expectedConflictResponses(raceResponses)
      && (raceDownstream === 1) !== (raceRework === 1),
  { downstreamTasks: raceDownstream, reworkTasks: raceRework }));

  const transfer = await start(fixture.simple.code, `conflict-transfer-${runId}`, fixture.admin);
  const transferResponses = await Promise.all(Array.from({ length: quick ? 6 : 20 }, () =>
    request(`/api/tasks/${transfer.firstTaskIds[0]}/transfer`, {
      method: 'POST', token: fixture.aToken,
      body: { targetUserId: fixture.users.c.id, comment: 'race' },
    })));
  const transferChildren = await taskCount(transfer.instanceId, 'parent_task_id IS NOT NULL');
  probes.push(probeResult('same-task transfer', transferResponses,
    successCount(transferResponses) === 1 && expectedConflictResponses(transferResponses)
      && transferChildren === 1, { childTasks: transferChildren }));

  const parallel = await start(fixture.parallel.code, `conflict-parallel-${runId}`, fixture.admin);
  const parallelResponses = (await Promise.all([
    duplicateActions(parallel.firstTaskIds[0], 'approve', fixture.aToken),
    duplicateActions(parallel.firstTaskIds[1], 'approve', fixture.bToken),
  ])).flat();
  const joins = await taskCount(parallel.instanceId, "node_id = 'a3'");
  probes.push(probeResult('parallel duplicate approvals', parallelResponses,
    successCount(parallelResponses) === 2 && expectedConflictResponses(parallelResponses)
      && joins === 1, { joinTasks: joins }));

  const parallelRace = await start(fixture.parallel.code,
    `conflict-parallel-reject-${runId}`, fixture.admin);
  const parallelRaceResponses = await Promise.all([
    request(`/api/mobile/tasks/${parallelRace.firstTaskIds[0]}/approve`, {
      method: 'POST', token: fixture.aToken, key: uniqueKey('parallel-race-a'), body: { comment: 'race' },
    }),
    request(`/api/mobile/tasks/${parallelRace.firstTaskIds[1]}/reject`, {
      method: 'POST', token: fixture.bToken, key: uniqueKey('parallel-race-b'), body: { comment: 'race' },
    }),
  ]);
  const parallelRaceState = {
    reworkTasks: await taskCount(parallelRace.instanceId, "task_type = 'REWORK'"),
    joinTasks: await taskCount(parallelRace.instanceId, "node_id = 'a3'"),
    invalidatedApprovals: await taskCount(parallelRace.instanceId,
      "status = 'APPROVED' AND operation_kind = 'INVALIDATED'"),
  };
  probes.push(probeResult('parallel approve/reject race', parallelRaceResponses,
    !parallelRaceResponses.some((r) => r.status >= 500)
      && parallelRaceState.reworkTasks === 1 && parallelRaceState.joinTasks === 0
      && parallelRaceState.invalidatedApprovals <= 1, parallelRaceState));

  return probes;
}

async function duplicateActions(taskId, action, token) {
  return Promise.all(Array.from({ length: quick ? 6 : 20 }, () =>
    request(`/api/mobile/tasks/${taskId}/${action}`, {
      method: 'POST', token, key: uniqueKey(`duplicate-${action}`), body: { comment: 'duplicate' },
    })));
}

async function globalDuplicateChecks() {
  const checks = {
    duplicateSimpleDownstream: await countSql(`
      SELECT count(*) FROM (
        SELECT proc_inst_id FROM t_task WHERE node_id = 'a2'
        GROUP BY proc_inst_id HAVING count(*) > 1
      ) duplicate`),
    duplicateParallelJoin: await countSql(`
      SELECT count(*) FROM (
        SELECT proc_inst_id FROM t_task WHERE node_id = 'a3'
        GROUP BY proc_inst_id HAVING count(*) > 1
      ) duplicate`),
    duplicateTransferChild: await countSql(`
      SELECT count(*) FROM (
        SELECT parent_task_id FROM t_task WHERE parent_task_id IS NOT NULL
        GROUP BY parent_task_id HAVING count(*) > 1
      ) duplicate`),
  };
  return { ...checks, passed: Object.values(checks).every((value) => value === 0) };
}

function assess(allScenarios, allConflicts, duplicateChecks) {
  const fiveXx = allScenarios.reduce((sum, scenario) => sum + Object.entries(scenario.statuses)
    .filter(([status]) => /^5/.test(status)).reduce((n, [, count]) => n + count, 0), 0);
  const non2xx = allScenarios.reduce((sum, scenario) => sum + Object.entries(scenario.statuses)
    .filter(([status]) => !/^2/.test(status)).reduce((n, [, count]) => n + count, 0), 0);
  const hikariTimeouts = allScenarios.reduce((sum, scenario) =>
    sum + scenario.system.hikari.timeoutCountDelta, 0);
  const deadlocks = allScenarios.reduce((sum, scenario) => sum + scenario.system.postgres.deadlocks, 0);
  const invariantFailures = allScenarios.filter((scenario) => !scenario.invariant?.passed)
    .map((scenario) => `${scenario.name}@${scenario.concurrency}`);
  const conflictFailures = allConflicts.filter((probe) => !probe.passed).map((probe) => probe.name);
  return {
    passed: non2xx === 0 && fiveXx === 0 && hikariTimeouts === 0 && deadlocks === 0
      && invariantFailures.length === 0 && conflictFailures.length === 0 && duplicateChecks.passed,
    independentNon2xx: non2xx, fiveXx, hikariTimeouts, postgresDeadlocks: deadlocks,
    invariantFailures, conflictFailures, duplicateChecksPassed: duplicateChecks.passed,
  };
}

function systemSampler() {
  let stopping = false;
  const samples = [];
  const loop = (async () => {
    while (!stopping) {
      try { samples.push(await systemSample()); } catch (error) {
        samples.push({ sampleError: error.message });
      }
      if (!stopping) await delay(750);
    }
  })();
  return {
    async stop() {
      stopping = true;
      await loop;
      try { samples.push(await systemSample()); } catch { /* metrics stay best-effort */ }
      return summarizeSystem(samples);
    },
  };
}

async function systemSample() {
  const [hikari, postgres, containers] = await Promise.all([
    hikariSample(), pgActivity(), containerStats(),
  ]);
  return { hikari, postgres, containers };
}

async function hikariSample() {
  const names = ['active', 'idle', 'pending', 'max', 'acquire', 'timeout'];
  const values = await Promise.all(names.map(async (name) => {
    try {
      const response = await fetch(`${base}/actuator/metrics/hikaricp.connections.${name}`,
        { signal: AbortSignal.timeout(5_000) });
      if (!response.ok) return [name, {}];
      const metric = await response.json();
      return [name, Object.fromEntries(metric.measurements.map((m) => [m.statistic, m.value]))];
    } catch { return [name, {}]; }
  }));
  return Object.fromEntries(values);
}

async function pgActivity() {
  const row = await psql(`
    SELECT count(*) FILTER (WHERE pid <> pg_backend_pid()),
           count(*) FILTER (WHERE wait_event_type = 'Lock'),
           (SELECT count(*) FROM pg_locks WHERE NOT granted)
    FROM pg_stat_activity WHERE datname = current_database()`);
  const [active = 0, lockWaits = 0, ungrantedLocks = 0] = row.trim().split('\t').map(Number);
  return { active, lockWaits, ungrantedLocks };
}

async function dbCounters() {
  const row = await psql(`SELECT xact_commit, xact_rollback, deadlocks
    FROM pg_stat_database WHERE datname = current_database()`);
  const [commits = 0, rollbacks = 0, deadlocks = 0] = row.trim().split('\t').map(Number);
  return { commits, rollbacks, deadlocks };
}

async function containerStats() {
  const { stdout } = await exec('docker', ['stats', '--no-stream', '--format', '{{json .}}',
    'antflow-write-load-backend', 'antflow-write-load-postgres'], { timeout: 10_000 });
  return Object.fromEntries(stdout.trim().split(/\r?\n/).filter(Boolean).map((line) => {
    const item = JSON.parse(line);
    return [item.Name, { cpuPercent: Number(item.CPUPerc.replace('%', '')),
      memoryMiB: memoryMiB(item.MemUsage.split('/')[0].trim()) }];
  }));
}

function summarizeSystem(samples) {
  const valid = samples.filter((sample) => sample.hikari);
  const firstTimeout = valid[0]?.hikari.timeout.COUNT ?? 0;
  const lastTimeout = valid.at(-1)?.hikari.timeout.COUNT ?? firstTimeout;
  const acquireMaxSeconds = max(valid.map((sample) => sample.hikari.acquire.MAX));
  return {
    samples: valid.length,
    hikari: {
      configuredPoolSize: poolSize,
      observedMaxPoolSize: max(valid.map((sample) => sample.hikari.max.VALUE)),
      activePeak: max(valid.map((sample) => sample.hikari.active.VALUE)),
      idlePeak: max(valid.map((sample) => sample.hikari.idle.VALUE)),
      pendingPeak: max(valid.map((sample) => sample.hikari.pending.VALUE)),
      acquireMaxMs: round(acquireMaxSeconds * 1000),
      timeoutCountDelta: Math.max(0, lastTimeout - firstTimeout),
    },
    postgres: {
      activePeak: max(valid.map((sample) => sample.postgres.active)),
      lockWaitPeak: max(valid.map((sample) => sample.postgres.lockWaits)),
      ungrantedLockPeak: max(valid.map((sample) => sample.postgres.ungrantedLocks)),
    },
    containers: Object.fromEntries(['antflow-write-load-backend', 'antflow-write-load-postgres']
      .map((name) => [name, {
        cpuPeakPercent: max(valid.map((sample) => sample.containers[name]?.cpuPercent)),
        memoryPeakMiB: max(valid.map((sample) => sample.containers[name]?.memoryMiB)),
      }])),
  };
}

async function request(path, { method = 'GET', token, body, key } = {}) {
  const headers = { accept: 'application/json' };
  if (token) headers.authorization = `Bearer ${token}`;
  if (body !== undefined) headers['content-type'] = 'application/json';
  if (key) headers['idempotency-key'] = key;
  const response = await fetch(new URL(path, base), {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(30_000),
  });
  const text = await response.text();
  let json = null;
  if (text) {
    try { json = JSON.parse(text); } catch { json = { raw: text }; }
  }
  return { status: response.status, json, headers: response.headers };
}

async function login(username, password) {
  const response = await expectOk(await request('/api/auth/login', {
    method: 'POST', body: { username, password },
  }), `login ${username}`);
  assert(response.json?.accessToken, `login ${username} returned no token`);
  return response.json.accessToken;
}

async function start(formCode, subject, token) {
  const response = await expectOk(await request('/api/mobile/instances', {
    method: 'POST', token, key: uniqueKey('setup'), body: startBody(formCode, subject),
  }), `start ${subject}`);
  return response.json;
}

function startBody(formCode, subject) {
  return { formCode, data: { subject }, selfSelected: {}, files: [] };
}

async function expectOk(response, operation) {
  if (response.status < 200 || response.status >= 300) {
    throw new Error(`${operation} failed: HTTP ${response.status} ${JSON.stringify(response.json)}`);
  }
  return response;
}

async function psql(sql) {
  const { stdout } = await exec('docker', ['exec', 'antflow-write-load-postgres',
    'psql', '-U', 'antflow', '-d', 'antflow', '-At', '-F', '\t', '-c', sql],
  { timeout: 30_000, maxBuffer: 10 * 1024 * 1024 });
  return stdout;
}

async function countSql(sql) { return Number((await psql(sql)).trim() || 0); }
async function taskCount(instanceId, where) {
  return countSql(`SELECT count(*) FROM t_task WHERE proc_inst_id = ${Number(instanceId)} AND ${where}`);
}

function subjectSql(prefix, select, joinTasks = false) {
  return `SELECT ${select} FROM t_process_instance pi
    JOIN t_form_data fd ON fd.id = pi.form_data_id
    ${joinTasks ? 'LEFT JOIN t_task t ON t.proc_inst_id = pi.id' : ''}
    WHERE fd.data->>'subject' LIKE '${sqlLiteral(prefix)}%'`;
}

function sqlLiteral(value) { return String(value).replaceAll("'", "''"); }
function uniqueKey(label) { return `${runId}-${label}-${++sequence}`; }
function successCount(responses) { return responses.filter((r) => r.status >= 200 && r.status < 300).length; }
function expectedConflictResponses(responses) {
  return responses.every((r) => (r.status >= 200 && r.status < 300)
    || r.status === 409 || r.status === 422);
}
function probeResult(name, responses, passed, details) {
  const statuses = {};
  const errorCodes = {};
  for (const response of responses) {
    statuses[response.status] = (statuses[response.status] ?? 0) + 1;
    if (response.status >= 400) {
      const code = response.json?.code ?? `HTTP_${response.status}`;
      errorCodes[code] = (errorCodes[code] ?? 0) + 1;
    }
  }
  return { name, passed, statuses, errorCodes, details };
}

function invariant(passed, details) { return { passed, ...details }; }
function summarizeLatencies(values) {
  return { mean: round(values.reduce((sum, value) => sum + value, 0) / Math.max(1, values.length)),
    p50: percentile(values, 0.5), p95: percentile(values, 0.95), p99: percentile(values, 0.99) };
}
function percentile(sorted, ratio) {
  if (!sorted.length) return 0;
  return round(sorted[Math.min(sorted.length - 1, Math.floor((sorted.length - 1) * ratio))]);
}
function max(values) { return Math.max(0, ...values.filter(Number.isFinite)); }
function round(value) { return Math.round((value ?? 0) * 100) / 100; }
function delay(ms) { return new Promise((resolve) => setTimeout(resolve, ms)); }
function numberArg(name, fallback) {
  const value = Number(args[name] ?? fallback);
  if (!Number.isFinite(value) || value <= 0) throw new Error(`--${name} must be positive`);
  return value;
}

async function mapLimit(items, concurrency, worker) {
  const output = new Array(items.length);
  let cursor = 0;
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (true) {
      const index = cursor++;
      if (index >= items.length) return;
      output[index] = await worker(items[index], index);
    }
  }));
  return output;
}

function simpleFlow(a, b) {
  return { id: 'root', type: 'ROOT', children: {
    id: 'a1', type: 'APPROVAL', props: { assignedType: 'ASSIGN_USER', assignedUser: [a], mode: 'OR' },
    children: { id: 'a2', type: 'APPROVAL',
      props: { assignedType: 'ASSIGN_USER', assignedUser: [b], mode: 'OR' }, children: null },
  } };
}

function parallelFlow(a, b, c) {
  return { id: 'root', type: 'ROOT', children: {
    id: 'p1', type: 'PARALLEL', props: { joinMode: 'ALL' }, branchs: [
      { id: 'b1', type: 'BRANCH', props: { conditionMode: 'ALWAYS' }, children: {
        id: 'a1', type: 'APPROVAL', props: { assignedType: 'ASSIGN_USER', assignedUser: [a], mode: 'OR' }, children: null } },
      { id: 'b2', type: 'BRANCH', props: { conditionMode: 'ALWAYS' }, children: {
        id: 'a2', type: 'APPROVAL', props: { assignedType: 'ASSIGN_USER', assignedUser: [b], mode: 'OR' }, children: null } },
    ], children: { id: 'a3', type: 'APPROVAL',
      props: { assignedType: 'ASSIGN_USER', assignedUser: [c], mode: 'OR' }, children: null },
  } };
}

function memoryMiB(text) {
  const match = String(text).match(/^([\d.]+)([KMG]iB)$/i);
  if (!match) return 0;
  return round(Number(match[1]) * ({ kib: 1 / 1024, mib: 1, gib: 1024 }[match[2].toLowerCase()]));
}

function comparisonMarkdown(first, second) {
  const [left, right] = first.poolSize <= second.poolSize ? [first, second] : [second, first];
  const rows = left.scenarios.map((item) => {
    const other = right.scenarios.find((candidate) => candidate.name === item.name
      && candidate.concurrency === item.concurrency);
    const change = other ? round((other.requestsPerSecond / item.requestsPerSecond - 1) * 100) : 0;
    return `| ${item.name} | ${item.concurrency} | ${item.requestsPerSecond} | ${other?.requestsPerSecond ?? '-'} | ${change}% | ${item.latencyMs.p95} | ${other?.latencyMs.p95 ?? '-'} |`;
  });
  return `# Write-path load comparison\n\n` +
    `Environment-specific capacity characterization; this is not a production SLA.\n\n` +
    `| Path | Concurrency | Pool ${left.poolSize} RPS | Pool ${right.poolSize} RPS | RPS delta | Pool ${left.poolSize} p95 ms | Pool ${right.poolSize} p95 ms |\n` +
    `|---|---:|---:|---:|---:|---:|---:|\n${rows.join('\n')}\n\n` +
    acceptanceLine(left) + acceptanceLine(right);
}

function acceptanceLine(report) {
  const conflicts = report.acceptance.conflictFailures?.length
    ? `; conflict failures=${report.acceptance.conflictFailures.join(', ')}` : '';
  return `- Pool ${report.poolSize}: ${report.acceptance.passed ? 'PASS' : 'FAIL'}; 5xx=${report.acceptance.fiveXx}, Hikari timeouts=${report.acceptance.hikariTimeouts}, PostgreSQL deadlocks=${report.acceptance.postgresDeadlocks}${conflicts}\n`;
}

async function writeText(file, content) {
  await mkdir(new URL('.', new URL(`file:///${String(file).replaceAll('\\', '/')}`)), { recursive: true });
  await writeFile(file, content, 'utf8');
}

function selfTest() {
  assert.deepEqual(summarizeLatencies([1, 2, 3, 4]), { mean: 2.5, p50: 2, p95: 3, p99: 3 });
  assert.equal(memoryMiB('1.5GiB'), 1536);
  assert(expectedConflictResponses([{ status: 200 }, { status: 409 }, { status: 422 }]));
  const sample = { poolSize: 10, acceptance: { passed: true, fiveXx: 0,
    hikariTimeouts: 0, postgresDeadlocks: 0 }, scenarios: [{ name: 'start', concurrency: 1,
      requestsPerSecond: 10, latencyMs: { p95: 5 } }] };
  const markdown = comparisonMarkdown(sample, { ...sample, poolSize: 20,
    scenarios: [{ name: 'start', concurrency: 1, requestsPerSecond: 12, latencyMs: { p95: 4 } }] });
  assert.match(markdown, /20%/);
  console.log('write-load self-test: PASS');
}
