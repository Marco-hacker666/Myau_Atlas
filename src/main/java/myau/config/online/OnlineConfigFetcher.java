package myau.config.online;

// Ported from OpenSkid (GPL-3.0), itself adapted from the MiauMinus
// online-config concept.
// Fetches raw JSON text only. Fetched data is parsed as data and applied per
// property by OnlineConfigApplier. It is never executed as code.
public final class OnlineConfigFetcher {
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int MAX_BYTES = 512 * 1024;

    private OnlineConfigFetcher() {
    }

    public static boolean looksLikeUrl(String source) {
        if (source == null) {
            return false;
        }
        String lower = source.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    public static String fetch(String source) throws java.io.IOException {
        if (source == null || source.trim().isEmpty()) {
            throw new java.io.IOException("Empty source");
        }
        if (looksLikeUrl(source)) {
            return fetchUrl(source.trim());
        }
        return fetchFile(source.trim());
    }

    private static String fetchUrl(String url) throws java.io.IOException {
        java.net.URLConnection connection = new java.net.URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "Myau");
        connection.setUseCaches(false);
        connection.connect();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        java.io.InputStream in = connection.getInputStream();
        try {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BYTES) {
                    throw new java.io.IOException("Remote config too large (over 512KB)");
                }
                out.write(buffer, 0, read);
            }
        } finally {
            try {
                in.close();
            } catch (java.io.IOException ignored) {
            }
        }
        return out.toString("UTF-8");
    }

    private static String fetchFile(String path) throws java.io.IOException {
        java.io.File file = new java.io.File(path);
        if (!file.isFile()) {
            throw new java.io.FileNotFoundException("File not found (" + path + ")");
        }
        if (file.length() > MAX_BYTES) {
            throw new java.io.IOException("Config file too large (over 512KB)");
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        java.io.InputStream in = new java.io.FileInputStream(file);
        try {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } finally {
            try {
                in.close();
            } catch (java.io.IOException ignored) {
            }
        }
        return out.toString("UTF-8");
    }
}
