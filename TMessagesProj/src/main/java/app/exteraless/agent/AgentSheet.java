package app.exteraless.agent;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.exteraless.ai.AiController;
import app.exteraless.ai.data.Message;
import app.exteraless.ai.network.Client;
import app.exteraless.ai.network.GenerationCallback;
import app.exteraless.player.MorphButton;
import app.exteraless.player.PlayerColors;
import app.exteraless.player.PlayerIcon;
import app.exteraless.player.Spring;

/**
 * The MD3 sheet of the agent — the tonal engine and the springs
 * are taken from the player (PlayerColors/MorphButton/Spring).
 */
public class AgentSheet extends BottomSheet implements AgentWatcher.Listener, GenerationCallback {

    private static AgentSheet instance;

    public static void open(BaseFragment fragment, AgentFab fab) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        if (instance != null) {
            try {
                instance.dismiss();
            } catch (Exception ignored) {
            }
            instance = null;
        }
        AgentSheet sheet = new AgentSheet(context, fragment.getResourceProvider(), fab);
        instance = sheet;
        sheet.show();
    }

    public static final String SEND_ARROW = "M12 20V5M5 12l7-7 7 7";

    private static final int BUBBLE_USER = 0;
    private static final int BUBBLE_AGENT = 1;
    private static final int BUBBLE_TOOL = 2;

    private static final Map<String, Path> pathCache = new HashMap<>();

    private static Path parsePath(String d) {
        Path path = pathCache.get(d);
        if (path == null) {
            path = androidx.core.graphics.PathParser.createPathFromPathData(d);
            pathCache.put(d, path);
        }
        return path;
    }

    private static final String[] START_HINTS = {
            "Summarize the last messages in the @durov channel",
            "Reply to the last message from the Work chat that the report is ready",
            "Watch the News channel: tone down the jokes about zeros in the comments",
            "Send /sdcard/Download/report.pdf to the Work chat with the caption \"Here\""
    };

    private final AgentFab fab;
    private final int account;
    private final PlayerColors colors;
    private final Paint userPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint agentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint toolPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inputPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bgPath = new Path();
    private final RectF bgRect = new RectF();

    private final List<Message> conversation = new ArrayList<>();
    private FrameLayout rootLayout;
    private ScrollView scroll;
    private LinearLayout transcript;
    private TextView statusView;
    private TextView modelView;
    private EditText input;
    private MorphButton sendButton;

    private final Spring avatarScale = new Spring(0.4f, 320f, 0.35f, 0.01f);
    private boolean avatarBusy;

    private Client client;
    private String requestId;

    public AgentSheet(Context context, Theme.ResourcesProvider resourcesProvider, AgentFab fabButton) {
        super(context, true, resourcesProvider);
        this.fab = fabButton;
        this.account = UserConfig.selectedAccount;
        boolean dark = PlayerColors.isDark(resourcesProvider);
        this.colors = PlayerColors.fromSeed(PlayerColors.noCoverSeed(resourcesProvider), dark);

        userPaint.setColor(colors.primaryContainer);
        agentPaint.setColor(colors.surfaceHigh);
        toolPaint.setColor(colors.surfaceContainer);
        inputPaint.setColor(colors.surfaceHigh);

        setApplyTopPadding(false);
        setApplyBottomPadding(true);

        rootLayout = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int maxWidth = AndroidUtilities.displaySize.x;
                int maxHeight = (int) (AndroidUtilities.displaySize.y * 0.74f);
                super.onMeasure(
                        MeasureSpec.makeMeasureSpec(Math.min(MeasureSpec.getSize(widthMeasureSpec), maxWidth), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.min(MeasureSpec.getSize(heightMeasureSpec), maxHeight), MeasureSpec.EXACTLY));
            }
        };
        rootLayout.setBackgroundColor(colors.surface);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        rootLayout.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        // ---------- header: spark + title + model + watch ----------
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dp(20), dp(16), dp(20), dp(6));
        header.setGravity(Gravity.CENTER_VERTICAL);

        SparkView avatar = new SparkView(context);
        header.addView(avatar, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(12);
        header.addView(titles, titleParams);

        TextView titleView = new TextView(context);
        titleView.setTextColor(colors.onSurface);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        titleView.setText("RatkoAgent");
        titles.addView(titleView);

        modelView = new TextView(context);
        modelView.setTextColor(colors.onSurfaceVariant);
        modelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        titles.addView(modelView);

        statusView = new TextView(context);
        statusView.setTextColor(colors.primary);
        statusView.setTypeface(AndroidUtilities.bold());
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        header.addView(statusView);

        column.addView(header);

        // ---------- transcript ----------
        scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        transcript = new LinearLayout(context);
        transcript.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(transcript, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        List<AgentConfig.Event> events = AgentConfig.getEvents();
        int shown = 0;
        for (int a = events.size() - 1; a >= 0 && shown < 6; a--, shown++) {
            addBubble(BUBBLE_TOOL, events.get(a).text);
        }
        if (!AiController.canUseAI()) {
            addBubble(BUBBLE_AGENT, "The AI service is not configured. Settings → AI — add a key and a route, and I will gain the ability to see and act in Telegram.");
        } else if (shown == 0) {
            String hint = START_HINTS[Utilities.fastRandom.nextInt(START_HINTS.length)];
            addBubble(BUBBLE_AGENT, "I am ready and see your entire Telegram: I can write, reply, send files, and watch chats and channels. For example:\n«" + hint + "»");
        }

        // ---------- input ----------
        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setPadding(dp(16), dp(8), dp(16), dp(12));
        inputRow.setGravity(Gravity.CENTER_VERTICAL);

        input = new EditText(context);
        input.setBackground(null);
        input.setTextColor(colors.onSurface);
        input.setHintTextColor(colors.onSurfaceVariant);
        input.setHint("What should I do in Telegram?");
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setMaxLines(6);
        input.setVerticalScrollBarEnabled(true);
        input.setPadding(dp(16), dp(11), dp(16), dp(11));
        input.setSingleLine(false);

        View inputBg = new View(context) {
            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                bgRect.set(0, 0, getWidth(), getHeight());
                canvas.drawRoundRect(bgRect, dp(26), dp(26), inputPaint);
            }
        };

        FrameLayout inputWrap = new FrameLayout(context);
        inputWrap.addView(inputBg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        inputWrap.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        LinearLayout.LayoutParams inputWrapParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        inputWrapParams.rightMargin = dp(10);
        inputRow.addView(inputWrap, inputWrapParams);

        sendButton = new MorphButton(context, PlayerIcon.stroke(SEND_ARROW, 24));
        sendButton.setColors(colors.primaryContainer, colors.onPrimaryContainer, colors.primary, colors.onPrimary);
        sendButton.setRadius(dp(16), false);
        sendButton.setOnClickListener(v -> send());
        sendButton.setOnLongClickListener(v -> {
            stop();
            addBubble(BUBBLE_TOOL, "Generation stopped");
            return true;
        });
        sendButton.setActive(false, false);
        inputRow.addView(sendButton, new LinearLayout.LayoutParams(dp(48), dp(48)));

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                sendButton.setActive(!TextUtils.isEmpty(s), true);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        column.addView(inputRow);

        containerView = rootLayout;

        setOnDismissListener((Runnable) () -> {
            AgentWatcher.removeListener(AgentSheet.this);
            if (instance == AgentSheet.this) {
                instance = null;
            }
        });
        AgentWatcher.addListener(this);
        AgentWatcher.ensureStarted();
        updateStatus();
    }

    // ---------- conversation ----------

    private void send() {
        String prompt = input.getText().toString().trim();
        if (TextUtils.isEmpty(prompt) || client != null) {
            return;
        }
        if (!AiController.canUseAI()) {
            addBubble(BUBBLE_TOOL, "The AI service is not configured");
            return;
        }
        input.setText("");
        addBubble(BUBBLE_USER, prompt);
        conversation.add(new Message("user", prompt));
        addBubble(BUBBLE_AGENT, "");
        if (fab != null) {
            fab.setGenerating(true, true);
        }
        setAvatarBusy(true, true);

        client = new Client.Builder()
                .tools(new AgentTools(account))
                .build();
        requestId = UUID.randomUUID().toString();
        List<Message> turn = new ArrayList<>(conversation);
        client.generate(requestId, turn, false, this);
    }

    public void stop() {
        Client current = client;
        String id = requestId;
        if (current != null && id != null) {
            client = null;
            requestId = null;
            current.stopRequest(id);
            updateStatus();
        }
    }

    // ---------- GenerationCallback ----------

    @Override
    public void onThinking() {
        setAvatarBusy(true, true);
    }

    @Override
    public void onChunk(String chunk) {
        appendToLastBubble(chunk);
    }

    @Override
    public void onReasoning(String chunk) {
        String trimmed = chunk == null ? "" : chunk.trim();
        if (!TextUtils.isEmpty(trimmed)) {
            addBubble(BUBBLE_TOOL, trimmed);
        }
    }

    @Override
    public void onResponse(String response) {
        client = null;
        requestId = null;
        conversation.add(new Message("assistant", response));
        if (TextUtils.isEmpty(response)) {
            removeLastBubble();
        } else {
            replaceLastBubble(response);
        }
        updateStatus();
        setAvatarBusy(AgentWatcher.isBusy(), true);
    }

    @Override
    public void onError(int code, String message) {
        client = null;
        requestId = null;
        replaceLastBubble("× " + (message == null ? "error" : message));
        updateStatus();
        setAvatarBusy(AgentWatcher.isBusy(), true);
    }

    // ---------- AgentWatcher.Listener ----------

    @Override
    public void onWatchListChanged() {
        AndroidUtilities.runOnUIThread(this::updateStatus);
    }

    @Override
    public void onBusyChanged(boolean busy) {
        AndroidUtilities.runOnUIThread(() -> {
            if (client == null && fab != null) {
                fab.setGenerating(busy, true);
            }
            setAvatarBusy(busy || client != null, true);
        });
    }

    // ---------- bubbles ----------

    private void addBubble(int kind, String text) {
        BubbleView bubble = new BubbleView(kind, text);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = kind == BUBBLE_USER ? Gravity.END
                : kind == BUBBLE_AGENT ? Gravity.START : Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = dp(6);
        params.leftMargin = dp(16);
        params.rightMargin = dp(16);
        transcript.addView(bubble, params);
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void appendToLastBubble(String addition) {
        View view = lastBubble();
        if (view instanceof BubbleView) {
            ((BubbleView) view).appendText(addition);
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        }
    }

    private void replaceLastBubble(String text) {
        View view = lastBubble();
        if (view instanceof BubbleView) {
            ((BubbleView) view).setTextOnly(text);
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        }
    }

    private void removeLastBubble() {
        View view = lastBubble();
        if (view instanceof BubbleView) {
            transcript.removeView(view);
        }
    }

    private View lastBubble() {
        int count = transcript.getChildCount();
        return count == 0 ? null : transcript.getChildAt(count - 1);
    }

    private void updateStatus() {
        int watches = AgentWatcher.watchesCount();
        if (watches > 0) {
            statusView.setText(LocaleController.formatPluralString("Chats", watches));
        } else {
            statusView.setText("");
        }
        String model = AiController.canUseAI() ? AiController.getSelected().getDisplayName() : null;
        modelView.setText(model != null ? model : "model is not set up");
        sendButton.setVisibility(AiController.canUseAI() ? View.VISIBLE : View.GONE);
    }

    private void setAvatarBusy(boolean busy, boolean animated) {
        if (avatarBusy != busy) {
            avatarBusy = busy;
            avatarScale.target = busy ? 1f : 0.4f;
        }
        if (fab != null) {
            fab.setGenerating(busy || client != null, animated);
        }
    }

    // ---------- avatar ----------

    private final class SparkView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean animating;

        SparkView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float breathing = avatarBusy ? 1f + 0.1f * (float) Math.sin(System.currentTimeMillis() / 170.0) : 1f;
            animating = avatarScale.step(0.016f);
            float s = Math.max(0.05f, avatarScale.value * breathing);
            paint.setColor(colors.primary);
            canvas.save();
            canvas.translate(cx, cy);
            canvas.rotate(avatarBusy ? (float) (System.currentTimeMillis() / 3.0 % 360) : 0f);
            canvas.scale(s, s);
            canvas.translate(-12f, -12f);
            canvas.drawPath(parsePath(AgentFab.SPARK_PATH), paint);
            canvas.restore();
            if (animating || avatarBusy) {
                postInvalidateOnAnimation();
            }
        }
    }

    // ---------- bubbles ----------

    private final class BubbleView extends TextView {

        private static final float MAX_WIDTH_DP = 280f;
        private final int kind;

        BubbleView(int kind, String text) {
            super(AgentSheet.this.getContext());
            this.kind = kind;
            setLineSpacing(dp(2), 1f);
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            setMaxWidth(dp(MAX_WIDTH_DP));
            if (kind == BUBBLE_USER) {
                setPadding(dp(13), dp(9), dp(14), dp(9));
                setTextColor(colors.onPrimaryContainer);
                setTypeface(AndroidUtilities.bold());
            } else if (kind == BUBBLE_AGENT) {
                setPadding(dp(14), dp(9), dp(13), dp(9));
                setTextColor(colors.onSurface);
            } else {
                setPadding(dp(10), dp(4), dp(10), dp(4));
                setTextColor(colors.onSurfaceVariant);
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                setGravity(Gravity.CENTER);
            }
            setText(text);
        }

        void appendText(String addition) {
            if (addition != null && !addition.isEmpty()) {
                String current = getText().toString();
                setText(current + addition);
            }
        }

        void setTextOnly(String text) {
            setText(text);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            if (kind != BUBBLE_TOOL) {
                float r = dp(16);
                float tail = dp(4);
                bgRect.set(0, 0, getWidth(), getHeight());
                float[] radii = kind == BUBBLE_USER
                        ? new float[]{r, r, r, r, tail, tail, r, r}   // tail bottom-right
                        : new float[]{r, r, r, r, r, r, tail, tail};   // tail bottom-left
                bgPath.rewind();
                bgPath.addRoundRect(bgRect, radii, Path.Direction.CW);
                canvas.drawPath(bgPath, kind == BUBBLE_USER ? userPaint : agentPaint);
            } else {
                bgRect.set(0, 0, getWidth(), getHeight());
                canvas.drawRoundRect(bgRect, dp(10), dp(10), toolPaint);
            }
            super.onDraw(canvas);
        }
    }

    @Override
    public void dismiss() {
        if (client != null && requestId != null) {
            client.stopRequest(requestId);
        }
        super.dismiss();
    }
}
