#!/usr/bin/env python
"""List Cellpose models registered in the configured Cellpose environment.

Reads JSON-line requests from stdin:
  {"id": "...", "list_models": true}

Writes one JSON line per request:
  {"id": "...", "models": [{"name": "cyto3", "builtin": true}, ...]}
"""

from __future__ import print_function

import json
import sys
import traceback
from pathlib import Path

from cellpose import models


def protocol(message):
    print(json.dumps(message), flush=True)


def builtin_names():
    raw = getattr(models, "MODEL_NAMES", [])
    return [str(name) for name in raw if str(name).strip()]


def user_model_records(builtins):
    model_dir = getattr(models, "MODEL_DIR", None)
    if not model_dir:
        return []
    root = Path(str(model_dir)).expanduser()
    if not root.is_dir():
        return []

    builtin_lower = set(name.lower() for name in builtins)
    records = []
    seen = set()
    for path in sorted(root.iterdir(), key=lambda p: p.name.lower()):
        if not path.is_file() or path.name.startswith("."):
            continue
        name = path.name
        if name.lower() in builtin_lower or name.lower() in seen:
            continue
        seen.add(name.lower())
        records.append({
            "name": name,
            "builtin": False,
            "path": str(path),
        })
    return records


def list_models():
    builtins = builtin_names()
    records = []
    for name in sorted(builtins, key=lambda n: n.lower()):
        records.append({"name": name, "builtin": True})
    records.extend(user_model_records(builtins))
    return records


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        req = {"id": "?"}
        try:
            req = json.loads(line)
            if req.get("list_models") is True:
                protocol({"id": req.get("id", "?"), "models": list_models()})
            else:
                protocol({"id": req.get("id", "?"), "error": "Unsupported request."})
        except Exception as exc:
            protocol({
                "id": req.get("id", "?"),
                "error": str(exc),
                "traceback": traceback.format_exc(),
            })


if __name__ == "__main__":
    main()
