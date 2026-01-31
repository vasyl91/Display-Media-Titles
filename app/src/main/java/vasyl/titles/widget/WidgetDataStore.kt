package vasyl.titles.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.widgetDataStore: DataStore<Preferences> by preferencesDataStore(name = "savedPrefs")