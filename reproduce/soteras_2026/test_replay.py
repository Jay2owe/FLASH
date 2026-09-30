"""Focused replay capture/comparison checks using small local files."""
import json
from pathlib import Path
import tempfile
import unittest
from capture_replay import capture
from compare_tables import compare


class ReplayChecks(unittest.TestCase):
    def test_capture_requires_processed_inputs_and_freezes_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            config = project/'FLASH/Config/.settings'
            config.mkdir(parents=True)
            (config/'channel_config.json').write_text('{}')
            (project/'image.tif').write_bytes(b'synthetic image')
            jar = project/'FLASH.jar'
            jar.write_bytes(b'test binary')
            record = {'schemaVersion':1, 'analysisIndex':7, 'projectRoot':str(project),
                      'status':'ok', 'finishedAtMillis':1, 'runId':'synthetic',
                      'flashVersion':'test', 'fijiBuild':'Fiji / 1.54p99', 'jdkVersion':'11.0.31',
                      'parameters':{'defaultMode':'Whole ROI mean'},
                      'inputs':[{'path':str(project/'image.tif'), 'status':'processed'}]}
            path = project/'record.jsonl'
            path.write_text(json.dumps(record)+'\n')
            manifest = capture(project, [path], jar)
            self.assertEqual(manifest['runs'][0]['imagej_version'], '1.54p99')
            self.assertEqual(len(manifest['files']), 2)
            record['inputs'][0]['status'] = 'skipped'
            path.write_text(json.dumps(record)+'\n')
            with self.assertRaisesRegex(ValueError, 'skipped'):
                capture(project, [path], jar)

    def test_comparison_allows_new_run_id_but_detects_changed_measurement(self):
        with tempfile.TemporaryDirectory() as directory:
            a,b = Path(directory)/'a.csv', Path(directory)/'b.csv'
            a.write_text('Animal,IntDen,run_id\nA,100,old\nB,200,old\n')
            b.write_text('Animal,IntDen,run_id\nB,200,new\nA,100,new\n')
            self.assertEqual(compare(a,b), 2)
            b.write_text('Animal,IntDen,run_id\nA,101,new\nB,200,new\n')
            with self.assertRaisesRegex(AssertionError, 'IntDen'):
                compare(a,b)


if __name__ == '__main__':
    unittest.main()
