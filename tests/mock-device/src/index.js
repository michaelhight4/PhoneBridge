#!/usr/bin/env node
/**
 * PhoneBridge mock device.
 *
 * Connects to a relay as a device, answers every protocol command with realistic fake data,
 * honours permission levels and the control queue, so the MCP server and CLI can be tested end to end.
 *
 * DEV MODE: by default this mock uses plaintext dev payloads (base64url of UTF-8 JSON) because
 * the E2E crypto implementation (tasks 01/05) may not be merged yet. Set --crypto to require
 * encrypted payloads (not yet implemented here; will be added when core crypto lands).
 *
 * Usage:
 *   node src/index.js --relay ws://127.0.0.1:8787
 *   node src/index.js --relay ws://127.0.0.1:8787 --name "Mock Pixel" --auto-accept
 */
import { WebSocket } from 'ws';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import {
  b64url, b64urlDecode, b64, randomBytes, sha256,
  peerIdFromPublicKey, fingerprint, generateX25519KeyPair, helloProof
} from './crypto.js';

const args = parseArgs(process.argv.slice(2));
if (args.help) {
  console.log(`PhoneBridge mock device\n\nOptions:\n  --relay <ws://...>      Relay URL (required)\n  --name <name>           Device name (default: Mock Phone)\n  --auto-accept           Auto-accept pair requests (default: true in dev mode)\n  --no-auto-accept        Require manual approval (not interactive; denies)\n  --plaintext             Use plaintext dev payloads (default: true)\n  --crypto                Require encrypted payloads (not implemented yet)\n  --log <file>            Append JSONL log\n  --once                  Exit after first connection closes\n  --help                  Show help`);
  process.exit(0);
}

if (!args.relay) {
  console.error('--relay is required (example: --relay ws://127.0.0.1:8787)');
  process.exit(2);
}
if (args.crypto) {
  console.error('Encrypted E2E mode is not implemented in this mock yet. Use --plaintext (default) until core crypto lands; see HANDOFF.md.');
  process.exit(2);
}

const deviceName = args.name || 'Mock Phone';
const autoAccept = args.autoAccept !== false;
const plaintextMode = !args.crypto;
const logFile = args.log || null;
const keyPair = generateX25519KeyPair();
const deviceId = peerIdFromPublicKey(keyPair.pubRaw);
const devicePubB64 = b64(keyPair.pubRaw);

const state = {
  pairedBots: new Map(), // peerId -> { name, level, pub }
  controller: null, // peerId
  queue: [], // peerId[]
  lastActivity: Date.now(),
  controlTimer: null,
  screenOn: true,
  notifications: [
    { pkg: 'com.example.chat', title: 'Alice', text: 'Hey, are you there?', time: Date.now() - 60000 },
    { pkg: 'com.android.systemui', title: 'System', text: 'Mock device ready', time: Date.now() - 30000 },
  ],
};

logEvent('startup', { deviceId, fingerprint: fingerprint(keyPair.pubRaw), relay: args.relay, plaintextMode, deviceName });

let ws;
let reconnectDelay = 500;
let closing = false;

connect();

function connect() {
  ws = new WebSocket(args.relay);
  ws.on('open', () => {
    reconnectDelay = 500;
    logEvent('ws_open', { relay: args.relay });
  });
  ws.on('message', (data) => handleFrame(data.toString('utf8')));
  ws.on('close', (code, reason) => {
    logEvent('ws_close', { code, reason: reason.toString('utf8') });
    clearControlTimer();
    state.controller = null;
    state.queue = [];
    if (!closing && !args.once) {
      setTimeout(connect, reconnectDelay);
      reconnectDelay = Math.min(reconnectDelay * 2, 5000);
    }
  });
  ws.on('error', (err) => logEvent('ws_error', { message: err.message }));
}

function sendEnvelope(obj) {
  const text = JSON.stringify(obj);
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(text);
}

function handleFrame(text) {
  let msg;
  try { msg = JSON.parse(text); } catch { return; }
  logEvent('recv', { type: msg.type, from: msg.from, id: msg.id });
  switch (msg.type) {
    case 'challenge':
      return handleChallenge(msg);
    case 'hello_ok':
      logEvent('hello_ok', { relayId: msg.relay_id, peer: msg.peer });
      return;
    case 'route':
      return handleRoute(msg);
    case 'ping':
      return sendEnvelope({ v: 1, type: 'pong', from: deviceId, to: msg.from, id: msg.id });
    case 'error':
      logEvent('relay_error', msg.error || msg);
      return;
    case 'relay_moved':
      logEvent('relay_moved', { new_url: msg.new_url });
      return;
    default:
      return;
  }
}

