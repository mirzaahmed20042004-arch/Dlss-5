package com.dlss5.optimizer.network

/**
 * Backend wiring for the DLSS-5 Supabase project.
 *
 * Only the *publishable* (anon) key lives here — Row Level Security on the server restricts what it can
 * read or write, and the desktop/CI service key never ships in the APK. Override at build time with
 * `-PdlssSupabaseUrl=... -PdlssSupabaseKey=...` or Gradle properties in `gradle.properties`.
 */
object SupabaseConfig {

    /** Project ref `dpafgmtrnotdctriwplb` (region ap-south-1). */
    const val URL: String = "https://dpafgmtrnotdctriwplb.supabase.co"

    /** Publishable key — safe to embed; every table is protected by RLS. */
    const val ANON_KEY: String = "sb_publishable_6rVT58K1q-NUTEZeKUDLTw_cxw84N9X"

    const val TABLE_DEVICES = "devices"
    const val TABLE_SESSIONS = "sessions"
    const val TABLE_TELEMETRY = "telemetry_samples"
    const val TABLE_CONFIG = "config_versions"

    const val EDGE_FUNCTION_CONFIG = "dlss-config"

    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 15_000

    fun isConfigured(): Boolean =
        URL.startsWith("https://") && !URL.contains("YOUR_PROJECT") &&
            ANON_KEY.isNotBlank() && !ANON_KEY.contains("placeholder")

    fun restUrl(table: String): String = "$URL/rest/v1/$table"

    fun functionUrl(name: String): String = "$URL/functions/v1/$name"
}
