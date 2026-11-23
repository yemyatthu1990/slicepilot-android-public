#!/usr/bin/env python3
# ruff: target-version = "py311"
"""SlicePilot knowledge graph toolkit.

The project's memory: a graph of the things that exist (nodes) and how they relate
(edges), plus an append-only journal of agent sessions. See knowledge/README.md for
the schema; knowledge/STATUS.md is generated from these files by this script.

    kb.py check                         validate graph.json + sessions.jsonl
    kb.py render                        regenerate STATUS.md (runs check first)
    kb.py show <id>                     one node: fields, edges, sessions
    kb.py query [--kind K] [--status S] [--contains TEXT]
    kb.py node set <id> [--status S] [--summary ...] [--updated DATE]
                        [--next "a; b"] [--add-ref R] [--verification level[:ref][:date]]
    kb.py node add <id> --title ... --status ... --summary ... [--edge from>type>to]
    kb.py session add --agent A --summary "..." [--touched a,b] [--evidence c,d]
                      [--verdict "..."] [--date YYYY-MM-DD] [--backfilled]
    kb.py sessions [--limit N]

Stdlib only.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tempfile
from datetime import UTC, date, datetime

HERE = os.path.dirname(os.path.abspath(__file__))
GRAPH_PATH = os.path.join(HERE, "graph.json")
SESSIONS_PATH = os.path.join(HERE, "sessions.jsonl")
STATUS_PATH = os.path.join(HERE, "STATUS.md")

SCHEMA_VERSION = 1

STATUSES = {
    "project": ["active", "archived"],
    "release": ["planned", "built", "uploaded", "staged", "live", "superseded",
                "halted", "draft"],
    "module": ["active", "vendored", "frozen", "legacy", "stub"],
    "feature": ["idea", "in_progress", "code_complete", "merged", "shipped",
                "deprecated"],
    "test": ["current", "partial", "stale", "missing"],
    "branch": ["active", "pending_merge", "merged", "parked", "stale", "abandoned",
               "deleted"],
    "external": ["live", "configured", "unverified", "retired"],
    "device": ["available", "in_use", "unreachable", "retired", "unknown"],
    "artifact": ["current", "stale", "archived"],
    "secret": ["current", "rotated", "missing", "unknown"],
    "rule": ["enforced", "advisory", "draft"],
    "doc": ["current", "reference", "stale", "draft"],
    "issue": ["open", "mitigated", "resolved", "wont_fix"],
}

VERIFICATION_LEVELS = ["none", "code_review", "unit_tests", "debug_apk",
                       "release_apk", "device", "store"]

EDGE_TYPES = [
    # ownership / structure
    "part_of", "contains",
    # a feature and where its code and release live
    "implemented_by", "implements", "shipped_in", "worked_on", "carries",
    # dependencies and integrations
    "depends_on", "requires", "requires_secret", "integrates", "uses", "used_by",
    "derived_from", "source_of",
    # hosting and targets
    "hosted_on", "hosts", "runs_on", "verified_on",
    # documentation and evidence
    "documented_by", "documents", "produced_by", "produces", "verified_by",
    # governance and problems
    "guards", "guarded_by", "blocks", "blocked_by", "affects", "related",
    "supersedes",
]

# Section order in STATUS.md; "project" renders as the header snapshot instead.
SECTION_ORDER = ["feature", "release", "branch", "module", "external", "device",
                 "artifact", "secret", "rule", "doc", "issue"]

ID_RE = re.compile(r"^[a-z]+:[a-z0-9][a-z0-9._/-]*$")
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")

# Short, stable id prefix per kind: "feat:cloud-print", never "feature:cloud-print".
PREFIX = {
    "project": "proj",
    "release": "rel",
    "module": "mod",
    "feature": "feat",
    "test": "test",
    "branch": "br",
    "external": "ext",
    "device": "dev",
    "artifact": "art",
    "secret": "sec",
    "rule": "rule",
    "doc": "doc",
    "issue": "iss",
}
PREFIX_TO_KIND = {prefix: kind for kind, prefix in PREFIX.items()}


def node_id_error(node: dict) -> str:
    """Problem with a node's id, or "" when it is well formed."""
    node_id, kind = node.get("id"), node.get("kind")
    if not isinstance(node_id, str) or not node_id:
        return "nodes[]: entry has no id"
    if not ID_RE.match(node_id):
        return f"node {node_id}: id is not '<prefix>:<kebab-slug>'"
    if kind in PREFIX and not node_id.startswith(f"{PREFIX[kind]}:"):
        return (f"node {node_id}: id prefix must be {PREFIX[kind]!r} for kind "
                f"{kind!r}")
    return ""


