package com.logblock.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Optional;
import java.util.StringJoiner;

/**
 * Serializes and deserializes BlockState to/from a string representation
 * in the format: "minecraft:block_id[prop1=val1,prop2=val2]".
 * This ensures rollback can restore the exact pre-change block state including
 * orientation, slab type, waterlogging, power level, etc.
 */
public final class BlockStateSerializer {

    private BlockStateSerializer() {}

    public static String serialize(BlockState state) {
        StringBuilder sb = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        var values = state.getValues();
        if (!values.isEmpty()) {
            StringJoiner joiner = new StringJoiner(",", "[", "]");
            for (var entry : values.entrySet()) {
                joiner.add(entry.getKey().getName() + "=" + serializeValue(entry.getKey(), entry.getValue()));
            }
            sb.append(joiner);
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String serializeValue(Property<T> prop, Comparable<?> value) {
        return prop.getName((T) value);
    }

    public static BlockState deserialize(String stateStr) {
        if (stateStr == null || stateStr.isBlank()) {
            return Blocks.AIR.defaultBlockState();
        }

        int bracketIdx = stateStr.indexOf('[');
        String blockIdStr = bracketIdx >= 0 ? stateStr.substring(0, bracketIdx) : stateStr;

        ResourceLocation rl;
        try {
            rl = ResourceLocation.parse(blockIdStr);
        } catch (Exception e) {
            return Blocks.AIR.defaultBlockState();
        }

        var blockOpt = BuiltInRegistries.BLOCK.getOptional(rl);
        if (blockOpt.isEmpty()) {
            return Blocks.AIR.defaultBlockState();
        }

        BlockState state = blockOpt.get().defaultBlockState();

        if (bracketIdx >= 0 && stateStr.endsWith("]")) {
            String propsStr = stateStr.substring(bracketIdx + 1, stateStr.length() - 1);
            for (String propKv : propsStr.split(",")) {
                String[] kv = propKv.split("=", 2);
                if (kv.length == 2) {
                    state = applyProperty(state, kv[0].trim(), kv[1].trim());
                }
            }
        }

        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState applyProperty(BlockState state, String propName, String propValue) {
        for (Property<?> prop : state.getProperties()) {
            if (prop.getName().equals(propName)) {
                Optional<? extends Comparable<?>> val = ((Property) prop).getValue(propValue);
                if (val.isPresent()) {
                    state = state.setValue((Property) prop, (Comparable) val.get());
                }
                break;
            }
        }
        return state;
    }
}
