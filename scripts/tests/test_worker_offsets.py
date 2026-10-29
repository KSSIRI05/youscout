import pathlib
import runpy
import unittest

offsets_caught_up = runpy.run_path(str(pathlib.Path(__file__).resolve().parents[1] / "smoke-worker.py"))["offsets_caught_up"]


class WorkerOffsetsTest(unittest.TestCase):
    def output(self, first="1 1 0", empty="- 0 -"):
        return "\n".join(f"group video.uploaded.v1 {n} {first if n == 0 else empty} client /host client-id" for n in range(3))

    def test_empty_uncommitted_partitions_are_caught_up(self):
        self.assertTrue(offsets_caught_up(self.output()))

    def test_nonempty_uncommitted_partition_is_not_caught_up(self):
        self.assertFalse(offsets_caught_up(self.output(empty="- 1 -")))

    def test_lag_and_incomplete_assignment_are_not_caught_up(self):
        self.assertFalse(offsets_caught_up(self.output(first="1 2 1")))
        self.assertFalse(offsets_caught_up(self.output().splitlines()[0]))

    def test_all_committed_partitions_are_caught_up(self):
        self.assertTrue(offsets_caught_up(self.output(empty="0 0 0")))
