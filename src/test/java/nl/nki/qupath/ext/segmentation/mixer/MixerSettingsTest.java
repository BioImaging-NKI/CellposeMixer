package nl.nki.qupath.ext.segmentation.mixer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MixerSettingsTest {

    private static final List<String> IMAGE_CHANNELS = List.of("DAPI", "CD45", "Ki67", "PanCK");

    private static MixerSettings example() {
        var s = new MixerSettings();
        s.getChannels().add(new ChannelSetting("DAPI", ChannelRole.NUCLEUS, 100, 3000));
        s.getChannels().add(new ChannelSetting("CD45", ChannelRole.CYTO, 50, 1000));
        s.getChannels().add(new ChannelSetting("Ki67", ChannelRole.IGNORE, 0, 1));
        s.getChannels().add(new ChannelSetting("PanCK", ChannelRole.CYTO, 300, 4000));
        return s;
    }

    @Test
    void createOpMatchesChannelsByName() {
        // Image with a different channel order than the settings
        var op = example().createOp(List.of("PanCK", "Ki67", "DAPI", "CD45"));
        assertEquals(List.of(2), op.getNucleus().stream().map(MixChannelsOp.ScaledChannel::index).toList());
        assertEquals(List.of(3, 0), op.getCyto().stream().map(MixChannelsOp.ScaledChannel::index).toList());
    }

    @Test
    void createOpIgnoresMissingIgnoredChannels() {
        var op = example().createOp(List.of("DAPI", "CD45", "PanCK"));
        assertEquals(1, op.getNucleus().size());
    }

    @Test
    void createOpReportsAllProblems() {
        var s = example();
        s.getChannels().get(1).setHigh(10); // CD45 high < low
        var e = assertThrows(IllegalArgumentException.class, () -> s.createOp(List.of("DAPI", "CD45", "Ki67")));
        assertTrue(e.getMessage().contains("CD45"), e.getMessage());
        assertTrue(e.getMessage().contains("PanCK"), e.getMessage());
    }

    @Test
    void createOpRequiresNucleus() {
        var s = MixerSettings.createDefault(IMAGE_CHANNELS);
        assertThrows(IllegalArgumentException.class, () -> s.createOp(IMAGE_CHANNELS));
    }

    @Test
    void alignToAddsMissingAndKeepsOthers() {
        var s = example();
        var aligned = s.alignTo(List.of("PanCK", "CD8", "DAPI"));
        assertEquals(List.of("PanCK", "CD8", "DAPI"), aligned.stream().map(ChannelSetting::getName).toList());
        assertEquals(ChannelRole.IGNORE, aligned.get(1).getRole());
        assertSame(s.getChannels().get(3), aligned.get(0)); // same object -> edits go to the settings
        // CD45 and Ki67 are not in this image but stay in the settings; CD8 was added
        assertEquals(5, s.getChannels().size());
    }

    @Test
    void jsonRoundTrip() {
        var s = example();
        s.setCombine(CombineMode.MAX);
        s.setClip(false);
        s.getCellpose().setDiameterMicrons(7.5);
        s.getCellpose().setNormalization(CellposeParameters.Normalization.NONE);

        var copy = MixerSettingsIO.fromJson(MixerSettingsIO.toJson(s));
        assertEquals(CombineMode.MAX, copy.getCombine());
        assertFalse(copy.isClip());
        assertEquals(7.5, copy.getCellpose().getDiameterMicrons());
        assertEquals(CellposeParameters.Normalization.NONE, copy.getCellpose().getNormalization());
        assertNull(copy.getCellpose().getPixelSizeMicrons());
        assertEquals(4, copy.getChannels().size());
        assertEquals(ChannelRole.CYTO, copy.getChannels().get(3).getRole());
        assertEquals(4000, copy.getChannels().get(3).getHigh());
    }

    @Test
    void projectHistoryNewestFirst(@TempDir Path projectDir) throws Exception {
        assertTrue(MixerSettingsIO.loadLatest(projectDir).isEmpty());

        var first = example();
        Path f1 = MixerSettingsIO.saveToProject(first, projectDir);
        var second = example();
        second.setCombine(CombineMode.MAX);
        Path f2 = MixerSettingsIO.saveToProject(second, projectDir); // same second -> counter suffix

        assertNotEquals(f1, f2);
        assertTrue(Files.exists(f1) && Files.exists(f2));
        var history = MixerSettingsIO.listHistory(projectDir);
        assertEquals(List.of(f2, f1), history);
        assertEquals(CombineMode.MAX, MixerSettingsIO.loadLatest(projectDir).orElseThrow().getCombine());
        assertNotNull(MixerSettingsIO.loadLatest(projectDir).orElseThrow().getSavedAt());
    }

    @Test
    void importLegacyFormat() {
        String json = """
                [
                  {"name": "DAPI",  "nuclear": true,  "membrane": false, "low": 120.5, "high": 2900.0},
                  {"name": "CD45",  "nuclear": false, "membrane": true,  "low": 60.0,  "high": 900.0},
                  {"name": "Ki67",  "nuclear": false, "membrane": false, "low": 10.0,  "high": 500.0},
                  {"name": "PanCK", "nuclear": true,  "membrane": true,  "low": 30.0,  "high": 800.0}
                ]""";
        var result = MixerSettingsIO.importLegacy(json, IMAGE_CHANNELS);
        var ch = result.settings().getChannels();
        assertEquals(ChannelRole.NUCLEUS, ch.get(0).getRole());
        assertEquals(120.5, ch.get(0).getLow());
        assertEquals(ChannelRole.CYTO, ch.get(1).getRole());
        assertEquals(ChannelRole.IGNORE, ch.get(2).getRole());
        assertEquals(ChannelRole.NUCLEUS, ch.get(3).getRole()); // both -> nucleus, with warning
        assertFalse(result.settings().isClip());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("PanCK")));
    }

    @Test
    void importLegacyWithoutNamesUsesPosition() {
        String json = """
                [{"nuclear": true, "membrane": false, "low": 1, "high": 2},
                 {"nuclear": false, "membrane": true, "low": 3, "high": 4}]""";
        var result = MixerSettingsIO.importLegacy(json, List.of("DAPI", "CD45"));
        assertEquals("DAPI", result.settings().getChannels().get(0).getName());
        assertEquals("CD45", result.settings().getChannels().get(1).getName());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("count") || w.contains("position")));
    }

    @Test
    void readingLegacyAsNewFormatGivesClearError() {
        assertThrows(IllegalArgumentException.class, () -> MixerSettingsIO.fromJson("[]"));
    }
}
