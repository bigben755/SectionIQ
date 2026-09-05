#!/usr/bin/env python3
"""Import Great Britain rail station coordinates from a NaPTAN national CSV.

The importer expects the NaPTAN Stops.csv-style national dataset. It filters
active RLY records, derives the Network Rail TIPLOC from the 9100-prefixed
ATCO code, joins that TIPLOC to SectionIQ's rail_tiplocs table to obtain the
CRS code, and upserts one coordinate per CRS into public.rail_stations.

Usage:
    python tools/import_naptan_rail_stations.py C:\\path\\to\\naptan.csv

The database URL is read from --database-url or SECTIONIQ_DATABASE_URL.
Use the Supabase session pooler on port 5432 because this script uses a
session-scoped temporary table and PostgreSQL COPY.
"""

from __future__ import annotations

import argparse
import csv
import io
import os
import sys
import zipfile
from pathlib import Path
from typing import Iterator, TextIO

import psycopg


REQUIRED_COLUMNS = {
    "ATCOCode",
    "CommonName",
    "Longitude",
    "Latitude",
    "StopType",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Import active NaPTAN rail stations into SectionIQ."
    )
    parser.add_argument(
        "naptan_file",
        help="Path to NaPTAN national CSV, or ZIP containing the stops CSV.",
    )
    parser.add_argument(
        "--database-url",
        default=os.environ.get("SECTIONIQ_DATABASE_URL"),
        help="PostgreSQL connection string. Defaults to SECTIONIQ_DATABASE_URL.",
    )
    return parser.parse_args()


def open_csv_from_zip(path: Path) -> tuple[TextIO, zipfile.ZipFile]:
    archive = zipfile.ZipFile(path)

    candidates = [
        name
        for name in archive.namelist()
        if name.lower().endswith(".csv")
    ]

    if not candidates:
        archive.close()
        raise RuntimeError("ZIP contains no CSV files")

    preferred = sorted(
        candidates,
        key=lambda name: (
            0 if Path(name).name.lower() == "stops.csv" else 1,
            len(name),
            name.lower(),
        ),
    )

    for name in preferred:
        raw = archive.open(name, "r")
        text = io.TextIOWrapper(raw, encoding="utf-8-sig", newline="")
        try:
            reader = csv.reader(text)
            header = next(reader)
        except Exception:
            text.close()
            continue

        text.seek(0)
        if REQUIRED_COLUMNS.issubset(set(header)):
            print(f"Using CSV inside ZIP: {name}")
            return text, archive

        text.close()

    archive.close()
    raise RuntimeError(
        "Could not find a NaPTAN Stops.csv-style file in the ZIP"
    )


def open_naptan_csv(path: Path) -> tuple[TextIO, zipfile.ZipFile | None]:
    if zipfile.is_zipfile(path):
        return open_csv_from_zip(path)

    return path.open("r", encoding="utf-8-sig", newline=""), None


def normalise_status(value: str | None) -> str:
    return (value or "").strip().lower()


def parse_float(value: str | None) -> float | None:
    text = (value or "").strip()
    if not text:
        return None
    try:
        return float(text)
    except ValueError:
        return None


def tiploc_from_atco(atco_code: str | None) -> str | None:
    atco = (atco_code or "").strip().upper()
    if not atco.startswith("9100"):
        return None

    tiploc = atco[4:].strip()
    if not tiploc:
        return None

    # TIPLOC codes are at most 7 characters in the NaPTAN rail reference model.
    if len(tiploc) > 7:
        return None

    return tiploc


def iter_active_rail_rows(handle: TextIO) -> Iterator[tuple[str, str, str, float, float, str | None]]:
    reader = csv.DictReader(handle)
    fieldnames = set(reader.fieldnames or [])

    missing = REQUIRED_COLUMNS - fieldnames
    if missing:
        raise RuntimeError(
            "NaPTAN CSV is missing required columns: " + ", ".join(sorted(missing))
        )

    for row in reader:
        if (row.get("StopType") or "").strip().upper() != "RLY":
            continue

        status = normalise_status(row.get("Status"))
        if status == "inactive":
            continue

        tiploc = tiploc_from_atco(row.get("ATCOCode"))
        if tiploc is None:
            continue

        latitude = parse_float(row.get("Latitude"))
        longitude = parse_float(row.get("Longitude"))
        if latitude is None or longitude is None:
            continue

        station_name = (row.get("CommonName") or tiploc).strip() or tiploc
        atco_code = (row.get("ATCOCode") or "").strip().upper()
        modified = (row.get("ModificationDateTime") or "").strip() or None

        yield (
            tiploc,
            atco_code,
            station_name,
            latitude,
            longitude,
            modified,
        )


