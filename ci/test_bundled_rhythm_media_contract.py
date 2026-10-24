"""Pin the existing three player assets; this is not upload/media audit acceptance."""
from pathlib import Path
import hashlib
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
PINS = {
    "rhythm-one.mp3": "d6c70e11982a02ba666331cff3356500a4e187a9bda41b5cc6b641c71cbd5dc1",
    "rhythm-two.mp3": "f654303fb7a29909d7799ced309b43b8563577f6c56f695202147e1706fdb1ee",
    "rhythm-three.mp3": "f87f9f1a475bb8c25e4697026c4b4dedd204a7180fe92e787c7cd742d759817f",
}

class BundledRhythmMediaContractTest(unittest.TestCase):
    def test_actual_packaged_files_and_bytes_match_existing_three_audio_assets(self):
        folder = ROOT / "chengyinhub-xcx/pages/play/audio/merchant-games"
        self.assertEqual(set(PINS), {p.name for p in folder.glob("*.mp3")})
        for filename, expected in PINS.items():
            self.assertEqual(expected, hashlib.sha256((folder / filename).read_bytes()).hexdigest())

    def test_backend_exact_aliases_match_existing_js_audio_contract_not_remote_covers(self):
        js = (ROOT / "chengyinhub-xcx/pages/play/utils/merchant-game-assets.js").read_text()
        actual = set(re.findall(r"'(/pages/play/audio/merchant-games/[^']+)'", js))
        packaged = {"/pages/play/audio/merchant-games/" + name for name in PINS}
        self.assertEqual(packaged, actual)
        schema = (ROOT / "chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/AppMediaSchema.java").read_text()
        block = schema.split("BUNDLED_MERCHANT_GAME_AUDIO = Set.of(", 1)[1].split(");", 1)[0]
        self.assertEqual(packaged | {"/audio/merchant-games/" + name for name in PINS},
                         set(re.findall(r'"([^"]+)"', block)))
        self.assertIn("BUNDLED_MERCHANT_GAME_AUDIO.contains(reference)", schema)
        self.assertNotIn("http", block)
        self.assertNotIn("..", block)

    def test_legacy_seed_audio_bytes_remain_and_processing_does_not_admit_stock(self):
        seed = (ROOT / "chengyinhub-admin/sql/migration_seed_merchant_game_a8_20260823.sql").read_text()
        for filename in PINS:
            self.assertIn('"audioUrl":"/audio/merchant-games/' + filename + '"', seed)
        guard = (ROOT / "chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/AppMediaReferenceGuard.java").read_text()
        processing = guard.split("public String processingUrl(", 1)[1].split("private Map<String, Object> resolve(", 1)[0]
        attachment = guard.split("public String requireAttachment(", 1)[1].split("public String requireAttachments(", 1)[0]
        self.assertNotIn("isBundledMerchantGameAudio", processing)
        self.assertNotIn("isBundledMerchantGameAudio", attachment)
        self.assertIn('"APPROVED".equals(asset.get("reviewState"))', processing)

if __name__ == "__main__":
    unittest.main()
