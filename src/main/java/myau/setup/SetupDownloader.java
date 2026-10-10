package myau.setup;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches catalog entries into the right folder.
 *
 * <p>Ported from OpenSkid (GPL-3.0), with a few hardening changes:
 *
 * <ul>
 *   <li>A jar or zip must start with the {@code PK} zip magic. Hosts happily
 *       answer 200 with an HTML error page or a login wall, and the original
 *       size-only check saved those into {@code mods/}.</li>
 *   <li>Transient failures retry {#ATTEMPTS} times with a growing backoff.</li>
 *   <li>A download can be cancelled from the UI, mid-transfer.</li>
 *   <li>At most {#MAX_PARALLEL} run at once, so selecting everything does not
 *       open twenty connections to the same CDN.</li>
 *   <li>Downloads land in a {@code .download} temp file and are renamed only
 *       once they are complete and valid.</li>
 * </ul>
 */
public final class SetupDownloader {
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final long MAX_BYTES = 128L * 1024L * 1024L;
    private static final int ATTEMPTS = 3;
    private static final int MAX_PARALLEL = 3;
    private static final String USER_AGENT = "Myau-Atlas-Setup";

    private static final Pattern SHARE_BUTTON =
            Pattern.compile("aria-label=\"Download file\"[^>]*href=\"([^\"]+)\"");
    private static final Pattern DIRECT_FALLBACK =
            Pattern.compile("href=\"(https://download\\d+\\.mediafire\\.com/[^\"]+)\"");

    private static final ExecutorService POOL = Executors.newFixedThreadPool(MAX_PARALLEL, new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "Myau-Setup-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    private SetupDownloader() {
    }

    public interface ProgressListener {
        void onProgress(long downloaded, long total);

        void onDone(File file);

        void onError(String message);
    }

    /** A queued or running download; the UI keeps this to offer a Cancel. */
    public static final class Download {
        private final SetupEntry entry;
        private volatile boolean cancelled;
        private volatile boolean finished;

        private Download(SetupEntry entry) {
            this.entry = entry;
        }

        public SetupEntry entry() {
            return entry;
        }

        public void cancel() {
            this.cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public boolean isFinished() {
            return finished;
        }
    }

    public static boolean hasLink(SetupEntry entry) {
        return entry != null && entry.url != null
                && entry.url.toLowerCase(Locale.ROOT).startsWith("http");
    }

    /** Queues {@code entry} and returns at once; the listener is told what happens. */
    public static Download downloadAsync(final SetupEntry entry, final ProgressListener listener) {
        final Download handle = new Download(entry);
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                String lastError = "download failed";
                for (int attempt = 1; attempt <= ATTEMPTS && !handle.cancelled; attempt++) {
                    try {
                        File saved = download(entry, listener, handle);
                        handle.finished = true;
                        listener.onDone(saved);
                        return;
                    } catch (Exception e) {
                        lastError = e.getMessage() == null || e.getMessage().isEmpty()
                                ? "download failed" : e.getMessage();
                        if (attempt < ATTEMPTS && !handle.cancelled) {
                            try {
                                Thread.sleep(500L * attempt);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
                handle.finished = true;
                if (!handle.cancelled) {
                    listener.onError(lastError);
                }
            }
        });
        return handle;
    }

    private static File download(SetupEntry entry, ProgressListener listener, Download handle) throws Exception {
        if (!hasLink(entry)) {
            throw new Exception("no download link yet");
        }
        String direct = resolveDirectUrl(entry, handle);
        File dir = SetupScanner.targetDir(entry);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new Exception("could not create the " + dir.getName() + " folder");
        }
        File temp = new File(dir, entry.fileName + ".download");
        File dest = new File(dir, entry.fileName);
        if (temp.exists() && !temp.delete()) {
            throw new Exception("could not clear the old download");
        }

        URLConnection connection = new URL(direct).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setUseCaches(false);
        connection.connect();
        if (connection instanceof HttpURLConnection) {
            int code = ((HttpURLConnection) connection).getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("server said " + code);
            }
        }
        long total = connection.getContentLengthLong();
        if (entry.expectedBytes > 0) {
            total = entry.expectedBytes;
        }

        InputStream in = connection.getInputStream();
        FileOutputStream out = new FileOutputStream(temp);
        boolean valid = false;
        try {
            byte[] buffer = new byte[32768];
            long downloaded = 0;
            int first = -1;
            int second = -1;
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (handle.cancelled) {
                    throw new Exception("cancelled");
                }
                if (downloaded == 0 && read > 0) {
                    first = buffer[0] & 0xFF;
                    second = read > 1 ? buffer[1] & 0xFF : -1;
                }
                downloaded += read;
                if (downloaded > MAX_BYTES) {
                    throw new Exception("file too large (over 128MB)");
                }
                out.write(buffer, 0, read);
                listener.onProgress(downloaded, total);
            }
            if (isArchive(entry.fileName) && !(first == 'P' && second == 'K')) {
                throw new Exception("not a valid jar or zip (the host sent a web page)");
            }
            valid = true;
        } finally {
            close(in);
            close(out);
            // Any failure -- bad content, cancelled, too large, a broken read --
            // must not leave a half-written .download file behind for the next
            // attempt or the user to trip over.
            if (!valid) {
                temp.delete();
            }
        }

        if (entry.expectedBytes > 0 && temp.length() != entry.expectedBytes) {
            temp.delete();
            throw new Exception("size mismatch, try again");
        }
        if (entry.expectedBytes == 0 && temp.length() < 4096) {
            temp.delete();
            throw new Exception("file too small, try again");
        }
        if (dest.exists() && !dest.delete()) {
            temp.delete();
            throw new Exception("could not replace " + dest.getName());
        }
        if (!temp.renameTo(dest)) {
            temp.delete();
            throw new Exception("could not save the file");
        }
        return dest;
    }

    static String resolveDirectUrl(SetupEntry entry, Download handle) throws Exception {
        if (entry.source == SetupEntry.Source.DIRECT) {
            return entry.url;
        }
        String page = fetchText(entry.url, handle);
        String link = extractShareLink(page);
        if (link == null) {
            throw new Exception("could not resolve the download link");
        }
        return link;
    }

    /**
     * Pulls the direct file URL out of a MediaFire share page. Kept separate
     * from the network code so the two patterns can be tested on saved HTML.
     *
     * @return the direct URL, or null when the page has neither form
     */
    static String extractShareLink(String html) {
        if (html == null) {
            return null;
        }
        Matcher button = SHARE_BUTTON.matcher(html);
        if (button.find()) {
            return unescape(button.group(1));
        }
        Matcher fallback = DIRECT_FALLBACK.matcher(html);
        if (fallback.find()) {
            return unescape(fallback.group(1));
        }
        return null;
    }

    /** A catalog file that has to be a zip container: a mod jar or a resource pack. */
    static boolean isArchive(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jar") || lower.endsWith(".zip");
    }

    private static String fetchText(String url, Download handle) throws Exception {
        URLConnection connection = new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setUseCaches(false);
        connection.connect();
        InputStream in = connection.getInputStream();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1 && raw.size() < 1024 * 1024) {
                if (handle.cancelled) {
                    throw new Exception("cancelled");
                }
                raw.write(buffer, 0, read);
            }
            return raw.toString("UTF-8");
        } finally {
            close(in);
        }
    }

    private static String unescape(String url) {
        return url.replace("&amp;", "&");
    }

    private static void close(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }
}
