import { describe, it, expect } from 'vitest';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, '../../..');
const schemaDir = path.join(repoRoot, 'protocol', 'schema');

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function walkJsonFiles(dir) {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walkJsonFiles(full));
    else if (entry.name.endsWith('.json')) out.push(full);
  }
  return out.sort();
}

function makeAjv() {
  const ajv = new Ajv2020({ allErrors: true, strict: false });
  addFormats(ajv);
  return ajv;
}

function loadSchemas(ajv) {
  const schemas = [];
  for (const file of walkJsonFiles(schemaDir)) {
    const schema = readJson(file);
    const rel = path.relative(schemaDir, file).replace(/\\/g, '/');
    const id = schema.$id || `file://${rel}`;
    try { ajv.addSchema(schema, id); } catch { /* ignore duplicate */ }
    schemas.push({ rel, id, schema });
  }
  return schemas;
}

function sampleDirs() {
  // Documented hook: core and mcp tests write JSON samples here.
  // Also check common module build locations.
  return [
    path.join(repoRoot, 'build', 'protocol-samples'),
    path.join(repoRoot, 'core', 'build', 'protocol-samples'),
    path.join(repoRoot, 'mcp', 'build', 'protocol-samples'),
    path.join(repoRoot, 'relay', 'build', 'protocol-samples'),
  ];
}

function collectSamples() {
  const files = [];
  for (const dir of sampleDirs()) {
    for (const file of walkJsonFiles(dir)) files.push(file);
  }
  return [...new Set(files)].sort();
}

function pickSchemas(sample, schemas) {
  const hint = sample.$schema || sample.schema;
  if (typeof hint === 'string' && hint.length > 0) {
    const byId = schemas.find((s) => s.id === hint || s.rel === hint || s.rel.endsWith(hint) || s.id.endsWith(hint));
    if (byId) return [byId];
  }
  // Heuristic: envelope has v+type, pairing offer has relay+code+exp, request has cmd+args, etc.
  const keys = sample && typeof sample === 'object' ? Object.keys(sample) : [];
  const has = (...ks) => ks.every((k) => keys.includes(k));
  if (has('v', 'type') && keys.includes('from')) return schemas.filter((s) => s.rel === 'envelope.schema.json');
  if (has('v', 'relay', 'device_id', 'secret', 'code', 'exp')) return schemas.filter((s) => s.rel === 'pairing-offer.schema.json');
  if (has('bot_name', 'bot_pub', 'requested_level')) return schemas.filter((s) => s.rel === 'pair-request.schema.json');
  if (has('ok', 'granted_level', 'device_name') || (has('ok', 'reason') && sample.ok === false)) return schemas.filter((s) => s.rel === 'pair-result.schema.json');
  if (has('id', 'cmd', 'args')) return schemas.filter((s) => s.rel === 'request.schema.json');
  if (has('id', 'ok') && (keys.includes('result') || keys.includes('error'))) return schemas.filter((s) => s.rel === 'response.schema.json');
  if (has('event', 'data')) return schemas.filter((s) => s.rel === 'event.schema.json');
  return schemas;
}

describe('protocol sample capture hook', () => {
  const ajv = makeAjv();
  const schemas = loadSchemas(ajv);
  const samples = collectSamples();

  it('reports sample discovery (no samples is ok)', () => {
    // This test documents the hook. It passes when no samples are present.
    expect(Array.isArray(samples)).toBe(true);
  });

  for (const file of samples) {
    it(`validates captured sample ${path.relative(repoRoot, file).replace(/\\/g, '/')}`, () => {
      const sample = readJson(file);
      const candidates = pickSchemas(sample, schemas);
      const errors = [];
      for (const c of candidates) {
        const validate = ajv.getSchema(c.id) || ajv.compile(c.schema);
        const ok = validate(sample);
        if (ok) return;
        errors.push({ schema: c.rel, errors: validate.errors });
      }
      expect.fail(`Sample did not match any candidate schema:\n${JSON.stringify(errors.slice(0, 3), null, 2)}`);
    });
  }
});
