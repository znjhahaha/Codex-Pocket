import { createServer } from 'node:http';
import { timingSafeEqual, randomUUID } from 'node:crypto';
import { mkdir, readFile, open, rename, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const validUuid = value => typeof value === 'string' && uuid.test(value);
const validToken = value => typeof value === 'string' && /^[\x21-\x7e]{1,4096}$/.test(value);
const day = 86400_000;
const genericBody = '任务有新进展，打开查看';
export function notificationPayload(token, eventId) {
  if (!validUuid(eventId) || !validToken(token)) throw new Error('invalid_payload');
  return { validate_only: false, message: {
    token: [token], notification: { title: 'Codex Pocket', body: genericBody },
    android: { urgency: 'HIGH', ttl: '3600s', notification: {
      title: 'Codex Pocket', body: genericBody, foreground_show: false,
      click_action: { type: 1, intent: `intent://notification/${eventId}#Intent;scheme=codexpocket;package=dev.codexpocket.app;end` },
    } },
  } };
}

export function huaweiSender({ appId, appSecret, apiOrigin = 'https://push-api.cloud.huawei.com', fetcher = fetch }) {
  if (typeof appId !== 'string' || !/^\d+$/.test(appId) || typeof appSecret !== 'string' || !appSecret) throw new Error('Huawei Push Kit configuration is incomplete');
  let origin;
  try { origin = new URL(apiOrigin); } catch { throw new Error('Invalid Huawei API origin'); }
  if (origin.protocol !== 'https:' || !/^push-api(?:\.cloud)?\.(?:huawei\.com|hicloud\.com)$/.test(origin.hostname) || origin.port || origin.username || origin.password || origin.search || origin.hash || origin.pathname !== '/') throw new Error('Invalid Huawei API origin');
  let cached = null;
  let fetching = null;
  const accessToken = async () => {
    if (cached && cached.expires > Date.now()) return cached.token;
    if (!fetching) fetching = (async () => {
      const response = await fetcher('https://oauth-login.cloud.huawei.com/oauth2/v3/token', {
        method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, redirect: 'error', signal: AbortSignal.timeout(10_000),
        body: new URLSearchParams({ grant_type: 'client_credentials', client_id: appId, client_secret: appSecret }),
      });
      if (!response.ok) throw new Error('provider_auth_failed');
      const data = await response.json();
      if (!data || !validToken(data.access_token) || !['string', 'number'].includes(typeof data.expires_in) || !Number.isFinite(Number(data.expires_in)) || Number(data.expires_in) <= 0) throw new Error('provider_auth_failed');
      cached = { token: data.access_token, expires: Date.now() + Math.max(0, Number(data.expires_in) - 60) * 1000 };
      return cached.token;
    })().catch(() => { throw new Error('provider_auth_failed'); }).finally(() => { fetching = null; });
    return fetching;
  };
  return async (token, eventId) => {
    const body = JSON.stringify(notificationPayload(token, eventId));
    const access = await accessToken();
    try {
      const response = await fetcher(`${origin.origin}/v1/${appId}/messages:send`, {
        method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${access}` },
        redirect: 'error', signal: AbortSignal.timeout(10_000), body,
      });
      if (response.status === 401) cached = null;
      if (!response.ok) throw new Error('provider_send_failed');
      const data = await response.json();
      // An expired token invalidates the cache for the next distinct event; never replay this send.
      if (String(data?.code) === '80200003') cached = null;
      if (String(data?.code) !== '80000000') throw new Error('provider_send_failed');
    } catch { throw new Error('provider_send_failed'); }
  };
}

/** Dedicated private gateway: receives routing metadata only, never chat content. */
export async function createGateway({ secret, state, send, now = Date.now }) {
  if (typeof secret !== 'string' || !/^[\x21-\x7e]{32,256}$/.test(secret)) throw new Error('A 32–256 character printable ASCII gateway secret is required');
  const directory = resolve(state);
  await mkdir(directory, { recursive: true, mode: 0o700 });
  const filename = join(directory, 'registrations.json');
  let devices = {};
  try { devices = JSON.parse(await readFile(filename, 'utf8')); }
  catch (error) { if (error.code !== 'ENOENT') throw new Error('registration_store_unreadable'); }
  if (!devices || Array.isArray(devices) || typeof devices !== 'object') throw new Error('invalid_registration_store');
  const canonicalDevices = {};
  for (const [id, device] of Object.entries(devices)) {
    if (!validUuid(id) || !device || !validToken(device.token) || !Number.isFinite(device.expires) || canonicalDevices[id.toLowerCase()]) throw new Error('invalid_registration_store');
    if (device.expires > now()) canonicalDevices[id.toLowerCase()] = device;
  }
  devices = canonicalDevices;
  const deliveryFile = join(directory, 'deliveries.json');
  let records = {};
  try { records = JSON.parse(await readFile(deliveryFile, 'utf8')); }
  catch (error) { if (error.code !== 'ENOENT') throw new Error('delivery_store_unreadable'); }
  if (!records || Array.isArray(records) || typeof records !== 'object') throw new Error('invalid_delivery_store');
  const seen = new Map();
  for (const [key, record] of Object.entries(records)) {
    const ids = key.split(':');
    if (ids.length !== 2 || !ids.every(validUuid) || !record || !Number.isFinite(record.timestamp) || !['unconfirmed', 'submitted'].includes(record.status)) throw new Error('invalid_delivery_store');
    if (record.timestamp > now() - day) seen.set(key.toLowerCase(), record);
  }
  let writes = Promise.resolve();
  const persist = (filename, data) => {
    const value = JSON.stringify(data);
    writes = writes.catch(() => {}).then(async () => {
      const tmp = join(directory, `.push-${randomUUID()}.tmp`);
      try {
        const file = await open(tmp, 'wx', 0o600);
        try { await file.writeFile(value); await file.sync(); } finally { await file.close(); }
        await rename(tmp, filename);
        if (process.platform !== 'win32') {
          const folder = await open(directory, 'r');
          try { await folder.sync(); } finally { await folder.close(); }
        }
      } finally { await rm(tmp, { force: true }); }
    });
    return writes;
  };
  const persistSeen = () => persist(deliveryFile, Object.fromEntries(seen));
  let registrations = Promise.resolve();
  const updateDevice = (id, device) => {
    registrations = registrations.catch(() => {}).then(async () => {
      const next = Object.fromEntries(Object.entries(devices).filter(([, value]) => value.expires > now()));
      if (device) {
        if (!next[id] && Object.keys(next).length >= 100) return false;
        next[id] = device;
      } else {
        delete next[id];
        // Revoke in memory even if persistence fails; the caller receives no durable acknowledgement.
        delete devices[id];
      }
      await persist(filename, next);
      devices = next;
      return true;
    });
    return registrations;
  };
  let rate = { since: now(), count: 0 };
  const server = createServer(async (req, res) => {
    const reply = (status, body) => { res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' }); res.end(JSON.stringify(body)); };
    if (req.url === '/healthz' && req.method === 'GET') return reply(200, { ok: true });
    const supplied = Buffer.from(req.headers.authorization || '');
    const expected = Buffer.from(`Bearer ${secret}`);
    if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) { req.resume(); return reply(401, { error: 'unauthorized' }); }
    if (now() - rate.since >= 60_000) rate = { since: now(), count: 0 };
    if (++rate.count > 120) { req.resume(); return reply(429, { error: 'rate_limited' }); }
    if (req.method !== 'POST' || !['/register', '/unregister', '/send'].includes(req.url)) { req.resume(); return reply(404, { error: 'not_found' }); }
    try {
      let size = 0; const chunks = [];
      for await (const chunk of req) { size += chunk.length; if (size > 8192) { reply(413, { error: 'too_large' }); req.destroy(); return; } chunks.push(chunk); }
      let body;
      try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
      catch { return reply(400, { error: 'invalid_request' }); }
      const keys = req.url === '/register' ? ['deviceId', 'token'] : req.url === '/send' ? ['deviceId', 'eventId'] : ['deviceId'];
      if (!body || typeof body !== 'object' || Array.isArray(body) || Object.keys(body).some(k => !keys.includes(k)) || !validUuid(body.deviceId)) return reply(400, { error: 'invalid_request' });
      body.deviceId = body.deviceId.toLowerCase();
      if (req.url === '/register') {
        if (!validToken(body.token)) return reply(400, { error: 'invalid_request' });
        if (!await updateDevice(body.deviceId, { token: body.token, expires: now() + 30 * day })) return reply(409, { error: 'capacity' });
        return reply(200, { registered: true });
      }
      if (req.url === '/unregister') { await updateDevice(body.deviceId, null); return reply(200, { revoked: true }); }
      if (!validUuid(body.eventId)) return reply(400, { error: 'invalid_request' });
      body.eventId = body.eventId.toLowerCase();
      const device = devices[body.deviceId];
      if (!device || device.expires <= now()) return reply(404, { error: 'device_unavailable' });
      for (const [key, record] of seen) if (record.timestamp <= now() - day) seen.delete(key);
      const key = `${body.deviceId}:${body.eventId}`;
      if (seen.has(key)) return seen.get(key).status === 'submitted'
        ? reply(200, { status: 'submitted' }) : reply(502, { error: 'delivery_unconfirmed' });
      if (seen.size >= 180_000) return reply(429, { error: 'capacity' });
      // Reserve before dispatch. Lost provider acknowledgements never authorize an automatic resend.
      const reservation = { timestamp: now(), status: 'unconfirmed' };
      seen.set(key, reservation);
      await persistSeen();
      // A concurrent revocation or token rotation wins while disk persistence is in flight.
      if (devices[body.deviceId] !== device || device.expires <= now()) return reply(404, { error: 'device_unavailable' });
      await send(device.token, body.eventId);
      reservation.status = 'submitted';
      await persistSeen();
      return reply(202, { status: 'submitted' });
    } catch { return reply(502, { error: 'delivery_unconfirmed' }); }
  });
  server.requestTimeout = 15_000; server.headersTimeout = 10_000;
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const sender = huaweiSender({ appId: process.env.HUAWEI_APP_ID, appSecret: process.env.HUAWEI_APP_SECRET });
    const server = await createGateway({ secret: process.env.POCKET_PUSH_SECRET, state: process.env.POCKET_PUSH_STATE || '/data', send: sender });
    server.listen(Number(process.env.PORT || 8788), process.env.HOST || '127.0.0.1', () => process.stdout.write('Codex Pocket push gateway ready\n'));
  } catch { process.stderr.write('Push gateway configuration or storage is unavailable\n'); process.exitCode = 1; }
}