# --------------------------------------------------------------------------- io

def _read_text(path: str) -> str:
    try:
        with open(path, encoding="utf-8") as handle:
            return handle.read()
    except OSError as exc:
        raise SystemExit(f"cannot read {path}: {exc}") from exc


def _load_json(text: str, label: str) -> dict:
    """Parse a JSON object passed on the command line, with a readable failure."""
    try:
        parsed = json.loads(text)
    except ValueError as exc:
        raise SystemExit(f"{label}: invalid JSON: {exc}") from exc
    if not isinstance(parsed, dict):
        raise SystemExit(f"{label}: expected a JSON object")
    return parsed


def read_graph(path: str = GRAPH_PATH) -> dict:
    try:
        graph = json.loads(_read_text(path))
    except ValueError as exc:
        raise SystemExit(f"{path}: invalid JSON: {exc}") from exc
    if not isinstance(graph, dict):
        raise SystemExit(f"{path}: expected a JSON object")
    return graph


def collect_edges(graph: dict) -> list:
    """Flatten every edge in the graph to {from, to, type} objects.

    Edges normally live inside the node they start from ("edges": [[type, other]]),
    which keeps a node's relationships in one place and makes agent edits small.
    A graph-level "edges": [{from, to, type}] list is also accepted and merged in,
    which is how the seed graph stored cross-kind links before adjacency existed.
    """
    edges = []
    for node in graph.get("nodes", []):
        if not isinstance(node, dict):
            continue
        for entry in node.get("edges", []) or []:
            if isinstance(entry, dict):
                edge = {"from": entry.get("from", node.get("id")),
                        "to": entry.get("to"), "type": entry.get("type")}
            elif isinstance(entry, (list, tuple)) and len(entry) == 2:
                edge = {"from": node.get("id"), "to": entry[1], "type": entry[0]}
            else:
                continue
            if edge["from"] and edge["to"]:
                edges.append(edge)
    for entry in graph.get("edges", []) or []:
        if isinstance(entry, dict):
            edges.append({"from": entry.get("from"), "to": entry.get("to"),
                          "type": entry.get("type")})
        elif isinstance(entry, (list, tuple)) and len(entry) == 3:
            edges.append({"from": entry[0], "to": entry[1], "type": entry[2]})
    return edges


def write_graph(graph: dict, path: str = GRAPH_PATH) -> None:
    try:
        text = json.dumps(graph, indent=2, ensure_ascii=False) + "\n"
    except (TypeError, ValueError) as exc:
        raise SystemExit(f"cannot serialize {path}: {exc}") from exc
    _atomic_write(path, text)


def read_sessions(path: str = SESSIONS_PATH) -> list:
    if not os.path.exists(path):
        return []
    sessions = []
    for lineno, line in enumerate(_read_text(path).splitlines(), 1):
        if not line.strip():
            continue
        try:
            sessions.append(json.loads(line))
        except ValueError as exc:
            raise SystemExit(f"{path}:{lineno}: invalid JSON: {exc}") from exc
    return sessions


def append_session(session: dict, path: str = SESSIONS_PATH) -> None:
    try:
        line = json.dumps(session, ensure_ascii=False, sort_keys=True)
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(line + "\n")
    except (TypeError, ValueError) as exc:
        raise SystemExit(f"cannot serialize session: {exc}") from exc
    except OSError as exc:
        raise SystemExit(f"cannot append to {path}: {exc}") from exc


