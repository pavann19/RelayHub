"""Explicit, transactional tenant bootstrap; defaults to a read-only preview.

Applies only to the named PostgreSQL container when --apply is supplied.
Raw keys are written once to a private output file, never to stdout or SQL.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import uuid


def sql_plan(tenant, name, revoke_demo=False):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", tenant):
        raise ValueError("Tenant ID must contain 1-64 letters, digits, underscores or hyphens")
    if not name or len(name) > 255 or any(ord(c) < 32 for c in name):
        raise ValueError("Tenant name must contain 1-255 printable characters")
    keys = {role: "rlh_live_" + secrets.token_hex(24) for role in ("admin", "ingest", "observe")}
    quoted_name = name.replace("'", "''")
    statements = ["BEGIN;", f"INSERT INTO tenants(id,name,tier,rate_limit_per_second,burst_capacity,monthly_quota) VALUES ('{tenant}','{quoted_name}','PRO',100,200,500000) ON CONFLICT(id) DO NOTHING;"]
    for role, raw in keys.items():
        digest = hashlib.sha256(raw.encode()).hexdigest()
        statements.append("INSERT INTO api_keys(id,tenant_id,key_prefix,key_hash,name,role,is_active) VALUES "
                          f"('key_{uuid.uuid4()}','{tenant}','{raw[:16]}','{digest}','Bootstrap {role}','ROLE_{role.upper()}',TRUE);")
    if revoke_demo:
        hashes = [hashlib.sha256(key.encode()).hexdigest() for key in
                  ("rlh_live_demo1234567890abcdef", "rlh_admin_9876543210fedcba")]
        statements.append("UPDATE api_keys SET is_active=FALSE WHERE tenant_id='" + tenant + "' AND key_hash IN ('" + "','".join(hashes) + "');")
    statements.append("COMMIT;")
    return "\n".join(statements), keys


def write_private_json(path, value):
    # On Windows, restrict the empty file before writing any raw credentials.
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as output:
        if os.name == "nt":
            principal = subprocess.check_output(["whoami"], text=True).strip()
            subprocess.run(["icacls", str(path), "/inheritance:r", "/grant:r", principal + ":F"],
                           check=True, capture_output=True)
        output.write(json.dumps(value, indent=2) + "\n")
        output.flush()
        os.fsync(output.fileno())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--db-user", required=True)
    parser.add_argument("--tenant", required=True)
    parser.add_argument("--tenant-name", default="RelayHub tenant")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--revoke-demo-keys", action="store_true")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    plan, keys = sql_plan(args.tenant, args.tenant_name, args.revoke_demo_keys)
    if not args.apply:
        print(json.dumps({"mode": "PREVIEW", "container": args.container, "database": args.database,
                          "tenant": args.tenant, "roles": list(keys), "revoke_demo_keys": args.revoke_demo_keys,
                          "writes": False}))
        return
    if args.output is None:
        parser.error("--output is required with --apply")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # Reserve output exclusively before touching the database; refuse accidental overwrite.
    try:
        write_private_json(args.output, {"tenant": args.tenant, "keys": keys})
        subprocess.run(["docker", "exec", "-i", args.container, "psql", "-v", "ON_ERROR_STOP=1", "-U",
                        args.db_user, "-d", args.database], input=plan.encode(), check=True, capture_output=True)
    except Exception:
        # Retain recovery credentials if the database commit outcome is uncertain; never print them.
        raise RuntimeError("Bootstrap did not complete cleanly. Inspect the private credential file and database before retrying.") from None
    print(json.dumps({"status": "APPLIED", "tenant": args.tenant, "private_credentials_file": str(args.output.resolve()),
                      "revoke_demo_keys": args.revoke_demo_keys}))


if __name__ == "__main__":
    main()
