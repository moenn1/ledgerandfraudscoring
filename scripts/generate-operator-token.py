#!/usr/bin/env python3

import argparse
import base64
import hashlib
import hmac
import json
import os
import time


def base64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate a local LedgerForge operator JWT.")
    parser.add_argument("--subject", required=True, help="JWT subject / operator identifier")
    parser.add_argument(
        "--role",
        action="append",
        dest="roles",
        required=True,
        help="Operator role to embed. Repeat for multiple roles."
    )
    parser.add_argument("--ttl-seconds", type=int, default=3600, help="Token lifetime in seconds")
    parser.add_argument(
        "--issuer",
        default=os.environ.get("LEDGERFORGE_AUTH_ISSUER", "https://auth.ledgerforge.local"),
        help="JWT issuer"
    )
    parser.add_argument(
        "--audience",
        default=os.environ.get("LEDGERFORGE_AUTH_AUDIENCE", "ledgerforge-operator-api"),
        help="JWT audience"
    )
    parser.add_argument(
        "--secret",
        default=os.environ.get(
            "LEDGERFORGE_AUTH_HMAC_SECRET",
            "ledgerforge-dev-operator-signing-secret-change-before-shared-envs"
        ),
        help="HS256 signing secret"
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "iss": args.issuer,
        "sub": args.subject,
        "aud": [args.audience],
        "iat": now,
        "exp": now + args.ttl_seconds,
        "preferred_username": args.subject,
        "roles": sorted(set(role.upper() for role in args.roles)),
    }

    encoded_header = base64url(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    encoded_payload = base64url(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    signing_input = f"{encoded_header}.{encoded_payload}".encode("utf-8")
    signature = hmac.new(args.secret.encode("utf-8"), signing_input, hashlib.sha256).digest()
    print(f"{encoded_header}.{encoded_payload}.{base64url(signature)}")


if __name__ == "__main__":
    main()
