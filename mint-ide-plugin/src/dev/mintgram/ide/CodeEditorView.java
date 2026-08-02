package dev.mintgram.ide;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CodeEditorView extends EditTextBoldCursor {
    interface ChangeListener {
        void onChanged(String text);
    }

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
        "(//[^\\n]*|/\\*[\\s\\S]*?\\*/)|(\"(?:\\\\.|[^\"\\\\])*\")|" +
        "\\b(abstract|boolean|break|byte|case|catch|char|class|const|continue|default|" +
        "do|double|else|enum|extends|final|finally|float|for|if|implements|import|" +
        "instanceof|int|interface|long|native|new|null|package|private|protected|" +
        "public|return|short|static|strictfp|super|switch|synchronized|this|throw|" +
        "throws|transient|true|false|try|void|volatile|while)\\b"
    );

    private final Paint lineNumberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable highlightRunnable = this::applyHighlighting;
    private ChangeListener changeListener;
    private boolean applyingSpans;

    CodeEditorView(Context context) {
        super(context);
        setTypeface(Typeface.MONOSPACE);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        setGravity(Gravity.TOP | Gravity.START);
        setSingleLine(false);
        setHorizontallyScrolling(true);
        setInputType(EditorInfo.TYPE_CLASS_TEXT
            | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
            | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        setPadding(AndroidUtilities.dp(52), AndroidUtilities.dp(12),
            AndroidUtilities.dp(16), AndroidUtilities.dp(24));
        lineNumberPaint.setTypeface(Typeface.MONOSPACE);
        lineNumberPaint.setTextSize(AndroidUtilities.dp(12));
        lineNumberPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));

        addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!applyingSpans && changeListener != null) {
                    changeListener.onChanged(s.toString());
                }
                handler.removeCallbacks(highlightRunnable);
                handler.postDelayed(highlightRunnable, 120);
                invalidate();
            }

            @Override public void afterTextChanged(Editable s) {
            }
        });
    }

    void setChangeListener(ChangeListener listener) {
        changeListener = listener;
    }

    void setSource(String source) {
        applyingSpans = true;
        setText(source == null ? "" : source);
        setSelection(length());
        applyingSpans = false;
        applyHighlighting();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        android.text.Layout layout = getLayout();
        if (layout != null) {
            int first = layout.getLineForVertical(getScrollY());
            int last = layout.getLineForVertical(getScrollY() + getHeight());
            for (int line = first; line <= last; line++) {
                float baseline = getPaddingTop() + layout.getLineBaseline(line);
                canvas.drawText(String.valueOf(line + 1), AndroidUtilities.dp(8), baseline,
                    lineNumberPaint);
            }
            Paint divider = new Paint();
            divider.setColor(Theme.getColor(Theme.key_divider));
            canvas.drawRect(AndroidUtilities.dp(43), getScrollY(),
                AndroidUtilities.dp(44), getScrollY() + getHeight(), divider);
        }
        super.onDraw(canvas);
    }

    private void applyHighlighting() {
        Editable editable = getText();
        if (editable == null || editable.length() > 300_000) {
            return;
        }
        applyingSpans = true;
        ForegroundColorSpan[] old = editable.getSpans(0, editable.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan span : old) {
            editable.removeSpan(span);
        }
        Matcher matcher = TOKEN_PATTERN.matcher(editable);
        while (matcher.find()) {
            int color;
            if (matcher.group(1) != null) {
                color = 0xff6a9955;
            } else if (matcher.group(2) != null) {
                color = 0xffce9178;
            } else {
                color = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
            }
            editable.setSpan(new ForegroundColorSpan(color), matcher.start(), matcher.end(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        applyingSpans = false;
    }
}
