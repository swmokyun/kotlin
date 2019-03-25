import com.moowork.gradle.node.yarn.YarnTask

description = "Simple Kotlin/JS tests runner with TeamCity reporter"

plugins {
    id("base")
    id("com.moowork.node") version "1.2.0"
}

val default = configurations.getByName(Dependency.DEFAULT_CONFIGURATION)
val archives = configurations.getByName(Dependency.ARCHIVES_CONFIGURATION)

default.extendsFrom(archives)

dependencies {
    archives(project(":kotlin-test:kotlin-test-js"))
}

node {
    version = "11.9.0"
    download = true
    nodeModulesDir = projectDir
}

tasks {
    "yarn" {
        outputs.upToDateWhen {
            projectDir.resolve("node_modules").isDirectory
        }
    }

    create<YarnTask>("yarnBuild") {
        group = "build"

        dependsOn("yarn")
        setWorkingDir(projectDir)
        args = listOf("build")

        inputs.dir(projectDir.resolve("src"))
        inputs.files(
            projectDir.resolve("cli.ts"),
            projectDir.resolve("nodejs-source-map-support.js"),
            projectDir.resolve("package.json"),
            projectDir.resolve("rollup.config.js"),
            projectDir.resolve("tsconfig.json"),
            projectDir.resolve("yarn.lock")
        )
        outputs.dir(projectDir.resolve("lib"))
    }

    create<Delete>("cleanYarn") {
        group = "build"

        delete = setOf(
            projectDir.resolve("node_modules"),
            projectDir.resolve("lib"),
            projectDir.resolve(".rpt2_cache")
        )
    }

    getByName("clean").dependsOn("cleanYarn")
}

val zip = tasks.create<Zip>("zip") {
    from(projectDir.resolve("lib"))
}

artifacts {
    add(
        "archives",
        zip.archiveFile.get().asFile
    ) {
        builtBy(zip)
    }
}

publish()