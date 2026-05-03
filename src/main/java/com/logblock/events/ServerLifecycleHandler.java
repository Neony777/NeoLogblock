package com.logblock.events;

import com.logblock.LogBlockMod;
import com.logblock.commands.LbCommand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@EventBusSubscriber(modid = LogBlockMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public class ServerLifecycleHandler {

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (LogBlockMod.getDatabase() != null) {
            LbCommand.loadRollbackSessionsFromDatabase();
            LogBlockMod.LOGGER.info("LogBlock: Loaded pending rollback sessions from database.");
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (LogBlockMod.getDatabase() != null) {
            LogBlockMod.LOGGER.info("LogBlock: Flushing write queue before shutdown...");
            LogBlockMod.getDatabase().shutdown();
            LogBlockMod.LOGGER.info("LogBlock: Database closed.");
        }
    }
}
