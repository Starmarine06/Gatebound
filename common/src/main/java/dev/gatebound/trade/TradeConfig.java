package dev.gatebound.trade;

/** Tunable numbers for drops, tomes and prices. */
public final class TradeConfig {
    /** Chance a hostile mob killed by a player drops a Gate Shard. */
    public static volatile double shardDropChance = 0.10;
    /** Extra shards dropped by high-health mobs (bosses). */
    public static volatile int bossShardBonus = 8;
    /** Maximum tomes of each stat a player may read. */
    public static volatile int maxTomesPerStat = 5;
    /** Max uses per offer between restocks (restock happens once per in-game day). */
    public static volatile int offerStock = 6;

    private TradeConfig() {
    }
}
