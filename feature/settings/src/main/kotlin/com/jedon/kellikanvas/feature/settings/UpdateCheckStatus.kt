package com.jedon.kellikanvas.feature.settings

fun updateCheckStatusLabel(state: UpdateCheckUiState): String = when (state) {
    UpdateCheckUiState.Idle -> "Idle"
    UpdateCheckUiState.Checking -> "Checking"
    UpdateCheckUiState.UpToDate -> "Up to date"
    is UpdateCheckUiState.UpdateAvailable -> "Update available v${state.versionName}"
    UpdateCheckUiState.Downloading -> "Downloading"
    is UpdateCheckUiState.ReadyToInstall -> "Ready to install"
    is UpdateCheckUiState.Error -> state.message
}
