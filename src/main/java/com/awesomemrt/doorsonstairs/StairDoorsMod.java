package com.awesomemrt.doorsonstairs;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;

public final class StairDoorsMod implements ModInitializer {
    public static final String MOD_ID = "stairdoors";

    @Override
    public void onInitialize() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> StairDoorPlacement.tryPlace(player, world, hand, hitResult));
    }
}
