package dev.mintgram.ide;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ProjectStore {
    static final String DEFAULT_FILE = "src/dev/mintgram/generated/MainPlugin.java";
    private static final String DEFAULT_PROJECT = "Starter";
    private static final int MAX_FILES = 128;
    private static final int MAX_FILE_SIZE = 512 * 1024;

    private final File projectsRoot;
    private final File legacyRoot;
    private File root;

    ProjectStore(File pluginDataDirectory) {
        projectsRoot = new File(pluginDataDirectory, "projects");
        legacyRoot = new File(pluginDataDirectory, "workspace");
        root = new File(projectsRoot, DEFAULT_PROJECT);
    }

    void ensureDefaultProject() throws IOException {
        if (!projectsRoot.exists() && !projectsRoot.mkdirs()) {
            throw new IOException("Не удалось создать хранилище проектов.");
        }
        if (legacyRoot.isDirectory() && listProjects().isEmpty()) {
            File migrated = new File(projectsRoot, DEFAULT_PROJECT);
            if (!legacyRoot.renameTo(migrated)) {
                throw new IOException("Не удалось перенести предыдущий проект.");
            }
            root = migrated;
        }
        List<String> projects = listProjects();
        if (projects.isEmpty()) {
            createProject(DEFAULT_PROJECT);
            return;
        }
        if (!root.isDirectory()) {
            openProject(projects.get(0));
        }
        ensureTemplate();
    }

    void createProject(String name) throws IOException {
        String normalized = normalizeProjectName(name);
        File target = resolveProject(normalized);
        if (target.exists()) {
            throw new IOException("Проект с таким названием уже существует.");
        }
        if (!target.mkdirs()) {
            throw new IOException("Не удалось создать проект.");
        }
        root = target;
        ensureTemplate();
    }

    void openProject(String name) throws IOException {
        File target = resolveProject(normalizeProjectName(name));
        if (!target.isDirectory()) {
            throw new IOException("Проект не найден.");
        }
        root = target;
        ensureTemplate();
    }

    boolean hasProject(String name) {
        try {
            return resolveProject(normalizeProjectName(name)).isDirectory();
        } catch (IOException ignored) {
            return false;
        }
    }

    List<String> listProjects() {
        ArrayList<String> result = new ArrayList<>();
        File[] children = projectsRoot.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && !child.getName().startsWith(".")) {
                    result.add(child.getName());
                }
            }
        }
        Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    String getProjectName() {
        return root.getName();
    }

    String suggestedPluginId() {
        String value = getProjectName().toLowerCase(java.util.Locale.US)
            .replaceAll("[^a-z0-9_]+", "_")
            .replaceAll("^_+|_+$", "");
        if (value.length() < 2 || !Character.isLetter(value.charAt(0))) {
            value = "my_plugin";
        }
        if (value.length() > 32) {
            value = value.substring(0, 32);
        }
        return value;
    }

    private void ensureTemplate() throws IOException {
        File main = resolve(DEFAULT_FILE);
        if (!main.exists()) {
            write(DEFAULT_FILE, defaultTemplate());
        }
    }

    File getRoot() {
        return root;
    }

    List<String> listJavaFiles() {
        ArrayList<String> result = new ArrayList<>();
        collect(root, result);
        Collections.sort(result);
        return result;
    }

    private void collect(File directory, List<String> result) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (result.size() >= MAX_FILES) {
                return;
            }
            if (child.isDirectory()) {
                collect(child, result);
            } else if (child.getName().endsWith(".java")) {
                result.add(relative(child));
            }
        }
    }

    String read(String relativePath) throws IOException {
        File file = resolve(relativePath);
        if (!file.isFile() || file.length() > MAX_FILE_SIZE) {
            throw new IOException("Файл не найден или слишком большой.");
        }
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > MAX_FILE_SIZE) {
                    throw new IOException("Файл слишком большой.");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    void write(String relativePath, String content) throws IOException {
        if (content == null || content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_SIZE) {
            throw new IOException("Файл слишком большой.");
        }
        File file = resolve(relativePath);
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("Не удалось создать папку.");
        }
        File temporary = new File(parent, file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (file.exists() && !file.delete()) {
            temporary.delete();
            throw new IOException("Не удалось заменить файл.");
        }
        if (!temporary.renameTo(file)) {
            throw new IOException("Не удалось сохранить файл.");
        }
    }

    void create(String relativePath) throws IOException {
        String normalized = normalize(relativePath);
        if (!normalized.endsWith(".java")) {
            throw new IOException("Имя файла должно оканчиваться на .java");
        }
        File file = resolve(normalized);
        if (file.exists()) {
            throw new IOException("Такой файл уже существует.");
        }
        String className = file.getName().substring(0, file.getName().length() - 5);
        String parent = normalized.contains("/") ?
            normalized.substring(0, normalized.lastIndexOf('/')) : "";
        String packageName = parent.startsWith("src/") ?
            parent.substring(4).replace('/', '.') : "dev.mintgram.generated";
        write(normalized,
            "package " + packageName + ";\n\n" +
            "public class " + className + " {\n" +
            "}\n"
        );
    }

    void delete(String relativePath) throws IOException {
        if (DEFAULT_FILE.equals(normalize(relativePath))) {
            throw new IOException("Главный файл проекта удалить нельзя.");
        }
        File file = resolve(relativePath);
        if (!file.isFile() || !file.delete()) {
            throw new IOException("Не удалось удалить файл.");
        }
    }

    String rename(String relativePath, String newRelativePath) throws IOException {
        String oldPath = normalize(relativePath);
        String newPath = normalize(newRelativePath);
        if (!newPath.endsWith(".java")) {
            throw new IOException("Имя файла должно оканчиваться на .java");
        }
        File source = resolve(oldPath);
        File target = resolve(newPath);
        if (!source.isFile()) {
            throw new IOException("Исходный файл не найден.");
        }
        if (target.exists()) {
            throw new IOException("Файл с таким именем уже существует.");
        }
        File parent = target.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("Не удалось создать папку.");
        }
        if (!source.renameTo(target)) {
            throw new IOException("Не удалось переименовать файл.");
        }
        return newPath;
    }

    private File resolve(String relativePath) throws IOException {
        String normalized = normalize(relativePath);
        File file = new File(root, normalized);
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (!filePath.startsWith(rootPath + File.separator)) {
            throw new IOException("Недопустимый путь.");
        }
        return file;
    }

    private File resolveProject(String name) throws IOException {
        File file = new File(projectsRoot, name);
        String parentPath = projectsRoot.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (!filePath.startsWith(parentPath + File.separator)) {
            throw new IOException("Недопустимое название проекта.");
        }
        return file;
    }

    private String relative(File file) {
        return root.toURI().relativize(file.toURI()).getPath();
    }

    private static String normalize(String path) throws IOException {
        if (path == null) {
            throw new IOException("Путь не указан.");
        }
        String value = path.trim().replace('\\', '/');
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.isEmpty() || value.contains("..") || value.contains("//")) {
            throw new IOException("Недопустимый путь.");
        }
        return value;
    }

    private static String normalizeProjectName(String name) throws IOException {
        if (name == null) {
            throw new IOException("Название проекта не указано.");
        }
        String value = name.trim();
        if (value.isEmpty() || value.length() > 48 || value.equals(".")
            || value.equals("..") || value.contains("/") || value.contains("\\")
            || value.indexOf('\0') >= 0) {
            throw new IOException("Название должно содержать 1–48 символов без / и \\.");
        }
        return value;
    }

    private static String defaultTemplate() {
        return "package dev.mintgram.generated;\n\n" +
            "import android.widget.Toast;\n\n" +
            "import java.util.Arrays;\n" +
            "import java.util.List;\n\n" +
            "import desu.mintgram.plugins.jvm.JvmPluginAdapter;\n" +
            "import desu.mintgram.plugins.jvm.JvmPluginContext;\n" +
            "import desu.mintgram.plugins.jvm.JvmSettings;\n" +
            "import desu.mintgram.plugins.models.SettingItem;\n\n" +
            "/** Готовый стартовый плагин для Mintgram SDK 2.1. */\n" +
            "public final class MainPlugin extends JvmPluginAdapter {\n" +
            "    @Override\n" +
            "    public void onLoad(JvmPluginContext context) {\n" +
            "        super.onLoad(context);\n" +
            "        context.log(\"Плагин успешно запущен\");\n" +
            "    }\n\n" +
            "    @Override\n" +
            "    public List<SettingItem> createSettings() {\n" +
            "        return Arrays.asList(\n" +
            "            JvmSettings.header(\"Мой первый плагин\"),\n" +
            "            JvmSettings.action(\n" +
            "                \"Показать приветствие\",\n" +
            "                \"Проверка готового проекта\",\n" +
            "                \"msg_emoji_smile\",\n" +
            "                this::showGreeting\n" +
            "            ),\n" +
            "            JvmSettings.divider(\"Проект готов к сборке и установке.\")\n" +
            "        );\n" +
            "    }\n\n" +
            "    private void showGreeting() {\n" +
            "        pluginContext.runOnUiThread(() ->\n" +
            "            Toast.makeText(\n" +
            "                pluginContext.getApplicationContext(),\n" +
            "                \"Привет из нового плагина!\",\n" +
            "                Toast.LENGTH_LONG\n" +
            "            ).show()\n" +
            "        );\n" +
            "    }\n" +
            "}\n";
    }
}
