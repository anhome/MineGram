package dev.mintgram.ide;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class PluginPackager {
    static final class Metadata {
        String id;
        String name;
        String description;
        String author;
        File image;
    }

    static File create(
        File exportDirectory,
        Metadata metadata,
        OnDeviceCompiler.Result compiled
    ) throws Exception {
        validate(metadata);
        if (!exportDirectory.exists() && !exportDirectory.mkdirs()) {
            throw new IOException("Не удалось создать папку экспорта.");
        }
        File target = new File(exportDirectory, metadata.id + ".plugin");
        File temporary = new File(exportDirectory, metadata.id + ".plugin.tmp");

        JSONObject manifest = new JSONObject();
        manifest.put("formatVersion", 1);
        manifest.put("engine", "jvm");
        manifest.put("language", "java");
        manifest.put("id", metadata.id);
        manifest.put("name", metadata.name.trim());
        manifest.put("entrypoint", compiled.entrypoint);
        manifest.put("version", "1.0.0");
        manifest.put("author", metadata.author.trim());
        manifest.put("description", metadata.description.trim());
        manifest.put("appVersion", ">=1.0");
        manifest.put("sdkVersion", ">=2.1.0");
        if (metadata.image != null) {
            manifest.put("image", "assets/icon.png");
        }

        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(temporary))) {
            addBytes(zip, "plugin.json",
                manifest.toString(2).getBytes(StandardCharsets.UTF_8));
            addFile(zip, "classes.dex", compiled.dexFile);
            if (metadata.image != null && metadata.image.isFile()) {
                addFile(zip, "assets/icon.png", metadata.image);
            }
        }
        if (target.exists() && !target.delete()) {
            temporary.delete();
            throw new IOException("Не удалось заменить предыдущую сборку.");
        }
        if (!temporary.renameTo(target)) {
            throw new IOException("Не удалось сохранить пакет плагина.");
        }
        return target;
    }

    private static void validate(Metadata metadata) throws IOException {
        if (metadata == null
            || metadata.id == null
            || !metadata.id.matches("^[a-zA-Z][a-zA-Z0-9_-]{1,31}$")) {
            throw new IOException(
                "ID должен начинаться с буквы и содержать 2–32 латинских символа, цифры, _ или -."
            );
        }
        if ("mint_ide".equals(metadata.id)) {
            throw new IOException("Этот ID зарезервирован самой IDE.");
        }
        if (metadata.name == null || metadata.name.trim().isEmpty()) {
            throw new IOException("Укажите название плагина.");
        }
        if (metadata.name.trim().length() > 64) {
            throw new IOException("Название слишком длинное.");
        }
        if (metadata.description == null) {
            metadata.description = "";
        }
        if (metadata.author == null || metadata.author.trim().isEmpty()) {
            metadata.author = "Mint IDE";
        }
        if (metadata.image != null && metadata.image.length() > 4L * 1024L * 1024L) {
            throw new IOException("Изображение должно быть меньше 4 МБ.");
        }
    }

    private static void addBytes(ZipOutputStream zip, String name, byte[] data)
        throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static void addFile(ZipOutputStream zip, String name, File file)
        throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                zip.write(buffer, 0, read);
            }
        }
        zip.closeEntry();
    }

    private PluginPackager() {
    }
}
