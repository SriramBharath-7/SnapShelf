package com.snapshelf.app.data

import android.content.Context
import android.net.Uri
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    @Query("UPDATE images SET isProtected = :protected, path = :path WHERE id = :id") suspend fun updateProtection(id: String, protected: Boolean, path: String)
}

@Database(entities = [Subject::class, Folder::class, LibraryImage::class], version = 2, exportSchema = false)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao
    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, LibraryDatabase::class.java, "snapshelf.db")
            .addMigrations(object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE images ADD COLUMN isProtected INTEGER NOT NULL DEFAULT 0")
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
        dao.deleteSubject(subject.id)
        folderIds.forEach { File(context.filesDir, "library/${it.id}").deleteRecursively() }
    }
    suspend fun deleteFolder(folder: Folder) { dao.deleteFolder(folder.id); File(context.filesDir, "library/${folder.id}").deleteRecursively() }
    suspend fun deleteImage(image: LibraryImage) { dao.deleteImage(image.id); File(image.path).delete() }
    suspend fun updatePosition(id: String, position: Float) = dao.updatePosition(id, position.coerceIn(0f, 1f))
    suspend fun reorder(folderId: String, orderedIds: List<String>) {
        orderedIds.forEachIndexed { index, imageId -> dao.updateOrder(imageId, index) }
    }
    suspend fun protect(image: LibraryImage): LibraryImage {
        if (image.isProtected) return image
        val source = File(image.path)
        val encrypted = VaultManager(File(context.filesDir, "vault")).encrypt(source, image.id)
        return try {
            dao.updateProtection(image.id, true, encrypted.absolutePath)
            if (!source.delete() && source.exists()) {
                dao.updateProtection(image.id, false, source.absolutePath)
                encrypted.delete()
                error("Could not remove the unencrypted source")
            }
            image.copy(isProtected = true, path = encrypted.absolutePath)
        } catch (error: Exception) {
            encrypted.delete()
            throw IllegalStateException("Could not register protected screenshot", error)
        }
    }
    fun decryptForViewing(image: LibraryImage): String {
        val target = File(context.cacheDir, "vault-view/${image.id}.png")
        VaultManager(File(context.filesDir, "vault")).decrypt(File(image.path), target)
        return target.absolutePath
    }

    suspend fun importImages(folder: Folder, uris: List<Uri>) {
        val destination = File(context.filesDir, "library/${folder.id}").apply { mkdirs() }
        val existingCount = dao.imageList(folder.id).size
        val pending = mutableListOf<LibraryImage>()
        uris.distinct().forEachIndexed { index, uri ->
            val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Screenshot_${index + 1}.png"
            val target = File(destination, "${UUID.randomUUID()}_${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use(input::copyTo) }
                    ?: error("Unable to read $name")
                pending += LibraryImage(folderId = folder.id, path = target.absolutePath, displayName = name, orderIndex = existingCount + index)
            } catch (error: Exception) {
                target.delete()
                throw IllegalStateException("Could not import $name", error)
            }
        }
        if (pending.isNotEmpty()) dao.insertImages(pending)
    }
    suspend fun reconcileStorage() {
        dao.allImages().filter { !File(it.path).exists() }.forEach { dao.deleteImage(it.id) }
    }
}
