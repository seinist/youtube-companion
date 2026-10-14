# Third-party components

Third-party components retain their own copyright and license terms. The
project's GPL-3.0-or-later grant does not replace these terms.

## Direct runtime dependencies

Versions are declared in `app/build.gradle.kts` and `car/build.gradle.kts`.
This inventory covers direct library families, not every transitive dependency
or every file included in a release APK.

| Component | License | Upstream license / source |
| --- | --- | --- |
| NewPipeExtractor v0.26.5 | GPL-3.0-or-later | [Versioned license declaration](https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.5#license), [source](https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.5) |
| AndroidX, including Media3, media, security-crypto and UI libraries | Apache-2.0 | [AndroidX license](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt) |
| Material Components for Android | Apache-2.0 | [License and source](https://github.com/material-components/material-components-android/blob/master/LICENSE) |
| OkHttp | Apache-2.0 | [License and source](https://github.com/square/okhttp/blob/master/LICENSE.txt) |
| Kotlin coroutines | Apache-2.0 | [License and source](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt) |
| Bundled Android Auto OEM SDK (`aauto.aar`) | **Unverified** | [Provenance and unresolved rights](car/libs/README.md) |

The GPL v3 text is included in [LICENSE](LICENSE), and the Apache 2.0 text in
[LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt). Upstream copyright notices and
any applicable NOTICE files must also be preserved; these generic license texts
do not replace component-specific attributions. Upstream links on moving
branches identify the projects; review the exact resolved artifacts for each
release.

JUnit and `org.json:json` are declared as test-only dependencies. Build tools,
the Gradle wrapper, transitive libraries and third-party assets must be assessed
separately when redistributing them; they are not relicensed by this project.

## Android Auto SDK: unresolved licensing

The local binary is documented as originating from
[AndreyPavlenko/Fermata](https://github.com/AndreyPavlenko/Fermata/blob/master/fermata/lib/auto/aauto.aar),
based on [martoreto/aauto-sdk](https://github.com/martoreto/aauto-sdk).
The original upstream currently documents its removal at Google's request.
No applicable license grant for this particular binary has been established by
this review. Do not infer its license from Fermata's repository-level license.

Local binary SHA-256:

```text
99337c3b591ac9670c12b508da38886aedba61dd494f39f5f166f02580ec584b
```

The project GPL declaration is not a redistribution grant for this binary.
Its redistribution rights, corresponding source and compatibility remain a
release prerequisite; see [LICENSING.md](LICENSING.md).
