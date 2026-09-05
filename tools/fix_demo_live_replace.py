from pathlib import Path

path = Path(__file__).resolve().parents[1] / 'dataview' / 'src' / 'DemoLiveJourney.tsx'
text = path.read_text(encoding='utf-8')
old = "event.event_type.replaceAll('_', ' ')"
new = "event.event_type.replace(/_/g, ' ')"
if old not in text:
    raise SystemExit('replaceAll target not found')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
print('Replaced ES2021 replaceAll usage with target-compatible replace.')
