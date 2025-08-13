package com.arantec.castafiore.ui.viewmodels

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.arantec.castafiore.ui.adapters.SearchResultsAdapter

class SearchViewModel : ViewModel() {
    val query: MutableLiveData<String> = MutableLiveData("")
    val items: MutableLiveData<List<SearchResultsAdapter.Item>> = MutableLiveData(emptyList())
}

