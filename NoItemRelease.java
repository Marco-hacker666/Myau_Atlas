package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.events.TickEvent;
import myau.mixin.IAccessorEntityPlayer;
import myau.module.Module;
import myau.property.properties.ModeProperty;
import myau.util.ActionLedger;
import net.minecraft.client.Minecraft;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C07PacketPlayerDigging;

/**
 * Does not send the RELEASE_USE_ITEM packet that the game itself sends when the
 * use-item key is let go. The client's own item-use handling is left alone.
 *
 * The packet is dropped at the very last step of NetworkManager.sendPacket
 * (MixinNetworkManager), after every PacketEvent listener and after
 * PlayerStateManager.handlePacket have seen it, and before BlinkManager /
 * LagManager could hold it. So Atlas's own bookkeeping (PlayerStateManager,
 * KillAura.blockingState, ...) treats the release as having happened, and only
 * the server never receives it.
 *
 * Which packet: MixinPlayerControllerMP opens a window around the vanilla
 * PlayerControllerMP.onStoppedUsingItem (beginVanillaRelease / endVanillaRelease);
 * the body runs unmodified. Inside the window the first RELEASE_USE_ITEM that no
 * module sent is claimed by instance (claimVanillaRelease, first thing in the send
 * hook) and only that instance is dropped (dropVanillaRelease). A release that a
 * module sends itself is never claimed, even from inside the window: NoSlow's Opal
 * mode sends one from its PacketEvent listener while the window can be open
 * (a C09 sent by syncCurrentPlayItem triggers it). Direct senders traced
 * (2026-10-08): NoSlow (NCP, Verus, Opal), KillAura.stopBlock, FastBow (x2).
 */
public class NoItemRelease extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String NAME = "NoItemRelease";

    /** True only while a vanilla release this module covers is being carried out. */
    private static boolean inVanillaRelease;
    /** The vanilla release packet to drop, by identity; null when there is none. */
    private static Packet<?> claimed;

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"CONSUMABLE", "SWORD", "ALL"});

    public NoItemRelease() {
        super(NAME, false);
    }

    /** Called by MixinPlayerControllerMP at the head of onStoppedUsingItem. */
    public static void beginVanillaRelease() {
        inVanillaRelease = false;
        claimed = null;
        if (Myau.moduleManager == null || mc.thePlayer == null) {
            return;
        }
        NoItemRelease self = (NoItemRelease) Myau.moduleManager.modules.get(NoItemRelease.class);
        if (self == null || !self.isEnabled()) {
            return;
        }
        ItemStack using = ((IAccessorEntityPlayer) mc.thePlayer).getItemInUse();
        inVanillaRelease = using != null && self.covers(using);
    }

    /** Called by MixinPlayerControllerMP when onStoppedUsingItem returns. */
    public static void endVanillaRelease() {
        inVanillaRelease = false;
        claimed = null;
    }

    /**
     * First thing in MixinNetworkManager.sendPacket, before any listener runs:
     * remember the vanilla release packet. A packet some module sent (a module
     * frame is on the stack) is not the vanilla one.
     */
    public static void claimVanillaRelease(Packet<?> packet) {
        if (!inVanillaRelease || claimed != null || !isRelease(packet)) {
            return;
        }
        /* callerModule() would find this very method (a myau.module.modules
           frame) and always answer "NoItemRelease"; skip our own frames. */
        if (ActionLedger.callerModuleExcept(NAME) != null) {
            return;
        }
        claimed = packet;
    }

    /**
     * Last step of MixinNetworkManager.sendPacket, after the listeners and
     * PlayerStateManager: true for the claimed packet, once.
     */
    public static boolean dropVanillaRelease(Packet<?> packet) {
        if (claimed == null || packet != claimed) {
            return false;
        }
        claimed = null;
        /* What EventManager.noteCancel recorded when this module cancelled the
           event itself: the attribution a correction is weighed against. */
        ActionLedger.note(NAME, ActionLedger.holdKind(packet, true));
        return true;
    }

    private static boolean isRelease(Packet<?> packet) {
        return packet instanceof C07PacketPlayerDigging
                && ((C07PacketPlayerDigging) packet).getStatus() == C07PacketPlayerDigging.Action.RELEASE_USE_ITEM;
    }

    /* Safety net: the window is closed by the mixin's RETURN hook; this closes it
       anyway if the vanilla method ever left through an exception. */
    @EventTarget(whenDisabled = true)
    public void onTick(TickEvent event) {
        inVanillaRelease = false;
        claimed = null;
    }

    @Override
    public void onDisabled() {
        inVanillaRelease = false;
        claimed = null;
    }

    private boolean covers(ItemStack using) {
        Item item = using.getItem();
        if (item instanceof ItemBow || item instanceof ItemFishingRod) {
            return false;
        }
        EnumAction action = using.getItemUseAction();
        if (action == EnumAction.BOW) {
            return false;
        }
        switch (this.mode.getValue()) {
            case 0:
                return action == EnumAction.EAT || action == EnumAction.DRINK;
            case 1:
                return action == EnumAction.BLOCK;
            default:
                return true;
        }
    }
}
