package dev.gatebound.data;

import dev.gatebound.network.SyncDataPayload;

import java.util.Arrays;

/** Client-side mirror of the local player progression, filled by SyncDataPayload. Read-only for UI and HUD. */
public final class ClientPlayerData {
    public static int level = 1;
    public static int xp;
    public static int xpToNext = PlayerData.xpToNext(1);
    public static int statPoints;
    public static final int[] STATS = new int[Stat.values().length];
    public static String classId = "";

    static {
        Arrays.fill(STATS, Stat.BASE_VALUE);
    }

    private ClientPlayerData() {
    }

    public static void update(SyncDataPayload p) {
        level = p.level();
        xp = p.xp();
        xpToNext = p.xpToNext();
        statPoints = p.statPoints();
        for (int n = 0; n < STATS.length; n++) {
            STATS[n] = n < p.stats().size() ? p.stats().get(n) : Stat.BASE_VALUE;
        }
        classId = p.classId();
    }
}
