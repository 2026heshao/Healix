package com.healix.app.db

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 迁移前的 DB 文件快照（`docs/v8/调研-数据继承方案.md` §3.2 第 1 条）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么需要
 * ══════════════════════════════════════════════════════════════════════════
 * 本应用的策略是「迁移失败宁可崩溃，也不静默删库」（见 [AppDatabase]）。
 * 这条策略只有在**崩了以后还有退路**时才成立 —— 否则用户在真机上遇到
 * 一个写法有误的 `Migration`，结果就是「App 打不开 + 数据全灭」，
 * 而数据只有手机本地一份副本，没有任何云端可回滚。
 *
 * 于是：每次 Room 版本升级**真正要发生之前**，先把数据库文件整份复制一份。
 * 快照是给**开发者/救援**用的最后一道保险，不是给用户点的功能
 * （普通用户无 root 取不出这个文件，这也是调研里否决「DB 快照当主方案」的原因 ——
 * 它只配做内部安全网；用户侧的迁移路径是应用内 JSON 导出/导入）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 三个刻意的实现选择
 * ══════════════════════════════════════════════════════════════════════════
 * 1. **调用点是 [AppDatabase.build]，不是 `HealixApp.onCreate`。**
 *    调研文档按"启动阶段"描述，但真正的约束是「必须在 Room 打开库之前」。
 *    放在 [AppDatabase.build] 里，这条约束由结构保证 —— 无论谁先碰数据库
 *    （Activity / Service / 桌面小工具 / 通知栏速记），都一定经过这里；
 *    而挂在 Application.onCreate 上，只是在"当前调用图"里碰巧成立。
 *
 * 2. **user_version 直接读 SQLite 文件头（offset 60），不用 `PRAGMA`。**
 *    `PRAGMA user_version` 需要真正打开数据库：WAL 需要恢复时可能触发写盘，
 *    只读打开会抛 `SQLiteCantOpenDatabaseException`。而这段代码跑在
 *    Application 启动路径上，**任何异常都不能让 App 起不来**。
 *    文件头是 SQLite 格式里最稳定的部分（格式 1 起未变）：前 16 字节
 *    `"SQLite format 3\0"`，其后 offset 60 处是 4 字节大端 user_version。
 *    代价：若 -wal 里有更新的 user_version 而主文件尚未 checkpoint，会读到旧值 →
 *    最多是**多存一份无害的快照**，不会漏存。
 *
 * 3. **全程吞异常。** 快照失败（磁盘满、权限异常）不能让启动崩溃 ——
 *    它是加固措施，不是关键路径。失败只记日志。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 落点与保留策略
 * ══════════════════════════════════════════════════════════════════════════
 * `filesDir/db_snapshot/<13 位毫秒时间戳>-v<旧>-to-v<新>/`，每个目录含
 * `healix.db` / `healix.db-wal` / `healix.db-shm`（存在才拷）与一份
 * `README.txt`（写清如何人工恢复）。只保留最近 [KEEP] 份，其余的删掉。
 *
 * ⚠️ 快照里是健康数据的明文副本 —— 因此它落在 `filesDir` 下，
 * 而 `res/xml/data_extraction_rules.xml` 已把 `domain="file"` 整域排除，
 * 配合 `allowBackup=false`，云备份与设备迁移都不会把它带走（与库文件同口径）。
 */
internal object DbSnapshot {

    private const val TAG = "DbSnapshot"

    /** 快照根目录名（在 `filesDir` 下）。 */
    private const val DIR_NAME = "db_snapshot"

    /** 保留份数：1 份怕赶上坏的那次，多了白占空间。 */
    private const val KEEP = 2

    /** SQLite 文件头里 user_version 的偏移（4 字节，大端）。 */
    private const val USER_VERSION_OFFSET = 60L

    /** 文件头魔数（含结尾的 NUL，共 16 字节）。 */
    private const val MAGIC = "SQLite format 3\u0000"

    /** user_version 之前 16 字节魔数 + 其它头部字段，至少要有 64 字节才读得到。 */
    private const val MIN_HEADER_BYTES = 64L

