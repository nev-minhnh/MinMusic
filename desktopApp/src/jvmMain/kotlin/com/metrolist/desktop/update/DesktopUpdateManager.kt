/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.update

import com.metrolist.shared.BuildConfig
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.update_not_published_stable
import com.metrolist.shared.network.sharedOkHttpClient
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.update.NIGHTLY_TAG
import com.metrolist.shared.update.ReleaseVersion
import com.metrolist.shared.update.checkNightly
import com.metrolist.shared.update.followsNightlyUpdates
import com.metrolist.shared.utils.OsUtil
import com.metrolist.shared.utils.XdgUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Request
import org.jetbrains.compose.resources.getString
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * MinMusic for Windows updates from nev-minhnh/MinMusic releases tagged `windows-vX.Y.Z`.
 * Android releases (`vX.Y.Z`) live in the same repository and are always ignored here.
 */
object DesktopUpdateManager {
    private const val API_BASE = "https://api.github.com/repos/nev-minhnh/MinMusic"
    internal const val RELEASES_URL = "$API_BASE/releases?per_page=50"
    private const val CACHE_SOURCE = "desktop-github-releases-minmusic-windows-v1"
    private const val MAX_INSTALLER_BYTES = 1024L * 1024 * 1024

    private val client = sharedOkHttpClient
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableDownloadProgress = MutableStateFlow<Int?>(null)
    val downloadProgress = mutableDownloadProgress.asStateFlow()

    @Volatile private var activeDownload: Call? = null

    data class RecoveryRelease(
        val version: String,
        val url: String,
        val inApp: Boolean,
    )

