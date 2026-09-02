package com.nstut.economybounties.minecraft;

import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.minecraft.commands.Commands;

/** Player entry point for the board; all actions after opening use server-validated packets. */
public final class BountyBoardCommands {
    private static boolean registered;

    private BountyBoardCommands() { }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        CommandRegistrationEvent.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("bounties")
                        .executes(context -> {
                            BountyBoardServer.open(context.getSource().getPlayerOrException());
                            return 1;
                        })
                        .then(Commands.literal("refresh").executes(context -> {
                            BountyBoardServer.open(context.getSource().getPlayerOrException());
                            return 1;
                        }))));
    }
}
