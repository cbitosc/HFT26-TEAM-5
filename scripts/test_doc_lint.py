"""Unit tests for scripts/doc-lint.py's rules. Run: python3 -m unittest scripts/test_doc_lint.py"""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("doc_lint", Path(__file__).with_name("doc-lint.py"))
lint = importlib.util.module_from_spec(_SPEC)
sys.modules["doc_lint"] = lint  # dataclasses look their module up by name
assert _SPEC.loader is not None
_SPEC.loader.exec_module(lint)

APP = lint.APP_PACKAGE


def index() -> lint.Index:
    """A small repository: two app types, a retired function, a few files and two ADRs."""
    idx = lint.Index()
    files = [
        f"{APP}/mesh/lora/LoraPacePolicy.kt",
        f"{APP}/mesh/power/PowerState.kt",
        f"{APP}/mesh/bluetooth/meshtastic/MeshtasticGatt.kt",
        "scripts/lab-lint.py",
        ".agents/context/lora.md",
    ]
    idx.files = set(files)
    for f in files:
        parts = f.split("/")
        idx.dirs.update("/".join(parts[:i]) for i in range(1, len(parts)))
    idx.roots = {"app", "scripts", ".agents", "mesh"}
    idx.tokens = {"LoraPacePolicy", "enqueue", "supersedes", "PowerPolicy", "PowerState", "MeshtasticGatt", "dial",
                  "theRoundRunsOnce", "requestMtu"}
    idx.types = {"LoraPacePolicy", "PowerPolicy"}
    idx.retired = {"dropQueued", "openNewEpoch"}
    idx.adrs = {"024", "2026-09.xdm2"}
    idx.lines = {f"{APP}/mesh/power/PowerState.kt": 120}
    return idx


def rules(text: str, idx=None) -> list[str]:
    return [f.rule for f in lint.lint_text("doc.md", text, idx or index())]


class RetiredSymbol(unittest.TestCase):
    def test_a_name_the_code_dropped_is_flagged_bare_and_dotted(self):
        self.assertEqual(rules("calls `openNewEpoch` then `LoraPacePolicy.dropQueued`"), ["retired-symbol"] * 2)

    def test_a_platform_name_the_repo_never_declared_passes(self):
        self.assertEqual(rules("`BluetoothGatt.requestMtu` and `onConnectionUpdated`"), [])

    def test_an_all_caps_word_is_prose(self):
        idx = index()
        idx.retired.add("BLOCKED")
        self.assertEqual(rules("lead with `BLOCKED`", idx), [])

    def test_a_reasoned_allow_in_the_paragraph_lets_it_stand(self):
        text = "It replaced `dropQueued`,\nwhich was per class.\n<!-- doc-lint: allow dropQueued — names the old API -->"
        self.assertEqual(rules(text), [])

    def test_an_allow_without_a_reason_does_not_count(self):
        self.assertEqual(rules("`dropQueued`\n<!-- doc-lint: allow dropQueued -->"), ["retired-symbol"])

    def test_an_allow_reaches_its_paragraph_only(self):
        text = "<!-- doc-lint: allow dropQueued — history -->\n\nNow `dropQueued` again."
        self.assertEqual(rules(text), ["retired-symbol"])


class MissingMember(unittest.TestCase):
    def test_a_member_of_an_app_type_nobody_mentions_is_flagged(self):
        self.assertEqual(rules("`LoraPacePolicy.dropAll`"), ["missing-member"])

    def test_a_member_of_a_foreign_type_passes(self):
        self.assertEqual(rules("`Config.SecurityConfig` and `field.record`"), [])

    def test_a_truncated_name_matches_by_prefix(self):
        self.assertEqual(rules("`PowerPolicy.theRoundRuns…`"), [])
        self.assertEqual(rules("`PowerPolicy.aGoneTest…`"), ["missing-member"])


    def test_a_protobuf_field_and_a_constant_family_pass(self):
        self.assertEqual(rules("`PowerPolicy.hop_limit` and `PowerPolicy.CAP_*`", self._with_cap()), [])

    @staticmethod
    def _with_cap():
        idx = index()
        idx.tokens.add("CAP_DOORBELL")
        return idx


class Comments(unittest.TestCase):
    def test_a_kdoc_mention_does_not_keep_a_retired_name_alive(self):
        src = 'val url = "https://x" // was dropQueued\n/** see [openNewEpoch] */ val c = \'/\' + """a // b"""'
        stripped = lint.strip_comments(src)
        self.assertNotIn("dropQueued", stripped)
        self.assertNotIn("openNewEpoch", stripped)
        self.assertIn('"https://x"', stripped)
        self.assertIn('"""a // b"""', stripped)


class MissingPath(unittest.TestCase):
    def test_a_rooted_path_to_nothing_is_flagged(self):
        self.assertEqual(rules("see `mesh/lora/LoraGone.kt` and `scripts/gone.py`"), ["missing-path"] * 2)

    def test_package_relative_shorthand_and_dir_symbol_resolve(self):
        self.assertEqual(rules("`mesh/lora/LoraPacePolicy`, `meshtastic/MeshtasticGatt`, `mesh/power/PowerPolicy`"), [])

    def test_file_member_resolves_through_its_file(self):
        self.assertEqual(rules("`mesh/lora/LoraPacePolicy.enqueue`"), [])

    def test_an_unrooted_or_generated_or_elided_path_is_not_checked(self):
        self.assertEqual(rules("`files/keystore-fault`, `app/build/reports/x`, `app/src/.../Gone.kt`, `../knit-ios/x`"), [])

    def test_a_fenced_command_path_is_checked(self):
        self.assertEqual(rules("```bash\npython3 scripts/lab-lint.py && scripts/gone.sh\n```"), ["missing-path"])


class LineRef(unittest.TestCase):
    def test_a_line_past_the_end_is_flagged(self):
        self.assertEqual(rules("`PowerState.kt:121`"), ["line-ref"])
        self.assertEqual(rules("`PowerState.kt:120`"), [])


class MissingAdr(unittest.TestCase):
    def test_an_unknown_id_is_flagged_and_a_known_one_passes(self):
        self.assertEqual(rules("ADR 024, ADR 025, 2026-09.xdm2 and 2026-09.zzzz"), ["missing-adr"] * 2)

    def test_an_ios_port_id_is_not_ours(self):
        self.assertEqual(rules("companion change (`knit-ios`, ADR 2026-09.zzzz there)"), [])
        self.assertEqual(rules("the iOS change (knit-ios\nADR 2026-09.zzzz) takes it"), [])

    def test_any_sibling_repos_id_is_not_ours(self):
        self.assertEqual(rules("a subset of knit-spool ADR 2026-10.zzzz, and `knit-pair` ADR 2026-10.yyyy"), [])
        self.assertEqual(rules("knit-next ADR 2026-10.zzzz"), ["missing-adr"])

    def test_a_date_is_not_an_id(self):
        self.assertEqual(rules("since 2026-10-09"), [])


class Record(unittest.TestCase):
    def test_a_record_doc_is_read_for_adr_ids_only(self):
        text = "# Review\n\n<!-- doc-lint: record — dated -->\n\n`dropQueued` and ADR 999"
        self.assertEqual(rules(text), ["missing-adr"])


class TheTree(unittest.TestCase):
    def test_the_real_index_knows_the_names_retired_on_2026_10_09(self):
        idx = lint.build_index()
        for name in ("dropQueued", "openNewEpoch", "unanchoredRaceWinner"):
            self.assertIn(name, idx.retired)
        self.assertIn("RatchetEngine", idx.types)


if __name__ == "__main__":
    unittest.main()
