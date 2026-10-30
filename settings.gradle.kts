pluginManagement {
    val user = listOf("ARTIFACTORY_CONSUME_USER", "ARTIFACTORY_USER", "TAK_USERNAME")
        .firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
    val password = listOf("ARTIFACTORY_CONSUME_PASSWORD", "ARTIFACTORY_PASSWORD", "TAK_PASSWORD")
        .firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
    logger.lifecycle("TAK plugin repository credentials enabled: ${user != null && password != null}")
    repositories {
        if (user != null && password != null) {
            maven {
                url = uri("https://artifacts.tak.gov/artifactory/maven-release")
                credentials {
                    username = user
                    this.password = password
                }
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    val user = listOf("ARTIFACTORY_CONSUME_USER", "ARTIFACTORY_USER", "TAK_USERNAME")
        .firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
    val password = listOf("ARTIFACTORY_CONSUME_PASSWORD", "ARTIFACTORY_PASSWORD", "TAK_PASSWORD")
        .firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
    logger.lifecycle("TAK dependency repository credentials enabled: ${user != null && password != null}")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (user != null && password != null) {
            maven {
                url = uri("https://artifacts.tak.gov/artifactory/maven-release")
                credentials {
                    username = user
                    this.password = password
                }
            }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "weartak-plugin-tak-cad"
include(":app")
