package dev.gatebound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Gatebound {
    public static final String MOD_ID = "gatebound";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private Gatebound() {
    }

    public static void init() {
        LOGGER.info("Gatebound initialised");
    }
}