def _atomic_write(path: str, text: str) -> None:
    directory = os.path.dirname(path) or "."
    try:
        fd, tmp = tempfile.mkstemp(dir=directory, prefix=".kb-", suffix=".tmp")
    except OSError as exc:
        raise SystemExit(f"cannot create temp file in {directory}: {exc}") from exc
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(text)
    except OSError as exc:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise SystemExit(f"cannot write {path}: {exc}") from exc
    try:
        os.replace(tmp, path)
        os.chmod(path, 0o644)
    except OSError as exc:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise SystemExit(f"cannot replace {path}: {exc}") from exc


def now_date() -> date:
    return datetime.now(UTC).date()


def today() -> str:
    return now_date().isoformat()


def now_stamp() -> str:
    return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def _is_date(value) -> bool:
    if not isinstance(value, str) or not DATE_RE.match(value):
        return False
    return value <= today()


# --------------------------------------------------------------------- validate

def _validate_edges(node: dict) -> list:
    """Validate the node's own adjacency list: [[type, other_id], ...]."""
    errors = []
    node_id = node.get("id")
    for entry in node.get("edges", []) or []:
        if isinstance(entry, dict):
            edge_from, edge_type, edge_to = (entry.get("from", node_id),
                                             entry.get("type"), entry.get("to"))
        elif isinstance(entry, (list, tuple)) and len(entry) == 2:
            edge_type, edge_to = entry[0], entry[1]
            edge_from = node_id
        else:
            errors.append(f"node {node_id}: bad edge {entry!r}")
            continue
        if edge_type not in EDGE_TYPES:
            errors.append(f"node {node_id}: unknown edge type {edge_type!r}")
        if not edge_to:
            errors.append(f"node {node_id}: edge {edge_type!r} has no target")
        elif edge_from == edge_to:
            errors.append(f"node {node_id}: self-edge {edge_type!r}")
    return errors


def _validate_verification(node: dict) -> list:
    """Validate the verification block and the shipped-feature rules it unlocks."""
    errors = []
    node_id, kind, status = node.get("id"), node.get("kind"), node.get("status")
    verification = node.get("verification")
    level = None
    if verification is not None:
        if not isinstance(verification, dict):
            return [f"node {node_id}: verification must be an object"]
        level = verification.get("level")
        if level not in VERIFICATION_LEVELS:
            allowed = ", ".join(VERIFICATION_LEVELS)
            errors.append(f"node {node_id}: verification.level {level!r} "
                          f"not in {allowed}")
        if verification.get("at") and not _is_date(verification.get("at")):
            errors.append(f"node {node_id}: verification.at must be a "
                          f"past YYYY-MM-DD")
        if not verification.get("ref"):
            errors.append(f"node {node_id}: verification.ref is required")
    too_low = level is None or VERIFICATION_LEVELS.index(level) < 4  # release_apk
    if kind == "feature" and status == "shipped" and too_low:
        errors.append(f"node {node_id}: shipped feature needs verification.level "
                      f">= release_apk (got {level!r})")
    return errors


def _validate_node(node, index: int, seen: set, edges: list) -> list:
    """Validate one node, returning human-readable problems."""
    if not isinstance(node, dict):
        return [f"nodes[{index}]: not an object"]
    node_id = node.get("id")
    kind = node.get("kind")
    status = node.get("status")
    if not isinstance(node_id, str) or not node_id:
        return [f"nodes[{index}]: missing id"]
    where = f"node {node_id}"
    errors = [f"{where}: duplicate id"] if node_id in seen else []
    seen.add(node_id)
    errors.extend(e for e in [node_id_error(node)] if e)
    if kind not in STATUSES:
        return errors + [f"{where}: unknown kind {kind!r}"]
    if status not in STATUSES[kind]:
        allowed = ", ".join(STATUSES[kind])
        errors.append(f"{where}: status {status!r} invalid for kind {kind} ({allowed})")
    if not node.get("title"):
        errors.append(f"{where}: missing title")
    if not node.get("summary"):
        errors.append(f"{where}: missing summary")
    if not _is_date(node.get("updated")):
        errors.append(f"{where}: updated {node.get('updated')!r} is not a past "
                      f"YYYY-MM-DD date")
    for key in ("refs", "next"):
        if not isinstance(node.get(key, []), list):
            errors.append(f"{where}: {key} must be a list")
    if node.get("owner") not in (None, "agent", "dev", "both"):
        errors.append(f"{where}: owner {node.get('owner')!r} not agent/dev/both")
    if not isinstance(node.get("attrs", {}), dict):
        errors.append(f"{where}: attrs must be an object")
    errors += _validate_verification(node)
    return errors


