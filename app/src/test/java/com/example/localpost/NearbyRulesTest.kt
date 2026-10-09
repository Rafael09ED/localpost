package com.example.localpost

import org.junit.Assert.*
import org.junit.Test

class NearbyRulesTest {
    @Test fun initialRequestPlusOnlyThreeRetries() {
        for (count in 0..3) assertTrue(NearbyTransport.canRequest(count))
        assertFalse(NearbyTransport.canRequest(4))
        assertFalse(NearbyTransport.canRequest(5))
    }

    @Test fun tieBreakerHasOneDialer() {
        assertTrue(NearbyTransport.shouldDial("0000000000000001", "0000000000000002"))
        assertFalse(NearbyTransport.shouldDial("0000000000000002", "0000000000000001"))
        assertFalse(NearbyTransport.shouldDial("0000000000000001", "0000000000000001"))
    }

    @Test fun permissionsFollowPlatformBoundary() {
        assertTrue(NearbyPermissions.required(32).contains("android.permission.ACCESS_FINE_LOCATION"))
        assertTrue(NearbyPermissions.required(32).contains("android.permission.ACCESS_COARSE_LOCATION"))
        assertFalse(NearbyPermissions.required(32).contains("android.permission.NEARBY_WIFI_DEVICES"))
        assertFalse(NearbyPermissions.required(33).contains("android.permission.ACCESS_FINE_LOCATION"))
        assertTrue(NearbyPermissions.required(33).contains("android.permission.NEARBY_WIFI_DEVICES"))
        assertFalse(NearbyPermissions.required(36).contains("android.permission.ACCESS_LOCAL_NETWORK"))
        assertTrue(NearbyPermissions.required(37).contains("android.permission.ACCESS_LOCAL_NETWORK"))
    }
}