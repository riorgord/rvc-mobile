pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libxposed-api(LSPosed 模块 API)
        maven("https://api.xposed.info/")
    }
}
rootProject.name = "rvc-app"
include(":app")
