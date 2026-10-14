// Wrapper so :car can depend on the vendored AAR without AGP's
// "direct local .aar file dependencies are not supported when building an AAR" error.
configurations.maybeCreate("default")
artifacts.add("default", file("../car/libs/aauto.aar"))