function handleChallenge(msg) {
  const nonceRaw = b64urlDecode(msg.nonce);
  const relayPubRaw = b64urlDecode(msg.relay_pub);
  const proof = helloProof(keyPair.privateKey, relayPubRaw, msg.relay_id, deviceId, nonceRaw);
  sendEnvelope({
    v: 1,
    type: 'hello',
    from: deviceId,
    pub: devicePubB64,
    nonce: msg.nonce,
    sig: b64url(proof),
    role: 'device',
  });
}


function handleRoute(msg) {
  if (!msg.payload) return;
  let inner;
  try {
    inner = decodePayload(msg.payload);
  } catch (e) {
    logEvent('decode_error', { from: msg.from, error: String(e.message || e) });
    return;
  }
  if (inner && inner.kind === 'pair_request') {
    return handlePairRequest(msg.from, msg.id, inner);
  }
  if (inner && inner.cmd) {
    return handleCommand(msg.from, msg.id, inner);
  }
  if (inner && inner.bot_name && inner.bot_pub && inner.requested_level) {
    return handlePairRequest(msg.from, msg.id, { kind: 'pair_request', ...inner });
  }
  logEvent('unknown_route_payload', { from: msg.from, keys: inner && typeof inner === 'object' ? Object.keys(inner) : [] });
}

function decodePayload(payloadB64url) {
  // DEV MODE: plaintext payload is base64url(utf8 JSON).
  const text = Buffer.from(payloadB64url, 'base64url').toString('utf8');
  return JSON.parse(text);
}

function encodePayload(obj) {
  return Buffer.from(JSON.stringify(obj), 'utf8').toString('base64url');
}

function sendToPeer(to, payloadObj) {
  sendEnvelope({
    v: 1,
    type: 'route',
    from: deviceId,
    to,
    id: crypto.randomUUID(),
    payload: encodePayload(payloadObj),
  });
}

function sendResponse(to, requestId, ok, body) {
  const resp = ok
    ? { id: requestId, ok: true, result: body || {} }
    : { id: requestId, ok: false, error: body || { code: 'internal', message: 'error' } };
  sendToPeer(to, resp);
}

function sendEvent(to, event, data) {
  sendToPeer(to, { event, data: data || {} });
}

function handlePairRequest(from, envelopeId, req) {
  const requested = (req.requested_level || 'view').toLowerCase();
  const level = ['view', 'control', 'full'].includes(requested) ? requested : 'view';
  const botName = req.bot_name || 'Unknown bot';
  const botPub = req.bot_pub || '';
  logEvent('pair_request', { from, botName, requested: level });

  if (autoAccept) {
    state.pairedBots.set(from, { name: botName, level, pub: botPub, pairedAt: Date.now() });
    sendToPeer(from, { ok: true, granted_level: level, device_name: deviceName });
    logEvent('pair_accepted', { from, level, botName });
  } else {
    sendToPeer(from, { ok: false, reason: 'denied by mock device' });
    logEvent('pair_denied', { from, botName });
  }
}

const LEVEL_RANK = { view: 1, control: 2, full: 3 };
const CMD_LEVEL = {
  status: 'view', screenshot: 'view', ui_tree: 'view', notifications: 'view',
  tap: 'control', long_press: 'control', swipe: 'control', type: 'control', key: 'control', open_app: 'control',
  request_control: 'control', release_control: 'control',
  install_apk: 'full', shell: 'full', files: 'full', settings: 'full', relay_move: 'full',
};

function hasLevel(botLevel, needed) {
  return (LEVEL_RANK[botLevel] || 0) >= (LEVEL_RANK[needed] || 0);
}

function ensurePaired(from) {
  let bot = state.pairedBots.get(from);
  if (!bot) {
    // DEV MODE: implicit pairing at full level for easy testing; log clearly.
    bot = { name: `dev-${from.slice(0, 8)}`, level: 'full', pub: '', pairedAt: Date.now(), implicit: true };
    state.pairedBots.set(from, bot);
    logEvent('implicit_pair', { from, level: bot.level });
  }
  return bot;
}

