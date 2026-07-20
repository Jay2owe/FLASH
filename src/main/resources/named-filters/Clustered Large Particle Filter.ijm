// === LARGE CLUSTER SEPARATION ===
run("Gaussian Blur...", "sigma=3 stack");
run("Subtract Background...", "rolling=40 stack");
run("Median...", "radius=3 stack");