    suspend fun checkForRecovery(): Result<RecoveryRelease?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val releasesJson = getReleaseJson(RELEASES_URL)
                findUpdate(releasesJson, BuildConfig.APP_VERSION)
                    ?.let {
                        RecoveryRelease(
                            it.version,
                            it.url,
                            it.canInstall(installationRoot() != null),
                        )
                    }
            }
        }

    suspend fun check(): Result<List<GitHubDesktopRelease>> =
        withContext(Dispatchers.IO) {
            runCatching {
                Settings.shared.lastUpdateCheckAttemptTimestamp = System.currentTimeMillis()
                val releases = parseReleases(getReleaseJson(RELEASES_URL))
                currentCoroutineContext().ensureActive()
                Settings.shared.cachedUpdateReleasesJson = json.encodeToString(releases)
                Settings.shared.lastUpdateCheckTimestamp = System.currentTimeMillis()
                releases
            }.onFailure { if (it is CancellationException) throw it }
        }

    suspend fun cachedReleases(): List<GitHubDesktopRelease> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (Settings.shared.updateCacheSource != CACHE_SOURCE) {
                    Settings.shared.cachedUpdateReleasesJson = ""
                    Settings.shared.lastUpdateCheckTimestamp = 0L
                    Settings.shared.lastUpdateCheckAttemptTimestamp = 0L
                    Settings.shared.updateCacheSource = CACHE_SOURCE
                }
                parseReleases(Settings.shared.cachedUpdateReleasesJson)
            }.getOrDefault(emptyList())
        }

    /** Only published Windows releases (`windows-vX.Y.Z`), newest first. */
    internal fun parseReleases(releasesJson: String): List<GitHubDesktopRelease> =
        json
            .decodeFromString<List<GitHubDesktopRelease>>(releasesJson)
            .filter { !it.draft && it.isWindowsRelease && ReleaseVersion.parse(it.version) != null }
            .sortedByDescending { ReleaseVersion.parse(it.version) }

    /** MinMusic publishes no desktop nightlies, so this only finds one if a nightly is ever listed. */
    internal fun newNightly(
        releases: List<GitHubDesktopRelease>,
        confirm: Boolean,
        currentVersion: String = BuildConfig.APP_VERSION,
    ): GitHubDesktopRelease? {
        val nightly = releases.firstOrNull { it.isNightly } ?: return null
        val asset = nightly.nightlyAsset ?: return null
        val result = checkNightly(currentVersion, nightly.targetCommit, asset.digest, Settings.shared.installedNightlyDigest)
        if (confirm) Settings.shared.installedNightlyDigest = result.installedDigest
        return nightly.takeIf { result.available }
    }

    internal fun availableUpdate(releases: List<GitHubDesktopRelease>): GitHubDesktopRelease? =
        releases.firstOrNull { isNewerDesktopVersion(it.version, BuildConfig.APP_VERSION) }
            ?: newNightly(releases, confirm = true)
                ?.takeIf { followsNightlyUpdates(Settings.shared.updateChannel, BuildConfig.APP_VERSION) }

    internal fun findUpdate(
        releasesJson: String,
        currentVersion: String,
        includePrereleases: Boolean = true,
    ): GitHubDesktopRelease? =
        parseReleases(releasesJson)
            .firstOrNull { (includePrereleases || !it.prerelease) && isNewerDesktopVersion(it.version, currentVersion) }

    suspend fun canInstall(release: GitHubDesktopRelease): Boolean =
        withContext(Dispatchers.IO) { release.canInstall(installationRoot() != null) }

    fun cancelDownload() {
        activeDownload?.cancel()
    }

    /** Downloads the release's setup .exe, verifies its GitHub SHA-256 digest and starts it; the app then exits. */
    suspend fun downloadAndLaunch(
        version: String,
        onLaunched: suspend () -> Unit = {},
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutableDownloadProgress.value = null
            runCatching {
                checkNotNull(installationRoot()) { "MinMusic installation was not found" }
                val tag = "$WINDOWS_TAG_PREFIX$version"
                val release = getRelease("$API_BASE/releases/tags/$tag")
                val errorMsg = getString(Res.string.update_not_published_stable)
                check(release.tagName == tag && release.canInstall(registeredInstallation = true)) { errorMsg }
                val expectedName = windowsInstallerAssetName(version)
                val asset =
                    checkNotNull(release.installer) {
                        "Release $tag does not contain $expectedName"
                    }
                check(asset.state == "uploaded") { "The installer is not ready" }
                check(asset.size in 1..MAX_INSTALLER_BYTES) { "The installer has an invalid size" }
                val expectedDigest = parseGitHubSHA256(asset.digest)

                val updatesDirectory = File(XdgUtils.getXdgCacheHome(), "metrolist/updates").toPath()
                Files.createDirectories(updatesDirectory)
                val partial = updatesDirectory.resolve("$expectedName.part")
                val destination = updatesDirectory.resolve(expectedName)
                Files.deleteIfExists(partial)
                try {
                    download(asset, partial, expectedDigest)
                    Files.move(
                        partial,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } finally {
                    Files.deleteIfExists(partial)
                }

                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    // The setup upgrades the installed copy in place (same upgrade code); MinMusic exits right after.
                    ProcessBuilder(destination.toString()).directory(updatesDirectory.toFile()).start()
                    onLaunched()
                }
                Unit
            }.onFailure { if (it is CancellationException) throw it }
                .also { activeDownload = null }
        }

    private fun getRelease(url: String): GitHubDesktopRelease = json.decodeFromString(getReleaseJson(url))

    private fun getReleaseJson(url: String): String {
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2026-03-10")
                .header("User-Agent", "MinMusic-Desktop/${BuildConfig.APP_VERSION}")
                .build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GitHub returned ${response.code}" }
            checkNotNull(response.body).string()
        }
    }

    private suspend fun download(
        asset: GitHubDesktopAsset,
        destination: Path,
        expectedDigest: ByteArray,
    ) {
        val request =
            Request
                .Builder()
                .url(asset.downloadUrl)
                .header("User-Agent", "MinMusic-Desktop/${BuildConfig.APP_VERSION}")
                .build()
        val call = client.newCall(request)
        activeDownload = call
        try {
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                check(response.isSuccessful) { "Installer download returned ${response.code}" }
                val body = checkNotNull(response.body)
                check(body.contentLength() < 0 || body.contentLength() == asset.size) { "Installer download size mismatch" }
                body.byteStream().buffered().use { input ->
                    Files.newOutputStream(destination).buffered().use { output ->
                        copyVerifiedUpdate(input, output, asset.size, expectedDigest) { mutableDownloadProgress.value = it }
                    }
                }
            }
        } finally {
            activeDownload = null
        }
    }

    /**
     * The installed copy that the setup .exe can upgrade: a packaged MinMusic.exe launcher that is not the
     * portable ZIP (the portable ZIP carries install-manifest.json next to the launcher). Portable copies and
     * development runs open the release page instead.
     */
    private fun installationRoot(): DesktopInstallation? =
        runCatching {
            if (!OsUtil.isWindows) return null
            val launcher = System.getProperty("jpackage.app-path")?.takeIf(String::isNotBlank)?.let { Path.of(it) } ?: return null
            if (!launcher.fileName.toString().equals(WINDOWS_LAUNCHER_NAME, ignoreCase = true)) return null
            if (!Files.isRegularFile(launcher)) return null
            val root = launcher.toAbsolutePath().parent ?: return null
            if (Files.exists(root.resolve(PORTABLE_MARKER))) return null
            DesktopInstallation(root, BuildConfig.APP_VERSION)
        }.getOrNull()
}

