package me.inkdye.vivoksu.ui.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.Parcelable
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.FileOutputStream
import android.system.Os
import android.util.Log
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.inkdye.vivoksu.BuildConfig
import me.inkdye.vivoksu.Natives
import me.inkdye.vivoksu.ksuApp
import org.json.JSONArray
import java.io.File

/**
 * @author weishu
 * @date 2023/1/1.
 */
private const val TAG = "KsuCli"

private fun getKsuDaemonPath(): String {
    return ksuApp.applicationInfo.nativeLibraryDir + File.separator + "libksud.so"
}

data class FlashResult(val code: Int, val err: String, val showReboot: Boolean) {
    constructor(result: Shell.Result, showReboot: Boolean) : this(result.code, result.err.joinToString("\n"), showReboot)
    constructor(result: Shell.Result) : this(result, result.isSuccess)
}

object KsuCli {
    val SHELL: Shell = createRootShell()
    val GLOBAL_MNT_SHELL: Shell = createRootShell(true)
}

fun getRootShell(globalMnt: Boolean = false): Shell {
    return if (globalMnt) KsuCli.GLOBAL_MNT_SHELL else {
        KsuCli.SHELL
    }
}

inline fun <T> withNewRootShell(
    globalMnt: Boolean = false,
    block: Shell.() -> T
): T {
    return createRootShell(globalMnt).use(block)
}

fun Uri.getFileName(context: Context): String? {
    var fileName: String? = null
    val contentResolver: ContentResolver = context.contentResolver
    val cursor: Cursor? = contentResolver.query(this, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            fileName = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
    }
    return fileName
}

fun createRootShell(globalMnt: Boolean = false): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    val builder = Shell.Builder.create()
    return try {
        if (globalMnt) {
            builder.build(getKsuDaemonPath(), "debug", "su", "-g")
        } else {
            builder.build(getKsuDaemonPath(), "debug", "su")
        }
    } catch (e: Throwable) {
        Log.w(TAG, "ksu failed: ", e)
        try {
            if (globalMnt) {
                builder.build("su", "-mm")
            } else {
                builder.build("su")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "su failed: ", e)
            builder.build("sh")
        }
    }
}

fun execKsud(args: String, newShell: Boolean = false): Boolean {
    return if (newShell) {
        withNewRootShell {
            ShellUtils.fastCmdResult(this, "${getKsuDaemonPath()} $args")
        }
    } else {
        ShellUtils.fastCmdResult(getRootShell(), "${getKsuDaemonPath()} $args")
    }
}

suspend fun getFeatureStatus(feature: String): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val out = shell.newJob()
        .add("${getKsuDaemonPath()} feature check $feature").to(ArrayList<String>(), null).exec().out
    out.firstOrNull()?.trim().orEmpty()
}

suspend fun getFeaturePersistValue(feature: String): Long? = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val out = shell.newJob()
        .add("${getKsuDaemonPath()} feature get --config $feature").to(ArrayList<String>(), null).exec().out
    val valueLine = out.firstOrNull { it.trim().startsWith("Value:") } ?: return@withContext null
    valueLine.substringAfter("Value:").trim().toLongOrNull()
}

fun install() {
    val start = SystemClock.elapsedRealtime()
    val magiskboot = File(ksuApp.applicationInfo.nativeLibraryDir, "libmagiskboot.so").absolutePath
    val libadbroot = File(ksuApp.applicationInfo.nativeLibraryDir, "libadbroot.so").absolutePath
    val result = execKsud("install --magiskboot $magiskboot --libadbroot $libadbroot", true)
    Log.w(TAG, "install result: $result, cost: ${SystemClock.elapsedRealtime() - start}ms")
}

fun listModules(): String {
    val shell = getRootShell()

    val out = shell.newJob()
        .add("${getKsuDaemonPath()} module list").to(ArrayList(), null).exec().out
    return out.joinToString("\n").ifBlank { "[]" }
}

fun getModuleCount(): Int {
    val result = listModules()
    runCatching {
        val array = JSONArray(result)
        return array.length()
    }.getOrElse { return 0 }
}

fun getSuperuserCount(): Int {
    return Natives.getSuperuserCount()
}

fun toggleModule(id: String, enable: Boolean): Boolean {
    val cmd = if (enable) {
        "module enable $id"
    } else {
        "module disable $id"
    }
    val result = execKsud(cmd, true)
    Log.i(TAG, "$cmd result: $result")
    return result
}

