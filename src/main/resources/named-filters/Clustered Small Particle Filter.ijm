// === SMALL CLUSTER SEPARATION ===
run("Gaussian Blur...", "sigma=1 stack");
run("Subtract Background...", "rolling=10 stack");
run("Median...", "radius=1 stack");
