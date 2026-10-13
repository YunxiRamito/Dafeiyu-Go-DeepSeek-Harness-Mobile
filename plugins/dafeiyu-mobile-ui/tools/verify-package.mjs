// Read-only verification of the locally generated npm pack; never extracts or executes a member.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';

const packageRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const archivePath = resolve(process.argv[2] || '../Temp_Android/Dist/dafeiyu-mobile-ui-0.1.6.tgz');
const reportPath = resolve(process.argv[3] || '../Temp_Android/qa/css-theme-0.1.6/package-receipt.json');
const manifest = JSON.parse(await readFile(resolve(packageRoot, 'package.json'), 'utf8'));
const archive = await readFile(archivePath);
assert.ok(archive.length <= 512 * 1024, 'The local mobile package must remain bounded');
const tar = gunzipSync(archive, { maxOutputLength: 2 * 1024 * 1024 });
const expected = ['LICENSE', 'README.md', 'THIRD_PARTY_NOTICES.md', 'client/client.js', 'client/context.js',
  'client/host-footer.js', 'client/index.js', 'client/startup.js', 'client/styles.css',
  'cordis.patch.yml', 'lib/index.js', 'package.json'];
const members = [];
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const text = bytes => bytes.toString('utf8').replace(/\0.*$/s, '');
let position = 0;
while (position + 512 <= tar.length) {
  const header = tar.subarray(position, position + 512);
  if (header.every(byte => byte === 0)) {
    assert.ok(tar.subarray(position).every(byte => byte === 0), 'Only zero padding may follow the archive end');
    position = tar.length; break;
  }
  const headerSum = [...header].reduce((sum, byte, index) => sum + (index >= 148 && index < 156 ? 32 : byte), 0);
  assert.equal(headerSum, parseInt(text(header.subarray(148, 156)).trim(), 8), 'Tar checksum');
  const prefix = text(header.subarray(345, 500));
  const name = `${prefix ? `${prefix}/` : ''}${text(header.subarray(0, 100))}`;
  const type = header[156] === 0 ? '0' : String.fromCharCode(header[156]);
  assert.equal(type, '0', `Only regular members are allowed: ${name}`);
  assert.ok(name.startsWith('package/') && expected.includes(name.slice(8)), `Unexpected member: ${name}`);
  assert.ok(!members.some(member => member.path === name.slice(8)), `Duplicate member: ${name}`);
  const size = parseInt(text(header.subarray(124, 136)).trim(), 8);
  assert.ok(Number.isSafeInteger(size) && size >= 0 && size <= 512 * 1024);
  const payload = tar.subarray(position + 512, position + 512 + size);
  assert.equal(payload.length, size);
  const source = await readFile(resolve(packageRoot, name.slice(8)));
  assert.ok(payload.equals(source), `Packed bytes differ from canonical source: ${name}`);
  if (name === 'package/package.json') {
    const packed = JSON.parse(payload.toString('utf8'));
    assert.equal(packed.name, manifest.name); assert.equal(packed.version, manifest.version);
  }
  members.push({ path: name.slice(8), type, bytes: size, sha256: digest(payload), canonicalBytesEqual: true });
  position += 512 + Math.ceil(size / 512) * 512;
}
assert.equal(position, tar.length);
assert.deepEqual(members.map(member => member.path).sort(), expected.sort());
const report = { schema: 1, package: manifest.name, version: manifest.version,
  archive: archivePath, bytes: archive.length, sha256: digest(archive), regularMembers: members.length,
  allCanonicalBytesEqual: true, scriptsExecuted: false, runtimeDependencies: Object.keys(manifest.dependencies || {}).length,
  scope: 'Generated archive and exact canonical payload bytes; no SDK installation or live browser claim', members };
await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
await writeFile(`${archivePath}.sha256`, `${report.sha256}  ${archivePath.split(/[\\/]/).at(-1)}\n`, 'utf8');
process.stdout.write(`${manifest.name}@${manifest.version}: ${report.regularMembers} regular members, ` +
  `${report.bytes} bytes, SHA-256 ${report.sha256}; all canonical bytes equal\n`);
