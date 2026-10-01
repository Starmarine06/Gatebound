package dev.gatebound.client;

import net.minecraft.client.Minecraft;

/** Entry points that touch client-only classes. Only call these through EnvExecutor on the client. */
public final class ClientHooks {
    private ClientHooks() {
    }

    public static void openAwakening() {
        Minecraft.getInstance().setScreenAndShow(new AwakeningScreen());
    }
}
