package com.othstudios.chunkyshared.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RingCurveTest
{
    @Test
    void radiusForTier_midTier_matchesQuadraticCurve()
    {
        // Arrange
        final int tier = 7;
        final int ringCount = 10;
        final int maxRadiusChunks = 500;
        final double curveExponent = 2.0;

        // Act
        final int result = RingCurve.radiusForTier(tier, ringCount, maxRadiusChunks, curveExponent);

        // Assert
        assertEquals(245, result);
    }

    @Test
    void radiusForTier_tierZero_returnsZero()
    {
        // Arrange
        final int tier = 0;

        // Act
        final int result = RingCurve.radiusForTier(tier, 10, 500, 2.0);

        // Assert
        assertEquals(0, result);
    }

    @Test
    void radiusForTier_tierEqualsRingCount_returnsMaxRadius()
    {
        // Arrange
        final int tier = 10;
        final int ringCount = 10;
        final int maxRadiusChunks = 500;

        // Act
        final int result = RingCurve.radiusForTier(tier, ringCount, maxRadiusChunks, 2.0);

        // Assert
        assertEquals(maxRadiusChunks, result);
    }

    @Test
    void radiusForTier_negativeTier_throwsIllegalArgument()
    {
        // Arrange
        final int tier = -1;

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> RingCurve.radiusForTier(tier, 10, 500, 2.0));
    }
}
