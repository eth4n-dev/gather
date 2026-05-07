package com.gather.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;

public final class GatherUi {
    private static final SoundEvent MENU_SWOOSH = SoundEvent.of(Identifier.of("gather", "menu_swoosh"));

    private GatherUi() {}

    public static void playClickSound() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    public static void playMenuOpenSound() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            client.getSoundManager().play(PositionedSoundInstance.ui(MENU_SWOOSH, 1.12F));
        }
    }

    public static void playMenuCloseSound() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            client.getSoundManager().play(PositionedSoundInstance.ui(MENU_SWOOSH, 0.82F));
        }
    }
}
