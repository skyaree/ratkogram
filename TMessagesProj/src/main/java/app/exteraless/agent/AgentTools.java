package app.exteraless.agent;

import android.text.TextUtils;

import androidx.collection.LongSparseArray;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.Iterator;
import java.util.List;

import app.exteraless.ai.data.Message;
import app.exteraless.ai.network.ToolHandler;

/**
 * Telegram control tools for the agent. Mirrors the tool pattern of
 * huecoder/MD3-Agent (spec -> describe -> execute) on top of the fork's
 * OpenAI-compatible tool-calling client.
 */
public class AgentTools implements ToolHandler {

    private final int account;

    public AgentTools(int account) {
        this.account = account;
    }

    // ---- definitions ----

    @Override
    public JSONArray definitions() {
        JSONArray defs = new JSONArray();
        defs.put(fn("tg_list_dialogs",
                "Returns the list of chats, groups, and channels of this Telegram account. Use it to look up the exact ID or username of a chat.",
                props(
                        prop("limit", "integer", "How many chats to return (up to 100). Default 30."),
                        prop("filter", "string", "Optional substring of the chat name or username to filter by.")),
                req()));
        defs.put(fn("tg_search_dialogs",
                "Searches the account's chats, groups, and channels by name, username, or link (t.me/...).",
                props(prop("query", "string", "Search query: name, @username, t.me link, or numeric ID.")),
                req("query")));
        defs.put(fn("tg_read_dialog",
                "Reads the recent message history of a chat, group, or channel, as a conversation transcript with message IDs and senders.",
                props(
                        prop("dialog", "string", "Chat: name, @username, t.me link, or numeric ID."),
                        prop("count", "integer", "How many recent messages to read (up to 100). Default 20.")),
                req("dialog")));
        defs.put(fn("tg_send_text",
                "Sends a text message to a chat, group, or channel. Returns ok or an error.",
                props(
                        prop("dialog", "string", "Chat: name, @username, t.me link, or numeric ID."),
                        prop("text", "string", "Message text to send.")),
                req("dialog", "text")));
        defs.put(fn("tg_reply_text",
                "Replies to a specific message in a chat. Replies to the last message by default, or to the first message containing the given substring.",
                props(
                        prop("dialog", "string", "Chat: name, @username, t.me link, or numeric ID."),
                        prop("text", "string", "Reply text."),
                        prop("reply_to_text", "string", "Substring of the message to reply to (if not the last one)."),
                        prop("last_message", "boolean", "True to reply to the very last message (default).")),
                req("dialog", "text")));
        defs.put(fn("tg_send_file",
                "Sends a file from the phone (photo, video, document — by absolute path) to a chat, optionally with a caption.",
                props(
                        prop("dialog", "string", "Chat: name, @username, t.me link, or numeric ID."),
                        prop("path", "string", "Absolute path to the file on the phone."),
                        prop("caption", "string", "Optional caption for the file.")),
                req("dialog", "path")));
        defs.put(fn("tg_watch_dialog",
                "Puts a chat, group, or channel under the agent's watch: the agent gains visibility of new incoming messages there in the background and reacts to them according to the prompt. Returns ok or an error.",
                props(
                        prop("dialog", "string", "Chat: name, @username, t.me link, or numeric ID."),
                        prop("prompt", "string", "What the agent should do with each incoming message in this chat."),
                        prop("auto_reply", "boolean", "True (default) — the agent itself decides how to react and can send messages.")),
                req("dialog")));
        defs.put(fn("tg_unwatch_dialog",
                "Removes a chat, group, or channel from the watch.",
                props(prop("dialog", "string", "Chat: name, @username, t.me link, or numeric ID.")),
                req("dialog")));
        defs.put(fn("tg_list_watches",
                "Returns the current watch list with the prompts for each chat.",
                props(),
                req()));
        defs.put(fn("tg_profile",
                "Information about the account being controlled: name, username, phone.",
                props(),
                req()));
        return defs;
    }

    // ---- execution ----

