package flash.pipeline.runtime;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ImageJRestartHelperTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private String originalOsName;

    @Before
    public void rememberOsName() {
        originalOsName = System.getProperty("os.name");
    }

    @After
    public void restoreOsName() {
        if (originalOsName == null) {
            System.clearProperty("os.name");
        } else {
            System.setProperty("os.name", originalOsName);
        }
    }

    @Test
    public void windowsLauncherResolutionFindsImageJWin64Executable() throws Exception {
        System.setProperty("os.name", "Windows 11");
        File fijiDir = temp.newFolder("Fiji.app");
        File launcher = new File(fijiDir, "ImageJ-win64.exe");
        assertTrue(launcher.createNewFile());

        ImageJRestartHelper.LaunchSpec spec = ImageJRestartHelper.resolveLaunchSpec(fijiDir);

        assertNotNull(spec);
        assertEquals(launcher.getAbsolutePath(), spec.getLaunchTarget().getAbsolutePath());
        assertEquals(ImageJRestartHelper.LaunchSpec.MODE_EXECUTABLE, spec.getMode());
    }

    @Test
    public void macLauncherResolutionUsesFijiAppOpenMode() throws Exception {
        System.setProperty("os.name", "Mac OS X");
        File fijiDir = temp.newFolder("Fiji.app");
        assertTrue(new File(fijiDir, "Contents").mkdirs());

        ImageJRestartHelper.LaunchSpec spec = ImageJRestartHelper.resolveLaunchSpec(fijiDir);

        assertNotNull(spec);
        assertEquals(fijiDir.getAbsolutePath(), spec.getLaunchTarget().getAbsolutePath());
        assertEquals(ImageJRestartHelper.LaunchSpec.MODE_MAC_APP, spec.getMode());
    }

    @Test
    public void windowsRestartScriptEscapesLauncherPathBeforeColon() throws Exception {
        File script = temp.newFile("restart.ps1");

        ImageJRestartHelper.writeWindowsRestartScript(script);

        String text = new String(Files.readAllBytes(script.toPath()), StandardCharsets.UTF_8);
        assertFalse("PowerShell treats $LauncherPath: as an invalid scoped variable",
                text.contains("$LauncherPath:"));
        assertTrue(text.contains("${LauncherPath}"));
        assertTrue(text.contains("does not evaluate command text"));
        assertFalse(text.contains("Invoke-Expression"));
        assertFalse(text.contains("iex "));
    }

    @Test
    public void windowsRestartCommandStaysHiddenWithoutExecutionPolicyBypass() throws Exception {
        System.setProperty("os.name", "Windows 11");
        File fijiDir = temp.newFolder("windows-fiji");
        File launcher = new File(fijiDir, "ImageJ-win64.exe");
        assertTrue(launcher.createNewFile());
        File script = temp.newFile("FLASH-restart-imagej-helper-test.ps1");
        File log = temp.newFile("FLASH-restart-imagej.log");
        ImageJRestartHelper.LaunchSpec spec =
                new ImageJRestartHelper.LaunchSpec(launcher, fijiDir,
                        ImageJRestartHelper.LaunchSpec.MODE_EXECUTABLE);

        List<String> command = ImageJRestartHelper.windowsCommand(script, spec, log);
        String joined = join(command);

        assertTrue(joined.contains("-WindowStyle Hidden"));
        assertTrue(joined.contains("-NonInteractive"));
        assertFalse(joined.contains("-ExecutionPolicy"));
        assertFalse(joined.contains("Bypass"));
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(value);
        }
        return sb.toString();
    }
}
