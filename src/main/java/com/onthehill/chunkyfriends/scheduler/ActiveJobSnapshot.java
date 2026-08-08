package com.onthehill.chunkyfriends.scheduler;

import java.util.UUID;

/**
 * Immutable snapshot of the single currently-active pregeneration job, captured at the moment it is read.
 *
 * @param playerUuid Unique identifier of the player whose job this is.
 * @param playerDisplayName The player's last-known display name, or {@code null} if never resolved.
 * @param world Identifier of the world the job is generating in, e.g. {@code "minecraft:overworld"}.
 * @param ringTier The tier currently being serviced (i.e. {@code currentRingTier + 1} at the time the job was
 *     started), not the player's persisted {@code currentRingTier}, which only advances on completion.
 * @param ringCount Total number of ring tiers configured at the time this snapshot was taken.
 * @param progressPercent Most recently reported completion percentage for this job.
 * @param chunks Most recently reported chunk count generated for this job.
 * @param chunksPerSecond Most recently reported generation rate for this job.
 * @param presencePaused Whether this job is currently paused because a player is online.
 * @param lastProgressEventEpochMillis Epoch millis at which the last progress event for this job was
 *     received, or {@code 0} if no progress event has fired yet.
 */
public record ActiveJobSnapshot(UUID playerUuid, String playerDisplayName, String world, int ringTier, int ringCount,
                                 double progressPercent, long chunks, double chunksPerSecond, boolean presencePaused,
                                 long lastProgressEventEpochMillis)
{
}
