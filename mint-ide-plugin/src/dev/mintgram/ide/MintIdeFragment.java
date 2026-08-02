package dev.mintgram.ide;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import desu.mintgram.plugins.Plugin;
import desu.mintgram.plugins.PluginsController;
import desu.mintgram.plugins.jvm.JvmPluginContext;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

public final class MintIdeFragment extends BaseFragment {
    private static final int MENU_FILES = 1;
    private static final int MENU_NEW_FILE = 2;
    private static final int PICK_ICON = 3301;

    private final JvmPluginContext pluginContext;
    private ProjectStore project;
    private CodeEditorView editor;
    private TextView fileLabel;
    private TextView projectLabel;
    private String currentFile = ProjectStore.DEFAULT_FILE;
    private String currentSource = "";
    private boolean dirty;
    private OnDeviceCompiler.Result pendingCompilation;
    private AlertDialog metadataDialog;
    private ImageView iconPreview;
    private File selectedIcon;

    public MintIdeFragment(JvmPluginContext pluginContext) {
        this.pluginContext = pluginContext;
    }

    @Override
    public boolean onFragmentCreate() {
        project = new ProjectStore(pluginContext.getDataDirectory());
        try {
            project.ensureDefaultProject();
            String savedProject = pluginContext.getString(
                "active_project",
                project.getProjectName()
            );
            if (project.hasProject(savedProject)) {
                project.openProject(savedProject);
            }
            currentSource = project.read(currentFile);
        } catch (IOException error) {
            currentSource = "";
            pluginContext.log(error);
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Mint IDE");
        actionBar.setSubtitle("Java · автосохранение");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    saveCurrentFile();
                    finishFragment();
                } else if (id == MENU_FILES) {
                    showFiles();
                } else if (id == MENU_NEW_FILE) {
                    showNewFileDialog();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        View newFileItem = menu.addItemWithWidth(
            MENU_NEW_FILE, R.drawable.msg_addfolder, AndroidUtilities.dp(48)
        );
        newFileItem.setContentDescription("Новый Java-файл");
        View filesItem = menu.addItemWithWidth(
            MENU_FILES, R.drawable.msg_folders, AndroidUtilities.dp(48)
        );
        filesItem.setContentDescription("Файлы проекта");

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = root;

        LinearLayout projectBar = new LinearLayout(context);
        projectBar.setGravity(Gravity.CENTER_VERTICAL);
        projectBar.setPadding(
            AndroidUtilities.dp(14), AndroidUtilities.dp(8),
            AndroidUtilities.dp(10), AndroidUtilities.dp(8)
        );
        projectBar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        root.addView(projectBar, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, 64, Gravity.TOP
        ));

        LinearLayout projectInfo = new LinearLayout(context);
        projectInfo.setOrientation(LinearLayout.VERTICAL);
        projectInfo.setGravity(Gravity.CENTER_VERTICAL);
        projectInfo.setOnClickListener(v -> showProjects());
        projectBar.addView(projectInfo, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
        ));

        TextView projectHint = new TextView(context);
        projectHint.setText("ТЕКУЩИЙ ПРОЕКТ");
        projectHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        projectHint.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        projectHint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        projectInfo.addView(projectHint);

        projectLabel = new TextView(context);
        projectLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        projectLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        projectLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        projectLabel.setSingleLine(true);
        updateProjectLabel();
        projectInfo.addView(projectLabel);

