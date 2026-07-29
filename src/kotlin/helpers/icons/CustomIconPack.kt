package desu.mintgram.helpers.icons

/**
 * A user-created icon pack: a name plus a set of per-icon overrides (original drawable resource
 * *name* — not numeric id, those aren't stable across builds — mapped to a saved override image).
 * Persisted/loaded by [IconPackStorage]; this class is just the in-memory shape.
 */
data class CustomIconPack(
    val id: String,
    var name: String,
    val overriddenNames: MutableSet<String> = mutableSetOf(),
)
