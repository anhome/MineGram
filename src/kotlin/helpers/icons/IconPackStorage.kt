package desu.mintgram.helpers.icons

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import desu.mintgram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * On-disk storage for user-created [CustomIconPack]s — everything lives under
 * `filesDir/icon_packs/<packId>/`: a `pack.json` (id/name/overridden names) plus one PNG per
 * overridden icon, named after the drawable's resource name. No backend, no import/export yet —
 * see EXTERAGRAM_PORT_STATUS.md for what's intentionally not done.
 */
object IconPackStorage {
    /** Master storage size — comfortably above any real on-screen icon size (even 48dp icons at
     * xxxhdpi land under 150px), so [getOverrideDrawable] always downscales rather than upscales. */
    private const val MASTER_SIZE_PX = 192

    private val rootDir: File
        get() = File(ApplicationLoader.applicationContext.filesDir, "icon_packs").apply { mkdirs() }

    private fun packDir(id: String) = File(rootDir, id)
    private fun packJsonFile(id: String) = File(packDir(id), "pack.json")
    private fun iconFile(id: String, drawableName: String) = File(packDir(id), "$drawableName.png")

    private val drawableCache = HashMap<String, Drawable>()

    fun listPacks(): List<CustomIconPack> {
        val dir = rootDir
        val dirs = dir.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { loadPack(it.name) }.sortedBy { it.name.lowercase() }
    }

    fun getPack(id: String): CustomIconPack? = loadPack(id)

