package app.exteraless.sessionimport;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LoginActivity;

/**
 * Шит импорта сессии при входе: Telethon StringSession / .session / tdata (zip).
 */
public class SessionImportSheet extends BottomSheet implements SessionImport.Listener {

    private static SessionImportSheet instance;
    private final LoginActivity loginActivity;
    private final Theme.ResourcesProvider rp;
    private final LinearLayout content;
    private final EditText stringInput;
    private final EditText passcodeInput;
    private final TextView statusView;
    private final TextView importButton;
    private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF chipRect = new RectF();

    private String sessionFilePath;
    private String tdataFilePath;
    private boolean busy;

    public static void open(LoginActivity activity) {
        if (instance != null) {
            try {
                instance.dismiss();
            } catch (Exception ignored) {
            }
        }
        SessionImportSheet sheet = new SessionImportSheet(
                activity.getParentActivity(), activity, activity.getResourceProvider());
        instance = sheet;
        sheet.show();
    }

    public static boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        SessionImportSheet sheet = instance;
        return sheet != null && sheet.onResult(requestCode, resultCode, data);
    }

    @SuppressLint("InflateParams")
    private SessionImportSheet(Context context, LoginActivity loginActivity,
                               Theme.ResourcesProvider resourcesProvider) {
        super(context, true, resourcesProvider);
        this.loginActivity = loginActivity;
        this.rp = resourcesProvider;

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(16), dp(24), dp(20));

        // заголовок
        TextView title = new TextView(context);
        title.setText("Импорт сессии");
        title.setTypeface(AndroidUtilities.bold());
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        content.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // подзаголовок
        TextView subtitle = new TextView(context);
        subtitle.setText("Telethon .session / StringSession / tdata (zip). Acкуратно: вход произойдёт без SMS — под винтовку попадёт только выбранная вами сессия.");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
        subtitle.setLineSpacing(dp(2), 1f);
        content.addView(subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            topMargin = dp(4);
        }});

        // StringSession
        stringInput = new EditText(context);
        stringInput.setHint("StringSession (1BQANOTEu…)");
        stringInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        stringInput.setMaxLines(3);
        stringInput.setSingleLine(false);
        stringInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        stringInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
        content.addView(stringInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            topMargin = dp(14);
        }});

        // tdata passcode
        passcodeInput = new EditText(context);
        passcodeInput.setHint("Пароль tdata (если есть)");
        passcodeInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        passcodeInput.setMaxLines(1);
        passcodeInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        passcodeInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
        content.addView(passcodeInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            topMargin = dp(10);
        }});

        // кнопки выбора файлов
        LinearLayout pickRow = new LinearLayout(context);
        pickRow.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(pickRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            topMargin = dp(12);
        }});

        pickRow.addView(chip("Выбрать .session", v -> pickFile(PICK_SESSION_FILE)), pickParams());
        FrameLayout spacer = new FrameLayout(context);
        pickRow.addView(spacer, new LinearLayout.LayoutParams(dp(8), dp(40)));
        pickRow.addView(chip("Выбрать tdata (zip)", v -> pickFile(PICK_TDATA_FILE)), pickParams());

        // статус
        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, rp));
        statusView.setGravity(Gravity.CENTER);
        content.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            topMargin = dp(12);
        }});

        // импорт
        importButton = new TextView(context) {
            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                chipRect.set(0, 0, getWidth(), getHeight());
                chipPaint.setColor(Theme.getColor(busy
                        ? Theme.key_windowBackgroundWhiteGrayText
                        : Theme.key_featuredStickers_addButton, rp));
                canvas.drawRoundRect(chipRect, dp(14), dp(14), chipPaint);
                super.onDraw(canvas);
            }
        };
        importButton.setText("Импортировать");
        importButton.setGravity(Gravity.CENTER);
        importButton.setTypeface(AndroidUtilities.bold());
        importButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        importButton.setTextColor(Theme.getColor(Theme.key_chats_actionIcon, rp));
        importButton.setOnClickListener(v -> runImport());
        content.addView(importButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)) {{
            topMargin = dp(16);
        }});

        containerView = content;
        setApplyTopPadding(false);
        setOnDismissListener((Runnable) () -> {
            if (instance == SessionImportSheet.this) {
                instance = null;
            }
        });
    }

    private LinearLayout.LayoutParams pickParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1f);
        return params;
    }

    private TextView chip(String text, View.OnClickListener onClick) {
        TextView chip = new TextView(getContext()) {
            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                chipRect.set(0, 0, getWidth(), getHeight());
                chipPaint.setColor(Theme.getColor(isPressed()
                        ? Theme.key_listSelector
                        : Theme.key_windowBackgroundGray, rp));
                canvas.drawRoundRect(chipRect, dp(12), dp(12), chipPaint);
                super.onDraw(canvas);
            }
        };
        chip.setText(text);
        chip.setGravity(Gravity.CENTER);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        chip.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        chip.setOnClickListener(onClick);
        return chip;
    }

    private void pickFile(int code) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            loginActivity.startActivityForResult(intent, code);
        } catch (Exception e) {
            setStatus("Не удалось открыть выбор файла: " + e.getMessage());
        }
    }

    private boolean onResult(int requestCode, int resultCode, Intent data) {
        if ((requestCode != PICK_SESSION_FILE && requestCode != PICK_TDATA_FILE)
                || data == null || data.getData() == null) {
            return false;
        }
        try {
            String name = "picked.bin";
            String nameGuess = data.getData().getLastPathSegment();
            if (!TextUtils.isEmpty(nameGuess)) {
                name = nameGuess.replaceAll("[^A-Za-z0-9._-]", "_");
            }
            String path = SessionImport.copyToCache(getContext(), data.getData(), name);
            if (requestCode == PICK_SESSION_FILE) {
                sessionFilePath = path;
                setStatus(".session: " + name);
            } else {
                tdataFilePath = path;
                setStatus("tdata: " + name);
            }
        } catch (Exception e) {
            setStatus("Не удалось прочитать файл: " + e.getMessage());
        }
        return true;
    }

    private void runImport() {
        if (busy) {
            return;
        }
        String sessionString = stringInput.getText().toString().trim();
        String passcode = passcodeInput.getText().toString();
        Context context = getContext();
        if (sessionString.length() > 16) {
            start(() -> SessionImport.importFromString(context, sessionString, this));
        } else if (sessionFilePath != null) {
            start(() -> SessionImport.importFromSessionFile(context, sessionFilePath, this));
        } else if (tdataFilePath != null) {
            start(() -> SessionImport.importFromTdata(context, tdataFilePath, passcode, this));
        } else {
            setStatus("Вставьте StringSession или выберите файл");
        }
    }

    private interface Starter {
        void start();
    }

    private void start(Starter starter) {
        busy = true;
        setStatus("Читаем сессию, подключаемся…");
        importButton.setText("Импорт…");
        starter.start();
    }

    // ---- SessionImport.Listener ----

    @Override
    public void onStatus(String status) {
        setStatus(status);
    }

    @Override
    public void onSuccess(TLRPC.TL_auth_authorization auth) {
        busy = false;
        setStatus("Готово, входим как " + (auth.user != null ? auth.user.first_name : ""));
        TLRPC.TL_auth_authorization copy = auth;
        AndroidUtilities.runOnUIThread(() -> {
            try {
                dismiss();
            } catch (Exception ignored) {
            }
            SessionImport.finishLogin(loginActivity, copy);
        }, 400);
    }

    @Override
    public void onError(String error) {
        busy = false;
        importButton.setText("Импортировать");
        setStatus("× " + error);
    }

    private void setStatus(String text) {
        AndroidUtilities.runOnUIThread(() -> {
            statusView.setText(text);
            importButton.invalidate();
        });
    }
}
