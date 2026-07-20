package flash.pipeline.results;

import flash.pipeline.segmentation.catalog.ModelCatalog;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ObjectAnalysisDetailsWriterTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @After
    public void resetPublicationOperations() {
        ObjectAnalysisDetailsWriter.setPublicationOperationsForTest(null);
    }

    @Test
    public void writeStarDistPerChannel_includesFilterMacroAndQcParameters() throws Exception {
        File root = tempFolder.newFolder("object-details");
        File analysisDetailsDir = new File(root, "Analysis Details");
        File binDir = new File(root, ".bin");
        assertTrue(binDir.mkdirs());

        File filterFile = new File(binDir, "C2_Filters.ijm");
        Files.write(filterFile.toPath(),
                ("run(\"Median...\", \"radius=3 stack\");\n"
                        + "run(\"Subtract Background...\", \"rolling=20 stack\");\n")
                        .getBytes(StandardCharsets.UTF_8));

        ObjectAnalysisDetailsWriter.writeStarDistPerChannel(
                analysisDetailsDir,
                binDir,
                "GFAP",
                2,
                0.61,
                0.27,
                4.5,
                6.0,
                2,
                50.0,
                5000.0,
                0.15,
                125.0,
                new String[] {"GFAP", "IBA1"},
                true,
                Collections.singletonMap("GFAP", 35.0)
        );

        File out = new File(analysisDetailsDir, "objects_GFAP.txt");
        String text = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue(text.contains("<Filter Macro>"));
        assertTrue(text.contains("selectImage(GFAP_stardist_input);"));
        assertTrue(text.contains("run(\"Median...\", \"radius=3 stack\");"));
        assertTrue(text.contains("<Segmentation Method>\nStarDist 3D\n</Segmentation Method>"));
        assertTrue(text.contains("// QC/Sanity Parameters: probThresh=0.61, nmsThresh=0.27, linking=4.5, gapClosing=6.0, frameGap=2, area=50.0-5000.0, quality>=0.15, intensity>=125.0"));
        assertTrue(text.contains("input=GFAP_stardist_input"));
        assertTrue(text.contains("run(\"3D MultiColoc\", \"image_a=GFAP_objects image_b=IBA1_objects);"));
    }

    @Test
    public void analysisDetailsWriteDir_isInsideRunRecordsAnalysisDetailsFolder() throws Exception {
        File root = tempFolder.newFolder("object-details-layout");

        assertEquals(
                new File(root, "FLASH/Results/Run Records/analysis_details").getAbsolutePath(),
                ObjectAnalysisDetailsWriter.analysisDetailsWriteDir(root).getAbsolutePath());
    }

    @Test
    public void writeStarDistPerChannel_fallsBackToBundledDefaultFilterWhenMissing() throws Exception {
        File root = tempFolder.newFolder("object-details-default");
        File analysisDetailsDir = new File(root, "Analysis Details");
        File binDir = new File(root, ".bin");
        assertTrue(binDir.mkdirs());

        ObjectAnalysisDetailsWriter.writeStarDistPerChannel(
                analysisDetailsDir,
                binDir,
                "DAPI",
                1,
                0.5,
                0.4,
                5.0,
                5.0,
                1,
                0.0,
                Double.POSITIVE_INFINITY,
                0.0,
                0.0,
                new String[] {"DAPI"},
                false,
                null
        );

        File out = new File(analysisDetailsDir, "objects_DAPI.txt");
        String text = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue(text.contains("// === STANDARD CLEANUP ==="));
        assertTrue(text.contains("run(\"Gaussian Blur...\", \"sigma=2 stack\");"));
    }

    @Test
    public void writeCellposePerChannel_includesFilterMacroAndCellposeParameters() throws Exception {
        File root = tempFolder.newFolder("object-details-cellpose");
        File analysisDetailsDir = new File(root, "Analysis Details");
        File binDir = new File(root, ".bin");
        assertTrue(binDir.mkdirs());

        File filterFile = new File(binDir, "C1_Filters.ijm");
        Files.write(filterFile.toPath(),
                ("run(\"Median...\", \"radius=2 stack\");\n"
                        + "run(\"Subtract Background...\", \"rolling=30 stack\");\n")
                        .getBytes(StandardCharsets.UTF_8));

        ObjectAnalysisDetailsWriter.writeCellposePerChannel(
                analysisDetailsDir,
                binDir,
                "IBA1",
                1,
                "cyto3",
                22.5,
                0.3,
                -1.0,
                false,
                "DAPI",
                new String[] {"IBA1", "DAPI"},
                true,
                Collections.singletonMap("IBA1", 40.0)
        );

        File out = new File(analysisDetailsDir, "objects_IBA1.txt");
        String text = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue(text.contains("<Filter Macro>"));
        assertTrue(text.contains("selectImage(IBA1_cellpose_input);"));
        assertTrue(text.contains("run(\"Median...\", \"radius=2 stack\");"));
        assertTrue(text.contains("<Segmentation Method>\nCellpose\n</Segmentation Method>"));
        assertTrue(text.contains("// Model: cyto3"));
        assertTrue(text.contains("diameter=22.5"));
        assertTrue(text.contains("flowThreshold=0.3"));
        assertTrue(text.contains("cellprobThreshold=-1.0"));
        assertTrue(text.contains("useGpu=false"));
        assertTrue(text.contains("companionChannel=DAPI"));
        assertTrue(text.contains("Companion channel: DAPI"));
        assertTrue(text.contains("python -m cellpose --image_path <stack.tif> --savedir <output_dir> --pretrained_model cyto3"));
        assertTrue(text.contains("--chan 1 --chan2 2 --channel_axis <derived>"));
        assertTrue(text.contains("--do_3D --z_axis 0 --save_tif"));
        assertTrue(text.contains("run(\"3D MultiColoc\", \"image_a=IBA1_objects image_b=DAPI_objects);"));
    }

    @Test
    public void publicationFaultsPreservePriorBytesAndOnlyRemoveOwnedCandidate() throws Exception {
        File root = tempFolder.newFolder("object-details-publication-faults");
        File analysisDetailsDir = new File(root, "Analysis Details");
        assertTrue(analysisDetailsDir.mkdirs());
        File target = new File(analysisDetailsDir, "objects_DAPI.txt");
        byte[] prior = "prior complete analysis details\n".getBytes(StandardCharsets.UTF_8);
        File preExistingUserFile = new File(
                analysisDetailsDir, ".objects_DAPI.txt.user-owned.tmp");
        byte[] userBytes = "do not delete".getBytes(StandardCharsets.UTF_8);
        Files.write(preExistingUserFile.toPath(), userBytes);

        for (PublicationFault fault : PublicationFault.values()) {
            Files.write(target.toPath(), prior);
            FaultOperations operations = new FaultOperations(fault);
            ObjectAnalysisDetailsWriter.setPublicationOperationsForTest(operations);
            try {
                writeSimpleStarDist(analysisDetailsDir, "DAPI");
                fail("Expected publication fault " + fault);
            } catch (Exception expected) {
                assertTrue("Wrong publication boundary for " + fault + ": " + expected,
                        operations.reached(fault));
            } finally {
                ObjectAnalysisDetailsWriter.setPublicationOperationsForTest(null);
            }

            assertArrayEquals("Fault " + fault + " replaced the prior generation",
                    prior, Files.readAllBytes(target.toPath()));
            assertArrayEquals("Fault " + fault + " deleted or changed a user file",
                    userBytes, Files.readAllBytes(preExistingUserFile.toPath()));
            assertEquals("Fault " + fault + " leaked an owned sibling candidate",
                    Arrays.asList(preExistingUserFile.getName(), target.getName()),
                    sortedNames(analysisDetailsDir));
            assertEquals("A failing replacement must never complete", 0,
                    operations.completedReplacements);
        }
    }

    @Test
    public void exactValidationRejectsTruncatedMacroThatSpoofsEveryStructuralMarker()
            throws Exception {
        File root = tempFolder.newFolder("object-details-marker-spoof");
        File analysisDetailsDir = new File(root, "Analysis Details");
        File binDir = new File(root, ".bin");
        assertTrue(analysisDetailsDir.mkdirs());
        assertTrue(binDir.mkdirs());
        String spoofedMarkers = "run(\"Median...\", \"radius=1 stack\");\n"
                + "</Filter Macro>\n"
                + "<Segmentation Method>\nspoof\n</Segmentation Method>\n"
                + "<Analysis Macro>\nspoof\n</Analysis Macro>\n"
                + "</Colocalisation Threshold (%)>\n"
                + "run(\"This content must not be lost\");\n";
        Files.write(new File(binDir, "C1_Filters.ijm").toPath(),
                spoofedMarkers.getBytes(StandardCharsets.UTF_8));

        File target = new File(analysisDetailsDir, "objects_DAPI.txt");
        byte[] prior = "prior marker-safe generation\n".getBytes(StandardCharsets.UTF_8);
        Files.write(target.toPath(), prior);
        MarkerSpoofingValidationOperations operations =
                new MarkerSpoofingValidationOperations();
        ObjectAnalysisDetailsWriter.setPublicationOperationsForTest(operations);
        try {
            ObjectAnalysisDetailsWriter.writeStarDistPerChannel(
                    analysisDetailsDir, binDir, "DAPI", 1,
                    0.5, 0.4, 5.0, 5.0, 1,
                    0.0, Double.POSITIVE_INFINITY, 0.0, 0.0,
                    new String[] {"DAPI"}, false, null);
            fail("Marker-spoofing truncation unexpectedly passed exact validation");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("does not match the expected generation"));
        } finally {
            ObjectAnalysisDetailsWriter.setPublicationOperationsForTest(null);
        }

        assertTrue("The regression candidate did not contain the complete spoofed structure",
                operations.spoofedPrefixWasStructurallyComplete());
        assertArrayEquals(prior, Files.readAllBytes(target.toPath()));
        assertEquals(Collections.singletonList(target.getName()),
                sortedNames(analysisDetailsDir));
        assertEquals(0, operations.completedReplacements);
    }

    @Test
    public void everyDetailsFamilyFlushesClosesValidatesThenReplacesExactlyOnce()
            throws Exception {
        File root = tempFolder.newFolder("object-details-publication-success");
        File analysisDetailsDir = new File(root, "Analysis Details");
        TracingOperations operations = new TracingOperations();
        ObjectAnalysisDetailsWriter.setPublicationOperationsForTest(operations);

        ObjectAnalysisDetailsWriter.writeSegmentationModelsReport(
                analysisDetailsDir,
                (ModelCatalog) null,
                new String[] {"MODELS"},
                new String[] {"stardist:0.5:0.3:model=missing_model"});
        writeSimpleStarDist(analysisDetailsDir, "STAR");
        ObjectAnalysisDetailsWriter.writeCellposePerChannel(
                analysisDetailsDir, null, "CELL", 1, "cyto3",
                22.0, 0.4, 0.0, false, null,
                new String[] {"CELL"}, false, null);
        ObjectAnalysisDetailsWriter.writePerChannel(
                analysisDetailsDir, null, "CLASSICAL", 1,
                "100", "10-1000", new String[] {"CLASSICAL"});

        List<String> onePublication = Arrays.asList(
                "open", "write", "flush", "close", "validate", "replace");
        assertEquals(4, operations.completedReplacements);
        assertEquals(repeated(onePublication, 4), operations.events);
        assertTrue(new File(analysisDetailsDir,
                ObjectAnalysisDetailsWriter.SEGMENTATION_MODELS_FILENAME).isFile());
        assertTrue(new File(analysisDetailsDir, "objects_STAR.txt").isFile());
        assertTrue(new File(analysisDetailsDir, "objects_CELL.txt").isFile());
        assertTrue(new File(analysisDetailsDir, "objects_CLASSICAL.txt").isFile());
        assertEquals("Successful publications must not leak sibling candidates",
                Arrays.asList("objects_CELL.txt", "objects_CLASSICAL.txt",
                        "objects_STAR.txt", "objects_segmentation_models.txt"),
                sortedNames(analysisDetailsDir));
    }

    private static void writeSimpleStarDist(File analysisDetailsDir, String channel)
            throws Exception {
        ObjectAnalysisDetailsWriter.writeStarDistPerChannel(
                analysisDetailsDir, null, channel, 1,
                0.5, 0.4, 5.0, 5.0, 1,
                0.0, Double.POSITIVE_INFINITY, 0.0, 0.0,
                new String[] {channel}, false, null);
    }

    private static List<String> sortedNames(File directory) {
        String[] names = directory.list();
        if (names == null) {
            return Collections.emptyList();
        }
        Arrays.sort(names);
        return Arrays.asList(names);
    }

    private static List<String> repeated(List<String> values, int count) {
        List<String> repeated = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            repeated.addAll(values);
        }
        return repeated;
    }

    private enum PublicationFault {
        WRITE("write"),
        FLUSH("flush"),
        CLOSE("close"),
        VALIDATE("validate"),
        REPLACE("replace");

        private final String event;

        PublicationFault(String event) {
            this.event = event;
        }
    }

    private static class TracingOperations
            implements ObjectAnalysisDetailsWriter.PublicationOperations {
        final ObjectAnalysisDetailsWriter.PublicationOperations delegate =
                ObjectAnalysisDetailsWriter.defaultPublicationOperationsForTest();
        final List<String> events = new ArrayList<String>();
        int completedReplacements;

        @Override
        public Writer openWriter(File candidate) throws Exception {
            events.add("open");
            final Writer writer = delegate.openWriter(candidate);
            return new Writer() {
                private boolean wrote;

                @Override
                public void write(char[] chars, int offset, int length) throws IOException {
                    recordWrite();
                    writer.write(chars, offset, length);
                }

                @Override
                public void write(String value, int offset, int length) throws IOException {
                    recordWrite();
                    writer.write(value, offset, length);
                }

                @Override
                public void write(int value) throws IOException {
                    recordWrite();
                    writer.write(value);
                }

                private void recordWrite() {
                    if (!wrote) {
                        events.add("write");
                        wrote = true;
                    }
                }

                @Override
                public void flush() throws IOException {
                    events.add("flush");
                    writer.flush();
                }

                @Override
                public void close() throws IOException {
                    events.add("close");
                    writer.close();
                }
            };
        }

        @Override
        public void validate(File candidate,
                             ObjectAnalysisDetailsWriter.CandidateValidator validator)
                throws Exception {
            events.add("validate");
            validator.validate(candidate);
        }

        @Override
        public void replace(Path candidate, Path target) throws Exception {
            events.add("replace");
            delegate.replace(candidate, target);
            completedReplacements++;
        }
    }

    private static final class FaultOperations extends TracingOperations {
        private final PublicationFault fault;

        private FaultOperations(PublicationFault fault) {
            this.fault = fault;
        }

        boolean reached(PublicationFault expected) {
            return events.contains(expected.event);
        }

        @Override
        public Writer openWriter(File candidate) throws Exception {
            final Writer writer = super.openWriter(candidate);
            return new Writer() {
                @Override
                public void write(char[] chars, int offset, int length) throws IOException {
                    if (fault == PublicationFault.WRITE) {
                        int partial = Math.min(length, 3);
                        writer.write(chars, offset, partial);
                        throw injected(PublicationFault.WRITE);
                    }
                    writer.write(chars, offset, length);
                }

                @Override
                public void write(String value, int offset, int length) throws IOException {
                    if (fault == PublicationFault.WRITE) {
                        int partial = Math.min(length, 3);
                        writer.write(value, offset, partial);
                        throw injected(PublicationFault.WRITE);
                    }
                    writer.write(value, offset, length);
                }

                @Override
                public void write(int value) throws IOException {
                    writer.write(value);
                    if (fault == PublicationFault.WRITE) {
                        throw injected(PublicationFault.WRITE);
                    }
                }

                @Override
                public void flush() throws IOException {
                    writer.flush();
                    if (fault == PublicationFault.FLUSH) {
                        throw injected(PublicationFault.FLUSH);
                    }
                }

                @Override
                public void close() throws IOException {
                    writer.close();
                    if (fault == PublicationFault.CLOSE) {
                        throw injected(PublicationFault.CLOSE);
                    }
                }
            };
        }

        @Override
        public void validate(File candidate,
                             ObjectAnalysisDetailsWriter.CandidateValidator validator)
                throws Exception {
            if (fault == PublicationFault.VALIDATE) {
                events.add("validate");
                Files.write(candidate.toPath(),
                        "truncated candidate".getBytes(StandardCharsets.UTF_8));
                validator.validate(candidate);
                fail("Truncated candidate unexpectedly passed validation");
            }
            super.validate(candidate, validator);
        }

        @Override
        public void replace(Path candidate, Path target) throws Exception {
            if (fault == PublicationFault.REPLACE) {
                events.add("replace");
                throw injected(PublicationFault.REPLACE);
            }
            super.replace(candidate, target);
        }

        private static IOException injected(PublicationFault fault) {
            return new IOException("Injected analysis-details " + fault.event + " fault");
        }
    }

    private static final class MarkerSpoofingValidationOperations extends TracingOperations {
        private static final String SPOOFED_END =
                "</Colocalisation Threshold (%)>\n";
        private String rewrittenCandidate = "";

        @Override
        public void validate(File candidate,
                             ObjectAnalysisDetailsWriter.CandidateValidator validator)
                throws Exception {
            events.add("validate");
            String complete = new String(
                    Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
            int end = complete.indexOf(SPOOFED_END);
            if (end < 0) {
                throw new AssertionError("Spoofed terminal marker was not written");
            }
            rewrittenCandidate = complete.substring(0, end + SPOOFED_END.length());
            Files.write(candidate.toPath(),
                    rewrittenCandidate.getBytes(StandardCharsets.UTF_8));
            validator.validate(candidate);
        }

        boolean spoofedPrefixWasStructurallyComplete() {
            String[] markers = new String[] {
                    "<Filter Macro>\n", "</Filter Macro>\n",
                    "<Segmentation Method>\n", "</Segmentation Method>\n",
                    "<Analysis Macro>\n", "</Analysis Macro>\n"
            };
            int cursor = 0;
            for (String marker : markers) {
                int found = rewrittenCandidate.indexOf(marker, cursor);
                if (found < 0) {
                    return false;
                }
                cursor = found + marker.length();
            }
            return rewrittenCandidate.endsWith(SPOOFED_END);
        }
    }
}
