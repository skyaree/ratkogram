package app.exteraless.sessionimport;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import com.chaquo.python.PyObject;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LoginActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import app.exteraless.plugins.PythonPluginsEngine;

/**
 * Мост: python-модуль ratko_import (telethon + tdata-парсер) возвращает
 * auth.exportAuthorization для текущего DC нативного клиента; дальше
 * auth.importAuthorization уходит в tgnet без логина (RequestFlagWithoutLogin),
 * ответ подаётся в LoginActivity.onAuthSuccess — вход без SMS.
 */
public final class SessionImport {

    public static final int PICK_SESSION_FILE = 75231;
    public static final int PICK_TDATA_FILE = 75232;

    public interface Listener {
        void onStatus(String status);

        void onSuccess(TLRPC.TL_auth_authorization auth);

        void onError(String error);
    }

    private SessionImport() {
    }

    // ---- python ----

    private static boolean ensurePython(Context context) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Boolean> started = new AtomicReference<>(false);
        PythonPluginsEngine.getInstance().ensureStarted(context, ok -> {
            started.set(ok);
            latch.countDown();
        });
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                return false;
            }
        } catch (InterruptedException e) {
            return false;
        }
        return started.get();
    }

    private static JSONObject callPython(Context context, String function, Object... args)
            throws Exception {
        if (!ensurePython(context)) {
            throw new IllegalStateException("Python-движок не запустился");
        }
        PyObject module = com.chaquo.python.Python.getInstance().getModule("ratko_import");
        String json = module.callAttr(function, args).toJava(String.class);
        return new JSONObject(json);
    }

    // ---- потоки ----

    public static void importFromString(Context context, String sessionString, Listener listener) {
        runThread(context, listener, () -> callPython(context, "import_from_string",
                sessionString, BuildVars.APP_ID, BuildVars.APP_HASH, currentDc()));
    }

    public static void importFromSessionFile(Context context, String path, Listener listener) {
        runThread(context, listener, () -> callPython(context, "import_from_session_file",
                path, BuildVars.APP_ID, BuildVars.APP_HASH, currentDc()));
    }

    public static void importFromTdata(Context context, String path, String passcode, Listener listener) {
        runThread(context, listener, () -> callPython(context, "import_from_tdata",
                path, passcode == null ? "" : passcode, BuildVars.APP_ID, BuildVars.APP_HASH, currentDc()));
    }

    private static int currentDc() {
        try {
            return ConnectionsManager.getInstance(org.telegram.messenger.UserConfig.selectedAccount)
                    .getCurrentDatacenterId();
        } catch (Exception e) {
            return 2;
        }
    }

    private interface PyCall {
        JSONObject call() throws Exception;
    }

    private static void runThread(Context context, Listener listener, PyCall call) {
        new Thread(() -> {
            try {
                JSONObject result = call.call();
                if (!result.optBoolean("ok")) {
                    postError(listener, result.optString("error", "ошибка"));
                    return;
                }
                byte[] bytes = android.util.Base64.decode(result.getString("bytes_b64"), android.util.Base64.NO_WRAP);
                long exportId = result.getLong("id");
                androidStatus(listener, "Получен экспорт авторизации, импортируем…");
                nativeImport(bytes, exportId, listener);
            } catch (Throwable t) {
                FileLog.e("SessionImport failed", t);
                postError(listener, t.getMessage() == null ? String.valueOf(t) : t.getMessage());
            }
        }, "session-import").start();
    }

    private static void androidStatus(Listener listener, String status) {
        AndroidUtilities.runOnUIThread(() -> listener.onStatus(status));
    }

    private static void postError(Listener listener, String error) {
        AndroidUtilities.runOnUIThread(() -> listener.onError(error));
    }

    // ---- нативный вход ----

    private static void nativeImport(byte[] exportBytes, long exportId, Listener listener) {
        TLRPC.TL_auth_importAuthorization request = new TLRPC.TL_auth_importAuthorization();
        request.id = exportId;
        request.bytes = exportBytes;

        RequestDelegate delegate = (response, error) -> {
            if (error != null) {
                postError(listener, error.text == null ? "SERVER_ERROR" : error.text);
                return;
            }
            if (response instanceof TLRPC.TL_auth_authorization) {
                TLRPC.TL_auth_authorization auth = (TLRPC.TL_auth_authorization) response;
                AndroidUtilities.runOnUIThread(() -> listener.onSuccess(auth));
            } else {
                postError(listener, "неожиданный ответ сервера");
            }
        };

        int account = org.telegram.messenger.UserConfig.selectedAccount;
        ConnectionsManager.getInstance(account).sendRequest(request, delegate,
                null, null,
                ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagEnableUnauthorized,
                0, ConnectionsManager.ConnectionTypeGeneric, true);
    }

    /** Завершение входа — дергает приватный onAuthSuccess у LoginActivity. */
    public static void finishLogin(LoginActivity activity, TLRPC.TL_auth_authorization auth) {
        try {
            Method method = LoginActivity.class.getDeclaredMethod("onAuthSuccess", TLRPC.TL_auth_authorization.class);
            method.setAccessible(true);
            method.invoke(activity, auth);
        } catch (Throwable t) {
            FileLog.e("SessionImport: cannot finish login", t);
        }
    }

    // ---- SAF ----

    public static String copyToCache(Context context, Uri uri, String name) throws Exception {
        File dir = new File(context.getCacheDir(), "session_import");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        for (File old : dir.listFiles()) {
            old.delete();
        }
        File target = new File(dir, name);
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                throw new IllegalStateException("не удалось открыть файл");
            }
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
        return target.getAbsolutePath();
    }
}
