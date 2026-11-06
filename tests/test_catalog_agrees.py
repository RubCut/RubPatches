"""The catalogue, the patch and the version file say the same thing.

RubP reads `patcher/catalog.json` to know what to download and what it should
end up with, and the mod reads `version.json` to know whether it is out of
date. Both are written by hand, half of them from a build's output, and a
number typed wrong there is not a crash anybody can read: the app downloads a
patch, makes an apk that is the right one, and then reports the result as
wrong -- or serves a stale build it made an hour ago, because the size it
compares against is not the size of what it makes.

So the two are checked against the things they describe: the patch file itself,
the `patch.json` inside it, the base apk it is for, and each other.

No toolchain, no network: the patch is the one checked in here.
"""

import hashlib
import json
import pathlib
import unittest
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


class CatalogAgrees(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.catalog = json.loads((ROOT / "patcher/catalog.json").read_text(encoding="utf-8"))
        cls.release = cls.catalog["mods"][0]["releases"][0]
        cls.patch_path = ROOT / "patches" / pathlib.PurePosixPath(
            cls.release["patch"]["url"]).name
        with zipfile.ZipFile(cls.patch_path) as patch:
            cls.inside = json.loads(patch.read("patch.json"))

    def test_the_patch_the_catalogue_points_at_is_the_one_here(self):
        self.assertTrue(self.patch_path.is_file(), self.patch_path)
        self.assertEqual(self.release["patch"]["size"], self.patch_path.stat().st_size)
        self.assertEqual(self.release["patch"]["sha256"], sha256(self.patch_path))

    def test_the_patch_is_for_the_base_apk_the_catalogue_names(self):
        self.assertEqual(self.release["base"]["sha256"], self.inside["base"]["sha256"])
        self.assertEqual(self.release["base"]["size"], self.inside["base"]["size"])

    def test_the_apk_that_comes_out_is_the_one_the_catalogue_promises(self):
        # RubP compares what it made against this size, and every build serves
        # that same file again only when the size matches: a catalogue that
        # disagrees with its own patch makes every launch rebuild, or worse,
        # hands the person a 'failed' after an apk that was made correctly.
        self.assertEqual(self.release["result"]["size"], self.inside["result"]["size"])

    def test_the_versions_agree(self):
        version = (ROOT / "VERSION").read_text(encoding="utf-8").strip()
        latest = json.loads((ROOT / "version.json").read_text(encoding="utf-8"))["version"]
        self.assertEqual(version, self.release["version"])
        self.assertEqual(latest, version)

    def test_the_notes_are_in_every_language_the_app_asks_for(self):
        words = json.loads((ROOT / "version.json").read_text(encoding="utf-8"))
        for key in ("notes", "notes_ru", "notes_uk"):
            self.assertTrue(words.get(key), key)
        for key in ("ru", "uk", "en"):
            self.assertTrue(self.release.get("notes", {}).get(key), key)


if __name__ == "__main__":
    unittest.main()