function isControlCommand(cmd) {
  return (LEVEL_RANK[CMD_LEVEL[cmd] || 'view'] || 0) >= LEVEL_RANK.control && cmd !== 'request_control' && cmd !== 'release_control';
}

function touchControl() {
  state.lastActivity = Date.now();
  clearControlTimer();
  state.controlTimer = setTimeout(() => {
    if (state.controller) {
      const old = state.controller;
      state.controller = null;
      logEvent('control_timeout', { peer: old });
      sendEvent(old, 'control_changed', { controller: null, reason: 'idle_timeout' });
      promoteQueue();
    }
  }, 120000);
}

function clearControlTimer() {
  if (state.controlTimer) { clearTimeout(state.controlTimer); state.controlTimer = null; }
}

function promoteQueue() {
  while (state.queue.length > 0) {
    const next = state.queue.shift();
    if (state.pairedBots.has(next) && ws && ws.readyState === WebSocket.OPEN) {
      state.controller = next;
      touchControl();
      sendEvent(next, 'control_changed', { controller: next, reason: 'granted' });
      state.queue.forEach((peer, idx) => sendEvent(peer, 'queue_position', { position: idx + 1 }));
      logEvent('control_granted', { peer: next });
      return;
    }
  }
}

function handleCommand(from, envelopeId, req) {
  const cmd = req.cmd;
  const cmdArgs = (req.args && typeof req.args === 'object') ? req.args : {};
  const bot = ensurePaired(from);
  const needed = CMD_LEVEL[cmd];
  logEvent('cmd', { from, cmd, args: cmdArgs });

  if (!needed) {
    return sendResponse(from, req.id, false, { code: 'unknown_cmd', message: `unknown command: ${cmd}` });
  }
  if (!hasLevel(bot.level, needed)) {
    return sendResponse(from, req.id, false, { code: 'permission_denied', message: `needs ${needed}` });
  }

  if (isControlCommand(cmd)) {
    if (state.controller && state.controller !== from) {
      const pos = state.queue.indexOf(from);
      if (pos === -1) { state.queue.push(from); sendEvent(from, 'queue_position', { position: state.queue.length }); }
      else { sendEvent(from, 'queue_position', { position: pos + 1 }); }
      return sendResponse(from, req.id, false, { code: 'not_controller', message: 'another bot holds control; queued' });
    }
    if (!state.controller) {
      state.controller = from;
      sendEvent(from, 'control_changed', { controller: from, reason: 'implicit' });
    }
    touchControl();
  }

  try {
    const result = executeCommand(from, cmd, cmdArgs);
    sendResponse(from, req.id, true, result);
  } catch (e) {
    if (e && e.pbCode) return sendResponse(from, req.id, false, { code: e.pbCode, message: e.message });
    logEvent('cmd_error', { cmd, error: String(e && e.message || e) });
    sendResponse(from, req.id, false, { code: 'internal', message: String(e && e.message || e) });
  }
}

function pbError(code, message) {
  const err = new Error(message);
  err.pbCode = code;
  return err;
}


