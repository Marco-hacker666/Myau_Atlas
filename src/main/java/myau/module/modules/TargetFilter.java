package myau.module.modules;

import myau.Myau;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

/**
 * One answer to "is this person a target", for every module that needs one.
 *
 * Fourteen modules in this client decide that question for themselves, and
 * they do not agree. Some check friends, teams and bots; some check two of the
 * three; several check none. Some skip the dead, some skip only the dying.
 * KillAura, PlainAura, FakeLag, LagRange and BlockHit each carry their own
 * copy of roughly the same twenty lines, and every copy is a place the rule
 * can be wrong on its own.
 *
 * That is not a tidiness complaint. A filter that disagrees between modules
 * produces behaviour no setting explains: an aura that attacks somebody the
 * lag module has decided is not worth holding packets for, a backtrack that
 * is rewinding a player the aura will not swing at. Both look like a bug in
 * the module you happen to be watching, and neither is.
 *
 * So the criteria live here, once, and modules ask. Asking is a static call
 * with no state, which is what lets a module consult it from a hot loop
 * without caring whether this module is switched on -- when it is off, every
 * question gets the answer the module would have reached by itself.
 *
 * WHAT IT ADDS OVER THE OLD SCATTERED CHECKS. Friends, teams and bots were
 * already covered, unevenly. The rest are things that repeatedly turned out to
 * matter and had nowhere to live: a distance ceiling that applies to
 * everything at once rather than being set five times in five modules; whether
 * an invisible player counts, which on Bedwars is a real question rather than
 * a preference; whether someone in a bed or at a shop counts; and a floor on
 * how long a player has to have existed before anything acts on them, because
 * the tick a player spawns in is the tick their team and ping are not yet
 * known and every one of the checks below returns the wrong answer.
 *
 * WHICH MODULES ACTUALLY CONSULT IT, as of now: KillAura, PlainAura, FakeLag,
 * LagRange and BlockHit's Predict mode. The rest still decide for themselves.
 * That is stated rather than glossed over, because a filter that some modules
 * ignore is worse than no filter if you believe it covers everything.
 */
public class TargetFilter extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    /** Friends are never targets. Off would be a strange thing to want. */
    public final BooleanProperty friends = new BooleanProperty("skip-friends", true);
    public final BooleanProperty teammates = new BooleanProperty("skip-teammates", true);
    public final BooleanProperty bots = new BooleanProperty("skip-bots", true);

    /**
     * Whether an invisible player is a target.
     *
     * A real question on Bedwars rather than a preference. Invisibility is a
     * purchasable item there, so an invisible player is usually a real
     * opponent who has just spent money on not being hit -- but the client
     * only knows where they are because it is told, and swinging at a spot
     * that looks empty is the single most visible thing an aura can do.
     */
    public final BooleanProperty invisible = new BooleanProperty("skip-invisible", false);

    /** Players in a bed, or standing at a shop villager. */
    public final BooleanProperty sleeping = new BooleanProperty("skip-sleeping", true);
    public final BooleanProperty shops = new BooleanProperty("skip-shops", true);

    /**
     * A ceiling that applies to every module at once.
     *
     * Not a replacement for each module's own range -- an aura still needs its
     * own reach and the lag modules still need their own engagement distance.
     * This is the outer bound none of them may exceed, which is the setting
     * that was missing when a module's range was raised for a reason and
     * quietly stayed raised.
     */
    public final FloatProperty maxDistance = new FloatProperty("max-distance", 24.0F, 3.0F, 64.0F);

    /**
     * Ticks a player must have existed before anything acts on them.
     *
     * The tick a player enters range is the tick the tab list may not have
     * them yet, and their team, their ping and whether they are a bot are all
     * read from it. Every check below returns a confident wrong answer during
     * that window. Half a second of patience costs nothing and removes a
     * whole class of "it attacked a teammate once".
     */
    public final IntProperty settleTicks = new IntProperty("settle-ticks", 10, 0, 60);

    public TargetFilter() {
        super("TargetFilter", false, false,
                "One shared answer to whether a player counts as a target, used by the combat and latency modules");
    }

    /**
     * Whether this player may be acted on.
     *
     * Static, and safe to call from anywhere, including when this module is
     * switched off -- in which case only the checks that were already
     * universal apply, so nothing changes for a module that adopts it.
     *
     * Never throws. It is called from targeting loops that run every tick, and
     * a filter that can throw is a filter that takes the module down with it;
     * the tab list lookups underneath this genuinely do return null during a
     * server transfer.
     */
    public static boolean accepts(EntityPlayer player) {
        if (player == null || mc.thePlayer == null || mc.theWorld == null) {
            return false;
        }
        if (player == mc.thePlayer || player == mc.thePlayer.ridingEntity) {
            return false;
        }
        if (player.isDead || player.deathTime > 0 || player.getHealth() <= 0.0F) {
            return false;
        }
        TargetFilter filter = instance();
        if (filter == null || !filter.isEnabled()) {
            /* Switched off means the baseline every module already applied:
               not me, not dead, not a friend. Adopting this call can then
               never change a module's behaviour until the module is turned on,
               which is what makes it safe to wire in everywhere at once. */
            return !safeFriend(player);
        }
        return filter.test(player);
    }

    private boolean test(EntityPlayer player) {
        try {
            if (this.friends.getValue() && safeFriend(player)) {
                return false;
            }
            if (this.teammates.getValue() && TeamUtil.isSameTeam(player)) {
                return false;
            }
            if (this.bots.getValue() && TeamUtil.isBot(player)) {
                return false;
            }
            if (this.invisible.getValue() && player.isInvisible()) {
                return false;
            }
            if (this.sleeping.getValue() && player.isPlayerSleeping()) {
                return false;
            }
            if (this.shops.getValue() && TeamUtil.isShop(player)) {
                return false;
            }
            if (mc.thePlayer.getDistanceToEntity(player) > this.maxDistance.getValue()) {
                return false;
            }
            if (player.ticksExisted < this.settleTicks.getValue()) {
                return false;
            }
            return true;
        } catch (Exception ignored) {
            /* A lookup failed, which during a server transfer is ordinary.
               Refusing is the safe answer: not acting on somebody is always
               recoverable, acting on the wrong somebody is not. */
            return false;
        }
    }

    private static boolean safeFriend(EntityPlayer player) {
        try {
            return TeamUtil.isFriend(player);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static TargetFilter instance() {
        try {
            return (TargetFilter) Myau.moduleManager.modules.get(TargetFilter.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public String[] getSuffix() {
        if (mc.theWorld == null || mc.thePlayer == null) {
            return new String[0];
        }
        int accepted = 0;
        int seen = 0;
        try {
            for (Object object : mc.theWorld.playerEntities) {
                if (!(object instanceof EntityPlayer) || object == mc.thePlayer) {
                    continue;
                }
                seen++;
                if (accepts((EntityPlayer) object)) {
                    accepted++;
                }
            }
        } catch (Exception ignored) {
            return new String[0];
        }
        /* What it is currently letting through, out of what is loaded. The
           number is the whole point of having one filter: when a module will
           not act on somebody, this says whether the filter is the reason. */
        return new String[]{accepted + "/" + seen};
    }
}
