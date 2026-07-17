package com.jedon.kellikanvas.platform.ambient

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SensorInventoryTest {
    @Test
    fun `standard light sensor is available`() {
        val inventory =
            Inventory(
                listOf(descriptor(type = SensorTypes.LIGHT, name = "Ambient light")),
            )

        assertThat(inventory.capabilities.light).isEqualTo(CapabilityStatus.AVAILABLE)
    }

    @Test
    fun `vendor RGB sensor remains candidate unverified`() {
        val inventory =
            Inventory(
                listOf(descriptor(type = 65_550, name = "Vendor RGB ambient color")),
            )

        assertThat(inventory.capabilities.vendorRgb).isEqualTo(CapabilityStatus.CANDIDATE_UNVERIFIED)
    }

    @Test
    fun `motion and orientation sensors never imply room presence`() {
        val inventory =
            Inventory(
                listOf(
                    descriptor(SensorTypes.ACCELEROMETER, "Accelerometer"),
                    descriptor(SensorTypes.GYROSCOPE, "Gyroscope"),
                    descriptor(SensorTypes.GRAVITY, "Gravity"),
                    descriptor(SensorTypes.ROTATION_VECTOR, "Rotation"),
                    descriptor(SensorTypes.DEVICE_MOTION, "Device motion"),
                ),
            )

        assertThat(inventory.presenceCandidates).isEmpty()
        assertThat(inventory.capabilities.presence).isEqualTo(CapabilityStatus.UNAVAILABLE)
    }

    @Test
    fun `only explicit presence sensor families are candidates`() {
        val inventory =
            Inventory(
                listOf(
                    descriptor(SensorTypes.PROXIMITY, "Proximity"),
                    descriptor(SensorTypes.LOW_LATENCY_OFFBODY_DETECT, "Off body"),
                    descriptor(SensorTypes.SIGNIFICANT_MOTION, "Significant motion"),
                    descriptor(65_551, "Vendor occupancy detector"),
                ),
            )

        assertThat(inventory.presenceCandidates).hasSize(4)
        assertThat(inventory.capabilities.presence).isEqualTo(CapabilityStatus.CANDIDATE_UNVERIFIED)
    }

    @Test
    fun `empty inventory reports unavailable capabilities`() {
        assertThat(Inventory(emptyList()).capabilities)
            .isEqualTo(AmbientCapabilities.unavailable())
    }

    private fun descriptor(
        type: Int,
        name: String,
    ) = SensorDescriptor(
        type = type,
        name = name,
        vendor = "test",
        version = 1,
        resolution = 0.1f,
        maximumRange = 100f,
        powerMilliamp = 0.2f,
        minDelayMicros = 0,
        maxDelayMicros = 0,
        reportingMode = 0,
        isWakeUp = false,
        stringType = "test.$type",
    )
}
