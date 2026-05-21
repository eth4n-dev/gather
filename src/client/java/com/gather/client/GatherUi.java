package com.gather.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class GatherUi {
    private static final SoundEvent MENU_SWOOSH = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("gather", "menu_swoosh"));

    private GatherUi() {}

    public static void playClickSound() {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    public static void playTextEditSound() {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT, 1.6F));
        }
    }

    public static void playGoalAddedSound() {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2F));
        }
    }

    public static void playMenuOpenSound() {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(MENU_SWOOSH, 1.12F));
        }
    }

    public static void playMenuCloseSound() {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(MENU_SWOOSH, 0.82F));
        }
    }

    public static Component itemName(Item item) {
        return item.getName(item.getDefaultInstance());
    }

    public static Component itemName(ItemStack stack) {
        return stack.getHoverName();
    }
}
