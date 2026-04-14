plugins {
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "com.irislauncher"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
}

javafx {
    version = "21"
    modules = listOf("javafx.controls", "javafx.fxml")
}

application {
    mainClass.set("com.irislauncher.Main")
}

tasks.processResources {
    from(rootDir) {
        include("*.ico", "*.png")
        into("com/irislauncher")
    }
}

tasks.jar {
    archiveFileName.set("iris-launcher.jar")
    manifest {
        attributes["Main-Class"] = "com.irislauncher.Main"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    doLast {
        copy {
            from(archiveFile)
            into(layout.projectDirectory.dir("out"))
        }
    }
}
