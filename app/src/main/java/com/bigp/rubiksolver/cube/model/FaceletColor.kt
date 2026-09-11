package com.bigp.rubiksolver.cube.model

/**
 * The six sticker colours of a standard cube.
 *
 * [srgb] is a reference sRGB value used both as the seed palette for colour classification and as
 * the render colour in the 3D view and the 2D net.
 */
enum class FaceletColor(val displayName: String, val srgb: Int) {
    WHITE("White", 0xFFF6F6F4.toInt()),
    YELLOW("Yellow", 0xFFF2D024.toInt()),
    RED("Red", 0xFFD62828.toInt()),
    ORANGE("Orange", 0xFFF77F1B.toInt()),
    BLUE("Blue", 0xFF1B5FD8.toInt()),
    GREEN("Green", 0xFF1FA84B.toInt());

    companion object {
        val ALL: List<FaceletColor> = entries.toList()
    }
}
