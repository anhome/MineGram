package dev.mintgram.ide;

import dalvik.system.DexClassLoader;
import desu.mintgram.plugins.jvm.JvmPluginContext;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class OnDeviceCompiler {
    static final class Result {
        final File dexFile;
        final String entrypoint;

        Result(File dexFile, String entrypoint) {
            this.dexFile = dexFile;
            this.entrypoint = entrypoint;
        }
    }

    private static final Pattern PACKAGE = Pattern.compile(
        "(?m)^\\s*package\\s+([a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)*)\\s*;"
    );
    private static final Pattern ENTRY_CLASS = Pattern.compile(
        "(?m)\\bpublic\\s+(?:final\\s+)?class\\s+([a-zA-Z_$][\\w$]*)\\s+" +
        "(?:extends\\s+(?:[\\w$.]+\\.)?JvmPluginAdapter|" +
        "implements\\s+(?:[\\w$.]+\\.)?JvmPlugin)\\b"
    );

    private final JvmPluginContext pluginContext;
    private final File toolchainDirectory;

    OnDeviceCompiler(JvmPluginContext pluginContext) {
        this.pluginContext = pluginContext;
        toolchainDirectory = new File(pluginContext.getDataDirectory(), "toolchain-v1");
    }

    Result compile(ProjectStore project) throws Exception {
        prepareToolchain();
        List<String> sources = project.listJavaFiles();
        if (sources.isEmpty()) {
            throw new IOException("В проекте нет Java-файлов.");
        }

        String entrypoint = findEntrypoint(project, sources);
        File buildRoot = new File(pluginContext.getDataDirectory(), "build");
        recreateDirectory(buildRoot);
        File classes = new File(buildRoot, "classes");
        File dex = new File(buildRoot, "dex");
        if (!classes.mkdirs() || !dex.mkdirs()) {
            throw new IOException("Не удалось создать папку сборки.");
        }

        File compilerDex = new File(toolchainDirectory, "compiler.dex.jar");
        File optimized = new File(toolchainDirectory, "optimized");
        optimized.mkdirs();
        DexClassLoader loader = new DexClassLoader(
            compilerDex.getAbsolutePath(),
            optimized.getAbsolutePath(),
            null,
            getClass().getClassLoader()
        );

        StringWriter diagnostics = new StringWriter();
        String command = buildEcjCommand(project, sources, classes);
        boolean compiled = invokeEcj(loader, command, diagnostics);
        if (!compiled) {
            String message = diagnostics.toString().trim();
            throw new IOException(message.isEmpty() ? "Компилятор Java отклонил исходный код." : message);
        }

        File classJar = new File(buildRoot, "compiled-classes.jar");
        zipClasses(classes, classJar);
        invokeD8(loader, classJar, dex, diagnostics);
        File classesDex = new File(dex, "classes.dex");
        if (!classesDex.isFile() || classesDex.length() == 0) {
            String message = diagnostics.toString().trim();
            throw new IOException(message.isEmpty() ? "D8 не создал classes.dex." : message);
        }
        return new Result(classesDex, entrypoint);
    }

    private void prepareToolchain() throws IOException {
        if (!toolchainDirectory.exists() && !toolchainDirectory.mkdirs()) {
            throw new IOException("Не удалось подготовить инструменты компилятора.");
        }
        extractAsset("compiler/compiler.dex.jar", new File(toolchainDirectory, "compiler.dex.jar"));
        extractAsset("sdk/android.jar", new File(toolchainDirectory, "android.jar"));
        extractAsset("sdk/mintgram-api.jar", new File(toolchainDirectory, "mintgram-api.jar"));
    }

    private void extractAsset(String path, File target) throws IOException {
        if (target.isFile() && target.length() > 0) {
            return;
        }
        pluginContext.copyAssetToFile(path, target);
    }

    private String buildEcjCommand(
        ProjectStore project,
        List<String> sources,
        File output
    ) {
        File androidJar = new File(toolchainDirectory, "android.jar");
        File apiJar = new File(toolchainDirectory, "mintgram-api.jar");
        StringBuilder command = new StringBuilder();
        command.append("-source 8 -target 8 -encoding UTF-8 -proc:none ");
        command.append("-nowarn -d ").append(quote(output)).append(' ');
        command.append("-classpath ").append(quote(
            androidJar.getAbsolutePath() + File.pathSeparator + apiJar.getAbsolutePath()
        ));
        for (String source : sources) {
            command.append(' ').append(quote(new File(project.getRoot(), source)));
        }
        return command.toString();
    }

    private static boolean invokeEcj(
        ClassLoader loader,
        String command,
        StringWriter diagnostics
    ) throws Exception {
        Class<?> main = Class.forName("org.eclipse.jdt.internal.compiler.batch.Main", true, loader);
        PrintWriter writer = new PrintWriter(diagnostics, true);
        try {
            Method compile = main.getMethod(
                "compile", String.class, PrintWriter.class, PrintWriter.class
            );
            return Boolean.TRUE.equals(compile.invoke(null, command, writer, writer));
        } catch (NoSuchMethodException ignored) {
            Method compile = main.getMethod("compile", String.class);
            return Boolean.TRUE.equals(compile.invoke(null, command));
        } catch (InvocationTargetException error) {
            throw unwrap(error);
        }
    }

    private static void invokeD8(
        ClassLoader loader,
        File classJar,
        File output,
        StringWriter diagnostics
    ) throws Exception {
        try {
            Class<?> d8 = Class.forName("com.android.tools.r8.D8", true, loader);
            Method main = d8.getMethod("main", String[].class);
            String[] arguments = {
                "--min-api", "26",
                "--release",
                "--output", output.getAbsolutePath(),
                classJar.getAbsolutePath()
            };
            main.invoke(null, (Object) arguments);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause != null) {
                diagnostics.append(cause.toString());
            }
            throw unwrap(error);
        }
    }

    private static String findEntrypoint(ProjectStore project, List<String> sources)
        throws IOException {
        String found = null;
        for (String path : sources) {
            String source = project.read(path);
            Matcher classMatcher = ENTRY_CLASS.matcher(source);
            if (!classMatcher.find()) {
                continue;
            }
            Matcher packageMatcher = PACKAGE.matcher(source);
            String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
            String candidate = packageName.isEmpty()
                ? classMatcher.group(1)
                : packageName + "." + classMatcher.group(1);
            if (found != null) {
                throw new IOException(
                    "Найдено несколько точек входа. Оставьте один public-класс, " +
                    "наследующий JvmPluginAdapter."
                );
            }
            found = candidate;
        }
        if (found == null) {
            throw new IOException(
                "Не найдена точка входа: public class, наследующий JvmPluginAdapter."
            );
        }
        return found;
    }

    private static void zipClasses(File directory, File output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(output))) {
            addDirectory(zip, directory, directory);
        }
    }

    private static void addDirectory(ZipOutputStream zip, File root, File directory)
        throws IOException {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                addDirectory(zip, root, file);
                continue;
            }
            String name = root.toURI().relativize(file.toURI()).getPath();
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
    }

    private static String quote(File file) {
        return quote(file.getAbsolutePath());
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Exception unwrap(InvocationTargetException error) {
        Throwable cause = error.getCause();
        if (cause instanceof Exception) {
            return (Exception) cause;
        }
        return new Exception(cause != null ? cause : error);
    }

    private static void recreateDirectory(File directory) throws IOException {
        if (directory.exists()) {
            deleteRecursively(directory);
        }
        if (!directory.mkdirs()) {
            throw new IOException("Не удалось очистить папку сборки.");
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            throw new IOException("Не удалось удалить " + file.getName());
        }
    }
}
