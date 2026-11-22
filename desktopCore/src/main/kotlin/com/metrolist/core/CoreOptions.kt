package com.metrolist.core

/** Command line of the sidecar (docs/architecture.md 3.3, step 3). Unknown arguments are ignored. */
data class CoreOptions(
    val parentPid: Long? = null,
    val dev: Boolean = false,
    val fixtureHome: String? = null,
    val cdsWarmup: Boolean = false,
    val exePath: String? = null,
    val dataRoot: String? = null,
    val logDir: String? = null,
    /** Print READY only after the backend (DB, PlayerState, ...) is initialised. Default: READY comes first. */
    val readyAfterInit: Boolean = false,
) {
    companion object {
        fun parse(args: Array<String>): CoreOptions {
            var options = CoreOptions()
            var i = 0

            fun value(): String? = args.getOrNull(++i)
            while (i < args.size) {
                when (args[i]) {
                    "--parent-pid" -> options = options.copy(parentPid = value()?.toLongOrNull())
                    "--dev" -> options = options.copy(dev = true)
                    "--fixture-home" -> options = options.copy(fixtureHome = value())
                    "--cds-warmup" -> options = options.copy(cdsWarmup = true)
                    "--exe-path" -> options = options.copy(exePath = value())
                    "--data-root" -> options = options.copy(dataRoot = value())
                    "--log-dir" -> options = options.copy(logDir = value())
                    "--ready-after-init" -> options = options.copy(readyAfterInit = true)
                }
                i++
            }
            return options
        }
    }
}
