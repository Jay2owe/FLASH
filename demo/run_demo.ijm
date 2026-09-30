// Run this macro in Fiji with FLASH installed. Select an empty output folder.
// Input TIFFs are copied so the supplied dataset remains unchanged.
macroFile = getInfo("macro.filepath");
if (macroFile == "") exit("Open this saved macro in Fiji's editor, then choose Run.");
demoDir = File.getParent(macroFile) + File.separator;
inputDir = demoDir + "data" + File.separator;
outputDir = getDirectory("Choose an EMPTY folder for the FLASH demo outputs");
outputEntries = getFileList(outputDir);
if (outputEntries.length != 0) exit("Choose an empty output folder.");
File.makeDirectory(outputDir + "input");
files = getFileList(inputDir);
for (i = 0; i < files.length; i++) {
    if (endsWith(files[i], ".tif"))
        File.copy(inputDir + files[i], outputDir + "input/" + files[i]);
}
// Explicit no-op filters preserve the known pixel values in this demo.
File.makeDirectory(outputDir + "FLASH");
File.makeDirectory(outputDir + "FLASH/Config");
File.makeDirectory(outputDir + "FLASH/Config/.settings");
File.saveString("// Simulated demo: preserve raw pixels; no filtering.\n",
    outputDir + "FLASH/Config/.settings/C1_Filters.ijm");
File.saveString("// Simulated demo: preserve raw pixels; no filtering.\n",
    outputDir + "FLASH/Config/.settings/C2_Filters.ijm");
// Whole-image intensity, all Z slices, one worker, no model downloads.
options = "dir=[" + outputDir + "] run_intensity headless=true parallel=false threads=1 "
    + "no_aggregate no_qc overwrite=auto channel_names=DAPI,Signal "
    + "channel_colors=Blue,Green filter_presets=Default,Default "
    + "intensity_thresholds=100,100 z_slice_mode=full intensity.preset=roi_mean "
    + "intensity.spatial=false intensityV2.useDeconv=false";
started = getTime();
run("FLASH", options);
print("FLASH simulated demo elapsed seconds: " + (getTime() - started) / 1000);
print("Demo output folder: " + outputDir);
