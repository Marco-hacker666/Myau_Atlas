package myau.ui.impl.clickgui.riselb;

import myau.Myau;
import myau.module.Module;
import myau.module.modules.ClickGUIModule;
import myau.util.AnimationUtil;
import myau.util.RenderUtil;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ClickGUI ported from the RiseLB LiquidBounce theme (ClickGui.svelte).
 *
 * One centred, draggable window rather than floating per-category panels:
 * a fixed-width sidebar carrying the logo, a search field and the category
 * nav, and a content pane with a heading, a list/grid toggle and a scrolling
 * list of module cards.
 *
 * Search spans every category rather than filtering only the selected one,
 * which is what the Svelte version does -- typing is the fast path to a
 * module whose category you do not remember.
 */
public class RiseLBClickGui extends GuiScreen {

    private static final long ANIMATION_DURATION = 220L;
    private static final double FRICTION = 0.85;
    private static final double SNAP = 0.18;

    private static final float MIN_WIDTH = 470.0f;
    private static final float MIN_HEIGHT = 300.0f;
    private static final float HEAD_HEIGHT = 26.0f;

    private static RiseLBClickGui instance;

    private final Map<String, List<RiseLBCard>> categories = new LinkedHashMap<String, List<RiseLBCard>>();
    private final List<String> categoryNames = new ArrayList<String>();

    private String selected;
    private String query = "";
    private boolean searchFocused;
    private boolean grid;

    private float windowX = Float.NaN;
    private float windowY = Float.NaN;
    private float windowW;
    private float windowH;

    private boolean dragging;
    private float dragX;
    private float dragY;

    private double velocity;
    private float scroll;
    private float targetScroll;

    private boolean closing;
    private long openTime;
    private long lastFrame;

    public RiseLBClickGui() {
        for (Map.Entry<String, List<Module>> entry : Categories.build().entrySet()) {
            List<RiseLBCard> cards = new ArrayList<RiseLBCard>();
            for (Module module : entry.getValue()) {
                cards.add(new RiseLBCard(module, entry.getKey()));
            }
            categories.put(entry.getKey(), cards);
            categoryNames.add(entry.getKey());
        }
        selected = categoryNames.isEmpty() ? null : categoryNames.get(0);
    }

    public static RiseLBClickGui getInstance() {
        if (instance == null) {
            instance = new RiseLBClickGui();
        }
        return instance;
    }

    public static void resetInstance() {
        instance = null;
    }

    @Override
    public void initGui() {
        super.initGui();
        myau.util.font.FontManager.initializeFonts();
        closing = false;
        openTime = System.currentTimeMillis();
        lastFrame = System.nanoTime();
        velocity = 0.0;
        scroll = 0.0f;
        targetScroll = 0.0f;

        ClickGUIModule module = RiseLBTheme.module();
        windowW = Math.max(MIN_WIDTH, module != null ? module.windowWidth.getValue() : 585);
        windowH = Math.max(MIN_HEIGHT, module != null ? module.windowHeight.getValue() : 400);

        ScaledResolution sr = new ScaledResolution(mc);
        if (Float.isNaN(windowX)) {
            windowX = (sr.getScaledWidth() - windowW) / 2.0f;
            windowY = (sr.getScaledHeight() - windowH) / 2.0f;
        }
    }

    public void close() {
        if (closing) {
            return;
        }
        closing = true;
        openTime = System.currentTimeMillis();
    }

