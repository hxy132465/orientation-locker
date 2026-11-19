pluginManagement {
    repositories {
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 允许初始化脚本/项目注入仓库，但优先使用 settings 中声明的仓库
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
    }
}

rootProject.name = "OrientationLocker"
include(":app")
