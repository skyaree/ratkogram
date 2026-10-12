package app.exteraless.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Preferences for the Ratko agent: watcher state, watch list, event log.
 */
public final class AgentConfig {

    public static class Watch {
        public int account;
        public long dialogId;
        public String title;       // cached dialog title
        public String prompt;      // what the agent should do with incoming messages
        public boolean autoReply;  // whether the watcher invokes the model

        public Watch() {
        }

        public Watch(int account, long dialogId, String title, String prompt, boolean autoReply) {
            this.account = account;
            this.dialogId = dialogId;
            this.title = title;
            this.prompt = prompt;
            this.autoReply = autoReply;
        }
    }

    public static class Event {
        public long time;
        public long dialogId;
        public String title;
        public String text;

        public Event() {
        }

        public Event(long time, long dialogId, String title, String text) {
            this.time = time;
            this.dialogId = dialogId;
            this.title = title;
            this.text = text;
        }
    }

    private static final Gson GSON = new Gson();
    private static final String KEY_WATCHES = "watches";
    private static final String KEY_WATCHER_ENABLED = "watcherEnabled";
    private static final String KEY_WATCH_INTERVAL = "watchIntervalSec";
    private static final String KEY_DEFAULT_PROMPT = "defaultWatchPrompt";
    private static final String KEY_EVENTS = "events";
    private static final int MAX_EVENTS = 60;

    private AgentConfig() {
    }

    public static SharedPreferences getPreferences() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences("ratkoAgent", Context.MODE_PRIVATE);
    }

    // ---- watch list ----

    public static synchronized List<Watch> getWatches() {
        List<Watch> watches = readList(KEY_WATCHES, new TypeToken<List<Watch>>() {}.getType());
        return watches == null ? new ArrayList<>() : watches;
    }

    public static synchronized void saveWatches(List<Watch> watches) {
        getPreferences().edit().putString(KEY_WATCHES, GSON.toJson(watches)).apply();
        AgentWatcher.onWatchListChanged();
    }

    public static synchronized Watch findWatch(long dialogId) {
        for (Watch w : getWatches()) {
            if (w.dialogId == dialogId) {
                return w;
            }
        }
        return null;
    }

    public static synchronized void addWatch(Watch watch) {
        List<Watch> watches = getWatches();
        boolean replaced = false;
        for (int a = 0; a < watches.size(); a++) {
            if (watches.get(a).dialogId == watch.dialogId) {
                watches.set(a, watch);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            watches.add(watch);
        }
        saveWatches(watches);
    }

    public static synchronized boolean removeWatch(long dialogId) {
        List<Watch> watches = getWatches();
        boolean removed = watches.removeIf(w -> w.dialogId == dialogId);
        if (removed) {
            saveWatches(watches);
        }
        return removed;
    }

    // ---- watcher prefs ----

    public static boolean isWatcherEnabled() {
        return getPreferences().getBoolean(KEY_WATCHER_ENABLED, true);
    }

    public static void setWatcherEnabled(boolean value) {
        getPreferences().edit().putBoolean(KEY_WATCHER_ENABLED, value).apply();
    }

    public static int getWatchIntervalSec() {
        return Math.max(15, getPreferences().getInt(KEY_WATCH_INTERVAL, 30));
    }

    public static void setWatchIntervalSec(int value) {
        getPreferences().edit().putInt(KEY_WATCH_INTERVAL, Math.max(15, value)).apply();
    }

    public static String getDefaultWatchPrompt() {
        return getPreferences().getString(KEY_DEFAULT_PROMPT,
                "A new message has arrived in this chat. If it requires a response, respond using the tools; otherwise, briefly summarize what happened.");
    }

    public static void setDefaultWatchPrompt(String value) {
        getPreferences().edit().putString(KEY_DEFAULT_PROMPT, value == null ? "" : value).apply();
    }

    // ---- event log (for the sheet) ----

    public static synchronized List<Event> getEvents() {
        List<Event> events = readList(KEY_EVENTS, new TypeToken<List<Event>>() {}.getType());
        return events == null ? new ArrayList<>() : events;
    }

    public static synchronized void logEvent(long dialogId, String title, String text) {
        List<Event> events = getEvents();
        events.add(new Event(System.currentTimeMillis(), dialogId, title, text));
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
        getPreferences().edit().putString(KEY_EVENTS, GSON.toJson(events)).apply();
    }

    public static synchronized void clearEvents() {
        getPreferences().edit().remove(KEY_EVENTS).apply();
    }

    private static <T> List<T> readList(String key, java.lang.reflect.Type type) {
        String raw = getPreferences().getString(key, null);
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        try {
            return GSON.fromJson(raw, type);
        } catch (Exception e) {
            FileLog.e("AgentConfig: cannot read " + key, e);
            return null;
        }
    }

    // ---- helpers ----

    public static JSONObject watchesToToolJson() {
        JSONObject result = new JSONObject();
        try {
            JSONArray watchesJson = new JSONArray();
            for (Watch watch : getWatches()) {
                JSONObject w = new JSONObject();
                w.put("dialog_id", watch.dialogId);
                w.put("title", watch.title);
                w.put("prompt", watch.prompt);
                w.put("auto_reply", watch.autoReply);
                watchesJson.put(w);
            }
            result.put("watches", watchesJson);
            result.put("watcher_enabled", isWatcherEnabled());
        } catch (Exception ignored) {
        }
        return result;
    }
}
