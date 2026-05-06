package flash.pipeline.image;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Detects NVIDIA GPU VRAM via {@code nvidia-smi} and converts it to a permit
 * count for {@link GpuConcurrency}.
 * <p>
 * Returns {@code 0} when no CUDA GPU is present (command missing, non-zero exit,
 * timeout, or parse failure). A return of {@code 0} means "no NVIDIA constraint"
 * to the permit composition logic, which then falls back to CPU-side budgets.
 * <p>
 * Why {@code nvidia-smi} over JNA/jcuda/WMI: ubiquitous on any box with NVIDIA
 * drivers installed, no native dependency we have to ship, portable across
 * Windows/Linux/macOS. See {@code docs/GPU_CONCURRENCY/PLAN_1_AUTO_DETECT_AND_UI.md}.
 */
public final class GpuProbe {

    /** Reserve for CUDA runtime + driver + display compositor (MiB). */
    private static final int VRAM_RESERVE_MIB = 512;

    /** Per-inference VRAM footprint estimate (MiB).
     *  Measured on a 2048x2048x13-slice 16-bit stack with StarDist 2D-to-3D
     *  inference. Err high — undersized is safer than oversized. */
    private static final int VRAM_PER_WORKER_MIB = 4096;

    /** Hard ceiling: no single card should ever drive more than this many
     *  simultaneous inferences regardless of VRAM headroom. */
    private static final int VRAM_PERMITS_MAX = 8;

    private GpuProbe() {}

    /**
     * Returns the VRAM-based permit budget for the first NVIDIA GPU visible to
     * {@code nvidia-smi}. Returns {@code 0} if no NVIDIA GPU is present.
     */
    public static int probeNvidiaVramPermits() {
        int totalMiB = probeNvidiaVramMiB();
        if (totalMiB <= 0) return 0;
        int permits = (totalMiB - VRAM_RESERVE_MIB) / VRAM_PER_WORKER_MIB;
        if (permits < 1) permits = 1;
        if (permits > VRAM_PERMITS_MAX) permits = VRAM_PERMITS_MAX;
        return permits;
    }

    /**
     * Raw VRAM probe in MiB. {@code 0} on any failure.
     */
    public static int probeNvidiaVramMiB() {
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "nvidia-smi",
                    "--query-gpu=memory.total",
                    "--format=csv,noheader,nounits");
            pb.redirectErrorStream(false);
            proc = pb.start();

            // Read first line of stdout on a background thread so the process
            // can't deadlock on a full pipe while we wait.
            final Process fproc = proc;
            final String[] firstLine = new String[1];
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(fproc.getInputStream(), StandardCharsets.UTF_8))) {
                    firstLine[0] = br.readLine();
                } catch (Exception ignored) { }
            }, "ihf-nvidia-smi-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = proc.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return 0;
            }
            reader.join(500);
            if (proc.exitValue() != 0) return 0;

            String line = firstLine[0];
            if (line == null) return 0;
            line = line.trim();
            if (line.isEmpty()) return 0;
            return Integer.parseInt(line);
        } catch (Exception e) {
            return 0;
        } finally {
            if (proc != null) {
                try { proc.destroy(); } catch (Exception ignored) { }
            }
        }
    }
}
