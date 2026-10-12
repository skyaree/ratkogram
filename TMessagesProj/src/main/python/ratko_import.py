# -*- coding: utf-8 -*-
"""Ratkogram: импорт Telegram-сессий (tdata / Telethon .session / StringSession).

Флоу:
  1) достаём auth_key + dc сессии (tdata расшифровывается локально,
     telethon-сессии читаются напрямую);
  2) подключаемся этой сессией как нормальный MTProto-клиент (telethon);
  3) auth.exportAuthorization(target_dc) -> bytes;
  4) Java-сторона вызывает auth.importAuthorization в нативном tgnet
     (RequestFlagWithoutLogin) и получает полноценный вход.

Схемы шифрования tdata — по исходникам Telegram Desktop
(storage_file_utilities.cpp / mtproto_auth_key.cpp / main_account.cpp).
"""

import base64
import hashlib
import hmac
import json
import os
import struct
import zipfile

# ----------------------------------------------------------------------------
# DC-адреса (публичная статическая таблица, порты 443)
# ----------------------------------------------------------------------------

DC_ADDRESSES = {
    1: "149.154.175.53",
    2: "149.154.167.51",
    3: "149.154.175.100",
    4: "149.154.167.91",
    5: "91.108.56.130",
}


def _err(message):
    return json.dumps({"ok": False, "error": message})


def _ok(payload):
    payload["ok"] = True
    return json.dumps(payload)


# ----------------------------------------------------------------------------
# Telethon-флоу: экспорт авторизации для целевого DC
# ----------------------------------------------------------------------------

def _export(session, api_id, api_hash, target_dc):
    import asyncio
    from telethon import TelegramClient
    from telethon.tl.functions.auth import ExportAuthorizationRequest

    loop = asyncio.new_event_loop()
    try:
        asyncio.set_event_loop(loop)
        client = TelegramClient(session, api_id, api_hash, loop=loop)
        if not loop.run_until_complete(client.connect()):
            return {"ok": False, "error": "не удалось подключиться с этой сессией"}
        try:
            if not loop.run_until_complete(client.is_user_authorized()):
                return {"ok": False, "error": "сессия не авторизована (revoked?) — нужен вход заново"}
            me = loop.run_until_complete(client.get_me())
            result = loop.run_until_complete(client(ExportAuthorizationRequest(target_dc)))
            return {
                "ok": True,
                "id": result.id,
                "bytes_b64": base64.b64encode(bytes(result.bytes)).decode("ascii"),
                "user_id": me.id,
                "first_name": me.first_name or "",
                "username": (me.username or "") if hasattr(me, "username") else "",
                "phone": (me.phone or "") if getattr(me, "phone", None) else "",
            }
        finally:
            loop.run_until_complete(client.disconnect())
    finally:
        loop.close()


def import_from_string(session_string, api_id, api_hash, target_dc):
    """StringSession Telethon (строка «1BQANOTEu...»)."""
    try:
        from telethon.sessions import StringSession
        session = StringSession(session_string.strip())
    except Exception as e:
        return _err("не похоже на StringSession: %s" % e)
    try:
        return _export(session, api_id, api_hash, target_dc)
    except Exception as e:
        return _err(str(e))


def import_from_session_file(path, api_id, api_hash, target_dc):
    """Файл .session Telethon (sqlite)."""
    try:
        from telethon.sessions import SQLiteSession
        session = SQLiteSession(path)
    except Exception as e:
        return _err("не удалось открыть .session: %s" % e)
    try:
        return _export(session, api_id, api_hash, target_dc)
    except Exception as e:
        return _err(str(e))


def import_from_tdata(path, passcode, api_id, api_hash, target_dc):
    """Папка tdata (распакованная) или zip-архив с ней.

    Поддерживаются tdata без пароля и с локальным passcode.
    """
    try:
        folder = _extract_tdata(path)
        info = _parse_tdata(folder, passcode or "")
        dc_id = info["dc_id"]
        auth_key = bytes(info["auth_key"])
        if dc_id not in DC_ADDRESSES:
            return _err("неизвестный DC %s" % dc_id)
        from telethon.sessions import MemorySession
        from telethon.crypto import AuthKey
        session = MemorySession()
        session.set_dc(dc_id, DC_ADDRESSES[dc_id], 443)
        session.auth_key = AuthKey(auth_key)
        result = _export(session, api_id, api_hash, target_dc)
        if result.get("ok") and not result.get("user_id"):
            result["user_id"] = info.get("user_id") or 0
        return result
    except TdataError as e:
        return _err(str(e))
    except Exception as e:
        return _err("tdata: %s" % e)


