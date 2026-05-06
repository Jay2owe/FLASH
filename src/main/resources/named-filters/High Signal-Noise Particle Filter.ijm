// === SIGNAL CLEANUP ===
run("Gaussian Blur...", "sigma=2 stack");
run("Subtract Background...", "rolling=20 stack");
run("Median...", "radius=2 stack");