    /** Cards currently shown: the search hits across all categories, or the selected category. */
    private List<RiseLBCard> visibleCards() {
        List<RiseLBCard> out = new ArrayList<RiseLBCard>();
        if (!query.isEmpty()) {
            String needle = query.toLowerCase();
            for (List<RiseLBCard> cards : categories.values()) {
                for (RiseLBCard card : cards) {
                    if (card.getModule().getName().toLowerCase().contains(needle)) {
                        out.add(card);
                    }
                }
            }
            return out;
        }
        List<RiseLBCard> cards = categories.get(selected);
        return cards != null ? cards : out;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        long now = System.nanoTime();
        float deltaTime = (now - lastFrame) / 1_000_000_000.0f;
        lastFrame = now;

        long elapsed = System.currentTimeMillis() - openTime;
        if (closing && elapsed > ANIMATION_DURATION) {
            mc.displayGuiScreen(null);
            return;
        }
        float progress = closing
                ? 1.0f - Math.min(1.0f, (float) elapsed / ANIMATION_DURATION)
                : Math.min(1.0f, (float) elapsed / ANIMATION_DURATION);
        progress = RiseLBTheme.spring(progress);
        if (progress <= 0.01f) {
            return;
        }

        updateScroll(deltaTime);

        RiseLBTheme.windowShadow(windowX, windowY, windowW, windowH, progress);
        RiseLBTheme.panel(windowX, windowY, windowW, windowH, RiseLBTheme.WINDOW_RADIUS,
                RiseLBTheme.BG, progress);

        renderSidebar(mouseX, mouseY, progress, deltaTime);
        renderContent(mouseX, mouseY, progress, deltaTime);

        try {
            Module invWalk = Myau.moduleManager.getModule("InvWalk");
            if (invWalk != null && invWalk.isEnabled()) {
                handleInvWalk();
            }
        } catch (Exception ignored) {
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void renderSidebar(int mouseX, int mouseY, float progress, float deltaTime) {
        float w = RiseLBTheme.SIDEBAR_WIDTH;
        // Square off the inner edge so the sidebar meets the body cleanly.
        RenderUtil.drawRoundedRect(windowX, windowY, w, windowH, RiseLBTheme.WINDOW_RADIUS,
                RiseLBTheme.solid(RiseLBTheme.SIDEBAR, progress), true, false, false, true);

        float x = windowX + RiseLBTheme.SIDEBAR_PAD_X;
        float y = windowY + RiseLBTheme.SIDEBAR_PAD_Y;
        float innerW = w - RiseLBTheme.SIDEBAR_PAD_X * 2.0f;

        RiseLBTheme.draw(24, "Myau", x + 4.0f, y, RiseLBTheme.solid(RiseLBTheme.TEXT, progress));
        RiseLBTheme.draw(12, "2.1", x + 4.0f + RiseLBTheme.width(24, "Myau") + 3.0f, y,
                RiseLBTheme.rgba(RiseLBTheme.accent(), (int) (255 * progress)));
        y += RiseLBTheme.height(24) + 12.0f;

        // Search field.
        float searchH = 20.0f;
        boolean filled = !query.isEmpty();
        RenderUtil.drawRoundedRect(x, y, innerW, searchH, RiseLBTheme.CONTROL_RADIUS,
                RiseLBTheme.fade(searchFocused || filled ? RiseLBTheme.FIELD_BG_FOCUS : RiseLBTheme.FIELD_BG, progress),
                true, true, true, true);
        if (searchFocused || filled) {
            RiseLBTheme.border(x, y, innerW, searchH, RiseLBTheme.CONTROL_RADIUS,
                    RiseLBTheme.rgba(RiseLBTheme.accent(), 255), progress);
        }
        String shown = filled ? query : "Search";
        int searchColor = filled
                ? RiseLBTheme.solid(RiseLBTheme.TEXT, progress)
                : RiseLBTheme.solid(RiseLBTheme.TEXT_DIM, progress);
        RiseLBTheme.draw(12, shown + (searchFocused ? "_" : ""), x + 8.0f,
                RiseLBTheme.textY(y, searchH, 12), searchColor);
        y += searchH + 10.0f;

        for (String name : categoryNames) {
            boolean active = !filled && name.equals(selected);
            boolean hovered = mouseX >= x && mouseX <= x + innerW
                    && mouseY >= y && mouseY <= y + RiseLBTheme.NAV_ITEM_HEIGHT;
            if (active) {
                RenderUtil.drawRoundedRect(x, y, innerW, RiseLBTheme.NAV_ITEM_HEIGHT,
                        RiseLBTheme.CONTROL_RADIUS,
                        RiseLBTheme.rgba(RiseLBTheme.accent(), (int) (255 * progress)),
                        true, true, true, true);
            } else if (hovered) {
                RenderUtil.drawRoundedRect(x, y, innerW, RiseLBTheme.NAV_ITEM_HEIGHT,
                        RiseLBTheme.CONTROL_RADIUS,
                        RiseLBTheme.fade(new Color(255, 255, 255, 16).getRGB(), progress),
                        true, true, true, true);
            }
            int color = active
                    ? RiseLBTheme.rgba(Color.WHITE, (int) (255 * progress))
                    : RiseLBTheme.solid(hovered ? RiseLBTheme.TEXT : RiseLBTheme.TEXT_DIM, progress);
            RiseLBTheme.draw(12, name, x + 9.0f + (hovered && !active ? 2.0f : 0.0f),
                    RiseLBTheme.textY(y, RiseLBTheme.NAV_ITEM_HEIGHT, 12), color);
            y += RiseLBTheme.NAV_ITEM_HEIGHT + RiseLBTheme.NAV_GAP;
        }
    }

    private float contentX() {
        return windowX + RiseLBTheme.SIDEBAR_WIDTH + RiseLBTheme.CONTENT_PAD_X;
    }

    private float contentWidth() {
        return windowW - RiseLBTheme.SIDEBAR_WIDTH - RiseLBTheme.CONTENT_PAD_X * 2.0f;
    }

    private float listTop() {
        return windowY + RiseLBTheme.CONTENT_PAD_Y + HEAD_HEIGHT;
    }

    private float listHeight() {
        return windowH - RiseLBTheme.CONTENT_PAD_Y * 2.0f - HEAD_HEIGHT;
    }

    private void renderContent(int mouseX, int mouseY, float progress, float deltaTime) {
        float x = contentX();
        float w = contentWidth();
        float y = windowY + RiseLBTheme.CONTENT_PAD_Y;

        String heading = query.isEmpty() ? String.valueOf(selected) : "Search";
        RiseLBTheme.draw(16, heading, x, y, RiseLBTheme.solid(RiseLBTheme.TEXT, progress));

        // list / grid toggle
        float toggleSize = 18.0f;
        float toggleY = y - 2.0f;
        float gridX = x + w - toggleSize;
        float listX = gridX - toggleSize - 3.0f;
        drawViewButton(listX, toggleY, toggleSize, !grid, false, progress);
        drawViewButton(gridX, toggleY, toggleSize, grid, true, progress);

        List<RiseLBCard> cards = visibleCards();
        float top = listTop();
        float height = listHeight();

        RenderUtil.scissor(x, top, w, height);
        int columns = grid ? 2 : 1;
        float columnWidth = columns == 1 ? w : (w - RiseLBTheme.CARD_GAP) / 2.0f;
        float cursorY = top - scroll;
        float rowHeight = 0.0f;

        for (int i = 0; i < cards.size(); i++) {
            RiseLBCard card = cards.get(i);
            int column = i % columns;
            float cardX = x + column * (columnWidth + RiseLBTheme.CARD_GAP);
            card.setBounds(cardX, cursorY, columnWidth);

            boolean offscreen = cursorY + card.getHeight() < top || cursorY > top + height;
            if (!offscreen) {
                card.render(mouseX, mouseY, progress, deltaTime, false);
            }
            rowHeight = Math.max(rowHeight, card.getHeight());
            if (column == columns - 1 || i == cards.size() - 1) {
                cursorY += rowHeight + RiseLBTheme.CARD_GAP;
                rowHeight = 0.0f;
            }
        }
        RenderUtil.releaseScissor();

        if (cards.isEmpty()) {
            String empty = "No modules found";
            RiseLBTheme.draw(12, empty, x + (w - RiseLBTheme.width(12, empty)) / 2.0f, top + 18.0f,
                    RiseLBTheme.solid(RiseLBTheme.TEXT_DIM, progress));
        }
    }

    private void drawViewButton(float x, float y, float size, boolean active, boolean isGrid, float progress) {
        if (active) {
            RenderUtil.drawRoundedRect(x, y, size, size, 6.0f,
                    RiseLBTheme.rgba(RiseLBTheme.accent(), (int) (255 * progress)), true, true, true, true);
        }
        int color = active
                ? RiseLBTheme.rgba(Color.WHITE, (int) (255 * progress))
                : RiseLBTheme.solid(RiseLBTheme.TEXT_DIM, progress);
        float pad = 5.0f;
        float inner = size - pad * 2.0f;
        if (isGrid) {
            float cell = (inner - 1.5f) / 2.0f;
            for (int row = 0; row < 2; row++) {
                for (int col = 0; col < 2; col++) {
                    RenderUtil.drawRect(x + pad + col * (cell + 1.5f), y + pad + row * (cell + 1.5f),
                            x + pad + col * (cell + 1.5f) + cell, y + pad + row * (cell + 1.5f) + cell, color);
                }
            }
        } else {
            for (int row = 0; row < 3; row++) {
                float lineY = y + pad + row * (inner / 3.0f);
                RenderUtil.drawRect(x + pad, lineY, x + pad + inner, lineY + 1.5f, color);
            }
        }
    }

    private void updateScroll(float deltaTime) {
        targetScroll += (float) velocity;
        velocity *= FRICTION;
        float max = maxScroll();
        if (targetScroll < 0.0f) {
            targetScroll = 0.0f;
        } else if (targetScroll > max) {
            targetScroll = max;
        }
        scroll = AnimationUtil.animateSmooth(targetScroll, scroll, 16.0f, deltaTime);
        if (Math.abs(velocity) < 0.4) {
            velocity = 0.0;
        }
    }

    private float maxScroll() {
        List<RiseLBCard> cards = visibleCards();
        int columns = grid ? 2 : 1;
        float total = 0.0f;
        float rowHeight = 0.0f;
        for (int i = 0; i < cards.size(); i++) {
            rowHeight = Math.max(rowHeight, cards.get(i).getHeight());
            if (i % columns == columns - 1 || i == cards.size() - 1) {
                total += rowHeight + RiseLBTheme.CARD_GAP;
                rowHeight = 0.0f;
            }
        }
        return Math.max(0.0f, total - listHeight());
    }

    private void handleInvWalk() {
        KeyBinding[] keys = {
                mc.gameSettings.keyBindForward, mc.gameSettings.keyBindBack,
                mc.gameSettings.keyBindLeft, mc.gameSettings.keyBindRight,
                mc.gameSettings.keyBindJump, mc.gameSettings.keyBindSprint,
                mc.gameSettings.keyBindSneak
        };
        for (KeyBinding key : keys) {
            KeyBinding.setKeyBindState(key.getKeyCode(), Keyboard.isKeyDown(key.getKeyCode()));
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        if (closing) {
            return;
        }
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            velocity += wheel > 0 ? -22.0 : 22.0;
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (closing) {
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);

        boolean insideWindow = mouseX >= windowX && mouseX <= windowX + windowW
                && mouseY >= windowY && mouseY <= windowY + windowH;
        searchFocused = false;

        if (!insideWindow) {
            return;
        }

        float sidebarRight = windowX + RiseLBTheme.SIDEBAR_WIDTH;
        if (mouseX <= sidebarRight) {
            float x = windowX + RiseLBTheme.SIDEBAR_PAD_X;
            float innerW = RiseLBTheme.SIDEBAR_WIDTH - RiseLBTheme.SIDEBAR_PAD_X * 2.0f;
            float y = windowY + RiseLBTheme.SIDEBAR_PAD_Y + RiseLBTheme.height(24) + 12.0f;

            if (mouseX >= x && mouseX <= x + innerW && mouseY >= y && mouseY <= y + 20.0f) {
                searchFocused = true;
                return;
            }
            y += 30.0f;
            for (String name : categoryNames) {
                if (mouseX >= x && mouseX <= x + innerW
                        && mouseY >= y && mouseY <= y + RiseLBTheme.NAV_ITEM_HEIGHT) {
                    selected = name;
                    query = "";
                    scroll = 0.0f;
                    targetScroll = 0.0f;
                    velocity = 0.0;
                    return;
                }
                y += RiseLBTheme.NAV_ITEM_HEIGHT + RiseLBTheme.NAV_GAP;
            }
            // Empty sidebar space drags the window.
            beginDrag(mouseX, mouseY);
            return;
        }

        float headBottom = windowY + RiseLBTheme.CONTENT_PAD_Y + HEAD_HEIGHT;
        if (mouseY < headBottom) {
            float toggleSize = 18.0f;
            float toggleY = windowY + RiseLBTheme.CONTENT_PAD_Y - 2.0f;
            float gridX = contentX() + contentWidth() - toggleSize;
            float listX = gridX - toggleSize - 3.0f;
            if (mouseY >= toggleY && mouseY <= toggleY + toggleSize) {
                if (mouseX >= listX && mouseX <= listX + toggleSize) {
                    grid = false;
                    return;
                }
                if (mouseX >= gridX && mouseX <= gridX + toggleSize) {
                    grid = true;
                    return;
                }
            }
            beginDrag(mouseX, mouseY);
            return;
        }

        if (mouseY > listTop() + listHeight()) {
            beginDrag(mouseX, mouseY);
            return;
        }

        for (RiseLBCard card : visibleCards()) {
            if (card.mouseClicked(mouseX, mouseY, mouseButton)) {
                return;
            }
        }
    }

    private void beginDrag(int mouseX, int mouseY) {
        dragging = true;
        dragX = mouseX - windowX;
        dragY = mouseY - windowY;
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        dragging = false;
        for (RiseLBCard card : visibleCards()) {
            card.mouseReleased(mouseX, mouseY, state);
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, button, timeSinceLastClick);
        if (dragging) {
            windowX = mouseX - dragX;
            windowY = mouseY - dragY;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (closing || System.currentTimeMillis() - openTime < 100) {
            return;
        }

        for (RiseLBCard card : visibleCards()) {
            if (card.isBinding()) {
                card.keyTyped(typedChar, keyCode);
                return;
            }
        }

        if (searchFocused) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                searchFocused = false;
                query = "";
                return;
            }
            if (keyCode == Keyboard.KEY_BACK) {
                if (!query.isEmpty()) {
                    query = query.substring(0, query.length() - 1);
                }
                return;
            }
            if (typedChar >= ' ' && typedChar != 127) {
                query = query + typedChar;
            }
            scroll = 0.0f;
            targetScroll = 0.0f;
            return;
        }

        Module clickGui = Myau.moduleManager.getModule("ClickGUI");
        if (keyCode == Keyboard.KEY_ESCAPE || (clickGui != null && keyCode == clickGui.getKey())) {
            close();
            return;
        }
        for (RiseLBCard card : visibleCards()) {
            card.keyTyped(typedChar, keyCode);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Module module = Myau.moduleManager.getModule("ClickGUI");
        if (module instanceof ClickGUIModule && ((ClickGUIModule) module).isSwitchingGuiStyle()) {
            return;
        }
        if (module != null) {
            module.setEnabled(false);
        }
    }
}