internal const val WINDOWS_TAG_PREFIX = "windows-v"
private const val WINDOWS_LAUNCHER_NAME = "MinMusic.exe"
private const val PORTABLE_MARKER = "install-manifest.json"

/** Release asset names; minmusic-windows-release.yml must publish exactly these. */
internal fun windowsInstallerAssetName(version: String): String = "MinMusic-$version-windows-x64-setup.exe"

internal fun windowsPortableAssetName(version: String): String = "MinMusic-$version-windows-x64-portable.zip"

@Serializable
data class GitHubDesktopRelease(
    @SerialName("tag_name") val tagName: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubDesktopAsset>,
    @SerialName("target_commitish") val targetCommit: String? = null,
) {
    val version: String get() = tagName.removePrefix(WINDOWS_TAG_PREFIX)
    val isWindowsRelease: Boolean get() = tagName.startsWith(WINDOWS_TAG_PREFIX)
    val isNightly: Boolean get() = tagName == NIGHTLY_TAG

    /** Nightlies share one tag, so their package digest tells them apart. */
    val updateKey: String get() = if (isNightly) "$NIGHTLY_TAG:${nightlyAsset?.digest}" else version

    // Nightly assets keep fixed names so their download links never change.
    val nightlyAsset: GitHubDesktopAsset?
        get() =
            assets.firstOrNull { asset ->
                asset.state == "uploaded" &&
                    when {
                        OsUtil.isWindows -> asset.name.endsWith("-windows.zip")
                        OsUtil.isMac -> asset.name.contains("-macos", ignoreCase = true)
                        else -> asset.name.endsWith("-linux-${desktopArchitecture()}.AppImage")
                    }
            }
    val installer: GitHubDesktopAsset?
        get() = assets.singleOrNull { it.name == windowsInstallerAssetName(version) }
    val portable: GitHubDesktopAsset?
        get() = assets.singleOrNull { it.name == windowsPortableAssetName(version) }
    val url: String get() =
        nightlyAsset?.takeIf { isNightly }?.downloadUrl
            ?: "https://github.com/nev-minhnh/MinMusic/releases/tag/$tagName"

    internal fun canInstall(registeredInstallation: Boolean): Boolean =
        registeredInstallation && isWindowsRelease && installer != null && !draft && !prerelease &&
            ReleaseVersion.parse(version)?.prerelease?.isEmpty() == true
}

@Serializable
data class GitHubDesktopAsset(
    val name: String,
    val state: String,
    val size: Long,
    val digest: String? = null,
    @SerialName("browser_download_url") val downloadUrl: String,
)

internal fun parseGitHubSHA256(value: String?): ByteArray {
    val digest = value?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
    require(digest != null && digest.length == 64 && digest.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        "The GitHub asset is missing a valid SHA-256 digest"
    }
    return digest.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

internal fun isNewerDesktopVersion(
    candidate: String,
    current: String,
): Boolean {
    val candidateVersion = ReleaseVersion.parse(candidate) ?: return false
    val currentVersion = ReleaseVersion.parse(current) ?: return false
    return candidateVersion > currentVersion
}

private data class DesktopInstallation(
    val root: Path,
    val version: String,
)

internal suspend fun copyVerifiedUpdate(
    input: InputStream,
    output: OutputStream,
    expectedSize: Long,
    expectedDigest: ByteArray,
    onProgress: (Int) -> Unit,
) {
    require(expectedSize > 0)
    val digest = MessageDigest.getInstance("SHA-256")
    var downloaded = 0L
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    onProgress(0)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        check(downloaded + count <= expectedSize) { "Installer download exceeded its expected size" }
        output.write(buffer, 0, count)
        digest.update(buffer, 0, count)
        downloaded += count
        onProgress(((downloaded * 100) / expectedSize).toInt())
    }
    currentCoroutineContext().ensureActive()
    check(downloaded == expectedSize) { "Installer download was incomplete" }
    check(MessageDigest.isEqual(digest.digest(), expectedDigest)) { "Installer SHA-256 verification failed" }
}

internal fun desktopUpdateCheckIsDue(
    lastAttempt: Long,
    now: Long,
): Boolean = lastAttempt !in 1..now || now - lastAttempt >= 60 * 60 * 1_000L

private fun desktopArchitecture(): String =
    when (System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "arm64"
        else -> "x64"
    }
