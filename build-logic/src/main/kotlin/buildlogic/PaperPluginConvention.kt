package buildlogic

import org.gradle.api.provider.Property

/**
 * Per-plugin knobs for `framework.paper-plugin`.
 *
 * `relocationBase` is the namespace all shaded dependencies (and the framework
 * itself) are relocated into; defaults to `<group>.<modulename>.libs`.
 */
interface PaperPluginConvention {
    val relocationBase: Property<String>
}
