#!/usr/bin/env python3
"""Yardhal sync service. Bind loopback and terminate HTTPS at an operator reverse proxy.

create-account prints the account UUID and one-time enrollment secret; store the secret
securely. serve never exposes a non-loopback listener and does not log HTTP requests.
Back up the SQLite database (including its WAL) with SQLite's online backup API.
"""

import argparse
import base64
import hashlib
import hmac
import ipaddress
import json
import os
import re
import secrets
import sqlite3
import sys
import uuid
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MAX_REQUEST = 256 * 1024
MAX_PAGE = 200
MAX_ENTITIES = 100_000
MAX_OPERATIONS = 1_000_000
LIVE_KIND_LIMITS = {"theme": 100, "accent": 1000}
UUID_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z")
GROUP_RE = re.compile(r"g-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z")
HOST_RE = re.compile(r"[a-zA-Z0-9][a-zA-Z0-9._-]{0,252}\Z")
B64_RE = re.compile(r"[A-Za-z0-9_-]{1,512}\Z")
URL_RE = re.compile(
    r"(?i)(?:[a-z][a-z0-9+.-]{1,31}://|\b(?:https?|ftp|file|content|data|irc|ircs|yardhal|magnet|"
    r"mailto|tel|sms|javascript|blob|s3|ipfs):|www\.)"
)
COLOR_FIELDS = {"background", "primary", "secondary", "tertiary", "surfaceVariant"}
NETWORK_FIELDS = {"id", "name", "host", "port", "tls", "nick", "username", "realName", "autojoin",
                  "alternateNicks", "saslAuthcid", "saslMode", "nickServAccount", "nickServService",
                  "waitForNickServ", "mode", "bouncerParentId", "bouncerNetId", "zncNetwork"}
KINDS = {"network", "order", "pin", "group", "membership", "mute", "theme", "selection",
         "accent", "avatar", "read", "reply", "reaction", "attachment", "redaction"}


class ApiError(Exception):
    def __init__(self, status, code, message):
        super().__init__(message)
        self.status = status
        self.code = code


def invalid(message="Invalid sync request"):
    raise ApiError(400, "invalid_request", message)


def integer(value, minimum=0, maximum=2**63 - 1):
    return type(value) is int and minimum <= value <= maximum


def identity(value):
    return isinstance(value, str) and UUID_RE.fullmatch(value) is not None

def group_identity(value):
    return identity(value) or isinstance(value, str) and GROUP_RE.fullmatch(value) is not None


def text(value, maximum=2048, empty=False, characters=512, reject_url=False):
    if not isinstance(value, str) or not (empty or value) or len(value) > characters:
        return False
    try:
        size = len(value.encode("utf-8"))
    except UnicodeError:
        return False
    return (size <= maximum and not any(ord(ch) < 32 or ord(ch) == 127 for ch in value)
            and (not reject_url or URL_RE.search(value) is None))


def required_object(value, fields, required=None):
    return isinstance(value, dict) and set(value).issubset(fields) and (required or fields).issubset(value)


def component(value, reject_url=False):
    if not isinstance(value, str) or not B64_RE.fullmatch(value):
        return False
    try:
        decoded = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4)).decode("utf-8")
    except (ValueError, UnicodeError):
        return False
    return (text(decoded, 1024, characters=256, reject_url=reject_url) and
            base64.urlsafe_b64encode(decoded.encode()).decode().rstrip("=") == value)


def valid_key(kind, key):
    if not text(key, 512) or len(key.split("/")) > 5:
        return False
    parts = key.split("/")
    if kind == "network":
        return len(parts) == 1 and identity(parts[0])
    if kind == "group":
        return len(parts) == 1 and group_identity(parts[0])
    if kind == "theme":
        return len(parts) == 1 and (identity(key) or re.fullmatch(r"bundled-[0-9]{1,4}", key) is not None)
    if kind in {"selection", "avatar"}:
        return key == "global"
    if kind == "order":
        return ((len(parts) == 2 and parts[0] == "network" and identity(parts[1])) or
                (len(parts) == 2 and parts[0] == "group" and group_identity(parts[1])) or
                (len(parts) == 3 and parts[0] == "channel" and identity(parts[1]) and component(parts[2])))
    if not identity(parts[0]):
        return False
    expected = 5 if kind == "reaction" else 3 if kind in {"reply", "attachment", "redaction"} else 2
    return len(parts) == expected and all(
        component(part, reject_url=index > 1)
        for index, part in enumerate(parts[1:], start=1)
    )