# ----------------------------------------------------------------------------
# tdata-парсер
# ----------------------------------------------------------------------------

class TdataError(Exception):
    pass


def _extract_tdata(path):
    """path — папка с tdata либо zip. Возвращает путь к папке, где лежит map."""
    path = str(path)
    if os.path.isdir(path):
        if os.path.exists(os.path.join(path, "map0")) or \
           os.path.exists(os.path.join(path, "maps")) or \
           os.path.exists(os.path.join(path, "map")):
            return path
        for name in os.listdir(path):
            inner = os.path.join(path, name)
            if os.path.isdir(inner) and (
                os.path.exists(os.path.join(inner, "map0")) or
                os.path.exists(os.path.join(inner, "maps"))
            ):
                return inner
        raise TdataError("в выбранной папке нет файлов map/map0 (это точно tdata?)")
    if path.lower().endswith(".zip") or os.path.isfile(path):
        base = path + "_extracted"
        with zipfile.ZipFile(path) as zf:
            zf.extractall(base)
        names = [n for n in zf.namelist() if n.endswith("map0") or n.endswith("maps")]
        if not any("tdata" in n.replace("\\", "/").split("/") for n in names):
            inner = base
            # самозахват: ищем подпапку с map-файлами
            for root, dirs, _ in os.walk(base):
                if os.path.exists(os.path.join(root, "map0")) or \
                   os.path.exists(os.path.join(root, "maps")):
                    inner = root
                    break
            return inner
        return base
    raise TdataError("непонятный путь: %s" % path)


def _find_map_file(folder):
    for name in ("map0", "map1", "maps", "map"):
        candidate = os.path.join(folder, name)
        if os.path.exists(candidate):
            return candidate
    raise TdataError("map-файл не найден")


def _read_tdf(path):
    """TDF$-файл: magic, version(int32 BE), данные, md5-подпись 16 байт."""
    raw = open(path, "rb").read()
    if len(raw) < 4 + 4 + 16 or raw[:4] != b"TDF$":
        raise TdataError("битый TDF-файл: %s" % os.path.basename(path))
    version = int.from_bytes(raw[4:8], "big")
    payload = raw[8:-16]
    signature = raw[-16:]
    check = hashlib.md5(
        payload
        + len(payload).to_bytes(4, "little")
        + version.to_bytes(4, "little")
        + raw[:4]
    ).digest()
    if check != signature:
        raise TdataError("подпись TDF не сошлась: %s" % os.path.basename(path))
    return payload


class _Reader(object):
    """QDataStream (Big-Endian, Qt_5_1)."""

    def __init__(self, data):
        self.data = data
        self.pos = 0

    def _take(self, n):
        if self.pos + n > len(self.data):
            raise TdataError("неожиданный конец данных")
        chunk = self.data[self.pos:self.pos + n]
        self.pos += n
        return chunk

    def u32(self):
        return int.from_bytes(self._take(4), "big")

    def i32(self):
        return struct.unpack(">i", self._take(4))[0]

    def u64(self):
        return int.from_bytes(self._take(8), "big")

    def qba(self):
        length = self.u32()
        if length == 0xFFFFFFFF or length == 0x7FFFFFFF:
            return None
        return self._take(length)


def _pbkdf2_sha1(password, salt, iterations, length):
    return hashlib.pbkdf2_hmac("sha1", password, salt, iterations, length)


def _prepare_aes_old_mtp(auth_key, msg_key, x=8):
    """AuthKey::prepareAES_oldmtp (recv: x = 8)."""
    sha1_a = hashlib.sha1(msg_key + auth_key[x:x + 32]).digest()
    sha1_b = hashlib.sha1(auth_key[32 + x:48 + x] + msg_key + auth_key[48 + x:64 + x]).digest()
    sha1_c = hashlib.sha1(auth_key[64 + x:96 + x] + msg_key).digest()
    sha1_d = hashlib.sha1(msg_key + auth_key[96 + x:128 + x]).digest()
    aes_key = sha1_a[:8] + sha1_b[8:20] + sha1_c[4:16]
    aes_iv = sha1_a[8:20] + sha1_b[:8] + sha1_c[16:20] + sha1_d[:8]
    return aes_key, aes_iv


def _aes_ige_decrypt(data, key, iv):
    """AES-256-IGE (используем движок telethon: telethon.crypto.aes.AES)."""
    from telethon.crypto.aes import AES
    return AES(key).decrypt_ige(data, iv)


