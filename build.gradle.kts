// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.multiplatform.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.detekt)
}

/**
 * Kotlin static analysis, to match the oxlint pass the two TypeScript packages already run.
 *
 * Blocking, because the baselines make that safe. `config/detekt/baseline-<module>.xml` holds the
 * 149 findings the tree already had, so a failure here is always something this change
 * introduced, never a backlog nobody asked for. A non-blocking linter in CI is a linter nobody
 * reads.
 *
 * Two ways to deal with a new finding: fix it, or — if it is a rule that is wrong for this
 * codebase the way `FunctionNaming` is for Compose — turn the rule off in `detekt.yml` with the
 * reason written next to it. Adding to a baseline is the last resort, not the first.
 *
 * No type resolution: it needs a compiled classpath per Kotlin target, which on a multiplatform
 * module means building everything before linting anything. The rules that need it are off.
 */
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        parallel = true
        buildUponDefaultConfig = true
        ignoreFailures = false
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        // Per project, not one shared file: `detektBaseline` writes the baseline for the project
        // it ran in, so a single shared path has each module overwrite the previous module's.
        baseline = rootProject.file("config/detekt/baseline-${project.name}.xml")
        // Paths in the baseline are relative to the repo root, so it matches on any machine.
        basePath = rootProject.projectDir.absolutePath
    }

    // A multiplatform module has no single "main" source set for detekt to infer, so both the
    // analysis task and the baseline task are pointed at `src` explicitly. They need the same
    // source or the baseline is written from a different set of files than the check reads —
    // which is how `:composeApp:detektBaseline` silently came out NO-SOURCE.
    tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
        setSource(files("src"))
        include("**/*.kt", "**/*.kts")
        exclude("**/build/**", "**/generated/**")
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        // Source sets, not compiled output: this runs without building the Kotlin targets first.
        setSource(files("src"))
        include("**/*.kt", "**/*.kts")
        exclude("**/build/**", "**/generated/**")
        reports {
            html.required.set(true)
            xml.required.set(false)
            sarif.required.set(true)
            md.required.set(false)
            txt.required.set(false)
        }
    }
}
