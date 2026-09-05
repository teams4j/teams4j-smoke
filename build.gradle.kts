plugins {
    java
    application
    kotlin("jvm") apply false
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenLocal()
        mavenCentral()
    }

    // Java 17 is the floor teams4j claims; compiling at 21 would hide a 21-only signature.
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
    }

    dependencies {
        // Modules are named without versions, so a module missing from the BOM fails to compile.
        add("implementation", platform("io.github.teams4j:teams4j-bom:${property("teams4jVersion")}"))
    }
}
