pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Scope JitPack to the extractor and its nanojson dependency.
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.teamnewpipe")
                includeGroup("com.github.TeamNewPipe")
            }
        }
    }
}
rootProject.name = "RaagMusic"
include(":app")
// Optional tooling dependencies are resolved only when generating profiles or benchmarking.
if (providers.gradleProperty("performanceTools").orNull == "true") include(":benchmarks")
