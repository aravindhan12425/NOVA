"""
Builds Nova's offline dictionary (app/src/main/assets/dictionary/wordnet.db) from WordNet 3.0.

Usage:
    python tools/build_dictionary.py <path to the unzipped "wordnet" folder>

WordNet data: https://wordnet.princeton.edu (the NLTK copy works too:
https://raw.githubusercontent.com/nltk/nltk_data/gh-pages/packages/corpora/wordnet.zip)

Output tables:
    entries(lemma, pos, senses, defs)   defs = up to 3 short definitions, most common first, joined by "\n"
    exc(form, base, pos)                irregular forms, e.g. "went" -> "go"
"""
import os
import shutil
import sqlite3
import sys

POS_FILES = {"n": "noun", "v": "verb", "a": "adj", "r": "adv"}
MAX_DEFS = 3


def read_glosses(path):
    glosses = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("  "):  # licence header
                continue
            head, _, gloss = line.partition(" | ")
            offset = head.split(" ", 1)[0]
            definition = gloss.split('; "')[0].strip().rstrip(";").strip()
            glosses[offset] = definition
    return glosses


def main(src, out):
    if os.path.exists(out):
        os.remove(out)
    db = sqlite3.connect(out)
    db.execute("CREATE TABLE entries(lemma TEXT NOT NULL, pos TEXT NOT NULL, senses INTEGER NOT NULL, "
               "defs TEXT NOT NULL, PRIMARY KEY(lemma, pos)) WITHOUT ROWID")
    db.execute("CREATE TABLE exc(form TEXT NOT NULL, base TEXT NOT NULL, pos TEXT NOT NULL)")

    rows = 0
    for pos, name in POS_FILES.items():
        glosses = read_glosses(os.path.join(src, f"data.{name}"))
        with open(os.path.join(src, f"index.{name}"), encoding="utf-8") as f:
            for line in f:
                if line.startswith("  "):
                    continue
                parts = line.split()
                lemma = parts[0].replace("_", " ")
                synset_cnt = int(parts[2])
                offsets = parts[-synset_cnt:]
                defs = []
                for off in offsets:
                    d = glosses.get(off)
                    if d and d not in defs:
                        defs.append(d)
                    if len(defs) == MAX_DEFS:
                        break
                if defs:
                    db.execute("INSERT OR REPLACE INTO entries VALUES (?,?,?,?)",
                               (lemma, pos, synset_cnt, "\n".join(defs)))
                    rows += 1
        with open(os.path.join(src, f"{name}.exc"), encoding="utf-8") as f:
            for line in f:
                parts = line.split()
                for base in parts[1:]:
                    db.execute("INSERT INTO exc VALUES (?,?,?)",
                               (parts[0].replace("_", " "), base.replace("_", " "), pos))

    db.execute("CREATE INDEX exc_form ON exc(form)")
    db.commit()
    db.execute("VACUUM")
    db.close()
    shutil.copy(os.path.join(src, "LICENSE"), os.path.join(os.path.dirname(out), "LICENSE-wordnet.txt"))
    print(f"{rows} entries -> {out} ({os.path.getsize(out) // 1024} KB)")


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    target = os.path.join(here, "..", "app", "src", "main", "assets", "dictionary", "wordnet.db")
    os.makedirs(os.path.dirname(target), exist_ok=True)
    main(sys.argv[1], os.path.normpath(target))
