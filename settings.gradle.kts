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
rootProject.name = "niap-android-cert-ext"
include(":cert-lib")
include(":cert-manager")
include(":validator-test-app")
include(":agent-test")
include(":cert-test-app")

include(":common-utils")
project(":common-utils").projectDir = file("../testbedui-plugins/common-utils")
