#!/usr/bin/env python3

import argparse
import csv
import datetime as dt
import gzip
import json
import os
import tempfile
import uuid
from collections import defaultdict
from pathlib import Path

import psycopg

NAMESPACE = uuid.UUID("0b933d47-7eca-4caa-88aa-d76a1d3b4ec1")
STP_PRIORITY = {"C": 0, "O": 1, "P": 2}


def parse_args():
    parser = argparse.ArgumentParser(
        description="Materialise one service date from a Network Rail SCHEDULE JSON gzip into SectionIQ."
    )
    parser.add_argument("schedule_gz", type=Path)
    parser.add_argument("--date", required=True, type=dt.date.fromisoformat)
    parser.add_argument(
        "--database-url",
        default=os.environ.get("SECTIONIQ_DATABASE_URL"),
        help="PostgreSQL connection string. Defaults to SECTIONIQ_DATABASE_URL.",
    )
    return parser.parse_args()


def applicable(schedule, service_date):
    start = dt.date.fromisoformat(schedule["schedule_start_date"])
    end = dt.date.fromisoformat(schedule["schedule_end_date"])
    if not (start <= service_date <= end):
        return False
    days = schedule.get("schedule_days_runs") or "0000000"
    return len(days) == 7 and days[service_date.weekday()] == "1"


def schedule_key(schedule):
    return (
        schedule["CIF_train_uid"],
        schedule["schedule_start_date"],
        schedule["CIF_stp_indicator"],
    )


def schedule_id_for(key):
    return uuid.uuid5(NAMESPACE, "|".join(key))


def choose_effective(candidates):
    """Return [(candidate, application_status), ...] for one UID on one service date."""
    selected = []

    ltp = [c for c in candidates if c["CIF_stp_indicator"] in {"C", "O", "P"}]
    if ltp:
        # Network Rail rule: O/C overlays take priority over P. If the feed ever
        # contains more than one applicable record of the same type, prefer the
        # most recent start date.
        ltp_sorted = sorted(
            ltp,
            key=lambda c: (
                STP_PRIORITY[c["CIF_stp_indicator"]],
                -dt.date.fromisoformat(c["schedule_start_date"]).toordinal(),
            ),
        )
        winner = ltp_sorted[0]
        status = "planned_cancel" if winner["CIF_stp_indicator"] == "C" else "effective"
        selected.append((winner, status))

    stp_new = [c for c in candidates if c["CIF_stp_indicator"] == "N"]
    if stp_new:
        # N is a genuinely new STP schedule, not an overlay. In the rare case of
        # overlapping N records for the same UID, take the latest start date.
        winner = max(
            stp_new,
            key=lambda c: dt.date.fromisoformat(c["schedule_start_date"]),
        )
        selected.append((winner, "effective"))

    return selected


def normalise_text(value):
    if value is None:
        return None
    if isinstance(value, list):
        return ",".join(str(v) for v in value)
    return str(value)


def nr_time_seconds(value):
    if not value:
        return None
    text = str(value)
    if len(text) not in (4, 5):
        return None
    half = text.endswith("H")
    raw = text[:4]
    if not raw.isdigit():
        return None
    hour = int(raw[:2])
    minute = int(raw[2:4])
    if hour > 23 or minute > 59:
        return None
    return hour * 3600 + minute * 60 + (30 if half else 0)


def location_clock_seconds(location):
    for key in ("arrival", "departure", "pass"):
        seconds = nr_time_seconds(location.get(key))
        if seconds is not None:
            return seconds
    return None


def first_pass(schedule_gz, service_date):
    tiplocs = []
    by_uid = defaultdict(list)
    header = None
    schedule_candidates = 0

    with gzip.open(schedule_gz, "rt", encoding="utf-8") as handle:
        for line in handle:
            obj = json.loads(line)

            if "JsonTimetableV1" in obj:
                header = obj["JsonTimetableV1"]
                continue

            if "TiplocV1" in obj:
                tip = obj["TiplocV1"]
                if tip.get("transaction_type", "Create").lower() != "delete":
                    tiplocs.append(tip)
                continue

            schedule = obj.get("JsonScheduleV1")
            if not schedule:
                continue
            if schedule.get("transaction_type", "Create").lower() == "delete":
                continue
            if applicable(schedule, service_date):
                schedule_candidates += 1
                # Keep only metadata needed for STP resolution in memory.
                by_uid[schedule["CIF_train_uid"]].append(
                    {
                        "CIF_train_uid": schedule["CIF_train_uid"],
                        "schedule_start_date": schedule["schedule_start_date"],
                        "CIF_stp_indicator": schedule["CIF_stp_indicator"],
                    }
                )

    selected = {}
    for uid, candidates in by_uid.items():
        for candidate, status in choose_effective(candidates):
            selected[schedule_key(candidate)] = status

    return header, tiplocs, selected, schedule_candidates


