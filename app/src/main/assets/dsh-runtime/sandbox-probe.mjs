// Android execution probe over the same pinned DSH Linux sandbox contract.
// It writes only to a freshly created application-private probe directory.
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import { installAndroidSandbox } from './android-sandbox.mjs';

const bundle = process.argv[2];
const parent = process.argv[3];
const nativeDirectory = process.argv[4];
if (!bundle?.startsWith('/dafeiyu-dsh') || !parent?.startsWith('/dafeiyu-sandbox-probe') ||
    nativeDirectory && !nativeDirectory.startsWith('/data/app/')) throw new Error('invalid controlled probe mount');
const root = mkdtempSync(join(parent, 'case-'));
const workspace = join(root, 'workspace');
const outside = join(root, 'outside');
mkdirSync(workspace); mkdirSync(outside);
const report = { schema: 1, dshVersion: '0.2.1-alpha.1', nodeVersion: process.version, platform: process.platform,
  architecture: process.arch, kernel: null, sharedAndroidKernel: true, runners: [], completeSandboxVerified: false };

const child = String.raw`
const fs=require('node:fs'),{spawnSync}=require('node:child_process');
const paths=JSON.parse(process.argv[1]);const cases=[];
const denialCodes=new Set(['EACCES','EPERM','EROFS','EXDEV']);
const attempt=(name,allowed,operation)=>{let succeeded=false,error=null;try{operation();succeeded=true;}catch(e){error=e.code??e.name;}cases.push({name,expectedAllowed:allowed,succeeded,error,pass:allowed?succeeded:!succeeded&&denialCodes.has(error)});};
const inside=paths.workspace+'/existing.txt',outside=paths.outside+'/existing.txt';
const insideAllowed=paths.mode!=='read-only',outsideAllowed=paths.mode==='danger-full-access';
attempt('read-workspace',true,()=>fs.readFileSync(inside));
attempt('read-outside',true,()=>fs.readFileSync(outside));
attempt('create-workspace',insideAllowed,()=>fs.writeFileSync(paths.workspace+'/created.txt','inside'));
attempt('create-outside',outsideAllowed,()=>fs.writeFileSync(paths.outside+'/created.txt','outside'));
attempt('truncate-workspace',insideAllowed,()=>fs.truncateSync(inside,1));
attempt('truncate-outside',outsideAllowed,()=>fs.truncateSync(outside,1));
attempt('write-through-symlink-outside',outsideAllowed,()=>fs.writeFileSync(paths.workspace+'/outside-link','link'));
attempt('hardlink-outside-into-workspace',outsideAllowed,()=>fs.linkSync(outside,paths.workspace+'/hard-link'));
const nestedCode="const fs=require('node:fs');fs.readFileSync(process.argv[2]);let wrote=false,error=null;try{fs.writeFileSync(process.argv[1],'nested');wrote=true;}catch(e){error=e.code??e.name;}console.log(JSON.stringify({started:true,readSucceeded:true,wrote,error}));";
const nested=spawnSync(process.execPath,['-e',nestedCode,paths.outside+'/child-created.txt',outside],{encoding:'utf8',stdio:['ignore','pipe','pipe'],timeout:5000,maxBuffer:65536});
let nestedReport=null;try{nestedReport=JSON.parse(nested.stdout?.trim()??'');}catch{}
const nestedReady=nested.status===0&&nestedReport?.started===true&&nestedReport?.readSucceeded===true;
const nestedSucceeded=nestedReport?.wrote===true&&fs.existsSync(paths.outside+'/child-created.txt');
const nestedBlocked=nestedReport?.wrote===false&&denialCodes.has(nestedReport?.error)&&!fs.existsSync(paths.outside+'/child-created.txt');
cases.push({name:'child-process-inherits-policy',expectedAllowed:outsideAllowed,succeeded:nestedSucceeded,error:nested.error?.code??nestedReport?.error??null,childStarted:nestedReady,pass:nestedReady&&(outsideAllowed?nestedSucceeded:nestedBlocked)});
console.log(JSON.stringify({cases,allPassed:cases.every(c=>c.pass)}));
`;