def main() -> int:
    args = parse_args()

    if not args.database_url:
        print(
            "Database URL missing. Set SECTIONIQ_DATABASE_URL or pass --database-url.",
            file=sys.stderr,
        )
        return 2

    path = Path(args.naptan_file).expanduser()
    if not path.exists():
        print(f"File not found: {path}", file=sys.stderr)
        return 2

    csv_handle, archive = open_naptan_csv(path)

    try:
        with psycopg.connect(args.database_url) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    create temporary table temp_naptan_rail_stations (
                        tiploc_code text not null,
                        atco_code text not null,
                        station_name text not null,
                        latitude double precision not null,
                        longitude double precision not null,
                        modification_datetime timestamptz
                    ) on commit drop
                    """
                )

                copied = 0
                with cur.copy(
                    """
                    copy temp_naptan_rail_stations
                    (tiploc_code, atco_code, station_name, latitude, longitude, modification_datetime)
                    from stdin
                    """
                ) as copy:
                    for record in iter_active_rail_rows(csv_handle):
                        copy.write_row(record)
                        copied += 1

                print(f"Active NaPTAN RLY rows staged: {copied:,}")

                cur.execute(
                    """
                    select count(*)
                    from temp_naptan_rail_stations n
                    join rail_tiplocs t
                      on t.tiploc_code = n.tiploc_code
                    where t.crs_code is not null
                    """
                )
                matched_rows = cur.fetchone()[0]

                cur.execute(
                    """
                    select count(distinct t.crs_code)
                    from temp_naptan_rail_stations n
                    join rail_tiplocs t
                      on t.tiploc_code = n.tiploc_code
                    where t.crs_code is not null
                    """
                )
                matched_crs = cur.fetchone()[0]

                print(f"Rows matched to Network Rail TIPLOC/CRS: {matched_rows:,}")
                print(f"Unique CRS codes ready to import: {matched_crs:,}")

                cur.execute(
                    """
                    with ranked as (
                        select
                            t.crs_code,
                            n.station_name,
                            n.latitude,
                            n.longitude,
                            n.modification_datetime,
                            n.tiploc_code,
                            row_number() over (
                                partition by t.crs_code
                                order by
                                    n.modification_datetime desc nulls last,
                                    n.tiploc_code,
                                    n.atco_code
                            ) as rn
                        from temp_naptan_rail_stations n
                        join rail_tiplocs t
                          on t.tiploc_code = n.tiploc_code
                        where t.crs_code is not null
                    )
                    insert into rail_stations (
                        crs_code,
                        station_name,
                        latitude,
                        longitude,
                        source,
                        source_updated_at
                    )
                    select
                        crs_code,
                        station_name,
                        latitude,
                        longitude,
                        'DfT NaPTAN',
                        modification_datetime
                    from ranked
                    where rn = 1
                    on conflict (crs_code) do update
                    set
                        station_name = excluded.station_name,
                        latitude = excluded.latitude,
                        longitude = excluded.longitude,
                        source = excluded.source,
                        source_updated_at = excluded.source_updated_at,
                        updated_at = now()
                    """
                )
                upserted = cur.rowcount

                # Because this is a full national snapshot, remove only stale rows
                # previously sourced by this same importer. Never remove manually
                # maintained or differently sourced station rows.
                cur.execute(
                    """
                    delete from rail_stations rs
                    where rs.source = 'DfT NaPTAN'
                      and not exists (
                          select 1
                          from temp_naptan_rail_stations n
                          join rail_tiplocs t
                            on t.tiploc_code = n.tiploc_code
                          where t.crs_code = rs.crs_code
                      )
                    """
                )
                deleted = cur.rowcount

                cur.execute(
                    """
                    select count(*)
                    from rail_stations
                    where source = 'DfT NaPTAN'
                    """
                )
                final_count = cur.fetchone()[0]

                cur.execute(
                    """
                    select n.tiploc_code, min(n.station_name)
                    from temp_naptan_rail_stations n
                    left join rail_tiplocs t
                      on t.tiploc_code = n.tiploc_code
                     and t.crs_code is not null
                    where t.tiploc_code is null
                    group by n.tiploc_code
                    order by n.tiploc_code
                    limit 20
                    """
                )
                unmatched = cur.fetchall()

            conn.commit()

        print(f"rail_stations rows inserted/updated: {upserted:,}")
        print(f"Stale NaPTAN rows removed: {deleted:,}")
        print(f"Final DfT NaPTAN station rows: {final_count:,}")

        if unmatched:
            print("Sample NaPTAN RLY TIPLOCs not matched to current rail_tiplocs:")
            for tiploc, name in unmatched:
                print(f"  {tiploc}: {name}")

        return 0

    finally:
        csv_handle.close()
        if archive is not None:
            archive.close()


if __name__ == "__main__":
    raise SystemExit(main())
