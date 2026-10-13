// Local development preview. No dependencies, proxying or DSH process restart.
import http from 'node:http';
import fs from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const port = 8791;
const origin = `http://127.0.0.1:${port}`;
const dshOrigin = 'http://127.0.0.1:8787';
const previewFile = fileURLToPath(new URL('../preview/index.html', import.meta.url));
const entryFile = 'G:/DeepSeek DSH/logs/last-url.txt';

const server = http.createServer(async (request, response) => {
  const headers = { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' };
  if (request.headers.host !== `127.0.0.1:${port}` || request.method !== 'GET') {
    response.writeHead(400, headers).end();
    return;
  }
  const url = new URL(request.url, origin);
  try {
    if (url.pathname === '/') {
      const html = await fs.readFile(previewFile);
      response.writeHead(200, { ...headers, 'Content-Type': 'text/html; charset=utf-8' }).end(html);
    } else if (url.pathname === '/session') {
      // Read the existing local login entry only at runtime. Its credential is
      // never embedded in the preview source, receipts, console or screenshots.
      const fetchSite = request.headers['sec-fetch-site'];
      if (fetchSite && fetchSite !== 'same-origin' && fetchSite !== 'none') {
        response.writeHead(403, headers).end();
        return;
      }
      const entry = new URL((await fs.readFile(entryFile, 'utf8')).trim());
      if (entry.origin !== dshOrigin) throw new Error('Local DSH origin changed');
      response.writeHead(302, { ...headers, Location: entry.href }).end();
    } else if (url.pathname === '/health') {
      response.writeHead(200, { ...headers, 'Content-Type': 'application/json' }).end('{"status":"ok"}');
    } else {
      response.writeHead(404, headers).end();
    }
  } catch {
    response.writeHead(503, { ...headers, 'Content-Type': 'text/plain; charset=utf-8' })
      .end('本机 DSH 预览暂时不可用，请确认 DSH 服务正在运行。');
  }
});

server.on('error', error => {
  console.error(`Preview server could not start (${error.code || 'unknown'}).`);
  process.exitCode = 1;
});
server.listen(port, '127.0.0.1', () => {
  console.log(`Dafeiyu-GO mobile preview: ${origin}/`);
});
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close());
