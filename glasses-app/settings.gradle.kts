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
    }
}

rootProject.name = "WornHello"
include(":app")
include(":demo")
include(":pixel")
include(":translator")
include(":viewfinder")
include(":link")
include(":launcher")
include(":ask")
include(":run")
include(":hub")
