package flash.pipeline.objects;

import flash.pipeline.io.CsvSupport;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Stage 09: the per-object profile CSV writer (producer) and reader (Spatial consumer) round-trip,
 * so Spatial can regenerate aggregate curves from saved data without recomputing.
 */
public class ObjectProfileCsvRoundTripTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void perObjectCsvRoundTripsThroughReaderIntoAggregator() throws Exception {
        File dir = tmp.newFolder("oip");

        // Two objects in one image, each with a partner radial curve.
        List<ObjectProfileResult> results = new ArrayList<ObjectProfileResult>();
        results.add(objectWithRadial(1, new double[] {1.0, 0.5, 0.0}));
        results.add(objectWithRadial(2, new double[] {0.0, 0.5, 1.0}));

        ObjectProfileCsvWriter.appendPerObject(dir, "AnimalA", "LH", "RegionR", "ROI1", results);
        File perObject = new File(dir, ObjectProfileCsvWriter.PER_OBJECT_FILE);
        assertTrue("per-object CSV written", perObject.isFile());

        ProfileAggregator agg = ObjectProfileCsvReader.aggregateFromPerObject(perObject);
        List<ProfileAggregator.AggregatedProfile> aggs = agg.results();
        assertEquals(1, aggs.size()); // one (source, partner, Radial, group)

        ProfileAggregator.AggregatedProfile a = aggs.get(0);
        assertEquals("DAPI", a.source);
        assertEquals("MOAB2", a.partner);
        assertEquals(ProfileAggregator.RADIAL, a.profileType);
        assertEquals(3, a.mean.length);
        // Bin-wise mean of the two curves: {(1+0)/2, (0.5+0.5)/2, (0+1)/2}.
        assertEquals(0.5, a.mean[0], 1e-9);
        assertEquals(0.5, a.mean[1], 1e-9);
        assertEquals(0.5, a.mean[2], 1e-9);
        assertEquals(2, a.n[0]); // both objects contributed
    }

    @Test
    public void perObjectCsv_serializesUnsafeTextAndNumericCellsExactly() throws Exception {
        File dir = tmp.newFolder("unsafe-per-object");
        ObjectProfileResult result = new ObjectProfileResult("\tSource \"\u03b1\"", 7, 100);
        ObjectProfileResult.PartnerProfiles partner = result.partner("\rPartner,\u03b2");
        partner.radialRaw = new double[]{-12.5};
        partner.radialNorm = new double[]{3.25};

        ObjectProfileCsvWriter.appendPerObject(dir, "=Animal", "  +LH", "\u0000-Region",
                "@ROI,\n\u96ea", Arrays.asList(result));

        File csv = new File(dir, ObjectProfileCsvWriter.PER_OBJECT_FILE);
        String newline = System.lineSeparator();
        String expected = "Animal,Hemisphere,Region,ROI,SourceChannel,Label,VoxelCount,"
                + "PartnerChannel,ProfileType,Bin,AxisNorm,ValueRaw,ValueNorm" + newline
                + "'=Animal,'  +LH,'\u0000-Region,\"'@ROI,\n\u96ea\","
                + "\"'\tSource \"\"\u03b1\"\"\",7,100,\"'\rPartner,\u03b2\","
                + "Radial,0,0.5,-12.5,3.25" + newline;

        byte[] actual = Files.readAllBytes(csv.toPath());
        assertArrayEquals(expected.getBytes(CsvSupport.CHARSET), actual);
        List<List<String>> rows = parseCsvIndependently(actual);
        assertEquals(2, rows.size());
        assertEquals(Arrays.asList(
                "'=Animal", "'  +LH", "'\u0000-Region", "'@ROI,\n\u96ea",
                "'\tSource \"\u03b1\"", "7", "100", "'\rPartner,\u03b2", "Radial",
                "0", "0.5", "-12.5", "3.25"), rows.get(1));
    }

    @Test
    public void aggregateCsv_serializesUnsafeMultilineUnicodeAndNumericCellsExactly()
            throws Exception {
        File dir = tmp.newFolder("unsafe-aggregate");
        ProfileAggregator aggregator = new ProfileAggregator();
        aggregator.add("\u000b=AggregateSource", "  +AggregatePartner", "-Type \"\u03c4\"",
                "\n@Group,\r\n\u96ea", new double[]{-2.5});

        ObjectProfileCsvWriter.writeAggregated(dir, aggregator);

        File csv = new File(dir, ObjectProfileCsvWriter.AGGREGATE_FILE);
        String newline = System.lineSeparator();
        String expected = "SourceChannel,PartnerChannel,ProfileType,Group,Bin,AxisNorm,Mean,SEM,N"
                + newline + "'\u000b=AggregateSource,'  +AggregatePartner,"
                + "\"'-Type \"\"\u03c4\"\"\",\"'\n@Group,\r\n\u96ea\","
                + "0,0.0,-2.5,0.0,1" + newline;

        byte[] actual = Files.readAllBytes(csv.toPath());
        assertArrayEquals(expected.getBytes(CsvSupport.CHARSET), actual);
        List<List<String>> rows = parseCsvIndependently(actual);
        assertEquals(2, rows.size());
        assertEquals(Arrays.asList("'\u000b=AggregateSource", "'  +AggregatePartner",
                "'-Type \"\u03c4\"", "'\n@Group,\r\n\u96ea", "0", "0.0", "-2.5",
                "0.0", "1"), rows.get(1));
    }

    private static ObjectProfileResult objectWithRadial(int label, double[] norm) {
        ObjectProfileResult r = new ObjectProfileResult("DAPI", label, 100);
        ObjectProfileResult.PartnerProfiles pf = r.partner("MOAB2");
        pf.radialRaw = norm.clone();
        pf.radialNorm = norm.clone();
        return r;
    }

    /** Test-only RFC-style decoder; it deliberately does not call the production CSV parser. */
    private static List<List<String>> parseCsvIndependently(byte[] bytes) {
        String text = new String(bytes, CsvSupport.CHARSET);
        List<List<String>> rows = new ArrayList<List<String>>();
        List<String> row = new ArrayList<String>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStart = true;

        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (quoted) {
                if (value == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(value);
                }
            } else if (fieldStart && value == '"') {
                quoted = true;
                fieldStart = false;
            } else if (value == ',') {
                row.add(field.toString());
                field.setLength(0);
                fieldStart = true;
            } else if (value == '\r' || value == '\n') {
                if (value == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                rows.add(row);
                row = new ArrayList<String>();
                field.setLength(0);
                fieldStart = true;
            } else {
                field.append(value);
                fieldStart = false;
            }
        }
        if (!fieldStart || field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
