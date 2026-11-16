package nl.nki.qupath.ext.segmentation.mixer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads/writes {@link MixerSettings}.
 * <p>
 * Settings are stored per project, one file per save, with date/time in the name:
 * {@code <project folder>/cellpose_mixer/cellpose_mixer_yyyy-MM-dd_HHmmss.json}. The newest file is the current one.
 * <p>
 * Also imports the old {@code codex_channel_command*.json} format written by get_cellpose_scaling_anno.groovy:
 * a JSON array of {@code {name, nuclear, membrane, low, high}} (name may be missing in older files).
 */
public final class MixerSettingsIO {

    public static final String FOLDER = "cellpose_mixer";
    static final String PREFIX = "cellpose_mixer_";
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private MixerSettingsIO() {}

    public static String toJson(MixerSettings settings) {
        return GSON.toJson(settings);
    }

    public static MixerSettings fromJson(String json) {
        JsonElement element = JsonParser.parseString(json);
        if (element.isJsonArray())
            throw new IllegalArgumentException("This looks like an old-style channel file; use importLegacy()");
        var settings = GSON.fromJson(element, MixerSettings.class);
        if (settings.getSchemaVersion() > MixerSettings.SCHEMA_VERSION)
            throw new IllegalArgumentException("Settings were written by a newer version of the extension (schema "
                    + settings.getSchemaVersion() + ")");
        return settings;
    }

    public static MixerSettings read(Path file) throws IOException {
        return fromJson(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static void write(MixerSettings settings, Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, toJson(settings), StandardCharsets.UTF_8);
    }

    /**
     * Save as a new timestamped file in the project folder (never overwrites earlier saves).
     *
     * @param projectDir folder containing project.qpproj
     * @return the file written
     */
    public static Path saveToProject(MixerSettings settings, Path projectDir) throws IOException {
        var now = LocalDateTime.now().withNano(0);
        settings.setSavedAt(now.toString());
        Path dir = projectDir.resolve(FOLDER);
        Path file = dir.resolve(PREFIX + FILE_TIME.format(now) + ".json");
        // Two saves within the same second: add a counter rather than overwrite
        for (int i = 2; Files.exists(file); i++)
            file = dir.resolve(PREFIX + FILE_TIME.format(now) + "_" + i + ".json");
        write(settings, file);
        return file;
    }

    /** All saved settings files in the project, newest first. */
    public static List<Path> listHistory(Path projectDir) throws IOException {
        Path dir = projectDir.resolve(FOLDER);
        if (!Files.isDirectory(dir))
            return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith(PREFIX) && n.endsWith(".json");
                    })
                    // Timestamp format sorts lexicographically
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        }
    }

    /** The newest saved settings in the project, if any. */
    public static Optional<MixerSettings> loadLatest(Path projectDir) throws IOException {
        var history = listHistory(projectDir);
        return history.isEmpty() ? Optional.empty() : Optional.of(read(history.getFirst()));
    }

    /** Result of importing an old-style file: the settings plus any warnings for the user. */
    public record ImportResult(MixerSettings settings, List<String> warnings) {}

    /**
     * Import the old {@code codex_channel_command*.json} format.
     *
     * @param json              file contents
     * @param imageChannelNames used when entries have no name (matched by position); may be null
     */
    public static ImportResult importLegacy(String json, List<String> imageChannelNames) {
        JsonArray array = JsonParser.parseString(json).getAsJsonArray();
        var settings = new MixerSettings();
        var warnings = new ArrayList<String>();
        if (imageChannelNames != null && imageChannelNames.size() != array.size())
            warnings.add("File has " + array.size() + " channels, the image has " + imageChannelNames.size());

        for (int i = 0; i < array.size(); i++) {
            JsonObject obj = array.get(i).getAsJsonObject();
            String name;
            if (obj.has("name") && !obj.get("name").isJsonNull()) {
                name = obj.get("name").getAsString();
            } else if (imageChannelNames != null && i < imageChannelNames.size()) {
                name = imageChannelNames.get(i);
                warnings.add("Entry " + i + " has no name; assumed '" + name + "' (by position)");
            } else {
                name = "Channel " + (i + 1);
                warnings.add("Entry " + i + " has no name; called it '" + name + "'");
            }
            boolean nuclear = getBoolean(obj, "nuclear");
            boolean membrane = getBoolean(obj, "membrane");
            ChannelRole role = nuclear ? ChannelRole.NUCLEUS : membrane ? ChannelRole.CYTO : ChannelRole.IGNORE;
            if (nuclear && membrane)
                warnings.add("'" + name + "' was both nuclear and membrane; it is now nucleus only");
            double low = getDouble(obj, "low", 0);
            double high = getDouble(obj, "high", 1);
            settings.getChannels().add(new ChannelSetting(name, role, low, high));
        }
        // The old scripts did not clip
        settings.setClip(false);
        warnings.add("Clipping is off, as in the old scripts");
        return new ImportResult(settings, warnings);
    }

    private static boolean getBoolean(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).getAsBoolean();
    }

    private static double getDouble(JsonObject obj, String key, double fallback) {
        // Gson writes NaN as a bare token (old scripts could produce it for empty histograms)
        if (!obj.has(key) || obj.get(key).isJsonNull())
            return fallback;
        try {
            return obj.get(key).getAsDouble();
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
