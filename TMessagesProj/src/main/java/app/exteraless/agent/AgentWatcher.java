package app.exteraless.agent;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import app.exteraless.ai.AiController;
import app.exteraless.ai.network.Client;

/**
 * The "alive" part of the agent: watches the selected chats in the background,
 * notices new messages, and gives the model a chance to react.
 */
public final class AgentWatcher {

    public interface Listener {
        default void onWatchListChanged() {
        }

        default void onBusyChanged(boolean busy) {
        }
    }

    private static final Handler mainThread = new Handler(Looper.getMainLooper());
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Map<String, Integer> lastTop = new HashMap<>();     // "account:dialog" -> top message id
    private static final Set<String> inflight = ConcurrentHashMap.newKeySet();
    private static volatile boolean started;
    private static volatile int busyCount;

    private AgentWatcher() {
    }

    public static void addListener(Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public static boolean isBusy() {
        return busyCount > 0;
    }

    public static int watchesCount() {
        return AgentConfig.getWatches().size();
    }

    public static void ensureStarted() {
        if (started) {
            return;
        }
        started = true;
        scheduleTick();
    }

    static void onWatchListChanged() {
        ensureStarted();
        mainThread.post(() -> {
            for (Listener listener : listeners) {
                listener.onWatchListChanged();
            }
        });
    }

    private static void scheduleTick() {
        List<AgentConfig.Watch> watches = AgentConfig.getWatches();
        boolean active = !watches.isEmpty() && AgentConfig.isWatcherEnabled();
        long delay = active
                ? AgentConfig.getWatchIntervalSec() * 1000L
                : 5 * 60 * 1000L;
        mainThread.postDelayed(() -> {
            if (!started) {
                return;
            }
            executor.execute(AgentWatcher::tick);
            scheduleTick();
        }, delay);
    }

    private static void tick() {
        if (!AgentConfig.isWatcherEnabled()) {
            return;
        }
        for (AgentConfig.Watch watch : AgentConfig.getWatches()) {
            try {
                processWatch(watch);
            } catch (Throwable t) {
                FileLog.e("AgentWatcher: tick failed for " + watch.dialogId, t);
            }
        }
    }

    private static void processWatch(AgentConfig.Watch watch) {
        if (!UserConfig.getInstance(watch.account).isClientActivated()) {
            return;
        }
        String key = watch.account + ":" + watch.dialogId;
        if (!inflight.add(key)) {
            return;
        }
        try {
            long top = TgAgentApi.localTopMessageId(watch.account, watch.dialogId);
            if (top < 0) {
                return; // dialog not loaded locally yet
            }
            Integer previous = lastTop.get(key);
            if (previous == null) {
                lastTop.put(key, (int) top);
                return; // first sighting — baseline, do not react
            }
            if (top <= previous) {
                lastTop.put(key, (int) Math.max(top, previous));
                return;
            }
            TgAgentApi.HistoryResult history =
                    TgAgentApi.readHistory(watch.account, watch.dialogId, 5, 0);
            if (history.error != null) {
                return;
            }
            lastTop.put(key, (int) top);

            // only incoming messages
            StringBuilder incoming = new StringBuilder();
            int selfId = UserConfig.getInstance(watch.account).getClientUserId();
            for (org.telegram.tgnet.TLRPC.Message message : history.messages) {
                if (message.out || message.id <= previous) {
                    continue;
                }
                String sender = TgAgentApi.senderName(watch.account, history, message, true);
                incoming.append(sender == null ? "someone" : sender)
                        .append(": ").append(TgAgentApi.bodyText(message)).append('\n');
            }
            if (incoming.length() == 0) {
                return;
            }

            String title = TextUtils.isEmpty(watch.title) ? String.valueOf(watch.dialogId) : watch.title;
            AgentConfig.logEvent(watch.dialogId, title,
                    "New message in \"" + title + "\":\n" + incoming.toString().trim());
            notifyWatchListChanged();

            if (!watch.autoReply || !AiController.canUseAI()) {
                return;
            }
            react(watch, title, incoming.toString().trim());
        } finally {
            inflight.remove(key);
        }
    }

    private static void react(AgentConfig.Watch watch, String title, String incoming) {
        busyCount++;
        notifyBusyChanged();
        Client client = new Client.Builder()
                .tools(new AgentTools(watch.account))
                .build();
        String prompt = "[watcher] A new message has arrived in the chat \""
                + title + "\" (id " + watch.dialogId + "):\n"
                + incoming + "\n\n"
                + "Your instructions for this chat: " + watch.prompt + "\n"
                + "Act according to the instructions. If a reply is needed — send it using tools. "
                + "If nothing needs to be done, briefly say so.";
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] result = {""};
        client.getResponse(prompt, false, false, null, new app.exteraless.ai.network.GenerationCallback() {
            @Override
            public void onChunk(String chunk) {
            }

            @Override
            public void onResponse(String response) {
                result[0] = response;
                latch.countDown();
            }

            @Override
            public void onError(int code, String message) {
                result[0] = "error: " + message;
                latch.countDown();
            }

            @Override
            public void onThinking() {
            }

            @Override
            public void onReasoning(String chunk) {
            }
        });
        try {
            latch.await(3, TimeUnit.MINUTES);
        } catch (InterruptedException ignored) {
        }
        if (!TextUtils.isEmpty(result[0])) {
            String note = "Agent (" + title + "): " + result[0];
            AgentConfig.logEvent(watch.dialogId, title, note);
            notifyWatchListChanged();
        }
        busyCount--;
        notifyBusyChanged();
    }

    private static void notifyWatchListChanged() {
        mainThread.post(() -> {
            for (Listener listener : listeners) {
                listener.onWatchListChanged();
            }
        });
    }

    private static void notifyBusyChanged() {
        mainThread.post(() -> {
            for (Listener listener : listeners) {
                listener.onBusyChanged(busyCount > 0);
            }
        });
    }
}
