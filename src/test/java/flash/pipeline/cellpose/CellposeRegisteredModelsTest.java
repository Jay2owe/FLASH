package flash.pipeline.cellpose;

import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CellposeRegisteredModelsTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @After
    public void tearDown() {
        CellposeRegisteredModels.resetTestHooks();
    }

    @Test
    public void parsesValidatedResponseIntoCatalogEntries() throws Exception {
        Path customPath = File.createTempFile("microglia_cp", ".pth",
                temp.getRoot()).toPath();
        Map<String, Object> response = response("environment-a", Arrays.asList(
                model("cyto3", true, null, true, true),
                model("microglia_cp", false, customPath.toString(), true, true)));

        List<ModelEntry> entries = CellposeRegisteredModels.entriesFromResponse(
                response, Collections.<String>emptyList());

        ModelEntry builtin = find(entries, "cellpose_cyto3");
        assertEquals(ModelEntry.Engine.CELLPOSE, builtin.engine);
        assertEquals(ModelEntry.Source.STOCK_BUILTIN, builtin.source);
        assertEquals("cyto3", builtin.pretrainedModel.get());

        ModelEntry custom = find(entries, "microglia_cp");
        assertEquals(ModelEntry.Source.USER_IMPORTED, custom.source);
        assertEquals(customPath.toAbsolutePath().normalize().toString(),
                custom.filePath.get());
        assertEquals("microglia_cp", custom.pretrainedModel.get());
        assertTrue(CellposeRegisteredModels.isValidatedDiscoveredCellposeEntry(custom));
    }

    @Test
    public void filtersAuxiliaryUnregisteredUnsupportedUnrunnableAndCaseDuplicates()
            throws Exception {
        Path customPath = temp.newFile("microglia_cp").toPath();
        Path unregistered = temp.newFile("unregistered_cp").toPath();
        List<Map<String, Object>> models = new ArrayList<Map<String, Object>>();
        models.add(model("cyto3", true, null, true, true));
        models.add(model("CYTO3", true, null, true, true));
        models.add(model("CPx", true, null, true, true));
        models.add(model("size_cyto3.npy", false, customPath.toString(), true, true));
        models.add(model("downloaded_stock", false, customPath.toString(), false, true));
        models.add(model("unregistered_cp", false, unregistered.toString(), false, true));
        models.add(model("cpsam", false, customPath.toString(), true, true));
        models.add(model("broken_cp", false, customPath.toString(), true, false));
        models.add(model("microglia_cp", false, customPath.toString(), true, true));
        models.add(model("MICROGLIA_CP", false, customPath.toString(), true, true));

        List<ModelEntry> entries = CellposeRegisteredModels.entriesFromResponse(
                response("environment-a", models), Collections.<String>emptyList());

        assertEquals(2, entries.size());
        assertEquals("cellpose_cyto3", entries.get(0).modelKey);
        assertEquals("microglia_cp", entries.get(1).modelKey);
    }

    @Test
    public void existingModelKeysAreComparedCaseInsensitively() throws Exception {
        Map<String, Object> response = response("environment-a", Arrays.asList(
                model("cyto3", true, null, true, true)));

        List<ModelEntry> entries = CellposeRegisteredModels.entriesFromResponse(
                response, Arrays.asList("CELLPOSE_CYTO3"));

        assertTrue(entries.isEmpty());
    }

    @Test
    public void unavailableFirstConfiguredSecondRetriesInSameJvm() throws Exception {
        final AtomicReference<CellposeRegisteredModels.DiscoveryEnvironment> environment =
                new AtomicReference<CellposeRegisteredModels.DiscoveryEnvironment>();
        final AtomicInteger backendCalls = new AtomicInteger();
        final Path customPath = temp.newFile("configured_model").toPath();
        CellposeRegisteredModels.setEnvironmentProviderForTests(
                new CellposeRegisteredModels.EnvironmentProvider() {
                    @Override public CellposeRegisteredModels.DiscoveryEnvironment current()
                            throws Exception {
                        CellposeRegisteredModels.DiscoveryEnvironment value = environment.get();
                        if (value == null) {
                            throw new IllegalStateException("not configured");
                        }
                        return value;
                    }
                });
        CellposeRegisteredModels.setDiscoveryBackendForTests(
                new CellposeRegisteredModels.DiscoveryBackend() {
                    @Override public Map<String, Object> discover(
                            CellposeRegisteredModels.DiscoveryEnvironment current,
                            long timeout,
                            TimeUnit unit) {
                        backendCalls.incrementAndGet();
                        return response(current.cacheKey, Arrays.asList(
                                model("configured_model", false,
                                        customPath.toString(), true, true)));
                    }
                });

        assertTrue(CellposeRegisteredModels.fetch(Collections.<String>emptyList()).isEmpty());
        environment.set(environment("configured-environment"));

        assertEquals(1, CellposeRegisteredModels.fetch(
                Collections.<String>emptyList()).size());
        assertEquals(1, backendCalls.get());
    }

    @Test
    public void transientFailureUsesShortRetryAndCachesOnlySuccessfulSnapshot()
            throws Exception {
        final AtomicLong now = new AtomicLong(1_000L);
        final AtomicInteger backendCalls = new AtomicInteger();
        final Path customPath = temp.newFile("retry_model").toPath();
        final CellposeRegisteredModels.DiscoveryEnvironment environment =
                environment("retry-environment");
        CellposeRegisteredModels.setClockForTests(new java.util.function.LongSupplier() {
            @Override public long getAsLong() {
                return now.get();
            }
        });
        CellposeRegisteredModels.setEnvironmentProviderForTests(fixed(environment));
        CellposeRegisteredModels.setDiscoveryBackendForTests(
                new CellposeRegisteredModels.DiscoveryBackend() {
                    @Override public Map<String, Object> discover(
                            CellposeRegisteredModels.DiscoveryEnvironment current,
                            long timeout,
                            TimeUnit unit) throws Exception {
                        if (backendCalls.incrementAndGet() == 1) {
                            throw new IllegalStateException("transient failure");
                        }
                        return response(current.cacheKey, Arrays.asList(
                                model("retry_model", false,
                                        customPath.toString(), true, true)));
                    }
                });

        assertTrue(CellposeRegisteredModels.fetch(Collections.<String>emptyList()).isEmpty());
        assertTrue(CellposeRegisteredModels.fetch(Collections.<String>emptyList()).isEmpty());
        assertEquals(1, backendCalls.get());

        now.addAndGet(CellposeRegisteredModels.FAILURE_RETRY_MS + 1L);
        assertEquals(1, CellposeRegisteredModels.fetch(
                Collections.<String>emptyList()).size());
        assertEquals(2, backendCalls.get());
        assertEquals(1, CellposeRegisteredModels.fetch(
                Collections.<String>emptyList()).size());
        assertEquals("Successful result should be cached", 2, backendCalls.get());
    }

    @Test
    public void successExpiresAndEnvironmentChangeBypassesOldSnapshot() throws Exception {
        final AtomicLong now = new AtomicLong(10_000L);
        final AtomicInteger backendCalls = new AtomicInteger();
        final AtomicReference<CellposeRegisteredModels.DiscoveryEnvironment> environment =
                new AtomicReference<CellposeRegisteredModels.DiscoveryEnvironment>(
                        environment("environment-one"));
        final Path customPath = temp.newFile("environment_model").toPath();
        CellposeRegisteredModels.setClockForTests(new java.util.function.LongSupplier() {
            @Override public long getAsLong() {
                return now.get();
            }
        });
        CellposeRegisteredModels.setEnvironmentProviderForTests(
                new CellposeRegisteredModels.EnvironmentProvider() {
                    @Override public CellposeRegisteredModels.DiscoveryEnvironment current() {
                        return environment.get();
                    }
                });
        CellposeRegisteredModels.setDiscoveryBackendForTests(
                new CellposeRegisteredModels.DiscoveryBackend() {
                    @Override public Map<String, Object> discover(
                            CellposeRegisteredModels.DiscoveryEnvironment current,
                            long timeout,
                            TimeUnit unit) {
                        backendCalls.incrementAndGet();
                        return response(current.cacheKey, Arrays.asList(
                                model("environment_model", false,
                                        customPath.toString(), true, true)));
                    }
                });

        assertEquals(1, CellposeRegisteredModels.fetch(
                Collections.<String>emptyList()).size());
        assertEquals(1, backendCalls.get());
        environment.set(environment("environment-two"));
        assertEquals(1, CellposeRegisteredModels.fetch(
                Collections.<String>emptyList()).size());
        assertEquals(2, backendCalls.get());
        now.addAndGet(CellposeRegisteredModels.SUCCESS_TTL_MS + 1L);
        assertEquals(1, CellposeRegisteredModels.fetch(
                Collections.<String>emptyList()).size());
        assertEquals(3, backendCalls.get());
    }

    @Test
    public void discoveryUsesManagedRuntimeCompatibleStartupBudget() throws Exception {
        final AtomicLong timeoutSeconds = new AtomicLong();
        CellposeRegisteredModels.setEnvironmentProviderForTests(
                fixed(environment("slow-environment")));
        CellposeRegisteredModels.setDiscoveryBackendForTests(
                new CellposeRegisteredModels.DiscoveryBackend() {
                    @Override public Map<String, Object> discover(
                            CellposeRegisteredModels.DiscoveryEnvironment current,
                            long timeout,
                            TimeUnit unit) {
                        timeoutSeconds.set(unit.toSeconds(timeout));
                        return response(current.cacheKey,
                                Collections.<Map<String, Object>>emptyList());
                    }
                });

        CellposeRegisteredModels.fetch(Collections.<String>emptyList());

        assertTrue(timeoutSeconds.get() >= 30L);
        assertTrue(timeoutSeconds.get() > 3L);
    }

    @Test
    public void runtimeRefreshInvalidatesSuccessfulDiscoverySnapshot() throws Exception {
        final AtomicInteger backendCalls = new AtomicInteger();
        final CellposeRegisteredModels.DiscoveryEnvironment environment =
                environment("refresh-environment");
        CellposeRegisteredModels.setEnvironmentProviderForTests(fixed(environment));
        CellposeRegisteredModels.setDiscoveryBackendForTests(
                new CellposeRegisteredModels.DiscoveryBackend() {
                    @Override public Map<String, Object> discover(
                            CellposeRegisteredModels.DiscoveryEnvironment current,
                            long timeout,
                            TimeUnit unit) {
                        backendCalls.incrementAndGet();
                        return response(current.cacheKey,
                                Collections.<Map<String, Object>>emptyList());
                    }
                });

        CellposeRegisteredModels.fetch(Collections.<String>emptyList());
        CellposeRegisteredModels.fetch(Collections.<String>emptyList());
        assertEquals(1, backendCalls.get());

        CellposeRuntime.invalidateCache();
        CellposeRegisteredModels.fetch(Collections.<String>emptyList());
        assertEquals(2, backendCalls.get());
    }

    @Test
    public void rejectsUnsupportedOrIncompleteProtocol() throws Exception {
        Map<String, Object> unsupported = response("environment-a",
                Collections.<Map<String, Object>>emptyList());
        unsupported.put("cellpose_version", "4.0.0");
        try {
            CellposeRegisteredModels.entriesFromResponse(unsupported,
                    Collections.<String>emptyList());
            fail("Expected unsupported Cellpose version to be rejected.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unsupported version"));
        }

        Map<String, Object> incomplete = response("environment-a", Arrays.asList(
                model("cyto3", true, null, true, true)));
        ((Map<String, Object>) ((List<?>) incomplete.get("models")).get(0))
                .remove("runnable");
        try {
            CellposeRegisteredModels.entriesFromResponse(incomplete,
                    Collections.<String>emptyList());
            fail("Expected incomplete validation flags to be rejected.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("validation flags"));
        }
    }

    @Test
    public void nullAutoDetectionRoutesToManualSelectionSeed() {
        assertNull(CellposeRuntime.readyDetectedPathOrNull(null));

        CellposeRuntime.ExistingInstallDetection unavailable =
                new CellposeRuntime.ExistingInstallDetection("python",
                        new CellposeRuntime.Status("python", true, true, false,
                                "", false, "not ready", ""));
        assertNull(CellposeRuntime.readyDetectedPathOrNull(unavailable));

        CellposeRuntime.ExistingInstallDetection ready =
                new CellposeRuntime.ExistingInstallDetection("python",
                        new CellposeRuntime.Status("python", true, true, true,
                                CellposeRuntime.SUPPORTED_CELLPOSE_VERSION, false,
                                "ready", ""));
        assertEquals("python", CellposeRuntime.readyDetectedPathOrNull(ready));
    }

    private static CellposeRegisteredModels.EnvironmentProvider fixed(
            final CellposeRegisteredModels.DiscoveryEnvironment environment) {
        return new CellposeRegisteredModels.EnvironmentProvider() {
            @Override public CellposeRegisteredModels.DiscoveryEnvironment current() {
                return environment;
            }
        };
    }

    private static CellposeRegisteredModels.DiscoveryEnvironment environment(String key) {
        return new CellposeRegisteredModels.DiscoveryEnvironment(
                "python", CellposeRuntime.SUPPORTED_CELLPOSE_VERSION, key);
    }

    private static Map<String, Object> response(String environmentKey,
                                                 List<? extends Map<String, Object>> models) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("id", "test");
        response.put("protocol", Integer.valueOf(1));
        response.put("success", Boolean.TRUE);
        response.put("cellpose_version", CellposeRuntime.SUPPORTED_CELLPOSE_VERSION);
        response.put("environment_key", environmentKey);
        response.put("models", new ArrayList<Map<String, Object>>(models));
        return response;
    }

    private static Map<String, Object> model(String name,
                                              boolean builtin,
                                              String path,
                                              boolean registered,
                                              boolean runnable) {
        Map<String, Object> model = new LinkedHashMap<String, Object>();
        model.put("name", name);
        model.put("builtin", Boolean.valueOf(builtin));
        model.put("registered", Boolean.valueOf(registered));
        model.put("runnable", Boolean.valueOf(runnable));
        if (path != null) {
            model.put("path", path);
        }
        return model;
    }

    private static ModelEntry find(List<ModelEntry> entries, String key) {
        for (ModelEntry entry : entries) {
            if (key.equals(entry.modelKey)) {
                return entry;
            }
        }
        throw new AssertionError("Missing entry: " + key);
    }
}
