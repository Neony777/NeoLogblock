package com.logblock;

import com.logblock.config.LogBlockConfig;
import com.logblock.database.DatabaseManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(LogBlockMod.MODID)
public class LogBlockMod {

    public static final String MODID = "logblock";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    private static DatabaseManager databaseManager;

    public LogBlockMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, LogBlockConfig.SPEC, "logblock-common.toml");

        modEventBus.addListener(this::onCommonSetup);
        modEventBus.addListener(this::onLoadComplete);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("LogBlock initializing database...");
        databaseManager = new DatabaseManager();
        databaseManager.initialize();
        LOGGER.info("LogBlock database initialized.");
    }

    private void onLoadComplete(FMLLoadCompleteEvent event) {
        LOGGER.info("LogBlock loaded successfully. Right-click blocks with a wooden pickaxe to inspect history.");
    }

    public static DatabaseManager getDatabase() {
        return databaseManager;
    }

    public static void setDatabase(DatabaseManager db) {
        databaseManager = db;
    }
}
