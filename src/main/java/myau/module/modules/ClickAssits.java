package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LeftClickMouseEvent;
import myau.events.RightClickMouseEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.util.ItemUtil;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.world.WorldSettings.GameType;
import org.lwjgl.input.Mouse;

import java.awt.AWTException;
import java.awt.Robot;
import java.util.LinkedList;

public class ClickAssits extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private Robot bot;
    private boolean ignoreNextLeft = false;
    private boolean ignoreNextRight = false;

    private final LinkedList<Long> leftClicks = new LinkedList<>();
    private final LinkedList<Long> rightClicks = new LinkedList<>();

    public final BooleanProperty leftClick = new BooleanProperty("left-click", true);
    public final FloatProperty chanceLeft = new FloatProperty("chance-left", 80.0F, 0.0F, 100.0F, this.leftClick::getValue);
    public final BooleanProperty weaponOnly = new BooleanProperty("weapon-only", true, this.leftClick::getValue);
    public final BooleanProperty onlyWhileTargeting = new BooleanProperty("only-while-targeting", false, this.leftClick::getValue);
    public final BooleanProperty aboveCPSLeft = new BooleanProperty("above-5-cps-left", false, this.leftClick::getValue);

    public final BooleanProperty rightClick = new BooleanProperty("right-click", false);
    public final FloatProperty chanceRight = new FloatProperty("chance-right", 80.0F, 0.0F, 100.0F, this.rightClick::getValue);
    public final BooleanProperty blocksOnly = new BooleanProperty("blocks-only", true, this.rightClick::getValue);
    public final BooleanProperty aboveCPSRight = new BooleanProperty("above-5-cps-right", false, this.rightClick::getValue);

    public final BooleanProperty disableInCreative = new BooleanProperty("disable-in-creative", true);

    public ClickAssits() {
        super("ClickAssits", false);
    }

    private int getLeftCPS() {
        long currentTime = System.currentTimeMillis();
        this.leftClicks.removeIf(time -> currentTime - time > 1000L);
        return this.leftClicks.size();
    }

    private int getRightCPS() {
        long currentTime = System.currentTimeMillis();
        this.rightClicks.removeIf(time -> currentTime - time > 1000L);
        return this.rightClicks.size();
    }

    private boolean shouldDoubleClickLeft() {
        if (!this.leftClick.getValue()) {
            return false;
        }

        if (this.chanceLeft.getValue() == 0.0F) {
            return false;
        }

        if (this.aboveCPSLeft.getValue() && this.getLeftCPS() <= 5) {
            return false;
        }

        if (this.weaponOnly.getValue() && !ItemUtil.isHoldingSword()) {
            return false;
        }

        if (this.onlyWhileTargeting.getValue()) {
            if (mc.objectMouseOver == null || mc.objectMouseOver.entityHit == null) {
                return false;
            }
        }

        if (this.chanceLeft.getValue() < 100.0F) {
            double chance = Math.random();
            if (chance >= this.chanceLeft.getValue() / 100.0F) {
                return false;
            }
        }

        return true;
    }

    private boolean shouldDoubleClickRight() {
        if (!this.rightClick.getValue()) {
            return false;
        }

        if (this.chanceRight.getValue() == 0.0F) {
            return false;
        }

        if (this.aboveCPSRight.getValue() && this.getRightCPS() <= 5) {
            return false;
        }

        if (this.blocksOnly.getValue()) {
            ItemStack item = mc.thePlayer.getHeldItem();
            if (item == null || !(item.getItem() instanceof ItemBlock)) {
                return false;
            }
        }

        if (this.chanceRight.getValue() < 100.0F) {
            double chance = Math.random();
            if (chance >= this.chanceRight.getValue() / 100.0F) {
                return false;
            }
        }

        return true;
    }

    private void fixLeftButton() {
        if (this.bot != null && this.ignoreNextLeft && !Mouse.isButtonDown(0)) {
            this.bot.mouseRelease(16);
            this.ignoreNextLeft = false;
        }
    }

    private void fixRightButton() {
        if (this.bot != null && this.ignoreNextRight && !Mouse.isButtonDown(1)) {
            this.bot.mouseRelease(4);
            this.ignoreNextRight = false;
        }
    }

    @EventTarget(whenDisabled = true)
    public void onTick(TickEvent event) {
        if (event.getType() == EventType.PRE) {
            this.fixLeftButton();
            this.fixRightButton();
        }
    }

    @EventTarget(value = Priority.HIGH, whenDisabled = true)
    public void onLeftClick(LeftClickMouseEvent event) {
        if (this.disableInCreative.getValue() && mc.playerController.getCurrentGameType() == GameType.CREATIVE) {
            return;
        }

        if (mc.currentScreen != null) {
            this.fixLeftButton();
            this.fixRightButton();
            return;
        }

        if (!this.isEnabled() || event.isCancelled() || this.bot == null) {
            return;
        }

        // Track for CPS if needed (after the enabled check, or the list grows
        // with every click while the module is off)
        if (this.aboveCPSLeft.getValue()) {
            this.leftClicks.add(System.currentTimeMillis());
        }

        // If this is the extra click we injected, ignore it
        if (this.ignoreNextLeft) {
            this.ignoreNextLeft = false;
            return;
        }

        // Check if we should inject an extra click
        if (this.shouldDoubleClickLeft()) {
            this.bot.mouseRelease(16);
            this.bot.mousePress(16);
            this.ignoreNextLeft = true;
        }
    }

    @EventTarget(value = Priority.HIGH, whenDisabled = true)
    public void onRightClick(RightClickMouseEvent event) {
        if (this.disableInCreative.getValue() && mc.playerController.getCurrentGameType() == GameType.CREATIVE) {
            return;
        }

        if (mc.currentScreen != null) {
            this.fixLeftButton();
            this.fixRightButton();
            return;
        }

        if (!this.isEnabled() || event.isCancelled() || this.bot == null) {
            return;
        }

        if (this.aboveCPSRight.getValue()) {
            this.rightClicks.add(System.currentTimeMillis());
        }

        // If this is the extra click we injected, ignore it
        if (this.ignoreNextRight) {
            this.ignoreNextRight = false;
            return;
        }

        // Check if we should inject an extra click
        if (this.shouldDoubleClickRight()) {
            this.bot.mouseRelease(4);
            this.bot.mousePress(4);
            this.ignoreNextRight = true;
        }
    }

    /* These were named onEnable/onDisable, which the framework never calls --
       Module's hooks are onEnabled/onDisabled. So the Robot was never created
       and the first injected click threw a NullPointerException. @Override
       now makes a misspelling a compile error. */
    @Override
    public void onEnabled() {
        try {
            this.bot = new Robot();
        } catch (AWTException e) {
            this.setEnabled(false);
        }
        this.ignoreNextLeft = false;
        this.ignoreNextRight = false;
        this.leftClicks.clear();
        this.rightClicks.clear();
    }

    @Override
    public void onDisabled() {
        /* Release anything still held down before dropping the bot. */
        if (this.bot != null) {
            if (this.ignoreNextLeft) {
                this.bot.mouseRelease(16);
            }
            if (this.ignoreNextRight) {
                this.bot.mouseRelease(4);
            }
        }
        this.ignoreNextLeft = false;
        this.ignoreNextRight = false;
        this.leftClicks.clear();
        this.rightClicks.clear();
        this.bot = null;
    }
}