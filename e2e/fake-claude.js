// A stand-in for the Anthropic API, for the browser tests (e2e/compose.yml
// points the app's ANTHROPIC_BASE_URL here). It accepts one key, and sorts
// payees into the categories it is sent by keyword.
const http = require('node:http');

const GOOD_KEY = 'sk-ant-e2e-good-key-0000'; // matches e2e/tests/claude.spec.js
const rules = { セブン: 'Food & Drinks', スギ薬局: 'Health', JR: 'Transport' };

const between = (s, a, b) => s.split(a)[1]?.split(b)[0] ?? '[]';

function json(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

http
  .createServer((req, res) => {
    if (req.url === '/health') return json(res, 200, { ok: true });
    if (req.headers['x-api-key'] !== GOOD_KEY) {
      return json(res, 401, { type: 'error', error: { type: 'authentication_error', message: 'invalid x-api-key' } });
    }
    if (req.method === 'GET' && req.url.startsWith('/v1/models/')) {
      return json(res, 200, { type: 'model', id: req.url.slice('/v1/models/'.length), display_name: 'Claude', created_at: '2026-01-01T00:00:00Z' });
    }
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      const text = JSON.parse(body).messages[0].content[0].text;
      const cats = JSON.parse(between(text, '<categories>', '</categories>'));
      const items = JSON.parse(between(text, '<items>', '</items>'));
      const answers = items.map(({ item, payee }) => {
        const word = Object.keys(rules).find((w) => payee.includes(w));
        const cat = word && cats.find((c) => c.name === rules[word]);
        return { item, category_id: cat ? cat.id : 0 };
      });
      json(res, 200, {
        id: 'msg_e2e', type: 'message', role: 'assistant', model: 'claude-opus-5-5', stop_reason: 'end_turn',
        content: [{ type: 'text', text: JSON.stringify({ answers }) }],
        usage: { input_tokens: 10, output_tokens: 10 },
      });
    });
  })
  .listen(8090);
