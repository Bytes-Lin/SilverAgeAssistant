package com.example.silverageassistant.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.silverageassistant.domain.weather.LocationPermissionRequiredException
import com.example.silverageassistant.domain.weather.LocationUnavailableException
import com.example.silverageassistant.domain.weather.WeatherRepository
import com.example.silverageassistant.domain.weather.WeatherSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeWeatherUiState(
    val snapshot: WeatherSnapshot? = null,
    val isLoading: Boolean = false,
    val isStale: Boolean = false,
    val needsLocationPermission: Boolean = false,
    val message: String? = null,
)

class HomeWeatherViewModel(
    private val repository: WeatherRepository,
    externalScope: CoroutineScope? = null,
) : ViewModel() {
    private val workScope = externalScope ?: viewModelScope
    private val _uiState = MutableStateFlow(HomeWeatherUiState())
    val uiState: StateFlow<HomeWeatherUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null

    fun refreshWeather() {
        if (refreshJob?.isActive == true) return
        _uiState.update {
            it.copy(
                isLoading = true,
                needsLocationPermission = false,
                message = null,
            )
        }
        refreshJob = workScope.launch {
            try {
                val result = repository.getWeather()
                _uiState.update {
                    it.copy(
                        snapshot = result.snapshot,
                        isLoading = false,
                        isStale = result.isStale,
                        needsLocationPermission = false,
                        message = if (result.isStale) {
                            "暂时无法更新，正在显示上次天气。"
                        } else {
                            null
                        },
                    )
                }
            } catch (_: LocationPermissionRequiredException) {
                onPermissionResult(false)
            } catch (_: LocationUnavailableException) {
                showError("无法获取当前位置，请检查手机定位是否已开启。")
            } catch (_: Exception) {
                showError("天气暂时无法更新，请稍后再试。")
            }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            refreshWeather()
        } else {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    needsLocationPermission = true,
                    message = "允许获取大致位置后，才能查询当地天气。",
                )
            }
        }
    }

    private fun showError(message: String) {
        _uiState.update {
            it.copy(
                isLoading = false,
                isStale = it.snapshot != null,
                needsLocationPermission = false,
                message = message,
            )
        }
    }

    class Factory(
        private val repository: WeatherRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HomeWeatherViewModel::class.java))
            return HomeWeatherViewModel(repository) as T
        }
    }
}
