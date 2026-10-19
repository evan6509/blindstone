# Release automation validation

Local validation on October 19, 2026, on `codex/development`. No push, merge,
remote tag, draft, or release was created. The unrelated
`docs/SEEDCRACKERX_ASSESSMENT.md` working-tree file was preserved.

## Implementation basis

Inspected the current GitHub workflows and relevant release/version/packaging
helpers in `evan6509/Manhunt`, `Chestlogger`, `shared-vitals`, `JobTracker`, and
`imageto3dprint`. The implementation adapts Manhunt/Chestlogger's serialized
version/build/draft/download/verify/publish flow to this repository's single
Fabric server JAR and existing alpha version scheme. Android signing and native
desktop packaging do not apply to this project.

Live, read-only GitHub inspection found no Blindstone tags/releases. Running the
new selector against the actual API chose `0.1.0-alpha.1`. Actions is enabled,
all actions are allowed, default token permissions are `read`, and the repository
rulesets listing is empty. Version selection and release jobs explicitly request
`contents: write`; validation keeps the default read permission and needs no
custom secrets. The actual Actions token and publication APIs remain untested.

## Local results

- Clean Java 25 / Gradle 9.6.1 build and all **126 required GameTests** passed.
- A second clean build with `BLINDSTONE_VERSION=0.1.0-alpha.2` and the checkout
  HEAD as `GITHUB_SHA` also passed all 126 GameTests and `prepareAdmission`.
- The selected-version JAR passed identity, dependency pins, mandatory mixins,
  Java bytecode level, embedded Fabric/manifest version, source-SHA and test-code
  exclusion checks. Packaging produced exactly the installable JAR,
  `CHANGELOG.md`, and `SHA256SUMS`; content and checksum verification passed.
- The installed selected-version production JAR passed ordinary offline
  two-client admission, concealment/lifecycle checks, and keepalive observation.
- The existing development-denial negative control passed initially. Against the
  selected-version JAR it failed once with `EOFError: Server closed socket`
  before decoding the expected denial reason, then passed unchanged against the
  **same JAR hash**. No gameplay or admission-harness behavior was changed, and
  CI continues to require the strict check. This intermittent failure remains a
  CI reliability risk.
- **32 Python release-helper tests** passed: first release, alpha/numeric order,
  stable progression, higher baselines, paginated drafts/tags, published/draft/
  orphan-tag retries, API failures, checkout/tag conflicts, missing/empty/extra
  assets, corrupt/checksum-altered downloads, read-only published retries,
  publication gates, and Latest selection.
- Python syntax, embedded shell syntax, YAML structure, checkout SHA, job
  dependencies, workflow triggers and permissions were checked locally.
- Actionlint **1.7.12** reported only its missing schema support for
  `concurrency.queue`. With that exact diagnostic excluded, all remaining
  actionlint checks passed. The implemented `queue: max` is supported by
  [current GitHub documentation](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)
  and retains up to 100 pending runs; it was deliberately preserved.
- `git diff --check` passed.

The version-override sample was built from the working tree with HEAD
`d6f3ef9f40619635bd84f394ccdda329fc0234e3` supplied as its source marker. It is a
local packaging exercise, not a claim that these uncommitted automation changes
are part of that commit. Actions checks out and builds the exact committed
triggering SHA.

## Event gates

| Event | Validate | Select/publish release |
| --- | --- | --- |
| Push to `codex/development` | Yes | No |
| PR to `main` or `codex/development` | Yes | No |
| Manual run, including on `main` | Yes | No |
| Push to `main`, including merged PR | Yes | Only after successful validation |
| Tag push | Workflow does not trigger | No |

Draft creation/upload/download/publication were exercised through mocked GitHub
boundaries, including failure paths. No GitHub Actions run or real release
publication is claimed. First remote acceptance requires authorized integration
and a successful `main` push run with the requested token permissions.