    /** 需要一起备份的伴生文件后缀（WAL 模式）。 */
    private val WAL_SUFFIXES = arrayOf("", "-wal", "-shm")

    /**
     * 若本次打开会触发**真实的版本变化**，先把库文件整份备份。
     *
     * 必须由 [AppDatabase.build] 在 `Room.databaseBuilder(...).build()` **之前**调用。
     *
     * @param targetVersion 代码里声明的 `@Database(version = ...)`
     */
    fun beforeOpen(context: Context, targetVersion: Int) {
        try {
            snapshotIfNeeded(context, targetVersion)
        } catch (t: Throwable) {
            // 静默降级：快照是加固，不是关键路径。磁盘满 / 权限异常都不该让 App 起不来。
            Log.e(TAG, "预迁移快照失败（忽略，不阻断启动）：${t.javaClass.simpleName}")
        }
    }

    private fun snapshotIfNeeded(context: Context, targetVersion: Int) {
        val dbFile = context.getDatabasePath(AppDatabase.DB_NAME)
        // 全新安装：还没有库文件。没有"升级前的样子"可留，Room 会直接建到目标版本。
        if (!dbFile.exists()) return

        val onDisk = readUserVersion(dbFile) ?: return
        // onDisk == 0：文件在（比如上次建库中途被杀），但没有有效版本 —— 交给 Room 处理。
        if (onDisk <= 0 || onDisk == targetVersion) return

        val root = File(context.filesDir, DIR_NAME)
        // 13 位毫秒时间戳定长补零 → 目录名的字典序 == 时间序，裁剪时不必解析名字。
        val stamp = System.currentTimeMillis().toString().padStart(13, '0')
        val dest = File(root, "$stamp-v$onDisk-to-v$targetVersion")
        if (!dest.isDirectory && !dest.mkdirs()) return

        // 主库 + WAL + SHM。-wal 里可能有尚未落盘的事务，只拷主文件会得到一个
        // 少一截数据的快照（WAL 模式下这不是可选项）。
        for (suffix in WAL_SUFFIXES) {
            val src = File(dbFile.path + suffix)
            if (!src.isFile) continue
            src.copyTo(File(dest, src.name), overwrite = true)
        }

        File(dest, "README.txt").writeText(readme(onDisk, targetVersion), Charsets.UTF_8)

        // 裁剪：保留最近 KEEP 份。目录名有序 → 直接按名字排序丢掉前面多余的。
        val all = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: return
        for (old in all.dropLast(KEEP)) {
            runCatching { old.deleteRecursively() }
        }
    }

    /**
     * 读 SQLite 文件头里的 user_version（大端 4 字节，offset 60）。
     * 读不到（文件太短 / 魔数不符 / IO 失败）返回 null —— 由调用方决定降级。
     */
    private fun readUserVersion(dbFile: File): Int? = runCatching {
        RandomAccessFile(dbFile, "r").use { raf ->
            if (raf.length() < MIN_HEADER_BYTES) return@use null
            val header = ByteArray(MAGIC.length)
            raf.readFully(header)
            if (String(header, Charsets.US_ASCII) != MAGIC) return@use null
            raf.seek(USER_VERSION_OFFSET)
            val raw = ByteArray(4)
            raf.readFully(raw)
            ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).int
        }
    }.getOrNull()

    private fun readme(from: Int, to: Int): String = """
        Healix 数据库预迁移快照
        ========================
        disk user_version : v$from
        app  user_version : v$to
        触发原因          : 本次启动会从 v$from 迁移到 v$to，迁移前自动留档

        目录内容：healix.db / healix.db-wal / healix.db-shm（存在才拷）。
        恢复方法：把这三个文件一起放回 App 私有目录 databases/
        （/data/data/com.healix.app/databases/），**并删除同目录下已有的
        healix.db 三个同名文件**，再启动 App。三个文件必须成组替换，
        只放主文件会丢掉 WAL 里尚未落盘的事务。

        ⚠️ 含健康数据明文。仅作迁移失败时的最后一道保险，
        正常路径请用「我的 → 导入数据」（应用内 JSON 迁移包）。
    """.trimIndent()
}
