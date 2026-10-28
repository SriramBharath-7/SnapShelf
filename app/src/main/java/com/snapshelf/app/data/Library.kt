package com.snapshelf.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.UUID

@Entity(tableName = "subjects")
data class Subject(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "folders",
    foreignKeys = [ForeignKey(entity = Subject::class, parentColumns = ["id"], childColumns = ["subjectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("subjectId")]
)
data class Folder(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val subjectId: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "images",
    foreignKeys = [ForeignKey(entity = Folder::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("folderId")]
)
data class LibraryImage(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val folderId: String,
    val path: String,
    val thumbnailPath: String?,
    val displayName: String,
    val orderIndex: Int,
    val importedAt: Long = System.currentTimeMillis(),
    val readingPosition: Float = 0f,
    val isProtected: Boolean = false
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM subjects ORDER BY createdAt DESC") fun subjects(): Flow<List<Subject>>
    @Query("SELECT * FROM subjects WHERE id = :id") suspend fun subject(id: String): Subject?
    @Query("SELECT * FROM folders WHERE subjectId = :subjectId ORDER BY createdAt") fun folders(subjectId: String): Flow<List<Folder>>
    @Query("SELECT * FROM folders WHERE id = :id") suspend fun folder(id: String): Folder?
    @Query("SELECT * FROM images WHERE folderId = :folderId ORDER BY orderIndex") fun images(folderId: String): Flow<List<LibraryImage>>
    @Query("SELECT * FROM images WHERE id = :id") suspend fun image(id: String): LibraryImage?
    @Query("SELECT * FROM images WHERE isProtected = 1 ORDER BY importedAt DESC") fun protectedImages(): Flow<List<LibraryImage>>
    @Query("SELECT * FROM images") suspend fun allImages(): List<LibraryImage>
    @Query("SELECT * FROM images WHERE folderId = :folderId ORDER BY orderIndex") suspend fun imageList(folderId: String): List<LibraryImage>
    @Insert suspend fun insertSubject(subject: Subject)
    @Insert suspend fun insertFolder(folder: Folder)
    @Insert suspend fun insertImages(images: List<LibraryImage>)
    @Query("UPDATE subjects SET name = :name WHERE id = :id") suspend fun renameSubject(id: String, name: String)
    @Query("UPDATE folders SET name = :name WHERE id = :id") suspend fun renameFolder(id: String, name: String)
    @Query("UPDATE images SET displayName = :name WHERE id = :id") suspend fun renameImage(id: String, name: String)
    @Query("DELETE FROM subjects WHERE id = :id") suspend fun deleteSubject(id: String)
    @Query("DELETE FROM folders WHERE id = :id") suspend fun deleteFolder(id: String)
    @Query("DELETE FROM images WHERE id = :id") suspend fun deleteImage(id: String)
    @Query("UPDATE images SET readingPosition = :position WHERE id = :id") suspend fun updatePosition(id: String, position: Float)
    @Query("UPDATE images SET orderIndex = :orderIndex WHERE id = :id") suspend fun updateOrder(id: String, orderIndex: Int)
    @Query("UPDATE images SET isProtected = :isProtected, path = :path WHERE id = :id") suspend fun updateProtection(id: String, isProtected: Boolean, path: String)
    @Query("UPDATE images SET thumbnailPath = :thumbnailPath WHERE id = :id") suspend fun updateThumbnail(id: String, thumbnailPath: String)
    @Query("UPDATE images SET isProtected = :isProtected, path = :path, thumbnailPath = :thumbnailPath WHERE id = :id") suspend fun updateProtectionPaths(id: String, isProtected: Boolean, path: String, thumbnailPath: String?)
}

@Database(entities = [Subject::class, Folder::class, LibraryImage::class], version = 3, exportSchema = false)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao
    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, LibraryDatabase::class.java, "snapshelf.db")
            .addMigrations(object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE images ADD COLUMN isProtected INTEGER NOT NULL DEFAULT 0")
                }
            }, object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE images ADD COLUMN thumbnailPath TEXT")
                }
            }).build()
    }
}

