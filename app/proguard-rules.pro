# The guard runs in a Shizuku process that loads GuardService by name, and ShellContext overrides
# hidden framework methods (bindServiceAsUser) that R8 can't see being called. Keep it all as is.
-keep class dev.saikiran.budsfirst.** { *; }

# Hidden framework classes the guard reaches through reflection or compiles against.
-dontwarn android.**
