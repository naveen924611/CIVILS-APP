package com.naveen.civilscompanion.ui.nav

/** The 10 rail items, in the order and with the icon paths of the approved design (Nav.dc.html). */
enum class Destination(
    val route: String,
    val label: String,
    val iconPath: String,
    /** Milestone in which the real screen is built (shown on placeholders). */
    val milestone: String,
) {
    Today("today", "Today", "M4 5h16v15H4zM4 10h16M9 3v4M15 3v4", "M5"),
    Briefs("briefs", "Briefs", "M4 5h13v14H6a2 2 0 0 1-2-2zM17 9h3v8a2 2 0 0 1-2 2M8 9h5M8 13h5", "M2"),
    Library("library", "Library", "M4 4h4v16H4zM10 4h4v16h-4zM16 5l4 1-3 14-4-1", "M3"),
    Read("read", "Read", "M4 19a2 2 0 0 1 2-2h13V3H6a2 2 0 0 0-2 2zM4 19v2h15", "M3"),
    Notes("notes", "Notes", "M9 6h11M9 12h11M9 18h11M4 6h1M4 12h1M4 18h1", "M4"),
    Revise("revise", "Revise", "M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7", "M5"),
    Ask("ask", "Ask", "M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3zM5 11a7 7 0 0 0 14 0M12 18v3", "M6"),
    Focus("focus", "Focus", "M4 14a8 8 0 1 0 16 0a8 8 0 1 0-16 0M12 10v4l2 2M9 2h6", "M11"),
    Alerts("alerts", "Alerts", "M6 16v-5a6 6 0 0 1 12 0v5l2 2H4zM10 21h4", "M2"),
    Settings("settings", "Settings", "M4 6h9M17 6h3M4 12h3M11 12h9M4 18h11M19 18h1M15 4v4M9 10v4M17 16v4", "M7"),
}