function bwrapArgs(mode) {
  const args = ['--ro-bind', '/', '/', '--dev', '/dev', '--unshare-pid', '--proc', '/proc', '--die-with-parent'];
  if (mode === 'workspace-write') args.push('--tmpfs', '/tmp', '--bind', workspace, workspace);
  return args;
}

function freshFiles() {
  for (const directory of [workspace, outside]) {
    rmSync(directory, { recursive: true, force: true });
    mkdirSync(directory);
    writeFileSync(join(directory, 'existing.txt'), 'original-file');
  }
  symlinkSync(join(outside, 'existing.txt'), join(workspace, 'outside-link'));
}

function probeBinary(binary, args) {
  const result = spawnSync(binary, args, { encoding: 'utf8', timeout: 5000, maxBuffer: 65536, stdio: ['ignore', 'pipe', 'pipe'] });
  return { exit: result.status, signal: result.signal, error: result.error?.code ?? null,
    stdout: result.stdout?.slice(0, 4096) ?? '', stderr: result.stderr?.slice(0, 4096) ?? '' };
}

function matrix(binary, args, mode) {
  freshFiles();
  const result = spawnSync(binary, [...args, process.execPath, '-e', child, JSON.stringify({ workspace, outside, mode })],
    { encoding: 'utf8', timeout: 15000, maxBuffer: 65536, stdio: ['ignore', 'pipe', 'pipe'] });
  let parsed = null;
  try { parsed = JSON.parse(result.stdout?.trim() ?? ''); } catch { /* real launch failures remain failures */ }
  return { mode, exit: result.status, signal: result.signal, error: result.error?.code ?? null,
    stderr: result.stderr?.slice(0, 4096) ?? '', cases: parsed?.cases ?? [], allPassed: result.status === 0 && parsed?.allPassed === true };
}

async function firstPartyMatrix(provider, mode) {
  freshFiles();
  const confined = await provider.confine([process.execPath, '-e', child, JSON.stringify({ workspace, outside, mode })],
    { mode, workspaceRoot: workspace });
  const result = spawnSync(confined.argv[0], confined.argv.slice(1),
    { encoding: 'utf8', timeout: 15000, maxBuffer: 65536, stdio: ['ignore', 'pipe', 'pipe'] });
  let parsed = null;
  try { parsed = JSON.parse(result.stdout?.trim() ?? ''); } catch { /* launch failure stays failed */ }
  return { mode, enforcement: confined.enforcement, exit: result.status, signal: result.signal, error: result.error?.code ?? null,
    stderr: result.stderr?.slice(0, 4096) ?? '', cases: parsed?.cases ?? [],
    allPassed: result.status === 0 && confined.enforcement === 'full' && parsed?.allPassed === true };
}

