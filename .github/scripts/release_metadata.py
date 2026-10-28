#!/usr/bin/env python3
"""API metadata for new clients; verify the temporary legacy attachment without downloading it."""
import base64
import json
import hashlib
import re
import sys
from pathlib import Path

PREFIX = '<!-- hypertweak-update:v1:'
PATTERN = re.compile(re.escape(PREFIX) + r'([A-Za-z0-9+/=]+) -->')


def extract(body):
    matches = list(PATTERN.finditer(body))
    if len(matches) != 1 or body.count(PREFIX) != 1:
        raise ValueError('Expected exactly one valid update metadata marker')
    return json.loads(base64.b64decode(matches[0][1], validate=True).decode('utf-8'))


def embed(metadata, body):
    # Release bodies are capped at 125,000 characters by GitHub.
    payload = json.dumps(metadata, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    body = PATTERN.sub('', body).rstrip()
    result = body + '\n\n' + PREFIX + base64.b64encode(payload).decode('ascii') + ' -->\n'
    if len(result) > 125000:
        raise ValueError('Release body exceeds GitHub limit')
    return result


def verify(expected, release, metadata_bytes):
    if extract(release.get('body') or '') != expected:
        raise ValueError('Published release metadata differs from verified APK metadata')
    apks = [a for a in release['assets'] if a['name'].lower().endswith('.apk')]
    if len(apks) != 1:
        raise ValueError('Expected exactly one APK asset')
    asset = apks[0]
    if (asset['name'] != expected['apkAsset'] or asset['size'] != expected['apkSize']
            or asset.get('digest') != 'sha256:' + expected['apkSha256']):
        raise ValueError('Published APK name/size/digest differs from verified APK')

    legacy = [a for a in release['assets'] if a['name'] == 'build-info.json']
    if len(legacy) != 1:
        raise ValueError('Expected legacy metadata attachment during migration')
    if (legacy[0]['size'] != len(metadata_bytes)
            or legacy[0].get('digest') != 'sha256:' + hashlib.sha256(metadata_bytes).hexdigest()):
        raise ValueError('Legacy metadata differs from the API body metadata source')


def main():
    command, *paths = sys.argv[1:]
    if command == 'embed':
        metadata, body = map(Path, paths)
        body.write_text(embed(json.loads(metadata.read_text()), body.read_text()), encoding='utf-8')
    elif command == 'code':
        release = json.loads(Path(paths[0]).read_text())
        body = release.get('body') or ''
        # Migration from the attachment format: the old rolling APK filename also
        # carries versionCode. Do not download the legacy metadata asset.
        if PREFIX in body:
            code = extract(body)['versionCode']
        else:
            codes = [int(m[1]) for a in release.get('assets', [])
                     if (m := re.search(r'^HyperTweak-v.+-([0-9]+)-release(?:-debug-signed)?\.apk$', a['name']))]
            if len(codes) != 1:
                raise ValueError('Cannot establish existing rolling release versionCode')
            code = codes[0]
        if not isinstance(code, int) or isinstance(code, bool) or code <= 0:
            raise ValueError('Invalid versionCode')
        print(code)
    elif command == 'verify':
        metadata_bytes = Path(paths[0]).read_bytes()
        verify(json.loads(metadata_bytes), json.loads(Path(paths[1]).read_text()), metadata_bytes)
    else:
        raise ValueError('Unknown command')


if __name__ == '__main__':
    main()
