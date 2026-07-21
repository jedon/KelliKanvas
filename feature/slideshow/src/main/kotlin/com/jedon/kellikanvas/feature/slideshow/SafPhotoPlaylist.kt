package com.jedon.kellikanvas.feature.slideshow

import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.source.SourceAdapter

object SafPhotoPlaylist {
    suspend fun build(
        adapter: SourceAdapter,
        roots: List<SelectedRoot>,
        pageSize: Int = 64,
    ): List<AssetRef> = CollectionPhotoPlaylist.build(
        adapters = mapOf(adapter.profileId to adapter),
        roots = roots,
        pageSize = pageSize,
    ).photos
}
