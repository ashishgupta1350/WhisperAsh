// Scans every file git would publish (tracked + new, not ignored) for API keys, private keys and
// your own identities. Run before every push:   node worker/scripts/check-secrets.mjs
// Exits 1 and lists file:line for anything found. Values are never printed in full.
// "Your identities" = your git email plus every line of the git-ignored file .secrets-denylist at
// the repo root (other emails, phone numbers, anything else that must never be published).
import { execFileSync } from 'node:child_process';
import { readFileSync, statSync } from 'node:fs';

const root = execFileSync('git', ['rev-parse', '--show-toplevel'], { encoding: 'utf8' }).trim();
const files = execFileSync('git', ['ls-files', '--cached', '--others', '--exclude-standard', '-z'], { cwd: root, encoding: 'utf8' })
  .split('\0').filter(Boolean);

const KEY_PATTERNS = [
  ['Groq key', /gsk_[A-Za-z0-9]{20,}/],
  ['Google/Gemini key', /AIza[0-9A-Za-z_-]{35}/],
  ['OpenAI-style key', /\bsk-(?:proj-)?[A-Za-z0-9_-]{24,}/],
  ['Private key', /-----BEGIN [A-Z ]*PRIVATE KEY-----/],
  ['Cloudflare API token', /\bCLOUDFLARE_API_TOKEN\s*=\s*['"]?[A-Za-z0-9_-]{30,}/],
];
// Made-up values in tests ("sk-qa-fake-...", "gsk_test_...") are fine.
const FAKE = /fake|test|example|dummy|placeholder|xxxx/i;

const deny = new Set();
try {
  const e = execFileSync('git', ['config', 'user.email'], { encoding: 'utf8' }).trim();
  if (e) deny.add(e.toLowerCase());
} catch { /* no git email */ }
try {
  for (const l of readFileSync(`${root}/.secrets-denylist`, 'utf8').split(/\r?\n/)) {
    const t = l.trim();
    if (t && !t.startsWith('#')) deny.add(t.toLowerCase());
  }
} catch { /* no denylist file */ }

const SKIP = /(?:^|\/)(?:package-lock\.json|[^/]*\.lock|[^/]*\.(?:png|jpe?g|gif|webp|ico|icns|webm|wav|m4a|mp3|mp4|apk|exe|dll|so|dylib|zip|gz|jar|ttf|otf|woff2?|pdf|bin|keystore|jks))$/i;

const findings = [];
for (const rel of files) {
  if (SKIP.test(rel) || rel === '.secrets-denylist') continue;
  const abs = `${root}/${rel}`;
  let text;
  try {
    if (statSync(abs).size > 5 * 1024 * 1024) continue;
    text = readFileSync(abs, 'utf8');
  } catch { continue; }
  if (text.includes('\u0000')) continue; // binary
  text.split('\n').forEach((line, i) => {
    for (const [name, re] of KEY_PATTERNS) {
      const m = line.match(re);
      if (m && !FAKE.test(m[0])) findings.push(`${rel}:${i + 1}  ${name}  ${m[0].slice(0, 8)}…`);
    }
    const low = line.toLowerCase();
    for (const d of deny) if (low.includes(d)) findings.push(`${rel}:${i + 1}  your own value  ${d.slice(0, 3)}…`);
  });
}

if (findings.length) {
  console.error(`Secret scan: ${findings.length} finding(s). Remove them (or move them to Worker secrets / .env) before pushing:\n`);
  for (const f of findings) console.error('  ' + f);
  process.exit(1);
}
console.log(`Secret scan: ${files.length} files checked, nothing found (${deny.size} personal value(s) checked).`);
