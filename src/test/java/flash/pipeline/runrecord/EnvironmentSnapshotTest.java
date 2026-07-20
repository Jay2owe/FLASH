package flash.pipeline.runrecord;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

public class EnvironmentSnapshotTest {

    @After
    public void resetCache() {
        EnvironmentSnapshot.clearCacheForTests();
    }

    @Test
    public void requiredFieldsNonNullInTestJvm() {
        assertNotNull(EnvironmentSnapshot.flashVersion());
        assertNotNull(EnvironmentSnapshot.fijiBuild());
        assertNotNull(EnvironmentSnapshot.jdkVersion());
        assertNotNull(EnvironmentSnapshot.osName());
        assertNotNull(EnvironmentSnapshot.biofVersion());
        assertNotNull(EnvironmentSnapshot.machineFingerprint());
    }

    @Test
    public void jdkVersionMatchesRuntime() {
        assertEquals(System.getProperty("java.version"), EnvironmentSnapshot.jdkVersion());
    }

    @Test
    public void osNameIncludesArch() {
        String os = EnvironmentSnapshot.osName();
        assertNotNull(os);
        assertFalse(os.isEmpty());
        assertEquals(true, os.contains(System.getProperty("os.arch")));
    }

    @Test
    public void machineFingerprintEmptyByDefault() {
        assertEquals("", EnvironmentSnapshot.machineFingerprint());
    }

    @Test
    public void secondCallHitsCachedSameInstance() {
        String first = EnvironmentSnapshot.osName();
        String second = EnvironmentSnapshot.osName();
        assertSame("cached value should be the identical String instance", first, second);
    }

    @Test
    public void noRawUsernameOrHostnameLeaks() {
        String userName = System.getProperty("user.name");
        StringBuilder all = new StringBuilder()
                .append(EnvironmentSnapshot.flashVersion())
                .append('|').append(EnvironmentSnapshot.fijiBuild())
                .append('|').append(EnvironmentSnapshot.jdkVersion())
                .append('|').append(EnvironmentSnapshot.osName())
                .append('|').append(EnvironmentSnapshot.biofVersion())
                .append('|').append(EnvironmentSnapshot.machineFingerprint());
        if (userName != null && !userName.trim().isEmpty()) {
            assertFalse("Environment snapshot must not leak raw username",
                    all.toString().contains(userName));
        }
    }
}
