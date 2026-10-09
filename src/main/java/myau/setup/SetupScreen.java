package myau.setup;

import myau.ui.UiMode;
import myau.ui.impl.gui.BackgroundRenderer;
import myau.ui.impl.gui.ModernGuiButton;
import myau.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Mouse;

import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The setup screen: tick what you want, it downloads into {@code mods/} and
 * {@code resourcepacks/} for you.
 *
 * <p>Ported from OpenSkid (GPL-3.0) and integrated with this client: the cards
 * follow the Atlas light/dark mode, downloads can be cancelled, and each
 * running row shows its transfer speed and remaining time instead of a bare
 * byte count.
 */
public class SetupScreen extends GuiScreen {
    private static final int ROW_H = 30;
    private static final int CARD_W = 440;

    private static final int ACCENT = 0xFF55FFFF;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final List<Row> rows = new ArrayList<>();
    private final Set<SetupEntry> selected = new HashSet<>();
    private final Map<SetupEntry, Long> progress = new HashMap<>();
    private final Map<SetupEntry, Long> totals = new HashMap<>();
    private final Map<SetupEntry, long[]> rate = new HashMap<>();
    private final Map<SetupEntry, SetupDownloader.Download> active = new HashMap<>();

    private boolean returnToMenu;
    private int scroll;
    private String footer = "";
    private boolean finished;
    private int autoTotal;
    private int autoDone;

    private GuiButton downloadButton;
    private GuiButton selectAllButton;
    private GuiButton rescanButton;
    private GuiButton skipButton;

    private static final class Row {
        final SetupEntry entry;
        String status;
        boolean busy;

        Row(SetupEntry entry, String status) {
            this.entry = entry;
            this.status = status;
        }
    }

    public SetupScreen() {
        rescan();
        autoTotal = SetupCatalog.autoEntries().size();
        for (final SetupEntry entry : SetupCatalog.autoEntries()) {
            if (!SetupScanner.isInstalled(entry) && SetupDownloader.hasLink(entry)) {
                footer = "Installing " + entry.displayName() + " in the background.";
                active.put(entry, SetupDownloader.downloadAsync(entry, listener(entry, true)));
            } else {
                autoDone++;
            }
        }
    }

    public void setReturnToMenu(boolean returnToMenu) {
        this.returnToMenu = returnToMenu;
    }

    private void rescan() {
        rows.clear();
        selected.clear();
        for (SetupEntry entry : SetupCatalog.all()) {
            if (entry.auto) {
                continue;
            }
            boolean installed = SetupScanner.isInstalled(entry);
            rows.add(new Row(entry, installed ? "installed" : "missing"));
            if (!installed && SetupDownloader.hasLink(entry)) {
                selected.add(entry);
            }
        }
    }

    @Override
    public void initGui() {
        buttonList.clear();
        int cx = width / 2;
        int by = height - 44;
        if (!finished) {
            downloadButton = new ModernGuiButton(1, cx - 220, by, 104, 20, "Download");
            selectAllButton = new ModernGuiButton(2, cx - 110, by, 104, 20, "Select all");
            rescanButton = new ModernGuiButton(3, cx, by, 104, 20, "Rescan");
            skipButton = new ModernGuiButton(4, cx + 110, by, 104, 20, "Skip");
            buttonList.add(downloadButton);
            buttonList.add(selectAllButton);
            buttonList.add(rescanButton);
            buttonList.add(skipButton);
        } else {
            buttonList.add(new ModernGuiButton(5, cx - 110, by, 104, 20, "Restart now"));
            buttonList.add(new ModernGuiButton(6, cx, by, 104, 20, "Later"));
        }
        refreshButtons();
    }

    private boolean anyBusy() {
        for (Row row : rows) {
            if (row.busy) {
                return true;
            }
        }
        return false;
    }

