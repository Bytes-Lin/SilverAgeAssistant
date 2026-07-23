package com.example.silverageassistant.ui.home

import com.example.silverageassistant.domain.weather.LocationPermissionRequiredException
import com.example.silverageassistant.domain.weather.WeatherRepository
import com.example.silverageassistant.domain.weather.WeatherResult
import com.example.silverageassistant.testing.weatherSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWeatherViewModelTest {
    @Test
    fun successfulRefresh_exposesWeatherForHome() {
        val viewModel = viewModel(
            WeatherRepository { WeatherResult(weatherSnapshot(), fromCache = false) },
        )

        viewModel.refreshWeather()

        assertEquals("多云", viewModel.uiState.value.snapshot?.current?.condition)
        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.isStale)
    }

    @Test
    fun missingPermission_explainsHowToContinue() {
        val viewModel = viewModel(
            WeatherRepository { throw LocationPermissionRequiredException() },
        )

        viewModel.refreshWeather()

        assertTrue(viewModel.uiState.value.needsLocationPermission)
        assertTrue(viewModel.uiState.value.message!!.contains("大致位置"))
    }

    private fun viewModel(repository: WeatherRepository) = HomeWeatherViewModel(
        repository = repository,
        externalScope = CoroutineScope(Dispatchers.Unconfined),
    )
}