try {
  const uname = probeBinary('/usr/bin/uname', ['-sr']);
  report.kernel = uname.exit === 0 ? uname.stdout.trim() : null;
  const bwrap = probeBinary('bwrap', [...bwrapArgs('read-only'), '--', '/usr/bin/true']);
  const bwrapRunner = { id: 'bubblewrap', verdict: bwrap.exit === 0 ? 'full' : 'unusable', probe: bwrap, modes: [] };
  if (bwrap.exit === 0) for (const mode of ['read-only', 'workspace-write']) {
    bwrapRunner.modes.push(matrix('bwrap', [...bwrapArgs(mode), '--'], mode));
  }
  report.runners.push(bwrapRunner);

  let landlockRunner;
  try {
    const api = await import(pathToFileURL(join(bundle, 'node_modules/@deepseek-ai/node-addon-system/lib/index.js')).href);
    const launcher = join(bundle, `node_modules/@deepseek-ai/node-addon-system-linux-${process.arch}/bin/landlock-run`);
    const result = probeBinary(launcher, ['--probe']);
    landlockRunner = { id: 'landlock', verdict: api.probe(launcher, { timeoutMs: 5000 }), probe: result, modes: [] };
    if (landlockRunner.verdict !== 'unusable') for (const mode of ['read-only', 'workspace-write']) {
      const writable = mode === 'workspace-write' ? ['/dev/null', '/tmp', workspace] : ['/dev/null'];
      landlockRunner.modes.push(matrix(launcher, [...api.grantArgs({ readOnly: ['/'], readWrite: writable }), '--'], mode));
    }
    // PRoot's exec loader and explicit guest bindings lie outside the translated
    // rootfs. Test them as additional read-only roots; do not reinterpret a
    // permission failure as an enforced denial inside an unstarted child.
    if (landlockRunner.verdict !== 'unusable') {
      const readOnly = ['/', bundle, parent, '/system', '/apex', '/proc', '/sys', '/dev', nativeDirectory].filter(value => value && existsSync(value));
      landlockRunner.adjustedGrants = readOnly;
      landlockRunner.adjustedModes = [];
      for (const mode of ['read-only', 'workspace-write']) {
        const writable = mode === 'workspace-write' ? ['/dev/null', '/tmp', workspace] : ['/dev/null'];
        landlockRunner.adjustedModes.push(matrix(launcher, [...api.grantArgs({ readOnly, readWrite: writable }), '--'], mode));
      }
    }
  } catch (failure) {
    landlockRunner = { id: 'landlock', verdict: 'unusable', error: String(failure?.code ?? failure?.name ?? 'load-failed'), modes: [] };
  }
  report.runners.push(landlockRunner);
  if (nativeDirectory && existsSync(nativeDirectory)) {
    let context;
    let restore;
    try {
      restore = await installAndroidSandbox(bundle, nativeDirectory, ['/dafeiyu-dsh', '/dafeiyu-sandbox-probe']);
      const require = createRequire(join(bundle, 'package.json'));
      const { Context } = await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href);
      const { LocalSandboxProvider } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-sandbox-local')).href);
      context = new Context();
      const provider = new LocalSandboxProvider(context, LocalSandboxProvider.Config({}));
      report.firstPartyModes = [];
      for (const mode of ['read-only', 'workspace-write']) report.firstPartyModes.push(await firstPartyMatrix(provider, mode));
    } catch (failure) {
      report.firstPartyFailure = String(failure?.code ?? failure?.name ?? 'failed');
    } finally { restore?.(); await context?.fiber.dispose(); }
  }
  const full = matrix(process.execPath, ['-e', 'const r=require("node:child_process").spawnSync(process.argv[1],process.argv.slice(2),{stdio:"inherit"});if(r.error)throw r.error;process.exit(r.status??1);'], 'danger-full-access');
  report.unrestrictedBaseline = full;
  const unavailable = full.cases.filter(value => !value.pass);
  report.androidFilesystemLimitations = unavailable.map(value => ({ name: value.name, error: value.error }));
  const baselineUsable = full.exit === 0 && unavailable.every(value => value.name === 'hardlink-outside-into-workspace' && ['EACCES', 'EPERM', 'EXDEV'].includes(value.error));
  for (const value of report.firstPartyModes ?? []) for (const item of value.cases) {
    item.causalProof = full.cases.some(baseline => baseline.name === item.name && baseline.pass) && item.pass;
    if (!item.causalProof && unavailable.some(baseline => baseline.name === item.name)) item.baselineUnavailable = true;
  }
  report.completeSandboxVerified = baselineUsable && report.firstPartyModes?.length === 2 && report.firstPartyModes.every(value => value.allPassed);
  report.requiresFurtherUserDecision = !report.completeSandboxVerified;
  report.note = report.completeSandboxVerified
    ? 'First-party file-effect modes passed in this actual Android kernel. Baseline-unavailable Android hardlink operations are recorded separately and are not counted as proof of Landlock denial.'
    : 'Do not advertise PC-equivalent sandbox support. Preserve evidence and ask before adding a full Linux VM.';
  console.log(JSON.stringify(report));
} finally {
  rmSync(root, { recursive: true, force: true });
}
process.stdin.resume(); // Host releases the verified PRoot process after reading the complete report.
