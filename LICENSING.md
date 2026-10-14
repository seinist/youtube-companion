# Licensing YouTube Companion

SPDX-License-Identifier: GPL-3.0-or-later

YouTube Companion's original project code and documentation are licensed under
the GNU General Public License as published by the Free Software Foundation,
either version 3 of the License, or (at your option) any later version.
The full license text is in [LICENSE](LICENSE).
Copyright remains with the respective authors and contributors.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the GNU General Public License for more details.

This grant covers material for which the project authors hold the necessary
rights. Third-party code, binaries, artwork and other materials retain their
own licenses and notices. It does not relicense the bundled Android Auto SDK,
YouTube content or third-party trademarks. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Use and redistribution

Private use and private modifications do not require publication. Commercial
use and redistribution are permitted under the GPL; this is not a
non-commercial-only license. When distributing a covered combined or modified
work, preserve the GPL freedoms and required notices, identify modifications,
and comply with the corresponding-source requirements of GPL sections 4–6.

For an APK release, provide the complete corresponding source for that exact
build, including modifications and the build scripts needed to build it, using
one of the methods permitted by GPL section 6. An unrelated branch or a link to
the latest source is not a substitute for the matching source. Do not include
private signing keys, credentials or local machine configuration.

## Release prerequisites

- Resolve the redistribution rights, source availability and GPL compatibility
  of `car/libs/aauto.aar` before distributing a build containing it. Its presence
  in another public repository is not proof of permission.
- Review the actual resolved dependency graph, including transitive libraries,
  and retain applicable copyright, license and NOTICE texts. The direct-library
  inventory is a starting point, not a complete binary compliance audit.
- Include required license and attribution materials with the distributed app.
  `app/build.gradle.kts` currently excludes several `META-INF/LICENSE` and
  `META-INF/NOTICE` resources. A repository notice alone does not establish that
  the APK contains all required notices; audit the final artifact and supply
  those notices through an appropriate packaged resource or license screen.
- Ship the GPL text and matching source access information with releases.

Adding this project license does not resolve third-party licensing issues or
establish permission from YouTube, Google or Android Auto for service access,
branding or distribution.