fun undoUninstallModule(id: String): Boolean {
    val cmd = "module undo-uninstall $id"
    val result = execKsud(cmd, true)
    Log.i(TAG, "undo uninstall module $id result: $result")
    return result
}

fun uninstallModule(id: String): Boolean {
    val cmd = "module uninstall $id"
    val result = execKsud(cmd, true)
    Log.i(TAG, "uninstall module $id result: $result")
    return result
}

private fun flashWithIO(
    cmd: String,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): Shell.Result {

    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    return withNewRootShell {
        newJob().add(cmd).to(stdoutCallback, stderrCallback).exec()
    }
}

fun flashModule(
    uri: Uri,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): FlashResult {
    val resolver = ksuApp.contentResolver
    with(resolver.openInputStream(uri)) {
        val file = File(ksuApp.cacheDir, "module.zip")
        file.outputStream().use { output ->
            this?.copyTo(output)
        }
        val cmd = "module install ${file.absolutePath}"
        val result = flashWithIO("${getKsuDaemonPath()} $cmd", onStdout, onStderr)
        Log.i("KernelSU", "install module $uri result: $result")

        file.delete()

        return FlashResult(result)
    }
}

fun runModuleAction(
    moduleId: String, onStdout: (String) -> Unit, onStderr: (String) -> Unit
): Boolean {
    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    val result = withNewRootShell(true) {
        newJob().add("${getKsuDaemonPath()} module action $moduleId")
            .to(stdoutCallback, stderrCallback).exec()
    }

    Log.i("KernelSU", "Module runAction result: $result")

    return result.isSuccess
}

fun restoreBoot(
    onStdout: (String) -> Unit, onStderr: (String) -> Unit
): FlashResult {
    val magiskboot = File(ksuApp.applicationInfo.nativeLibraryDir, "libmagiskboot.so")
    val result = flashWithIO("${getKsuDaemonPath()} boot-restore -f --magiskboot $magiskboot", onStdout, onStderr)
    return FlashResult(result)
}

fun uninstallPermanently(
    onStdout: (String) -> Unit, onStderr: (String) -> Unit
): FlashResult {
    val magiskboot = File(ksuApp.applicationInfo.nativeLibraryDir, "libmagiskboot.so")
    val result = flashWithIO("${getKsuDaemonPath()} uninstall --magiskboot $magiskboot --package-name ${BuildConfig.APPLICATION_ID}", onStdout, onStderr)
    return FlashResult(result)
}

@Parcelize
sealed class LkmSelection : Parcelable {
    @Parcelize
    data class LkmUri(val uri: Uri) : LkmSelection()

    @Parcelize
    data class KmiString(val value: String) : LkmSelection()

    @Parcelize
    data object KmiNone : LkmSelection()
}

private const val AVB_ASSETS_DIR = "avb"
private const val PYTHON_ASSETS_DIR = "python_env"
private const val KSU_WORK_DIR = "/data/local/tmp/ksu_work"
private const val PYTHON_WORK_DIR = "/data/local/tmp/ksu_python"

