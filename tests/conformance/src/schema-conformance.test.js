import { describe, it, expect } from 'vitest';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, '../../..');
const schemaDir = path.join(repoRoot, 'protocol', 'schema');
const examplesDir = path.join(repoRoot, 'protocol', 'examples');

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function listJsonFiles(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir)
    .filter((f) => f.endsWith('.json'))
    .map((f) => path.join(dir, f))
    .sort();
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
  const schemaFiles = walkJsonFiles(schemaDir);
  const schemas = new Map();
  for (const file of schemaFiles) {
    const schema = readJson(file);
    const rel = path.relative(schemaDir, file).replace(/\\/g, '/');
    schemas.set(rel, schema);
    if (schema.$id) ajv.addSchema(schema, schema.$id);
  }
  return schemas;
}

function schemaForExample(exampleName) {
  // command.<name>.args.valid.json -> commands/<name>.schema.json#args
  // command.<name>.result.invalid.json -> commands/<name>.schema.json#result
  const commandMatch = exampleName.match(/^command\.([^.]+)\.(args|result)\.(valid|invalid(?:\.\d+)?)$/);
  if (commandMatch) {
    const [, cmd, part] = commandMatch;
    return { schemaRel: `commands/${cmd}.schema.json`, pointer: `#/properties/${part}` };
  }
  // envelope.<type>.* -> envelope.schema.json (whole envelope validates against oneOf)
  const envelopeMatch = exampleName.match(/^envelope\.([^.]+)\.(valid|invalid(?:\.\d+)?)$/);
  if (envelopeMatch) {
    return { schemaRel: 'envelope.schema.json', pointer: null };
  }
  // <name>.valid/invalid -> <name>.schema.json
  const baseMatch = exampleName.match(/^(.+)\.(valid|invalid(?:\.\d+)?)$/);
  if (baseMatch) {
    return { schemaRel: `${baseMatch[1]}.schema.json`, pointer: null };
  }
  return null;
}

describe('protocol schema conformance', () => {
  const ajv = makeAjv();
  const schemas = loadSchemas(ajv);

  it('has schemas and examples', () => {
    expect(schemas.size).toBeGreaterThan(0);
    expect(listJsonFiles(examplesDir).length).toBeGreaterThan(0);
  });

  const exampleFiles = listJsonFiles(examplesDir);
  for (const file of exampleFiles) {
    const base = path.basename(file, '.json');
    const mapping = schemaForExample(base);
    it(`example ${path.basename(file)} maps to a schema`, () => {
      expect(mapping).not.toBeNull();
      expect(schemas.has(mapping.schemaRel)).toBe(true);
    });

    it(`validates ${path.basename(file)}`, () => {
      expect(mapping).not.toBeNull();
      const schema = schemas.get(mapping.schemaRel);
      const targetSchema = mapping.pointer
        ? { $ref: `${schema.$id}${mapping.pointer}` }
        : schema;
      const validate = ajv.compile(targetSchema);
      const data = readJson(file);
      const ok = validate(data);
      const isValidExample = /\.valid$/.test(base);
      if (isValidExample) {
        expect(ok, JSON.stringify(validate.errors, null, 2)).toBe(true);
      } else {
        expect(ok).toBe(false);
      }
    });
  }
});
