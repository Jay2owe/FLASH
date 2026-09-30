"""Compare declared FLASH CSV measurements, allowing new run identifiers only."""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path


def table(path, ignored):
    with path.open(encoding='utf-8-sig', newline='') as stream:
        reader = csv.DictReader(stream)
        columns = [name for name in reader.fieldnames if name not in ignored]
        rows = [tuple(row[name] for name in columns) for row in reader]
    # Run order can differ during parallel processing. Compare the complete
    # rows as an unordered collection; retain duplicates rather than deduping.
    return columns, sorted(rows)


def compare(original, replay, *, ignored=('run_id',), rtol=1e-8, atol=1e-10):
    columns, expected = table(Path(original), ignored)
    actual_columns, actual = table(Path(replay), ignored)
    if columns != actual_columns or len(expected) != len(actual):
        raise AssertionError('Columns or row counts differ: ' + str(original))
    for row_number, (left, right) in enumerate(zip(expected, actual), 1):
        for column, a, b in zip(columns, left, right):
            if a == b:
                continue
            try:
                equal = math.isclose(float(a), float(b), rel_tol=rtol, abs_tol=atol)
            except (ValueError, TypeError):
                equal = False
            if not equal:
                raise AssertionError(f'{original}: row {row_number}, {column}: {a!r} != {b!r}')
    return len(actual)


def fingerprint(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--original', type=Path, required=True)
    parser.add_argument('--replay', type=Path, required=True)
    parser.add_argument('--tables', nargs='+', required=True, help='Explicit project-relative CSV paths')
    parser.add_argument('--out', type=Path, required=True, help='New comparison receipt path')
    args = parser.parse_args()
    if args.out.exists():
        parser.error('Existing comparison receipts cannot be overwritten.')
    receipt = {'status':'running', 'ignored_columns':['run_id'], 'rtol':1e-8, 'atol':1e-10, 'tables':[]}
    try:
        for relative in args.tables:
            relative = Path(relative)
            if relative.is_absolute() or '..' in relative.parts:
                raise ValueError('Declare relative CSV paths within both projects.')
            original, replay = args.original / relative, args.replay / relative
            rows = compare(original, replay)
            receipt['tables'].append({'relative_path':relative.as_posix(), 'matched_rows':rows,
                                      'original_sha256':fingerprint(original), 'replay_sha256':fingerprint(replay)})
        receipt['status'] = 'passed'
        print('Matched', sum(item['matched_rows'] for item in receipt['tables']), 'rows across', len(receipt['tables']), 'tables.')
    except BaseException as error:
        receipt['status'] = 'failed'
        receipt['error'] = f'{type(error).__name__}: {error}'
        raise
    finally:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(receipt, indent=2)+'\n', encoding='utf-8')


if __name__ == '__main__':
    main()
