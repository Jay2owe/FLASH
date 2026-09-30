"""Freeze an existing FLASH run's inputs/settings for the adjacent Java API replay.

Supply the original configured project, one or more completed JSONL run records
in execution order, the exact FLASH JAR and a NEW destination manifest. Files
are hashed, never changed. Input images remain outside the repository. The
manifest is local evidence and may contain sensitive filenames: review before
sharing. Old projects without run records need their original configuration
and producing macros; this tool does not infer missing settings from the PDF.
"""
import argparse
import hashlib
import json
from pathlib import Path


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def capture(project, records, jar):
    project, jar = Path(project).resolve(), Path(jar).resolve()
    files, runs = {}, []
    for record_path in records:
        entries = [json.loads(line) for line in Path(record_path).read_text(encoding='utf-8').splitlines() if line.strip()]
        completed = [entry for entry in entries if entry.get('finishedAtMillis', 0) > 0]
        if not completed:
            raise ValueError(f'No completed record in {record_path}')
        record = completed[-1]
        if record['status'] != 'ok' or not record.get('inputs') or not record.get('parameters'):
            raise ValueError(f'Record is incomplete, warned or failed: {record_path}')
        if record['schemaVersion'] != 1 or record['analysisIndex'] not in [2, 3, 4, 5, 6, 7, 8, 9, 10, 11]:
            raise ValueError('Unsupported record schema/analysis, or interactive setup/figure task.')
        if Path(record['projectRoot']).resolve() != project:
            raise ValueError('Record belongs to another project. Capture against its original project root.')
        for entry in record['inputs']:
            path = Path(entry['path']).resolve()
            relative = path.relative_to(project).as_posix()
            if entry.get('status') != 'processed':
                raise ValueError(f'Unprocessed or skipped original input: {relative}')
            files[relative] = path
        runs.append({'run_id': record['runId'], 'analysis_index': record['analysisIndex'],
                     'parameters': record['parameters'], 'flash_version': record['flashVersion'],
                     'imagej_version': record['fijiBuild'].split(' / ')[-1],
                     'java_version': record['jdkVersion'], 'record_sha256': sha256(record_path)})
    config = project / 'FLASH/Config'
    if not config.is_dir():
        raise FileNotFoundError('Missing FLASH/Config, including original filters, labels, ROIs and models.')
    for path in config.rglob('*'):
        if path.is_file():
            files[path.relative_to(project).as_posix()] = path
    if not (config / '.settings/channel_config.json').is_file():
        raise FileNotFoundError('This replay needs the recorded channel_config.json; legacy projects are not guessed.')
    identities = {(r['flash_version'], r['imagej_version'], r['java_version']) for r in runs}
    if len(identities) != 1:
        raise ValueError('All captured stages must use the same software environment.')
    return {'schema_version': 1, 'claim': 'Frozen recorded runs; paper identity must be established against original paper outputs.',
            'flash_jar_sha256': sha256(jar), 'runs': runs,
            'files': [{'relative_path': relative, 'bytes': path.stat().st_size, 'sha256': sha256(path)}
                      for relative, path in sorted(files.items())]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, required=True)
    parser.add_argument('--records', type=Path, nargs='+', required=True)
    parser.add_argument('--flash-jar', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        parser.error('Choose a new manifest path; existing manifests are never overwritten.')
    manifest = capture(args.project, args.records, args.flash_jar)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print('Captured', len(manifest['files']), 'files and', len(manifest['runs']), 'recorded analyses.')


if __name__ == '__main__':
    main()
