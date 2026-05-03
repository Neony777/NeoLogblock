package com.logblock.events;

import com.logblock.LogBlockMod;
import com.logblock.config.LogBlockConfig;
import com.logblock.database.BlockLogEntry;
import com.logblock.database.EntityLogEntry;
import com.logblock.util.BlockStateSerializer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

@EventBusSubscriber(modid = LogBlockMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public class BlockEventHandler {

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!LogBlockConfig.LOG_BLOCKS.get()) return;
        if (LogBlockMod.getDatabase() == null) return;

        Player player = event.getPlayer();
        if (player == null) return;

        Level level = (Level) event.getLevel();
        BlockPos pos = event.getPos();
        BlockState brokenState = event.getState();

        String world = dimensionName(level);
        String blockBefore = BlockStateSerializer.serialize(brokenState);
        String blockAfter  = BlockStateSerializer.serialize(Blocks.AIR.defaultBlockState());
        String nbt = serializeBlockEntityNbt(level, pos);

        LogBlockMod.getDatabase().logBlockChange(
            world, pos.getX(), pos.getY(), pos.getZ(),
            blockBefore, blockAfter, nbt,
            player.getName().getString(), BlockLogEntry.ACTOR_PLAYER,
            BlockLogEntry.ACTION_DESTROY
        );
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!LogBlockConfig.LOG_BLOCKS.get()) return;
        if (LogBlockMod.getDatabase() == null) return;
        if (!(event.getEntity() instanceof Player player)) return;

        Level level = (Level) event.getLevel();
        BlockPos pos = event.getPos();
        BlockState placedState   = event.getPlacedBlock();
        BlockState replacedState = event.getBlockSnapshot().getState();

        String world = dimensionName(level);
        String blockAfter  = BlockStateSerializer.serialize(placedState);
        String blockBefore = BlockStateSerializer.serialize(replacedState);

        // ACTION_CREATE when placing into air; ACTION_REPLACE when overwriting an existing block.
        String action = replacedState.isAir() ? BlockLogEntry.ACTION_CREATE : BlockLogEntry.ACTION_REPLACE;

        LogBlockMod.getDatabase().logBlockChange(
            world, pos.getX(), pos.getY(), pos.getZ(),
            blockBefore, blockAfter, null,
            player.getName().getString(), BlockLogEntry.ACTOR_PLAYER,
            action
        );
    }

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!LogBlockConfig.LOG_EXPLOSIONS.get()) return;
        if (LogBlockMod.getDatabase() == null) return;
        if (!(event.getLevel() instanceof Level level)) return;

        String world = dimensionName(level);
        String actorName = resolveExplosionActor(event);
        String airState  = BlockStateSerializer.serialize(Blocks.AIR.defaultBlockState());

        for (BlockPos pos : event.getAffectedBlocks()) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;

            String nbt = serializeBlockEntityNbt(level, pos);
            LogBlockMod.getDatabase().logBlockChange(
                world, pos.getX(), pos.getY(), pos.getZ(),
                BlockStateSerializer.serialize(state), airState, nbt,
                actorName, BlockLogEntry.ACTOR_EXPLOSION,
                BlockLogEntry.ACTION_EXPLODE
            );
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!LogBlockConfig.LOG_ENTITIES.get()) return;
        if (LogBlockMod.getDatabase() == null) return;

        LivingEntity entity = event.getEntity();
        if (entity instanceof Player) return;

        DamageSource source = event.getSource();
        Entity attacker = source.getEntity();

        String killerName;
        String killerType;
        if (attacker instanceof Player player) {
            killerName = player.getName().getString();
            killerType = EntityLogEntry.KILLER_PLAYER;
        } else if (attacker != null) {
            killerName = attacker.getType().toShortString();
            killerType = EntityLogEntry.KILLER_ENTITY;
        } else {
            killerName = source.typeHolder()
                .unwrapKey()
                .map(k -> k.location().toString())
                .orElse("unknown");
            killerType = EntityLogEntry.KILLER_UNKNOWN;
        }

        String world = dimensionName(entity.level());
        String entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();

        LogBlockMod.getDatabase().logEntityKill(
            world, entity.getX(), entity.getY(), entity.getZ(),
            entityType, entity.getName().getString(),
            killerName, killerType
        );
    }


    public static String dimensionName(Level level) {
        ResourceKey<Level> key = level.dimension();
        return key.location().toString();
    }

    public static String serializeBlockEntityNbt(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return null;
        try {
            CompoundTag tag = be.saveWithoutMetadata(level.registryAccess());
            return tag.toString();
        } catch (Exception e) {
            LogBlockMod.LOGGER.warn("LogBlock: could not serialize block entity NBT at {}", pos);
            return null;
        }
    }

    private static String resolveExplosionActor(ExplosionEvent.Detonate event) {
        var explosion = event.getExplosion();
        var indirect = explosion.getIndirectSourceEntity();
        if (indirect instanceof Player player) return player.getName().getString();
        var direct = explosion.getDirectSourceEntity();
        if (direct != null) return direct.getType().toShortString();
        return "explosion";
    }
}
