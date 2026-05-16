package com.breakinblocks.aeroportals.util;

import org.joml.Quaterniondc;
import org.joml.Vector3d;

public final class YawMath {
    private YawMath() {}

    public static double yawFromOrientation(Quaterniondc orientation) {
        Vector3d forward = new Vector3d(0, 0, 1);
        orientation.transform(forward);
        return Math.toDegrees(Math.atan2(-forward.x, forward.z));
    }
}
