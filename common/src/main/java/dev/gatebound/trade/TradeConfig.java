package dev.gatebound.trade;

/** Tunable numbers for drops, tomes and prices. */
public final class TradeConfig {
    /** Chance a hostile mob killed by a player inside the Spire or a gate dungeon drops a Gate Shard (never in the open world). */
    public static volatile double shardDropChance = 0.10;
    /** Extra shards dropped by high-health mobs (bosses). */
    public static volatile int bossShardBonus = 8;
    /** Maximum tomes of each stat a player may read. */
    public static volatile int maxTomesPerStat = 5;
    /** Max uses per offer between restocks (restock happens once per in-game day). */
    public static volatile int offerStock = 6;

    /** Multiplier on every shop price paid in Gate Shards. */
    public static volatile double priceMultiplier = 2.5;

    /** Static holder; not instantiable. */
    private TradeConfig() {
    }
}