class LibraryRepository(private val context: Context, private val dao: LibraryDao) {
    val subjects = dao.subjects()
    fun folders(id: String) = dao.folders(id)
    fun images(id: String) = dao.images(id)
    val protectedImages = dao.protectedImages()
    suspend fun image(id: String) = dao.image(id)
    suspend fun subject(id: String) = dao.subject(id)
    suspend fun folder(id: String) = dao.folder(id)
    suspend fun collectionFor(id: String): List<LibraryImage> {
        val current = dao.image(id) ?: return emptyList()
        return dao.imageList(current.folderId)
    }
    suspend fun createSubject(name: String) = dao.insertSubject(Subject(name = name.trim()))
    suspend fun createFolder(subjectId: String, name: String) = dao.insertFolder(Folder(subjectId = subjectId, name = name.trim()))
    suspend fun renameSubject(subject: Subject, name: String) = dao.renameSubject(subject.id, name.trim())
    suspend fun renameFolder(folder: Folder, name: String) = dao.renameFolder(folder.id, name.trim())
    suspend fun renameImage(image: LibraryImage, name: String) = dao.renameImage(image.id, name.trim())
    suspend fun deleteSubject(subject: Subject) {
        val folderIds = dao.folders(subject.id).first()
        val imagesByFolder = folderIds.associateWith { folder -> dao.imageList(folder.id) }
        dao.deleteSubject(subject.id)
        folderIds.forEach { folder ->
            imagesByFolder[folder].orEmpty().forEach { image -> deleteFiles(image) }
            File(context.filesDir, "library/${folder.id}").deleteRecursively()
        }
    }
    suspend fun deleteFolder(folder: Folder) {
        dao.imageList(folder.id).forEach { image -> deleteFiles(image) }
        dao.deleteFolder(folder.id)
        File(context.filesDir, "library/${folder.id}").deleteRecursively()
    }
    suspend fun deleteImage(image: LibraryImage) { dao.deleteImage(image.id); deleteFiles(image) }
    suspend fun updatePosition(id: String, position: Float) = dao.updatePosition(id, position.coerceIn(0f, 1f))
    suspend fun reorder(folderId: String, orderedIds: List<String>) {
        orderedIds.forEachIndexed { index, imageId -> dao.updateOrder(imageId, index) }
    }
    suspend fun protect(image: LibraryImage): LibraryImage {
        if (image.isProtected) return image
        val source = File(image.path)
        val encrypted = VaultManager(File(context.filesDir, "vault")).encrypt(source, image.id)
        val encryptedThumbnail = image.thumbnailPath?.let { VaultManager(File(context.filesDir, "vault")).encrypt(File(it), "${image.id}_thumb") }
        return try {
            dao.updateProtectionPaths(image.id, true, encrypted.absolutePath, encryptedThumbnail?.absolutePath)
            if (!source.delete() && source.exists()) {
                dao.updateProtectionPaths(image.id, false, source.absolutePath, image.thumbnailPath)
                encrypted.delete()
                encryptedThumbnail?.delete()
                error("Could not remove the unencrypted source")
            }
            image.thumbnailPath?.let { thumbnail -> if (!File(thumbnail).delete() && File(thumbnail).exists()) error("Could not remove the unencrypted thumbnail") }
            image.copy(isProtected = true, path = encrypted.absolutePath)
        } catch (error: Exception) {
            encrypted.delete()
            encryptedThumbnail?.delete()
            throw IllegalStateException("Could not register protected screenshot", error)
        }
    }
    fun decryptForViewing(image: LibraryImage): String {
        val target = File(context.cacheDir, "vault-view/${image.id}.png")
        VaultManager(File(context.filesDir, "vault")).decrypt(File(image.path), target)
        return target.absolutePath
    }

    suspend fun importImages(folder: Folder, uris: List<Uri>) {
        val subjectName = dao.subject(folder.subjectId)?.name ?: folder.subjectId
        val destination = File(
            context.filesDir,
            "library/subjects/${storageSegment(subjectName)}_${folder.subjectId}/units/${storageSegment(folder.name)}_${folder.id}/images"
        ).apply { mkdirs() }
        val existingCount = dao.imageList(folder.id).size
        val pending = mutableListOf<LibraryImage>()
        try {
            uris.distinct().forEachIndexed { index, uri ->
                val name = displayName(uri) ?: "Screenshot_${index + 1}.png"
                val target = File(destination, "${UUID.randomUUID()}_${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
                context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use(input::copyTo) }
                    ?: error("Unable to read $name")
                try {
                    val thumbnail = createThumbnail(target, destination)
                    pending += LibraryImage(folderId = folder.id, path = target.absolutePath, thumbnailPath = thumbnail.absolutePath, displayName = name, orderIndex = existingCount + index)
                } catch (error: Exception) {
                    target.delete()
                    throw error
                }
            }
        } catch (error: Exception) {
            pending.forEach { deleteFiles(it) }
            throw IllegalStateException("Could not import selected screenshots", error)
        }
        try {
            if (pending.isNotEmpty()) dao.insertImages(pending)
        } catch (error: Exception) {
            pending.forEach { deleteFiles(it) }
            throw IllegalStateException("Could not save imported screenshots", error)
        }
    }
    suspend fun reconcileStorage() {
        dao.allImages().forEach { image ->
            if (!File(image.path).exists()) {
                dao.deleteImage(image.id)
                return@forEach
            }
            if (!image.isProtected && image.thumbnailPath == null) {
                runCatching { dao.updateThumbnail(image.id, createThumbnail(File(image.path), File(context.filesDir, "library/${image.folderId}")).absolutePath) }
            }
        }
    }

    private fun deleteFiles(image: LibraryImage) {
        File(image.path).delete()
        image.thumbnailPath?.let { File(it).delete() }
    }

    private fun displayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
    }

    private fun storageSegment(value: String): String =
        value.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "unnamed" }

    private fun createThumbnail(source: File, directory: File): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unable to decode ${source.name}" }
        var sample = 1
        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath, options) ?: error("Unable to create thumbnail for ${source.name}")
        return try {
            File(directory.apply { mkdirs() }, "${source.nameWithoutExtension}_thumb.webp").also { thumbnail ->
                try {
                    thumbnail.outputStream().use { output -> require(bitmap.compress(Bitmap.CompressFormat.WEBP, 82, output)) }
                } catch (error: Exception) {
                    thumbnail.delete()
                    throw error
                }
            }
        } finally {
            bitmap.recycle()
        }
    }
}
