package com.gather;

import com.gather.network.GatherNetworking;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GatherMod implements ModInitializer {

    public static final String MOD_ID = "gather";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        GatherNetworking.registerServerSide();
        registerCommands();
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("gatherop")
                .then(Commands.literal("xray")
                    .requires(src -> src.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))
                    .then(Commands.literal("on")
                        .executes(ctx -> setXray(ctx, true)))
                    .then(Commands.literal("off")
                        .executes(ctx -> setXray(ctx, false)))
                    .then(Commands.literal("status")
                        .executes(GatherMod::xrayStatus)))));
    }

    private static int setXray(CommandContext<CommandSourceStack> ctx, boolean value) {
        GatherServerConfig.setXray(value);
        GatherNetworking.broadcastXrayPermission(ctx.getSource().getServer(), value);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[Gather] Xray " + (value ? "enabled" : "disabled") + " for all players."), true);
        return 1;
    }

    private static int xrayStatus(CommandContext<CommandSourceStack> ctx) {
        boolean allowed = GatherServerConfig.isXrayAllowed();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[Gather] Xray is currently " + (allowed ? "enabled" : "disabled") + "."), false);
        return 1;
    }
}
