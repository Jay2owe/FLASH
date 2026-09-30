#@ File(label="Frozen replay manifest", style="file") manifestFile
#@ File(label="Original source project (read only)", style="directory") sourceProject
#@ File(label="EMPTY separate output project", style="directory") outputProject

/* Replay recorded FLASH analyses using FLASH_Pipeline.runReplayCli.
 * Validates exact input/configuration/JAR hashes, copies only declared files
 * to an empty independent project, and writes a receipt even after failure.
 * Analysis options come from original run records, never manuscript guesses.
 * This script excludes figure-generation stages. See adjacent README.md.
 */
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import flash.pipeline.FLASH_Pipeline
import ij.IJ
import java.nio.file.Files
import java.security.MessageDigest

String sha256(File file) {
    def digest = MessageDigest.getInstance('SHA-256')
    file.withInputStream { input ->
        byte[] buffer = new byte[1048576]
        int count
        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count)
    }
    return digest.digest().encodeHex().toString()
}

File within(File root, String relative) {
    if (!relative || new File(relative).isAbsolute()) throw new IllegalArgumentException('Expected a relative file path')
    File file = new File(root, relative).canonicalFile
    if (!file.toPath().startsWith(root.toPath()) || file == root) throw new IllegalArgumentException('File escapes project: ' + relative)
    return file
}

def manifest = new JsonSlurper().parse(manifestFile)
File source = sourceProject.canonicalFile
File output = outputProject.canonicalFile
if (!source.isDirectory() || !output.isDirectory() || output.list().length != 0)
    throw new IllegalArgumentException('Choose an existing source and an empty output folder')
if (source.toPath().startsWith(output.toPath()) || output.toPath().startsWith(source.toPath()))
    throw new IllegalArgumentException('Source and output must be separate, non-overlapping folders')
if (manifest.schema_version != 1 || !manifest.runs || !manifest.files)
    throw new IllegalArgumentException('Incomplete replay manifest')
File jar = new File(FLASH_Pipeline.protectionDomain.codeSource.location.toURI())
if (!jar.isFile() || sha256(jar) != manifest.flash_jar_sha256)
    throw new IllegalStateException('Installed FLASH JAR differs from the captured original')
for (run in manifest.runs) {
    if (run.imagej_version != IJ.getFullVersion() || run.java_version != System.getProperty('java.version'))
        throw new IllegalStateException('ImageJ or Java differs from the recorded environment')
}
long sourceBytes = manifest.files.sum { it.bytes as long } as long
if (output.usableSpace < sourceBytes * 3 + 104857600L)
    throw new IllegalStateException('Insufficient space: allow input copies plus at least twice their size for outputs')
def receipt = [status:'running', manifest_sha256:sha256(manifestFile), flash_jar_sha256:sha256(jar),
               imagej:IJ.getVersion(), java:System.getProperty('java.version'), runs:[],
               claim:'Recorded-run replay; paper-result equality requires a separate numerical comparison']
def writeReceipt = { new File(output, 'paper_replay_receipt.json').text = JsonOutput.prettyPrint(JsonOutput.toJson(receipt)) }
writeReceipt()
try {
    for (entry in manifest.files) {
        File from = within(source, entry.relative_path as String)
        if (!from.isFile() || from.length() != (entry.bytes as long) || sha256(from) != entry.sha256)
            throw new IllegalStateException('Input/configuration differs from captured file: ' + entry.relative_path)
        File to = within(output, entry.relative_path as String)
        to.parentFile.mkdirs()
        Files.copy(from.toPath(), to.toPath())
    }
    for (run in manifest.runs) {
        def p = run.parameters
        // Global runtime choices are recorded separately from the analysis preset.
        String directory = output.absolutePath.replace('\\', '/')
        if (directory =~ /[\[\]"\r\n]/) throw new IllegalArgumentException('Unsupported macro path characters')
        String options = "dir=[${directory}] analysisIndex=${run.analysis_index} headless=true no_aggregate overwrite=auto"
        options += " parallel=${p.parallel} threads=${p.threads} loader_threads=${p.loader_threads} loader_percent=${p.loader_percent} gpu_permits=${p.gpu_permits}"
        options += " tif_cache=${p.tif_cache} verbose=${p.verbose}"
        if (!p.qc_report) options += ' no_qc'
        if (p.containsKey('intensity_v2_use_deconv')) options += " intensityV2.useDeconv=${p.intensity_v2_use_deconv}"
        if (p.containsKey('three_d_use_deconv')) options += " threeD.useDeconv=${p.three_d_use_deconv}"
        if (p.containsKey('split_merge_use_deconv')) options += " splitmerge.useDeconv=${p.split_merge_use_deconv}"
        if (p.containsKey('split_merge_apply_orientation_transforms')) options += " splitmerge.applyOrientationTransforms=${p.split_merge_apply_orientation_transforms}"
        def outcomes = new FLASH_Pipeline().runReplayCli(options, run.run_id as String, p as Map)
        if (outcomes.size() != 1) throw new IllegalStateException('Expected one selected analysis outcome')
        def outcome = outcomes[0]
        receipt.runs << [parent_run_id:run.run_id, run_id:outcome.runId, status:outcome.status,
                         reason:outcome.reason, options:options, record_file:outcome.recordFile?.toString()]
        writeReceipt()
        if (outcome.status != 'ok') throw new IllegalStateException('FLASH replay did not complete cleanly: ' + outcome.reason)
    }
    receipt.status = 'passed'
    println('FLASH recorded API replay complete. Compare CSV measurements with the original reference.')
} catch (Throwable error) {
    receipt.status = 'failed'
    receipt.error = error.toString()
    throw error
} finally {
    writeReceipt()
}