def validate(graph: dict, sessions: list) -> list:
    """Return every schema problem in the graph and journal; empty means healthy."""
    errors = []
    if graph.get("schema_version") != SCHEMA_VERSION:
        errors.append(f"schema_version {graph.get('schema_version')!r} "
                      f"(expected {SCHEMA_VERSION})")
    nodes = graph.get("nodes")
    if not isinstance(nodes, list) or not nodes:
        return errors + ["nodes[] missing or empty"]
    edges = collect_edges(graph)
    if not isinstance(graph.get("edges", []), list):
        return errors + ["edges[] must be a list"]
    shipped = [node for node in nodes
               if isinstance(node, dict)
               and (node.get("kind"), node.get("status")) == ("feature", "shipped")
               and node.get("verification")]
    for node in shipped:
        if not any(e["from"] == node.get("id") and e["type"] == "shipped_in"
                   for e in edges):
            errors.append(f"node {node.get('id')}: shipped feature needs a "
                          f"shipped_in edge")

    seen: set = set()
    by_kind: dict = {}
    for index, node in enumerate(nodes):
        before = len(errors)
        errors += _validate_node(node, index, seen, edges)
        if len(errors) == before and isinstance(node, dict) and node.get("kind"):
            by_kind.setdefault(node["kind"], []).append(node)

    projects = by_kind.get("project", [])
    if len(projects) != 1:
        errors.append(f"expected exactly 1 project node, found {len(projects)}")
    for project in projects:
        if not isinstance(project.get("next"), list):
            errors.append(f"node {project.get('id')}: project needs a next[] list")

    seen_edges = set()
    for index, edge in enumerate(edges):
        where = f"edges[{index}]"
        if not isinstance(edge, dict):
            errors.append(f"{where}: not an object")
            continue
        src, dst, etype = edge.get("from"), edge.get("to"), edge.get("type")
        if src not in seen:
            errors.append(f"{where}: from {src!r} is not a node id")
        if dst not in seen:
            errors.append(f"{where}: to {dst!r} is not a node id")
        if etype not in EDGE_TYPES:
            errors.append(f"{where}: unknown edge type {etype!r}")
            continue
        if src == dst:
            errors.append(f"{where}: self-edge")
        elif (src, dst, etype) in seen_edges:
            errors.append(f"{where}: duplicate edge {(src, dst, etype)}")
        seen_edges.add((src, dst, etype))

    for index, session in enumerate(sessions):
        where = "sessions[" + str(index) + "]"
        if not isinstance(session, dict):
            errors.append(f"{where}: not an object")
            continue
        entry_date = session.get("date")
        if not _is_date(entry_date):
            errors.append(f"{where}: date {entry_date!r} is not a past YYYY-MM-DD")
        for key in ("agent", "summary"):
            if not session.get(key):
                errors.append(f"{where}: {key} is required")
        touched = session.get("touched", [])
        if not isinstance(touched, list):
            errors.append(f"{where}: touched must be a list")
        else:
            for node_id in touched:
                if node_id not in seen:
                    errors.append(f"{where}: touched {node_id!r} is not a node id")
        if not isinstance(session.get("evidence", []), list):
            errors.append(f"{where}: evidence must be a list")
    return errors


