package com.virtualap.app.util

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object VirtualAPInstaller {
    suspend fun install(
        context: Context,
        onProgress: (Int, String) -> Unit  // level, message
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val assetManager = context.assets
            val cacheDir = context.cacheDir

            // Step 1: Create directories
            onProgress(Log.INFO, "Creating directories...")
            Shell.cmd("mkdir -p ${Constants.VAP_DIR}/bin ${Constants.VAP_DIR}/logs ${Constants.VAP_DIR}/rootfs").exec()

            // Step 2: Deploy busybox
            onProgress(Log.INFO, "Installing busybox...")
            deployAsset(context, "bin/busybox", "${Constants.VAP_DIR}/bin/busybox", cacheDir)
                ?.let { return@withContext Result.failure(it) }
            Shell.cmd("chmod 755 ${Constants.VAP_DIR}/bin/busybox").exec()

            // Step 3: Deploy vap.sh
            onProgress(Log.INFO, "Installing vap.sh...")
            deployAsset(context, "backend/vap.sh", "${Constants.VAP_DIR}/vap.sh", cacheDir)
                ?.let { return@withContext Result.failure(it) }
            Shell.cmd("chmod 755 ${Constants.VAP_DIR}/vap.sh").exec()

            // Step 4: Deploy start-ap
            onProgress(Log.INFO, "Installing start-ap...")
            deployAsset(context, "backend/start-ap", "${Constants.VAP_DIR}/start-ap", cacheDir)
                ?.let { return@withContext Result.failure(it) }
            Shell.cmd("chmod 755 ${Constants.VAP_DIR}/start-ap").exec()

            // Step 5: Extract rootfs tarball
            onProgress(Log.INFO, "Extracting Alpine rootfs (this takes a moment)...")
            val rootfsAssets = assetManager.list("rootfs") ?: emptyArray()
            val tarAsset = rootfsAssets.firstOrNull { it.endsWith(".tar.xz") }
                ?: return@withContext Result.failure(Exception("No rootfs tarball found in assets/rootfs/. Run build_rootfs.sh first and rebuild the APK."))

            val tempTar = File(cacheDir, tarAsset)
            assetManager.open("rootfs/$tarAsset").use { input ->
                tempTar.outputStream().use { input.copyTo(it) }
            }
            onProgress(Log.INFO, "Unpacking $tarAsset...")
            // Android's system tar has no xz support; use shipped busybox for both
            // decompression (xzcat) and extraction (tar), piped to avoid a temp .tar file.
            val extractResult = Shell.cmd(
                "${Constants.BUSYBOX} xzcat '${tempTar.absolutePath}'" +
                " | ${Constants.BUSYBOX} tar xf - -C '${Constants.VAP_DIR}/rootfs/' 2>&1"
            ).exec()
            tempTar.delete()
            if (!extractResult.isSuccess) {
                val errLines = (extractResult.out + extractResult.err)
                    .joinToString("\n").ifBlank { "no output — check busybox xzcat/tar support" }
                return@withContext Result.failure(Exception("rootfs extraction failed:\n$errLines"))
            }

            // Step 6: Verify
            onProgress(Log.INFO, "Verifying installation...")
            val ok = Shell.cmd("test -x ${Constants.VAP_DIR}/start-ap && echo ok").exec()
                .out.any { it.contains("ok") }
            if (!ok) return@withContext Result.failure(Exception("Verification failed: start-ap not executable"))

            onProgress(Log.INFO, "Installation complete!")
            Result.success(Unit)
        } catch (e: Exception) {
            onProgress(Log.ERROR, "Installation failed: ${e.message}")
            Result.failure(e)
        }
    }

    private fun deployAsset(context: Context, assetPath: String, destPath: String, cacheDir: File): Exception? {
        return try {
            val tmpFile = File(cacheDir, File(assetPath).name)
            context.assets.open(assetPath).use { input ->
                tmpFile.outputStream().use { input.copyTo(it) }
            }
            val copyResult = Shell.cmd("cp ${tmpFile.absolutePath} $destPath 2>&1").exec()
            tmpFile.delete()
            if (!copyResult.isSuccess) Exception("Failed to deploy $assetPath: ${copyResult.err.joinToString()}")
            else null
        } catch (e: Exception) {
            e
        }
    }
}
