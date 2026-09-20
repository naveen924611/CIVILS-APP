# Decisions and verified facts
Log build-time verifications here (model names, quotas, feed URLs, library versions, Oracle shapes).

| Date | Topic | Finding |
|---|---|---|
| 2026-09-20 | Gradle | Current stable Gradle reported as 9.7.1 (services.gradle.org). CI pins 8.14.3 to match AGP 8.13. |
| 2026-09-20 | AGP | developer.android.com lists AGP 9.4.0 as newest (needs Gradle 9.6+, JDK 17). Not used yet: AGP 9 changes Kotlin/KSP/Hilt integration and could not be test-built here. |
| 2026-09-20 | Android versions | Chosen: AGP 8.13.0, Kotlin 2.2.20, KSP 2.2.20-2.0.2, Hilt 2.57.1, Compose BOM 2025.09.00, Retrofit 3.0.0, OkHttp 5.1.0, Firebase BOM 34.3.0. **Unverified**: Maven was blocked from the build sandbox; the first GitHub Actions run is the check. |
| 2026-09-20 | Package name | `com.naveen.civilscompanion` (`in` is a Kotlin keyword). |
| 2026-09-20 | Fonts | Fraunces, IBM Plex Sans, Noto Sans Telugu (all SIL OFL 1.1) from Fontsource npm packages, converted woff2 -> ttf; Latin/Telugu subsets only. Licences in app assets. |
| 2026-09-20 | Vector search | Not needed until M4. Decide sqlite-vec vs ChromaDB then. |
