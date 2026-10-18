package im.mrgreen.learning

import android.content.Context
import io.requery.android.database.sqlite.SQLiteDatabase
import java.io.File
import java.security.MessageDigest

/** Local database backups only. Models, preferences, documents and app binaries are not restored. */
object DatabaseSnapshots {
    private const val MAX_BYTES=500L*1024*1024
    fun available(context:Context):List<File> = File(context.filesDir,"learning-snapshots").listFiles().orEmpty().filter{it.isFile && it.extension=="db"}.sortedByDescending{it.lastModified()}.take(20)
    private fun digest(file:File):String {
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes=ByteArray(65536);while(true){val n=input.read(bytes);if(n<0)break;hash.update(bytes,0,n)} }
        return hash.digest().joinToString(""){"%02x".format(it)}
    }
    private fun validate(file:File) {
        require(file.isFile && file.length() in 16..MAX_BYTES){"Snapshot size is invalid"}
        val header=file.inputStream().use { val b=ByteArray(16);require(it.read(b)==16);b }
        require(header.contentEquals("SQLite format 3\u0000".toByteArray())){"Not a SQLite snapshot"}
        val db=SQLiteDatabase.openDatabase(file.absolutePath,null,SQLiteDatabase.OPEN_READONLY)
        try {
            require(db.rawQuery("PRAGMA quick_check",null).use {it.moveToFirst() && it.getString(0)=="ok"}){"Snapshot integrity check failed"}
            require(db.rawQuery("PRAGMA user_version",null).use {it.moveToFirst() && it.getInt(0)==2}){"Unsupported snapshot schema version"}
            require(db.rawQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN ('identity','messages','facts','doctor_changes')",null).use {it.moveToFirst() && it.getInt(0)==4}){"Snapshot is missing required app tables"}
        } finally {db.close()}
    }
    /** Called only after the in-app destructive-data confirmation. Does not modify the live DB. */
    @Synchronized fun stageAfterApproval(context:Context,file:File,approved:Boolean) {
        require(approved){"Explicit restore approval required"}
        require(file.canonicalFile.parentFile==File(context.filesDir,"learning-snapshots").canonicalFile){"Snapshot must be app-owned"}
        validate(file)
        val temp=File(context.filesDir,"database-restore.tmp")
        file.copyTo(temp,overwrite=true);validate(temp)
        val pending=File(context.filesDir,"database-restore.pending")
        require(temp.renameTo(pending)){"Could not stage snapshot atomically"}
        context.getSharedPreferences("database_restore",0).edit().putString("sha256",digest(pending)).commit()
    }
    /** Application calls this before opening GreenStore. Retains the previous DB + WAL as a recovery copy. */
    fun applyPending(context:Context) {
        val pending=File(context.filesDir,"database-restore.pending");if(!pending.isFile)return
        val prefs=context.getSharedPreferences("database_restore",0)
        val target=context.getDatabasePath("mr-green.db")
        try {
            require(digest(pending)==prefs.getString("sha256",null)){"Staged snapshot digest changed"}
            validate(pending)
            val backup=File(context.filesDir,"database-restore-backups/${System.currentTimeMillis()}").apply{mkdirs()}
            val originals=listOf(target,File(target.path+"-wal"),File(target.path+"-shm"))
            val copied=mutableListOf<Pair<File,File>>()
            try {
                originals.filter{it.exists()}.forEach { original -> val saved=File(backup,original.name);require(original.renameTo(saved)){"Could not preserve previous DB"};copied+=original to saved }
                target.parentFile?.mkdirs();require(pending.renameTo(target)){"Could not activate restored DB"}
            } catch(e:Exception) {copied.asReversed().forEach{(original,saved)->saved.renameTo(original)};throw e}
            prefs.edit().remove("sha256").putString("last_result","Database restored; previous DB retained at ${backup.name}").commit()
        } catch(_:Exception) {
            // Keep the current DB; never repeatedly retry a bad pending snapshot on each startup.
            pending.renameTo(File(context.filesDir,"database-restore.rejected"))
            prefs.edit().remove("sha256").putString("last_result","Snapshot restore rejected; previous database retained").commit()
        }
    }
}
