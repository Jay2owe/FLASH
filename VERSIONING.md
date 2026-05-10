# Versioning

This repository uses a simple three-part release number:

- `X.0.0` -> bump `X` for a new feature set or other user-visible capability expansion
- `0.Y.0` -> bump `Y` for a major rework, architecture change, or large optimization within the same feature generation
- `0.0.Z` -> bump `Z` for bug fixes, small tweaks, and narrow optimizations

Apply the rule from the scale of the change, not just from how much code moved.

## Current release history

- `1.0.0` was the first full feature release.
- The large feature wave previously labeled `1.1.0` is treated as `2.0.0` under this policy.
- The pipeline-wide z-slice subset feature promoted the project to `3.0.0`.
- `4.0.0` bundles the Configuration QC setup wizard, the inline filter builder (accordion editor with fork-on-edit and save-as-preset), the ROI orientation workflow, dependency autofix and Maven wrapper hardening, the ImageJ update-site publish workflow, and an output-layout migration with associated late-stage UX tweaks.

## When updating the version

1. Update [pom.xml](./pom.xml).
2. Update any user-facing version stamps that are meant to track the current release.
3. Rebuild the plugin JAR so deployed filenames match the declared version.

If a change includes both a feature and multiple bug fixes, the feature-level bump wins.