private fun extractAssetDir(context: Context, assetDir: String, targetDir: File) {
    val assetManager = context.assets
    val files = assetManager.list(assetDir) ?: return
    for (name in files) {
        val subAssets = assetManager.list("$assetDir/$name")
        if (subAssets != null && subAssets.isNotEmpty()) {
            val subDir = File(targetDir, name)
            subDir.mkdirs()
            extractAssetDir(context, "$assetDir/$name", subDir)
        } else {
            val outFile = File(targetDir, name)
            if (!outFile.exists()) {
                assetManager.open("$assetDir/$name").use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}

private fun setupBundledPython(context: Context): String? {
    val pythonDir = File(PYTHON_WORK_DIR)
    val launcher = File(pythonDir, "python_launcher.sh")

    // Extract Python environment if launcher script is missing
    if (!launcher.exists()) {
        pythonDir.mkdirs()
        try {
            extractAssetDir(context, PYTHON_ASSETS_DIR, pythonDir)
        } catch (e: Exception) {
            Log.e("KsuCli", "Failed to extract Python environment", e)
            return null
        }
    }

    // Verify extraction succeeded
    val pythonBin = File(pythonDir, "python")
    if (!pythonBin.exists()) {
        Log.e("KsuCli", "Python binary not found after extraction")
        return null
    }
    if (!launcher.exists()) {
        Log.e("KsuCli", "Python launcher script not found after extraction")
        return null
    }

    // Set execute permissions via root shell (single command to avoid race conditions)
    val chmodResult = withNewRootShell {
        newJob().add(
            "chmod 755 ${pythonBin.absolutePath} && " +
            "chmod 755 ${launcher.absolutePath}"
        ).exec()
    }
    if (!chmodResult.isSuccess) {
        Log.e("KsuCli", "chmod failed: ${chmodResult.err.joinToString()}")
        return null
    }

    // Verify the launcher script can execute Python
    val testResult = withNewRootShell {
        newJob().add("sh ${launcher.absolutePath} -c 'import sys; print(sys.version)'").exec()
    }
    if (!testResult.isSuccess) {
        Log.e("KsuCli", "Python test failed: ${testResult.err.joinToString()}")
        return null
    }
    Log.i("KsuCli", "Bundled Python OK: ${testResult.out.joinToString("").trim()}")

    return launcher.absolutePath
}

private fun copyAvbAssetsToWorkDir(context: Context): File {
    val workDir = File(KSU_WORK_DIR)
    workDir.mkdirs()

    val assetNames = listOf("avbtool.py", "testkey_rsa4096.pem", "testkey_rsa2048.pem")
    for (name in assetNames) {
        val outFile = File(workDir, name)
        if (!outFile.exists()) {
            context.assets.open("$AVB_ASSETS_DIR/$name").use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
    return workDir
}

/**
 * Parse full AVB info from avbtool info_image output.
 * Returns: Triple(algorithm, rollback_index, image_size) or null on failure.
 */
private fun parseAvbFullInfo(infoOutput: String): Triple<String, String, String>? {
    val algorithm = Regex("Algorithm:\\s+(\\S+)").find(infoOutput)?.groupValues?.get(1) ?: return null
    val rollbackIndex = Regex("Rollback Index:\\s+(\\d+)").find(infoOutput)?.groupValues?.get(1) ?: "0"
    val imageSize = Regex("Image size:\\s+(\\d+)\\s+bytes").find(infoOutput)?.groupValues?.get(1) ?: return null
    return Triple(algorithm, rollbackIndex, imageSize)
}

/**
 * Run an avbtool command via the bundled Python launcher.
 * Returns the Shell.Result from flashWithIO.
 */
private fun runAvbtool(
    launcherPath: String,
    avbtoolPath: String,
    vararg args: String,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): com.topjohnwu.superuser.Shell.Result {
    val cmd = "sh $launcherPath $avbtoolPath ${args.joinToString(" ")}"
    return flashWithIO(cmd, onStdout, onStderr)
}

/**
 * Lenovo AVB signing using non-chain mode (matching 非链式.sh / rebuild_avb.py).
 *
 * Flow:
 * 1. Parse original (unpatched) boot image to get algorithm, rollback_index, partition_size
 * 2. Erase footer on patched image
 * 3. Add hash footer on patched image using original parameters
 * 4. Rebuild vbmeta.img with original vbmeta algorithm/rollback_index/flags
 * 5. Flash both signed images (or output to Downloads)
 */
private fun lenovoSignAndFlash(
    context: Context,
    originalImage: File,
    patchedImage: File,
    vbmetaImage: File?,
    partition: String,
    ota: Boolean,
    isDirectFlash: Boolean,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): Boolean {
    onStdout("[lenovo] === AVB Signing (non-chain mode) ===")

    // Step 0: Setup Python
    val launcherPath = setupBundledPython(context)
    if (launcherPath == null) {
        onStderr("[lenovo] ERROR: Failed to setup bundled Python environment")
        return false
    }
    onStdout("[lenovo] Python launcher: $launcherPath")

    val workDir = copyAvbAssetsToWorkDir(context)
    val avbtool = File(workDir, "avbtool.py").absolutePath

    // Step 1: Parse ORIGINAL image info (to get algorithm, rollback_index, partition_size)
    onStdout("[lenovo] Step 1: Parsing original image info...")
    val origInfoResult = runAvbtool(launcherPath, avbtool,
        "info_image", "--image", originalImage.absolutePath,
        onStdout = onStdout, onStderr = onStderr)
    if (!origInfoResult.isSuccess) {
        onStderr("[lenovo] ERROR: Failed to parse original image")
        return false
    }
    val origInfo = origInfoResult.out.joinToString("\n")
    val origParsed = parseAvbFullInfo(origInfo)
    if (origParsed == null) {
        onStderr("[lenovo] ERROR: Could not parse algorithm/rollback_index from original image")
        return false
    }
    val (algorithm, rollbackIndex, partitionSize) = origParsed
    onStdout("[lenovo] Original: algorithm=$algorithm, rollback_index=$rollbackIndex, partition_size=$partitionSize")

    // Step 2: Select key based on algorithm
    val keyFile = when {
        algorithm.contains("RSA4096") -> File(workDir, "testkey_rsa4096.pem")
        algorithm.contains("RSA2048") -> File(workDir, "testkey_rsa2048.pem")
        else -> {
            onStderr("[lenovo] ERROR: Unsupported algorithm: $algorithm")
            return false
        }
    }
    onStdout("[lenovo] Using key: ${keyFile.name}")

    // Step 3: Erase footer on patched image
    onStdout("[lenovo] Step 2: Erasing AVB footer on patched image...")
    val eraseResult = runAvbtool(launcherPath, avbtool,
        "erase_footer", "--image", patchedImage.absolutePath,
        onStdout = onStdout, onStderr = onStderr)
    if (!eraseResult.isSuccess) {
        onStderr("[lenovo] WARNING: erase_footer failed (may have no footer), continuing...")
    }

    // Step 4: Add hash footer on patched image (non-chain: algorithm=NONE for sha256)
    // For non-chain mode: use algorithm=NONE with salt from original image
    onStdout("[lenovo] Step 3: Adding hash footer to patched image...")
    val signAlgorithm = "NONE"  // Non-chain mode: sha256 hash only
    val addFooterCmd = mutableListOf(
        avbtool, "add_hash_footer",
        "--image", patchedImage.absolutePath,
        "--partition_name", partition,
        "--partition_size", partitionSize,
        "--algorithm", signAlgorithm
    )
    val addFooterResult = runAvbtool(launcherPath, *addFooterCmd.toTypedArray(),
        onStdout = onStdout, onStderr = onStderr)
    if (!addFooterResult.isSuccess) {
        onStderr("[lenovo] ERROR: Failed to add hash footer")
        return false
    }

    // Step 5: Rebuild vbmeta if vbmeta.img is available
    if (vbmetaImage != null && vbmetaImage.exists()) {
        onStdout("[lenovo] Step 4: Rebuilding vbmeta...")
        val vbmetaInfoResult = runAvbtool(launcherPath, avbtool,
            "info_image", "--image", vbmetaImage.absolutePath,
            onStdout = onStdout, onStderr = onStderr)
        if (vbmetaInfoResult.isSuccess) {
            val vbmetaInfo = vbmetaInfoResult.out.joinToString("\n")
            val vbmetaAlgorithm = Regex("Algorithm:\\s+(\\S+)").find(vbmetaInfo)?.groupValues?.get(1) ?: "SHA256_RSA4096"
            val vbmetaRollback = Regex("Rollback Index:\\s+(\\d+)").find(vbmetaInfo)?.groupValues?.get(1) ?: "0"
            val vbmetaFlags = Regex("Flags:\\s+(\\d+)").find(vbmetaInfo)?.groupValues?.get(1) ?: "0"

            val vbmetaKeyFile = when {
                vbmetaAlgorithm.contains("RSA4096") -> File(workDir, "testkey_rsa4096.pem")
                vbmetaAlgorithm.contains("RSA2048") -> File(workDir, "testkey_rsa2048.pem")
                else -> File(workDir, "testkey_rsa4096.pem")
            }
            val newVbmeta = File(KSU_WORK_DIR, "vbmeta_new.img")
            val rebuildCmd = mutableListOf(
                avbtool, "make_vbmeta_image",
                "--output", newVbmeta.absolutePath,
                "--algorithm", vbmetaAlgorithm,
                "--key", vbmetaKeyFile.absolutePath,
                "--rollback_index", vbmetaRollback,
                "--flags", vbmetaFlags,
                "--rollback_index_location", "0",
                "--padding_size", "4096",
                "--include_descriptors_from_image", vbmetaImage.absolutePath,
                "--include_descriptors_from_image", patchedImage.absolutePath
            )
            val rebuildResult = runAvbtool(launcherPath, *rebuildCmd.toTypedArray(),
                onStdout = onStdout, onStderr = onStderr)
            if (rebuildResult.isSuccess && newVbmeta.exists()) {
                // Replace original vbmeta with rebuilt one
                newVbmeta.copyTo(vbmetaImage, overwrite = true)
                newVbmeta.delete()
                onStdout("[lenovo] vbmeta rebuilt successfully")
            } else {
                onStderr("[lenovo] WARNING: vbmeta rebuild failed, using original")
            }
        }
    }

    // Step 6: Flash or output
    if (isDirectFlash) {
        onStdout("[lenovo] Step 5: Flashing signed images...")
        val slotSuffix = withNewRootShell {
            val slotCmd = if (ota) "boot-info slot-suffix --ota" else "boot-info slot-suffix"
            val out = newJob().add("${getKsuDaemonPath()} $slotCmd").to(ArrayList(), null).exec().out
            out.filter { it.isNotBlank() }.joinToString("").trim()
        }
        // Flash patched+signed boot image
        val bootDevice = "/dev/block/by-name/${partition}${slotSuffix}"
        onStdout("[lenovo] dd if=${patchedImage.absolutePath} of=$bootDevice")
        val ddBoot = flashWithIO("dd if=${patchedImage.absolutePath} of=$bootDevice",
            onStdout, onStderr)
        if (!ddBoot.isSuccess) {
            onStderr("[lenovo] ERROR: Failed to flash $partition")
            return false
        }
        // Flash rebuilt vbmeta
        if (vbmetaImage != null && vbmetaImage.exists()) {
            val vbmetaDevice = "/dev/block/by-name/vbmeta${slotSuffix}"
            onStdout("[lenovo] dd if=${vbmetaImage.absolutePath} of=$vbmetaDevice")
            val ddVbmeta = flashWithIO("dd if=${vbmetaImage.absolutePath} of=$vbmetaDevice",
                onStdout, onStderr)
            if (!ddVbmeta.isSuccess) {
                onStderr("[lenovo] WARNING: Failed to flash vbmeta (non-fatal)")
            }
        }
        onStdout("[lenovo] Flash completed!")
        // Cleanup
        patchedImage.delete()
        vbmetaImage?.delete()
        originalImage.delete()
    } else {
        // Output to Downloads
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloadsDir.mkdirs()
        val ts = System.currentTimeMillis()
        val signedBoot = File(downloadsDir, "lenovo_signed_${partition}_${ts}.img")
        patchedImage.copyTo(signedBoot, overwrite = true)
        patchedImage.delete()
        onStdout("[lenovo] Signed $partition saved: ${signedBoot.absolutePath}")
        if (vbmetaImage != null && vbmetaImage.exists()) {
            val signedVbmeta = File(downloadsDir, "lenovo_signed_vbmeta_${ts}.img")
            vbmetaImage.copyTo(signedVbmeta, overwrite = true)
            vbmetaImage.delete()
            onStdout("[lenovo] Signed vbmeta saved: ${signedVbmeta.absolutePath}")
        }
        originalImage.delete()
    }

    onStdout("[lenovo] === AVB signing completed ===")
    return true
}

fun installBoot(
    bootUri: Uri?,
    lkm: LkmSelection,
    ota: Boolean,
    partition: String?,
    allowShell: Boolean,
    enableAdb: Boolean,
    vivoPatch: Boolean = false,
    lenovoMode: Boolean = false,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): FlashResult {
    val resolver = ksuApp.contentResolver

    val bootFile = bootUri?.let { uri ->
        with(resolver.openInputStream(uri)) {
            val bootFile = File(ksuApp.cacheDir, "boot.img")
            bootFile.outputStream().use { output ->
                this?.copyTo(output)
            }

            bootFile
        }
    }

    val magiskboot = File(ksuApp.applicationInfo.nativeLibraryDir, "libmagiskboot.so")

    // vivo dual-path:
    //   * vivo ON + vendor_boot  -> boot-patch-vivo  (rmvr only, NO LKM)
    //   * vivo ON + init_boot/boot -> boot-patch with --kmi <ver>_vivo (vivo vermagic LKM)
    //   * vivo OFF               -> boot-patch (standard flow)
    val useVivoRmvr = vivoPatch && partition == "vendor_boot"
    val useVivoLkm = vivoPatch && !useVivoRmvr
    onStdout(
        when {
            useVivoRmvr -> "[manager] vivo mode: vendor_boot rmvr (no LKM injection)"
            useVivoLkm -> "[manager] vivo mode: install vivo-vermagic LKM into ${partition ?: "init_boot"}"
            lenovoMode -> "[manager] Lenovo mode: patch + AVB sign + flash"
            else -> "[manager] standard patch flow on ${partition ?: "auto"}"
        }
    )
    var cmd = if (useVivoRmvr) "boot-patch-vivo" else "boot-patch"
    cmd += " --magiskboot ${magiskboot.absolutePath}"

    // Lenovo mode: patch without flash, then sign, then flash manually
    if (lenovoMode && bootFile == null) {
        // Direct install with Lenovo mode: output to /data/local/tmp for signing
        cmd += " -o $KSU_WORK_DIR --out-name lenovo_patched_boot.img"
    } else if (bootFile == null) {
        // Standard direct install: flash directly
        cmd += " -f"
    } else {
        cmd += " -b ${bootFile.absolutePath}"
    }

    if (allowShell) {
        cmd += " --allow-shell"
    }

    if (enableAdb) {
        cmd += " --enable-adbd"
    }

    if (ota) {
        cmd += " -u"
    }

    var lkmFile: File? = null
    when (lkm) {
        is LkmSelection.LkmUri -> {
            lkmFile = with(resolver.openInputStream(lkm.uri)) {
                val file = File(ksuApp.cacheDir, "kernelsu-tmp-lkm.ko")
                file.outputStream().use { output ->
                    this?.copyTo(output)
                }

                file
            }
            cmd += " -m ${lkmFile.absolutePath}"
        }

        is LkmSelection.KmiString -> {
            // vivo LKM path: auto-append _vivo suffix if not already present
            val selectedKmi = if (useVivoLkm && !lkm.value.endsWith("_vivo")) {
                "${lkm.value}_vivo"
            } else {
                lkm.value
            }
            cmd += " --kmi $selectedKmi"
        }

        LkmSelection.KmiNone -> {
            // do nothing
        }
    }

    // output dir
    if (bootFile != null) {
        val downloadsDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        cmd += " -o $downloadsDir"

        if (lenovoMode) {
            cmd += " --out-name lenovo_patched_${System.currentTimeMillis()}.img"
        } else if (useVivoRmvr) {
            cmd += " --out-name kernelsu_patched_rmvr_${System.currentTimeMillis()}.img"
        } else if (useVivoLkm) {
            cmd += " --out-name kernelsu_patched_vivo_${System.currentTimeMillis()}.img"
        }
    }

    partition?.let { part ->
        cmd += " --partition $part"
    }

    val result = flashWithIO("${getKsuDaemonPath()} $cmd", onStdout, onStderr)
    Log.i("KernelSU", "install boot result: ${result.isSuccess}")

    // Lenovo mode: patch → sign (non-chain) → flash/output
    if (lenovoMode && result.isSuccess) {
        val targetPartition = partition ?: "init_boot"
        onStdout("[lenovo] Target partition: $targetPartition")

        val workDir = File(KSU_WORK_DIR)
        workDir.mkdirs()

        val signedOk = if (bootFile != null) {
            // ---- File mode: user selected a boot image file ----
            // Patched image is already in Downloads (lenovo_patched_*.img)
            // We also need the original image for AVB parameter extraction
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val patchedImage = downloadsDir.listFiles()?.filter {
                it.name.startsWith("lenovo_patched_") && it.name.endsWith(".img")
            }?.maxByOrNull { it.lastModified() }

            if (patchedImage == null) {
                onStderr("[lenovo] ERROR: Could not find patched image in Downloads")
                false
            } else {
                // Use the original boot file as reference for AVB params
                // Rename patched to init_boot.img in work dir
                val initBootInWork = File(workDir, "init_boot.img")
                patchedImage.copyTo(initBootInWork, overwrite = true)
                patchedImage.delete()

                // User needs to provide vbmeta.img — skip for now, use patched only
                onStdout("[lenovo] Running non-chain AVB signing...")
                lenovoSignAndFlash(
                    context = ksuApp,
                    originalImage = bootFile,
                    patchedImage = initBootInWork,
                    vbmetaImage = null,
                    partition = targetPartition,
                    ota = ota,
                    isDirectFlash = false,
                    onStdout = onStdout,
                    onStderr = onStderr
                )
            }
        } else {
            // ---- Direct install: read from device, patch, sign, flash ----
            val slotSuffix = withNewRootShell {
                val slotCmd = if (ota) "boot-info slot-suffix --ota" else "boot-info slot-suffix"
                val out = newJob().add("${getKsuDaemonPath()} $slotCmd").to(ArrayList(), null).exec().out
                out.filter { it.isNotBlank() }.joinToString("").trim()
            }

            // 1. Backup original boot image from device
            val origBoot = File(workDir, "original_boot.img")
            val origBootDevice = "/dev/block/by-name/${targetPartition}${slotSuffix}"
            onStdout("[lenovo] Backing up original: $origBootDevice -> ${origBoot.absolutePath}")
            val ddOrig = flashWithIO("dd if=$origBootDevice of=${origBoot.absolutePath}",
                onStdout, onStderr)
            if (!ddOrig.isSuccess || !origBoot.exists()) {
                onStderr("[lenovo] ERROR: Failed to backup original boot image")
                return FlashResult(1, "Failed to backup original boot image", false)
            }

            // 2. Backup original vbmeta from device
            val origVbmeta = File(workDir, "vbmeta.img")
            val vbmetaDevice = "/dev/block/by-name/vbmeta${slotSuffix}"
            onStdout("[lenovo] Backing up vbmeta: $vbmetaDevice -> ${origVbmeta.absolutePath}")
            val ddVbmeta = flashWithIO("dd if=$vbmetaDevice of=${origVbmeta.absolutePath}",
                onStdout, onStderr)
            if (!ddVbmeta.isSuccess || !origVbmeta.exists()) {
                onStdout("[lenovo] WARNING: No vbmeta partition found, proceeding without vbmeta rebuild")
            }

            // 3. Patched image is at /data/local/tmp/lenovo_patched_boot.img
            //    Rename it to init_boot.img in work dir
            val patchedFromKsud = File(KSU_WORK_DIR, "lenovo_patched_boot.img")
            val initBootInWork = File(workDir, "init_boot.img")
            if (patchedFromKsud.exists()) {
                patchedFromKsud.copyTo(initBootInWork, overwrite = true)
                patchedFromKsud.delete()
            } else {
                onStderr("[lenovo] ERROR: Patched image not found at ${patchedFromKsud.absolutePath}")
                return FlashResult(1, "Patched image not found", false)
            }

            // 4. Sign and flash
            onStdout("[lenovo] Running non-chain AVB signing...")
            lenovoSignAndFlash(
                context = ksuApp,
                originalImage = origBoot,
                patchedImage = initBootInWork,
                vbmetaImage = if (origVbmeta.exists()) origVbmeta else null,
                partition = targetPartition,
                ota = ota,
                isDirectFlash = true,
                onStdout = onStdout,
                onStderr = onStderr
            )
        }

        bootFile?.delete()
        lkmFile?.delete()

        val showReboot = bootUri == null && signedOk
        if (showReboot) {
            install()
        }
        return FlashResult(
            if (signedOk) 0 else 1,
            if (signedOk) "" else "AVB signing or flashing failed",
            showReboot
        )
    }

    bootFile?.delete()
    lkmFile?.delete()

    // if boot uri is empty, it is direct install, when success, we should show reboot button
    val showReboot = bootUri == null && result.isSuccess // we create a temporary val here, to avoid calc showReboot double
    if (showReboot) { // because we decide do not update ksud when startActivity
        install() // install ksud here
    }
    return FlashResult(result, showReboot)
}

fun reboot(reason: String = "") {
    if (reason == "soft_reboot") {
        execKsud("soft-reboot", true)
        return
    }
    val shell = getRootShell()
    if (reason == "recovery") {
        // KEYCODE_POWER = 26, hide incorrect "Factory data reset" message
        ShellUtils.fastCmd(shell, "/system/bin/input keyevent 26")
    }
    ShellUtils.fastCmd(shell, "/system/bin/svc power reboot $reason || /system/bin/reboot $reason")
}

fun rootAvailable(): Boolean {
    val shell = getRootShell()
    return shell.isRoot
}

suspend fun getCurrentKmi(): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info current-kmi"
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd")
}

suspend fun getSupportedKmis(): List<String> = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info supported-kmis"
    val out = shell.newJob().add("${getKsuDaemonPath()} $cmd").to(ArrayList(), null).exec().out
    out.filter { it.isNotBlank() }.map { it.trim() }
}

suspend fun isAbDevice(): Boolean = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info is-ab-device"
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim().toBoolean()
}

suspend fun getDefaultPartition(): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    if (shell.isRoot) {
        val cmd = "boot-info default-partition"
        ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim()
    } else {
        if (!Os.uname().release.contains("android12-")) "init_boot" else "boot"
    }
}

suspend fun getSlotSuffix(ota: Boolean): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = if (ota) {
        "boot-info slot-suffix --ota"
    } else {
        "boot-info slot-suffix"
    }
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim()
}