def valid_value(kind, key, value):
    if value is None:
        return True
    try:
        if len(content(value).encode("utf-8")) > 32 * 1024:
            return False
    except (ValueError, UnicodeError, TypeError):
        return False
    fields = {
        "order": {"after"}, "pin": {"enabled"}, "group": {"name"},
        "membership": {"group"}, "mute": {"enabled"}, "theme": {"name", "light", "dark"},
        "selection": {"id"}, "accent": {"color"}, "avatar": {"show"},
        "read": {"timestampMs"}, "reply": {"parentMsgid"}, "reaction": {"present"},
        "attachment": {"name", "mimeType", "sizeBytes", "width", "height"},
        "redaction": {"deleted"},
    }
    if kind == "network":
        if not required_object(value, NETWORK_FIELDS, {"id", "name", "host", "port", "tls", "nick"}):
            return False
        host = value["host"]
        if (not isinstance(host, str) or not host or len(host) > 512 or
                any(ord(ch) < 32 or ord(ch) == 127 for ch in host) or
                "/" in host or "\\" in host or "@" in host):
            return False
        bracketed = host.startswith("[") and host.endswith("]")
        try:
            ipaddress.ip_address(host[1:-1] if bracketed else host)
            host_valid = True
        except ValueError:
            if bracketed:
                host_valid = False
            else:
                try:
                    hostname = host.encode("idna").decode("ascii")
                except UnicodeError:
                    return False
                host_valid = HOST_RE.fullmatch(hostname) is not None
        if not (identity(value["id"]) and key == value["id"] and host_valid and
                text(value["name"]) and integer(value["port"], 1, 65535) and
                type(value["tls"]) is bool and text(value["nick"])):
            return False
        for field in ("username", "realName", "saslAuthcid", "nickServAccount", "nickServService", "bouncerNetId", "zncNetwork"):
            if field in value and value[field] is not None and not text(value[field], empty=True):
                return False
        if "bouncerParentId" in value and value["bouncerParentId"] is not None and not identity(value["bouncerParentId"]):
            return False
        for field in ("autojoin", "alternateNicks"):
            if field in value and (not isinstance(value[field], list) or len(value[field]) > 200 or
                                   not all(text(item, empty=True) for item in value[field])):
                return False
        for field in ("saslMode", "mode"):
            options = {"AUTO", "PLAIN", "SCRAM_SHA_256", "EXTERNAL"} if field == "saslMode" else {"DIRECT", "SOJU", "ZNC"}
            if field in value and (not isinstance(value[field], str) or value[field] not in options):
                return False
        return "waitForNickServ" not in value or type(value["waitForNickServ"]) is bool
    if not required_object(value, fields[kind]):
        return False
    if kind == "order":
        return value["after"] is None or (
            text(value["after"], 512) and value["after"] != key and valid_key("order", value["after"]) and
            value["after"].split("/")[0] == key.split("/")[0] and
            (not key.startswith("channel/") or value["after"].split("/")[1] == key.split("/")[1])
        )
    if kind in {"pin", "mute"}:
        return type(value["enabled"]) is bool
    if kind == "group":
        return text(value["name"])
    if kind == "membership":
        return value["group"] is None or group_identity(value["group"])
    if kind == "selection":
        return (value["id"] is None or identity(value["id"]) or
                isinstance(value["id"], str) and re.fullmatch(r"bundled-[0-9]{1,4}", value["id"]) is not None)
    if kind == "avatar":
        return type(value["show"]) is bool
    if kind == "accent":
        return integer(value["color"], 0xFF000000, 0xFFFFFFFF)
    if kind == "read":
        return integer(value["timestampMs"])
    if kind == "reply":
        return text(value["parentMsgid"], reject_url=True)
    if kind == "reaction":
        return type(value["present"]) is bool
    if kind == "redaction":
        return value["deleted"] is True
    if kind == "attachment":
        return ((value["name"] is None or
                 (text(value["name"], empty=True, reject_url=True) and "/" not in value["name"] and "\\" not in value["name"])) and
                (value["mimeType"] is None or
                 (text(value["mimeType"], empty=True, reject_url=True) and "\\" not in value["mimeType"])) and
                all(value[field] is None or integer(value[field], 0, 2**63 - 1)
                    for field in ("sizeBytes", "width", "height")))
    if kind == "theme":
        if not text(value["name"], 320, characters=80) or (value["light"] is None and value["dark"] is None):
            return False
        for side in ("light", "dark"):
            variant = value[side]
            if variant is None:
                continue
            if not required_object(variant, {"name", "dark", "colors"}) or variant["name"] != value["name"] or variant["dark"] != (side == "dark"):
                return False
            colors = variant["colors"]
            if not required_object(colors, COLOR_FIELDS) or not all(integer(c, 0xFF000000, 0xFFFFFFFF) for c in colors.values()):
                return False
        return True
    return False


