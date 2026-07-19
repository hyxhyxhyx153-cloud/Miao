package com.hyx.miao.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiEndpointSelectorTest {
    @Test
    fun `emulator uses host loopback mapping when no override exists`() {
        val result = ApiEndpointSelector.select(
            hasCustomUrl = false,
            customUrl = "http://custom.example/api/v1/",
            emulatorUrl = "http://10.0.2.2:3000/api/v1/",
            lanUrl = "http://192.168.2.2:3000/api/v1/",
            isEmulator = true,
        )

        assertEquals("http://10.0.2.2:3000/api/v1/", result)
    }

    @Test
    fun `physical device uses lan endpoint when no override exists`() {
        val result = ApiEndpointSelector.select(
            hasCustomUrl = false,
            customUrl = "http://custom.example/api/v1/",
            emulatorUrl = "http://10.0.2.2:3000/api/v1/",
            lanUrl = "http://192.168.2.2:3000/api/v1/",
            isEmulator = false,
        )

        assertEquals("http://192.168.2.2:3000/api/v1/", result)
    }

    @Test
    fun `explicit endpoint wins and receives required trailing slash`() {
        val result = ApiEndpointSelector.select(
            hasCustomUrl = true,
            customUrl = "https://miao.example/api/v1",
            emulatorUrl = "http://10.0.2.2:3000/api/v1/",
            lanUrl = "http://192.168.2.2:3000/api/v1/",
            isEmulator = true,
        )

        assertEquals("https://miao.example/api/v1/", result)
    }
}
