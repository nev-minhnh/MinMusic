package com.metrolist.desktop.state.playback

/**
 * Path hook for imported local files (`local_<hash>` ids, v2 `import.files`): the sidecar installs [resolver], which
 * maps an id to a `file:` URI. v1 never sets it, so its playback is unchanged.
 */
object LocalMediaPaths {
    @Volatile
    var resolver: ((String) -> String?)? = null

    fun lookup(id: String): String? = resolver?.invoke(id)
}
