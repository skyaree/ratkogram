package app.exteraless.agent;

import android.net.Uri;
import android.text.TextUtils;

import androidx.core.view.inputmethod.InputContentInfoCompat;
import androidx.collection.LongSparseArray;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Low-level Telegram operations for the agent: dialog resolution, history reads,
 * text/file sends. Everything is safe to call from a non-UI worker thread;
 * UI-bound calls are marshaled with latches.
 */
public final class TgAgentApi {

    public static class ResolvedDialog {
        public long dialogId;
        public String title;
        public String username;
        public boolean channel;     // broadcast channel
        public boolean group;
        public boolean user;

        public String describe() {
            String type = user ? "user" : channel ? "channel" : group ? "group" : "chat";
            return title + " (@" + username + ", " + type + ", id " + dialogId + ")";
        }
    }

    public static class HistoryResult {
        public List<TLRPC.Message> messages = new ArrayList<>();
        public LongSparseArray<TLRPC.User> users = new LongSparseArray<>();
        public LongSparseArray<TLRPC.Chat> chats = new LongSparseArray<>();
        public String error;
    }

    private TgAgentApi() {
    }

    // ---------- dialog resolution ----------

    public static ResolvedDialog resolveDialog(int account, String query) {
        if (TextUtils.isEmpty(query)) {
            return null;
        }
        String trimmed = query.trim();
        ResolvedDialog resolved = resolveFromDialogs(account, trimmed);
        if (resolved != null) {
            return resolved;
        }
        String username = trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
        if (username.matches("[a-zA-Z][\\w_]{3,31}") || trimmed.startsWith("@") ||
                trimmed.startsWith("https://t.me/") || trimmed.startsWith("t.me/")) {
            if (trimmed.contains("t.me/")) {
                int idx = trimmed.indexOf("t.me/");
                username = trimmed.substring(idx + 5);
                int slash = username.indexOf('/');
                if (slash > 0) {
                    username = username.substring(0, slash);
                }
            }
            return resolveUsername(account, username);
        }
        // plain digit id ("-100123...", "123...")
        try {
            long id = Long.parseLong(trimmed);
            return describeId(account, id);
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private static ResolvedDialog describeId(int account, long id) {
        if (id > 0) {
            TLRPC.User user = MessagesController.getInstance(account).getUser(id);
            if (user != null) {
                return fromUser(user);
            }
        } else if (-id > 0) {
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-id);
            if (chat != null) {
                return fromChat(chat);
            }
        }
        return null;
    }

    private static ResolvedDialog resolveFromDialogs(int account, String query) {
        final CountDownLatch latch = new CountDownLatch(1);
        final ResolvedDialog[] out = new ResolvedDialog[1];
        AndroidUtilities.runOnUIThread(() -> {
            try {
                List<TLRPC.Dialog> dialogs = MessagesController.getInstance(account).getAllDialogs();
                String number = query.startsWith("-") ? query.substring(1) : query;
                long asId = 0;
                try {
                    asId = Long.parseLong(number);
                } catch (NumberFormatException ignored) {
                }
                long asUserId = 0;
                if (!query.startsWith("-")) {
                    try {
                        asUserId = Long.parseLong(query);
                    } catch (NumberFormatException ignored) {
                    }
                }
                ResolvedDialog bestPrefix = null;
                for (TLRPC.Dialog dialog : dialogs) {
                    long id = dialog.id;
                    if (id == asId || id == asUserId) {
                        ResolvedDialog d = describeId(account, id);
                        if (d != null) {
                            out[0] = d;
                            return;
                        }
                    }
                    ResolvedDialog d = describeId(account, id);
                    if (d == null) {
                        continue;
                    }
                    String lowerTitle = d.title == null ? "" : d.title.toLowerCase();
                    if (lowerTitle.equals(query.toLowerCase())) {
                        out[0] = d;
                        return;
                    }
                    if (d.username != null && d.username.equalsIgnoreCase(query)) {
                        out[0] = d;
                        return;
                    }
                    if (bestPrefix == null && !lowerTitle.isEmpty()
                            && lowerTitle.startsWith(query.toLowerCase())) {
                        bestPrefix = d;
                    }
                }
                out[0] = bestPrefix;
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return out[0];
    }

    private static ResolvedDialog resolveUsername(int account, String username) {
        TLRPC.TL_contacts_resolveUsername request = new TLRPC.TL_contacts_resolveUsername();
        request.username = username;
        final CountDownLatch latch = new CountDownLatch(1);
        final ResolvedDialog[] out = new ResolvedDialog[1];
        RequestDelegate delegate = (response, error) -> {
            try {
                if (response instanceof TLRPC.TL_contacts_resolvedPeer) {
                    TLRPC.TL_contacts_resolvedPeer peer = (TLRPC.TL_contacts_resolvedPeer) response;
                    for (TLRPC.User user : peer.users) {
                        if (user.username != null && user.username.equalsIgnoreCase(username)) {
                            MessagesController.getInstance(account).putUser(user, false, true);
                            out[0] = fromUser(user);
                            return;
                        }
                    }
                    for (TLRPC.Chat chat : peer.chats) {
                        if (chat.username != null && chat.username.equalsIgnoreCase(username)) {
                            MessagesController.getInstance(account).putChat(chat, false);
                            out[0] = fromChat(chat);
                            return;
                        }
                    }
                }
            } finally {
                latch.countDown();
            }
        };
        ConnectionsManager.getInstance(account).sendRequest(request, delegate);
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return out[0];
    }

    static ResolvedDialog fromUser(TLRPC.User user) {
        ResolvedDialog d = new ResolvedDialog();
        d.dialogId = user.id;
        d.title = UserObject.getUserName(user);
        d.username = user.username;
        d.user = true;
        return d;
    }

    static ResolvedDialog fromChat(TLRPC.Chat chat) {
        ResolvedDialog d = new ResolvedDialog();
        d.dialogId = -chat.id;
        d.title = chat.title;
        d.username = chat.username;
        d.channel = chat.broadcast;
        d.group = !chat.broadcast;
        return d;
    }

    // ---------- history ----------

    public static HistoryResult readHistory(int account, long dialogId, int limit, int offsetId) {
        HistoryResult result = new HistoryResult();
        TLRPC.InputPeer peer = MessagesController.getInstance(account).getInputPeer(dialogId);
        if (peer == null) {
            result.error = "unknown peer";
            return result;
        }
        TLRPC.TL_messages_getHistory request = new TLRPC.TL_messages_getHistory();
        request.peer = peer;
        request.limit = Math.max(1, Math.min(limit, 100));
        request.offset_id = offsetId;
        request.offset_date = 0;
        request.add_offset = offsetId == 0 ? 0 : -request.limit;
        final CountDownLatch latch = new CountDownLatch(1);
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            try {
                if (error != null) {
                    result.error = error.text;
                    return;
                }
                if (response instanceof TLRPC.messages_Messages) {
                    TLRPC.messages_Messages messages = (TLRPC.messages_Messages) response;
                    result.messages.addAll(messages.messages);
                    for (TLRPC.User user : messages.users) {
                        result.users.put(user.id, user);
                    }
                    for (TLRPC.Chat chat : messages.chats) {
                        result.chats.put(chat.id, chat);
                    }
                } else {
                    result.error = "unexpected response";
                }
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        if (result.error == null && result.messages.isEmpty()) {
            result.error = "empty";
        }
        return result;
    }

    /** Transcribes loaded history into a compact chat log for the model. */
    public static String transcript(int account, HistoryResult history, int selfUserId) {
        if (history == null || history.messages.isEmpty()) {
            return "(no messages)";
        }
        StringBuilder builder = new StringBuilder();
        List<TLRPC.Message> reversed = new ArrayList<>(history.messages);
        java.util.Collections.reverse(reversed); // oldest first
        for (TLRPC.Message message : reversed) {
            builder.append('[').append("id ").append(message.id);
            if (message.out) {
                builder.append(", me");
            } else {
                String name = senderName(account, history, message, false);
                if (name != null) {
                    builder.append(", from: ").append(name);
                }
            }
            builder.append("] ");
            String body = bodyText(message);
            builder.append(body).append('\n');
        }
        return builder.toString();
    }

    public static String bodyText(TLRPC.Message message) {
        String text = message.message;
        boolean hasText = !TextUtils.isEmpty(text);
        TLRPC.MessageMedia media = message.media;
        String mediaLabel = null;
        if (media != null) {
            if (media instanceof TLRPC.TL_messageMediaPhoto) {
                mediaLabel = "[photo]";
            } else if (media instanceof TLRPC.TL_messageMediaDocument) {
                TLRPC.Document document = media.document;
                String filename = TgAgentApi.fileName(document);
                String mime = document != null && document.mime_type != null ? document.mime_type : null;
                if (MessageObject.isVoiceDocument(document)) {
                    mediaLabel = "[voice message]";
                } else if (MessageObject.isAnimatedStickerDocument(document) || MessageObject.isStickerDocument(document)) {
                    mediaLabel = "[sticker]";
                } else if (MessageObject.isRoundVideoDocument(document)) {
                    mediaLabel = "[video message]";
                } else if (MessageObject.isVideoDocument(document) || MessageObject.isGifDocument(document)) {
                    mediaLabel = filename != null ? "[video: " + filename + "]" : "[video]";
                } else if (mime != null && mime.startsWith("audio/")) {
                    mediaLabel = filename != null ? "[audio: " + filename + "]" : "[audio]";
                } else {
                    mediaLabel = filename != null ? "[file: " + filename + "]" : "[document]";
                }
            } else if (media instanceof TLRPC.TL_messageMediaWebPage) {
                String url = ((TLRPC.TL_messageMediaWebPage) media).webpage != null
                        ? ((TLRPC.TL_messageMediaWebPage) media).webpage.url : null;
                mediaLabel = url != null ? "[link: " + url + "]" : "[link]";
            } else if (media instanceof TLRPC.TL_messageMediaGeo) {
                mediaLabel = "[location]";
            } else if (media instanceof TLRPC.TL_messageMediaContact) {
                mediaLabel = "[contact]";
            } else if (media instanceof TLRPC.TL_messageMediaPoll) {
                TLRPC.Poll poll = ((TLRPC.TL_messageMediaPoll) media).poll;
                mediaLabel = "[poll: " + (poll != null && poll.question != null ? poll.question.text : "") + "]";
            } else {
                mediaLabel = "[" + media.getClass().getSimpleName()
                        .replace("TL_messageMedia", "").replace("TL_", "") + "]";
            }
        }
        if (hasText && mediaLabel != null) {
            return mediaLabel + " " + text;
        }
        return hasText ? text : (mediaLabel != null ? mediaLabel : "(unsupported message)");
    }

    public static String fileName(TLRPC.Document document) {
        if (document == null) {
            return null;
        }
        for (TLRPC.DocumentAttribute attribute : document.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeFilename) {
                return ((TLRPC.TL_documentAttributeFilename) attribute).file_name;
            }
        }
        return null;
    }

    public static String senderName(int account, HistoryResult history, TLRPC.Message message, boolean allowSelf) {
        if (message.from_id instanceof TLRPC.TL_peerUser) {
            long userId = ((TLRPC.TL_peerUser) message.from_id).user_id;
            if (allowSelf && userId == UserConfig.getInstance(account).getClientUserId()) {
                return "me";
            }
            TLRPC.User user = history.users.get(userId);
            if (user == null) {
                user = MessagesController.getInstance(account).getUser(userId);
            }
            if (user != null) {
                return UserObject.getUserName(user);
            }
        } else if (message.from_id instanceof TLRPC.TL_peerChannel) {
            long id = ((TLRPC.TL_peerChannel) message.from_id).channel_id;
            TLRPC.Chat chat = history.chats.get(id);
            if (chat == null) {
                chat = MessagesController.getInstance(account).getChat(id);
            }
            if (chat != null) {
                return chat.title;
            }
        }
        return null;
    }

    // ---------- sending ----------

    public static String sendText(int account, long dialogId, String text, TLRPC.Message replyTo) {
        MessageObject replyToObject = replyTo == null ? null : asMessageObject(account, replyTo);
        CountDownLatch latch = new CountDownLatch(1);
        final String[] error = {null};
        AndroidUtilities.runOnUIThread(() -> {
            try {
                SendMessagesHelper.getInstance(account).sendMessage(text, dialogId,
                        replyToObject, null, null, true, null, null, null,
                        true, 0, 0, null, true);
            } catch (Throwable t) {
                error[0] = t.getMessage();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return error[0];
    }

    public static String sendFile(int account, long dialogId, String path, String caption, TLRPC.Message replyTo) {
        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            return "file not found: " + path;
        }
        String mime = mimeFor(path);
        MessageObject replyToObject = replyTo == null ? null : asMessageObject(account, replyTo);
        CountDownLatch latch = new CountDownLatch(1);
        final String[] error = {null};
        AndroidUtilities.runOnUIThread(() -> {
            try {
                SendMessagesHelper.prepareSendingDocument(
                        AccountInstance.getInstance(account),
                        path, path, (Uri) null, caption, mime,
                        dialogId, replyToObject, null,
                        null, null, null,
                        true, 0, (InputContentInfoCompat) null, null, 0, false);
            } catch (Throwable t) {
                error[0] = t.getMessage();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return error[0];
    }

    public static MessageObject asMessageObject(int account, TLRPC.Message message) {
        LongSparseArray<TLRPC.User> users = new LongSparseArray<>();
        if (message.from_id instanceof TLRPC.TL_peerUser) {
            long id = ((TLRPC.TL_peerUser) message.from_id).user_id;
            TLRPC.User user = MessagesController.getInstance(account).getUser(id);
            if (user != null) {
                users.put(id, user);
            }
        }
        try {
            return new MessageObject(account, message, users, false, false);
        } catch (Exception e) {
            return null;
        }
    }

    public static String mimeFor(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    // ---------- watcher support ----------

    /** Reads the local top message id of a dialog, -1 if the dialog is not loaded. */
    public static long localTopMessageId(int account, long dialogId) {
        final CountDownLatch latch = new CountDownLatch(1);
        final long[] out = {-1};
        AndroidUtilities.runOnUIThread(() -> {
            try {
                TLRPC.Dialog dialog = MessagesController.getInstance(account).dialogs_dict.get(dialogId);
                if (dialog != null) {
                    out[0] = dialog.top_message;
                }
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return out[0];
    }

    public static boolean isLoggedIn(int account) {
        return UserConfig.getInstance(account).isClientActivated();
    }

    /** Compact listing of dialogs for the model. */
    public static String listDialogs(int account, int limit, String filter) {
        final CountDownLatch latch = new CountDownLatch(1);
        final StringBuilder out = new StringBuilder();
        AndroidUtilities.runOnUIThread(() -> {
            try {
                List<TLRPC.Dialog> dialogs = MessagesController.getInstance(account).getAllDialogs();
                int shown = 0;
                String lower = filter == null ? null : filter.toLowerCase();
                for (TLRPC.Dialog dialog : dialogs) {
                    if (shown >= limit) {
                        break;
                    }
                    ResolvedDialog d = describeId(account, dialog.id);
                    if (d == null || d.title == null) {
                        continue;
                    }
                    if (lower != null && !d.title.toLowerCase().contains(lower)
                            && (d.username == null || !d.username.toLowerCase().contains(lower))) {
                        continue;
                    }
                    String type = d.user ? "user" : d.channel ? "channel" : "group";
                    out.append('"').append(d.title.replace("\n", " ")).append("\" — ")
                            .append(type).append(", id ").append(dialog.id)
                            .append(d.username != null ? ", @" + d.username : "")
                            .append('\n');
                    shown++;
                }
                if (out.length() == 0) {
                    out.append("(nothing found)");
                }
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return out.toString();
    }
}
