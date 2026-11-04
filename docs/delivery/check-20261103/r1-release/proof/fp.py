"""Structure fingerprint of a scratch schema (loopback only)."""
import sys, json, hashlib, pymysql
def fp(schema, port=3316):
    c = pymysql.connect(host="127.0.0.1", port=port, user="root", password="", database=schema)
    cur = c.cursor(); out = {}
    cur.execute("SELECT table_name, engine, table_collation FROM information_schema.tables WHERE table_schema=%s ORDER BY 1", (schema,))
    out["tables"] = [list(r) for r in cur.fetchall()]
    cur.execute("SELECT table_name, column_name, ordinal_position, column_type, is_nullable, column_default, extra, collation_name, column_comment FROM information_schema.columns WHERE table_schema=%s ORDER BY 1,3", (schema,))
    out["columns"] = [[str(x) for x in r] for r in cur.fetchall()]
    cur.execute("SELECT table_name, index_name, non_unique, seq_in_index, column_name FROM information_schema.statistics WHERE table_schema=%s ORDER BY 1,2,4", (schema,))
    out["indexes"] = [[str(x) for x in r] for r in cur.fetchall()]
    c.close()
    out["digest"] = {k: hashlib.sha256(json.dumps(v, ensure_ascii=False).encode()).hexdigest()[:16] for k, v in out.items()}
    return out
if __name__ == "__main__":
    d = fp(sys.argv[1]); json.dump(d, open(sys.argv[2], "w"), ensure_ascii=False)
    print(sys.argv[1], len(d["tables"]), "tables", len(d["columns"]), "columns", len(d["indexes"]), "index cols", d["digest"])
