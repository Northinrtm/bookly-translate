package com.northin.bookly.data.parser

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.select.NodeVisitor

/** Collects elements whose tag is in [tags], in document order (a single pre-order pass). */
internal fun collectElementsInOrder(root: Element, tags: Set<String>): List<Element> {
    val result = mutableListOf<Element>()
    root.traverse(
        object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node is Element && node.tagName().lowercase() in tags) {
                    result.add(node)
                }
            }

            override fun tail(node: Node, depth: Int) = Unit
        },
    )
    return result
}