function executeCommand(from, cmd, args) {
  switch (cmd) {
    case 'status':
      return {
        device: { name: deviceName, model: 'Mock Pixel 8', android: '14', mock: true },
        battery: { level: 87, charging: false },
        screen_on: state.screenOn,
        controller: state.controller,
        queue: state.queue.map((p, i) => ({ peer: p, position: i + 1 })),
        shizuku: { available: false, reason: 'mock device' },
      };
    case 'screenshot':
      return makeScreenshot(args);
    case 'ui_tree':
      return { root: makeUiTree(args) };
    case 'notifications':
      return { items: state.notifications.slice(-(args.since ? 100 : 20)) };
    case 'tap':
      if (args.node_id == null && (typeof args.x !== 'number' || typeof args.y !== 'number')) throw pbError('bad_args', 'tap needs x,y or node_id');
      logEvent('tap', args);
      return {};
    case 'long_press':
      if (typeof args.x !== 'number' || typeof args.y !== 'number') throw pbError('bad_args', 'long_press needs x,y');
      logEvent('long_press', args);
      return {};
    case 'swipe':
      for (const k of ['x1','y1','x2','y2']) if (typeof args[k] !== 'number') throw pbError('bad_args', 'swipe needs x1,y1,x2,y2');
      logEvent('swipe', args);
      return {};
    case 'type':
      if (typeof args.text !== 'string') throw pbError('bad_args', 'type needs text');
      logEvent('type', { text: args.text, node_id: args.node_id || null });
      return {};
    case 'key': {
      const allowed = ['back','home','recents','notifications','quick_settings','lock','enter','power_dialog'];
      if (!allowed.includes(args.key)) throw pbError('bad_args', `key must be one of ${allowed.join(', ')}`);
      logEvent('key', { key: args.key });
      return {};
    }
    case 'open_app':
      if (!args.package && !args.label) throw pbError('bad_args', 'open_app needs package or label');
      logEvent('open_app', args);
      return {};
    case 'request_control':
      if (state.controller === from) return { granted: true };
      if (!state.controller) {
        state.controller = from;
        touchControl();
        sendEvent(from, 'control_changed', { controller: from, reason: 'granted' });
        return { granted: true };
      }
      if (!state.queue.includes(from)) {
        state.queue.push(from);
        sendEvent(from, 'queue_position', { position: state.queue.length });
      } else {
        sendEvent(from, 'queue_position', { position: state.queue.indexOf(from) + 1 });
      }
      return { granted: false };
    case 'release_control':
      if (state.controller === from) {
        state.controller = null;
        clearControlTimer();
        sendEvent(from, 'control_changed', { controller: null, reason: 'released' });
        promoteQueue();
      } else {
        const idx = state.queue.indexOf(from);
        if (idx >= 0) state.queue.splice(idx, 1);
        state.queue.forEach((peer, i) => sendEvent(peer, 'queue_position', { position: i + 1 }));
      }
      return {};
    case 'install_apk':
      if (!args.url && !args.data_b64) throw pbError('bad_args', 'install_apk needs url or data_b64+name');
      logEvent('install_apk', { url: args.url || null, name: args.name || null });
      return { package: 'com.example.mock.installed' };
    case 'shell':
      if (typeof args.command !== 'string') throw pbError('bad_args', 'shell needs command');
      logEvent('shell', { command: args.command });
      return { exit: 0, stdout: `mock: ${args.command}\n`, stderr: '' };
    case 'files':
      return handleFiles(args);
    case 'settings':
      return handleSettings(args);
    case 'relay_move':
      if (!args.to) throw pbError('bad_args', 'relay_move needs to');
      logEvent('relay_move', args);
      return { new_url: args.to === 'this' ? args.relay || 'ws://127.0.0.1:8787' : String(args.to) };
    default:
      throw pbError('unknown_cmd', `unknown command: ${cmd}`);
  }
}

function handleFiles(args) {
  const op = args.op;
  const p = args.path;
  if (!op || !p) throw pbError('bad_args', 'files needs op and path');
  if (!['list','read','write','delete'].includes(op)) throw pbError('bad_args', 'files op must be list/read/write/delete');
  logEvent('files', { op, path: p });
  switch (op) {
    case 'list':
      return { entries: [{ name: 'mock.txt', type: 'file', size: 12 }] };
    case 'read':
      return { path: p, data_b64: Buffer.from('mock content', 'utf8').toString('base64') };
    case 'write':
      return { path: p, written: true };
    case 'delete':
      return { path: p, deleted: true };
    default:
      throw pbError('bad_args', 'bad files op');
  }
}

function handleSettings(args) {
  if (!args.namespace || !args.key) throw pbError('bad_args', 'settings needs namespace and key');
  if (!['system','secure','global'].includes(args.namespace)) throw pbError('bad_args', 'bad namespace');
  logEvent('settings', args);
  if (args.value !== undefined) return { value: args.value };
  return { value: '0' };
}

function makeScreenshot(args) {
  const maxWidth = Math.min(Math.max(Number(args.max_width) || 320, 64), 1080);
  const height = Math.round(maxWidth * (16 / 9));
  const format = args.format === 'jpeg' ? 'jpeg' : 'png';
  const png = makePng(maxWidth, height);
  // For jpeg request, still return a PNG but honor mime? Better return real format; we generate PNG only.
  // Keep simple: always return PNG, set mime accordingly if png requested; for jpeg, return PNG with png mime in dev mode? No, be honest.
  const mime = 'image/png';
  return { mime, width: maxWidth, height, data_b64: png.toString('base64') };
}

