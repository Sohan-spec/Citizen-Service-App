package com.sohanreddy.caci

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import kotlinx.coroutines.launch
import java.util.Date

class WaterReleaseViewModel : ViewModel() {

    private val repository = WaterReleaseRepository()

    private val _releases = MutableLiveData<List<WaterRelease>>()
    val releases: LiveData<List<WaterRelease>> = _releases

    private val _scheduleResult = MutableLiveData<Result<String>>()
    val scheduleResult: LiveData<Result<String>> = _scheduleResult

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    fun scheduleRelease(
        locality: String,
        scheduledTimeMillis: Long,
        duration: String,
        note: String,
        officerId: String,
        latitude: Double,
        longitude: Double,
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val release = WaterRelease(
                    locality = locality,
                    scheduledTime = Timestamp(Date(scheduledTimeMillis)),
                    duration = duration,
                    note = note,
                    officerId = officerId,
                    latitude = latitude,
                    longitude = longitude,
                )
                val id = repository.scheduleRelease(release)
                _scheduleResult.value = Result.success(id)
            } catch (e: Exception) {
                _scheduleResult.value = Result.failure(e)
            }
            _isLoading.value = false
        }
    }

    fun loadReleasesForLocality(locality: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _releases.value = repository.getReleasesForLocality(locality)
            } catch (_: Exception) {
                _releases.value = emptyList()
            }
            _isLoading.value = false
        }
    }

    fun loadAllReleases() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _releases.value = repository.getAllScheduledReleases()
            } catch (_: Exception) {
                _releases.value = emptyList()
            }
            _isLoading.value = false
        }
    }

    fun loadReleasesForOfficer(officerId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _releases.value = repository.getReleasesForOfficer(officerId)
            } catch (_: Exception) {
                _releases.value = emptyList()
            }
            _isLoading.value = false
        }
    }
}