        Button newProject = new Button(context);
        newProject.setText("Новый проект");
        newProject.setAllCaps(false);
        newProject.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueButton));
        newProject.setOnClickListener(v -> showNewProjectDialog());
        projectBar.addView(newProject, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ));

        editor = new CodeEditorView(context);
        editor.setSource(currentSource);
        editor.setChangeListener(source -> {
            currentSource = source;
            dirty = true;
        });
        root.addView(editor, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
            Gravity.TOP | Gravity.START, 0, 64, 0, 58
        ));

        LinearLayout footer = new LinearLayout(context);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(6),
            AndroidUtilities.dp(8), AndroidUtilities.dp(6));
        footer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        root.addView(footer, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, 58, Gravity.BOTTOM
        ));

        fileLabel = new TextView(context);
        fileLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        fileLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        fileLabel.setTypeface(Typeface.MONOSPACE);
        fileLabel.setSingleLine(true);
        updateFileLabel();
        footer.addView(fileLabel, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ));

        Button build = new Button(context);
        build.setText("Создать плагин");
        build.setAllCaps(false);
        build.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueButton));
        build.setOnClickListener(v -> compileProject());
        footer.addView(build, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT
        ));
        return fragmentView;
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        saveCurrentFile();
        return super.onBackPressed(invoked);
    }

    @Override
    public void onPause() {
        saveCurrentFile();
        super.onPause();
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_ICON || resultCode != Activity.RESULT_OK
            || data == null || data.getData() == null) {
            return;
        }
        try {
            selectedIcon = copySelectedIcon(data.getData());
            if (iconPreview != null) {
                iconPreview.setImageBitmap(BitmapFactory.decodeFile(selectedIcon.getAbsolutePath()));
            }
        } catch (Throwable error) {
            showError("Не удалось прочитать изображение", error);
        }
    }

    private void showFiles() {
        saveCurrentFile();
        List<String> files = project.listJavaFiles();
        if (files.isEmpty()) {
            showNewFileDialog();
            return;
        }
        String[] labels = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            labels[i] = (files.get(i).equals(currentFile) ? "✓  " : "    ") + files.get(i);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Файлы проекта");
        builder.setItems(labels, (dialog, which) -> openFile(files.get(which)));
        builder.setPositiveButton("Новый файл", (dialog, which) -> showNewFileDialog());
        builder.setNeutralButton("Управление", (dialog, which) -> showFileManager());
        builder.setNegativeButton("Закрыть", null);
        showDialog(builder.create());
    }

    private void showProjects() {
        saveCurrentFile();
        List<String> projects = project.listProjects();
        String[] labels = new String[projects.size()];
        for (int i = 0; i < projects.size(); i++) {
            labels[i] = (projects.get(i).equals(project.getProjectName()) ? "✓  " : "    ")
                + projects.get(i);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Проекты");
        builder.setItems(labels, (dialog, which) -> switchProject(projects.get(which)));
        builder.setPositiveButton("Создать новый", (dialog, which) -> showNewProjectDialog());
        builder.setNegativeButton("Закрыть", null);
        showDialog(builder.create());
    }

    private void showNewProjectDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        EditText input = createInput(context, "Название проекта", false);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Создать новый проект");
        builder.setMessage(
            "IDE создаст готовый MainPlugin.java с примером настройки, действия и уведомления."
        );
        builder.setView(wrap(context, input));
        builder.setPositiveButton("Создать", (dialog, which) -> {
            try {
                saveCurrentFile();
                project.createProject(input.getText().toString());
                activateCurrentProject();
            } catch (Throwable error) {
                showError("Проект не создан", error);
            }
        });
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
    }

    private void switchProject(String name) {
        try {
            saveCurrentFile();
            project.openProject(name);
            activateCurrentProject();
        } catch (Throwable error) {
            showError("Проект не открыт", error);
        }
    }

    private void activateCurrentProject() throws IOException {
        currentFile = ProjectStore.DEFAULT_FILE;
        currentSource = project.read(currentFile);
        dirty = false;
        pluginContext.setSetting("active_project", project.getProjectName());
        if (editor != null) {
            editor.setSource(currentSource);
        }
        updateProjectLabel();
        updateFileLabel();
    }

    private void showFileManager() {
        saveCurrentFile();
        List<String> files = project.listJavaFiles();
        if (files.isEmpty()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Выберите файл");
        builder.setItems(files.toArray(new String[0]), (dialog, which) ->
            showFileActions(files.get(which))
        );
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
    }

    private void showFileActions(String path) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(path);
        builder.setItems(new String[]{"Переименовать", "Удалить"}, (dialog, which) -> {
            if (which == 0) {
                showRenameFileDialog(path);
            } else {
                confirmDeleteFile(path);
            }
        });
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
    }

    private void showRenameFileDialog(String path) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        EditText input = createInput(context, "Новый путь", false);
        input.setText(path);
        input.setSelection(input.length());
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Переименовать файл");
        builder.setView(wrap(context, input));
        builder.setPositiveButton("Переименовать", (dialog, which) -> {
            try {
                String renamed = project.rename(path, input.getText().toString());
                if (path.equals(currentFile)) {
                    currentFile = renamed;
                    updateFileLabel();
                }
            } catch (Throwable error) {
                showError("Файл не переименован", error);
            }
        });
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
    }

    private void confirmDeleteFile(String path) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Удалить файл?");
        builder.setMessage(path);
        builder.setPositiveButton("Удалить", (dialog, which) -> {
            try {
                project.delete(path);
                if (path.equals(currentFile)) {
                    List<String> remaining = project.listJavaFiles();
                    if (!remaining.isEmpty()) {
                        openFile(remaining.get(0));
                    }
                }
            } catch (Throwable error) {
                showError("Файл не удалён", error);
            }
        });
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
    }

    private void showNewFileDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        EditText input = createInput(context, "src/dev/mintgram/generated/Helper.java", false);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Новый Java-файл");
        builder.setMessage("Укажите путь внутри проекта");
        builder.setView(wrap(context, input));
        builder.setPositiveButton("Создать", (dialog, which) -> {
            try {
                String path = input.getText().toString().trim();
                project.create(path);
                openFile(path);
            } catch (Throwable error) {
                showError("Файл не создан", error);
            }
        });
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
    }

    private void openFile(String path) {
        saveCurrentFile();
        try {
            currentFile = path;
            currentSource = project.read(path);
            dirty = false;
            if (editor != null) {
                editor.setSource(currentSource);
            }
            updateFileLabel();
        } catch (Throwable error) {
            showError("Не удалось открыть файл", error);
        }
    }

    private void saveCurrentFile() {
        if (!dirty || project == null || currentFile == null) {
            return;
        }
        try {
            project.write(currentFile, currentSource);
            dirty = false;
            updateFileLabel();
        } catch (Throwable error) {
            showError("Не удалось сохранить файл", error);
        }
    }

    private void updateFileLabel() {
        if (fileLabel != null) {
            fileLabel.setText((dirty ? "● " : "") + currentFile);
        }
    }

    private void updateProjectLabel() {
        if (projectLabel != null && project != null) {
            projectLabel.setText(project.getProjectName() + "  ▾");
        }
    }

    private void compileProject() {
        saveCurrentFile();
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog progress = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.setMessage("Проверяем и компилируем Java-код…");
        showDialog(progress);

        pluginContext.runOnPluginThread(() -> {
            try {
                OnDeviceCompiler.Result result = new OnDeviceCompiler(pluginContext).compile(project);
                pluginContext.runOnUiThread(() -> {
                    progress.dismiss();
                    pendingCompilation = result;
                    showMetadataDialog();
                });
            } catch (Throwable error) {
                pluginContext.runOnUiThread(() -> {
                    progress.dismiss();
                    showError("Плагин невозможно создать", error);
                });
            }
        });
    }

    private void showMetadataDialog() {
        Context context = getParentActivity();
        if (context == null || pendingCompilation == null) {
            return;
        }
        selectedIcon = null;
        LinearLayout fields = new LinearLayout(context);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(4),
            AndroidUtilities.dp(20), AndroidUtilities.dp(8));

        EditText name = createInput(context, "Название плагина", false);
        EditText id = createInput(context, "ID: my_plugin", false);
        EditText author = createInput(context, "Автор", false);
        EditText description = createInput(context, "Описание", true);
        name.setText(project.getProjectName());
        id.setText(project.suggestedPluginId());
        author.setText("Mint IDE");
        description.setText("Плагин создан в Mint IDE на основе готового проекта.");
        fields.addView(name);
        fields.addView(id);
        fields.addView(author);
        fields.addView(description);

        LinearLayout imageRow = new LinearLayout(context);
        imageRow.setGravity(Gravity.CENTER_VERTICAL);
        iconPreview = new ImageView(context);
        iconPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iconPreview.setImageResource(R.drawable.msg_plugins);
        imageRow.addView(iconPreview, new LinearLayout.LayoutParams(
            AndroidUtilities.dp(64), AndroidUtilities.dp(64)
        ));
        Button chooseImage = new Button(context);
        chooseImage.setText("Выбрать картинку");
        chooseImage.setAllCaps(false);
        chooseImage.setOnClickListener(v -> pickIcon());
        imageRow.addView(chooseImage, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        fields.addView(imageRow);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(fields);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Оформление плагина");
        builder.setView(scroll);
        builder.setPositiveButton("Собрать и установить", null);
        builder.setNegativeButton("Отмена", null);
        metadataDialog = builder.create();
        metadataDialog.setOnShowListener(dialog -> metadataDialog.getButton(
            AlertDialog.BUTTON_POSITIVE
        ).setOnClickListener(v -> {
            PluginPackager.Metadata metadata = new PluginPackager.Metadata();
            metadata.name = name.getText().toString();
            metadata.id = id.getText().toString().trim().toLowerCase(Locale.US);
            metadata.author = author.getText().toString();
            metadata.description = description.getText().toString();
            metadata.image = selectedIcon;
            packageAndInstall(metadata);
        }));
        showDialog(metadataDialog);
    }

    private void packageAndInstall(PluginPackager.Metadata metadata) {
        if (metadataDialog != null) {
            metadataDialog.dismiss();
        }
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog progress = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.setMessage("Собираем пакет и устанавливаем плагин…");
        showDialog(progress);
        pluginContext.runOnPluginThread(() -> {
            try {
                File exports = new File(pluginContext.getDataDirectory(), "exports");
                File pluginFile = PluginPackager.create(exports, metadata, pendingCompilation);
                PluginsController.PluginsEngine engine = PluginsController.engines.get("jvm");
                if (engine == null) {
                    throw new IOException("JVM-движок недоступен.");
                }
                engine.installPluginFromFile(pluginFile.getAbsolutePath(), null, error -> {
                    if (error != null) {
                        pluginContext.runOnUiThread(() -> {
                            progress.dismiss();
                            showError("Плагин не установлен", new IOException(error));
                        });
                        return;
                    }
                    Plugin installed = PluginsController.getInstance().plugins.get(metadata.id);
                    if (installed != null && installed.isEnabled()) {
                        pluginContext.runOnUiThread(() -> {
                            progress.dismiss();
                            showSuccess(metadata.id);
                        });
                        return;
                    }
                    PluginsController.getInstance().setPluginEnabled(metadata.id, true, enableError ->
                        pluginContext.runOnUiThread(() -> {
                            progress.dismiss();
                            if (enableError == null) {
                                showSuccess(metadata.id);
                            } else {
                                showError("Плагин собран, но не запущен",
                                    new IOException(enableError));
                            }
                        })
                    );
                });
            } catch (Throwable error) {
                pluginContext.runOnUiThread(() -> {
                    progress.dismiss();
                    showError("Плагин невозможно создать", error);
                });
            }
        });
    }

    private void showSuccess(String pluginId) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(AndroidUtilities.dp(20), 0,
            AndroidUtilities.dp(20), AndroidUtilities.dp(8));

        RLottieImageView duck = new RLottieImageView(context);
        duck.setAnimation(R.raw.utyan_passcode, 170, 170);
        duck.setScaleType(ImageView.ScaleType.CENTER);
        duck.playAnimation();
        content.addView(duck, new LinearLayout.LayoutParams(
            AndroidUtilities.dp(180), AndroidUtilities.dp(180)
        ));
        TextView message = new TextView(context);
        message.setText("Вы успешно создали плагин");
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        message.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        message.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        message.setGravity(Gravity.CENTER);
        content.addView(message, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setView(content);
        builder.setPositiveButton("Поделиться", (dialog, which) -> {
            PluginsController.PluginsEngine engine = PluginsController.engines.get("jvm");
            if (engine != null) {
                engine.sharePlugin(pluginId);
            }
        });
        builder.setNegativeButton("Закрыть", null);
        showDialog(builder.create());
    }

    private void pickIcon() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_ICON);
    }

    private File copySelectedIcon(Uri uri) throws IOException {
        Context context = getParentActivity();
        if (context == null) {
            throw new IOException("Экран уже закрыт.");
        }
        File file = new File(pluginContext.getDataDirectory(), "selected-icon.png");
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IOException("Файл недоступен.");
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (bytes.size() + read > 4 * 1024 * 1024) {
                    throw new IOException("Изображение должно быть меньше 4 МБ.");
                }
                bytes.write(buffer, 0, read);
            }
            try (FileOutputStream output = new FileOutputStream(file)) {
                bytes.writeTo(output);
            }
        }
        if (BitmapFactory.decodeFile(file.getAbsolutePath()) == null) {
            file.delete();
            throw new IOException("Выбранный файл не является изображением.");
        }
        return file;
    }

    private void showError(String title, Throwable error) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        String message = error != null && error.getMessage() != null
            ? error.getMessage() : String.valueOf(error);
        if (message.length() > 7000) {
            message = message.substring(0, 7000) + "\n…";
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setPositiveButton("Понятно", null);
        showDialog(builder.create());
    }

    private static EditText createInput(Context context, String hint, boolean multiline) {
        EditText input = new EditText(context);
        input.setHint(hint);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setSingleLine(!multiline);
        input.setInputType(InputType.TYPE_CLASS_TEXT
            | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        input.setMinLines(multiline ? 3 : 1);
        return input;
    }

    private static FrameLayout wrap(Context context, View view) {
        FrameLayout wrapper = new FrameLayout(context);
        wrapper.addView(view, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
            Gravity.TOP | Gravity.START, 24, 4, 24, 0
        ));
        return wrapper;
    }
}
