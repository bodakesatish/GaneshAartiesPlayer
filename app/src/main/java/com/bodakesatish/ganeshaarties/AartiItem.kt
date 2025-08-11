package com.bodakesatish.ganeshaarties

data class AartiItem(
    val id: Int,
    val title: String,
    val rawResourceId: Int,
    var isChecked: Boolean = false // Default to checked, or false as you prefer
)