suspend fun getAvailablePartitions(): List<String> = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info available-partitions"
    val out = shell.newJob().add("${getKsuDaemonPath()} $cmd").to(ArrayList(), null).exec().out
    out.filter { it.isNotBlank() }.map { it.trim() }
}

fun hasMagisk(): Boolean {
    val shell = getRootShell(true)
    val result = shell.newJob().add("which magisk").exec()
    Log.i(TAG, "has magisk: ${result.isSuccess}")
    return result.isSuccess
}

fun isSepolicyValid(rules: String?): Boolean {
    if (rules == null) {
        return true
    }
    val shell = getRootShell()
    val result =
        shell.newJob().add("${getKsuDaemonPath()} sepolicy check '$rules'").to(ArrayList(), null)
            .exec()
    return result.isSuccess
}

fun getSepolicy(pkg: String): String {
    val shell = getRootShell()
    val result =
        shell.newJob().add("${getKsuDaemonPath()} profile get-sepolicy $pkg").to(ArrayList(), null)
            .exec()
    Log.i(TAG, "code: ${result.code}, out: ${result.out}, err: ${result.err}")
    return result.out.joinToString("\n")
}

fun setSepolicy(pkg: String, rules: String): Boolean {
    val shell = getRootShell()
    val result = shell.newJob().add("${getKsuDaemonPath()} profile set-sepolicy $pkg '$rules'")
        .to(ArrayList(), null).exec()
    Log.i(TAG, "set sepolicy result: ${result.code}")
    return result.isSuccess
}

