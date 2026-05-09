package flash.pipeline.ui.config;

import flash.pipeline.bin.BinConfig;

import ij.ImagePlus;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ConfigQcContext {

    public static final class ConfigQcImage {
        private final int seriesIndex;
        private final String seriesName;
        private final ImagePlus image;

        public ConfigQcImage(int seriesIndex, String seriesName, ImagePlus image) {
            this.seriesIndex = seriesIndex;
            this.seriesName = safe(seriesName);
            this.image = image;
        }

        public int getSeriesIndex() {
            return seriesIndex;
        }

        public String getSeriesName() {
            return seriesName;
        }

        public ImagePlus getImage() {
            return image;
        }

        public String getDisplayName() {
            if (!seriesName.trim().isEmpty()) {
                return seriesName;
            }
            if (image != null && image.getTitle() != null && !image.getTitle().trim().isEmpty()) {
                return image.getTitle();
            }
            return "Untitled image";
        }
    }

    private final File projectDirectory;
    private final File binFolder;
    private final Object config;
    private final List<ConfigQcImage> images;
    private final List<String> channelNames;
    private final Map<String, Object> attributes = new HashMap<String, Object>();
    private int channelIndex;
    private int currentImageIndex;
    private Integer requestedNextImageIndex;

    public ConfigQcContext(File projectDirectory, File binFolder, Object config,
                           List<ConfigQcImage> images, List<String> channelNames,
                           int channelIndex) {
        this.projectDirectory = projectDirectory;
        this.binFolder = binFolder;
        this.config = config;
        this.images = Collections.unmodifiableList(copyImages(images));
        this.channelNames = Collections.unmodifiableList(copyStrings(channelNames));
        this.channelIndex = Math.max(0, channelIndex);
        this.currentImageIndex = 0;
    }

    public static ConfigQcContext fromImages(File projectDirectory, File binFolder, Object config,
                                             List<ImagePlus> images, List<String> channelNames,
                                             int channelIndex) {
        List<ConfigQcImage> wrapped = new ArrayList<ConfigQcImage>();
        if (images != null) {
            for (int i = 0; i < images.size(); i++) {
                ImagePlus image = images.get(i);
                String title = image == null ? "" : image.getTitle();
                wrapped.add(new ConfigQcImage(i, title, image));
            }
        }
        return new ConfigQcContext(projectDirectory, binFolder, config, wrapped, channelNames, channelIndex);
    }

    public File getProjectDirectory() {
        return projectDirectory;
    }

    public File getBinFolder() {
        return binFolder;
    }

    public Object getConfig() {
        return config;
    }

    public List<ConfigQcImage> getImages() {
        return images;
    }

    public int getImageCount() {
        return images.size();
    }

    public boolean hasImages() {
        return !images.isEmpty();
    }

    public int getCurrentImageIndex() {
        return currentImageIndex;
    }

    public void setCurrentImageIndex(int index) {
        if (images.isEmpty()) {
            currentImageIndex = 0;
            return;
        }
        currentImageIndex = clamp(index, 0, images.size() - 1);
    }

    public void resetCurrentImage() {
        currentImageIndex = 0;
    }

    public boolean moveToNextImage() {
        if (currentImageIndex + 1 >= images.size()) {
            return false;
        }
        currentImageIndex++;
        return true;
    }

    public void requestNextImageIndex(int index) {
        requestedNextImageIndex = Integer.valueOf(index);
    }

    Integer consumeRequestedNextImageIndex() {
        Integer requested = requestedNextImageIndex;
        requestedNextImageIndex = null;
        return requested;
    }

    public ConfigQcImage getCurrentImage() {
        if (images.isEmpty()) {
            return null;
        }
        return images.get(clamp(currentImageIndex, 0, images.size() - 1));
    }

    public ImagePlus getCurrentImagePlus() {
        ConfigQcImage current = getCurrentImage();
        return current == null ? null : current.getImage();
    }

    public String getCurrentImageDisplayName() {
        ConfigQcImage current = getCurrentImage();
        return current == null ? "No image selected" : current.getDisplayName();
    }

    public String getImageProgressText() {
        if (images.isEmpty()) {
            return "No images";
        }
        return "Image " + (currentImageIndex + 1) + " / " + images.size();
    }

    public String getCurrentImageMovedStatusText() {
        if (images.isEmpty()) {
            return "No images remain.";
        }
        return "Moved to image " + (currentImageIndex + 1) + " / " + images.size()
                + ": " + getCurrentImageDisplayName();
    }

    public int getChannelIndex() {
        return channelIndex;
    }

    public void setChannelIndex(int channelIndex) {
        this.channelIndex = Math.max(0, channelIndex);
    }

    public int getChannelNumber() {
        return channelIndex + 1;
    }

    public List<String> getChannelNames() {
        return channelNames;
    }

    public String getChannelName() {
        if (channelIndex >= 0 && channelIndex < channelNames.size()) {
            return safe(channelNames.get(channelIndex));
        }
        return "";
    }

    public String getChannelLabel() {
        String name = getChannelName();
        if (name.trim().isEmpty()) {
            return "C" + getChannelNumber();
        }
        return "C" + getChannelNumber() + " - " + name;
    }

    public String getChannelLutName() {
        List<String> colors = channelColorsFromConfig();
        if (channelIndex >= 0 && colors != null && channelIndex < colors.size()) {
            String color = safe(colors.get(channelIndex)).trim();
            if (!color.isEmpty()) {
                return color;
            }
        }
        return "Grays";
    }

    public void putAttribute(String key, Object value) {
        if (key == null || key.trim().isEmpty()) return;
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    private static List<ConfigQcImage> copyImages(List<ConfigQcImage> source) {
        List<ConfigQcImage> copy = new ArrayList<ConfigQcImage>();
        if (source != null) {
            for (int i = 0; i < source.size(); i++) {
                ConfigQcImage image = source.get(i);
                if (image != null) {
                    copy.add(image);
                }
            }
        }
        return copy;
    }

    private static List<String> copyStrings(List<String> source) {
        List<String> copy = new ArrayList<String>();
        if (source != null) {
            for (int i = 0; i < source.size(); i++) {
                copy.add(safe(source.get(i)));
            }
        }
        return copy;
    }

    private static int clamp(int value, int minimum, int maximum) {
        if (maximum < minimum) return minimum;
        if (value < minimum) return minimum;
        if (value > maximum) return maximum;
        return value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    @SuppressWarnings("unchecked")
    private List<String> channelColorsFromConfig() {
        if (config instanceof BinConfig) {
            return ((BinConfig) config).channelColors;
        }
        Object value = fieldValue(config, "colors");
        if (!(value instanceof List<?>)) {
            value = fieldValue(config, "channelColors");
        }
        if (value instanceof List<?>) {
            return (List<String>) value;
        }
        return null;
    }

    private static Object fieldValue(Object target, String fieldName) {
        if (target == null || fieldName == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