# ----------------------------------------------------------------------- render

def cell(value) -> str:
    text = "" if value is None else str(value)
    return text.replace("|", "\\|").replace("\n", " ").strip()


def verify_label(node: dict) -> str:
    verification = node.get("verification")
    if not isinstance(verification, dict):
        return "—"
    return f"{verification.get('level', '?')} {verification.get('at', '')}".strip()


def table(rows, headers) -> list:
    lines = ["| " + " | ".join(headers) + " |",
             "|" + "|".join(["---"] * len(headers)) + "|"]
    for row in rows:
        lines.append("| " + " | ".join(cell(item) for item in row) + " |")
    return lines


def render_nodes(kind: str, group: list) -> list:
    """Rows for one kind, with the columns that matter for that kind."""
    ordered = sorted(group, key=lambda n: (n.get("status", ""), n.get("id", "")))
    if kind == "release":
        return table([(n.get("id"), n.get("title"), n.get("status"),
                       (n.get("attrs") or {}).get("date", ""), verify_label(n),
                       "; ".join(n.get("next") or [])) for n in
                      sorted(group, key=lambda n: n.get("id", ""))],
                     ("id", "version", "status", "date", "verified", "next"))
    if kind == "branch":
        return table([(n.get("id"), n.get("status"),
                       (n.get("attrs") or {}).get("head_date", ""),
                       (n.get("attrs") or {}).get("ahead", ""),
                       "; ".join(n.get("next") or [])) for n in
                      sorted(group, key=lambda n: n.get("id", ""))],
                     ("branch", "status", "last commit", "ahead of main", "next"))
    if kind == "module":
        return table([(n.get("id"), n.get("title"), n.get("status"),
                       ", ".join(f"`{r}`" for r in (n.get("refs") or [])[:2]) or "—",
                       n.get("updated"), "; ".join(n.get("next") or []))
                      for n in ordered],
                     ("id", "path / name", "status", "refs", "updated", "next"))
    if kind == "issue":
        return table([(n.get("id"), n.get("title"), n.get("status"),
                       (n.get("attrs") or {}).get("severity", ""), n.get("updated"),
                       "; ".join(n.get("next") or [])) for n in ordered],
                     ("id", "title", "status", "severity", "updated", "next"))
    return table([(n.get("id"), n.get("title"), n.get("status"), verify_label(n),
                   n.get("updated"), "; ".join(n.get("next") or []))
                  for n in ordered],
                 ("id", "title", "status", "verified", "updated", "next"))


def render_rules(group: list) -> list:
    lines = []
    for node in sorted(group, key=lambda n: (n.get("status", ""), n.get("id", ""))):
        refs = ", ".join(f"`{r}`" for r in node.get("refs", [])) or "—"
        lines.append(f"- **{node['title']}** _({node['status']})_ — {node['summary']}  \n"
                     f"  refs: {refs}")
    return lines


def render_sessions(sessions: list, limit=None) -> str:
    ordered = sorted(sessions, key=lambda s: s.get("date", ""), reverse=True)
    if limit:
        ordered = ordered[:limit]
    if not ordered:
        return "_no sessions recorded_"
    lines = []
    for session in ordered:
        mark = " *(backfilled)*" if session.get("backfilled") else ""
        touched = ", ".join(f"`{t}`" for t in session.get("touched", [])) or "—"
        evidence = ", ".join(f"`{e}`" for e in session.get("evidence", [])) or "—"
        lines.append(f"- **{cell(session.get('date'))}** — "
                     f"{cell(session.get('agent'))}: {cell(session.get('summary'))}"
                     f"{mark}  \n"
                     f"  touched: {touched}  \n"
                     f"  evidence: {evidence}")
        if session.get("verdict"):
            lines.append(f"  verdict: {cell(session.get('verdict'))}")
    return "\n".join(lines).replace("  \n  verdict", "\n  verdict")


