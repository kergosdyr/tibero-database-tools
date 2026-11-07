#!/usr/bin/env python3
"""Check a Plugin Verifier result before submitting a release to Marketplace."""
import argparse
from pathlib import Path
import re
import sys


def check_verifier_report(directory: Path) -> str:
    """Require binary compatibility and no unresolved Internal API usages."""
    verdict_path = directory / 'verification-verdict.txt'
    verdict = verdict_path.read_text().strip()
    if not verdict.startswith('Compatible.'):
        raise ValueError(f'Binary compatibility check failed: {verdict_path}: {verdict or "empty verdict"}')

    internal_path = directory / 'internal-api-usages.txt'
    internal_details = internal_path.read_text().strip() if internal_path.exists() else ''
    internal_count = re.search(r'\b([1-9]\d*) usages? of internal API\b', verdict, re.IGNORECASE)
    if internal_details or internal_count:
        count = internal_count.group(1) if internal_count else 'reported'
        raise ValueError(
            f'Marketplace submission blocked: {count} Internal API usages in {directory}. '
            'Resolve these usages with a supported API or JetBrains before submission. '
            f'See {internal_path if internal_details else verdict_path}'
        )
    return verdict


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path, nargs='+', help='Directory containing verification-verdict.txt')
    args = parser.parse_args()
    failed = False
    for directory in args.report:
        try:
            verdict = check_verifier_report(directory)
        except (OSError, ValueError) as error:
            print(f'FAIL: {error}', file=sys.stderr)
            failed = True
        else:
            print(f'PASS: {directory}: {verdict}')
    return int(failed)


if __name__ == '__main__':
    sys.exit(main())
