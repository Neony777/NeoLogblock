package com.logblock.commands;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public class RollbackSession {

    /**
     * Snapshot of a single block's state before and after rollback.
     * blockEntityNbt is the pre-rollback block entity data, used to restore it during /lb redo.
     */
    public record BlockSnapshot(
        BlockPos pos,
        String dimensionKey,
        BlockState stateBefore,
        BlockState stateAfter,
        String blockEntityNbt
    ) {}

    private final String playerName;
    private final List<BlockSnapshot> snapshots;
    private final long createdAt;

    public RollbackSession(String playerName, List<BlockSnapshot> snapshots) {
        this.playerName = playerName;
        this.snapshots = List.copyOf(snapshots);
        this.createdAt = System.currentTimeMillis();
    }

    public String getPlayerName() { return playerName; }
    public List<BlockSnapshot> getSnapshots() { return snapshots; }
    public long getCreatedAt() { return createdAt; }
    public int size() { return snapshots.size(); }
}
