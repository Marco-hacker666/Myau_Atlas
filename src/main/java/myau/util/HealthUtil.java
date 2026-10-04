package myau.util;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.IScoreObjectiveCriteria;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.Map;

/**
 * Best available health for another player.
 *
 * {@code EntityLivingBase.getHealth()} reads entity metadata, which for other
 * players is only as good as what the server chose to send. Minigame servers
 * routinely do not send it at all, or send it once and never update it, so a
 * display built on it shows a full bar for someone on half a heart. What those
 * servers do keep current is the scoreboard objective they put in the tab list
 * -- it is the number their own players read during a fight, so it is the one
 * that has to be right.
 *
 * Sources are tried in order of how much the server has committed to them:
 * the tab list first, then the objective under the nameplate, then metadata.
 * Metadata is never skipped entirely, because on a vanilla server it is the
 * only source there is.
 *
 * Values are returned in the units {@code getHealth()} uses -- half-hearts,
 * twenty for a full player -- whatever the source. An objective declared with
 * the HEARTS render type already counts that way, which is what the vanilla
 * health criterion produces and what these servers use.
 */
public class HealthUtil {

    private static final Minecraft mc = Minecraft.getMinecraft();

    /** Display slots, in the order vanilla numbers them. */
    public static final int SLOT_LIST = 0;
    public static final int SLOT_BELOW_NAME = 2;

    public static final String SOURCE_TAB = "tab";
    public static final String SOURCE_BELOW = "below-name";
    public static final String SOURCE_ENTITY = "entity";
    public static final String SOURCE_NONE = "none";

    private HealthUtil() {
    }

    /* Scoreboard reads are not cheap: the vanilla accessor builds and sorts a
       fresh list on every call, and this is consulted once per rendered
       nameplate per frame. Resolving the whole board once a tick turns
       thousands of sorts per second into twenty. */
    private static final Map<String, Float> cache = new HashMap<String, Float>();
    private static final Map<String, String> sources = new HashMap<String, String>();
    private static long cachedAt = Long.MIN_VALUE;

    private static void refresh() {
        if (mc.theWorld == null) {
            cache.clear();
            sources.clear();
            cachedAt = Long.MIN_VALUE;
            return;
        }
        long now = mc.theWorld.getTotalWorldTime();
        if (now == cachedAt) {
            return;
        }
        cachedAt = now;
        cache.clear();
        sources.clear();

        Scoreboard scoreboard = mc.theWorld.getScoreboard();
        if (scoreboard == null) {
            return;
        }
        /* Lower-priority slot first so the tab list overwrites it. */
        collect(scoreboard, SLOT_BELOW_NAME, SOURCE_BELOW);
        collect(scoreboard, SLOT_LIST, SOURCE_TAB);
    }

    private static void collect(Scoreboard scoreboard, int slot, String source) {
        ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(slot);
        if (objective == null) {
            return;
        }
        boolean hearts = objective.getRenderType() == IScoreObjectiveCriteria.EnumRenderType.HEARTS;
        for (Score score : scoreboard.getSortedScores(objective)) {
            String name = score.getPlayerName();
            if (name == null || name.startsWith("#") || score.getScorePoints() <= 0) {
                continue;
            }
            float value = score.getScorePoints();
            /* An INTEGER objective holds whatever the server put there, so it
               is only believable inside the range a health value occupies. */
            if (!hearts && value > 20.0F) {
                continue;
            }
            cache.put(name, value);
            sources.put(name, source);
        }
    }

    /**
     * The score for a player in the objective occupying a display slot, or
     * null when the slot is empty or carries no score for them.
     *
     * Read through the sorted-score collection rather than by lookup: the
     * lookup call creates an entry when one is missing, which would leave this
     * client's scoreboard holding rows the server never sent.
     */
    public static Integer scoreIn(int slot, String name) {
        if (mc.theWorld == null || name == null) {
            return null;
        }
        Scoreboard scoreboard = mc.theWorld.getScoreboard();
        if (scoreboard == null) {
            return null;
        }
        ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(slot);
        if (objective == null) {
            return null;
        }
        for (Score score : scoreboard.getSortedScores(objective)) {
            if (name.equals(score.getPlayerName())) {
                return score.getScorePoints();
            }
        }
        return null;
    }

    /** Whether the objective in a slot is one the server renders as hearts. */
    private static boolean isHearts(int slot) {
        if (mc.theWorld == null) {
            return false;
        }
        Scoreboard scoreboard = mc.theWorld.getScoreboard();
        if (scoreboard == null) {
            return false;
        }
        ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(slot);
        return objective != null
                && objective.getRenderType() == IScoreObjectiveCriteria.EnumRenderType.HEARTS;
    }

    /**
     * Health from a scoreboard slot, in half-hearts, or -1 when the slot has
     * nothing usable.
     *
     * A score of zero is treated as nothing rather than as death. Servers
     * leave stale zeroes behind for players who have left, and reading one as
     * an empty health bar for someone standing in front of you is the worse
     * of the two failures.
     */
    private static float fromSlot(int slot, String name, float maxHealth) {
        Integer score = scoreIn(slot, name);
        if (score == null || score <= 0) {
            return -1.0F;
        }
        float value = score.floatValue();
        /* A HEARTS objective counts in the same units as getHealth(). An
           INTEGER one is whatever the server decided, so it is only trusted
           when it lands inside the range a health value could occupy. */
        if (!isHearts(slot) && value > maxHealth) {
            return -1.0F;
        }
        return value;
    }

    /** Which source {@link #resolve} would use, for display and debugging. */
    public static String source(EntityLivingBase entity) {
        if (entity == null) {
            return SOURCE_NONE;
        }
        if (entity instanceof EntityPlayer) {
            refresh();
            String source = sources.get(entity.getName());
            if (source != null) {
                return source;
            }
        }
        return SOURCE_ENTITY;
    }

    /**
     * Health in half-hearts, from the most trustworthy source available.
     *
     * Safe to call for any entity: anything that is not a player, or that no
     * objective covers, falls through to metadata and behaves exactly as
     * {@code getHealth()} did.
     */
    public static float resolve(EntityLivingBase entity) {
        if (entity == null) {
            return 0.0F;
        }
        if (entity instanceof EntityPlayer) {
            refresh();
            Float value = cache.get(entity.getName());
            if (value != null) {
                return Math.min(value.floatValue(), Math.max(1.0F, entity.getMaxHealth()));
            }
        }
        return entity.getHealth();
    }
}
