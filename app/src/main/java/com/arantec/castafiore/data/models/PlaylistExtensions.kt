package com.arantec.castafiore.data.models

fun Playlist.getCoverArtUrl(
    serverUrl: String,
    username: String,
    token: String,
    salt: String,
    size: Int
): String? {
    return coverArt?.let {
        // Example URL pattern, adjust as needed for your backend
        "$serverUrl/rest/getCoverArt.view?id=$it&u=$username&t=$token&s=$salt&c=Castafiore&v=1.16.1&size=$size"
    }
}

