package flash.pipeline.click.training;

import flash.pipeline.segmentation.catalog.ModelEntry;
import smile.classification.RandomForest;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ObjectClassifierPersistence {
    public static final String ENGINE_VERSION = "smile-2.6.0";
    public static final String MODEL_FILENAME = "model.smile";

    private ObjectClassifierPersistence() {
    }

    public static Path modelPath(Path projectRoot, String modelKey) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("projectRoot must not be null");
        }
        requireSafeModelKey(modelKey);
        return projectRoot.resolve("Configuration")
                .resolve("Segmentation Models")
                .resolve("files")
                .resolve(modelKey)
                .resolve(MODEL_FILENAME)
                .toAbsolutePath()
                .normalize();
    }

    public static Path saveModel(Path projectRoot,
                                 String modelKey,
                                 RandomForest model) throws IOException {
        Path path = modelPath(projectRoot, modelKey);
        saveModel(path, model);
        return path;
    }

    public static void saveModel(Path path, RandomForest model) throws IOException {
        if (path == null) {
            throw new IOException("Model path must not be null.");
        }
        if (model == null) {
            throw new IOException("Random Forest model must not be null.");
        }
        Files.createDirectories(path.getParent());
        try (OutputStream out = Files.newOutputStream(path);
             ObjectOutputStream objects = new ObjectOutputStream(out)) {
            objects.writeObject(model);
        }
    }

    public static RandomForest loadModel(Path path) throws IOException, ClassNotFoundException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("Smile Random Forest model file does not exist: " + path);
        }
        try (InputStream in = Files.newInputStream(path);
             ObjectInputStream objects = new ObjectInputStream(in)) {
            Object value = objects.readObject();
            if (!(value instanceof RandomForest)) {
                throw new IOException("Serialized model is not a Smile RandomForest: " + path);
            }
            return (RandomForest) value;
        }
    }

    public static ModelEntry catalogEntry(String modelKey,
                                          String name,
                                          String description,
                                          String baseToken,
                                          ObjectClassifierTrainer.TrainingResult result) {
        requireSafeModelKey(modelKey);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("engineVersion", ENGINE_VERSION);
        metadata.put("license", "LGPL-3.0");
        if (result != null) {
            metadata.put("trainedAt", Long.valueOf(System.currentTimeMillis()));
            metadata.put("positiveExamples", Integer.valueOf(result.positiveExamples));
            metadata.put("negativeExamples", Integer.valueOf(result.negativeExamples));
            metadata.put("crossValAccuracy", Double.valueOf(result.crossValAccuracy));
            metadata.put("qualityFlag", result.quality.name());
            metadata.put("quality", result.quality.name());
            metadata.put("featureNames", stringList(result.featureNames));
            metadata.put("featureImportance",
                    featureImportanceMap(result.featureNames, result.featureImportance));
        }

        return new ModelEntry(
                modelKey,
                emptyToDefault(name, modelKey),
                emptyToDefault(description, "Smile Random Forest object post-filter."),
                ModelEntry.Engine.SMILE_RF,
                ModelEntry.Source.USER_TRAINED,
                "files/" + modelKey + "/" + MODEL_FILENAME,
                null,
                null,
                null,
                emptyToDefault(baseToken, "classical"),
                null,
                metadata,
                false);
    }

    public static String[] featureNamesFromMetadata(ModelEntry entry) {
        if (entry == null) return new String[0];
        Object raw = entry.metadata.get("featureNames");
        if (raw instanceof List) {
            List<?> values = (List<?>) raw;
            List<String> out = new ArrayList<String>();
            for (Object value : values) {
                if (value != null && !String.valueOf(value).trim().isEmpty()) {
                    out.add(String.valueOf(value).trim());
                }
            }
            return out.toArray(new String[0]);
        }
        return new String[0];
    }

    private static List<String> stringList(String[] values) {
        List<String> out = new ArrayList<String>();
        if (values != null) {
            for (String value : values) {
                if (value != null) out.add(value);
            }
        }
        return out;
    }

    private static Map<String, Object> featureImportanceMap(String[] names, double[] weights) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (names == null || weights == null) {
            return out;
        }
        int count = Math.min(names.length, weights.length);
        for (int i = 0; i < count; i++) {
            String name = names[i];
            double weight = weights[i];
            if (name != null && !name.trim().isEmpty() && Double.isFinite(weight)) {
                out.put(name.trim(), Double.valueOf(weight));
            }
        }
        return out;
    }

    private static String emptyToDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static void requireSafeModelKey(String modelKey) {
        if (modelKey == null || !modelKey.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid model key: " + modelKey);
        }
    }
}
