#!/usr/bin/env python3
"""Run actual feed parser/pager tests with real JSON decoding, JUnit and coroutines.
Requires JDK 17+, Kotlin compiler/stdlib/coroutines, org.json, JUnit4 and Hamcrest.
No Android runtime, live account or network service is simulated by these fixtures.
"""
import argparse
import glob
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compiler-classpath', required=True)
    parser.add_argument('--test-classpath', required=True)
    args = parser.parse_args()
    def expand(value):
        return os.pathsep.join(str(Path(p).resolve()) for part in value.split(os.pathsep)
                               for p in (glob.glob(part) if '*' in part else [part]))
    compiler = expand(args.compiler_classpath)
    cp = compiler + os.pathsep + expand(args.test_classpath)
    root = Path(__file__).resolve().parents[1]
    base = root / 'car/src/main/java/de/uni/youtubeaa/car/hybrid'
    tests = root / 'car/src/test/java/de/uni/youtubeaa/car/hybrid'
    sources = [base / name for name in ['InnerTubeFeedParser.kt', 'FeedPager.kt']]
    names = ['InnerTubeFeedParserTest', 'FeedPagerTest']
    sources += [tests / (name + '.kt') for name in names]
    with tempfile.TemporaryDirectory(prefix='youtube-aa-feed-') as tmp:
        subprocess.run(['java', '-cp', compiler, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                        '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp,
                        '-d', tmp, *map(str, sources)], check=True)
        subprocess.run(['java', '-cp', tmp + os.pathsep + cp, 'org.junit.runner.JUnitCore',
                        *['de.uni.youtubeaa.car.hybrid.' + name for name in names]], check=True)

if __name__ == '__main__':
    main()
