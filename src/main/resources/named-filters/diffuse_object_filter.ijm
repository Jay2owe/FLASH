// Diffuse Object Filter
// =====================
// 3D bandpass filter for detecting objects in diffuse, low signal-to-noise data.
// Uses a Difference of Gaussians (DoG) to isolate object-scale signal from both
// noise (small sigma) and background autofluorescence (large sigma), then applies
// a fixed threshold and 3D Objects Counter for volumetric detection.
//
// Designed for: immunofluorescence z-stacks where staining is faint/diffuse
// (e.g. Caspase-3, low-expressing reporters) and auto-thresholds like Otsu
// give inconsistent results across images.
//
// Input:  Single-channel 16-bit z-stack (extract channel first if multichannel)
// Output: 3D labeled objects map + statistics table with raw intensity measurements
//
// Parameters (adjust for your data):
//   small_xy/z  — preserves objects, removes pixel noise (default: 2/1)
//   big_xy/z    — captures background scale (default: 15/4)
//   threshold   — fixed cutoff on the DoG result (default: 2000)
//   min_voxels  — minimum 3D object size in voxels (default: 100)
//   max_voxels  — maximum 3D object size in voxels (default: 50000)
//
// Sigma values account for XY/Z anisotropy. Defaults calibrated for:
//   0.284 um/px XY, 1.0 um Z-step, ~10 um diameter objects
//   Adjust big_xy proportionally for different resolutions.

// === PARAMETERS ===
small_xy = 2;
small_z = 1;
big_xy = 15;
big_z = 4;
min_voxels = 100;
max_voxels = 50000;
// --------------------

original = getTitle();

// === DIFFERENCE OF GAUSSIANS ===
run("Duplicate...", "title=DoG_small duplicate");
run("Gaussian Blur 3D...", "x=" + small_xy + " y=" + small_xy + " z=" + small_z);

selectImage(original);
run("Duplicate...", "title=DoG_big duplicate");
run("Gaussian Blur 3D...", "x=" + big_xy + " y=" + big_xy + " z=" + big_z);

imageCalculator("Subtract create stack", "DoG_small", "DoG_big");
rename("DoG_result");
close("DoG_small");
close("DoG_big");

// === DENOISE ===
run("Median 3D...", "x=1 y=1 z=1");
