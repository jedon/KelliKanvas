package com.jedon.kellikanvas

import android.content.Context
import com.jedon.kellikanvas.catalog.KelliKanvasDatabaseFactory
import com.jedon.kellikanvas.catalog.preferences.DataStoreAppPreferencesRepository
import com.jedon.kellikanvas.source.saf.ContentResolverSafDocuments
import com.jedon.kellikanvas.source.saf.SafProfile
import com.jedon.kellikanvas.source.saf.SafSourceAdapter

class AppContainer(appContext: Context) {
    val database = KelliKanvasDatabaseFactory.create(appContext)
    val preferences = DataStoreAppPreferencesRepository.create(appContext)

    val contentResolver = appContext.contentResolver

    fun safAdapter(profile: SafProfile): SafSourceAdapter =
        SafSourceAdapter(
            profile = profile,
            documents = ContentResolverSafDocuments(contentResolver),
        )
}
