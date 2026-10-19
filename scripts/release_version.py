#!/usr/bin/env python3
"""Select a monotonic SemVer release; adapted from Manhunt and Chestlogger."""
import json
import os
from pathlib import Path
import re
import subprocess

VERSION = re.compile(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(alpha|beta|rc)\.([1-9]\d*))?')


def parse_version(value):
    match = VERSION.fullmatch(value)
    if not match:
        raise ValueError(f'Expected MAJOR.MINOR.PATCH[-alpha.N|-beta.N|-rc.N]: {value}')
    major, minor, patch, channel, number = match.groups()
    return (*map(int, (major, minor, patch)), {'alpha': 0, 'beta': 1, 'rc': 2, None: 3}[channel], int(number or 0))


def version_tag(tag):
    return isinstance(tag, str) and tag.startswith('v') and VERSION.fullmatch(tag[1:]) is not None


def properties():
    return dict(line.strip().split('=', 1) for line in Path('gradle.properties').read_text().splitlines()
                if '=' in line and not line.lstrip().startswith('#'))


def gh(*args):
    # In particular, authentication/network failures are never an empty history.
    return subprocess.check_output(['gh', *args], text=True)


def api_list(repository, endpoint):
    pages = json.loads(gh('api', f'repos/{repository}/{endpoint}?per_page=100', '--paginate', '--slurp'))
    if not isinstance(pages, list) or any(not isinstance(page, list) for page in pages):
        raise ValueError(f'Invalid paginated {endpoint} response')
    items = [item for page in pages for item in page]
    if any(not isinstance(item, dict) for item in items):
        raise ValueError(f'Invalid {endpoint} item')
    return items


def history(repository):
    releases = api_list(repository, 'releases')  # Write-capable token includes drafts.
    tags = {tag['name']: tag['commit']['sha'] for tag in api_list(repository, 'tags')}
    for release in releases:
        if version_tag(release['tag_name']) and not release['draft'] and release['tag_name'] not in tags:
            raise ValueError(f"Published release has no tag: {release['tag_name']}")
    return releases, tags


def validate_target(release, actual_commit, commit):
    if actual_commit is not None and actual_commit != commit:
        raise ValueError('Release tag points at a different commit')
    if release is not None and actual_commit is None:
        if not release['draft'] or release.get('target_commitish') != commit:
            raise ValueError('Release does not identify the exact triggering commit')


def select_version(releases, commit, tags, base_version):
    base = parse_version(base_version)
    known = [r for r in releases if version_tag(r['tag_name'])]
    names = [r['tag_name'] for r in known]
    if len(names) != len(set(names)):
        raise ValueError('Duplicate release tags in API response')
    existing = []
    for release in known:
        tag = release['tag_name']
        target = tags.get(tag)
        if target == commit or release.get('target_commitish') == commit:
            validate_target(release, target, commit)
            existing.append(tag[1:])
    if existing:
        return max(existing, key=parse_version)
    # An orphan tag is an unfinished operation and can safely be completed at its exact SHA.
    same_commit = [tag[1:] for tag, sha in tags.items() if version_tag(tag) and sha == commit]
    if same_commit:
        return max(same_commit, key=parse_version)
    reserved = {tag[1:] for tag in tags if version_tag(tag)} | {r['tag_name'][1:] for r in known}
    if not reserved or base > max(map(parse_version, reserved)):
        return base_version
    highest = max(reserved, key=parse_version)
    major, minor, patch, channel, number = parse_version(highest)
    if channel < 3:
        return f'{major}.{minor}.{patch}-' + ('alpha', 'beta', 'rc')[channel] + f'.{number + 1}'
    return f'{major}.{minor}.{patch + 1}'


def require_checkout(commit):
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('Expected the full triggering commit SHA')
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    if head != commit:
        raise ValueError('Checkout differs from the triggering commit')


def main():
    commit = os.environ['GITHUB_SHA']
    require_checkout(commit)
    releases, tags = history(os.environ['GITHUB_REPOSITORY'])
    version = select_version(releases, commit, tags, properties()['mod_version'])
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write(f'version={version}\ntag=v{version}\n')
    print(f'Release version: {version}')


if __name__ == '__main__':
    main()
