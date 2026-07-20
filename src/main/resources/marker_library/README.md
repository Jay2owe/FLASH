`markers.json` is the source of truth for the bundled marker library.

Edit `markers.json` directly when adding or revising entries. Keep marker names,
aliases, categories, and schema fields consistent; `MarkerLibraryValidationTest`
checks the bundled resource during the Maven test suite.
