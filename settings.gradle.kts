pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "ParaReadAndroid"
include(":app", ":core", ":engine:llama")
// Opt in to desktop checks; normal Android builds do not configure the host native toolchain.
if (providers.gradleProperty("hostLlama").orNull == "true") include(":tools:host-llama")
