package myau.util;

/**
 * What one melee hit should take off its target under vanilla 1.8 rules
 * (2026-10-04), so a hit that does far less can be told apart from a target
 * that is simply well armoured.
 *
 * EntityPlayer.attackTargetEntityWithCurrentItem and
 * EntityLivingBase.applyArmorCalculations / applyPotionDamageCalculations:
 *   damage  = (1 + weapon - weakness) x (1 + 1.3 x strength level)
 *             x 1.5 on a critical (falling, not on the ground)
 *             + 1.25 x sharpness level
 *   armour  : x (25 - armour points) / 25
 *   enchants: the protection points (EPF) of the armour, summed and capped
 *             at 25, are randomised each hit to between (raw+1)/2 and
 *             (raw+1)/2 + raw/2, capped at 20, each point 4% off.
 * The randomisation is why the answer is a range, not a number.
 *
 * Not modelled: resistance, the target blocking with a sword (halves it),
 * Bedwars-style damage changes on a modded server. Those make the real hit
 * smaller than this, so the caller only calls a hit "mitigated" when it is
 * well under the bottom of the range.
 *
 * Pure.
 */
public final class DamageModel {

    private DamageModel() {
    }

    /**
     * @param weapon     the held item's attack damage modifier (sword: wood 4,
     *                   stone 5, iron 6, diamond 7; nothing 0)
     * @param sharpness  sharpness level, 0 for none
     * @param strength   strength amplifier + 1, 0 for none
     * @param weakness   weakness amplifier + 1, 0 for none
     * @param crit       whether the hit is a critical
     * @param armour     the target's armour points (0-20)
     * @param protRaw    the target's summed protection points before
     *                   randomisation (see protectionPoints)
     * @return {least, most} health the hit takes off
     */
    public static float[] expected(float weapon, int sharpness, int strength, int weakness, boolean crit,
                                   int armour, int protRaw) {
        float damage = (1.0F + weapon - 0.5F * weakness) * (1.0F + 1.3F * strength);
        if (crit) {
            damage *= 1.5F;
        }
        damage += 1.25F * sharpness;
        damage = Math.max(0.0F, damage);
        damage = damage * (25 - clamp(armour, 0, 25)) / 25.0F;
        int raw = clamp(protRaw, 0, 25);
        int least = (raw + 1) >> 1;
        int most = Math.min(20, least + (raw >> 1));
        least = Math.min(20, least);
        return new float[]{damage * (25 - most) / 25.0F, damage * (25 - least) / 25.0F};
    }

    /**
     * Protection points of one armour piece against a melee hit, as 1.8's
     * EnchantmentProtection.calcModifierDamage gives them: plain protection
     * only (fire, blast and projectile protection do nothing here).
     */
    public static int protectionPoints(int protectionLevel) {
        if (protectionLevel <= 0) {
            return 0;
        }
        return (int) ((6 + protectionLevel * protectionLevel) / 3.0F * 0.75F);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
