package com.realityse.ravensight

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StrictMode
import com.realityse.ravensight.core.AutoError
import com.realityse.ravensight.core.ExitRecord
import java.io.File
import org.json.JSONObject

/**
 * The process-wide uncaught exception hook. Installed at most once per
 * process and only when a controller first wants capture; after that it
 * stays chained but does nothing unless a [sink] is set. It always calls the
 * handler that was there before, so the app crashes exactly as it would
 * without Ravensight.
 */
internal object CrashHandler {
    /** Records one crash. Called on the crashing thread; must not throw past the handler. */
    @Volatile
    var sink: ((Thread, Throwable) -> Unit)? = null

    private var installed: Hook? = null

    @Synchronized
    fun install() {
        if (installed != null) return
        val hook = Hook(Thread.getDefaultUncaughtExceptionHandler())
        Thread.setDefaultUncaughtExceptionHandler(hook)
        installed = hook
    }

    val isInstalled: Boolean
        @Synchronized get() = installed != null

    /** Test hook: puts the previous handler back when nothing chained after us. */
    @Synchronized
    fun uninstallForTest() {
        val hook = installed ?: return
        if (Thread.getDefaultUncaughtExceptionHandler() === hook) Thread.setDefaultUncaughtExceptionHandler(hook.previous)
        installed = null
        sink = null
    }

    internal class Hook(val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            try {
                sink?.invoke(thread, error)
            } catch (_: Throwable) {
                // A failure in the capture path is dropped silently.
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, error)
                } else {
                    // What ThreadGroup does with no default handler. Calling
                    // ThreadGroup.uncaughtException here would loop back to us.
                    System.err.print("Exception in thread \"" + thread.name + "\" ")
                    error.printStackTrace(System.err)
                }
            }
        }
    }
}

/** This process's name: Application.getProcessName() on API 28+, else /proc/self/cmdline, else the package. */
internal fun currentProcessName(context: Context): String {
    val name = try {
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else null
    } catch (_: Throwable) {
        null
    } ?: try {
        File("/proc/self/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
    } catch (_: Throwable) {
        null
    }
    return name?.trim()?.takeIf { it.isNotEmpty() } ?: context.packageName
}

/**
 * Crashes written synchronously by the dying process and read once by the
 * next launch of the same process. App-private, in noBackupFilesDir (never
 * backed up or transferred), one directory per process name so a service
 * process's crash is never sent as the game's, at most [MAX_FILES] at a
 * time, deleted when read.
 */
internal class CrashStore(
    private val context: Context,
    processName: String = currentProcessName(context),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val root = File(context.noBackupFilesDir ?: context.filesDir, "ravensight_crashes")
    private val dir = File(root, safeName(processName))

    /**
     * Called on the crashing thread. Disk access is allowed for the write,
     * so a debug build with a StrictMode penaltyDeath policy still records
     * the crash; the thread's policy is restored after.
     */
    fun write(error: AutoError) {
        val policy = StrictMode.allowThreadDiskWrites()
        try {
            writeFile(error)
        } finally {
            StrictMode.setThreadPolicy(policy)
        }
    }

    private fun writeFile(error: AutoError) {
        if (!dir.isDirectory && !dir.mkdirs()) return
        if ((dir.list()?.size ?: 0) >= MAX_FILES) return
        val json = JSONObject()
            .put("source", error.source)
            .put("severity", error.severity)
            .put("message", error.message)
            .put("atMs", error.atMs)
        error.stack?.let { json.put("stack", it) }
        error.type?.let { json.put("type", it) }
        error.trail?.let { json.put("trail", it) }
        val name = "crash-${error.atMs}-${System.nanoTime()}"
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(json.toString(), Charsets.UTF_8)
        tmp.renameTo(File(dir, "$name.json"))
    }

    /**
     * Every stored crash of this process, oldest first; the files are
     * deleted whatever they hold. Also prunes other processes' records:
     * a directory for a process the app no longer declares, and any record
     * older than [RETENTION_MS].
     */
    fun takeAll(): List<AutoError> {
        try {
            prune()
        } catch (_: Throwable) {
            // Pruning is best effort.
        }
        val files = dir.listFiles() ?: return emptyList()
        val out = ArrayList<AutoError>()
        for (file in files.sortedBy { it.name }) {
            try {
                if (file.name.endsWith(".json")) {
                    val json = JSONObject(file.readText(Charsets.UTF_8))
                    // Text was scrubbed when written; of() scrubs and cuts again, which changes nothing.
                    out += AutoError.of(
                        source = json.optString("source", "crash"),
                        severity = json.optString("severity", "critical"),
                        message = json.optString("message"),
                        stack = json.optString("stack").takeIf { it.isNotEmpty() },
                        type = json.optString("type").takeIf { it.isNotEmpty() },
                        trail = json.optString("trail").takeIf { it.isNotEmpty() },
                        atMs = json.optLong("atMs"),
                    )
                }
            } catch (_: Throwable) {
                // A torn or foreign file is dropped.
            } finally {
                file.delete()
            }
        }
        return out.sortedBy { it.atMs }
    }

    /** Opt-out or a new identity: every process's records go, not only this one's. */
    fun clear() {
        root.deleteRecursively()
    }

    private fun prune() {
        val declared = declaredProcesses()
        val cutoff = nowMs() - RETENTION_MS
        for (sub in root.listFiles() ?: return) {
            if (!sub.isDirectory || (declared != null && sub.name !in declared)) {
                sub.deleteRecursively()
                continue
            }
            sub.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
            if (sub.list()?.isEmpty() == true) sub.delete()
        }
    }

    /** Directory names of every process the manifest declares, the default one included; null if unreadable. */
    private fun declaredProcesses(): Set<String>? = try {
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        val names = HashSet<String>()
        names += context.packageName
        info.applicationInfo?.processName?.let { names += it }
        info.activities?.forEach { names += it.processName }
        info.services?.forEach { names += it.processName }
        info.receivers?.forEach { names += it.processName }
        info.providers?.forEach { names += it.processName }
        names.filterNotNull().map(::safeName).toSet()
    } catch (_: Throwable) {
        null
    }

    companion object {
        const val MAX_FILES = 5

        /** A crash record not sent within this long is deleted unsent. */
        const val RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000

        fun safeName(processName: String): String = processName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}

/**
 * The platform's record of how earlier processes of this app ended, from
 * ApplicationExitInfo on API 30 and later. Below API 30 it is empty. Only
 * the reason, the time and the short description are read, never a
 * tombstone or a trace.
 */
internal object SystemExitReasons {
    /** Exits of this process only ([processName]), so a service process's exit is never the game's. */
    fun read(context: Context, processName: String = currentProcessName(context)): List<ExitRecord> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        return readApi30(context, processName)
    }

    @androidx.annotation.RequiresApi(30)
    private fun readApi30(context: Context, processName: String): List<ExitRecord> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        val infos: List<ApplicationExitInfo> = am.getHistoricalProcessExitReasons(context.packageName, 0, 0)
        return infos.filter { it.processName == processName }.map { ExitRecord(it.timestamp, it.reason, it.description) }
    }
}