def render(graph: dict, sessions: list) -> str:
    nodes = graph["nodes"]
    edges = collect_edges(graph)
    by_kind: dict = {}
    for node in nodes:
        by_kind.setdefault(node["kind"], []).append(node)
    project = by_kind["project"][0]
    attrs = project.get("attrs") or {}

    out = [f"# {project.get('title', 'Project')} — project status", ""]
    out.append("> Generated by `knowledge/kb.py render` from `graph.json` + "
               f"`sessions.jsonl`.\n> Do not hand-edit this file. Last render: "
               f"{now_stamp()}.")
    out.append("")
    out.append("## Snapshot")
    out.append("")
    out.extend(table([(k, v) for k, v in attrs.items()], ("field", "value")))
    out.append("")
    out.append(f"**Current truth:** {project.get('summary')}")
    out.append("")
    if project.get("next"):
        out.append("## Now → next")
        out.append("")
        for index, item in enumerate(project["next"], 1):
            out.append(f"{index}. {item}")
        out.append("")
    out.append(f"_Graph: {len(nodes)} nodes, {len(edges)} edges · "
               f"{len(sessions)} recorded sessions._")
    out.append("")

    for kind in SECTION_ORDER:
        group = by_kind.get(kind, [])
        if not group:
            continue
        out.append(f"## {kind.capitalize()} ({len(group)})")
        out.append("")
        out.extend(render_rules(group) if kind == "rule"
                   else render_nodes(kind, group))
        out.append("")

    grouped: dict = {}
    for edge in sorted(edges, key=lambda e: (e["type"], e["from"], e["to"])):
        grouped.setdefault(edge["type"], []).append(edge)
    out.append("## Relationships")
    out.append("")
    for etype in EDGE_TYPES:
        group = grouped.get(etype)
        if not group:
            continue
        targets = "; ".join(f"`{e['from']}` → `{e['to']}`" for e in group)
        out.append(f"**{etype}** — {targets}")
        out.append("")

    out.append("## Session journal")
    out.append("")
    out.append(render_sessions(sessions, limit=12))
    out.append("")
    return "\n".join(out)


# -------------------------------------------------------------------- commands

def cmd_check(args) -> int:
    graph = read_graph()
    sessions = read_sessions()
    errors = validate(graph, sessions)
    for error in errors:
        print(f"ERROR {error}")
    if errors:
        print(f"\n{len(errors)} problem(s)")
        return 1
    print(f"ok: {len(graph['nodes'])} nodes, {len(collect_edges(graph))} edges, "
          f"{len(sessions)} sessions")
    return 0


def cmd_render(args) -> int:
    graph = read_graph()
    sessions = read_sessions()
    errors = validate(graph, sessions)
    if errors:
        for error in errors:
            print(f"ERROR {error}")
        print("\nrefusing to render: fix the graph first")
        return 1
    _atomic_write(STATUS_PATH, render(graph, sessions))
    print(f"wrote {STATUS_PATH}")
    return 0


def cmd_show(args) -> int:
    graph = read_graph()
    node = next((n for n in graph["nodes"] if n.get("id") == args.id), None)
    if node is None:
        prefix = args.id.split(":")[0] if ":" in args.id else "feature"
        print(f"no node {args.id!r} (try: kb.py query --kind {prefix})")
        return 1
    edges = collect_edges(graph)
    print(json.dumps(node, indent=2, ensure_ascii=False))
    print("\noutgoing:")
    print("\n".join(f"  {e['type']:<16} {e['to']}" for e in edges
                    if e["from"] == args.id) or "  none")
    print("incoming:")
    print("\n".join(f"  {e['type']:<16} {e['from']}" for e in edges
                    if e["to"] == args.id) or "  none")
    print(f"\nsessions touching {args.id}:")
    hits = [s for s in read_sessions() if args.id in (s.get("touched") or [])]
    print("\n".join(f"  {s.get('date')} {s.get('agent')} — {s.get('summary')}"
                    for s in sorted(hits, key=lambda s: s.get("date", ""),
                                    reverse=True)) or "  none")
    return 0


