package com.inspiredandroid.kai.testutil

import com.inspiredandroid.kai.data.AppSettings
import com.russhwolf.settings.MapSettings

/**
 * In-memory [AppSettings] for unit tests — no Android dependencies, fully
 * deterministic between test runs.
 */
object TestSettings {
    fun appSettings(): AppSettings = AppSettings(MapSettings())
}
