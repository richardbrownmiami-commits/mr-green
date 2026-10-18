#!/usr/bin/env python3
"""Host SQLite SQL checks, NOT Android/requery or Redmi runtime evidence."""
import pathlib
import re
import sqlite3
import unittest

SOURCE = (pathlib.Path(__file__).resolve().parents[1] / 'app/src/main/java/im/mrgreen/data/GreenStore.kt').read_text()


def literal_sql(block):
    return [bytes(value, 'utf-8').decode('unicode_escape') for value in re.findall(r'db\.execSQL\("([^"\n]+)"\)', block)]


def new_database():
    db = sqlite3.connect(':memory:')
    block = SOURCE.split('override fun onCreate(db: SQLiteDatabase) {', 1)[1].split('private fun createFts', 1)[0]
    for sql in literal_sql(block):
        db.execute(sql)
    for table, columns in re.findall(r'createFts\(db, "([^"]+)", "([^"]+)"\)', block):
        # Same compile-time substitutions as GreenStore.createFts; use its literal SQL.
        names = [value.strip() for value in columns.split(',')]
        values = {'table': table, 'columns': columns,
                  'newValues': ','.join('new.' + value for value in names),
                  'oldValues': ','.join('old.' + value for value in names)}
        fts_block = SOURCE.split('private fun createFts(', 1)[1].split('override fun onUpgrade', 1)[0]
        for sql in literal_sql(fts_block):
            for name, value in values.items():
                sql = sql.replace('${' + name + '}', value).replace('$' + name, value)
            db.execute(sql)
    return db


class StorageSqlTests(unittest.TestCase):
    def test_additive_v1_to_v2_preserves_data_and_fts(self):
        db = new_database()
        db.execute("INSERT INTO messages(role,content,created_at) VALUES('user','sentinel memory',1)")
        db.execute("INSERT INTO facts(id,key,value) VALUES(1,'sentinel','keep me')")
        db.execute("INSERT INTO cloud_log VALUES(1,'groq','P1','chat',10,1)")
        before = dict(db.execute("SELECT name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'"))
        upgrade = SOURCE.split('override fun onUpgrade(', 1)[1].split('private fun createCloudCache', 1)[0]
        self.assertIn('oldVersion == 1 && newVersion == 2', upgrade)
        self.assertNotRegex(upgrade.upper(), r'DROP|DELETE|REPLACE')
        cache_block = SOURCE.split('private fun createCloudCache', 1)[1].split('/**', 1)[0]
        for sql in literal_sql(cache_block):
            db.execute(sql)
        after = dict(db.execute("SELECT name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'"))
        for key, value in before.items():
            self.assertEqual(value, after[key])
        self.assertEqual('sentinel memory', db.execute('SELECT content FROM messages').fetchone()[0])
        self.assertEqual('keep me', db.execute('SELECT value FROM facts').fetchone()[0])
        self.assertEqual(1, db.execute("SELECT count(*) FROM fts_messages WHERE fts_messages MATCH 'sentinel'").fetchone()[0])
        self.assertEqual(1, db.execute('SELECT count(*) FROM cloud_log').fetchone()[0])
        self.assertIn('cloud_cache', after)

    def test_cloud_log_is_metadata_only(self):
        db = new_database()
        columns = {row[1] for row in db.execute('PRAGMA table_info(cloud_log)')}
        self.assertEqual({'id','provider','data_class','category','size_bytes','created_at'}, columns)
        self.assertIn('require(dataClass == "P0" || dataClass == "P1")', SOURCE)
        self.assertIn('require(category in CLOUD_CATEGORIES)', SOURCE)

    def test_cache_expiration_and_bounds_sql(self):
        db = new_database()
        cache_block = SOURCE.split('private fun createCloudCache', 1)[1].split('/**', 1)[0]
        for sql in literal_sql(cache_block):
            db.execute(sql)
        for i in range(35):
            db.execute('INSERT INTO cloud_cache VALUES(?,?,?)', (f'digest-{i}', 'reply', i))
        sql = re.search(r'db.execSQL\("(DELETE FROM cloud_cache WHERE digest NOT IN[^"\n]+)"\)', SOURCE).group(1)
        db.execute(sql)
        self.assertEqual(30, db.execute('SELECT count(*) FROM cloud_cache').fetchone()[0])
        db.execute('DELETE FROM cloud_cache WHERE expires <= ?', (20,))
        self.assertEqual(14, db.execute('SELECT count(*) FROM cloud_cache').fetchone()[0])
        self.assertIn('ttl.coerceIn(1L, MAX_CACHE_TTL_MS)', SOURCE)
        self.assertIn('5 * 60 * 1000L', SOURCE)
        self.assertIn('MessageDigest.getInstance("SHA-256")', SOURCE)


if __name__ == '__main__':
    unittest.main(verbosity=2)
