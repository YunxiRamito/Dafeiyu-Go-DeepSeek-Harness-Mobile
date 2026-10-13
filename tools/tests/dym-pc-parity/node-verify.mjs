import path from 'node:path';
import fs from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
const [base, bundle, persistenceFile] = process.argv.slice(2);
const { listArtifacts, readArtifact, readCredentials } = await import(pathToFileURL(persistenceFile).href);
const home = path.resolve(base, 'target-home');
const listed = await listArtifacts(bundle, path.join(home, 'sessions'), 'zstd');
if (listed.items.length !== 1) throw new Error('Duplicate history or missing first-party record');
const artifact = await readArtifact(bundle, path.join(home, 'sessions'), listed.items[0].header.id, 'zstd');
const messages = artifact.events.filter(item => ['user/message', 'assistant/message'].includes(item.type));
if (messages.length !== 2 || messages[0].data.content[0].text !== 'fixture prompt' || messages[1].data.message.content[0].text !== 'fixture answer') throw new Error('Compatible body lost');
if (artifact.events.some(item => !['user/message', 'assistant/message', 'session/title', 'android/history-source', 'turn/start', 'turn/end', 'step/start', 'step/end'].includes(item.type))) throw new Error('Restored event may execute');
if (artifact.events.filter(item => item.type === 'turn/start').length !== artifact.events.filter(item => item.type === 'turn/end').length) throw new Error('Pending imported task');
const credentials = await readCredentials(bundle, home);
if (credentials.refs.DEEPSEEK_API_KEY !== 'fixture-official-key'
  || (credentials.refs.FIXTURE_PROVIDER_KEY ?? credentials.refs.DAFEIYU_PROVIDER_OM_FIXTURE) !== 'fixture-other-key') throw new Error('First-party credential restore missing');
await fs.writeFile(path.resolve(base, 'native-readback.json'), JSON.stringify({ passed: 4, firstPartyPersistence: true, duplicateSessions: false, oldTaskReplay: false, paidModelCalls: 0 }));
process.stdout.write('PASS actual first-party persistent body, no duplicated Session, completed turn boundaries, genuine home credential readback\n');