def cmd_query(args) -> int:
    needle = (args.contains or "").lower()
    rows = [node for node in read_graph()["nodes"]
            if (not args.kind or node.get("kind") == args.kind)
            and (not args.status or node.get("status") == args.status)
            and (not needle or needle in json.dumps(node).lower())]
    if not rows:
        print("no matching nodes")
        return 1
    for node in sorted(rows, key=lambda n: (n.get("kind", ""), n.get("id", ""))):
        print(f"{node.get('kind'):<10} {node.get('id'):<42} "
              f"{node.get('status'):<14} {node.get('title')}")
    return 0


def _parse_edge_spec(spec: str, owner: str) -> list:
    """Parse 'type>target' (outgoing from owner) or 'from>type>to' into node form."""
    parts = [p.strip() for p in spec.split(">")]
    if len(parts) == 2:
        return [parts[0], parts[1]]
    if len(parts) == 3:
        if parts[0] != owner:
            raise SystemExit(f"edge {spec!r} starts at {parts[0]!r}, not {owner!r}")
        return [parts[1], parts[2]]
    raise SystemExit(f"bad edge {spec!r} (want type>target or from>type>to)")


def cmd_node_set(args) -> int:
    graph = read_graph()
    target = next((n for n in graph["nodes"] if n.get("id") == args.id), None)
    if target is None:
        print(f"no node {args.id!r}")
        return 1
    if args.status is not None:
        target["status"] = args.status
    if args.title is not None:
        target["title"] = args.title
    if args.summary is not None:
        target["summary"] = args.summary
    if args.updated is not None:
        target["updated"] = args.updated
    if args.next is not None:
        target["next"] = [i.strip() for i in args.next.split(";") if i.strip()]
    if args.add_ref:
        refs = target.setdefault("refs", [])
        refs.extend(ref for ref in args.add_ref if ref not in refs)
    if args.verification is not None:
        parts = args.verification.split(":")
        target["verification"] = {
            "level": parts[0],
            "ref": parts[1] if len(parts) > 1 else "",
            "at": parts[2] if len(parts) > 2 else today(),
        }
    if args.add_edge:
        edges = target.setdefault("edges", [])
        for spec in args.add_edge:
            edge = _parse_edge_spec(spec, args.id)
            if edge not in edges:
                edges.append(edge)
    if args.drop_edge:
        edges = target.setdefault("edges", [])
        for spec in args.drop_edge:
            edge = _parse_edge_spec(spec, args.id)
            if edge in edges:
                edges.remove(edge)
    if args.owner is not None:
        target["owner"] = args.owner
    if args.add_attrs:
        target.setdefault("attrs", {}).update(_load_json(args.add_attrs, "--add-attrs"))
    target.setdefault("updated", today())
    write_graph(graph)
    print(json.dumps(target, indent=2, ensure_ascii=False))
    return cmd_render(argparse.Namespace())


def cmd_node_add(args) -> int:
    graph = read_graph()
    if any(n.get("id") == args.id for n in graph["nodes"]):
        print(f"node {args.id!r} already exists")
        return 1
    prefix = args.id.split(":")[0]
    kind = PREFIX_TO_KIND.get(prefix, prefix)
    node = {"id": args.id, "kind": kind, "title": args.title, "status": args.status,
            "summary": args.summary, "updated": args.updated or today()}
    if args.ref:
        node["refs"] = list(args.ref)
    if args.next:
        node["next"] = [i.strip() for i in args.next.split(";") if i.strip()]
    if args.attrs:
        node["attrs"] = _load_json(args.attrs, "--attrs")
    graph["nodes"].append(node)
    graph["nodes"].sort(key=lambda n: (n.get("kind", ""), n.get("id", "")))
    for spec in args.edge or []:
        node.setdefault("edges", []).extend(
            e for e in [_parse_edge_spec(spec, args.id)] if e not in node["edges"])
    write_graph(graph)
    print(json.dumps(node, indent=2, ensure_ascii=False))
    return cmd_render(argparse.Namespace())


