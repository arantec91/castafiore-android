package com.arantec.castafiore.data.model

import com.google.gson.annotations.SerializedName

// Wrapper para la respuesta del API
// Importa SubsonicResponse desde SubsonicResponse.kt

data class SubsonicResponseWrapper(
    @SerializedName("subsonic-response")
    val subsonicResponse: SubsonicResponse?
)
