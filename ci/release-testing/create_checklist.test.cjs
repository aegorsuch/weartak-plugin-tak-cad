const test = require('node:test');
const assert = require('node:assert/strict');
const { createChecklist } = require('./create_checklist.cjs');

function fixture(existing = []) {
  const created = [];
  const github = {
    rest: {
      repos: { getReleaseByTag: async () => ({ data: {
        id: 123, draft: false, html_url: 'https://example.invalid/release/v1',
        assets: [{ name: 'TAK-CAD-debug-not-production-signed.apk', state: 'uploaded',
          browser_download_url: 'https://example.invalid/debug.apk' }],
      } }) },
      issues: {
        listForRepo: () => {},
        checkUserCanBeAssigned: async () => {},
        create: async (args) => { created.push(args); return { data: { html_url: 'https://example.invalid/issue/1' } }; },
      },
    },
    paginate: { iterator: async function* () { yield { data: existing }; } },
  };
  return { github, created, context: { repo: { owner: 'test', repo: 'tak-cad' } }, core: { info: () => {} }, tag: 'v0.1.0' };
}

test('creates debug checklist with optional tester', async () => {
  const data = fixture();
  await createChecklist({ ...data, tester: 'tester' });
  assert.equal(data.created.length, 1);
  assert.deepEqual(data.created[0].assignees, ['tester']);
  assert.match(data.created[0].body, /takcad-release-testing:123/);
  assert.match(data.created[0].body, /not production release approval/);
});

test('does not duplicate even a closed checklist', async () => {
  const data = fixture([{ body: '<!-- takcad-release-testing:123 -->', state: 'closed' }]);
  await createChecklist(data);
  assert.equal(data.created.length, 0);
});

test('rejects invalid tag, invalid tester, draft or absent APK', async () => {
  await assert.rejects(() => createChecklist({ ...fixture(), tag: 'bad/tag' }), /release tag/);
  await assert.rejects(() => createChecklist({ ...fixture(), tester: 'invalid user' }), /username/);
  const draft = fixture();
  draft.github.rest.repos.getReleaseByTag = async () => ({ data: { draft: true } });
  await assert.rejects(() => createChecklist(draft), /draft/);
  const absent = fixture();
  absent.github.rest.repos.getReleaseByTag = async () => ({ data: { assets: [] } });
  await assert.rejects(() => createChecklist(absent), /APK asset/);
});
