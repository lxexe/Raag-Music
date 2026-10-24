import tempfile
import unittest
from pathlib import Path

from release_metadata import metadata


class ReleaseMetadataTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.build = Path(self.temp.name) / 'build.gradle.kts'
        self.build.write_text('versionCode = 36\nversionName = "1.24.4"\n')

    def test_matching_version(self):
        self.assertEqual(metadata('v1.24.4', self.build), {'version': '1.24.4', 'version_code': '36'})

    def test_mismatched_version(self):
        with self.assertRaises(ValueError):
            metadata('v1.24.5', self.build)

    def test_untrusted_or_invalid_tag(self):
        for tag in ['main', 'v1.2.3\nextra=value', 'v1.2.3;echo nope', 'v1.2', 'v1.2.3-beta']:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                metadata(tag, self.build)

    def test_ambiguous_version_is_rejected(self):
        self.build.write_text(self.build.read_text() + 'versionName = "2.0.0"\n')
        with self.assertRaises(ValueError):
            metadata('v1.24.4', self.build)
