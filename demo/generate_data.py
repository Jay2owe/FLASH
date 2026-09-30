"""Recreate the small, artificial FLASH dataset (requires numpy and tifffile).

Writes twelve calibrated, two-channel ImageJ TIFF stacks and per-slice pixel
truth to --output (default: data beside this script). The seed, dimensions,
calibration and signal levels are explicit. Existing files are not overwritten.
These inputs demonstrate software operation; they contain no biological data.
"""
import argparse
import csv
from pathlib import Path

import numpy as np
import tifffile

SEED = 20260930
SHAPE = (5, 2, 64, 64)  # Z, channel, Y, X; one time point


def generate(output):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise FileExistsError(f"Use an empty output directory: {output}")
    rng = np.random.default_rng(SEED)
    z, y, x = np.indices((SHAPE[0], SHAPE[2], SHAPE[3]))
    mask = np.zeros(z.shape, dtype=bool)
    for cy in (16, 32, 48):
        for cx in (16, 32, 48):
            mask |= ((x - cx) / 4) ** 2 + ((y - cy) / 4) ** 2 + ((z - 2) / 2) ** 2 <= 1
    rows = []
    for group in ("A", "B"):
        for subject in range(1, 7):
            animal = f"{group}{subject:02d}"
            name = f"Simulated-{animal}_LH_Demo"
            stack = rng.integers(20, 26, size=SHAPE, dtype=np.uint16)
            stack[:, 0] += mask.astype(np.uint16) * 500
            stack[:, 1] += mask.astype(np.uint16) * (300 + 10 * subject + (200 if group == "B" else 0))
            tifffile.imwrite(
                output / f"{name}.tif", stack, imagej=True,
                resolution=(1.0, 1.0),
                metadata={"axes": "ZCYX", "spacing": 2.0, "unit": "um"},
            )
            for channel, marker in enumerate(("DAPI", "Signal")):
                for slice_index in range(SHAPE[0]):
                    plane = stack[slice_index, channel]
                    rows.append({"Image": name, "AnimalName": animal, "Condition": group,
                                 "Channel": marker, "Slice": slice_index + 1,
                                 "PixelSum": int(plane.sum()), "PixelMean": float(plane.mean()),
                                 "PositivePixelsAbove100": int((plane > 100).sum())})
    with (output / "pixel_truth.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print(f"Created 12 simulated TIFF stacks and pixel_truth.csv in {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parent / "data")
    generate(parser.parse_args().output)
