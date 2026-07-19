package com.jedon.kellikanvas.permission

enum class PermissionRowId {
    Internet,
    LocalNetwork,
    ActivityRecognition,
    BodySensors,
}

enum class PermissionStatus {
    GrantedAtInstall,
    Granted,
    Denied,
    NotApplicable,
}

data class PermissionRow(
    val id: PermissionRowId,
    val status: PermissionStatus,
)

data class PermissionSnapshot(
    val rows: List<PermissionRow>,
)
