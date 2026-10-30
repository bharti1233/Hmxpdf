# Debug builds run R8 only because the dex step requires it (see the debug block in
# app/build.gradle.kts: poi 5.4.1's CleanerUtil needs API 26+ D8 support, R8 removes it).
# Keep debug builds vanilla: no renaming, no optimizer — only what R8 needs to make the
# bytecode dexable, plus the shared keep rules.
-dontobfuscate
-dontoptimize