def write_stage_files(schedule_gz, service_date, selected, tiplocs, temp_dir, import_id):
    paths = {
        "tiplocs": Path(temp_dir) / "tiplocs.csv",
        "schedules": Path(temp_dir) / "schedules.csv",
        "locations": Path(temp_dir) / "locations.csv",
        "service_dates": Path(temp_dir) / "service_dates.csv",
    }

    with paths["tiplocs"].open("w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        for tip in tiplocs:
            writer.writerow(
                [
                    tip.get("tiploc_code"),
                    tip.get("nalco"),
                    tip.get("stanox"),
                    tip.get("crs_code"),
                    tip.get("description"),
                    tip.get("tps_description"),
                    str(import_id),
                ]
            )

    schedule_count = 0
    location_count = 0

    with (
        paths["schedules"].open("w", newline="", encoding="utf-8") as sf,
        paths["locations"].open("w", newline="", encoding="utf-8") as lf,
        paths["service_dates"].open("w", newline="", encoding="utf-8") as df,
        gzip.open(schedule_gz, "rt", encoding="utf-8") as handle,
    ):
        sw = csv.writer(sf)
        lw = csv.writer(lf)
        dw = csv.writer(df)

        for line in handle:
            obj = json.loads(line)
            schedule = obj.get("JsonScheduleV1")
            if not schedule:
                continue
            key = schedule_key(schedule)
            if key not in selected:
                continue

            sid = schedule_id_for(key)
            segment = schedule.get("schedule_segment") or {}
            new_segment = schedule.get("new_schedule_segment") or {}
            status = selected[key]

            sw.writerow(
                [
                    str(sid),
                    schedule.get("CIF_train_uid"),
                    schedule.get("schedule_start_date"),
                    schedule.get("schedule_end_date"),
                    schedule.get("schedule_days_runs"),
                    schedule.get("CIF_bank_holiday_running"),
                    schedule.get("train_status"),
                    schedule.get("CIF_stp_indicator"),
                    schedule.get("atoc_code"),
                    schedule.get("applicable_timetable"),
                    segment.get("CIF_train_category"),
                    segment.get("signalling_id"),
                    segment.get("CIF_headcode"),
                    segment.get("CIF_train_service_code"),
                    segment.get("CIF_business_sector"),
                    segment.get("CIF_power_type"),
                    segment.get("CIF_timing_load"),
                    segment.get("CIF_speed"),
                    segment.get("CIF_operating_characteristics"),
                    segment.get("CIF_train_class"),
                    segment.get("CIF_sleepers"),
                    segment.get("CIF_reservations"),
                    segment.get("CIF_connection_indicator"),
                    segment.get("CIF_catering_code"),
                    segment.get("CIF_service_branding"),
                    new_segment.get("traction_class"),
                    new_segment.get("uic_code"),
                    str(import_id),
                ]
            )

            dw.writerow(
                [
                    service_date.isoformat(),
                    str(sid),
                    schedule.get("CIF_train_uid"),
                    schedule.get("CIF_stp_indicator"),
                    status,
                ]
            )
            schedule_count += 1

            if status != "effective":
                continue

            day_offset = 0
            previous_seconds = None
            for sequence, location in enumerate(segment.get("schedule_location") or []):
                current_seconds = location_clock_seconds(location)
                if (
                    current_seconds is not None
                    and previous_seconds is not None
                    and current_seconds + 43200 < previous_seconds
                ):
                    day_offset += 1
                if current_seconds is not None:
                    previous_seconds = current_seconds

                lw.writerow(
                    [
                        str(sid),
                        sequence,
                        location.get("location_type"),
                        location.get("tiploc_code"),
                        location.get("tiploc_instance"),
                        location.get("arrival"),
                        location.get("departure"),
                        location.get("pass"),
                        location.get("public_arrival"),
                        location.get("public_departure"),
                        location.get("platform"),
                        location.get("line"),
                        location.get("path"),
                        location.get("engineering_allowance"),
                        location.get("pathing_allowance"),
                        location.get("performance_allowance"),
                        normalise_text(location.get("activity")),
                        day_offset,
                    ]
                )
                location_count += 1

    return paths, schedule_count, location_count


def copy_csv(cur, table, columns, path):
    sql = f"COPY {table} ({', '.join(columns)}) FROM STDIN WITH (FORMAT CSV)"
    with cur.copy(sql) as copy:
        with path.open("r", encoding="utf-8", newline="") as handle:
            while chunk := handle.read(1024 * 1024):
                copy.write(chunk)


def load_database(database_url, service_date, header, paths, import_id, schedule_count, location_count):
    with psycopg.connect(database_url) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                insert into public.rail_schedule_imports
                    (id, source_kind, source_format, source_day, status, metadata)
                values (%s, 'full', 'json', %s, 'running', %s::jsonb)
                """,
                (
                    import_id,
                    service_date.isoformat(),
                    json.dumps(header or {}),
                ),
            )

            cur.execute(
                """
                insert into public.rail_timetable_dates(service_date, source_import_id, status)
                values (%s, %s, 'loading')
                on conflict (service_date) do update set
                    source_import_id = excluded.source_import_id,
                    status = 'loading',
                    error_text = null,
                    updated_at = now()
                """,
                (service_date, import_id),
            )

            cur.execute("create temp table stg_tiplocs (like public.rail_tiplocs including defaults) on commit drop")
            cur.execute("alter table stg_tiplocs drop column created_at, drop column updated_at")
            cur.execute("create temp table stg_schedules (like public.rail_schedules including defaults) on commit drop")
            cur.execute("alter table stg_schedules drop column created_at, drop column updated_at")
            cur.execute("create temp table stg_locations (like public.rail_schedule_locations including defaults) on commit drop")
            cur.execute("alter table stg_locations drop column id, drop column created_at")
            cur.execute("create temp table stg_service_dates (like public.rail_schedule_service_dates including defaults) on commit drop")
            cur.execute("alter table stg_service_dates drop column created_at")

            copy_csv(
                cur,
                "stg_tiplocs",
                ["tiploc_code", "nalco", "stanox", "crs_code", "description", "tps_description", "source_import_id"],
                paths["tiplocs"],
            )
            copy_csv(
                cur,
                "stg_schedules",
                [
                    "id", "train_uid", "schedule_start_date", "schedule_end_date", "schedule_days_runs",
                    "bank_holiday_running", "train_status", "stp_indicator", "atoc_code", "applicable_timetable",
                    "train_category", "signalling_id", "headcode", "train_service_code", "business_sector",
                    "power_type", "timing_load", "cif_speed", "operating_characteristics", "train_class",
                    "sleepers", "reservations", "connection_indicator", "catering_code", "service_branding",
                    "traction_class", "uic_code", "source_import_id",
                ],
                paths["schedules"],
            )
            copy_csv(
                cur,
                "stg_locations",
                [
                    "schedule_id", "sequence", "location_type", "tiploc_code", "tiploc_instance", "arrival",
                    "departure", "pass", "public_arrival", "public_departure", "platform", "line", "path",
                    "engineering_allowance", "pathing_allowance", "performance_allowance", "activity", "day_offset",
                ],
                paths["locations"],
            )
            copy_csv(
                cur,
                "stg_service_dates",
                ["service_date", "schedule_id", "train_uid", "stp_indicator", "application_status"],
                paths["service_dates"],
            )

            cur.execute(
                """
                insert into public.rail_tiplocs
                    (tiploc_code, nalco, stanox, crs_code, description, tps_description, source_import_id)
                select tiploc_code, nalco, stanox, crs_code, description, tps_description, source_import_id
                from stg_tiplocs
                on conflict (tiploc_code) do update set
                    nalco = excluded.nalco,
                    stanox = excluded.stanox,
                    crs_code = excluded.crs_code,
                    description = excluded.description,
                    tps_description = excluded.tps_description,
                    source_import_id = excluded.source_import_id,
                    updated_at = now()
                """
            )

            cur.execute(
                """
                insert into public.rail_schedules
                select s.*, now(), now()
                from stg_schedules s
                on conflict (train_uid, schedule_start_date, stp_indicator) do update set
                    schedule_end_date = excluded.schedule_end_date,
                    schedule_days_runs = excluded.schedule_days_runs,
                    bank_holiday_running = excluded.bank_holiday_running,
                    train_status = excluded.train_status,
                    atoc_code = excluded.atoc_code,
                    applicable_timetable = excluded.applicable_timetable,
                    train_category = excluded.train_category,
                    signalling_id = excluded.signalling_id,
                    headcode = excluded.headcode,
                    train_service_code = excluded.train_service_code,
                    business_sector = excluded.business_sector,
                    power_type = excluded.power_type,
                    timing_load = excluded.timing_load,
                    cif_speed = excluded.cif_speed,
                    operating_characteristics = excluded.operating_characteristics,
                    train_class = excluded.train_class,
                    sleepers = excluded.sleepers,
                    reservations = excluded.reservations,
                    connection_indicator = excluded.connection_indicator,
                    catering_code = excluded.catering_code,
                    service_branding = excluded.service_branding,
                    traction_class = excluded.traction_class,
                    uic_code = excluded.uic_code,
                    source_import_id = excluded.source_import_id,
                    updated_at = now()
                """
            )

            cur.execute(
                "delete from public.rail_schedule_service_dates where service_date = %s",
                (service_date,),
            )
            cur.execute(
                """
                insert into public.rail_schedule_service_dates
                    (service_date, schedule_id, train_uid, stp_indicator, application_status)
                select service_date, schedule_id, train_uid, stp_indicator, application_status
                from stg_service_dates
                """
            )

            cur.execute(
                """
                delete from public.rail_schedule_locations l
                using (select distinct schedule_id from stg_locations) s
                where l.schedule_id = s.schedule_id
                """
            )
            cur.execute(
                """
                insert into public.rail_schedule_locations
                    (schedule_id, sequence, location_type, tiploc_code, tiploc_instance, arrival, departure, pass,
                     public_arrival, public_departure, platform, line, path, engineering_allowance,
                     pathing_allowance, performance_allowance, activity, day_offset)
                select schedule_id, sequence, location_type, tiploc_code, tiploc_instance, arrival, departure, pass,
                       public_arrival, public_departure, platform, line, path, engineering_allowance,
                       pathing_allowance, performance_allowance, activity, day_offset
                from stg_locations
                """
            )

            cur.execute(
                """
                update public.rail_schedule_imports set
                    status = 'complete',
                    completed_at = now(),
                    tiploc_records = (select count(*) from stg_tiplocs),
                    schedule_records = %s,
                    schedule_location_records = %s
                where id = %s
                """,
                (schedule_count, location_count, import_id),
            )
            cur.execute(
                """
                update public.rail_timetable_dates set
                    status = 'complete',
                    schedule_count = %s,
                    schedule_location_count = %s,
                    loaded_at = now(),
                    updated_at = now()
                where service_date = %s
                """,
                (schedule_count, location_count, service_date),
            )


def main():
    args = parse_args()
    if not args.database_url:
        raise SystemExit("Missing database URL. Set SECTIONIQ_DATABASE_URL or pass --database-url.")
    if not args.schedule_gz.exists():
        raise SystemExit(f"File not found: {args.schedule_gz}")

    print(f"Scanning {args.schedule_gz} for {args.date}...")
    header, tiplocs, selected, candidate_count = first_pass(args.schedule_gz, args.date)
    print(f"Applicable raw schedules: {candidate_count:,}")
    print(f"Effective/planned-cancel schedule records selected: {len(selected):,}")
    print(f"TIPLOC records: {len(tiplocs):,}")

    import_id = uuid.uuid4()
    with tempfile.TemporaryDirectory(prefix="sectioniq-wtt-") as temp_dir:
        print("Preparing bulk-load files...")
        paths, schedule_count, location_count = write_stage_files(
            args.schedule_gz,
            args.date,
            selected,
            tiplocs,
            temp_dir,
            import_id,
        )
        print(f"Schedules to load: {schedule_count:,}")
        print(f"Timing points to load: {location_count:,}")
        print("Bulk loading into SectionIQ...")
        load_database(
            args.database_url,
            args.date,
            header,
            paths,
            import_id,
            schedule_count,
            location_count,
        )

    print("Import complete.")


if __name__ == "__main__":
    main()