    @Override
    public String call(String name, JSONObject arguments) {
        switch (name) {
            case "tg_list_dialogs": {
                int limit = Math.max(1, Math.min(arguments.optInt("limit", 30), 100));
                return TgAgentApi.listDialogs(account, limit, arguments.optString("filter", null));
            }
            case "tg_search_dialogs": {
                String query = arguments.optString("query");
                TgAgentApi.ResolvedDialog dialog = TgAgentApi.resolveDialog(account, query);
                return dialog != null
                        ? "Found: " + dialog.describe()
                        : TgAgentApi.listDialogs(account, 15, query);
            }
            case "tg_read_dialog": {
                TgAgentApi.ResolvedDialog dialog = TgAgentApi.resolveDialog(account,
                        arguments.optString("dialog"));
                if (dialog == null) {
                    return "error: chat not found";
                }
                int count = Math.max(1, Math.min(arguments.optInt("count", 20), 100));
                TgAgentApi.HistoryResult history = TgAgentApi.readHistory(account, dialog.dialogId, count, 0);
                if (history.error != null) {
                    return "error: " + history.error;
                }
                return "Chat " + dialog.describe() + ":\n"
                        + TgAgentApi.transcript(account, history, UserConfig.getInstance(account).getClientUserId());
            }
            case "tg_send_text": {
                TgAgentApi.ResolvedDialog dialog = TgAgentApi.resolveDialog(account,
                        arguments.optString("dialog"));
                if (dialog == null) {
                    return "error: chat not found";
                }
                String text = arguments.optString("text");
                if (TextUtils.isEmpty(text)) {
                    return "error: empty text";
                }
                String error = TgAgentApi.sendText(account, dialog.dialogId, text, null);
                return error == null ? "ok: sent to " + dialog.describe() : "error: " + error;
            }
            case "tg_reply_text": {
                TgAgentApi.ResolvedDialog dialog = TgAgentApi.resolveDialog(account,
                        arguments.optString("dialog"));
                if (dialog == null) {
                    return "error: chat not found";
                }
                String text = arguments.optString("text");
                if (TextUtils.isEmpty(text)) {
                    return "error: empty text";
                }
                TgAgentApi.HistoryResult history = TgAgentApi.readHistory(account, dialog.dialogId, 20, 0);
                if (history.error != null) {
                    return "error: " + history.error;
                }
                TLRPC.Message target = null;
                String needle = arguments.optString("reply_to_text", null);
                if (!TextUtils.isEmpty(needle)) {
                    for (TLRPC.Message message : history.messages) {
                        if (message.message != null && message.message.toLowerCase()
                                .contains(needle.toLowerCase())) {
                            target = message;
                            break;
                        }
                    }
                }
                if (target == null && (arguments.optBoolean("last_message", true) || !TextUtils.isEmpty(needle))) {
                    for (TLRPC.Message message : history.messages) {
                        if (!message.out && TextUtils.isEmpty(needle)) {
                            target = message;
                            break;
                        }
                    }
                    if (target == null && TextUtils.isEmpty(needle) && !history.messages.isEmpty()) {
                        target = history.messages.get(0);
                    }
                }
                if (target == null) {
                    return "error: message to reply to not found";
                }
                String error = TgAgentApi.sendText(account, dialog.dialogId, text, target);
                return error == null
                        ? "ok: replied to message id " + target.id + " in " + dialog.describe()
                        : "error: " + error;
            }
            case "tg_send_file": {
                TgAgentApi.ResolvedDialog dialog = TgAgentApi.resolveDialog(account,
                        arguments.optString("dialog"));
                if (dialog == null) {
                    return "error: chat not found";
                }
                String path = arguments.optString("path");
                File file = new File(path);
                if (!file.exists()) {
                    return "error: file not found: " + path;
                }
                String error = TgAgentApi.sendFile(account, dialog.dialogId, path,
                        arguments.optString("caption", null), null);
                return error == null
                        ? "ok: sending file " + file.getName() + " (" + file.length() + " bytes) to " + dialog.describe()
                        : "error: " + error;
            }
            case "tg_watch_dialog": {
                TgAgentApi.ResolvedDialog dialog = TgAgentApi.resolveDialog(account,
                        arguments.optString("dialog"));
                if (dialog == null) {
                    return "error: chat not found";
                }
                String prompt = arguments.optString("prompt",
                        AgentConfig.getDefaultWatchPrompt());
                boolean autoReply = arguments.optBoolean("auto_reply", true);
                AgentConfig.Watch watch = new AgentConfig.Watch(account, dialog.dialogId,
                        dialog.title, prompt, autoReply);
                AgentConfig.addWatch(watch);
                return "ok: " + dialog.describe() + " is under watch. Prompt: " + prompt;
            }
            case "tg_unwatch_dialog": {
                TgAgentApi.ResolvedDialog dialog = TgAgentApi.resolveDialog(account,
                        arguments.optString("dialog"));
                if (dialog == null) {
                    return "error: chat not found";
                }
                boolean removed = AgentConfig.removeWatch(dialog.dialogId);
                return removed
                        ? "ok: " + dialog.describe() + " removed from watch"
                        : "error: this chat was not under watch";
            }
            case "tg_list_watches": {
                List<AgentConfig.Watch> watches = AgentConfig.getWatches();
                if (watches.isEmpty()) {
                    return "The watch list is empty.";
                }
                StringBuilder builder = new StringBuilder();
                for (AgentConfig.Watch watch : watches) {
                    builder.append("- \"").append(watch.title == null ? watch.dialogId : watch.title)
                            .append("\" (id ").append(watch.dialogId).append("), prompt: ")
                            .append(watch.prompt).append('\n');
                }
                return builder.toString();
            }
            case "tg_profile": {
                TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
                if (self == null) {
                    return "error: account not logged in";
                }
                StringBuilder builder = new StringBuilder();
                builder.append(UserObject.getUserName(self));
                if (self.username != null) {
                    builder.append(" (@" + self.username + ")");
                }
                builder.append("phone: +").append(self.phone);
                return "I am controlling the account: " + builder;
            }
            default:
                return "error: unknown tool " + name;
        }
    }

