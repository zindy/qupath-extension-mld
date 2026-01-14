plugins {
    // To optionally create a shadow/fat jar that bundle up any non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
}


// TODO: Configure your extension here (please change the defaults!)
qupathExtension {
    name = "qupath-extension-mld"
    group = "io.github.qupath"
    version = "0.1.0-SNAPSHOT"
    description = "A simple QuPath extension"
    automaticModule = "io.github.qupath.extension.mld"
}

// TODO: Define your dependencies here
dependencies {

    // Main dependencies for most QuPath extensions
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)

    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)

    implementation("io.github.qupath:qupath-extension-openslide:0.6.0-rc4")
}
