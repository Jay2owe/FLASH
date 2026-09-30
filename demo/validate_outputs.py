"""Check all 120 demo intensity measurements against the supplied pixel truth.

Run with Python 3 (standard library only):
    python demo/validate_outputs.py /path/to/demo-output
Reads outputs without changing them. Fails on missing/duplicated rows or a
changed intensity. The full-image area is 4096 square micrometres (1 um pixels),
so integrated density equals the sum of the original slice's pixel values.
"""
import argparse
import csv
import math
from pathlib import Path


def validate(output, truth):
    with truth.open(encoding="utf-8", newline="") as handle:
        expected_rows = list(csv.DictReader(handle))
    expected = {(row["Channel"], row["AnimalName"], int(row["Slice"])): int(row["PixelSum"])
                for row in expected_rows}
    if len(expected) != 120:
        raise AssertionError("The supplied pixel truth must contain 120 distinct measurements.")
    for channel in ("DAPI", "Signal"):
        path = output / "FLASH/Results/Tables/Intensity" / f"{channel}.csv"
        with path.open(encoding="utf-8-sig", newline="") as handle:
            rows = list(csv.DictReader(handle))
        if len(rows) != 60:
            raise AssertionError(f"{channel}: expected 60 rows, got {len(rows)}")
        seen = set()
        for row in rows:
            animal = row["Animal Name"].removeprefix("Simulated-")
            key = (channel, animal, int(float(row["z"])))
            if key in seen:
                raise AssertionError(f"Duplicate measurement: {key}")
            seen.add(key)
            for column in ("IntDen", "IntDen_Unfiltered"):
                if not math.isclose(float(row[column]), expected[key], rel_tol=0, abs_tol=0.01):
                    raise AssertionError(f"{key}: {column} differs from the pixel truth")
        if seen != {key for key in expected if key[0] == channel}:
            raise AssertionError(f"{channel}: some expected measurements are missing")
        print(f"{channel}: PASS (60 rows; all integrated intensities match pixel truth)")
    print("FLASH demo passed: all 120 slice/channel measurements match.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="Output folder selected in the Fiji demo")
    parser.add_argument("--truth", type=Path, default=Path(__file__).resolve().parent / "data/pixel_truth.csv")
    args = parser.parse_args()
    validate(args.output.resolve(), args.truth.resolve())
