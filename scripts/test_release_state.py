import unittest
from unittest.mock import patch
from subprocess import CompletedProcess
from release_state import read_release, release_plan


class ReleaseStateTest(unittest.TestCase):
    tag = "v0.7.1-rc.20261125.55"

    def release(self, draft=False, assets=()):
        return {"tag_name": self.tag, "draft": draft, "prerelease": True,
                "assets": [{"name": name, "size": 100, "state": "uploaded"} for name in assets]}

    def test_new_and_draft_releases_build(self):
        for release in (None, self.release(draft=True)):
            self.assertEqual("true", release_plan(self.tag, release, "draft")["build"])

    def test_published_release_is_not_rebuilt(self):
        self.assertEqual("false", release_plan(self.tag, self.release(), "draft")["build"])

    def test_component_release_does_not_publish_relay(self):
        release = self.release(assets=("codex-pocket-arm64.apk", "phone-environment.zip"))
        self.assertEqual("false", release_plan(self.tag, release, "relay")["publish"])

    def test_relay_requires_complete_uploaded_pair(self):
        archive = f"codex-pocket-{self.tag[1:]}-relay-amd64.tar.gz"
        checksum = "relay-SHA256SUMS.txt"
        for names in ((archive,), (checksum,)):
            with self.assertRaisesRegex(ValueError, "both"):
                release_plan(self.tag, self.release(assets=names), "relay")
        release = self.release(assets=(archive, checksum))
        self.assertEqual("true", release_plan(self.tag, release, "relay")["publish"])
        release["assets"][0]["state"] = "starter"
        with self.assertRaisesRegex(ValueError, "uploading"):
            release_plan(self.tag, release, "relay")

    def test_unpublished_or_wrong_tag_is_rejected(self):
        for release in (None, self.release(draft=True)):
            with self.assertRaises(ValueError):
                release_plan(self.tag, release, "relay")
        with self.assertRaises(ValueError):
            release_plan("main", None, "draft")
        with self.assertRaises(ValueError):
            release_plan("v0.7.2", self.release(), "draft")

    @patch("release_state.subprocess.run")
    def test_only_not_found_can_start_a_new_release(self, run):
        run.return_value = CompletedProcess([], 1, '{"status":"404"}', 'not found')
        self.assertIsNone(read_release("owner/repo", self.tag))
        run.return_value = CompletedProcess([], 1, '{"status":"500"}', 'server error')
        with self.assertRaisesRegex(RuntimeError, "Cannot read"):
            read_release("owner/repo", self.tag)


if __name__ == "__main__":
    unittest.main()
