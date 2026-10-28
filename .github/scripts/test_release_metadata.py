import json
import hashlib
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import release_metadata as metadata


class ReleaseMetadataTest(unittest.TestCase):
    def setUp(self):
        self.info = dict(versionCode=123, apkAsset='HyperTweak-v1.8.0-123-release.apk',
                         apkSize=1024, apkSha256='a' * 64, notes={'zh': '中文 -->\n更新'})
        self.metadata_bytes = json.dumps(self.info).encode('utf-8')
        self.release = dict(body=metadata.embed(self.info, 'Visible notes'), assets=[dict(
            name=self.info['apkAsset'], size=1024, digest='sha256:' + 'a' * 64), dict(
            name='build-info.json', size=len(self.metadata_bytes),
            digest='sha256:' + hashlib.sha256(self.metadata_bytes).hexdigest())])

    def test_round_trip_and_replacement(self):
        body = metadata.embed(self.info, self.release['body'])
        self.assertEqual(body.count(metadata.PREFIX), 1)
        self.assertEqual(metadata.extract(body), self.info)
        metadata.verify(self.info, self.release, self.metadata_bytes)

    def test_malformed_duplicate_or_oversized_body(self):
        for body in ['', metadata.PREFIX + '??? -->', self.release['body'] * 2]:
            with self.assertRaises(ValueError):
                metadata.extract(body)
        with self.assertRaises(ValueError):
            metadata.embed(self.info, 'x' * 125000)

    def test_remote_mismatch_fails(self):
        for field, value in [('size', 1025), ('digest', 'sha256:' + 'b' * 64), ('name', 'old.apk')]:
            release = json.loads(json.dumps(self.release))
            release['assets'][0][field] = value
            with self.assertRaises(ValueError):
                metadata.verify(self.info, release, self.metadata_bytes)
        self.release['assets'].append(dict(self.release['assets'][0]))
        with self.assertRaises(ValueError):
            metadata.verify(self.info, self.release, self.metadata_bytes)

    def test_legacy_attachment_must_exist_and_match_the_body_source(self):
        for asset in [None, dict(name='build-info.json', size=1, digest='sha256:' + 'b' * 64)]:
            release = json.loads(json.dumps(self.release))
            release['assets'] = [release['assets'][0]] + ([asset] if asset else [])
            with self.assertRaises(ValueError):
                metadata.verify(self.info, release, self.metadata_bytes)
        with self.assertRaises(ValueError):
            metadata.verify(dict(self.info, versionCode=124), self.release, self.metadata_bytes)

    def test_monotonicity_migrates_from_old_apk_name_without_download(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'release.json'
            for body in [self.release['body'], 'Old release body']:
                self.release['body'] = body
                path.write_text(json.dumps(self.release))
                result = subprocess.run([sys.executable, str(Path(metadata.__file__)), 'code', str(path)],
                                        check=True, capture_output=True, text=True)
                self.assertEqual(result.stdout.strip(), '123')


if __name__ == '__main__':
    unittest.main()
