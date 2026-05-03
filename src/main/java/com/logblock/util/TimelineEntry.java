package com.logblock.util;

import net.minecraft.network.chat.Component;

/**
 * One row in a unified per-coordinate activity timeline. Carries the original
 * row's epoch-millisecond timestamp plus its already-formatted chat
 * {@link Component}, so block / container / interaction entries can be merged
 * into a single chronological list without leaking concrete entry types into
 * the caller.
 */
public record TimelineEntry(long timestamp, Component formatted) {}
