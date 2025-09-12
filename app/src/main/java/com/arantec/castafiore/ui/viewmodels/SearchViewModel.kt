package com.arantec.castafiore.ui.viewmodels

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.arantec.castafiore.data.models.Playlist
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.models.Album
import com.arantec.castafiore.data.models.Artist
import com.arantec.castafiore.ui.adapters.SearchResultsAdapter

class SearchViewModel : ViewModel() {
    val query: MutableLiveData<String> = MutableLiveData("")
    val items: MutableLiveData<List<SearchResultsAdapter.Item>> = MutableLiveData(emptyList())
    // Public playlists cache for SearchFragment grid
    val publicPlaylists: MutableLiveData<List<Playlist>> = MutableLiveData(emptyList())

    // Full search results to rebuild views based on filters
    val fullSongs: MutableLiveData<List<Song>> = MutableLiveData(emptyList())
    val fullAlbums: MutableLiveData<List<Album>> = MutableLiveData(emptyList())
    val fullArtists: MutableLiveData<List<Artist>> = MutableLiveData(emptyList())
    val bestItem: MutableLiveData<SearchResultsAdapter.Item?> = MutableLiveData(null)
}