    // ---- presentation ----

    @Override
    public String describe(String name, JSONObject arguments) {
        String dialog = arguments.optString("dialog", null);
        switch (name) {
            case "tg_list_dialogs":
                return "Reading the chat list";
            case "tg_search_dialogs":
                return "Searching chat: " + dialog;
            case "tg_read_dialog":
                return "Reading messages from: " + dialog;
            case "tg_send_text":
                return "Sending a message to: " + dialog;
            case "tg_reply_text":
                return "Replying in: " + dialog;
            case "tg_send_file":
                return "Sending a file to: " + dialog;
            case "tg_watch_dialog":
                return "Setting up watch: " + dialog;
            case "tg_unwatch_dialog":
                return "Removing watch: " + dialog;
            case "tg_list_watches":
                return "Reading the watch list";
            case "tg_profile":
                return "Reading account information";
            default:
                return name;
        }
    }

    @Override
    public List<Message> fallback() {
        return null;
    }

    // ---- json helpers (MD3-Agent style) ----

    private static JSONObject fn(String name, String description, JSONObject parameters, JSONArray required) {
        JSONObject fn = new JSONObject();
        try {
            fn.put("name", name);
            fn.put("description", description);
            if (required != null && required.length() > 0) {
                parameters.put("required", required);
            }
            fn.put("parameters", parameters);
            return new JSONObject().put("type", "function").put("function", fn);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONObject props(JSONObject... properties) {
        JSONObject object = new JSONObject();
        try {
            object.put("type", "object");
            JSONObject inner = new JSONObject();
            for (JSONObject property : properties) {
                Iterator<String> keys = property.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    inner.put(key, property.get(key));
                }
            }
            object.put("properties", inner);
        } catch (Exception ignored) {
        }
        return object;
    }

    private static JSONObject prop(String name, String type, String description) {
        try {
            JSONObject property = new JSONObject();
            property.put("type", type);
            property.put("description", description);
            return new JSONObject().put(name, property);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONArray req(String... names) {
        JSONArray array = new JSONArray();
        for (String n : names) {
            array.put(n);
        }
        return array;
    }
}
