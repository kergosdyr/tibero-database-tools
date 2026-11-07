from pathlib import Path
import tempfile
import unittest

from verifier_report import check_verifier_report, main
from contextlib import redirect_stderr, redirect_stdout
from io import StringIO
from unittest.mock import patch


class VerifierReportTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def report(self, verdict, internal=None):
        (self.directory / 'verification-verdict.txt').write_text(verdict)
        if internal is not None:
            (self.directory / 'internal-api-usages.txt').write_text(internal)
        return self.directory

    def test_compatible_report_without_internal_usages_passes(self):
        for internal in [None, '', ' \n']:
            with self.subTest(internal=internal):
                self.assertEqual('Compatible.', check_verifier_report(self.report('Compatible.\n', internal)))

    def test_experimental_and_deprecated_warnings_do_not_imply_internal_usage(self):
        verdict = 'Compatible. 3 usages of deprecated API. 8 usages of experimental API.'
        self.assertEqual(verdict, check_verifier_report(self.report(verdict)))

    def test_internal_count_blocks_even_when_details_are_missing(self):
        for count, noun in [(1, 'usage'), (9, 'usages')]:
            with self.subTest(count=count):
                verdict = f'Compatible. {count} {noun} of internal API'
                with self.assertRaisesRegex(ValueError, 'Marketplace submission blocked'):
                    check_verifier_report(self.report(verdict))

    def test_internal_details_block_even_when_verdict_omits_count(self):
        with self.assertRaisesRegex(ValueError, 'Marketplace submission blocked'):
            check_verifier_report(self.report('Compatible.', 'Internal interface DBIntrospector.Factory is referenced.'))

    def test_binary_errors_and_incomplete_results_block(self):
        for verdict in ['Not compatible.', 'Plugin is invalid.', '', 'Verification failed.']:
            with self.subTest(verdict=verdict):
                with self.assertRaisesRegex(ValueError, 'Binary compatibility check failed'):
                    check_verifier_report(self.report(verdict))

    def test_missing_verdict_blocks(self):
        with self.assertRaises(FileNotFoundError):
            check_verifier_report(self.directory)

    def test_cli_reports_all_targets_and_returns_failure(self):
        blocked = self.report('Compatible. 9 usages of internal API')
        compatible = self.directory / 'second-ide'
        compatible.mkdir()
        (compatible / 'verification-verdict.txt').write_text('Compatible.')
        output, errors = StringIO(), StringIO()
        with patch('sys.argv', ['verifier_report.py', str(blocked), str(compatible)]), redirect_stdout(output), redirect_stderr(errors):
            self.assertEqual(1, main())
        self.assertIn('Marketplace submission blocked', errors.getvalue())
        self.assertIn(f'PASS: {compatible}', output.getvalue())


if __name__ == '__main__':
    unittest.main()
