#!/usr/bin/env python
"""Persistent Cellpose worker for FLASH Parameter Variations.

Usage:
  cellpose_loop.py <model> <image_tif> <out_dir> [--gpu]
      [--has-second-channel] [--channel-axis N]
      [--do-3d] [--z-axis N] [--anisotropy X]

Reads JSON-line param sets from stdin:
  {"id": "v01", "diameter": 30.0, "flow_threshold": 0.4,
   "cellprob_threshold": 0.0}

Writes one JSON line to stdout per request:
  {"id": "v01", "mask_path": "<out_dir>/v01_cp_masks.tif",
   "duration_ms": 4823}
  or {"id": "v01", "error": "..."}
"""

from __future__ import print_function

import argparse
import contextlib
import json
import sys
import time
import traceback
from pathlib import Path

from cellpose import io, models


def parse_args(argv):
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("model")
    parser.add_argument("image_tif")
    parser.add_argument("out_dir")
    parser.add_argument("--gpu", action="store_true")
    parser.add_argument("--has-second-channel", action="store_true")
    parser.add_argument("--channel-axis", type=int, default=None)
    parser.add_argument("--do-3d", action="store_true")
    parser.add_argument("--z-axis", type=int, default=None)
    parser.add_argument("--anisotropy", type=float, default=None)
    return parser.parse_args(argv)


def protocol(message):
    print(json.dumps(message), flush=True)


def eval_kwargs(args, req):
    kwargs = {
        "diameter": float(req["diameter"]),
        "flow_threshold": float(req["flow_threshold"]),
        "cellprob_threshold": float(req["cellprob_threshold"]),
    }
    if args.has_second_channel:
        kwargs["channels"] = [1, 2]
        kwargs["channel_axis"] = args.channel_axis
    else:
        kwargs["channels"] = [0, 0]
    if args.do_3d:
        kwargs["do_3D"] = True
        kwargs["z_axis"] = args.z_axis
        if args.anisotropy is not None:
            kwargs["anisotropy"] = args.anisotropy
    return kwargs


def save_masks(path, masks):
    try:
        import numpy as np

        masks = np.asarray(masks)
        if masks.dtype.kind in ("i", "u") and masks.max(initial=0) <= 65535:
            masks = masks.astype("uint16", copy=False)
    except Exception:
        pass
    io.imsave(str(path), masks)


def save_cellprob(path, cellprob):
    import numpy as np
    import tifffile

    tifffile.imwrite(str(path), np.asarray(cellprob, dtype=np.float32))


def main(argv):
    args = parse_args(argv)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    with contextlib.redirect_stdout(sys.stderr):
        model = models.CellposeModel(gpu=args.gpu, pretrained_model=args.model)
        img = io.imread(args.image_tif)
    protocol({"ready": True})

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        req = {"id": "?"}
        try:
            req = json.loads(line)
            t0 = time.time()
            with contextlib.redirect_stdout(sys.stderr):
                result = model.eval(img, **eval_kwargs(args, req))
            masks = result[0] if isinstance(result, tuple) else result
            mask_path = out_dir / ("%s_cp_masks.tif" % req["id"])
            save_masks(mask_path, masks)
            response = {
                "id": req["id"],
                "mask_path": str(mask_path),
                "duration_ms": int((time.time() - t0) * 1000),
            }
            if req.get("dump_cellprob", False):
                if not isinstance(result, tuple) or len(result) < 2 or len(result[1]) < 3:
                    raise RuntimeError("Cellpose did not return a cellprob flow map.")
                cellprob_path = out_dir / ("%s_cellprob.tif" % req["id"])
                save_cellprob(cellprob_path, result[1][2])
                response["cellprob_path"] = str(cellprob_path)
            protocol(response)
        except Exception as exc:
            protocol({
                "id": req.get("id", "?"),
                "error": str(exc),
                "traceback": traceback.format_exc(),
            })


if __name__ == "__main__":
    main(sys.argv[1:])
