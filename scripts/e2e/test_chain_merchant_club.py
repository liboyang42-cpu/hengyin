import unittest

from scripts.e2e.chain_merchant_club import (
    MERCHANTS,
    assert_merchant_node_facts,
    merchant_node_payload,
    plan_placeholder_node_bindings,
    select_topic_ticket_template,
)


class TopicTicketTemplateTest(unittest.TestCase):
    def test_reuses_exactly_one_placeholder_node_per_chapter(self):
        chapters = (124, 125, 126, 127)
        rows = tuple((79 + i, chapter_id, None) for i, chapter_id in enumerate(chapters))
        self.assertEqual(
            tuple((79 + i, merchant["member"])
                  for i, merchant in enumerate(MERCHANTS)),
            plan_placeholder_node_bindings(rows, chapters),
        )

        for bad_rows in (
            rows[:3],
            rows + ((99, chapters[0], None),),
            ((79, chapters[0], 9002),) + rows[1:],
        ):
            with self.subTest(rows=bad_rows), self.assertRaises(RuntimeError):
                plan_placeholder_node_bindings(bad_rows, chapters)

    def test_merchant_nodes_require_canonical_store_cluster_facts(self):
        chapters = (124, 125, 126, 127)
        rows = tuple(
            (83 + i, chapter_id, merchant["member"], 1,
             "上海市黄浦区%s%d" % (merchant["store"], i + 1),
             "%.6f" % (121.48 + i * 0.01), "%.6f" % (31.24 + i * 0.01),
             merchant["template"])
            for i, (chapter_id, merchant) in enumerate(zip(chapters, MERCHANTS))
        )
        assert_merchant_node_facts(rows, chapters, require_approved=True)

        payload = merchant_node_payload(chapters[0], MERCHANTS[0], 0)
        self.assertEqual("121.480000", payload["longitude"])
        self.assertEqual("31.240000", payload["latitude"])
        self.assertEqual(6001, payload["templateId"])

        for bad_rows in (
            (rows[0][:5] + (None,) + rows[0][6:],) + rows[1:],
            (rows[0][:3] + (0,) + rows[0][4:],) + rows[1:],
            rows[:3],
        ):
            with self.subTest(rows=bad_rows), self.assertRaises(RuntimeError):
                assert_merchant_node_facts(bad_rows, chapters, require_approved=True)

    def test_reuses_one_topic_ticket_and_marks_legacy_duplicates(self):
        self.assertEqual((None, ()), select_topic_ticket_template(()))
        self.assertEqual((11, ()), select_topic_ticket_template(((11,),)))
        self.assertEqual((11, (12, 13, 14)),
                         select_topic_ticket_template(((11,), (12,), (13,), (14,))))


if __name__ == "__main__":
    unittest.main()