def parse_json(payload):
    def pairs(items):
        obj = {}
        for key, value in items:
            if key in obj:
                invalid("Duplicate JSON key")
            obj[key] = value
        return obj
    try:
        return json.loads(payload.decode("utf-8"), object_pairs_hook=pairs,
                          parse_constant=lambda _: invalid("Non-finite JSON number"))
    except (ValueError, UnicodeError, RecursionError):
        invalid("Invalid UTF-8 JSON")


@contextmanager
def connect(path):
    db = sqlite3.connect(path, timeout=10, isolation_level=None)
    try:
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("PRAGMA busy_timeout=10000")
        with db:
            yield db
    finally:
        db.close()


def initialize(path):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    with connect(path) as db:
        db.execute("PRAGMA journal_mode=WAL")
        # Keep tombstones and operation UUIDs until account deletion: advancing
        # a download cursor never proves an upload acknowledgment was received.
        db.executescript("""
            CREATE TABLE IF NOT EXISTS accounts(
                id TEXT PRIMARY KEY, secret_hash BLOB NOT NULL, epoch TEXT NOT NULL,
                revision INTEGER NOT NULL DEFAULT 0, log_floor INTEGER NOT NULL DEFAULT 0,
                entity_count INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS devices(
                account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                id TEXT NOT NULL, token_hash BLOB NOT NULL UNIQUE, revoked INTEGER NOT NULL DEFAULT 0,
                cursor INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(account,id)
            );
            CREATE TABLE IF NOT EXISTS entities(
                account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                kind TEXT NOT NULL, key TEXT NOT NULL, value TEXT, seq INTEGER NOT NULL,
                origin TEXT NOT NULL, PRIMARY KEY(account,kind,key)
            );
            CREATE TABLE IF NOT EXISTS changes(
                account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                seq INTEGER NOT NULL, kind TEXT NOT NULL, key TEXT NOT NULL,
                value TEXT, origin TEXT NOT NULL, PRIMARY KEY(account,seq)
            );
            CREATE TABLE IF NOT EXISTS operations(
                account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                id TEXT NOT NULL, origin TEXT NOT NULL, digest BLOB NOT NULL,
                accepted INTEGER NOT NULL, reason TEXT, PRIMARY KEY(account,id)
            );
            CREATE INDEX IF NOT EXISTS changes_account ON changes(account,seq);
        """)


def create_account(path):
    initialize(path)
    account, secret = str(uuid.uuid4()), secrets.token_hex(32)
    with connect(path) as db:
        db.execute("INSERT INTO accounts(id,secret_hash,epoch) VALUES(?,?,?)",
                   (account, hashlib.sha256(bytes.fromhex(secret)).digest(), str(uuid.uuid4())))
    return {"account": account, "secret": secret}

def rotate_epoch(path, account):
    if not identity(account):
        invalid("Expected account UUID")
    initialize(path)
    epoch = str(uuid.uuid4())
    with connect(path) as db:
        db.execute("BEGIN IMMEDIATE")
        updated = db.execute("UPDATE accounts SET epoch=? WHERE id=?", (epoch, account))
        if not updated.rowcount:
            raise ApiError(404, "not_found", "Account not found")
        db.execute("UPDATE devices SET cursor=0 WHERE account=?", (account,))
        db.commit()
    return {"account": account, "epoch": epoch}


