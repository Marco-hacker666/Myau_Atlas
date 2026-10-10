package myau.config.online;

// Ported from OpenSkid (GPL-3.0), itself adapted from the MiauMinus
// online-config concept. One cached shared config, as the cache lists it.
public class OnlineConfigEntry {
    private final String id;
    private final String name;
    private final String source;
    private final long cachedAt;
    private final long size;

    public OnlineConfigEntry(String id, String name, String source, long cachedAt, long size) {
        this.id = id;
        this.name = name;
        this.source = source;
        this.cachedAt = cachedAt;
        this.size = size;
    }

    public String getId() {
        if (id != null && !id.trim().isEmpty()) {
            return id;
        }
        return name != null ? name : "";
    }

    public String getName() {
        if (name != null && !name.trim().isEmpty()) {
            return name;
        }
        return getId();
    }

    public String getSource() {
        return source != null ? source : "unknown";
    }

    public long getCachedAt() {
        return Math.max(0L, cachedAt);
    }

    public long getSize() {
        return Math.max(0L, size);
    }
}
