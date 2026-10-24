import unittest
from release_notes import notes_for_version


class ReleaseNotesTest(unittest.TestCase):
    def test_selects_exact_section_without_other_versions(self):
        changelog = '# Changelog\n\n## 1.24.7 — updates\n\n- Daily check\n\n## 1.24.6\n\n- Older change\n'
        self.assertEqual('- Daily check', notes_for_version(changelog, '1.24.7'))

    def test_missing_version_does_not_publish_unrelated_notes(self):
        with self.assertRaises(ValueError):
            notes_for_version('## 1.24.70\n\n- Something else\n', '1.24.7')