    private void refreshButtons() {
        if (finished || downloadButton == null) {
            return;
        }
        boolean busy = anyBusy();
        downloadButton.enabled = !selected.isEmpty() && !busy;
        selectAllButton.enabled = !busy;
        skipButton.enabled = !busy;
        rescanButton.displayString = busy ? "Cancel" : "Rescan";
        rescanButton.enabled = true;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 1) {
            startSelected();
        } else if (button.id == 2) {
            for (Row row : rows) {
                if (!row.busy && row.status.equals("missing") && SetupDownloader.hasLink(row.entry)) {
                    selected.add(row.entry);
                }
            }
        } else if (button.id == 3) {
            if (anyBusy()) {
                cancelAll();
            } else {
                rescan();
                progress.clear();
                totals.clear();
                rate.clear();
                footer = "Rescanned. Drop files in yourself and hit Rescan anytime.";
                checkFinished();
                refreshButtons();
            }
        } else if (button.id == 4) {
            closeDone();
        } else if (button.id == 5) {
            SetupState.markDone();
            mc.shutdown();
        } else if (button.id == 6) {
            closeDone();
        }
    }

    private void cancelAll() {
        for (SetupDownloader.Download download : active.values()) {
            download.cancel();
        }
        active.clear();
        for (Row row : rows) {
            if (row.busy) {
                row.busy = false;
                row.status = "missing";
                selected.add(row.entry);
            }
        }
        progress.clear();
        totals.clear();
        rate.clear();
        footer = "Cancelled. Click Download to try again.";
        refreshButtons();
    }

    private void closeDone() {
        SetupState.markDone();
        if (returnToMenu) {
            try {
                mc.displayGuiScreen(new myau.ui.impl.mainmenu.MyauMainMenu());
            } catch (Exception e) {
                mc.displayGuiScreen(null);
            }
        } else {
            mc.displayGuiScreen(null);
        }
    }

    private void toggle(Row row) {
        if (row.busy || row.status.equals("installed")) {
            return;
        }
        if (!SetupDownloader.hasLink(row.entry)) {
            footer = "No link yet for " + row.entry.displayName() + ".";
            return;
        }
        if (selected.contains(row.entry)) {
            selected.remove(row.entry);
        } else {
            selected.add(row.entry);
        }
    }

    private void startSelected() {
        List<SetupEntry> queue = new ArrayList<>(selected);
        selected.clear();
        for (Row row : rows) {
            if (queue.contains(row.entry)) {
                row.busy = true;
                row.status = "starting";
                active.put(row.entry, SetupDownloader.downloadAsync(row.entry, listener(row.entry, false)));
            }
        }
        refreshButtons();
    }

    private SetupDownloader.ProgressListener listener(final SetupEntry entry, final boolean silent) {
        return new SetupDownloader.ProgressListener() {
            @Override
            public void onProgress(final long downloaded, final long total) {
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        long now = System.currentTimeMillis();
                        long[] sample = rate.get(entry);
                        if (sample == null) {
                            sample = new long[]{downloaded, now, 0};
                            rate.put(entry, sample);
                        } else if (now - sample[1] >= 400) {
                            long elapsed = now - sample[1];
                            sample[2] = elapsed > 0 ? (downloaded - sample[0]) * 1000L / elapsed : 0;
                            sample[0] = downloaded;
                            sample[1] = now;
                        }
                        progress.put(entry, downloaded);
                        totals.put(entry, total);
                        Row row = find(entry);
                        if (row != null && !silent) {
                            row.status = "downloading";
                        }
                    }
                });
            }

            @Override
            public void onDone(final File file) {
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        onEntryDone(entry, silent);
                    }
                });
            }

            @Override
            public void onError(final String message) {
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        active.remove(entry);
                        Row row = find(entry);
                        if (row != null && !silent) {
                            row.busy = false;
                            row.status = "failed";
                            selected.add(entry);
                        }
                        footer = entry.displayName() + " failed: " + message + ". Click the row to retry.";
                        refreshButtons();
                    }
                });
            }
        };
    }

    private void onEntryDone(SetupEntry entry, boolean silent) {
        active.remove(entry);
        if (silent) {
            autoDone++;
            if (autoDone >= autoTotal) {
                footer = "Background installs done.";
            }
            return;
        }
        Row row = find(entry);
        if (row != null) {
            row.busy = false;
            row.status = "installed";
            progress.remove(entry);
            totals.remove(entry);
            rate.remove(entry);
        }
        if (entry.section == SetupEntry.Section.PACKS) {
            activatePack(entry.fileName);
        }
        footer = entry.displayName() + " installed.";
        checkFinished();
        refreshButtons();
    }

    private void checkFinished() {
        if (anyBusy()) {
            return;
        }
        for (Row row : rows) {
            if (row.status.equals("missing") && SetupDownloader.hasLink(row.entry)) {
                return;
            }
        }
        finish();
    }

    private void finish() {
        finished = true;
        SetupState.markDone();
        footer = "All done. Mods need a restart to load.";
        scroll = 0;
        initGui();
    }

    private Row find(SetupEntry entry) {
        for (Row row : rows) {
            if (row.entry == entry) {
                return row;
            }
        }
        return null;
    }

    private void activatePack(String fileName) {
        try {
            File file = new File(SetupScanner.packsDir(), fileName);
            if (!file.isFile() || file.length() < 4096) {
                return;
            }
            List<String> packs = mc.gameSettings.resourcePacks;
            String key = "file/" + fileName;
            if (!packs.contains(key)) {
                packs.add(0, key);
            }
            mc.gameSettings.saveOptions();
            mc.refreshResources();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scroll += wheel > 0 ? ROW_H : -ROW_H;
            if (scroll < 0) {
                scroll = 0;
            }
            int maxScroll = Math.max(0, contentHeight() - (height - 150));
            if (scroll > maxScroll) {
                scroll = maxScroll;
            }
        }
    }

    private int contentHeight() {
        int total = 0;
        SetupEntry.Section last = null;
        for (Row row : rows) {
            if (last != row.entry.section) {
                total += 26;
                last = row.entry.section;
            }
            total += ROW_H;
        }
        return total;
    }

    private int rowTop(int index) {
        int y = 86 - scroll;
        SetupEntry.Section last = null;
        for (int i = 0; i <= index && i < rows.size(); i++) {
            if (last != rows.get(i).entry.section) {
                y += 26;
                last = rows.get(i).entry.section;
            }
            if (i == index) {
                return y;
            }
            y += ROW_H;
        }
        return y;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        try {
            super.mouseClicked(mouseX, mouseY, button);
        } catch (Exception ignored) {
        }
        if (button != 0) {
            return;
        }
        int cx = width / 2;
        for (int i = 0; i < rows.size(); i++) {
            int y = rowTop(i);
            if (mouseX >= cx - CARD_W / 2 && mouseX <= cx + CARD_W / 2 && mouseY >= y && mouseY <= y + ROW_H - 4) {
                Row row = rows.get(i);
                toggle(row);
                if (row.status.equals("failed")) {
                    row.busy = true;
                    row.status = "starting";
                    selected.remove(row.entry);
                    active.put(row.entry, SetupDownloader.downloadAsync(row.entry, listener(row.entry, false)));
                    refreshButtons();
                }
                break;
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float ticks) {
        try {
            BackgroundRenderer.draw(width, height);
        } catch (Exception e) {
            drawDefaultBackground();
        }
        drawRect(0, 0, width, height, UiMode.isLight() ? 0x99FFFFFF : 0x99000000);

        boolean light = UiMode.isLight();
        int cardBg = light ? new Color(255, 255, 255, 235).getRGB() : new Color(20, 20, 22, 235).getRGB();
        int cardHover = light ? new Color(240, 244, 252, 245).getRGB() : new Color(30, 30, 34, 245).getRGB();
        int titleColor = light ? 0xFF202028 : 0xFFFFFFFF;
        int subColor = light ? 0xFF5A5A66 : 0xFFAAAAAA;
        int blurbColor = light ? 0xFF7A7A86 : 0xFF777777;
        int emptyBar = light ? 0xFFC9C9D2 : 0xFF333336;

        myau.font.FontProcess.getScaledFont("sans", 3.0f).drawCenteredString("Myau Setup", width / 2, 22, titleColor);
        drawCenteredString(fontRendererObj, "Tick what you want. Missing items download to the right folders.",
                width / 2, 52, subColor);

        int cx = width / 2;
        SetupEntry.Section lastSection = null;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (lastSection != row.entry.section) {
                int headerY = rowTop(i) - 20;
                if (headerY > 60 && headerY < height - 60) {
                    drawString(fontRendererObj,
                            row.entry.section == SetupEntry.Section.MODS ? "Mods" : "Resource packs",
                            cx - CARD_W / 2, headerY, ACCENT);
                }
                lastSection = row.entry.section;
            }
            drawRow(row, rowTop(i), mouseX, mouseY, selected.contains(row.entry), cardBg, cardHover, blurbColor, emptyBar, light);
        }
        if (!footer.isEmpty()) {
            drawCenteredString(fontRendererObj, footer, width / 2, height - 58, light ? 0xFFB07A00 : 0xFFFFFF55);
        }
        super.drawScreen(mouseX, mouseY, ticks);
    }

    private void drawRow(Row row, int y, int mouseX, int mouseY, boolean ticked,
                         int cardBg, int cardHover, int blurbColor, int emptyBar, boolean light) {
        if (y < 60 || y > height - 70) {
            return;
        }
        int cx = width / 2;
        int x = cx - CARD_W / 2;
        boolean hovered = mouseX >= x && mouseX <= x + CARD_W && mouseY >= y && mouseY <= y + ROW_H - 4;
        boolean installed = row.status.equals("installed");
        boolean failed = row.status.equals("failed");

        RenderUtil.drawRoundedRect((float) x, (float) y, (float) CARD_W, (float) (ROW_H - 4), 4.0f,
                hovered && !installed ? cardHover : cardBg, true, true, true, true);
        int barColor = installed ? GREEN : ticked ? ACCENT : failed ? RED : emptyBar;
        RenderUtil.drawRoundedRect((float) x, (float) y, 3.0f, (float) (ROW_H - 4), 1.0f,
                barColor, true, true, true, true);

        int checkColor = installed || ticked ? GREEN : (light ? 0xFFB4B4BE : 0xFF555558);
        RenderUtil.drawRoundedRectOutline((float) (x + 12), (float) (y + 7), 12.0f, 12.0f, 3.0f, 1.0f,
                checkColor, true, true, true, true);
        if (ticked || installed) {
            drawString(fontRendererObj, "X", x + 15, y + 8, GREEN);
        }

        int nameColor = installed ? GREEN : failed ? RED : (light ? 0xFF202028 : 0xFFFFFFFF);
        String label = row.entry.displayName();
        if (!SetupDownloader.hasLink(row.entry) && !installed) {
            label += " (no link yet)";
        }
        drawString(fontRendererObj, label, x + 30, y + 4, nameColor);
        drawString(fontRendererObj, row.entry.blurb, x + 30, y + 15, blurbColor);

        String status = row.status.equals("failed") ? "failed, click to retry" : row.status;
        int statusColor = installed ? GREEN : failed ? RED : row.busy ? ACCENT : (light ? 0xFF5A5A66 : 0xFFAAAAAA);
        String detail = row.busy ? transferDetail(row.entry) : status;
        if (detail != null && !detail.isEmpty()) {
            drawString(fontRendererObj, detail, x + CARD_W - fontRendererObj.getStringWidth(detail) - 10, y + 4, statusColor);
        }

        Long done = progress.get(row.entry);
        Long total = totals.get(row.entry);
        if (done != null) {
            float fraction = total != null && total > 0 ? Math.min(1.0f, (float) (done / (double) total)) : -1.0f;
            if (fraction >= 0) {
                RenderUtil.drawRoundedRect((float) (x + 30), (float) (y + ROW_H - 8),
                        (float) ((CARD_W - 40) * fraction), 2.0f, 1.0f, ACCENT, true, true, true, true);
            }
        }
    }

    /** "42% · 1.4 MB/s · 8s left" while downloading, "downloading" before the first sample. */
    private String transferDetail(SetupEntry entry) {
        Long done = progress.get(entry);
        if (done == null) {
            return "starting";
        }
        Long total = totals.get(entry);
        long[] sample = rate.get(entry);
        long speed = sample == null ? 0 : sample[2];
        StringBuilder out = new StringBuilder();
        if (total != null && total > 0) {
            out.append((int) Math.min(100L, done * 100L / total)).append('%');
        } else {
            out.append(SetupEntry.formatBytes(done));
        }
        if (speed > 0) {
            out.append(" \u00B7 ").append(SetupEntry.formatBytes(speed)).append("/s");
            if (total != null && total > done) {
                long seconds = (total - done) / speed;
                out.append(" \u00B7 ").append(seconds).append("s left");
            }
        }
        return out.toString();
    }

    @Override
    protected void keyTyped(char typed, int keyCode) {
        if (keyCode == 1 && !finished && !anyBusy()) {
            closeDone();
        }
    }
}
