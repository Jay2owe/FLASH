# Bundled atlas regions

This folder contains the static atlas-region lookup used for brain-region
autocomplete and exported atlas metadata. FLASH does not call BrainGlobe,
Python, QuPath, or the network at runtime for this feature.

The initial atlas key is `allen_mouse_25um`. The JSON resource was generated
from the Allen Mouse Brain Atlas adult structure graph and stores each region's
id, acronym, display name, and structure id path.
