const fs = require('node:fs');
const path = require('node:path');

async function createChecklist({ github, context, core, tag, tester }) {
  if (!/^v[0-9A-Za-z._-]{1,100}$/.test(tag || '')) throw new Error('Expected published v* release tag.');
  if (tester && !/^[a-z\d](?:[a-z\d-]{0,37}[a-z\d])?$/i.test(tester)) {
    throw new Error('RELEASE_TESTER must be a GitHub username.');
  }
  const repo = context.repo;
  const { data: release } = await github.rest.repos.getReleaseByTag({ ...repo, tag });
  if (release.draft) throw new Error('Release is still a draft.');
  const asset = release.assets.find((item) =>
    item.name === 'TAK-CAD-debug-not-production-signed.apk' && item.state === 'uploaded');
  if (!asset) throw new Error('Published release has no verified debug APK asset.');
  const marker = `<!-- takcad-release-testing:${release.id} -->`;
  for await (const response of github.paginate.iterator(github.rest.issues.listForRepo, {
    ...repo, state: 'all', per_page: 100,
  })) {
    const existing = response.data.find((issue) => !issue.pull_request && (issue.body || '').includes(marker));
    if (existing) { core.info(`Checklist already exists: ${existing.html_url}`); return existing; }
  }
  if (tester) await github.rest.issues.checkUserCanBeAssigned({ ...repo, assignee: tester });
  const checklist = fs.readFileSync(path.join(__dirname, 'checklist.md'), 'utf8');
  const { data: issue } = await github.rest.issues.create({
    ...repo,
    title: `Test TAK-CAD ${tag} (debug build)`,
    body: `${marker}\n${tester ? `@${tester}\n` : ''}Release: ${release.html_url}\n` +
      `Debug APK: ${asset.browser_download_url}\n\n${checklist}`,
    ...(tester ? { assignees: [tester] } : {}),
  });
  core.info(`Created release checklist: ${issue.html_url}`);
  return issue;
}

module.exports = { createChecklist };