fun listAppProfileTemplates(): List<String> {
    val shell = getRootShell()
    return shell.newJob().add("${getKsuDaemonPath()} profile list-templates").to(ArrayList(), null)
        .exec().out
}

fun getAppProfileTemplate(id: String): String {
    val shell = getRootShell()
    return shell.newJob().add("${getKsuDaemonPath()} profile get-template '${id}'")
        .to(ArrayList(), null).exec().out.joinToString("\n")
}

fun setAppProfileTemplate(id: String, template: String): Boolean {
    val shell = getRootShell()
    val escapedTemplate = template.replace("\"", "\\\"")
    val cmd = """${getKsuDaemonPath()} profile set-template "$id" "$escapedTemplate'""""
    return shell.newJob().add(cmd)
        .to(ArrayList(), null).exec().isSuccess
}

fun deleteAppProfileTemplate(id: String): Boolean {
    val shell = getRootShell()
    return shell.newJob().add("${getKsuDaemonPath()} profile delete-template '${id}'")
        .to(ArrayList(), null).exec().isSuccess
}

fun forceStopApp(packageName: String, userId: Int? = null) {
    val shell = getRootShell()
    val userArg = userId?.let { " --user $it" } ?: ""
    val result = shell.newJob().add("am force-stop$userArg $packageName").exec()
    Log.i(TAG, "force stop $packageName result: $result")
}

fun launchApp(packageName: String, userId: Int? = null) {
    val shell = getRootShell()
    val userArg = userId?.let { " --user $it" } ?: ""
    val result =
        shell.newJob()
            .add("cmd package resolve-activity --brief$userArg $packageName | tail -n 1 | xargs cmd activity start-activity$userArg -n")
            .exec()
    Log.i(TAG, "launch $packageName result: $result")
}

fun restartApp(packageName: String, userId: Int? = null) {
    forceStopApp(packageName, userId)
    launchApp(packageName, userId)
}
