"""Focused release-history, artifact and draft-publication regression tests."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import release_version as rv
import release_assets as ra
import publish_release as pr
from verify_jar import verify

SHA = 'a' * 40
OTHER = 'b' * 40
BASE = '0.1.0-alpha.1'
TAG = 'v' + BASE


def release(tag=TAG, draft=False, target=OTHER):
    return dict(tag_name=tag, draft=draft, target_commitish=target, prerelease='-' in tag)


class Versions(unittest.TestCase):
    def select(self, releases=(), tags=None, base=BASE):
        return rv.select_version(list(releases), SHA, tags or {}, base)

    def test_first_release_uses_checked_in_alpha(self):
        self.assertEqual(self.select(), BASE)

    def test_alpha_counter_and_unrelated_tags(self):
        self.assertEqual(self.select(tags={TAG: OTHER, 'not-a-release': SHA}), '0.1.0-alpha.2')

    def test_drafts_reserve_numbers_even_without_a_tag(self):
        self.assertEqual(self.select([release('v0.1.0-alpha.9', True)]), '0.1.0-alpha.10')

    def test_published_and_draft_reruns_reuse_version(self):
        for draft in (False, True):
            with self.subTest(draft=draft):
                self.assertEqual(self.select([release(draft=draft)], {TAG: SHA, 'v0.2.0': OTHER}), BASE)
        self.assertEqual(self.select([release(draft=True, target=SHA)]), BASE)

    def test_tag_only_retry_reuses_version(self):
        self.assertEqual(self.select(tags={TAG: SHA}), BASE)

    def test_conflicting_draft_target_fails(self):
        with self.assertRaisesRegex(ValueError, 'different commit'):
            self.select([release(draft=True, target=SHA)], {TAG: OTHER})

    def test_stable_history_never_resets_to_old_alpha(self):
        self.assertEqual(self.select(tags={'v2.3.9': OTHER}), '2.3.10')

    def test_explicit_new_baseline_and_channels(self):
        self.assertEqual(self.select(tags={TAG: OTHER}, base='0.2.0-alpha.1'), '0.2.0-alpha.1')
        for channel in ('alpha', 'beta', 'rc'):
            self.assertEqual(self.select(tags={f'v1.0.0-{channel}.9': OTHER}), f'1.0.0-{channel}.10')
        self.assertEqual(self.select(tags={'v0.1.0-rc.8': OTHER}, base='0.1.0'), '0.1.0')

    def test_numeric_order_and_stable_precedence(self):
        self.assertGreater(rv.parse_version('1.0.0-alpha.10'), rv.parse_version('1.0.0-alpha.9'))
        self.assertGreater(rv.parse_version('1.0.0'), rv.parse_version('1.0.0-rc.9'))

    def test_invalid_base_and_duplicate_releases(self):
        for value in ('01.0.0', '0.1.0-alpha.0', 'x', '0.1.0\nINJECT=1'):
            with self.assertRaises(ValueError):
                self.select(base=value)
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            self.select([release(), release()])

    def test_paginated_history_includes_drafts_and_tags(self):
        pages = [[release(f'v0.1.0-alpha.{n}', True) for n in range(1, 101)], [release('v0.1.0-alpha.101', True)]]
        with patch.object(rv, 'gh', side_effect=[json.dumps(pages), json.dumps([[{'name': 'v9.0.0', 'commit': {'sha': OTHER}}]])]) as gh:
            releases, tags = rv.history('owner/repo')
        self.assertEqual(len(releases), 101)
        self.assertEqual(self.select(releases, tags), '9.0.1')
        self.assertTrue(all('--paginate' in call.args and '--slurp' in call.args for call in gh.call_args_list))

    def test_api_auth_failures_are_not_empty_history(self):
        for failure in (subprocess.CalledProcessError(1, ['gh']), json.JSONDecodeError('bad', '', 0)):
            with patch.object(rv, 'gh', side_effect=failure), self.assertRaises(type(failure)):
                rv.history('owner/repo')
        with patch.object(rv, 'gh', return_value='{"message":"Bad credentials"}'), self.assertRaises(ValueError):
            rv.history('owner/repo')

    def test_published_release_requires_resolvable_tag(self):
        with patch.object(rv, 'gh', side_effect=[json.dumps([[release()]]), '[[]]']):
            with self.assertRaisesRegex(ValueError, 'no tag'):
                rv.history('owner/repo')

    def test_failure_listing_tags_also_fails_history(self):
        with patch.object(rv, 'gh', side_effect=['[[]]', subprocess.CalledProcessError(1, ['gh'])]):
            with self.assertRaises(subprocess.CalledProcessError):
                rv.history('owner/repo')

    def test_wrong_checkout_fails(self):
        with patch.object(rv.subprocess, 'check_output', return_value=OTHER), self.assertRaises(ValueError):
            rv.require_checkout(SHA)

    def test_missing_or_conflicting_release_target(self):
        for rel, actual in [(release(), None), (release(draft=True), OTHER), (release(draft=True, target='main'), None)]:
            with self.assertRaises(ValueError):
                rv.validate_target(rel, actual, SHA)


class ArtifactFixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name) / 'release'
        self.directory.mkdir()
        props = rv.properties()
        self.jar = self.directory / f'blindstone-{BASE}+mc{props["minecraft_version"]}.jar'
        self.metadata = json.loads((ROOT / 'src/main/resources/fabric.mod.json').read_text().replace('${version}', f'{BASE}+mc{props["minecraft_version"]}'))
        with ZipFile(self.jar, 'w') as archive:
            archive.writestr('fabric.mod.json', json.dumps(self.metadata))
            archive.writestr('META-INF/MANIFEST.MF', f'Manifest-Version: 1.0\r\nImplementation-Version: {self.metadata["version"]}\r\nBlindstone-Commit: {SHA}\r\n\r\n')
            archive.writestr('blindstone.mixins.json', (ROOT / 'src/main/resources/blindstone.mixins.json').read_bytes())
            # Tiny headers exercise packaging identity checks; actual JVM behavior is covered by GameTests.
            for source in (ROOT / 'src/main/java').rglob('*.java'):
                archive.writestr(str(source.relative_to(ROOT / 'src/main/java').with_suffix('.class')), b'\xca\xfe\xba\xbe\x00\x00\x00\x45')
        (self.directory / 'CHANGELOG.md').write_text(f'# Blindstone {BASE}\n\n<!-- blindstone-commit: {SHA} -->\n')
        self.checksums()

    def checksums(self):
        (self.directory / 'SHA256SUMS').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n'
            for p in [self.jar, self.directory / 'CHANGELOG.md']))


class Artifacts(ArtifactFixture):
    def test_valid_artifact(self):
        self.assertEqual(ra.verify_assets(self.directory, BASE, SHA)[0], self.jar)

    def test_missing_empty_extra_and_corrupt_downloads(self):
        original = self.jar.read_bytes()
        self.jar.unlink()
        with self.assertRaises(SystemExit):
            ra.verify_assets(self.directory, BASE, SHA)
        self.jar.write_bytes(b'')
        with self.assertRaises(SystemExit):
            ra.verify_assets(self.directory, BASE, SHA)
        self.jar.write_bytes(original)
        (self.directory / 'secret.env').write_text('must not ship')
        with self.assertRaises(SystemExit):
            ra.verify_assets(self.directory, BASE, SHA)
        (self.directory / 'secret.env').unlink()
        (self.directory / 'CHANGELOG.md').write_text(f'# Blindstone {BASE}\nchanged\n<!-- blindstone-commit: {SHA} -->\n')
        with self.assertRaisesRegex(SystemExit, 'Checksum'):
            ra.verify_assets(self.directory, BASE, SHA)

    def test_wrong_jar_identity_version_and_commit(self):
        with self.assertRaises(SystemExit):
            verify(self.directory, '0.1.0-alpha.2', SHA)
        with self.assertRaises(SystemExit):
            verify(self.directory, BASE, OTHER)
        with ZipFile(self.jar, 'a') as archive:
            archive.writestr('com/example/SecretGameTests.class', b'x')
        with self.assertRaisesRegex(SystemExit, 'Test code'):
            verify(self.directory, BASE, SHA)

    def test_malformed_duplicate_and_traversal_checksums(self):
        for text in ('bad', '0' * 64 + '  ../secret\n', (self.directory / 'SHA256SUMS').read_text() * 2):
            with self.subTest(text=text):
                (self.directory / 'SHA256SUMS').write_text(text)
                with self.assertRaises(SystemExit):
                    ra.verify_assets(self.directory, BASE, SHA)

    def test_source_and_version_in_notes(self):
        with patch.object(ra.subprocess, 'check_output', return_value=f'{SHA}\0Fix *visibility* @someone\n'):
            text = ra.notes(BASE, SHA, 'owner/repo', self.jar, self.metadata)
            compared = ra.notes(BASE, SHA, 'owner/repo', self.jar, self.metadata, 'v0.0.1')
        self.assertIn('Initial release.', text)
        self.assertIn('Fix \\*visibility\\* &#64;someone', text)
        self.assertIn(f'/commit/{SHA}', text)
        self.assertIn('compare/v0.0.1...v' + BASE, compared)

    def test_previous_release_is_published_ancestor_not_future_or_draft(self):
        releases = [release('v0.0.8'), release('v0.0.9', True), release('v9.0.0')]
        with patch.object(ra.subprocess, 'check_output', return_value=OTHER), patch.object(ra.subprocess, 'run') as run:
            run.return_value.returncode = 0
            self.assertEqual(ra.previous_tag(releases, {r['tag_name']: OTHER for r in releases}, BASE, SHA), 'v0.0.8')
            run.return_value.returncode = 1
            self.assertIsNone(ra.previous_tag(releases, {r['tag_name']: OTHER for r in releases}, BASE, SHA))


class Publication(ArtifactFixture):
    def exercise(self, existing=None, corrupt=False, download_failure=False, final_failure=False):
        state = dict(existing or release(draft=True, target=SHA), body=(self.directory / 'CHANGELOG.md').read_text())
        initial = ([state], {TAG: SHA}) if existing else ([], {})
        final = ([state], {TAG: SHA})
        calls = []

        def fake_gh(*args):
            calls.append(args)
            if args[:2] == ('release', 'download'):
                if download_failure:
                    raise subprocess.CalledProcessError(1, ['gh'])
                dest = Path(args[args.index('--dir') + 1])
                for path in self.directory.iterdir():
                    shutil.copyfile(path, dest / path.name)
                if corrupt:
                    (dest / 'CHANGELOG.md').write_text('corrupt')
            if args[:1] == ('api',) and '--jq' in args:
                return SHA
            return ''

        def history(_):
            nonlocal initial
            result = initial
            initial = final
            if final_failure and result == final:
                raise subprocess.CalledProcessError(1, ['gh'])
            return result

        with patch.object(pr, 'gh', side_effect=fake_gh), patch.object(pr, 'history', side_effect=history), patch.object(pr, 'require_checkout'):
            try:
                pr.publish(self.directory, BASE, TAG, 'owner/repo', SHA)
            finally:
                self.calls = calls

    def test_new_release_is_draft_verified_then_published(self):
        self.exercise()
        create = next(c for c in self.calls if c[:2] == ('release', 'create'))
        self.assertIn('--draft', create)
        self.assertIn('--verify-tag', create)
        self.assertIn('--prerelease', create)
        download = next(i for i, c in enumerate(self.calls) if c[:2] == ('release', 'download'))
        publish = next(i for i, c in enumerate(self.calls) if '--draft=false' in c)
        self.assertLess(download, publish)
        self.assertIn('--latest=false', self.calls[publish])
        self.assertTrue(any(f'ref=refs/tags/{TAG}' in c and f'sha={SHA}' in c for c in self.calls))

    def test_draft_retry_only_replaces_unpublished_assets(self):
        self.exercise(release(draft=True, target=SHA))
        self.assertFalse(any(c[:2] == ('release', 'create') for c in self.calls))
        self.assertTrue(any('--clobber' in c for c in self.calls))

    def test_published_retry_is_read_only(self):
        self.exercise(release(target=SHA))
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(self.calls[0][:2], ('release', 'download'))

    def test_corrupt_or_failed_download_never_publishes(self):
        for options, error in [({'corrupt': True}, SystemExit), ({'download_failure': True}, subprocess.CalledProcessError), ({'final_failure': True}, subprocess.CalledProcessError)]:
            with self.subTest(options=options), self.assertRaises(error):
                self.exercise(**options)
            self.assertFalse(any('--draft=false' in c for c in self.calls))

    def test_tag_conflict_never_mutates(self):
        with patch.object(pr, 'require_checkout'), patch.object(pr, 'history', return_value=([], {TAG: OTHER})), patch.object(pr, 'gh') as gh:
            with self.assertRaises(ValueError):
                pr.publish(self.directory, BASE, TAG, 'owner/repo', SHA)
            gh.assert_not_called()

    def test_create_tag_api_failure_never_creates_a_release(self):
        with patch.object(pr, 'require_checkout'), patch.object(pr, 'history', return_value=([], {})), patch.object(pr, 'gh', side_effect=subprocess.CalledProcessError(1, ['gh'])) as gh:
            with self.assertRaises(subprocess.CalledProcessError):
                pr.publish(self.directory, BASE, TAG, 'owner/repo', SHA)
            self.assertEqual(gh.call_count, 1)
            self.assertIn('POST', gh.call_args.args)

    def test_self_consistent_but_changed_download_is_rejected(self):
        downloaded = Path(self.temp.name) / 'downloaded'
        shutil.copytree(self.directory, downloaded)
        with ZipFile(downloaded / self.jar.name, 'a') as archive:
            archive.writestr('EXTRA.txt', 'unexpected upload bytes')
        (downloaded / 'SHA256SUMS').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n'
            for p in [downloaded / self.jar.name, downloaded / 'CHANGELOG.md']))
        ra.verify_assets(downloaded, BASE, SHA)
        with self.assertRaisesRegex(ValueError, 'differs from uploaded'):
            pr.verify_download(downloaded, BASE, SHA, self.directory)

    def test_published_missing_asset_never_mutates(self):
        with patch.object(pr, 'verify_download', side_effect=SystemExit('Missing artifact')):
            with self.assertRaises(SystemExit):
                self.exercise(release(target=SHA))
        self.assertFalse(any('--clobber' in c or '--draft=false' in c for c in self.calls))

    def test_latest_respects_newer_published_versions_and_main_head(self):
        self.assertEqual(pr.latest_flag([release('v2.0.0')], '1.0.0', SHA, SHA), '--latest=false')
        self.assertEqual(pr.latest_flag([], '1.0.0', SHA, OTHER), '--latest=false')
        self.assertEqual(pr.latest_flag([], BASE, SHA, SHA), '--latest=false')
        self.assertEqual(pr.latest_flag([], '1.0.0', SHA, SHA), '--latest=true')

    def test_event_gate_rejects_pr_development_tags_and_manual(self):
        for event, ref in [('pull_request', 'refs/heads/main'), ('push', 'refs/heads/codex/development'), ('workflow_dispatch', 'refs/heads/main'), ('push', 'refs/tags/v1.0.0')]:
            with patch.dict(os.environ, GITHUB_EVENT_NAME=event, GITHUB_REF=ref), patch.object(pr, 'publish') as publish:
                with self.assertRaisesRegex(ValueError, 'push to main'):
                    pr.main()
                publish.assert_not_called()
        with patch.dict(os.environ, GITHUB_EVENT_NAME='push', GITHUB_REF='refs/heads/main', BLINDSTONE_VERSION=BASE, RELEASE_TAG=TAG, GITHUB_REPOSITORY='owner/repo', GITHUB_SHA=SHA), patch.object(pr, 'publish') as publish:
            pr.main()
            publish.assert_called_once()


if __name__ == '__main__':
    unittest.main()
