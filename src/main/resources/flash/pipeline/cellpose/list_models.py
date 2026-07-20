#!/usr/bin/env python
"""List runnable models from the configured, pinned Cellpose environment.

The user list comes only from Cellpose's authoritative get_user_models API;
files merely present in MODEL_DIR are never advertised as registered models.
"""

from __future__ import print_function

import json
import sys
import traceback
from pathlib import Path

from cellpose import models

try:
    from importlib import metadata as importlib_metadata
except ImportError:  # pragma: no cover - retained for older managed Python
    import importlib_metadata


PROTOCOL_VERSION = 1
MAX_REGISTERED_MODELS = 4096
MAX_MODEL_NAME_CHARACTERS = 512
SUPPORTED_BUILTINS = {
    "cyto3", "cyto2", "cyto", "nuclei", "tissuenet_cp3", "livecell_cp3",
    "yeast_phc_cp3", "yeast_bf_cp3", "bact_phase_cp3", "bact_fluor_cp3",
    "deepbacs_cp3", "cyto2_cp3",
}


def protocol(message):
    print(json.dumps(message, ensure_ascii=False), flush=True)


def installed_cellpose_version():
    try:
        return str(importlib_metadata.version("cellpose")).strip()
    except Exception:
        import cellpose
        return str(getattr(cellpose, "__version__", "unknown")).strip()


def clean_name(value):
    name = str(value).strip()
    if not name or len(name) > MAX_MODEL_NAME_CHARACTERS:
        return None
    if any(ord(character) < 32 or ord(character) == 127 for character in name):
        return None
    return name


def unsupported_name(name):
    folded = name.casefold()
    return folded == "cpsam" or folded.startswith("cpsam_")


def auxiliary_name(name):
    folded = name.casefold()
    return folded.startswith("size_") and folded.endswith(".npy")


def builtin_records():
    raw = getattr(models, "MODEL_NAMES", None)
    if not isinstance(raw, (list, tuple)):
        raise RuntimeError("Pinned Cellpose MODEL_NAMES registry is unavailable.")
    records = []
    seen = set()
    for value in raw:
        name = clean_name(value)
        if (not name or name.casefold() not in SUPPORTED_BUILTINS
                or unsupported_name(name) or auxiliary_name(name)):
            continue
        folded = name.casefold()
        if folded in seen:
            continue
        seen.add(folded)
        records.append({
            "name": name,
            "builtin": True,
            "registered": True,
            "runnable": True,
            "validated_by": "MODEL_NAMES",
        })
    return records, seen


def authoritative_user_names():
    getter = getattr(models, "get_user_models", None)
    if not callable(getter):
        raise RuntimeError("Pinned Cellpose get_user_models API is unavailable.")
    values = getter()
    if values is None:
        return []
    values = list(values)
    if len(values) > MAX_REGISTERED_MODELS:
        raise RuntimeError("Cellpose user registry exceeds the supported model limit.")
    return values


def registered_model_path(name):
    raw = Path(name).expanduser()
    if raw.is_absolute():
        candidate = raw
    else:
        model_dir = getattr(models, "MODEL_DIR", None)
        if model_dir is None:
            return None
        root = Path(str(model_dir)).expanduser().resolve(strict=False)
        candidate = root.joinpath(raw).resolve(strict=False)
        try:
            candidate.relative_to(root)
        except ValueError:
            return None
    if candidate.is_symlink() or not candidate.is_file():
        return None
    return candidate.resolve(strict=True)


def validate_user_model(path):
    # This is the same pinned API used by inference. Construction loads the
    # network weights and rejects cache/diameter files or incompatible models.
    model = models.CellposeModel(gpu=False, pretrained_model=str(path))
    loaded = getattr(model, "pretrained_model", None)
    if not loaded:
        raise RuntimeError("Cellpose did not load model weights.")
    del model


def user_model_records(builtin_names):
    records = []
    rejected = []
    seen = set(builtin_names)
    for value in authoritative_user_names():
        name = clean_name(value)
        if not name:
            rejected.append("<invalid-name>")
            continue
        folded = name.casefold()
        if folded in seen or unsupported_name(name) or auxiliary_name(name):
            rejected.append(name)
            continue
        seen.add(folded)
        path = registered_model_path(name)
        if path is None:
            rejected.append(name)
            continue
        try:
            validate_user_model(path)
        except Exception:
            rejected.append(name)
            continue
        records.append({
            "name": name,
            "builtin": False,
            "registered": True,
            "runnable": True,
            "path": str(path),
            "validated_by": "CellposeModel",
        })
    return records, rejected


def list_models():
    builtins, builtin_names = builtin_records()
    users, rejected = user_model_records(builtin_names)
    records = builtins + users
    if len(records) > MAX_REGISTERED_MODELS:
        raise RuntimeError("Cellpose registry exceeds the supported model limit.")
    return records, rejected


def response_for(req):
    if req.get("list_models") is not True:
        raise ValueError("Unsupported request.")
    if req.get("protocol") != PROTOCOL_VERSION:
        raise ValueError("Unsupported model-discovery protocol.")
    expected_version = str(req.get("supported_version", "")).strip()
    environment_key = str(req.get("environment_key", "")).strip()
    if not expected_version or not environment_key:
        raise ValueError("Model-discovery environment is incomplete.")
    actual_version = installed_cellpose_version()
    if actual_version != expected_version:
        raise RuntimeError(
            "Unsupported Cellpose version: expected %s, found %s."
            % (expected_version, actual_version))
    if actual_version.split(".", 1)[0] != "3":
        raise RuntimeError("FLASH supports only the pinned Cellpose 3 runtime.")
    records, rejected = list_models()
    return {
        "id": req.get("id", "?"),
        "protocol": PROTOCOL_VERSION,
        "success": True,
        "cellpose_version": actual_version,
        "environment_key": environment_key,
        "models": records,
        "rejected": rejected,
    }


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        req = {"id": "?"}
        try:
            req = json.loads(line)
            protocol(response_for(req))
        except Exception as exc:
            protocol({
                "id": req.get("id", "?"),
                "protocol": PROTOCOL_VERSION,
                "success": False,
                "error": str(exc),
                "traceback": traceback.format_exc(),
            })


if __name__ == "__main__":
    main()
