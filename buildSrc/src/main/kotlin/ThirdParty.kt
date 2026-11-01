import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/** THIRD_PARTY.md: one table per app, rows `| group:name | version | licence |`. */
object ThirdParty {
    /** The `group:name:version` rows under the `## [section]` heading. */
    fun listed(markdown: String, section: String): Set<String> {
        val lines = markdown.lines()
        val start = lines.indexOfFirst { it.trim() == "## $section" }
        require(start >= 0) { "THIRD_PARTY.md has no \"## $section\" section" }
        return lines.drop(start + 1)
            .takeWhile { !it.startsWith("## ") }
            .mapNotNull { row ->
                val cells = row.split('|').map(String::trim)
                if (cells.size >= 4 && ':' in cells[1]) "${cells[1]}:${cells[2]}" else null
            }
            .toSet()
    }

    /** Shipped libraries the section does not list at that version. */
    fun missing(markdown: String, section: String, shipped: Collection<String>): List<String> =
        (shipped.toSet() - listed(markdown, section)).sorted()
}

/** Copies LICENSE and THIRD_PARTY.md into the APK's assets for the About screen. */
abstract class LegalAssets : DefaultTask() {
    @get:InputFiles
    abstract val files: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        files.forEach { it.copyTo(dir.resolve(it.name)) }
    }
}

/** Fails release builds when THIRD_PARTY.md's [section] does not list a library this app ships. */
fun Project.verifyThirdParty(section: String) {
    val list = rootProject.file("THIRD_PARTY.md")
    val verify = tasks.register("verifyThirdParty") {
        inputs.file(list)
        doLast {
            val shipped = configurations.getByName("releaseRuntimeClasspath").incoming.resolutionResult.allComponents
                .mapNotNull { it.id as? ModuleComponentIdentifier }
                .filterNot { it.module.endsWith("-bom") } // platforms ship no code
                .map { "${it.group}:${it.module}:${it.version}" }
            val missing = ThirdParty.missing(list.readText(), section, shipped)
            if (missing.isNotEmpty()) {
                throw GradleException("THIRD_PARTY.md \"$section\" does not list: ${missing.joinToString()}")
            }
        }
    }
    tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verify) }
}