def check_secret(row, secret):
    if not isinstance(secret, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", secret):
        return False
    return hmac.compare_digest(row["secret_hash"], hashlib.sha256(bytes.fromhex(secret)).digest())


def authenticate(db, header):
    if not isinstance(header, str) or re.fullmatch(r"Bearer [0-9a-fA-F]{64}", header) is None:
        raise ApiError(401, "unauthorized", "Valid device token required")
    token_hash = hashlib.sha256(bytes.fromhex(header[7:])).digest()
    device = db.execute("SELECT * FROM devices WHERE token_hash=? AND revoked=0", (token_hash,)).fetchone()
    valid = hmac.compare_digest(device["token_hash"] if device is not None else bytes(32), token_hash)
    if device is None or not valid:
        raise ApiError(401, "unauthorized", "Valid device token required")
    return device

def require_active_device(db, device):
    current = db.execute("SELECT token_hash,revoked FROM devices WHERE account=? AND id=?",
                         (device["account"], device["id"])).fetchone()
    matches = hmac.compare_digest(
        current["token_hash"] if current is not None else bytes(32), device["token_hash"],
    )
    if current is None or current["revoked"] or not matches:
        raise ApiError(401, "unauthorized", "Device token was revoked")


def enroll(db, data):
    if not required_object(data, {"account", "secret", "device"}) or not identity(data["account"]) or not identity(data["device"]):
        invalid("Expected account, enrollment secret and device UUID")
    db.execute("BEGIN IMMEDIATE")
    row = db.execute("SELECT * FROM accounts WHERE id=?", (data["account"],)).fetchone()
    valid = check_secret(row if row is not None else {"secret_hash": bytes(32)}, data["secret"])
    if row is None or not valid:
        raise ApiError(401, "unauthorized", "Invalid account or enrollment secret")
    token = secrets.token_hex(32)
    db.execute("INSERT INTO devices(account,id,token_hash,revoked,cursor) VALUES(?,?,?,0,0) "
               "ON CONFLICT(account,id) DO UPDATE SET token_hash=excluded.token_hash,revoked=0,cursor=0",
               (row["id"], data["device"], hashlib.sha256(bytes.fromhex(token)).digest()))
    db.commit()
    return {"token": token, "epoch": row["epoch"]}


def content(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def download(db, account, since, limit, snapshot=False):
    if snapshot:
        rows = db.execute(
            "SELECT seq,kind,key,value,origin FROM entities WHERE account=? AND seq>? ORDER BY seq LIMIT ?",
            (account["id"], since, limit + 1),
        ).fetchall()
    else:
        # After log compaction, current entity rows at/below the floor form a
        # virtual log. This also lets a new device paginate a snapshot across
        # the compaction floor without silently omitting offline metadata.
        rows = db.execute(
            "SELECT seq,kind,key,value,origin FROM entities WHERE account=? AND seq>? AND seq<=? "
            "UNION ALL SELECT seq,kind,key,value,origin FROM changes WHERE account=? AND seq>? "
            "ORDER BY seq LIMIT ?",
            (account["id"], since, account["log_floor"], account["id"],
             max(since, account["log_floor"]), limit + 1),
        ).fetchall()
    selected = rows[:limit]
    has_more = len(rows) > limit
    cursor = selected[-1]["seq"] if has_more else account["revision"]
    return {"epoch": account["epoch"], "cursor": cursor,
            "changes": [{"seq": row["seq"], "kind": row["kind"], "key": row["key"],
                         "value": json.loads(row["value"]) if row["value"] is not None else None,
                         "origin": row["origin"]} for row in selected], "hasMore": has_more}


def conflict(db, account, kind, key, value, base, current):
    parts = key.split("/")
    network = parts[1] if kind == "order" and parts[0] in {"network", "channel"} else parts[0]
    if kind != "network" and identity(network):
        parent = db.execute("SELECT value FROM entities WHERE account=? AND kind='network' AND key=?",
                            (account, network)).fetchone()
        if parent is not None and parent["value"] is None and value is not None:
            return "network_deleted", None
    if kind == "network" and value is not None and value.get("bouncerParentId"):
        parent = db.execute("SELECT value FROM entities WHERE account=? AND kind='network' AND key=?",
                            (account, value["bouncerParentId"])).fetchone()
        if parent is not None and parent["value"] is None:
            return "network_deleted", None
    if kind == "order" and parts[0] == "group" and value is not None:
        group = db.execute("SELECT value FROM entities WHERE account=? AND kind='group' AND key=?",
                           (account, parts[1])).fetchone()
        if group is not None and group["value"] is None:
            return "group_deleted", None
    if kind in {"network", "group", "theme"} and current is not None and current["value"] is None and value is not None:
        return "identity_deleted", None
    if kind in {"reply", "attachment", "reaction"} and value is not None:
        parent_key = "/".join(key.split("/")[:3])
        redaction = db.execute("SELECT value FROM entities WHERE account=? AND kind='redaction' AND key=?",
                               (account, parent_key)).fetchone()
        if redaction is not None:
            return "message_deleted", None
    if kind == "redaction" and current is not None and value is not None:
        return (None, False) if current["value"] == content(value) else ("message_deleted", None)
    if kind == "reply" and current is not None:
        return (None, False) if current["value"] == content(value) else ("reply_immutable", None)
    if kind == "read" and current is not None and current["value"] is not None and value is not None:
        if json.loads(current["value"])["timestampMs"] >= value["timestampMs"]:
            return None, False
    if kind == "reaction" and current is not None and current["seq"] > base and value is not None and value["present"]:
        previous = None if current["value"] is None else json.loads(current["value"])
        if previous is None or not previous["present"]:
            return "stale_reaction_add", None
    if current is not None and current["value"] is None and value is not None and current["seq"] > base:
        return "stale_tombstone", None
    if kind == "redaction" and value is None:
        return "redaction_immutable", None
    if kind == "read" and value is None:
        return "read_immutable", None
    if current is not None and current["value"] == (content(value) if value is not None else None):
        return None, False
    return None, True


def descendants(db, account, root):
    by_parent = {}
    rows = db.execute("SELECT key,value FROM entities WHERE account=? AND kind='network' AND value IS NOT NULL",
                      (account,)).fetchall()
    for row in rows:
        parent = json.loads(row["value"]).get("bouncerParentId")
        if parent is not None:
            by_parent.setdefault(parent, []).append(row["key"])
    queue, seen, ordered = [root], {root}, []
    for ancestor in queue:
        for child in sorted(by_parent.get(ancestor, ())):
            if child not in seen:
                seen.add(child)
                queue.append(child)
                ordered.append(child)
    return ordered


def apply_changes(db, account, device, requested):
    accepted, rejected = [], []
    revision, count = account["revision"], account["entity_count"]
    op_count = db.execute("SELECT COUNT(*) FROM operations WHERE account=?", (account["id"],)).fetchone()[0] if requested else 0
    for change in requested:
        if not isinstance(change, dict) or set(change) != {"id", "kind", "key", "value", "base"} or not identity(change["id"]):
            invalid("Each change needs an operation UUID, kind, key, value and base revision")
        op_id = change["id"]
        if not isinstance(change["kind"], str) or not isinstance(change["key"], str) or not integer(change["base"]):
            reason = "invalid_change"
        elif change["kind"] not in KINDS or not valid_key(change["kind"], change["key"]) or not valid_value(change["kind"], change["key"], change["value"]):
            reason = "invalid_change"
        elif change["base"] > revision:
            reason = "future_revision"
        else:
            reason = None
        digest = hashlib.sha256(json.dumps(
            {field: change[field] for field in ("kind", "key", "value")},
            ensure_ascii=True, sort_keys=True, separators=(",", ":"),
        ).encode("ascii")).digest()
        previous = db.execute("SELECT origin,digest,accepted,reason FROM operations WHERE account=? AND id=?",
                              (account["id"], op_id)).fetchone()
        if previous is not None:
            if previous["origin"] != device["id"] or not hmac.compare_digest(previous["digest"], digest):
                rejected.append({"id": op_id, "reason": "id_reused"})
            elif previous["accepted"]:
                accepted.append(op_id)
            else:
                rejected.append({"id": op_id, "reason": previous["reason"]})
            continue
        if op_count >= MAX_OPERATIONS:
            # There is no safe TTL for operation UUIDs: an offline device may
            # retry a lost acknowledgment at any time. Downloads and old-ID
            # retries remain available; move to a new account to resume writes.
            rejected.append({"id": op_id, "reason": "operation_quota"})
            continue
        if reason is None:
            kind, key, value, base = (change[field] for field in ("kind", "key", "value", "base"))
            current = db.execute("SELECT value,seq FROM entities WHERE account=? AND kind=? AND key=?",
                                 (account["id"], kind, key)).fetchone()
            if current is None and count >= MAX_ENTITIES:
                reason = "account_quota"
            else:
                reason, update = conflict(db, account["id"], kind, key, value, base, current)
                if reason is None and update and value is not None and kind in LIVE_KIND_LIMITS and (
                    current is None or current["value"] is None
                ):
                    active = db.execute(
                        "SELECT COUNT(*) FROM entities WHERE account=? AND kind=? AND value IS NOT NULL",
                        (account["id"], kind),
                    ).fetchone()[0]
                    if active >= LIVE_KIND_LIMITS[kind]:
                        reason = kind + "_quota"
                if reason is None and update:
                    revision += 1
                    encoded = content(value) if value is not None else None
                    db.execute("INSERT INTO entities(account,kind,key,value,seq,origin) VALUES(?,?,?,?,?,?) "
                               "ON CONFLICT(account,kind,key) DO UPDATE SET value=excluded.value,seq=excluded.seq,origin=excluded.origin",
                               (account["id"], kind, key, encoded, revision, device["id"]))
                    db.execute("INSERT INTO changes(account,seq,kind,key,value,origin) VALUES(?,?,?,?,?,?)",
                               (account["id"], revision, kind, key, encoded, device["id"]))
                    if current is None:
                        count += 1
                    if kind == "network" and value is None:
                        for child in descendants(db, account["id"], key):
                            revision += 1
                            db.execute(
                                "UPDATE entities SET value=NULL,seq=?,origin=? WHERE account=? AND kind='network' AND key=?",
                                (revision, device["id"], account["id"], child),
                            )
                            db.execute(
                                "INSERT INTO changes(account,seq,kind,key,value,origin) VALUES(? ,?,'network',?,NULL,?)",
                                (account["id"], revision, child, device["id"]),
                            )
        db.execute("INSERT INTO operations(account,id,origin,digest,accepted,reason) VALUES(?,?,?,?,?,?)",
                   (account["id"], op_id, device["id"], digest, int(reason is None), reason))
        op_count += 1
        if reason is None:
            accepted.append(op_id)
        else:
            rejected.append({"id": op_id, "reason": reason})
    db.execute("UPDATE accounts SET revision=?,entity_count=? WHERE id=?", (revision, count, account["id"]))
    return accepted, rejected


def sync(db, device, data):
    if not required_object(data, {"since", "epoch", "changes", "limit"}, {"since", "changes", "limit"}):
        invalid("Expected since, epoch, changes and limit")
    since, epoch, requested, limit = data["since"], data.get("epoch"), data["changes"], data["limit"]
    if not integer(since) or not integer(limit, 1, MAX_PAGE) or not isinstance(requested, list) or len(requested) > MAX_PAGE or not (epoch is None or identity(epoch)):
        invalid("Invalid cursor, epoch, page size or upload size")
    db.execute("BEGIN IMMEDIATE")
    require_active_device(db, device)
    account = db.execute("SELECT * FROM accounts WHERE id=?", (device["account"],)).fetchone()
    if (epoch is not None and epoch != account["epoch"] or
            epoch is None and (since != 0 or requested) or since > account["revision"]):
        # Never apply uploads until the client has fetched the current epoch's
        # snapshot. Keep 409 tiny: Android reads only its first 4096 bytes.
        raise ApiError(409, "epoch_mismatch", account["epoch"])
    if since > device["cursor"]:
        db.execute("UPDATE devices SET cursor=? WHERE account=? AND id=?", (since, device["account"], device["id"]))
    accepted, rejected = apply_changes(db, account, device, requested)
    account = db.execute("SELECT * FROM accounts WHERE id=?", (device["account"],)).fetchone()
    result = download(db, account, since, limit, snapshot=since == 0)
    result.update({"accepted": accepted, "rejected": rejected})
    # An offline device holds the floor indefinitely. Once every active device has
    # advanced, old log records are redundant; the latest entities (including
    # tombstones) always remain for a fresh device or a reset snapshot.
    floor = db.execute("SELECT MIN(cursor) AS position FROM devices WHERE account=? AND revoked=0",
                       (device["account"],)).fetchone()["position"]
    if floor is not None and floor > account["log_floor"]:
        db.execute("DELETE FROM changes WHERE account=? AND seq<=?", (device["account"], floor))
        db.execute("UPDATE accounts SET log_floor=? WHERE id=?", (floor, device["account"]))
    db.commit()
    return result


class Handler(BaseHTTPRequestHandler):
    db_path = None
    protocol_version = "HTTP/1.1"

    def setup(self):
        super().setup()
        self.connection.settimeout(20)

    def log_message(self, *_args):
        # Paths, headers and request bodies may contain credentials; never log them.
        pass

    def respond(self, status, data):
        if status >= 400:
            self.close_connection = True
        payload = content(data).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        if status >= 400:
            self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self):
        try:
            if self.path not in {"/v1/enroll", "/v1/sync", "/v1/device/revoke", "/v1/account/delete"}:
                raise ApiError(404, "not_found", "Unknown endpoint")
            if self.headers.get("Transfer-Encoding") or self.headers.get("Content-Type", "").split(";")[0].strip().lower() != "application/json":
                invalid("Use Content-Type application/json and Content-Length")
            lengths = self.headers.get_all("Content-Length", [])
            if len(lengths) != 1 or not lengths[0].isdecimal():
                invalid("Exactly one Content-Length required")
            length = lengths[0]
            if len(length) > 8 or int(length) > MAX_REQUEST:
                raise ApiError(413, "request_too_large", "Request exceeds 256 KiB")
            data = parse_json(self.rfile.read(int(length)))
            with connect(self.db_path) as db:
                if self.path == "/v1/enroll":
                    result = enroll(db, data)
                else:
                    device = authenticate(db, self.headers.get("Authorization"))
                    if self.path == "/v1/sync":
                        result = sync(db, device, data)
                    elif self.path == "/v1/device/revoke":
                        if data != {}:
                            invalid("Expected empty object")
                        db.execute("BEGIN IMMEDIATE")
                        require_active_device(db, device)
                        db.execute("UPDATE devices SET revoked=1 WHERE account=? AND id=?", (device["account"], device["id"]))
                        db.commit()
                        result = {"ok": True}
                    else:
                        if not required_object(data, {"secret"}):
                            invalid("Expected enrollment secret")
                        db.execute("BEGIN IMMEDIATE")
                        require_active_device(db, device)
                        account = db.execute("SELECT * FROM accounts WHERE id=?", (device["account"],)).fetchone()
                        if account is None or not check_secret(account, data["secret"]):
                            raise ApiError(403, "forbidden", "Invalid enrollment secret")
                        db.execute("DELETE FROM accounts WHERE id=?", (device["account"],))
                        db.commit()
                        result = {"ok": True}
            self.respond(200, result)
        except ApiError as error:
            if error.code == "epoch_mismatch":
                self.respond(error.status, {"error": error.code, "epoch": str(error)})
            else:
                self.respond(error.status, {"error": error.code, "message": str(error)})
        except (sqlite3.OperationalError, sqlite3.DatabaseError):
            self.respond(503, {"error": "unavailable", "message": "Sync storage unavailable; retry later"})
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            self.close_connection = True

    def do_GET(self):
        self.respond(405, {"error": "method_not_allowed", "message": "Use POST"})


class LoopbackServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main(argv=None):
    os.umask(0o077)
    parser = argparse.ArgumentParser(description="Self-hosted Yardhal sync; TLS reverse proxy required for remote access")
    parser.add_argument("--db", required=True, help="SQLite database path; protect it and back it up")
    actions = parser.add_subparsers(dest="command", required=True)
    actions.add_parser("create-account", help="Print account UUID and one-time 32-byte secret")
    rotate = actions.add_parser("rotate-epoch", help="Require every device to refetch account snapshot; keep outboxes")
    rotate.add_argument("--account", required=True, help="Account UUID to reset after restoring backup")
    serve = actions.add_parser("serve", help="Listen on loopback HTTP behind an operator TLS reverse proxy")
    serve.add_argument("--port", type=int, default=8765)
    args = parser.parse_args(argv)
    if args.command == "create-account":
        print(content(create_account(args.db)))
    elif args.command == "rotate-epoch":
        try:
            print(content(rotate_epoch(args.db, args.account)))
        except ApiError as error:
            parser.error(str(error))
    else:
        if not 1 <= args.port <= 65535:
            parser.error("port must be 1..65535")
        initialize(args.db)
        Handler.db_path = args.db
        # Never allow configurable bind addresses: all off-host traffic must pass
        # through the operator's HTTPS reverse proxy with a valid certificate.
        with LoopbackServer(("127.0.0.1", args.port), Handler) as server:
            print(f"Yardhal sync listening on 127.0.0.1:{args.port}; configure an HTTPS reverse proxy", file=sys.stderr)
            server.serve_forever()


if __name__ == "__main__":
    main()