    private fun loadPack(id: String): CustomIconPack? {
        val file = packJsonFile(id)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val names = mutableSetOf<String>()
            val arr = json.optJSONArray("icons") ?: JSONArray()
            for (i in 0 until arr.length()) names.add(arr.getString(i))
            CustomIconPack(id, json.optString("name", id), names)
        } catch (e: Exception) {
            FileLog.e(e)
            null
        }
    }

    private fun savePack(pack: CustomIconPack) {
        packDir(pack.id).mkdirs()
        val json = JSONObject()
        json.put("id", pack.id)
        json.put("name", pack.name)
        json.put("icons", JSONArray(pack.overriddenNames.toList()))
        packJsonFile(pack.id).writeText(json.toString())
    }

    fun createPack(name: String): CustomIconPack {
        val pack = CustomIconPack(UUID.randomUUID().toString(), name)
        savePack(pack)
        return pack
    }

    fun renamePack(id: String, newName: String) {
        val pack = loadPack(id) ?: return
        pack.name = newName
        savePack(pack)
    }

    fun deletePack(id: String) {
        packDir(id).deleteRecursively()
        drawableCache.keys.removeAll { it.startsWith("$id/") }
        if (InuConfig.ACTIVE_CUSTOM_ICON_PACK_ID.value == id) {
            InuConfig.ACTIVE_CUSTOM_ICON_PACK_ID.value = ""
            InuConfig.ICON_REPLACEMENT.value = InuConfig.IconReplacementItem.OFF
        }
    }

    /** [bitmap] is cropped to a square and downscaled to [MASTER_SIZE_PX] before saving — no
     * interactive cropper. The actual on-screen size is decided later, per usage, by
     * [getOverrideDrawable] (matching the original icon's intrinsic size). */
    fun setOverride(packId: String, drawableName: String, bitmap: Bitmap) {
        val pack = loadPack(packId) ?: return
        val squared = centerCropSquare(bitmap)
        val scaled = if (squared.width <= MASTER_SIZE_PX && squared.height <= MASTER_SIZE_PX) squared
            else Bitmap.createScaledBitmap(squared, MASTER_SIZE_PX, MASTER_SIZE_PX, true)
        try {
            FileOutputStream(iconFile(packId, drawableName)).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            FileLog.e(e)
            return
        } finally {
            if (scaled !== squared) squared.recycle()
            if (scaled !== bitmap) scaled.recycle()
        }
        pack.overriddenNames.add(drawableName)
        savePack(pack)
        invalidateCache(packId, drawableName)
    }

    fun removeOverride(packId: String, drawableName: String) {
        val pack = loadPack(packId) ?: return
        pack.overriddenNames.remove(drawableName)
        savePack(pack)
        iconFile(packId, drawableName).delete()
        invalidateCache(packId, drawableName)
    }

    private fun invalidateCache(packId: String, drawableName: String) {
        val prefix = "$packId/$drawableName/"
        drawableCache.keys.removeAll { it == "$packId/$drawableName" || it.startsWith(prefix) }
    }

    /**
     * Returns the override scaled to [targetWidth]x[targetHeight] px with [density] stamped onto
     * the bitmap, mirroring exteraGram's `IconManager.createBitmapFromFile`: it scales the
     * replacement to the *original* icon's intrinsic size and matches its density, so
     * `BitmapDrawable`'s own dp-scaling math doesn't distort it a second time at draw time.
     * Defaults (no target given) fall back to a plain 24dp icon at the device's current density —
     * good enough for callers that don't know/care about the original drawable's exact size.
     */
    fun getOverrideDrawable(
        packId: String,
        drawableName: String,
        targetWidth: Int = AndroidUtilities.dp(24f),
        targetHeight: Int = targetWidth,
        density: Int = ApplicationLoader.applicationContext.resources.displayMetrics.densityDpi,
    ): Drawable? {
        val cacheKey = "$packId/$drawableName/$targetWidth/$targetHeight"
        drawableCache[cacheKey]?.let { return it }
        val file = iconFile(packId, drawableName)
        if (!file.exists()) return null
        val raw = try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            FileLog.e(e)
            null
        } ?: return null
        val scaled = if (raw.width == targetWidth && raw.height == targetHeight) raw
            else Bitmap.createScaledBitmap(raw, targetWidth, targetHeight, true).also { if (it !== raw) raw.recycle() }
        scaled.density = density
        val drawable = BitmapDrawable(ApplicationLoader.applicationContext.resources, scaled)
        drawableCache[cacheKey] = drawable
        return drawable
    }

    fun hasOverride(packId: String, drawableName: String): Boolean = iconFile(packId, drawableName).exists()

    private fun centerCropSquare(bitmap: Bitmap): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        val x = (bitmap.width - size) / 2
        val y = (bitmap.height - size) / 2
        return if (size == bitmap.width && size == bitmap.height) bitmap else Bitmap.createBitmap(bitmap, x, y, size, size)
    }

    /** Curated set of app UI icons that make sense to replace — reuses [SolarIconPack]'s already-vetted
     * key list rather than enumerating every `R.drawable.*` (which is mostly non-icon art assets) —
     * plus, for imported packs (see [importPackFromZip]), whatever names *they* brought with them,
     * even if outside that curated set, so they're still visible/editable in the picker. */
    fun replaceableIconNames(): List<String> {
        val resources = ApplicationLoader.applicationContext.resources
        val curated = SolarIconPack.originalIds().mapNotNull { id ->
            try {
                resources.getResourceEntryName(id)
            } catch (e: Exception) {
                null
            }
        }
        val imported = listPacks().flatMap { it.overriddenNames }
        return (curated + imported).distinct().sorted()
    }

    fun resolveDrawableId(name: String): Int {
        val resources = ApplicationLoader.applicationContext.resources
        return resources.getIdentifier(name, "drawable", ApplicationLoader.applicationContext.packageName)
    }

    /** Cheap read of just `metadata.json`'s `packName`/icon count, without touching any image
     * entries — used to show a confirm prompt before actually importing (see
     * [desu.mintgram.helpers.icons.IconPackImportHelper]), since a full [importPackFromZip] would
     * already commit the pack to disk. */
    fun peekPackInfo(inputStream: InputStream): Pair<String, Int>? {
        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && (entry.name == "metadata.json" || entry.name.endsWith("/metadata.json"))) {
                    val json = try {
                        JSONObject(String(zis.readBytes(), Charsets.UTF_8))
                    } catch (e: Exception) {
                        FileLog.e(e)
                        return null
                    }
                    val name = json.optString("packName").ifEmpty { json.optString("packId", "Imported pack") }
                    val count = json.optJSONObject("icons")?.length() ?: 0
                    return name to count
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return null
    }

    /**
     * Imports an AyuGram/exteraGram-style `.icon`/`.icons` pack — a plain zip with a `metadata.json`
     * (`packName`, `icons: {resourceName: relativeImagePath}`) plus the referenced images. Same
     * container format across that whole fork lineage (AyuGram4A is an exteraGram fork), so this
     * covers both without needing pack-specific handling. Each image is decoded and re-saved through
     * [setOverride] like any manually-picked replacement — no separate storage path for imports.
     */
    fun importPackFromZip(inputStream: InputStream): CustomIconPack? {
        var metadataEntryDir = ""
        var metadataJson: String? = null
        val imageBytes = HashMap<String, ByteArray>()

        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val bytes = zis.readBytes()
                    if (entry.name == "metadata.json" || entry.name.endsWith("/metadata.json")) {
                        metadataJson = String(bytes, Charsets.UTF_8)
                        val slash = entry.name.lastIndexOf('/')
                        metadataEntryDir = if (slash >= 0) entry.name.substring(0, slash + 1) else ""
                    } else {
                        imageBytes[entry.name] = bytes
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val json = metadataJson?.let { JSONObject(it) } ?: return null
        val packName = json.optString("packName").ifEmpty { json.optString("packId", "Imported pack") }
        val iconsObj = json.optJSONObject("icons") ?: return null

        val pack = createPack(packName)
        val keys = iconsObj.keys()
        var importedAny = false
        while (keys.hasNext()) {
            val resourceName = keys.next()
            val relPath = iconsObj.optString(resourceName, "")
            if (relPath.isEmpty()) continue
            val normalized = relPath.removePrefix("./")
            val bytes = imageBytes[metadataEntryDir + normalized] ?: imageBytes[normalized] ?: continue
            try {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                setOverride(pack.id, resourceName, bitmap)
                bitmap.recycle()
                importedAny = true
            } catch (e: Exception) {
                FileLog.e(e)
            }
        }
        if (!importedAny) {
            deletePack(pack.id)
            return null
        }
        return getPack(pack.id)
    }
}
