package com.logblock.util;

import com.logblock.config.LogBlockConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

public final class SoundEffects {

    private SoundEffects() {}

    public static void playInspect(ServerPlayer player) {
        play(player, SoundEvents.UI_BUTTON_CLICK.value(), 1.4f);
    }

    public static void playQuery(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_HARP.value(), 1.6f);
    }

    public static void playNoResults(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_BASS.value(), 0.8f);
    }

    public static void playRollback(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_BELL.value(), 1.2f);
    }

    public static void playRedo(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_CHIME.value(), 1.5f);
    }

    public static void playError(ServerPlayer player) {
        play(player, SoundEvents.VILLAGER_NO, 1.0f);
    }

    public static void playReload(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f);
    }

    private static void play(ServerPlayer player, SoundEvent sound, float pitch) {
        if (player == null) return;
        if (!LogBlockConfig.PLAY_SOUNDS.get()) return;
        float volume = LogBlockConfig.SOUND_VOLUME.get().floatValue();
        if (volume <= 0.0f) return;
        player.playNotifySound(sound, SoundSource.MASTER, volume, pitch);
    }
}
