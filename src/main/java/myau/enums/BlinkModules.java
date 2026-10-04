package myau.enums;

public enum BlinkModules {
    /* The ceiling is the longest each one blinks on purpose, with a margin
       (the lease, PacketHolds): past it the blink is ended for it. */
    NONE("BlinkManager", 0L),
    /* Until it lands, or falls far enough to be set back: a long fall. */
    ANTI_VOID("AntiVoid", 10000L),
    /* One block/attack cycle, a few ticks, renewed every cycle. */
    AUTO_BLOCK("KillAura", 2000L),
    /* The player's own blink: as long as they keep it on. */
    BLINK("Blink", -1L),
    DISPLACE("Displace", 2000L),
    HITFLICK("Hitflick", 2000L),
    /* The whole fall, until the landing packet. */
    NO_FALL("NoFall", 10000L),
    /* Scaffold's safe-stuck hold. It used to borrow BLINK, which let Scaffold
       and the Blink module release each other's queues and filed Scaffold's
       holds under "BLINK" in the ledger. */
    SCAFFOLD("Scaffold", 5000L),
    NO_SLOW("NoSlow", 2000L);

    private final String moduleName;
    private final long leaseMs;

    BlinkModules(String moduleName, long leaseMs) {
        this.moduleName = moduleName;
        this.leaseMs = leaseMs;
    }

    /**
     * The module that holds under this name, as the ledger should call it
     * (plan step 12): "ANTI_VOID" named nothing FlagResponder could find.
     */
    public String moduleName() {
        return this.moduleName;
    }

    /**
     * The longest this one blinks on purpose, ms, margin included; negative
     * for no limit. For NONE, 0: packets held with no one blinking are
     * orphans and go at once.
     */
    public long leaseMs() {
        return this.leaseMs;
    }
}