def cmd_sessions(args) -> int:
    print(render_sessions(read_sessions(), limit=args.limit))
    return 0


def cmd_session_add(args) -> int:
    session = {
        "date": args.date or today(),
        "agent": args.agent,
        "summary": args.summary,
        "touched": [t.strip() for t in (args.touched or "").split(",") if t.strip()],
        "evidence": [e.strip() for e in (args.evidence or "").split(",") if e.strip()],
    }
    if args.verdict:
        session["verdict"] = args.verdict
    if args.backfilled:
        session["backfilled"] = True
    errors = validate(read_graph(), read_sessions() + [session])
    if errors:
        for error in errors:
            print(f"ERROR {error}")
        print("\nrefusing to append: graph or entry is invalid")
        return 1
    append_session(session)
    print(json.dumps(session, ensure_ascii=False, sort_keys=True))
    return cmd_render(argparse.Namespace())


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="kb.py", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("check", help="validate graph.json and sessions.jsonl").set_defaults(
        func=cmd_check)
    sub.add_parser("render", help="regenerate STATUS.md").set_defaults(func=cmd_render)

    show = sub.add_parser("show", help="print one node with its edges and sessions")
    show.add_argument("id")
    show.set_defaults(func=cmd_show)

    query = sub.add_parser("query", help="list nodes by kind/status/text")
    query.add_argument("--kind", choices=sorted(STATUSES))
    query.add_argument("--status")
    query.add_argument("--contains", help="case-insensitive substring match in the node")
    query.set_defaults(func=cmd_query)

    node_cmd = sub.add_parser("node", help="create or update a node")
    node_sub = node_cmd.add_subparsers(dest="which", required=True)
    setter = node_sub.add_parser("set", help="patch fields of an existing node")
    setter.add_argument("id")
    setter.add_argument("--status")
    setter.add_argument("--title")
    setter.add_argument("--summary")
    setter.add_argument("--updated")
    setter.add_argument("--next", help="semicolon-separated replacement for next[]")
    setter.add_argument("--add-ref", action="append")
    setter.add_argument("--add-edge", action="append",
                        help="outgoing edge 'type>target', or 'from>type>to'")
    setter.add_argument("--drop-edge", action="append")
    setter.add_argument("--verification", help="level[:ref[:date]]")
    setter.add_argument("--owner", choices=["agent", "dev", "both"])
    setter.add_argument("--add-attrs", help="JSON object merged into attrs")
    setter.set_defaults(func=cmd_node_set)

    adder = node_sub.add_parser("add", help="add a node (kind comes from the id)")
    adder.add_argument("id")
    adder.add_argument("--title", required=True)
    adder.add_argument("--status", required=True)
    adder.add_argument("--summary", required=True)
    adder.add_argument("--updated")
    adder.add_argument("--ref", action="append")
    adder.add_argument("--next")
    adder.add_argument("--attrs", help="JSON object")
    adder.add_argument("--edge", action="append", help="from>type>to, repeatable")
    adder.set_defaults(func=cmd_node_add)

    session_cmd = sub.add_parser("session", help="append a work-session journal entry")
    session_sub = session_cmd.add_subparsers(dest="which", required=True)
    add = session_sub.add_parser("add", help="append one session line and re-render")
    add.add_argument("--agent", required=True)
    add.add_argument("--summary", required=True)
    add.add_argument("--touched", help="comma-separated node ids")
    add.add_argument("--evidence", help="comma-separated commit:<sha> / file: / url")
    add.add_argument("--verdict", help="what was actually proven, honestly")
    add.add_argument("--date")
    add.add_argument("--backfilled", action="store_true",
                     help="entry reconstructed from history rather than logged live")
    add.set_defaults(func=cmd_session_add)

    sessions = sub.add_parser("sessions", help="print the journal")
    sessions.add_argument("--limit", type=int)
    sessions.set_defaults(func=cmd_sessions)
    return parser


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
