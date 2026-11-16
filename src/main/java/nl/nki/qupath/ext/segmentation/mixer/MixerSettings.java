package nl.nki.qupath.ext.segmentation.mixer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything needed to build the 2-channel Cellpose input and run Cellpose.
 * Serialized to JSON by {@link MixerSettingsIO}. Channels are matched to an image by name.
 */
public class MixerSettings {

    public static final int SCHEMA_VERSION = 1;

    private int schemaVersion = SCHEMA_VERSION;
    /** ISO-8601 local date/time when saved; informational. */
    private String savedAt;
    private List<ChannelSetting> channels = new ArrayList<>();
    private CombineMode combine = CombineMode.SUM;
    private boolean clip = true;
    private CellposeParameters cellpose = new CellposeParameters();
    private ScalingParameters scaling = new ScalingParameters();

    public int getSchemaVersion() { return schemaVersion; }

    public String getSavedAt() { return savedAt; }
    public void setSavedAt(String savedAt) { this.savedAt = savedAt; }

    public List<ChannelSetting> getChannels() { return channels; }
    public void setChannels(List<ChannelSetting> channels) { this.channels = new ArrayList<>(channels); }

    public CombineMode getCombine() { return combine == null ? CombineMode.SUM : combine; }
    public void setCombine(CombineMode combine) { this.combine = combine; }

    public boolean isClip() { return clip; }
    public void setClip(boolean clip) { this.clip = clip; }

    public CellposeParameters getCellpose() {
        if (cellpose == null)
            cellpose = new CellposeParameters();
        return cellpose;
    }
    public void setCellpose(CellposeParameters cellpose) { this.cellpose = cellpose; }

    public ScalingParameters getScaling() {
        if (scaling == null)
            scaling = new ScalingParameters();
        return scaling;
    }
    public void setScaling(ScalingParameters scaling) { this.scaling = scaling; }

    /**
     * Match these settings to the channels of an image (by name) and build the op that creates the Cellpose input.
     *
     * @param imageChannelNames channel names of the image, in image order
     * @throws IllegalArgumentException if an active channel is missing from the image, a name is ambiguous,
     *                                  a range is invalid, or no channel is assigned to the nucleus
     */
    public MixChannelsOp createOp(List<String> imageChannelNames) {
        return createOp(imageChannelNames, true);
    }

    /**
     * As {@link #createOp(List)}; with {@code requireNucleus = false} an empty nucleus channel is allowed
     * (used by the preview, so the user can look at the cyto mix before assigning a nucleus channel).
     */
    public MixChannelsOp createOp(List<String> imageChannelNames, boolean requireNucleus) {
        Map<String, Integer> indexByName = new HashMap<>();
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < imageChannelNames.size(); i++) {
            if (indexByName.put(imageChannelNames.get(i), i) != null)
                problems.add("Image has more than one channel named '" + imageChannelNames.get(i) + "'");
        }

        var nuc = new ArrayList<MixChannelsOp.ScaledChannel>();
        var cyto = new ArrayList<MixChannelsOp.ScaledChannel>();
        for (var ch : channels) {
            if (ch.getRole() == ChannelRole.IGNORE)
                continue;
            Integer idx = indexByName.get(ch.getName());
            if (idx == null) {
                problems.add("Channel '" + ch.getName() + "' (" + ch.getRole() + ") is not in this image");
                continue;
            }
            if (!(ch.getHigh() > ch.getLow())) {
                problems.add("Channel '" + ch.getName() + "': high (" + ch.getHigh() + ") must be larger than low (" + ch.getLow() + ")");
                continue;
            }
            var scaled = new MixChannelsOp.ScaledChannel(idx, ch.getLow(), ch.getHigh());
            (ch.getRole() == ChannelRole.NUCLEUS ? nuc : cyto).add(scaled);
        }
        if (requireNucleus && nuc.isEmpty() && problems.isEmpty())
            problems.add("No channel is assigned to the nucleus");
        if (!problems.isEmpty())
            throw new IllegalArgumentException(String.join("\n", problems));
        return new MixChannelsOp(nuc, cyto, getCombine(), clip);
    }

    /**
     * Make sure there is a {@link ChannelSetting} for every image channel (new ones are IGNORE, 0–1) and return
     * them in image order. These are the same objects as in {@link #getChannels()}, so editing them edits the settings.
     * Settings for channels that are not in this image are kept (other images in the project may have them).
     */
    public List<ChannelSetting> alignTo(List<String> imageChannelNames) {
        Map<String, ChannelSetting> byName = new HashMap<>();
        for (var ch : channels)
            byName.putIfAbsent(ch.getName(), ch);
        var result = new ArrayList<ChannelSetting>(imageChannelNames.size());
        for (var name : imageChannelNames) {
            var ch = byName.get(name);
            if (ch == null) {
                ch = new ChannelSetting(name, ChannelRole.IGNORE, 0, 1);
                channels.add(ch);
                byName.put(name, ch);
            }
            result.add(ch);
        }
        return result;
    }

    /** Default settings for an image: all channels ignored, low/high = 0/1. */
    public static MixerSettings createDefault(List<String> imageChannelNames) {
        var settings = new MixerSettings();
        for (var name : imageChannelNames)
            settings.channels.add(new ChannelSetting(name, ChannelRole.IGNORE, 0, 1));
        return settings;
    }
}
