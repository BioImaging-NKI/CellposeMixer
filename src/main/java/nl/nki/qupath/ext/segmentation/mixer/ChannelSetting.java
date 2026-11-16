package nl.nki.qupath.ext.segmentation.mixer;

/**
 * Settings for one image channel. Scaled value = (x - low) / (high - low).
 */
public class ChannelSetting {

    private String name;
    private ChannelRole role = ChannelRole.IGNORE;
    private double low;
    private double high;

    public ChannelSetting() {}

    public ChannelSetting(String name, ChannelRole role, double low, double high) {
        this.name = name;
        this.role = role;
        this.low = low;
        this.high = high;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public ChannelRole getRole() { return role == null ? ChannelRole.IGNORE : role; }
    public void setRole(ChannelRole role) { this.role = role; }

    public double getLow() { return low; }
    public void setLow(double low) { this.low = low; }

    public double getHigh() { return high; }
    public void setHigh(double high) { this.high = high; }

    @Override
    public String toString() {
        return name + " [" + getRole() + ", " + low + " - " + high + "]";
    }
}
