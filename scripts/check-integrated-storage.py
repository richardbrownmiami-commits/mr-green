#!/usr/bin/env python3
"""Production-SQL host checks; not Android/requery or phone evidence."""
import importlib.util
from pathlib import Path
import sqlite3
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location("phase2_storage",ROOT/"scripts/check-phase2-storage.py")
base=importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)

class IntegratedStorageTests(unittest.TestCase):
    def test_snapshot_is_standalone_and_preserves_original_rows(self):
        db=base.new_database()
        db.execute("PRAGMA user_version=2")
        db.execute("INSERT INTO messages(role,content,created_at) VALUES('user','snapshot sentinel',1)")
        db.commit()
        with tempfile.TemporaryDirectory() as d:
            snapshot=Path(d)/"daily.db"
            db.execute("VACUUM INTO ?",(str(snapshot),))
            db.execute("DELETE FROM messages")
            db.commit()
            restored=sqlite3.connect(snapshot)
            self.assertEqual("ok",restored.execute("PRAGMA quick_check").fetchone()[0])
            self.assertEqual(2,restored.execute("PRAGMA user_version").fetchone()[0])
            self.assertEqual("snapshot sentinel",restored.execute("SELECT content FROM messages").fetchone()[0])
            self.assertEqual(1,restored.execute("SELECT count(*) FROM fts_messages WHERE fts_messages MATCH 'sentinel'").fetchone()[0])
            restored.close()
        db.close()

    def test_vector_ddl_and_bounded_candidate_query_run_against_actual_schema(self):
        db=base.new_database()
        source=(ROOT/"app/src/main/java/im/mrgreen/memory/ConversationMemory.kt").read_text()
        for sql in base.literal_sql(source):
            if sql.startswith("CREATE "):db.execute(sql)
        db.execute("INSERT INTO messages(role,content,created_at) VALUES('user','my green bicycle is near the garage',1)")
        db.execute("INSERT INTO conversation_vectors VALUES(1,'user',1,?,1)",(bytes(1024),))
        import re
        query=re.search(r'"(WITH fts_ids AS[^"\n]+)"',source).group(1)
        rows=db.execute(query,('"bicycle"',"0")).fetchall()
        self.assertEqual(1,len(rows))
        self.assertEqual(1,rows[0][0])
        self.assertIn("LIMIT 512",query)
        self.assertEqual([],db.execute(query,('"bicycle"',"1")).fetchall())
        db.close()

    def test_exact_dedup_preserves_different_tags_sources_and_importance(self):
        db=base.new_database()
        rows=[(1,"same","value","note",0.5,"user",1),(2,"same","value","note",0.5,"user",1),
              (3,"same","value","private",0.5,"user",1),(4,"same","value","note",0.9,"user",1),
              (5,"same","value","note",0.5,"import",1)]
        db.executemany("INSERT INTO facts(id,key,value,tags,importance,source,last_used) VALUES(?,?,?,?,?,?,?)",rows)
        source=(ROOT/"app/src/main/java/im/mrgreen/core/HealthMaintenance.kt").read_text()
        import re
        query=re.search(r'"(DELETE FROM facts WHERE id IN[^"\n]+)"',source).group(1)
        db.execute(query)
        self.assertEqual([1,3,4,5],[r[0] for r in db.execute("SELECT id FROM facts ORDER BY id")])
        db.close()

if __name__=="__main__":unittest.main(verbosity=2)
