package myau.setup;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SetupDownloaderTest {

    private static SetupEntry entry(String url) {
        return new SetupEntry(SetupEntry.Section.MODS, SetupEntry.Source.DIRECT,
                "X", "", "x.jar", url, 0, false);
    }

    @Test
    public void httpLinksCountAsLinked() {
        assertTrue(SetupDownloader.hasLink(entry("https://example.com/x.jar")));
        assertTrue(SetupDownloader.hasLink(entry("http://example.com/x.jar")));
    }

    @Test
    public void blankOrNonHttpLinksDoNot() {
        assertFalse(SetupDownloader.hasLink(entry("")));
        assertFalse(SetupDownloader.hasLink(entry(null)));
        assertFalse(SetupDownloader.hasLink(entry("ftp://example.com/x.jar")));
        assertFalse(SetupDownloader.hasLink(null));
    }

    @Test
    public void jarsAndZipsAreArchives() {
        assertTrue(SetupDownloader.isArchive("Controlling-7.0.0.1.jar"));
        assertTrue(SetupDownloader.isArchive("Stewound.ZIP"));
        assertFalse(SetupDownloader.isArchive("installer.exe"));
        assertFalse(SetupDownloader.isArchive("index.html"));
        assertFalse(SetupDownloader.isArchive(null));
    }

    @Test
    public void mediaFireShareButtonIsScraped() {
        String html = "<a class=\"input popsok\" aria-label=\"Download file\" "
                + "href=\"https://download2390.mediafire.com/abc123/Stewound.zip\" id=\"downloadButton\">";
        assertEquals("https://download2390.mediafire.com/abc123/Stewound.zip",
                SetupDownloader.extractShareLink(html));
    }

    @Test
    public void mediaFireDirectHrefFallbackWorks() {
        String html = "<a href=\"https://download1234.mediafire.com/xyz/file.zip\">click</a>";
        assertEquals("https://download1234.mediafire.com/xyz/file.zip",
                SetupDownloader.extractShareLink(html));
    }

    @Test
    public void mediaFireLinkIsEntityDecoded() {
        String html = "<a href=\"https://download1.mediafire.com/a/b.zip?x=1&amp;y=2\">d</a>";
        assertEquals("https://download1.mediafire.com/a/b.zip?x=1&y=2",
                SetupDownloader.extractShareLink(html));
    }

    @Test
    public void missingLinkReturnsNull() {
        assertNull(SetupDownloader.extractShareLink("<html><body>no button here</body></html>"));
        assertNull(SetupDownloader.extractShareLink(null));
    }
}
