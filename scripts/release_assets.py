#!/usr/bin/env python3
"""Package and verify only Blindstone's installable JAR, notes and checksums."""
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

from release_version import parse_version, properties
from verify_jar import require, verify


def previous_tag(releases, tags, version, commit):
    candidates = [r['tag_name'] for r in releases if not r['draft']
                  and r['tag_name'] in tags and r['tag_name'].startswith('v')]
    applicable = []
    for tag in candidates:
        try:
            parsed = parse_version(tag[1:])
        except ValueError:
            continue
        if parsed >= parse_version(version):
            continue
        # Checkout has full tag history. Fail rather than silently omit missing history.
        local = subprocess.check_output(['git', 'rev-parse', f'refs/tags/{tag}^{{commit}}'], text=True).strip()
        if local != tags[tag]:
            raise ValueError(f'Local and remote tag disagree: {tag}')
        result = subprocess.run(['git', 'merge-base', '--is-ancestor', local, commit], check=False)
        if result.returncode not in (0, 1):
            raise ValueError(f'Cannot inspect release ancestry: {tag}')
        if result.returncode == 0:
            applicable.append(tag)
    return max(applicable, key=lambda t: parse_version(t[1:])) if applicable else None


def notes(version, commit, repository, jar, metadata, previous=None):
    revision = f'refs/tags/{previous}..{commit}' if previous else commit
    log = subprocess.check_output(['git', 'log', '--reverse', '--format=%H%x00%s', revision, '--'], text=True)
    url = f'https://github.com/{repository}'
    lines = [f'# Blindstone {version}', '', 'Server-side Fabric private alpha. Platform-independent Java 25 JAR.', '', '## Changes', '']
    for entry in log.splitlines():
        sha, subject = entry.split('\0', 1)
        subject = re.sub(r'([\\`*_{}\[\]<>])', r'\\\1', subject).replace('@', '&#64;')
        lines.append(f'- {subject} ([{sha[:7]}]({url}/commit/{sha}))')
    if not log:
        lines.append('- No new commits in this release.')
    lines += ['', f'[Full changelog]({url}/compare/{previous}...v{version})' if previous else 'Initial release.',
              '', '## Install', '', f"Put `{jar.name}` in the server's `mods` directory.", '',
              'Requirements: ' + ', '.join(f'{name} `{metadata["depends"][key]}`' for key, name in
                  [('minecraft', 'Minecraft'), ('fabricloader', 'Fabric Loader'), ('fabric-api', 'Fabric API'), ('java', 'Java')]) + '.',
              '', 'Fabric API is a separate required download. No client mod is required.', '',
              'This private alpha retains the acceptance limits documented in the README; automated checks do not establish graphical or adversarial-client acceptance.',
              '', f'Source: [{commit}]({url}/commit/{commit}).', f'<!-- blindstone-commit: {commit} -->', '']
    return '\n'.join(lines)


def names(version):
    parse_version(version)
    return {f'blindstone-{version}+mc{properties()["minecraft_version"]}.jar', 'CHANGELOG.md', 'SHA256SUMS'}


def verify_assets(directory, version, commit):
    directory = Path(directory)
    expected = names(version)
    require({p.name for p in directory.iterdir()} == expected, 'Unexpected release asset set')
    require(all(p.is_file() and not p.is_symlink() and p.stat().st_size > 0 for p in directory.iterdir()), 'Missing or empty release asset')
    jar, metadata = verify(directory, version, commit)
    changelog = (directory / 'CHANGELOG.md').read_text()
    require(changelog.startswith(f'# Blindstone {version}\n') and f'<!-- blindstone-commit: {commit} -->' in changelog,
            'Release notes version/commit differs')
    checksums = (directory / 'SHA256SUMS').read_text().splitlines()
    entries = {}
    for line in checksums:
        match = re.fullmatch(r'([0-9a-f]{64})  ([^/\\]+)', line)
        require(match is not None, 'Invalid checksum entry')
        digest, name = match.groups()
        require(name not in entries, 'Duplicate checksum entry')
        entries[name] = digest
    require(set(entries) == expected - {'SHA256SUMS'}, 'Checksum manifest asset set differs')
    for name, digest in entries.items():
        require(hashlib.sha256((directory / name).read_bytes()).hexdigest() == digest, f'Checksum differs: {name}')
    return jar, metadata


def package(directory, version, commit, repository, previous=None):
    directory = Path(directory)
    # Reject a stale output directory rather than accidentally upload unrelated files.
    directory.mkdir(parents=True, exist_ok=True)
    require(not list(directory.iterdir()), 'Release directory must be empty')
    jar, metadata = verify('build/libs', version, commit)
    shutil.copyfile(jar, directory / jar.name)
    (directory / 'CHANGELOG.md').write_text(notes(version, commit, repository, jar, metadata, previous))
    files = [directory / jar.name, directory / 'CHANGELOG.md']
    (directory / 'SHA256SUMS').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in files))
    verify_assets(directory, version, commit)


if __name__ == '__main__':
    version = os.environ.get('BLINDSTONE_VERSION', properties()['mod_version'])
    commit = os.environ['GITHUB_SHA']
    directory = sys.argv[2] if len(sys.argv) > 2 else 'build/release'
    if sys.argv[1] == 'package':
        package(directory, version, commit, os.environ['GITHUB_REPOSITORY'], os.environ.get('PREVIOUS_TAG') or None)
    elif sys.argv[1] == 'verify':
        verify_assets(directory, version, commit)
    else:
        sys.exit('Usage: release_assets.py package|verify [DIRECTORY]')
