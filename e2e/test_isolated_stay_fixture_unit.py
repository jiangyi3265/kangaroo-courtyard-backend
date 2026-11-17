"""Pure local guard/lifecycle checks; no HTTP or SQL is executed."""
import sys
import unittest
from unittest.mock import patch

from isolated_stay_fixture import IsolatedStayFixture, cleanup_predicate


class StayFixtureUnitTest(unittest.TestCase):
    def test_cleanup_uses_exact_known_entity_ids(self):
        clause = cleanup_predicate({'id', 'store_id', 'stay_id'},
                                   {'store_id': [17, 17], 'stay_id': [], 'app_user_id': [19]})
        self.assertEqual('`store_id` in (17)', clause)
        self.assertEqual('', cleanup_predicate({'id', 'title'}, {'store_id': [17]}))

    def test_cleanup_rejects_non_numeric_identifiers(self):
        with self.assertRaises(ValueError):
            cleanup_predicate({'store_id'}, {'store_id': ['1) or 1=1']})

    def test_finally_cleans_on_business_failure_and_does_not_swallow_it(self):
        fixture = IsolatedStayFixture('unit')
        with patch.object(fixture, 'cleanup') as cleanup:
            self.assertFalse(fixture.__exit__(AssertionError, AssertionError('failed'), None))
            cleanup.assert_called_once_with()

    def test_missing_or_production_database_is_rejected_before_io(self):
        for database in ('', 'dkxy', 'dkxy_production', 'acceptance_bad-name'):
            with self.subTest(database=database), patch.dict('os.environ', {
                'KG_BASE': 'http://localhost:8180', 'KG_DB': database}), \
                    patch('isolated_stay_fixture.flow.admin_login') as login:
                with self.assertRaises(AssertionError):
                    IsolatedStayFixture('unit').__enter__()
                login.assert_not_called()

    def test_remote_api_is_rejected_before_io(self):
        with patch.dict('os.environ', {'KG_BASE': 'https://production.example', 'KG_DB': 'dkxy_acceptance_unit'}), \
                patch('isolated_stay_fixture.flow.admin_login') as login:
            with self.assertRaises(AssertionError):
                IsolatedStayFixture('unit').__enter__()
            login.assert_not_called()


if __name__ == '__main__':
    unittest.main()
