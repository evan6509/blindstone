#!/usr/bin/env python3
"""Publish only after downloaded draft assets pass integrity and content checks."""
import os
from pathlib import Path
import tempfile

from release_assets import names, verify_assets
from release_version import gh, history, parse_version, require_checkout, validate_target, version_tag


def get_release(releases, tag):
    matches = [r for r in releases if r['tag_name'] == tag]
    if len(matches) > 1:
        raise ValueError(f'Multiple releases for {tag}')
    return matches[0] if matches else None


def verify_download(directory, version, commit, original=None):
    verify_assets(directory, version, commit)
    if original is not None:
        for name in names(version):
            if (Path(directory) / name).read_bytes() != (Path(original) / name).read_bytes():
                raise ValueError(f'Downloaded asset differs from uploaded build: {name}')


def latest_flag(releases, version, commit, main_commit):
    # Prereleases cannot be Latest. An old queued run must not displace newer code/version.
    if parse_version(version)[3] < 3 or main_commit != commit:
        return '--latest=false'
    newer = any(not r['draft'] and version_tag(r['tag_name'])
                and parse_version(r['tag_name'][1:]) > parse_version(version) for r in releases)
    return '--latest=false' if newer else '--latest=true'


def publish(directory, version, tag, repository, commit):
    parse_version(version)
    if tag != f'v{version}':
        raise ValueError('Selected version and release tag disagree')
    require_checkout(commit)
    verify_assets(directory, version, commit)
    releases, tags = history(repository)
    existing = get_release(releases, tag)
    validate_target(existing, tags.get(tag), commit)
    if existing is not None and not existing['draft']:
        # A rerun never overwrites published bytes, even if the rebuild differs.
        with tempfile.TemporaryDirectory(prefix='blindstone-published-') as downloaded:
            gh('release', 'download', tag, '--repo', repository, '--dir', downloaded)
            verify_download(downloaded, version, commit)
            if existing['body'] != (Path(downloaded) / 'CHANGELOG.md').read_text():
                raise ValueError('Published notes differ from the downloadable changelog')
        print(f'Already published and verified: {tag}')
        return

    # Create the tag once at the exact SHA. POST fails on collision and never moves a tag.
    if tag not in tags:
        gh('api', '--method', 'POST', f'repos/{repository}/git/refs',
           '-f', f'ref=refs/tags/{tag}', '-f', f'sha={commit}')
    assets = [str(Path(directory) / name) for name in sorted(names(version))]
    changelog = str(Path(directory) / 'CHANGELOG.md')
    title = f'Blindstone {version}'
    if existing is None:
        options = ['--prerelease'] if parse_version(version)[3] < 3 else []
        gh('release', 'create', tag, *assets, '--repo', repository, '--verify-tag', '--target', commit,
           '--draft', '--title', title, '--notes-file', changelog, *options)
    else:
        # Replacing assets is permitted only on a verified, unpublished draft at this SHA.
        gh('release', 'upload', tag, *assets, '--repo', repository, '--clobber')
        gh('release', 'edit', tag, '--repo', repository, '--title', title, '--notes-file', changelog)
    with tempfile.TemporaryDirectory(prefix='blindstone-draft-') as downloaded:
        gh('release', 'download', tag, '--repo', repository, '--dir', downloaded)
        verify_download(downloaded, version, commit, directory)

    # Re-read remote state before publishing; API failures leave the draft unpublished.
    releases, tags = history(repository)
    current = get_release(releases, tag)
    validate_target(current, tags.get(tag), commit)
    if current is None or not current['draft'] or tags.get(tag) != commit:
        raise ValueError('Draft or tag changed before publication')
    if current['body'] != Path(changelog).read_text():
        raise ValueError('Draft release notes differ from the verified changelog')
    main_commit = gh('api', f'repos/{repository}/commits/main', '--jq', '.sha').strip()
    prerelease = '--prerelease=true' if parse_version(version)[3] < 3 else '--prerelease=false'
    gh('release', 'edit', tag, '--repo', repository, '--draft=false', prerelease,
       latest_flag(releases, version, commit, main_commit))
    print(f'Published verified release: {tag} ({commit})')


def main():
    if os.environ.get('GITHUB_EVENT_NAME') != 'push' or os.environ.get('GITHUB_REF') != 'refs/heads/main':
        raise ValueError('Publication is permitted only for a push to main')
    publish('release', os.environ['BLINDSTONE_VERSION'], os.environ['RELEASE_TAG'],
            os.environ['GITHUB_REPOSITORY'], os.environ['GITHUB_SHA'])


if __name__ == '__main__':
    main()
