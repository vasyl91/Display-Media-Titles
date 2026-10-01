package vasyl.titles.helpers

object Helpers {
    /** Unused since the previous track is tracked by NotificationListener itself; kept for compatibility. */
    var songPrev: String = ""

    /** Live streams (duration 0): the title is only shown once until the metadata or state changes. */
    var counter: Int = 0
}
