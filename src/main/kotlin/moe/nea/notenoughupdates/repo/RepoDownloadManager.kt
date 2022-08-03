package moe.nea.notenoughupdates.repo

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.jvm.nio.*
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import moe.nea.notenoughupdates.NotEnoughUpdates
import moe.nea.notenoughupdates.NotEnoughUpdates.logger
import moe.nea.notenoughupdates.util.iterate
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipInputStream
import kotlin.io.path.*


object RepoDownloadManager {

    val repoSavedLocation = NotEnoughUpdates.DATA_DIR.resolve("repo-extracted")
    val repoMetadataLocation = NotEnoughUpdates.DATA_DIR.resolve("loaded-repo-sha.txt")

    private fun loadSavedVersionHash(): String? =
            if (repoSavedLocation.exists()) {
                if (repoMetadataLocation.exists()) {
                    try {
                        repoMetadataLocation.readText().trim()
                    } catch (e: IOException) {
                        null
                    }
                } else {
                    null
                }
            } else null

    private fun saveVersionHash(versionHash: String) {
        latestSavedVersionHash = versionHash
        repoMetadataLocation.writeText(versionHash)
    }

    var latestSavedVersionHash: String? = loadSavedVersionHash()
        private set

    @Serializable
    private class GithubCommitsResponse(val sha: String)

    private suspend fun requestLatestGithubSha(): String? {
        val response =
                NotEnoughUpdates.httpClient.get("https://api.github.com/repos/${RepoManager.config.user}/${RepoManager.config.repo}/commits/${RepoManager.config.branch}")
        if (response.status.value != 200) {
            return null
        }
        return response.body<GithubCommitsResponse>().sha
    }

    private suspend fun downloadGithubArchive(url: String): Path = withContext(IO) {
        val response = NotEnoughUpdates.httpClient.get(url)
        val targetFile = Files.createTempFile("notenoughupdates-repo", ".zip")
        val outputChannel = Files.newByteChannel(targetFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        response.bodyAsChannel().copyTo(outputChannel)
        targetFile
    }

    /**
     * Downloads the latest repository from github, setting [latestSavedVersionHash].
     * @return true, if an update was performed, false, otherwise (no update needed, or wasn't able to complete update)
     */
    suspend fun downloadUpdate(): Boolean = withContext(CoroutineName("Repo Update Check")) {
        val latestSha = requestLatestGithubSha()
        if (latestSha == null) {
            logger.warn("Could not request github API to retrieve latest REPO sha.")
            return@withContext false
        }
        val currentSha = loadSavedVersionHash()
        if (latestSha != currentSha) {
            val requestUrl = "https://github.com/${RepoManager.config.user}/${RepoManager.config.repo}/archive/$latestSha.zip"
            logger.info("Planning to upgrade repository from $currentSha to $latestSha from $requestUrl")
            val zipFile = downloadGithubArchive(requestUrl)
            logger.info("Download repository zip file to $zipFile. Deleting old repository")
            withContext(IO) { repoSavedLocation.toFile().deleteRecursively() }
            logger.info("Extracting new repository")
            withContext(IO) { extractNewRepository(zipFile) }
            logger.info("Repository loaded on disk.")
            saveVersionHash(latestSha)
            return@withContext true
        } else {
            logger.debug("Repository on latest sha $currentSha. Not performing update")
            return@withContext false
        }
    }

    private fun extractNewRepository(zipFile: Path) {
        repoSavedLocation.createDirectories()
        ZipInputStream(zipFile.inputStream()).use { cis ->
            while (true) {
                val entry = cis.nextEntry ?: break
                if (entry.isDirectory) continue
                val extractedLocation =
                        repoSavedLocation.resolve(
                                entry.name.substringAfter('/', missingDelimiterValue = "")
                        )
                if (repoSavedLocation !in extractedLocation.iterate { it.parent }) {
                    logger.error("Not Enough Updates detected an invalid zip file. This is a potential security risk, please report this in the Moulberry discord.")
                    throw RuntimeException("Not Enough Updates detected an invalid zip file. This is a potential security risk, please report this in the Moulberry discord.")
                }
                extractedLocation.parent.createDirectories()
                cis.copyTo(extractedLocation.outputStream())
                cis.closeEntry()
            }
        }
    }


}
