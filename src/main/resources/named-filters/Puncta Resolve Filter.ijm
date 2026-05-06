// Edge+Density Hybrid with Gaussian 2D post-processing
// Best pipeline so far: 193 spots/slice matches 195 manual ground truth
//
// Input: active 16-bit Z-stack
// Output: 8-bit combined image ready for TrackMate-StarDist

original = getTitle();

// === DENSITY PATH ===
run("Duplicate...", "title=_dens duplicate");
run("Subtract Background...", "rolling=50 stack");
run("Gaussian Blur 3D...", "x=2 y=2 z=1");
run("8-bit");
run("Auto Local Threshold", "method=Bernsen radius=15 parameter_1=0 parameter_2=0 white stack");
run("32-bit");
run("Divide...", "value=255 stack");
run("Gaussian Blur 3D...", "x=8 y=8 z=2");
run("Enhance Contrast...", "saturated=1.0 normalize process_all");
run("8-bit");
rename("_density");

// === EDGE PATH (from raw) ===
selectWindow(original);
run("Duplicate...", "title=_edge duplicate");
run("8-bit");
run("Auto Local Threshold", "method=Bernsen radius=15 parameter_1=0 parameter_2=0 white stack");
run("32-bit");
run("Gaussian Blur 3D...", "x=2 y=2 z=1");
run("Variance...", "radius=5 stack");
run("Enhance Contrast...", "saturated=1.0 normalize process_all");
run("8-bit");

// === COMBINE + POST-PROCESS ===
imageCalculator("Add create stack", "_edge", "_density");
close("_density");
close("_edge");

// Gaussian 2D (per-slice, no Z-blurring) + Minimum 3D r=4
run("Gaussian Blur...", "sigma=3 stack");
run("Minimum 3D...", "x=4 y=4 z=1");
