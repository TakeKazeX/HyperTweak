package com.takekazex.hypertweak.hook.rules.systemui.island

import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** Resolve the actual native family resource even when a font module removes its system alias. */
internal object IslandCondensedFontSource {
    const val FAMILY = "misans-condensed"
    const val AXES = "'wght' 450, 'wdth' 30"

    fun resolve(config: File, directory: File): File {
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        parser.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        val document = config.inputStream().use(parser::parse)
        val families = document.getElementsByTagName("family")
        val matches = (0 until families.length).map { families.item(it) }
            .filter { it.attributes?.getNamedItem("name")?.nodeValue == FAMILY }
        check(matches.size == 1) { "native condensed family missing or ambiguous" }
        val fonts = matches.single().childNodes
        val files = (0 until fonts.length).map { fonts.item(it) }.filter { it.nodeName == "font" }
            .map { it.textContent.trim() }.distinct()
        check(files.size == 1) { "native condensed font file missing or ambiguous" }
        val name = files.single()
        check(name.isNotBlank() && File(name).name == name && name != "." && name != "..")
        return File(directory, name).canonicalFile.also {
            check(it.parentFile == directory.canonicalFile && it.isFile && it.canRead()) {
                "native condensed font file unavailable"
            }
        }
    }
}