def _decrypt_local(encrypted, auth_key_256):
    """DecryptLocal: первые 16 байт — msgKey (первые 16 SHA1 расшифрованного).

    Расшифрованный блок: [uint32 LE dataLen][полезные байты...][padding].
    EncryptedDescriptor.stream читает с offset 4, размер данных = dataLen.
    """
    if encrypted is None or len(encrypted) <= 16 or (len(encrypted) & 0x0F):
        raise TdataError("битый зашифрованный блок")
    msg_key = encrypted[:16]
    body = encrypted[16:]
    aes_key, aes_iv = _prepare_aes_old_mtp(auth_key_256, msg_key)
    decrypted = _aes_ige_decrypt(body, aes_key, aes_iv)
    if hashlib.sha1(decrypted).digest()[:16] != msg_key:
        raise TdataError("неверный пароль tdata или повреждённые данные")
    data_len = int.from_bytes(decrypted[:4], "little")
    if data_len < 4 or data_len > len(decrypted):
        raise TdataError("битая длина блока")
    return decrypted[4:data_len]


def _to_file_part(key8):
    """ToFilePart: 16 hex-цифр от uint64, нибблы от младшего к старшему."""
    val = int.from_bytes(key8, "little")
    return "".join("%X" % ((val >> (4 * i)) & 0xF) for i in range(16))


def _data_file_candidates(folder, data_name="data"):
    digest = hashlib.md5(data_name.encode("utf-8")).digest()
    part = _to_file_part(digest[:8])
    names = [part, part + "s", part + "0", part + "1", data_name, data_name + "0", data_name + "s"]
    out = []
    for n in names:
        p = os.path.join(folder, n)
        if os.path.exists(p) and p not in out:
            out.append(p)
    return out


def _legacy_local_key(passcode, salt):
    # CreateLegacyLocalKey: PBKDF2-HMAC-SHA1, без пароля 1 итерация
    return _pbkdf2_sha1(passcode.encode("utf-8"), salt, 1 if not passcode else 100000, 256)


def _parse_tdata(folder, passcode):
    map_payload = _read_tdf(_find_map_file(folder))
    reader = _Reader(map_payload)
    legacy_salt = reader.qba()
    legacy_key_encrypted = reader.qba()
    map_encrypted = reader.qba()
    if not legacy_salt or len(legacy_salt) != 32:
        raise TdataError("битая соль в map-файле")

    # без пароля localKey зашифрован legacy-ключом от пустого passcode
    if passcode:
        legacy_key = _legacy_local_key(passcode, legacy_salt)
    else:
        legacy_key = _legacy_local_key("", legacy_salt)

    key_block = _decrypt_local(legacy_key_encrypted, legacy_key)
    key_reader = _Reader(key_block)
    inner = key_reader.qba()
    if inner and len(inner) == 256:
        local_key = inner
    elif len(key_block) == 256:
        local_key = key_block
    else:
        raise TdataError("не удалось прочесть localKey")

    # сам map расшифровываем localKey'ем — нужен только чтобы подтвердить
    # пароль (расшифровка падает, если пароль неверный)
    _decrypt_local(map_encrypted, local_key)

    # файл data: dbiMtpAuthorization
    data_payload = None
    for candidate in _data_file_candidates(folder):
        try:
            data_payload = _read_tdf(candidate)
            break
        except TdataError:
            continue
    if data_payload is None:
        raise TdataError("файл data (mtp-авторизация) в tdata не найден")

    data_reader = _Reader(data_payload)
    encrypted_blob = data_reader.qba()
    descriptor = _decrypt_local(encrypted_blob, local_key)
    descriptor_reader = _Reader(descriptor)
    block_id = descriptor_reader.i32()
    if block_id != 0x4B:  # dbiMtpAuthorization
        raise TdataError("в файле data нет mtp-авторизации (блок 0x%x)" % block_id)
    serialized = descriptor_reader.qba()

    s_reader = _Reader(serialized)
    wide_tag = s_reader.u64()
    if wide_tag != 0xFFFFFFFFFFFFFFFF:
        raise TdataError("неизвестный формат mtp-авторизации (старый tdata не поддержан)")
    user_id = s_reader.u64()
    main_dc = s_reader.i32()
    count = s_reader.i32()
    keys = {}
    for _ in range(max(0, count)):
        dc = s_reader.i32()
        key = s_reader._take(256)
        keys[dc] = key
    if main_dc not in keys:
        if not keys:
            raise TdataError("в tdata нет auth-ключей")
        main_dc = sorted(keys.keys())[0]
    return {
        "dc_id": main_dc,
        "auth_key": keys[main_dc],
        "user_id": user_id,
    }