function makeUiTree(args) {
  const maxDepth = args.max_depth != null ? Number(args.max_depth) : 5;
  const root = {
    id: 'root',
    cls: 'android.widget.FrameLayout',
    text: null,
    desc: 'Mock Home Screen',
    res_id: 'mock:id/root',
    bounds: [0, 0, 1080, 1920],
    clickable: false,
    focusable: false,
    editable: false,
    children: [],
  };
  if (maxDepth <= 0) return root;
  root.children.push({
    id: 'status_bar',
    cls: 'android.widget.LinearLayout',
    text: null,
    desc: 'Status bar',
    res_id: 'mock:id/status_bar',
    bounds: [0, 0, 1080, 96],
    clickable: false,
    focusable: false,
    editable: false,
    children: maxDepth > 1 ? [
      { id: 'clock', cls: 'android.widget.TextView', text: '12:00', desc: null, res_id: 'mock:id/clock', bounds: [40, 24, 180, 72], clickable: false, focusable: false, editable: false, children: [] },
    ] : [],
  });
  root.children.push({
    id: 'home_btn',
    cls: 'android.widget.Button',
    text: 'Home',
    desc: 'Home button',
    res_id: 'mock:id/home',
    bounds: [440, 1700, 640, 1850],
    clickable: true,
    focusable: true,
    editable: false,
    children: [],
  });
  root.children.push({
    id: 'search_input',
    cls: 'android.widget.EditText',
    text: '',
    desc: 'Search',
    res_id: 'mock:id/search',
    bounds: [60, 200, 1020, 320],
    clickable: true,
    focusable: true,
    editable: true,
    children: [],
  });
  return root;
}

// Minimal PNG generator (RGB, no compression dependencies). Generates a valid PNG.
function makePng(width, height) {
  // Build raw RGBA rows with filter byte 0
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y++) {
    const rowOff = y * (width * 4 + 1);
    raw[rowOff] = 0; // filter none
    for (let x = 0; x < width; x++) {
      const off = rowOff + 1 + x * 4;
      const checker = ((Math.floor(x / 16) + Math.floor(y / 16)) % 2) === 0;
      raw[off] = checker ? 66 : 46;       // R
      raw[off + 1] = checker ? 133 : 110;  // G
      raw[off + 2] = checker ? 244 : 170;  // B
      raw[off + 3] = 255;                  // A
    }
  }
  const compressed = zlibDeflate(raw);
  const sig = Buffer.from([137,80,78,71,13,10,26,10]);
  const ihdr = pngChunk('IHDR', Buffer.from([
    ...u32(width), ...u32(height), 8, 6, 0, 0, 0, // 8-bit RGBA
  ]));
  const idat = pngChunk('IDAT', compressed);
  const iend = pngChunk('IEND', Buffer.alloc(0));
  return Buffer.concat([sig, ihdr, idat, iend]);
}

function pngChunk(type, data) {
  const len = Buffer.from(u32(data.length));
  const t = Buffer.from(type, 'ascii');
  const crc = crc32(Buffer.concat([t, data]));
  return Buffer.concat([len, t, data, Buffer.from(u32(crc >>> 0))]);
}

function u32(n) { return [(n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255]; }

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    c ^= buf[i];
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
  }
  return (c ^ 0xffffffff) >>> 0;
}

function zlibDeflate(data) { return zlib.deflateSync(data); }


function logEvent(kind, data) {
  const entry = { ts: new Date().toISOString(), kind, ...data };
  const line = JSON.stringify(entry);
  if (args.verbose !== false) console.log(line);
  if (logFile) {
    try { fs.mkdirSync(path.dirname(logFile), { recursive: true }); fs.appendFileSync(logFile, line + '\n'); } catch {}
  }
}

function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--help' || a === '-h') out.help = true;
    else if (a === '--relay') out.relay = argv[++i];
    else if (a === '--name') out.name = argv[++i];
    else if (a === '--auto-accept') out.autoAccept = true;
    else if (a === '--no-auto-accept') out.autoAccept = false;
    else if (a === '--plaintext') out.plaintext = true;
    else if (a === '--crypto') out.crypto = true;
    else if (a === '--log') out.log = argv[++i];
    else if (a === '--once') out.once = true;
    else if (a === '--quiet') out.verbose = false;
    else out._.push(a);
  }
  return out;
}

process.on('SIGINT', () => { closing = true; try { ws && ws.close(); } catch {} process.exit(0); });
process.on('SIGTERM', () => { closing = true; try { ws && ws.close(); } catch {} process.exit(0); });

