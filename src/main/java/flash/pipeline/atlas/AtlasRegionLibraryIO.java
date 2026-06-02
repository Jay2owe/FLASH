package flash.pipeline.atlas;

import flash.pipeline.ui.wizard.JsonIO;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Loads bundled atlas region metadata from the plugin jar. */
public final class AtlasRegionLibraryIO {
    private static final String RESOURCE_PATH = "/atlas/allen_mouse_25um_regions.json";
    private static AtlasRegionLibrary bundled;

    private AtlasRegionLibraryIO() {
    }

    public static synchronized AtlasRegionLibrary loadBundled() throws IOException {
        if (bundled != null) return bundled;
        InputStream stream = AtlasRegionLibraryIO.class.getResourceAsStream(RESOURCE_PATH);
        if (stream == null) {
            throw new IOException("Missing bundled atlas region resource: " + RESOURCE_PATH);
        }
        try {
            bundled = fromJson(readFully(stream));
            return bundled;
        } finally {
            stream.close();
        }
    }

    public static AtlasRegionLibrary loadBundledQuietly() {
        try {
            return loadBundled();
        } catch (IOException e) {
            return new AtlasRegionLibrary("allen_mouse_25um", Collections.<AtlasRegion>emptyList());
        }
    }

    public static AtlasRegionLibrary fromJson(String json) throws IOException {
        Map<String, Object> root = JsonIO.parseObject(json);
        String atlasKey = JsonIO.stringValue(root.get("atlasKey"));
        List<AtlasRegion> regions = new ArrayList<AtlasRegion>();
        for (Object regionObject : JsonIO.asList(root.get("regions"))) {
            Map<String, Object> region = JsonIO.asObject(regionObject);
            regions.add(new AtlasRegion(
                    atlasKey,
                    JsonIO.intValue(region.get("id"), 0),
                    JsonIO.stringValue(region.get("acronym")),
                    JsonIO.stringValue(region.get("name")),
                    integerList(region.get("structureIdPath")),
                    stringList(region.get("aliases"))));
        }
        return new AtlasRegionLibrary(atlasKey, regions);
    }

    private static List<Integer> integerList(Object raw) {
        List<Integer> out = new ArrayList<Integer>();
        for (Object value : JsonIO.asList(raw)) {
            if (value instanceof Number) {
                out.add(Integer.valueOf(((Number) value).intValue()));
            } else if (value != null) {
                try {
                    out.add(Integer.valueOf(Integer.parseInt(String.valueOf(value).trim())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<String>();
        for (Object value : JsonIO.asList(raw)) {
            if (value != null && !String.valueOf(value).trim().isEmpty()) {
                out.add(String.valueOf(value).trim());
            }
        }
        return out;
    }

    private static String readFully(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toString("UTF-8");
    }
}
