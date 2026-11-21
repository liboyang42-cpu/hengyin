"""Contract tests for the T2 maps-backend dense district seed (ci/perf/maps/seed_dense_district.py)."""
import io
import os
import subprocess
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "perf", "maps", "seed_dense_district.py")
sys.path.insert(0, os.path.join(HERE, "perf", "maps"))
import seed_dense_district as seed  # noqa: E402


def run(*args):
    return subprocess.run([sys.executable, SCRIPT, *args], capture_output=True, text=True)


class MapsSeedTest(unittest.TestCase):
    def test_refuses_production_schema_and_low_ids(self):
        self.assertEqual(2, run("--database", "chengyinhubdb").returncode)
        self.assertEqual(2, run("--database", "CHENGYINHUBDB ").returncode)
        self.assertEqual(2, run("--database", "cy_local", "--id-base", "10").returncode)

    def test_deterministic_and_rerunnable(self):
        a = run("--database", "cy_local", "--places", "300", "--members", "3", "--clubs", "10")
        b = run("--database", "cy_local", "--places", "300", "--members", "3", "--clubs", "10")
        self.assertEqual(0, a.returncode, a.stderr)
        self.assertEqual(a.stdout, b.stdout)
        out = a.stdout
        self.assertIn("USE `cy_local`;", out)
        # Cleanup of earlier seeded rows comes before any insert, scoped to the seeded id range.
        self.assertLess(out.index("DELETE FROM roam_poi WHERE id >= 9000000;"), out.index("INSERT INTO roam_poi "))
        self.assertNotIn("DELETE FROM roam_poi;", out)
        self.assertEqual(300, out.count("'loadseed place "))

    def test_geohash_matches_reference_values(self):
        self.assertEqual("u4pruydqqvj", seed.geohash(57.64911, 10.40744, 11))
        self.assertEqual("wtmk", seed.geohash(30.259, 120.15, 4))

    def test_covers_visibility_cases_ties_and_tile_edges(self):
        args = seed.argparse.Namespace(database="cy_local", places=400, members=2, clubs=5, events_per_club=2, deals=5,
                                       sponsored=2, cells_per_member=10, center_lat=30.259, center_lng=120.15,
                                       span_lat=0.06, span_lng=0.07, id_base=9_000_000, seed=1)
        sql = "\n".join(seed.build(args))
        rows = [line for line in sql.splitlines() if "'loadseed place " in line]
        self.assertEqual(400, len(rows))
        status = [line.split(", ")[11] for line in rows]
        self.assertIn("0", status)  # unpublished places
        self.assertTrue(any(", 1, 0, 1, NULL" in line for line in rows))  # pending merchant application (hidden)
        self.assertTrue(any(", 1, 1, 3, NULL" in line for line in rows))  # approved claim (shown)
        self.assertTrue(any(line.split(", ")[3] in ("30.24", "30.26", "30.28") for line in rows))  # tile edge
        points = [tuple(line.split(", ")[3:5]) for line in rows]
        self.assertLess(len(set(points)), len(points))  # places sharing one point (distance ties)


if __name__ == "__main__":
    unittest.main()
