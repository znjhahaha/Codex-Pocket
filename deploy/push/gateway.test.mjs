import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createGateway, notificationPayload, huaweiSender } from './server.mjs';

const secret = 's'.repeat(48);
const deviceId = '2d80c826-16c6-4b3a-ab89-814772753aee';
const eventId = '74701caf-44a5-4764-8e78-939210eb146c';
async function fixture(t, options = {}) {
  const state = await mkdtemp(join(tmpdir(), 'pocket-push-'));
  let server;
  let base;
  const start = async () => {
    server = await createGateway({ secret, state, send: async () => {}, ...options });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    base = `http://127.0.0.1:${server.address().port}`;
  };
  const close = () => new Promise(resolve => { server.close(resolve); server.closeIdleConnections(); });
  await start();
  t.after(async () => { await close(); await rm(state, { recursive: true, force: true }); });
  const raw = (path, body) => fetch(`${base}${path}`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${secret}` }, body });
  return { state, raw, post: (path, body) => raw(path, JSON.stringify(body)), restart: async () => { await close(); await start(); } };
}

test('Huawei payload contains no conversation content and an opaque route only', () => {
  const id = '74701caf-44a5-4764-8e78-939210eb146c';
  const value = notificationPayload('token', id);
  assert.deepEqual(value.message.token, ['token']);
  assert.equal(value.message.notification.title, 'Codex Pocket');
  assert.ok(value.message.android.notification.click_action.intent.includes(id));
  assert.equal(value.message.android.notification.foreground_show, false);
  assert.throws(() => notificationPayload('token', 'chat/title?secret'));
});

test('malformed JSON is a client error and never echoes request content', async t => {
  const gateway = await fixture(t);
  for (const body of ['{"token":"private-token",', '', 'null', '[]', '42', '"text"']) {
    const response = await gateway.raw('/register', body);
    assert.equal(response.status, 400);
    assert.deepEqual(await response.json(), { error: 'invalid_request' });
  }
});

test('UUID fields reject arrays instead of coercing them to strings', async t => {
  let sends = 0;
  const gateway = await fixture(t, { send: async () => { sends++; } });
  assert.equal((await gateway.post('/register', { deviceId: [deviceId], token: 'token' })).status, 400);
  await gateway.post('/register', { deviceId, token: 'token' });
  assert.equal((await gateway.post('/send', { deviceId, eventId: [eventId] })).status, 400);
  assert.equal(sends, 0);
});

test('failed provider acknowledgement stays unconfirmed on replay and restart', async t => {
  let sends = 0;
  const gateway = await fixture(t, { send: async () => { sends++; throw new Error('private-provider-token'); } });
  await gateway.post('/register', { deviceId, token: 'token' });
  for (let attempt = 0; attempt < 3; attempt++) {
    const response = await gateway.post('/send', { deviceId, eventId });
    assert.equal(response.status, 502);
    assert.deepEqual(await response.json(), { error: 'delivery_unconfirmed' });
    if (attempt === 1) await gateway.restart();
  }
  assert.equal(sends, 1);
});

test('successful deduplication survives restart and UUID case changes', async t => {
  let sends = 0;
  const gateway = await fixture(t, { send: async () => { sends++; } });
  await gateway.post('/register', { deviceId, token: 'token' });
  assert.equal((await gateway.post('/send', { deviceId, eventId })).status, 202);
  await gateway.restart();
  assert.equal((await gateway.post('/send', { deviceId: deviceId.toUpperCase(), eventId: eventId.toUpperCase() })).status, 200);
  assert.equal(sends, 1);
});

test('in-flight duplicate never claims provider acknowledgement', async t => {
  let unblock;
  let entered;
  const started = new Promise(resolve => { entered = resolve; });
  const gate = new Promise(resolve => { unblock = resolve; });
  let sends = 0;
  const gateway = await fixture(t, { send: async () => { sends++; entered(); await gate; throw new Error('unknown outcome'); } });
  await gateway.post('/register', { deviceId, token: 'token' });
  const first = gateway.post('/send', { deviceId, eventId });
  await started;
  const duplicate = gateway.post('/send', { deviceId, eventId });
  unblock();
  const responses = await Promise.all([first, duplicate]);
  assert.deepEqual(responses.map(response => response.status), [502, 502]);
  assert.equal(sends, 1);
});

test('registration expires at the boundary and token rotation uses only the new token', async t => {
  let now = 1_000;
  const tokens = [];
  const gateway = await fixture(t, { now: () => now, send: async token => { tokens.push(token); } });
  await gateway.post('/register', { deviceId, token: 'old' });
  await gateway.post('/register', { deviceId, token: 'new' });
  await gateway.post('/send', { deviceId, eventId });
  assert.deepEqual(tokens, ['new']);
  now += 30 * 86400_000;
  assert.equal((await gateway.post('/send', { deviceId, eventId: '4f17608c-5663-429d-8e4d-78106bdca3b4' })).status, 404);
});

test('persisted event reservations contain no tokens or conversation routes', async t => {
  const gateway = await fixture(t);
  await gateway.post('/register', { deviceId, token: 'private-device-token' });
  await gateway.post('/send', { deviceId, eventId });
  const saved = await readFile(join(gateway.state, 'deliveries.json'), 'utf8');
  assert.ok(saved.includes(eventId));
  assert.ok(!saved.includes('private-device-token'));
  assert.ok(!saved.includes('runtimeId') && !saved.includes('threadId'));
});

test('Huawei transport uses the official OAuth and messages envelopes with synthetic credentials', async () => {
  const calls = [];
  const send = huaweiSender({ appId: '123456', appSecret: 'fixture+secret&=', fetcher: async (url, init) => {
    calls.push({ url, init });
    return Response.json(url.includes('/oauth2/') ? { access_token: 'fixture-access', expires_in: 3600 } : { code: '80000000' });
  } });
  await send('fixture-device', eventId);
  await send('fixture-device', eventId);
  assert.equal(calls.length, 3);
  assert.equal(calls[0].url, 'https://oauth-login.cloud.huawei.com/oauth2/v3/token');
  assert.equal(calls[0].init.method, 'POST');
  assert.equal(calls[0].init.headers['content-type'], 'application/x-www-form-urlencoded');
  assert.deepEqual(Object.fromEntries(calls[0].init.body), { grant_type: 'client_credentials', client_id: '123456', client_secret: 'fixture+secret&=' });
  for (const call of calls.slice(1)) {
    assert.equal(call.url, 'https://push-api.cloud.huawei.com/v1/123456/messages:send');
    assert.equal(call.init.headers.authorization, 'Bearer fixture-access');
    assert.equal(call.init.redirect, 'error');
    assert.ok(call.init.signal instanceof AbortSignal);
    assert.deepEqual(JSON.parse(call.init.body), notificationPayload('fixture-device', eventId));
    assert.ok(!call.init.body.includes('fixture+secret&=') && !call.init.body.includes('fixture-access'));
  }
});

test('Huawei rejects invalid payloads before obtaining an access token', async () => {
  let calls = 0;
  const send = huaweiSender({ appId: '123456', appSecret: 'fixture-secret', fetcher: async () => {
    calls++; return Response.json({ access_token: 'fixture-access', expires_in: 3600 });
  } });
  await assert.rejects(send('fixture-device', 'private-conversation-id'));
  assert.equal(calls, 0);
});

test('Huawei provider errors cannot expose credentials through thrown errors', async () => {
  const send = huaweiSender({ appId: '123456', appSecret: 'fixture-secret', fetcher: async () => { throw new Error('fixture-secret'); } });
  await assert.rejects(send('fixture-device', eventId), error => error.message === 'provider_auth_failed');
});

test('Huawei does not send with an empty OAuth token or retry an uncertain send', async () => {
  for (const accessToken of ['', 'token\r\nheader']) {
    let calls = 0;
    const send = huaweiSender({ appId: '123456', appSecret: 'fixture-secret', fetcher: async () => {
      calls++; return Response.json({ access_token: accessToken, expires_in: 3600 });
    } });
    await assert.rejects(send('fixture-device', eventId));
    assert.equal(calls, 1);
  }
});

test('authenticated registration, payload rejection, deduplication and revocation', async () => {
  const state = await mkdtemp(join(tmpdir(), 'pocket-push-'));
  const deliveries = [];
  const server = await createGateway({ secret: 's'.repeat(48), state, send: async (token, eventId) => { deliveries.push({ token, eventId }); } });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  const deviceId = '2d80c826-16c6-4b3a-ab89-814772753aee';
  const eventId = '74701caf-44a5-4764-8e78-939210eb146c';
  const post = (path, body, auth = true) => fetch(`${base}${path}`, { method: 'POST', headers: { 'content-type': 'application/json', ...(auth ? { authorization: `Bearer ${'s'.repeat(48)}` } : {}) }, body: JSON.stringify(body) });
  try {
    assert.equal((await post('/register', { deviceId, token: 'fixture' }, false)).status, 401);
    assert.equal((await post('/register', { deviceId, token: 'fixture' })).status, 200);
    assert.equal((await post('/send', { deviceId, eventId, body: 'private chat' })).status, 400);
    assert.equal((await post('/send', { deviceId, eventId })).status, 202);
    assert.equal((await post('/send', { deviceId, eventId })).status, 200);
    assert.deepEqual(deliveries, [{ token: 'fixture', eventId }]);
    assert.equal((await post('/unregister', { deviceId })).status, 200);
    assert.equal((await post('/send', { deviceId, eventId: '4f17608c-5663-429d-8e4d-78106bdca3b4' })).status, 404);
  } finally { await new Promise(resolve => server.close(resolve)); await rm(state, { recursive: true, force: true }); }
});
