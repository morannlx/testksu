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
private const val AVB_PATCH_DIR = "/data/local/tmp"
private const val AVB_WORK_DIR = "/data/local/tmp/ksu_avb"
private const val PYTHON_WORK_DIR = "/data/local/tmp/ksu_python"

private fun extractAssetDir(context: Context, assetDir: String, targetDir: File) {
    val assetManager = context.assets
    val files = assetManager.list(assetDir) ?: return
    for (name in files) {
        val subAssets = assetManager.list("$assetDir/$name")
        if (subAssets != null && subAssets.isNotEmpty()) {
            // It's a directory
            val subDir = File(targetDir, name)
            subDir.mkdirs()
            extractAssetDir(context, "$assetDir/$name", subDir)
        } else {
            // It's a file
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

    // Extract Python environment if not already present
    if (!pythonDir.exists() || !File(pythonDir, "python").exists()) {
        pythonDir.mkdirs()
        try {
            extractAssetDir(context, PYTHON_ASSETS_DIR, pythonDir)
        } catch (e: Exception) {
            Log.e("KsuCli", "Failed to extract Python environment", e)
            return null
        }
    }

    val pythonBin = File(pythonDir, "python")
    if (!pythonBin.exists()) return null

    // Set execute permissions via root shell
    withNewRootShell {
        newJob().add("chmod 755 ${pythonBin.absolutePath}").exec()
        // Also chmod all .so files
        newJob().add("chmod 755 ${pythonDir}/*.so* 2>/dev/null").exec()
    }

    return pythonBin.absolutePath
}

private fun copyAvbAssetsToWorkDir(context: Context): File {
    val workDir = File(AVB_WORK_DIR)
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

private fun parseAvbInfo(infoOutput: String): Pair<String, String>? {
    val algorithm = Regex("Algorithm:\\s+(\\S+)").find(infoOutput)?.groupValues?.get(1) ?: return null
    val imageSize = Regex("Image size:\\s+(\\d+)\\s+bytes").find(infoOutput)?.groupValues?.get(1) ?: return null
    return Pair(algorithm, imageSize)
}

private fun signWithAvbtool(
    context: Context,
    patchedImage: File,
    partition: String,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): Boolean {
    onStdout("[lenovo] Starting AVB signing for $partition...")

    val pythonBin = setupBundledPython(context)
    if (pythonBin == null) {
        onStderr("[lenovo] ERROR: Failed to setup bundled Python environment")
        return false
    }
    onStdout("[lenovo] Using bundled Python: $pythonBin")

    val workDir = copyAvbAssetsToWorkDir(context)
    val avbtool = File(workDir, "avbtool.py")

    // Build environment prefix: set LD_LIBRARY_PATH and PYTHONHOME for the bundled Python
    val pythonDir = File(PYTHON_WORK_DIR)
    val envPrefix = "export LD_LIBRARY_PATH=${pythonDir}:\$LD_LIBRARY_PATH && " +
        "export PYTHONHOME=${pythonDir} && " +
        "export PYTHONPATH=${pythonDir}/lib/python3.12 && "

    // Step 1: Get image info
    onStdout("[lenovo] Step 1: Parsing AVB image info...")
    val infoResult = flashWithIO(
        "${envPrefix}${pythonBin} ${avbtool.absolutePath} info_image --image ${patchedImage.absolutePath}",
        onStdout, onStderr
    )
    if (!infoResult.isSuccess) {
        onStderr("[lenovo] ERROR: Failed to parse AVB image info")
        return false
    }

    val infoOutput = infoResult.out.joinToString("\n")
    val (algorithm, imageSize) = parseAvbInfo(infoOutput)
        ?: run {
            onStderr("[lenovo] ERROR: Could not parse algorithm or image size from AVB info")
            return false
        }
    onStdout("[lenovo] Detected algorithm: $algorithm, image size: $imageSize bytes")

    // Step 2: Erase old AVB footer
    onStdout("[lenovo] Step 2: Erasing old AVB footer...")
    val eraseResult = flashWithIO(
        "${envPrefix}${pythonBin} ${avbtool.absolutePath} erase_footer --image ${patchedImage.absolutePath}",
        onStdout, onStderr
    )
    if (!eraseResult.isSuccess) {
        onStderr("[lenovo] WARNING: Erase footer failed (may be no footer), continuing...")
    }

    // Step 3: Select key based on algorithm
    val keyFile = when {
        algorithm.contains("RSA4096") -> File(workDir, "testkey_rsa4096.pem")
        algorithm.contains("RSA2048") -> File(workDir, "testkey_rsa2048.pem")
        else -> {
            onStderr("[lenovo] ERROR: Unsupported algorithm: $algorithm")
            return false
        }
    }
    onStdout("[lenovo] Using key: ${keyFile.name}")

    // Step 4: Add new hash footer
    onStdout("[lenovo] Step 3: Adding AVB hash footer...")
    val signResult = flashWithIO(
        "${envPrefix}${pythonBin} ${avbtool.absolutePath} add_hash_footer " +
            "--image ${patchedImage.absolutePath} " +
            "--partition_name $partition " +
            "--partition_size $imageSize " +
            "--algorithm $algorithm " +
            "--key ${keyFile.absolutePath} " +
            "--rollback_index 0",
        onStdout, onStderr
    )
    if (!signResult.isSuccess) {
        onStderr("[lenovo] ERROR: Failed to add AVB hash footer")
        return false
    }

    onStdout("[lenovo] AVB signing completed successfully!")
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
        cmd += " -o $AVB_PATCH_DIR --out-name lenovo_patched_boot.img"
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

    // Lenovo mode: sign the patched image and flash manually
    if (lenovoMode && result.isSuccess) {
        val targetPartition = partition ?: "boot"
        val signedOk = if (bootFile != null) {
            // File mode: sign the output in Downloads
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val patchedImage = downloadsDir.listFiles()?.filter {
                it.name.startsWith("lenovo_patched_") && it.name.endsWith(".img")
            }?.maxByOrNull { it.lastModified() }

            if (patchedImage != null) {
                signWithAvbtool(ksuApp, patchedImage, targetPartition, onStdout, onStderr)
            } else {
                onStderr("[lenovo] ERROR: Could not find patched image in Downloads")
                false
            }
        } else {
            // Direct install: sign the temp output, then dd flash
            val patchDir = File(AVB_PATCH_DIR)
            val patchedImage = File(patchDir, "lenovo_patched_boot.img")
            if (patchedImage.exists()) {
                val signed = signWithAvbtool(ksuApp, patchedImage, targetPartition, onStdout, onStderr)
                if (signed) {
                    // Flash the signed image with dd
                    val slotSuffix = withNewRootShell {
                        val cmd = if (ota) "boot-info slot-suffix --ota" else "boot-info slot-suffix"
                        val out = newJob().add("${getKsuDaemonPath()} $cmd").to(ArrayList(), null).exec().out
                        out.filter { it.isNotBlank() }.joinToString("").trim()
                    }
                    val bootDevice = "/dev/block/by-name/${targetPartition}${slotSuffix}"
                    onStdout("[lenovo] Flashing signed image to $bootDevice...")
                    val ddResult = flashWithIO(
                        "dd if=${patchedImage.absolutePath} of=$bootDevice",
                        onStdout, onStderr
                    )
                    if (ddResult.isSuccess) {
                        onStdout("[lenovo] Flash completed successfully!")
                        if (ota) {
                            onStdout("[lenovo] OTA mode: device will switch to new slot on next reboot")
                        }
                    } else {
                        onStderr("[lenovo] ERROR: dd flash failed")
                    }
                    ddResult.isSuccess
                } else {
                    false
                }
            } else {
                onStderr("[lenovo] ERROR: Patched image not found at ${patchedImage.absolutePath}")
                false
            }
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
