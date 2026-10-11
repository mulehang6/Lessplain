package com.master.lessplain.diagram

import java.awt.geom.Rectangle2D
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 边框不是实心障碍，回边避让不得切断合法的跨组通道。 */
class ClusterBorderClearanceTest {
    private val clearance = ClusterBorderClearance(mapOf("cross" to listOf(Rectangle2D.Double(40.0, 40.0, 160.0, 120.0))))

    @Test
    fun `parallel runs on either side of borders need clearance`() {
        assertTrue(clearance.blocks(RoutingSegment(60.0, 40.0, 180.0, 40.0), "cross"))
        assertTrue(clearance.blocks(RoutingSegment(60.0, 35.0, 180.0, 35.0), "cross"))
        assertTrue(clearance.blocks(RoutingSegment(60.0, 45.0, 180.0, 45.0), "cross"))
        assertTrue(clearance.blocks(RoutingSegment(195.0, 60.0, 195.0, 140.0), "cross"))
        assertFalse(clearance.blocks(RoutingSegment(60.0, 28.0, 180.0, 28.0), "cross"))
        assertFalse(clearance.blocks(RoutingSegment(212.0, 60.0, 212.0, 140.0), "cross"))
    }

    @Test
    fun `normal crossings corners and internal routes remain available`() {
        assertFalse(clearance.blocks(RoutingSegment(120.0, 10.0, 120.0, 80.0), "cross"))
        assertFalse(clearance.blocks(RoutingSegment(170.0, 100.0, 240.0, 100.0), "cross"))
        assertFalse(clearance.blocks(RoutingSegment(220.0, 40.0, 260.0, 40.0), "cross"))
        assertFalse(clearance.blocks(RoutingSegment(60.0, 40.0, 180.0, 40.0), "local"))
    }

    @Test
    fun `labels cannot cover cluster frames`() {
        assertTrue(clearance.blocksLabel(Rectangle2D.Double(80.0, 32.0, 80.0, 16.0), "cross"))
        assertFalse(clearance.blocksLabel(Rectangle2D.Double(80.0, 64.0, 80.0, 16.0), "cross"))
    }
}
