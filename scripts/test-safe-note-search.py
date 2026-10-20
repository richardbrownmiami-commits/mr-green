#!/usr/bin/env python3
"""Exercise the actual Kotlin search SQL on host SQLite; not Android/JNI testing."""
import json
import pathlib
import re
import sqlite3
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = (ROOT / 'app/src/main/java/im/mrgreen/actions/ActionRuntime.kt').read_text()
SEARCH = SOURCE.split('private fun searchNotes(', 1)[1].split('private fun storageSummary(', 1)[0]
SQL = json.loads('"' + re.search(r'rawQuery\("((?:\\.|[^"\\])*)"', SEARCH).group(1) + '"')


def literal_pattern(query):
    return '%' + query.strip()[:120].replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_') + '%'


class SafeNoteSearch(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.execute('CREATE TABLE facts(id INTEGER PRIMARY KEY, value TEXT, tags TEXT)')

    def tearDown(self):
        self.db.close()

    def insert(self, value, tag='note'):
        self.db.execute('INSERT INTO facts(value,tags) VALUES(?,?)', (value, tag))

    def results(self, query):
        return [row[0] for row in self.db.execute(SQL, (literal_pattern(query),))]

    def test_wildcards_and_backslash_are_literal(self):
        self.insert('discount 20% off'); self.insert('discount 200 off')
        self.insert('file_name'); self.insert('fileXname'); self.insert(r'path\name')
        self.assertEqual(['discount 20% off'], self.results('20%'))
        self.assertEqual(['file_name'], self.results('file_'))
        self.assertEqual([r'path\name'], self.results('\\'))
        self.assertIn('replace("%", "\\\\%")', SEARCH)
        self.assertIn('replace("_", "\\\\_")', SEARCH)

    def test_search_is_parameterized_and_private_note_only(self):
        self.insert("a quote ' OR 1=1 --")
        self.insert('private chat secret', 'chat')
        self.insert('unrelated note')
        self.assertEqual(["a quote ' OR 1=1 --"], self.results("' OR 1=1 --"))
        self.assertEqual([], self.results('private chat secret'))

    def test_newest_ten_results_without_modifying_existing_rows(self):
        for n in range(30):
            self.insert(f'groceries {n}')
        self.assertEqual([f'groceries {n}' for n in range(29, 19, -1)], self.results('groceries'))
        self.assertEqual(30, self.db.execute('SELECT count(*) FROM facts').fetchone()[0])


if __name__ == '__main__':
    unittest.main(verbosity=2)
