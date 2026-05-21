pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
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
project(":common-utils").projectDir = file("/Users/kwatanabe/work-repo/testbedui-plugins/common-utils")
