package com.example.silverageassistant.data.weather

import com.example.silverageassistant.domain.weather.GeoCoordinates
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkLocationNameResolverTest {
    @Test
    fun bigDataCloudResponse_prefersCityAndRemovesMunicipalitySuffix() {
        val resolver = BigDataCloudLocationNameResolver()

        val city = resolver.parseCity(
            """
                {
                  "principalSubdivision": "上海市",
                  "city": "上海市",
                  "locality": "黄浦区"
                }
            """.trimIndent(),
        )

        assertEquals("上海", city)
    }

    @Test
    fun fallbackResolver_usesNetworkResultWhenSystemHasNoCity() = runBlocking {
        val coordinates = GeoCoordinates(31.2304, 121.4737)
        val resolver = FallbackLocationNameResolver(
            LocationNameResolver { null },
            LocationNameResolver { received ->
                assertEquals(coordinates, received)
                "上海"
            },
        )

        assertEquals("上海", resolver.resolve(coordinates))
    }
}
