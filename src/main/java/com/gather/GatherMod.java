package com.gather;

import com.gather.network.GatherNetworking;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
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
            dispatcher.register(CommandManager.literal("gatherop")
                .then(CommandManager.literal("xray")
                    .requires(src -> src.getPermissions().hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS)))
                    .then(CommandManager.literal("on")
                        .executes(ctx -> setXray(ctx, true)))
                    .then(CommandManager.literal("off")
                        .executes(ctx -> setXray(ctx, false)))
                    .then(CommandManager.literal("status")
                        .executes(GatherMod::xrayStatus)))));
    }

    private static int setXray(CommandContext<ServerCommandSource> ctx, boolean value) {
        GatherServerConfig.setXray(value);
        GatherNetworking.broadcastXrayPermission(ctx.getSource().getServer(), value);
        ctx.getSource().sendFeedback(() -> Text.literal(
                "[Gather] Xray " + (value ? "enabled" : "disabled") + " for all players."), true);
        return 1;
    }

    private static int xrayStatus(CommandContext<ServerCommandSource> ctx) {
        boolean allowed = GatherServerConfig.isXrayAllowed();
        ctx.getSource().sendFeedback(() -> Text.literal(
                "[Gather] Xray is currently " + (allowed ? "enabled" : "disabled") + "."), false);
        return 1;
    }
}
