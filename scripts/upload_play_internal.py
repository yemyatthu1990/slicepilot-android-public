#!/usr/bin/env python3
import argparse
import json
import os

from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

PACKAGE = "world.yemyat.slicepilot"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--key", default="secrets/play-service-account.json")
    parser.add_argument("--bundle", default="android/app/build/outputs/bundle/release/app-release.aab")
    parser.add_argument("--track", default="internal")
    parser.add_argument(
        "--release-name",
        default=None,
        help="Release label shown in the console; omit to let Play show the version code.",
    )
    parser.add_argument("--version-code", type=int, default=1)
    parser.add_argument("--status", default="draft", choices=["draft", "inProgress", "halted", "completed"])
    parser.add_argument("--mapping", default="android/app/build/outputs/mapping/release/mapping.txt")
    parser.add_argument(
        "--native-symbols",
        default="android/app/build/outputs/native-debug-symbols/release/native-debug-symbols.zip",
    )
    parser.add_argument(
        "--notes",
        default=(
            "SlicePilot initial internal build.\n"
            "- Foreground slicing with notifications\n"
            "- Open STL/3MF files from Files app\n"
            "- Cute Spirited Away-inspired UI"
        ),
    )
    args = parser.parse_args()

    creds = service_account.Credentials.from_service_account_file(args.key, scopes=[SCOPE])
    service = build("androidpublisher", "v3", credentials=creds)

    edit = service.edits().insert(packageName=PACKAGE, body={}).execute()
    edit_id = edit["id"]
    print(f"Created edit {edit_id}")

    media = MediaFileUpload(
        args.bundle,
        mimetype="application/octet-stream",
        resumable=True,
    )
    bundle = service.edits().bundles().upload(
        packageName=PACKAGE,
        editId=edit_id,
        media_body=media,
    ).execute()
    sha1 = bundle.get("sha1", "")
    print(f"Uploaded bundle sha1={sha1}")

    if args.mapping and os.path.isfile(args.mapping):
        service.edits().deobfuscationfiles().upload(
            packageName=PACKAGE,
            editId=edit_id,
            apkVersionCode=args.version_code,
            deobfuscationFileType="proguard",
            media_body=MediaFileUpload(args.mapping, mimetype="application/octet-stream", resumable=True),
        ).execute()
        print(f"Uploaded proguard mapping {args.mapping}")

    if args.native_symbols and os.path.isfile(args.native_symbols):
        service.edits().deobfuscationfiles().upload(
            packageName=PACKAGE,
            editId=edit_id,
            apkVersionCode=args.version_code,
            deobfuscationFileType="nativeCode",
            media_body=MediaFileUpload(args.native_symbols, mimetype="application/octet-stream", resumable=True),
        ).execute()
        print(f"Uploaded native symbols {args.native_symbols}")

    notes = args.notes
    if os.path.isfile(args.notes):
        notes = open(args.notes, encoding="utf-8").read().strip()
    release = {
        "versionCodes": [args.version_code],
        "status": args.status,
        "releaseNotes": [{"language": "en-US", "text": notes}],
    }
    if args.release_name:
        release["name"] = args.release_name
    track_body = {"track": args.track, "releases": [release]}
    service.edits().tracks().update(
        packageName=PACKAGE,
        editId=edit_id,
        track=args.track,
        body=track_body,
    ).execute()
    print(f"Updated {args.track} track")

    service.edits().commit(packageName=PACKAGE, editId=edit_id).execute()
    print("Committed edit")


if __name__ == "__main__":
    main()
