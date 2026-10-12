#!/usr/bin/env python3
"""Run with: python3 -m unittest discover -s scripts -p 'test_sync_server.py'."""

import base64
import http.client
import importlib.util
import json
import os
import sqlite3
import tempfile
import threading
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

MODULE_PATH = Path(__file__).with_name("sync-server.py")
SPEC = importlib.util.spec_from_file_location("yardhal_sync_server", MODULE_PATH)
sync_server = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sync_server)


def uid():
    return str(uuid.uuid4())


def encoded(text):
    return base64.urlsafe_b64encode(text.encode()).decode().rstrip("=")


class ServiceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.database = os.path.join(self.tmp.name, "sync.db")
        self.account = sync_server.create_account(self.database)
        self.start()
        self.device_id, self.token, self.epoch = self.enroll(self.account)

    def start(self):
        class IsolatedHandler(sync_server.Handler):
            db_path = self.database

        self.server = sync_server.LoopbackServer(("127.0.0.1", 0), IsolatedHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def stop(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def tearDown(self):
        self.stop()

    def post(self, route, value, token=None, raw=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        body = json.dumps(value).encode() if raw is None else raw
        headers = {"Content-Type": "application/json", "Content-Length": str(len(body))}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        try:
            connection.request("POST", route, body, headers)
            result = connection.getresponse()
            return result.status, json.loads(result.read())
        finally:
            connection.close()

    def enroll(self, account, device=None):
        device = device or uid()
        status, response = self.post("/v1/enroll", {**account, "device": device})
        self.assertEqual(status, 200, response)
        return device, response["token"], response["epoch"]

    def sync(self, token=None, since=0, changes=(), limit=200, epoch=None):
        return self.post("/v1/sync", {"since": since, "epoch": self.epoch if epoch is None else epoch,
                                      "changes": changes, "limit": limit}, token or self.token)

    def op(self, kind, key, value, base=0, operation_id=None):
        return {"id": operation_id or uid(), "kind": kind, "key": key, "value": value, "base": base}

    def test_auth_isolation_and_token_hashes(self):
        other = sync_server.create_account(self.database)
        _, other_token, other_epoch = self.enroll(other)
        group = self.op("group", uid(), {"name": "private"})
        status, first = self.sync(changes=[group])
        self.assertEqual(status, 200)
        self.assertEqual(first["accepted"], [group["id"]])
        status, second = self.sync(token=other_token, epoch=other_epoch)
        self.assertEqual((status, second["changes"]), (200, []))
        self.assertEqual(self.post("/v1/sync", {}, "0" * 64)[0], 401)
        self.assertEqual(self.post("/v1/enroll", {"account": self.account["account"],
                                                    "device": uid(), "secret": "0" * 64})[0], 401)
        with sync_server.connect(self.database) as db:
            dump = " ".join(str(row) for row in db.iterdump())
            self.assertNotIn(self.token, dump)
            self.assertNotIn(self.account["secret"], dump)

    def test_idempotent_ack_across_server_restart_and_conflicting_reuse(self):
        item = self.op("group", uid(), {"name": "first"})
        status, original = self.sync(changes=[item])
        self.assertEqual(status, 200)
        self.stop()
        self.start()
        status, repeated = self.sync(changes=[item])
        self.assertEqual(status, 200)
        self.assertEqual(repeated["accepted"], [item["id"]])
        self.assertEqual(repeated["cursor"], original["cursor"])
        altered = {**item, "value": {"name": "different"}}
        status, reused = self.sync(changes=[altered])
        self.assertEqual(status, 200)
        self.assertEqual(reused["rejected"], [{"id": item["id"], "reason": "id_reused"}])

    def test_stale_delete_and_parent_tombstone_never_revive(self):
        network = uid()
        identity = {"id": network, "name": "net", "host": "irc.example.org", "port": 6697,
                    "tls": True, "nick": "guest"}
        _, first = self.sync(changes=[self.op("network", network, identity)])
        self.assertEqual(first["cursor"], 1)
        _, deleted = self.sync(changes=[self.op("network", network, None, base=1)])
        self.assertEqual(deleted["cursor"], 2)
        _, stale = self.sync(changes=[self.op("network", network, identity, base=1),
                                           self.op("pin", network + "/" + encoded("#channel"), {"enabled": True}, base=1)])
        self.assertEqual({entry["reason"] for entry in stale["rejected"]}, {"identity_deleted", "network_deleted"})
        with sync_server.connect(self.database) as db:
            row = db.execute("SELECT value FROM entities WHERE kind='network' AND key=?", (network,)).fetchone()
            self.assertIsNone(row["value"])

    def test_parent_network_delete_cascades_derived_children_atomically(self):
        parent, child, grandchild = uid(), uid(), uid()
        def network(key, ancestor=None):
            return {"id": key, "name": key, "host": "irc.example.org", "port": 6697,
                    "tls": True, "nick": "n", "bouncerParentId": ancestor}
        self.sync(changes=[self.op("network", parent, network(parent)),
                           self.op("network", child, network(child, parent)),
                           self.op("network", grandchild, network(grandchild, child))])
        status, deleted = self.sync(changes=[self.op("network", parent, None, base=3)])
        self.assertEqual(status, 200)
        self.assertEqual(deleted["cursor"], 6)
        self.assertEqual([(row["seq"], row["key"]) for row in deleted["changes"]][-3:],
                         [(4, parent), (5, child), (6, grandchild)])
        orphan = uid()
        _, stale = self.sync(changes=[
            self.op("network", child, network(child, parent), base=3),
            self.op("order", "channel/" + child + "/" + encoded("#test"), {"after": None}, base=3),
            self.op("network", orphan, network(orphan, parent), base=3),
        ])
        self.assertEqual([entry["reason"] for entry in stale["rejected"]],
                         ["network_deleted", "network_deleted", "network_deleted"])
        with sync_server.connect(self.database) as db:
            network_rows = db.execute("SELECT key,value FROM entities WHERE kind='network'").fetchall()
            self.assertEqual({row["key"] for row in network_rows}, {parent, child, grandchild})
            self.assertTrue(all(row["value"] is None for row in network_rows))

    def test_concurrent_disjoint_keys_and_same_key_server_order(self):
        _, second_token, _ = self.enroll(self.account)
        first_key, second_key = uid(), uid()
        barrier = threading.Barrier(3)
        responses = []
        def upload(token, change):
            barrier.wait()
            responses.append(self.sync(token=token, changes=[change]))
        jobs = [threading.Thread(target=upload, args=(self.token, self.op("group", first_key, {"name": "one"}))),
                threading.Thread(target=upload, args=(second_token, self.op("group", second_key, {"name": "two"})))]
        for job in jobs:
            job.start()
        barrier.wait()
        for job in jobs:
            job.join(timeout=5)
        self.assertEqual([status for status, _ in responses], [200, 200])
        _, snapshot = self.sync()
        self.assertEqual({entry["value"]["name"] for entry in snapshot["changes"]}, {"one", "two"})
        same_key = uid()
        self.sync(changes=[self.op("group", same_key, {"name": "base"})])
        barrier = threading.Barrier(3)
        responses.clear()
        jobs = [threading.Thread(target=upload, args=(token, self.op("group", same_key, {"name": label}, base=0)))
                for token, label in ((self.token, "A"), (second_token, "B"))]
        for job in jobs:
            job.start()
        barrier.wait()
        for job in jobs:
            job.join(timeout=5)
        _, snapshot = self.sync()
        edits = [entry for entry in snapshot["changes"] if entry["key"] == same_key]
        self.assertEqual(len(edits), 1)
        self.assertIn(edits[0]["value"]["name"], {"A", "B"})
        self.assertTrue(all(result["accepted"] for _, result in responses))

    def test_max_read_immutable_reply_reaction_and_redaction(self):
        network = uid()
        conversation = network + "/" + encoded("#chan")
        message = conversation + "/" + encoded("message-1")
        reaction = message + "/" + encoded("🦊") + "/" + encoded("alice")
        _, initial = self.sync(changes=[self.op("read", conversation, {"timestampMs": 100}),
                                                self.op("reply", message, {"parentMsgid": "parent"}),
                                                self.op("reaction", reaction, {"present": True})])
        self.assertEqual(len(initial["accepted"]), 3)
        _, removed = self.sync(changes=[self.op("reaction", reaction, {"present": False}, base=0),
                                                self.op("read", conversation, {"timestampMs": 80}),
                                                self.op("reply", message, {"parentMsgid": "other"})])
        self.assertIn("reply_immutable", [entry["reason"] for entry in removed["rejected"]])
        _, stale = self.sync(changes=[self.op("reaction", reaction, {"present": True}, base=0),
                                              self.op("redaction", message, {"deleted": True})])
        self.assertIn("stale_reaction_add", [entry["reason"] for entry in stale["rejected"]])
        _, same_redaction = self.sync(changes=[self.op("redaction", message, {"deleted": True})])
        self.assertEqual(len(same_redaction["accepted"]), 1)
        self.assertEqual(same_redaction["cursor"], stale["cursor"])
        _, after = self.sync(changes=[self.op("attachment", message,
                                              {"name": "file", "mimeType": "image/png", "sizeBytes": 12,
                                               "width": 2, "height": 2})])
        self.assertEqual(after["rejected"][0]["reason"], "message_deleted")
        with sync_server.connect(self.database) as db:
            marker = db.execute("SELECT value FROM entities WHERE kind='read'").fetchone()
            self.assertEqual(json.loads(marker["value"])["timestampMs"], 100)

    def test_paginated_snapshot_and_log_compaction_with_offline_device(self):
        _, second_token, _ = self.enroll(self.account)
        for number in range(7):
            self.sync(changes=[self.op("group", uid(), {"name": str(number)})])
        cursor, received = 0, []
        for _ in range(8):
            _, page = self.sync(token=second_token, since=cursor, limit=2)
            received.extend(page["changes"])
            cursor = page["cursor"]
            if not page["hasMore"]:
                break
        self.assertEqual(len({record["key"] for record in received}), 7)
        self.assertEqual(cursor, 7)
        with sync_server.connect(self.database) as db:
            self.assertEqual(db.execute("SELECT log_floor FROM accounts").fetchone()[0], 0)
        self.sync(since=cursor)
        self.sync(token=second_token, since=cursor)
        with sync_server.connect(self.database) as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM changes").fetchone()[0], 0)
        _, snapshot = self.sync(since=0)
        self.assertEqual(len(snapshot["changes"]), 7)
        _, new_token, _ = self.enroll(self.account)
        cursor, fresh = 0, []
        for _ in range(8):
            status, page = self.sync(token=new_token, since=cursor, limit=2)
            self.assertEqual(status, 200)
            fresh.extend(page["changes"])
            cursor = page["cursor"]
            if not page["hasMore"]:
                break
        self.assertEqual({row["key"] for row in fresh}, {row["key"] for row in snapshot["changes"]})

    def test_transaction_rolls_back_on_failure_after_apply_and_before_ack(self):
        operation = self.op("group", uid(), {"name": "recover"})
        request = {"since": 0, "epoch": self.epoch, "changes": [operation], "limit": 200}
        with self.assertRaises(RuntimeError):
            with sync_server.connect(self.database) as db:
                device = sync_server.authenticate(db, "Bearer " + self.token)
                with patch.object(sync_server, "download", side_effect=RuntimeError("simulated crash")):
                    sync_server.sync(db, device, request)
        with sync_server.connect(self.database) as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM operations").fetchone()[0], 0)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM entities").fetchone()[0], 0)
        _, applied = self.sync(changes=[operation])
        self.assertEqual(applied["accepted"], [operation["id"]])
        _, repeated = self.sync(changes=[operation])
        self.assertEqual(repeated["cursor"], applied["cursor"])

    def test_epoch_mismatch_does_not_apply_upload(self):
        operation = self.op("group", uid(), {"name": "offline"})
        status, reply = self.sync(changes=[operation], epoch=uid())
        self.assertEqual((status, reply["error"], reply["epoch"]), (409, "epoch_mismatch", self.epoch))
        with sync_server.connect(self.database) as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM operations").fetchone()[0], 0)
        _, snapshot = self.sync(changes=[])
        self.assertEqual(snapshot["changes"], [])
        _, upload = self.sync(changes=[operation])
        self.assertEqual(upload["accepted"], [operation["id"]])

    def test_operator_epoch_rotation_preserves_snapshot_and_outbox_retry(self):
        operation = self.op("group", "g-" + uid(), {"name": "portable"})
        _, applied = self.sync(changes=[operation])
        self.assertEqual(applied["accepted"], [operation["id"]])
        old_epoch = self.epoch
        rotated = sync_server.rotate_epoch(self.database, self.account["account"])
        self.assertNotEqual(rotated["epoch"], old_epoch)
        status, mismatch = self.sync(changes=[self.op("group", uid(), {"name": "offline"})])
        self.assertEqual((status, mismatch["error"], mismatch["epoch"]),
                         (409, "epoch_mismatch", rotated["epoch"]))
        self.epoch = rotated["epoch"]
        status, snapshot = self.sync(changes=[])
        self.assertEqual(status, 200)
        self.assertEqual(snapshot["changes"][0]["key"], operation["key"])
        replay = {**operation, "base": snapshot["cursor"]}
        _, response = self.sync(since=snapshot["cursor"], changes=[replay])
        self.assertEqual(response["accepted"], [operation["id"]])
        self.assertEqual(response["cursor"], applied["cursor"])

    def test_token_rotated_after_authentication_cannot_write(self):
        with sync_server.connect(self.database) as db:
            stale_device = sync_server.authenticate(db, "Bearer " + self.token)
            self.enroll(self.account, self.device_id)
            request = {"since": 0, "epoch": self.epoch, "changes": [
                self.op("group", uid(), {"name": "must-not-write"}),
            ], "limit": 12}
            with self.assertRaises(sync_server.ApiError) as failure:
                sync_server.sync(db, stale_device, request)
            self.assertEqual((failure.exception.status, failure.exception.code), (401, "unauthorized"))
        with sync_server.connect(self.database) as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM entities").fetchone()[0], 0)

    def test_device_revoke_reenroll_and_full_account_delete(self):
        device2, token2, _ = self.enroll(self.account)
        self.assertEqual(self.post("/v1/device/revoke", {}, token2), (200, {"ok": True}))
        self.assertEqual(self.sync(token=token2)[0], 401)
        _, renewed, _ = self.enroll(self.account, device2)
        self.assertEqual(self.sync(token=renewed)[0], 200)
        self.assertEqual(self.post("/v1/account/delete", {"secret": "0" * 64}, renewed)[0], 403)
        self.assertEqual(self.post("/v1/account/delete", {"secret": self.account["secret"]}, renewed),
                         (200, {"ok": True}))
        self.assertEqual(self.sync()[0], 401)
        self.assertEqual(self.sync(token=renewed)[0], 401)
        self.assertEqual(self.post("/v1/enroll", {**self.account, "device": uid()})[0], 401)
        with sync_server.connect(self.database) as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM accounts").fetchone()[0], 0)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM operations").fetchone()[0], 0)

    def test_live_theme_and_accent_quotas_preserve_tombstones(self):
        colors = {key: 0xFF000000 for key in sync_server.COLOR_FIELDS}
        theme = {"name": "safe", "light": {"name": "safe", "dark": False, "colors": colors}, "dark": None}
        first, second, third = uid(), uid(), uid()
        with patch.dict(sync_server.LIVE_KIND_LIMITS, {"theme": 2, "accent": 1}):
            _, created = self.sync(changes=[self.op("theme", first, theme),
                                             self.op("theme", second, theme)])
            self.assertEqual(len(created["accepted"]), 2)
            _, full = self.sync(changes=[self.op("theme", third, theme)])
            self.assertEqual(full["rejected"][0]["reason"], "theme_quota")
            self.sync(changes=[self.op("theme", first, None)])
            _, free = self.sync(changes=[self.op("theme", third, theme)])
            self.assertEqual(len(free["accepted"]), 1)
            _, deleted = self.sync(changes=[self.op("theme", first, theme)])
            self.assertEqual(deleted["rejected"][0]["reason"], "identity_deleted")
            network = uid()
            accent_a, accent_b = network + "/" + encoded("#a"), network + "/" + encoded("#b")
            self.sync(changes=[self.op("accent", accent_a, {"color": 0xFF123456})])
            _, accent_full = self.sync(changes=[self.op("accent", accent_b, {"color": 0xFF123456})])
            self.assertEqual(accent_full["rejected"][0]["reason"], "accent_quota")
            self.sync(changes=[self.op("accent", accent_a, None)])
            _, accent_reused = self.sync(changes=[self.op("accent", accent_b, {"color": 0xFF123456})])
            self.assertEqual(len(accent_reused["accepted"]), 1)
        with sync_server.connect(self.database) as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM entities WHERE kind='theme'").fetchone()[0], 3)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM entities WHERE kind='accent'").fetchone()[0], 2)

    def test_operation_quota_bounds_deduplication_without_losing_old_replays(self):
        first = self.op("group", uid(), {"name": "one"})
        second = self.op("group", uid(), {"name": "two"})
        blocked = self.op("group", uid(), {"name": "three"})
        with patch.object(sync_server, "MAX_OPERATIONS", 2):
            _, first_batch = self.sync(changes=[first, second, blocked])
            self.assertEqual(first_batch["accepted"], [first["id"], second["id"]])
            self.assertEqual(first_batch["rejected"], [{"id": blocked["id"], "reason": "operation_quota"}])
            status, replay = self.sync(changes=[first, blocked])
            self.assertEqual(status, 200)
            self.assertEqual(replay["accepted"], [first["id"]])
            self.assertEqual(replay["rejected"], [{"id": blocked["id"], "reason": "operation_quota"}])
            self.assertEqual(replay["cursor"], 2)
            self.assertEqual(self.post("/v1/device/revoke", {}, self.token), (200, {"ok": True}))
        with sync_server.connect(self.database) as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM operations").fetchone()[0], 2)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM entities").fetchone()[0], 2)

    def test_quota_and_strict_schema_filters_without_banning_safe_names(self):
        with patch.object(sync_server, "MAX_ENTITIES", 1):
            self.sync(changes=[self.op("group", uid(), {"name": "first"})])
            _, quota = self.sync(changes=[self.op("group", uid(), {"name": "second"})])
            self.assertEqual(quota["rejected"][0]["reason"], "account_quota")
        key = uid()
        forbidden = [
            self.op("network", key, {"id": key, "name": "n", "host": "irc.example.org", "port": 6697,
                                     "tls": True, "nick": "n", "serverPasswordRef": "secret"}),
            self.op("group", uid(), {"name": "okay", "body": "chat text"}),
            self.op("attachment", uid() + "/" + encoded("#c") + "/" + encoded("m"),
                    {"name": "a", "mimeType": "a/b", "sizeBytes": 3, "width": None,
                     "height": None, "url": "https://example.org"}),
            self.op("network", key, {"id": key, "name": "n", "host": "irc.example.org",
                                     "port": 6697, "tls": True, "nick": "n", "mode": []}),
            self.op("selection", "global", {"id": []}),
            self.op("theme", uid(), {"name": "x", "light": {"name": "x", "dark": False,
                                                           "colors": {"background": 0xFF000000, "body": "secret"}},
                                     "dark": None}),
        ]
        _, result = self.sync(changes=forbidden)
        self.assertEqual(len(result["rejected"]), len(forbidden))
        self.assertEqual({entry["reason"] for entry in result["rejected"]}, {"invalid_change"})
        colors = {field: 0xFF112233 for field in sync_server.COLOR_FIELDS}
        title = "News https://example.org"
        allowed = [
            self.op("network", key, {"id": key, "name": title, "host": "www.irc.example.org",
                                     "port": 6697, "tls": True, "nick": "news", "autojoin": ["#www.example.org"]}),
            self.op("group", uid(), {"name": "Articles https://example.org"}),
            self.op("theme", uid(), {"name": title,
                                    "light": {"name": title, "dark": False, "colors": colors}, "dark": None}),
            self.op("pin", key + "/" + encoded("#www.example.org"), {"enabled": True}),
        ]
        _, portable = self.sync(changes=allowed)
        self.assertEqual(portable["accepted"], [change["id"] for change in allowed])
        self.assertEqual(portable["rejected"], [])
        forbidden_msgid = key + "/" + encoded("#www.example.org") + "/" + encoded("https://example.org")
        _, opaque = self.sync(changes=[self.op("reply", forbidden_msgid, {"parentMsgid": "parent"})])
        self.assertEqual(opaque["rejected"][0]["reason"], "invalid_change")
        self.assertEqual(self.post("/v1/sync", {}, self.token, raw=b"{\"since\":0,\"since\":1}")[0], 400)
        too_large = b"{" + b" " * sync_server.MAX_REQUEST + b"}"
        self.assertEqual(self.post("/v1/sync", {}, self.token, raw=too_large)[0], 413)


if __name__ == "__main__":
    unittest.main()
