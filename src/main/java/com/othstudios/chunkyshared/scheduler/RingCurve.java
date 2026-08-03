package com.othstudios.chunkyshared.scheduler;

/**
 * Pure utility mapping a ring tier to its chunk radius via a configurable ease-in curve.
 */
public final class RingCurve
{
    private RingCurve()
    {
    }

    /**
     * Computes the chunk radius for a given ring tier.
     *
     * @param tier Ring tier, {@code 0} (no coverage yet) to {@code ringCount} inclusive.
     * @param ringCount Total number of ring tiers.
     * @param maxRadiusChunks Ceiling radius, in chunks, reached at {@code tier == ringCount}.
     * @param curveExponent Exponent of the ease-in curve. {@code 1.0} is linear, {@code 2.0} is quadratic.
     * @return The radius, in chunks, rounded to the nearest whole chunk.
     * @throws IllegalArgumentException If {@code tier} is negative.
     * @implNote radius(i) = round(maxRadiusChunks * (i / ringCount) ^ curveExponent)
     */
    public static int radiusForTier(final int tier, final int ringCount, final int maxRadiusChunks, final double curveExponent)
    {
        if (tier < 0)
        {
            throw new IllegalArgumentException("tier must not be negative: " + tier);
        }
        if (tier == 0)
        {
            return 0;
        }
        final double ratio = (double) tier / ringCount;
        return (int) Math.round(maxRadiusChunks * Math.pow(ratio, curveExponent));
    }
}
