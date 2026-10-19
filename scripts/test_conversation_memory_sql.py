#!/usr/bin/env python3
"""Host SQLite checks for the exact SQL literals used by ConversationMemory."""
import pathlib
import re
import sqlite3
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "app/src/main/java/im/mrgreen/memory/ConversationMemory.kt").read_text()


def sql_containing(marker):
    strings = re.findall(r'"((?:[^"\\]|\\.)*)"', SOURCE)
    return next(value.replace('\\"', '"') for value in strings if marker in value)


class ConversationMemorySqlTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript("""
            CREATE TABLE messages(id INTEGER PRIMARY KEY, role TEXT, content TEXT, created_at INTEGER);
            CREATE TABLE conversation_vectors(message_id INTEGER PRIMARY KEY, role TEXT, created_at INTEGER, vector BLOB, norm REAL);
        """)

    def tearDown(self):
        self.db.close()

    def test_sync_balances_recent_indexing_with_old_backlog(self):
        sql = sql_containing("WITH missing AS")
        for i in range(1, 401):
            self.db.execute("INSERT INTO messages VALUES(?,?,?,?)", (i, "user", f"message {i}", i))
        rows = self.db.execute(sql, (64, 64)).fetchall()
        ids = {row[0] for row in rows}
        self.assertEqual(128, len(ids))
        self.assertEqual(set(range(1, 65)), ids & set(range(1, 129)))
        self.assertEqual(set(range(337, 401)), ids & set(range(337, 401)))

    def test_recent_recall_excludes_current_and_returns_latest_exchange_in_order(self):
        sql = sql_containing("SELECT m.id,m.role,substr(m.content,1,600) FROM messages m JOIN conversation_vectors")
        for i, role, text in [(1, "user", "older"), (2, "assistant", "old answer"),
                              (3, "user", "current question"), (4, "assistant", "current answer"),
                              (5, "user", "new current")]:
            self.db.execute("INSERT INTO messages VALUES(?,?,?,?)", (i, role, text, i))
            self.db.execute("INSERT INTO conversation_vectors VALUES(?,?,?,x'00',1)", (i, role, i))
        rows = self.db.execute(sql, (1,)).fetchall()
        self.assertEqual([(3, "user", "current question"), (4, "assistant", "current answer")], rows)

    def test_stale_vector_deletion_predicate_detects_deleted_messages(self):
        self.db.execute("INSERT INTO conversation_vectors VALUES(9,'user',1,x'00',1)")
        self.db.execute("DELETE FROM conversation_vectors WHERE message_id NOT IN (SELECT id FROM messages)")
        self.assertEqual(0, self.db.execute("SELECT count(*) FROM conversation_vectors").fetchone()[0])

    def test_sync_is_bounded_idempotent_and_does_not_index_system_rows(self):
        sql = sql_containing("WITH missing AS")
        for i in range(1, 301):
            self.db.execute("INSERT INTO messages VALUES(?,?,?,?)", (i, "system" if i == 300 else "user", "x" * 1000, i))
        seen = set()
        for _ in range(5):
            rows = self.db.execute(sql, (64, 64)).fetchall()
            self.assertLessEqual(len(rows), 128)
            for mid, role, content, created in rows:
                self.assertNotIn(mid, seen)
                self.assertLessEqual(len(content), 600)
                seen.add(mid)
                self.db.execute("INSERT INTO conversation_vectors VALUES(?,?,?,x'00',1)", (mid, role, created))
        self.assertEqual(set(range(1, 300)), seen)
        self.assertEqual([], self.db.execute(sql, (64, 64)).fetchall())

    def test_fts_can_find_an_indexed_message_older_than_2000_rows(self):
        self.db.execute("CREATE VIRTUAL TABLE fts_messages USING fts5(content)")
        for i in range(1, 2201):
            content = "The bicycle is in the garage" if i == 1 else "unrelated topic"
            self.db.execute("INSERT INTO messages VALUES(?,?,?,?)", (i, "user", content, i))
            self.db.execute("INSERT INTO fts_messages(rowid,content) VALUES(?,?)", (i, content))
            self.db.execute("INSERT INTO conversation_vectors VALUES(?,?,?,x'00',1)", (i, "user", i))
        sql = sql_containing("WITH fts_ids AS")
        rows = self.db.execute(sql, ('"bicycle"', "0")).fetchall()
        self.assertIn(1, {row[0] for row in rows})
        self.assertLessEqual(len(rows), 512)

    def test_messages_and_index_survive_host_database_close_and_reopen(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "memory.db"
            disk = sqlite3.connect(path)
            disk.executescript("CREATE TABLE messages(id INTEGER PRIMARY KEY,role TEXT,content TEXT,created_at INTEGER); CREATE TABLE conversation_vectors(message_id INTEGER PRIMARY KEY,role TEXT,created_at INTEGER,vector BLOB,norm REAL);")
            disk.execute("INSERT INTO messages VALUES(1,'user','My bicycle is green',1)")
            disk.execute("INSERT INTO conversation_vectors VALUES(1,'user',1,x'00',1)")
            disk.commit()
            disk.close()
            reopened = sqlite3.connect(path)
            try:
                self.assertEqual("My bicycle is green", reopened.execute("SELECT content FROM messages").fetchone()[0])
                rows = reopened.execute(sql_containing("SELECT m.id,m.role,substr(m.content,1,600) FROM messages m JOIN conversation_vectors"), (0,)).fetchall()
                self.assertEqual([(1, "user", "My bicycle is green")], rows)
            finally:
                reopened.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)
